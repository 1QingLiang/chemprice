package com.datamarket.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 价格数据导出服务（供 AI 智能问答的「数据导出」能力使用）。
 * <p>
 * 设计要点：
 * 1. 导出范围**硬上限 7 天**（服务端夹紧，前端传再大也没用）；
 * 2. 生成的是真正的 .xlsx（EasyExcel，多工作表：市场价 / 企业价 / 国际价）；
 * 3. 文件不放在 Web 根目录，浏览器通过**带签名与有效期的令牌**下载，
 *    令牌用 jwt.secret 做 HMAC-SHA256，过期或篡改一律拒绝；
 * 4. 每次生成顺手清理超过 2 天的旧文件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportService {

    /** 导出范围硬上限（天） */
    public static final int MAX_DAYS = 7;

    /** 单次导出行数硬上限，防止极端品种把内存打爆 */
    private static final int MAX_ROWS_PER_SHEET = 60000;

    private final JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${chemprice.export.dir:/home/ubuntu/chemprice/exports}")
    private String exportDir;

    /** 令牌有效期（秒） */
    private static final long TOKEN_TTL_SECONDS = 30 * 60;

    /** 一个工作表的定义 */
    private record SheetDef(String table, String sheetName, List<String> cols, List<String> heads) {
    }

    private static final SheetDef MARKET = new SheetDef(
            "market_price", "市场价",
            List.of("varieties_name", "market_name", "region_name", "specifications_name", "brand_name",
                    "price_type_name", "middle_price", "low_price", "high_price", "unit_valuation_name",
                    "data_rise_or_fall", "data_rate", "data_date"),
            List.of("商品", "报价点", "区域", "规格", "品牌", "报价方式",
                    "主流价", "最低价", "最高价", "单位", "涨跌额", "涨跌幅", "数据日期"));

    private static final SheetDef ENTERPRISE = new SheetDef(
            "enterprise_price", "企业价",
            List.of("varieties_name", "market_name", "region_name", "specifications_name", "brand_name",
                    "price_type_name", "middle_price", "low_price", "high_price", "unit_valuation_name",
                    "data_rise_or_fall", "data_rate", "data_date"),
            List.of("商品", "报价点", "区域", "规格", "品牌", "报价方式",
                    "主流价", "最低价", "最高价", "单位", "涨跌额", "涨跌幅", "数据日期"));

    private static final SheetDef INTL = new SheetDef(
            "international_price", "国际价",
            List.of("varieties_name", "market_name", "specifications_name", "price_type_name",
                    "middle_price", "low_price", "high_price", "unit_valuation_name",
                    "calc_rmb_price", "fx_used_currency", "data_rise_or_fall", "data_rate", "data_date"),
            List.of("商品", "报价点", "规格", "报价方式",
                    "主流价", "最低价", "最高价", "单位",
                    "折合人民币", "折算币种", "涨跌额", "涨跌幅", "数据日期"));

    /** 导出结果 */
    public record Result(String token, String fileName, int rows, String startDate, String endDate, int days,
                         boolean truncated) {
    }

    /**
     * 生成某个品种近 N 天的 Excel。
     *
     * @param varietiesId 已通过产品权限校验的品种 id
     * @param varietyName 品种名（用于文件名）
     * @param days        天数，服务端夹紧到 1..7
     */
    public Result create(int varietiesId, String varietyName, int days) throws Exception {
        int d = Math.max(1, Math.min(MAX_DAYS, days));
        boolean truncatedAny = false;

        Date maxDate = jdbcTemplate.queryForObject(
                "SELECT MAX(data_date) FROM market_price", Date.class);
        if (maxDate == null) {
            throw new IllegalStateException("价格库暂无数据，无法导出。");
        }
        Date start = Date.valueOf(maxDate.toLocalDate().minusDays(d - 1L));
        Date end = maxDate;

        Path dir = Paths.get(exportDir);
        Files.createDirectories(dir);

        String uuid = UUID.randomUUID().toString().replace("-", "");
        Path file = dir.resolve(uuid + ".xlsx");

        int total = 0;
        try {
            ExcelWriter writer = EasyExcel.write(file.toFile()).build();
            try {
                int idx = 0;
            for (SheetDef def : List.of(MARKET, ENTERPRISE, INTL)) {
                String sql = "SELECT " + String.join(", ", def.cols())
                        + " FROM " + def.table()
                        + " WHERE varieties_id = ? AND middle_price IS NOT NULL"
                        + " AND data_date >= ? AND data_date <= ?"
                        + " ORDER BY data_date, market_name LIMIT " + MAX_ROWS_PER_SHEET;
                // 数据量检测：该表在区间内的总行数超过单表上限时置位，由调用方提示用户（不静默截断）
                try {
                    Long cnt = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM " + def.table()
                                    + " WHERE varieties_id = ? AND middle_price IS NOT NULL"
                                    + " AND data_date >= ? AND data_date <= ?",
                            Long.class, varietiesId, start, end);
                    if (cnt != null && cnt > MAX_ROWS_PER_SHEET) {
                        truncatedAny = true;
                    }
                } catch (Exception ignored) {
                    // 统计失败不影响导出主流程
                }
                List<Map<String, Object>> raw;
                try {
                    raw = jdbcTemplate.queryForList(sql, varietiesId, start, end);
                } catch (Exception e) {
                    log.warn("导出跳过工作表 {}: {}", def.table(), e.getMessage());
                    continue;
                }
                if (raw.isEmpty()) {
                    idx++;
                    continue;
                }
                List<List<String>> head = def.heads().stream()
                        .map(List::of).collect(Collectors.toList());
                List<List<Object>> rows = new ArrayList<>(raw.size());
                for (Map<String, Object> r : raw) {
                    List<Object> line = new ArrayList<>(def.cols().size());
                    for (String c : def.cols()) {
                        line.add(cellValue(r.get(c)));
                    }
                    rows.add(line);
                }
                WriteSheet sheet = EasyExcel.writerSheet(idx++, def.sheetName() + "(" + rows.size() + ")")
                        .head(head).build();
                writer.write(rows, sheet);
                total += rows.size();
                }
            } finally {
                try {
                    writer.finish();
                } catch (Exception ignored) {
                    // finish 失败也要继续走下面的清理逻辑
                }
            }
        } catch (Exception e) {
            // 写失败时不要把半成品/0 字节文件留在目录里
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // 删除失败不影响向上抛错
            }
            throw e;
        }

        if (total == 0) {
            Files.deleteIfExists(file);
            throw new IllegalStateException("EMPTY");
        }

        String safeName = (varietyName == null ? "商品" : varietyName.replaceAll("[\\\\/:*?\"<>|]", "_"));
        String fileName = safeName + "_" + def(start) + "_" + def(end) + ".xlsx";
        String token = makeToken(uuid, fileName);
        cleanOldFiles(dir);
        return new Result(token, fileName, total, start.toString(), end.toString(), d, truncatedAny);
    }

    private static String def(Date d) {
        return d.toLocalDate().toString().replace("-", "");
    }

    /**
     * 单元格取值：EasyExcel 的动态表头写入不认 java.sql.Date（报
     * "Can not find 'Converter' support class Date"），所以日期统一转字符串；
     * BigDecimal 去掉多余的尾随零，避免出现 6900.0000 这种观感。
     */
    private static Object cellValue(Object v) {
        if (v == null) return "";
        if (v instanceof java.util.Date dt) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format(dt);
        }
        if (v instanceof java.math.BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        return v;
    }

    /* ================= 令牌 ================= */

    private String makeToken(String uuid, String fileName) {
        long exp = System.currentTimeMillis() / 1000 + TOKEN_TTL_SECONDS;
        String payload = exp + "|" + uuid + "|" + fileName;
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return b64 + "." + hmac(payload);
    }

    /**
     * 校验令牌并解析出文件名（含 uuid）。
     *
     * @return [uuid, fileName]，非法/过期返回 null
     */
    public String[] verifyToken(String token) {
        if (token == null) return null;
        int dot = token.lastIndexOf('.');
        if (dot <= 0) return null;
        String b64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        if (!hmac(payload).equals(sig)) return null;
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) return null;
        try {
            if (Long.parseLong(parts[0]) < System.currentTimeMillis() / 1000) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{parts[1], parts[2]};
    }

    public Path fileOf(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-f]{32}")) return null;
        Path p = Paths.get(exportDir).resolve(uuid + ".xlsx").normalize();
        if (!p.startsWith(Paths.get(exportDir).normalize())) return null;
        return Files.exists(p) ? p : null;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(out, 18));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    /** 清理 2 天前的导出文件（避免磁盘堆积） */
    private void cleanOldFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            long deadline = System.currentTimeMillis() - 2L * 24 * 3600 * 1000;
            s.filter(p -> p.toString().endsWith(".xlsx")).forEach(p -> {
                try {
                    File f = p.toFile();
                    if (f.lastModified() < deadline) Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响主流程
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }
}
