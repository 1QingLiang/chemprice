package com.datamarket.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 化工英文物性/安全文本 -> 中文 的本地术语翻译层。
 *
 * <p>设计原则：
 * <ol>
 *   <li><b>数值/单位优先剥离</b>：先抽出「数值 + 单位 + 温度」，剩余说明文字再过词典，
 *       避免词典把 "52 °F" 里的字符改坏。</li>
 *   <li><b>长句绝不硬翻</b>：命中不了词典的长句，返回「整句中文概括 + 原文保留」，
 *       由前端折叠展示原文，避免出现机器翻译的生硬中文。</li>
 *   <li><b>全静态无状态</b>：纯函数，便于缓存与并发。</li>
 * </ol>
 */
public final class ChemZh {

    private ChemZh() {
    }

    // ---------------------------------------------------------------- 单位与温度

    /** 华氏度 -> 摄氏度（无小数，四舍五入） */
    public static double f2c(double f) {
        return Math.round((f - 32) * 5.0 / 9.0 * 10.0) / 10.0;
    }

    private static final Pattern TEMP_F = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*(?:°\\s*)?(?:deg\\s*)?F\\b|(-?\\d+(?:\\.\\d+)?)\\s*°F",
            Pattern.CASE_INSENSITIVE);

    /** 华氏度写法：支持 "52 °F" / "52°F" / "52 F" / "52 deg F" */
    private static final Pattern F_PAT = Pattern.compile(
            "(-?\\d+(?:\\.\\d+)?)\\s*(?:°\\s*|deg\\s*)?F(?:ahrenheit)?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 把文本里的 °F 转成中文摄氏度。
     * 例："52 °F" -> "11 ℃"；"180 °F at 760 mmHg" -> "82 ℃ at 760 mmHg"
     * 规则：一律取整 —— 物性/安全表里的摄氏度小数位没有实际意义（原始 °F 精度本就很粗）。
     */
    public static String temp(String s) {
        if (s == null) return null;
        Matcher m = F_PAT.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            double f = Double.parseDouble(m.group(1));
            sb.append(s, last, m.start());
            sb.append(Math.round(f2c(f))).append(" ℃");
            last = m.end();
        }
        if (!any) return s;
        sb.append(s.substring(last));
        return sb.toString();
    }

    /** 数值美化：整数不带小数点 */
    public static String fmt(double d) {
        if (Math.abs(d - Math.rint(d)) < 0.001) return String.valueOf((long) Math.rint(d));
        String t = String.format("%.1f", d);
        if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);
        return t;
    }

    // ---------------------------------------------------------------- 文献出处剔除

    private static final Pattern CITATION = Pattern.compile(
            "\\(\\s*(?:NTP|USCG|HSDB|ACGIH|NIOSH|EPA|IARC|OSHA|DOT|NFPA|MSDS|SRP|CAMEO|Merck|Budavari|"
            + "Lewis|Lide|Clayton|Sax|Grant|Hawley|Patty|Kirk|Ullmann)[^)]{0,160}\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CITATION2 = Pattern.compile(
            "\\b[12][089]\\d\\d\\b|\\b20[12]\\d\\b(?=\\s*[,)]|\\s*$)|\\b(?:NTP|USCG|HSDB|ACGIH|NIOSH)\\b(?!\\s*[a-z])",
            Pattern.CASE_INSENSITIVE);

    /** 剔除 "(NTP, 1992)"、"(USCG, 1999)" 这类文献标注，中文页里没有意义 */
    public static String stripCitation(String s) {
        if (s == null) return null;
        String t = CITATION.matcher(s).replaceAll(" ");
        t = t.replaceAll("\\s*[,;]\\s*(?=[)）])", "");
        t = t.replaceAll("\\s{2,}", " ").replaceAll("^[\\s,;·]+", "").replaceAll("[\\s,;·]+$", "");
        return t.trim();
    }

    // ---------------------------------------------------------------- 短语词典

    /**
     * 长短语优先替换（按 key 长度倒序在 build 阶段排好）。
     * 只做「术语级」替换，不做语法翻译。
     */
    private static final Map<String, String> PHRASES = new LinkedHashMap<>();

    private static void p(String en, String cn) {
        PHRASES.put(en.toLowerCase(), cn);
    }

    static {
        p("water soluble", "可溶于水");
        p("water-soluble", "可溶于水");
        p("soluble in water", "溶于水");
        p("insoluble in water", "不溶于水");
        p("soluble in water in all proportions", "可与水以任意比例互溶");
        p("soluble in all proportions", "可以任意比例互溶");
        p("fireproof", "防火");
        p("fire-proof", "防火");
        p("separated from incompatible materials", "与禁配物隔离存放");
        p("keep in well closed containers in a cool place", "储存于密闭容器中，置于阴凉处");
        p("keep in well closed containers in a cool place and away from fire", "储存于密闭容器中，置于阴凉处，远离火源");
        p("away from fire", "远离火源");
        p("away from heat", "远离热源");
        p("away from sources of ignition", "远离火源");
        p("away from ignition sources", "远离火源");
        p("away from", "远离");
        p("keep in the dark", "避光保存");
        p("store only if stabilized", "仅在已稳定化处理后方可储存");
        p("store in an area without drain or sewer access", "储存区不得有排水口或下水道接口");
        p("separated from incompatible materials", "与禁配物隔离存放");
        p("see chemical dangers", "参见化学危险性说明");
        p("fireproof", "耐火建筑");
        p("store in detached units of noncombustible construction", "储存于独立的不燃结构库房");
        p("keep in well closed containers", "储存于密闭容器中");
        p("keep in well closed container", "储存于密闭容器中");
        p("in a cool place", "阴凉处");
        p("in a cool, dry place", "阴凉干燥处");
        p("noncombustible construction", "不燃结构");
        p("noncombustible", "不燃");
        p("non-combustible", "不燃");
        p("detached units", "独立库房");
        p("store in detached units", "储存于独立库房");
        p("the finely powdered resin is a significant dust hazard",
                "该树脂细粉存在显著粉尘爆炸危险");
        p("is a significant dust hazard", "存在显著粉尘爆炸危险");
        p("dust hazard", "粉尘爆炸危险");
        p("may gradually develop slight odor of ammonia", "可能逐渐产生轻微氨味");
        p("develop slight odor of ammonia", "产生轻微氨味");
        p("slight odor of ammonia", "轻微氨味");
        p("mild phenolic odor", "微弱酚类气味");
        p("phenolic odor", "酚类气味");
        p("characteristic, sweet, balsamic, almost floral", "特征性甜香、香脂样、略带花香");
        p("characteristic, sweet, balsamic", "特征性甜香、香脂样");
        p("unpleasant odor", "不愉快气味");
        p("highly flammable", "高度易燃");
        p("soluble in water in all proportions", "可与水以任意比例互溶");
        p("in all proportions", "以任意比例");
        p("very soluble in water in all proportions", "可与水以任意比例互溶");
        p("balsamic", "香脂样");
        p("floral", "花香");

        // —— 色泽 / 形态
        p("fat soluble", "可溶于油脂");
        p("cause decomposition to", "可分解生成**");
        p("cause decomposition", "引起分解");
        p("causes decomposition", "引起分解");
        p("can cause", "可引起**");
        p("may cause", "可能引起**");
        p("cause", "引起**");
        p("causes", "造成**");
        p("cause severe", "造成严重**");
        p("resulting in", "导致**");
        p("reacts vigorously with", "与**剧烈反应");
        p("vigorous reaction", "剧烈反应");
        p("violent reaction", "剧烈反应");
        p("heat may cause", "受热可能引起**");
        p("may be ignited", "可被点燃");
        p("must be grounded", "必须接地");
        p("dangerous when wet", "遇湿危险");
        p("keep out of water", "避免接触水");
        p("do not use water", "禁止用水**");
        p("use dry chemical", "使用干粉**");
        p("carbon dioxide", "二氧化碳");
        p("dry chemical", "干粉");
        p("alcohol foam", "抗醇泡沫");
        p("water spray", "喷雾水");
        p("fighting", "灭火");
        p("fire", "火灾");

        // —— 色泽 / 形态
        p("colorless liquid", "无色液体");
        p("colourless liquid", "无色液体");
        p("colorless solid", "无色固体");
        p("colourless solid", "无色固体");
        p("colorless gas", "无色气体");
        p("white crystalline solid", "白色结晶固体");
        p("white crystalline powder", "白色结晶粉末");
        p("white solid", "白色固体");
        p("white powder", "白色粉末");
        p("crystalline solid", "结晶固体");
        p("crystalline powder", "结晶粉末");
        p("oily liquid", "油状液体");
        p("mobile liquid", "易流动液体");
        p("volatile liquid", "挥发性液体");
        p("hygroscopic", "易吸湿");
        p("deliquescent", "易潮解");
        p("monoclinic", "单斜晶系");
        p("orthorhombic", "斜方晶系");
        p("liquid", "液体");
        p("solid", "固体");
        p("gas", "气体");
        p("powder", "粉末");
        p("crystals", "晶体");

        // —— 气味
        p("camphor-like odor", "樟脑样气味");
        p("camphor odor", "樟脑气味");
        p("pungent odor", "刺激性气味");
        p("pungent", "刺激性");
        p("mild odor", "微弱气味");
        p("faint odor", "微弱气味");
        p("slight odor", "轻微气味");
        p("alcohol odor", "醇类气味");
        p("sweet odor", "甜味气味");
        p("characteristic odor", "特征性气味");
        p("aromatic odor", "芳香气味");
        p("ether-like odor", "醚样气味");
        p("irritating odor", "刺激性气味");
        p("odorless", "无气味");
        p("no odor", "无气味");

        // —— 溶解性
        p("greater than or equal to", "≥");
        p("miscible with water", "与水混溶");
        p("miscible with most organic solvents", "与多数有机溶剂混溶");
        p("miscible", "可混溶");
        p("insoluble in water", "不溶于水");
        p("soluble in water", "溶于水");
        p("slightly soluble in water", "微溶于水");
        p("very soluble in water", "易溶于水");
        p("freely soluble", "易溶");
        p("slightly soluble", "微溶");
        p("sparingly soluble", "难溶");
        p("practically insoluble", "几乎不溶");
        p("insoluble", "不溶");
        p("soluble", "可溶");
        p("volatile with steam", "随水蒸气挥发");

        // —— 密度 / 相对密度
        p("less dense than water; will float", "密度小于水，浮于水面");
        p("less dense than water", "密度小于水");
        p("denser than water; will sink", "密度大于水，下沉");
        p("denser than water", "密度大于水");
        p("relative density", "相对密度");
        p("at 20 deg c", "20 ℃ 时");
        p("at 25 deg c", "25 ℃ 时");
        p("at 68 °f", "20 ℃ 时");
        p("at 78.8 °f", "26 ℃ 时");
        p("at 760 mmhg", "760 mmHg 时");
        p("mmhg", "mmHg");
        p("kpa", "kPa");

        // —— 危险/安全通用
        p("highly flammable", "高度易燃");
        p("extremely flammable", "极易燃");
        p("flammable", "易燃");
        p("combustible", "可燃");
        p("nonflammable", "不燃");
        p("non-flammable", "不燃");
        p("oxidizing materials", "氧化性物质");
        p("polymerizing initiators", "聚合引发剂");
        p("polymerization accelerators", "聚合促进剂");
        p("polymerization can occur", "可能发生聚合");
        p("easily oxidized materials", "易被氧化物质");
        p("polyvalent metal salts", "多价金属盐");
        p("unalloyed steel", "非合金钢");
        p("stainless steel", "不锈钢");
        p("ammonium hydroxide", "氢氧化铵");
        p("chloro-sulfonic acid", "氯磺酸");
        p("ethylene diamine", "乙二胺");
        p("2-aminoethanol", "2-氨基乙醇");
        p("monomethyl ether of hydroquinone", "对苯二酚单甲醚");
        p("physical damage", "物理损伤");
        p("deep discoloration", "严重变色");
        p("elevated temperatures", "高温");
        p("well-ventilated", "通风良好");
        p("non-combustible", "不燃");
        p("in order to", "为了");
        p("should be stored", "应储存于");
        p("can be stored", "可储存于");
        p("under no circumstances", "在任何情况下都不得");
        p("does not affect", "不影响");
        p("the presence of water", "水的存在");
        p("temperature range", "温度范围");
        p("a major concern", "一个重要问题");
        p("is required for", "是……所必需的");
        p("can lead to", "可能导致");
        p("may initiate", "可能引发");
        p("only in vessels lined with", "只能使用内衬以下材料的容器：");
        p("oxidizing agents", "氧化剂");
        p("oxidizers", "氧化剂");
        p("oxidizer", "氧化剂");
        p("strong oxidizers", "强氧化剂");
        p("strong oxidizing agents", "强氧化剂");
        p("strong mineral acids", "强无机酸");
        p("mineral acids", "无机酸");
        p("strong acids", "强酸");
        p("strong bases", "强碱");
        p("acid chlorides", "酰氯");
        p("acid anhydrides", "酸酐");
        p("alkalies", "碱类");
        p("alkali metals", "碱金属");
        p("reducing agents", "还原剂");
        p("peroxides", "过氧化物");
        p("organic peroxides", "有机过氧化物");
        p("water", "水");
        p("moisture", "水分");
        p("air", "空气");
        p("heat", "受热");
        p("open flames", "明火");
        p("sparks", "火花");
        p("static discharge", "静电放电");
        p("direct sunlight", "阳光直射");
        p("incompatible with", "禁配物：");
        p("incompatible materials", "禁配物");
        p("incompatible", "禁配");
        p("may react with", "可与**反应");
        p("reacts with", "与**反应");
        p("decomposition", "分解");
        p("decompose", "分解");
        p("polymerize", "聚合");
        p("polymerization", "聚合");
        p("explosive", "爆炸性");
        p("explosion", "爆炸");
        p("explosive mixture", "爆炸性混合物");
        p("ignition", "着火");
        p("ignite", "着火");
        p("ignition temperature", "着火温度");
        p("flash back", "回火");
        p("burn", "燃烧");
        p("corrosive", "腐蚀性");
        p("toxic", "有毒");
        p("harmful", "有害");
        p("carcinogen", "致癌物");
        p("suspected of causing cancer", "疑似致癌");
        p("irritation", "刺激");
        p("respiratory irritation", "呼吸道刺激");
        p("eye irritation", "眼睛刺激");
        p("skin irritation", "皮肤刺激");
        p("serious eye damage", "严重眼损伤");
        p("drowsiness or dizziness", "困倦或头晕");
        p("damage to organs", "器官损害");
        p("fertility or the unborn child", "生育能力或胎儿");
        p("swallowed", "吞咽");
        p("inhaled", "吸入");
        p("vapor", "蒸气");
        p("vapors", "蒸气");
        p("vapour", "蒸气");

        // —— 储存 / 运输
        p("keep container tightly closed in a dry and well-ventilated place",
                "储存于干燥、通风良好的场所，保持容器密闭");
        p("keep container tightly closed", "保持容器密闭");
        p("containers which are opened must be carefully resealed and kept upright to prevent leakage",
                "开启后的容器须重新密封并保持直立，防止泄漏");
        p("keep away from heat", "远离热源");
        p("keep away from sources of ignition", "远离火种");
        p("store in a cool, dry, well-ventilated area", "储存于阴凉、干燥、通风处");
        p("store in a cool, dry place", "储存于阴凉干燥处");
        p("store in a cool place", "储存于阴凉处");
        p("store away from", "远离**储存");
        p("protect from moisture", "防潮");
        p("protect from light", "避光");
        p("avoid contact with", "避免接触**");
        p("avoid inhalation", "避免吸入");
        p("avoid breathing vapors", "避免吸入蒸气");
        p("use personal protective equipment", "使用个体防护装备");
        p("personal protective equipment", "个体防护装备");
        p("ground and bond containers", "容器接地并跨接");
        p("use spark-proof tools", "使用防爆工具");
        p("no person may", "任何人均不得**");
        p("hazardous materials", "危险货物");
        p("dangerous goods", "危险货物");
        p("bulk", "散装");
        p("packaging", "包装");
        p("packing", "包装");
        p("tank car", "罐车");
        p("tank truck", "槽罐车");
        p("tanker", "槽船");
        p("drums", "桶装");
        p("drum", "桶");
        p("cylinder", "钢瓶");
        p("freight container", "货运集装箱");
        p("air transport", "航空运输");
        p("water transport", "水路运输");
        p("road transport", "公路运输");
        p("rail transport", "铁路运输");
        p("by sea", "海运");
        p("by air", "空运");
        p("secure", "妥善固定");
        p("ventilated", "通风");
        p("well-ventilated", "通风良好");
        p("segregation", "隔离");
        p("segregated from", "与**隔离");

        // —— 法规机构
        p("international air transport association", "国际航空运输协会");
        p("dangerous goods regulations", "危险货物运输规则");
        p("international maritime dangerous goods code", "国际海运危险货物规则（IMDG Code）");
        p("international maritime organization", "国际海事组织");
        p("code of federal regulations", "美国联邦法规");
        p("superfund amendments and reauthorization act", "美国超级基金修正与再授权法");
        p("comprehensive environmental response, compensation, and liability act",
                "美国综合环境响应、赔偿与责任法（CERCLA）");
        p("reportable quantity", "应报告量");
        p("emergency planning and community right-to-know act",
                "美国应急计划与社区知情权法（EPCRA）");
        p("threshold planning quantity", "阈值计划量");
        p("clean air act", "美国清洁空气法");
        p("clean water act", "美国清洁水法");
        p("resource conservation and recovery act", "美国资源保护与回收法");
        p("occupational safety and health administration", "美国职业安全与健康管理局（OSHA）");
        p("permissible exposure limit", "容许接触限值");
        p("threshold limit value", "阈限值");
        p("recommended exposure limit", "推荐接触限值");
        p("time-weighted average", "时间加权平均浓度");
        p("immediately dangerous to life or health", "立即威胁生命或健康浓度（IDLH）");
        p("based on", "依据**");
        p("pursuant to", "依据**");
        p("constitute", "构成**");
        p("to be followed by", "须由**遵守");
        p("member airlines", "成员航空公司");
        p("when transporting", "运输**时");
        p("lays down basic principles for transporting hazardous chemicals",
                "规定了运输危险化学品的基本原则");
        p("detailed recommendations", "详细建议");
        p("good practice", "良好操作规范");
        p("a general index of technical names has also been compiled",
                "并编制了技术名称总索引");
        p("should always be consulted", "应始终查阅");
        p("when attempting to locate the appropriate procedures to be used when shipping any substance or article",
                "以确定装运任何物质或物品时应采用的适当程序");

        // —— 常见残留杂词
        p("containers which are opened must be carefully resealed", "开启后的容器须重新密封");
        p("which are opened", "开启后的");
        p("must be", "须");
        p("should be", "应");
        p("has resulted in", "曾导致**");
        p("has been", "已**");
        p("have been", "已**");
        p("resulted in", "导致**");
        p("particularly during early stages of large batches", "尤其在大批量生产初期");
        p("severe explosions", "严重爆炸");
        p("the preparation of", "**的制备");
        p("by addn of", "由**加入");
        p("caused ignition", "引起着火");
        p("contact of", "**接触");
        p("with", "与**");
    }

    /** 按短语长度倒序排列的 key，保证长短语优先命中 */
    private static final List<String> PHRASE_KEYS = new ArrayList<>();

    static {
        List<String> ks = new ArrayList<>(PHRASES.keySet());
        ks.sort((a, b) -> Integer.compare(b.length(), a.length()));
        PHRASE_KEYS.addAll(ks);
    }

    private static final Pattern WORD_BOUND =
            Pattern.compile("[A-Za-z]");

    /** 短语替换：长短语优先；单词也走此表（若单词词典未覆盖） */
    public static String phrase(String s) {
        if (s == null || s.isEmpty()) return s;
        String t = s;
        for (String k : PHRASE_KEYS) {
            if (k.indexOf(' ') < 0) continue;              // 单词走 words() 逻辑，避免误伤
            int idx = indexOfIgnoreCase(t, k);
            if (idx < 0) continue;
            t = replaceIgnoreCase(t, k, PHRASES.get(k));
        }
        return t;
    }

    /** 单个词（含 PHRASES 里的单词，如 sublimes/decomposes）-> 中文 */
    public static String singleWord(String w) {
        if (w == null) return null;
        String k = w.toLowerCase().replaceAll("[.,;:]+$", "");
        String hit = WORDS.get(k);
        if (hit != null) return hit;
        return PHRASES.get(k);
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static String replaceIgnoreCase(String hay, String needle, String rep) {
        StringBuilder sb = new StringBuilder();
        String low = hay.toLowerCase(), nlow = needle.toLowerCase();
        int i = 0;
        while (true) {
            int idx = low.indexOf(nlow, i);
            if (idx < 0) {
                sb.append(hay, i, hay.length());
                break;
            }
            sb.append(hay, i, idx).append(rep);
            i = idx + needle.length();
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 句式模板

    /**
     * 句型级翻译：处理「量词 + 单位 + 说明」这类固定结构。
     * 例："31 mmHg at 68 °F ; 42 mmHg at 77 °F" -> "20 ℃ 时 31 mmHg；25 ℃ 时 42 mmHg"
     */
    private static final Pattern AT_TEMP = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*(?:mmHg|kPa|Pa|mm Hg|bar|hPa))\\s+at\\s+(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*F",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AT_TEMP2 = Pattern.compile(
            "at\\s+(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*F\\s*,?\\s*(\\d+(?:\\.\\d+)?\\s*(?:mmHg|kPa|Pa|bar))",
            Pattern.CASE_INSENSITIVE);

    /** 常压标注："82 ℃ at 760 mmHg" -> "常压（760 mmHg）下 82 ℃" */
    private static final Pattern AT_PRESS = Pattern.compile(
            "^(.{0,40}?)\\s+at\\s+(760\\s*mmhg|101\\.?3?\\s*kpa|1\\s*atm|atmospheric\\s+pressure)\\b\\.?$",
            Pattern.CASE_INSENSITIVE);

    private static String template(String s) {
        String t = s;

        // 0) 常压沸点等："180 °F at 760 mmHg" -> "常压（760 mmHg）下 82 ℃"
        Matcher mp = AT_PRESS.matcher(t);
        if (mp.matches()) {
            String head = temp(mp.group(1).trim());
            String pr = mp.group(2).toUpperCase().replaceAll("\\s+", " ");
            String prCn = pr.contains("MMHG") ? "760 mmHg"
                    : (pr.contains("ATM") ? "常压" : "101.3 kPa");
            return "常压（" + prCn + "）下 " + head;
        }

        // 31 mmHg at 68 °F  ->  20 ℃ 时 31 mmHg
        Matcher m1 = AT_TEMP.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m1.find()) {
            double c = f2c(Double.parseDouble(m1.group(2)));
            m1.appendReplacement(sb, Matcher.quoteReplacement(
                    Math.round(c) + " ℃ 时 " + m1.group(1).replaceAll("\\s+", " ")));
        }
        m1.appendTail(sb);
        t = sb.toString();

        Matcher m2 = AT_TEMP2.matcher(t);
        sb = new StringBuffer();
        while (m2.find()) {
            double c = f2c(Double.parseDouble(m2.group(1)));
            m2.appendReplacement(sb, Matcher.quoteReplacement(
                    Math.round(c) + " ℃ 时 " + m2.group(2).replaceAll("\\s+", " ")));
        }
        m2.appendTail(sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 句型模板（应对长句）

    /** "X at NN °F - 说明" -> "NN ℃ 时 X（说明）" */
    /** "0.792 at 68 °F - Less dense than water"（°F 已被 temp() 换成 ℃，两种都认） */
    private static final Pattern DENSITY_TPL = Pattern.compile(
            "^([0-9.]+)\\s+(?:at|@)\\s+(-?[0-9.]+)\\s*(?:°?\\s*F|℃|°C)\\s*[-–—]\\s*(.+)$");

    /** "Strong mineral acids can cause decomposition to XXX" -> "强无机酸可分解生成 XXX" */
    private static final Pattern CAUSE_DECOMP = Pattern.compile(
            "^(.+?)\\s+can\\s+cause\\s+decomposition\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** "Contact of A with B caused ignition." -> "A 与 B 接触会引起着火。" */
    private static final Pattern CONTACT_IGNITE = Pattern.compile(
            "^Contact\\s+of\\s+(.+?)\\s+with\\s+(.+?)\\s+caused\\s+ignition\\.?$", Pattern.CASE_INSENSITIVE);

    /** "The preparation of X by ... has resulted in severe explosions..." */
    private static final Pattern PREP_EXPLODE = Pattern.compile(
            "^The\\s+preparation\\s+of\\s+(.+?)\\s+by\\s+adding\\s+(.+?)\\s+to\\s+(.+?)\\s+has\\s+resulted\\s+in\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    /** "X is incompatible with Y" / "Incompatible with Y" */
    private static final Pattern INCOMPAT = Pattern.compile(
            "^(?:Incompatible\\s+with|X\\s+is\\s+incompatible\\s+with)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** "React with / reacts with" */
    private static final Pattern REACTS_WITH = Pattern.compile(
            "^(.+?)\\s+reacts?\\s+(?:vigorously\\s+)?with\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** "Can react vigorously with oxidizing materials." */
    private static final Pattern CAN_REACT = Pattern.compile(
            "^can\\s+react\\s+(violently|vigorously|readily|slowly)?\\s*with\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);

    /** "Incompatible materials: X, Y, Z" */
    private static final Pattern INCOMPAT_MAT = Pattern.compile(
            "^(?:incompatible\\s+materials|incompatible\\s+with)\\s*:?\\s*(.+?)\\.?$", Pattern.CASE_INSENSITIVE);

    /** 无主语的祈使/陈述式："REACTS VIOLENTLY WITH GALLIUM PERCHLORATE." */
    private static final Pattern REACTS_ONLY = Pattern.compile(
            "^reacts?\\s+(?:violently|vigorously|readily|slowly|rapidly)?\\s*with\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);

    /** "Strong oxidizers, many fluorides & perchlorates, nitric acid" —— 纯物质清单（含 & 与 many） */
    private static final Pattern OXID_LIST = Pattern.compile(
            "^(strong\\s+)?(oxidizers?|oxidants?|oxidizing\\s+agents?)\\s*[,，]\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /** 化学物质名 -> 中文（用在句型模板里） */
    private static final Map<String, String> CHEMS = new LinkedHashMap<>();

    private static void c(String en, String cn) {
        CHEMS.put(en.toLowerCase(), cn);
    }

    static {
        c("acids", "酸类");
        c("acid", "酸");
        c("strong acids", "强酸");
        c("mineral acids", "无机酸");
        c("organic acids", "有机酸");
        c("alkali metals", "碱金属");
        c("alkaline earth metals", "碱土金属");
        c("reducing agents", "还原剂");
        c("oxidizing materials", "氧化性物质");
        c("combustible materials", "可燃物");
        c("organic materials", "有机物");
        c("gallium perchlorate", "高氯酸镓");
        c("perchlorate", "高氯酸盐");
        c("perchlorates", "高氯酸盐");
        c("chlorate", "氯酸盐");
        c("chlorates", "氯酸盐");
        c("fluoride", "氟化物");
        c("fluorides", "氟化物");
        c("bromide", "溴化物");
        c("bromides", "溴化物");
        c("iodide", "碘化物");
        c("iodides", "碘化物");
        c("chloride", "氯化物");
        c("chlorides", "氯化物");
        c("sodium hypochlorite", "次氯酸钠");
        c("calcium hypochlorite", "次氯酸钙");
        c("hypochlorite", "次氯酸盐");
        c("chloramines", "氯胺");
        c("chloramine", "氯胺");
        c("oxidant", "氧化剂");
        c("oxidants", "氧化剂");
        c("oxidizers", "氧化剂");
        c("oxidizing agents", "氧化剂");
        c("reducing agents", "还原剂");
        c("nitrogen trichloride", "三氯化氮");
        c("iodine pentafluoride", "五氟化碘");
        c("iodine heptafluoride", "七氟化碘");
        c("dioxygenyl tetrafluoroborate", "四氟硼酸二氧基");
        c("alkyl perchlorates", "烷基高氯酸盐");
        c("potassium sodium alloy", "钾钠合金");
        c("sodium potassium alloy", "钠钾合金");
        c("tert-butanol", "叔丁醇");
        c("tertiary butyl alcohol", "叔丁醇");
        c("tert-butyl alcohol", "叔丁醇");
        c("hydrogen peroxide", "过氧化氢");
        c("sulfuric acid", "硫酸");
        c("sulphuric acid", "硫酸");
        c("nitric acid", "硝酸");
        c("hydrochloric acid", "盐酸");
        c("strong hydrochloric acid", "浓盐酸");
        c("sodium hydroxide", "氢氧化钠");
        c("caustic soda", "氢氧化钠（烧碱）");
        c("potassium hydroxide", "氢氧化钾");
        c("ammonia", "氨");
        c("isobutylene", "异丁烯");
        c("isobutylene gas", "异丁烯气体");
        c("flammable isobutylene gas", "易燃的异丁烯气体");
        c("severe explosions", "严重爆炸");
        c("severe explosion", "严重爆炸");
        c("explosions", "爆炸");
        c("explosion", "爆炸");
        c("ignition", "着火");
        c("decomposition", "分解");
        c("water soluble", "可溶于水");
        c("soluble in water", "溶于水");
        c("insoluble in water", "不溶于水");
        c("highly flammable", "高度易燃");
        c("extremely flammable", "极易燃");
        c("di tertiary butyl peroxide", "二叔丁基过氧化物");
        c("di-tert-butyl peroxide", "二叔丁基过氧化物");
        c("organic peroxides", "有机过氧化物");
        c("peroxides", "过氧化物");
        c("oxidizing materials", "氧化性物质");
        c("oxidizing agents", "氧化剂");
        c("strong oxidizers", "强氧化剂");
        c("strong oxidizing agents", "强氧化剂");
        c("acid chlorides", "酰氯");
        c("acid anhydrides", "酸酐");
        c("alkalies", "碱类");
        c("alkali metals", "碱金属");
        c("reducing agents", "还原剂");
        c("mineral acids", "无机酸");
        c("strong mineral acids", "强无机酸");
        c("water", "水");
        c("air", "空气");
        c("heat", "热");
        c("light", "光");
        c("moisture", "水分");
        c("steam", "蒸汽");
        c("iron", "铁");
        c("steel", "钢");
        c("copper", "铜");
        c("aluminum", "铝");
        c("aluminium", "铝");
        c("brass", "黄铜");
        c("zinc", "锌");
        c("rubber", "橡胶");
        c("plastics", "塑料");
        // ===== 2026-09-23 补：丙烯酸/聚合类页面暴露的高频缺口 =====
        c("amines", "胺类");
        c("amine", "胺");
        c("alkalis", "碱类");
        c("alkali", "碱");
        c("ammonium hydroxide", "氢氧化铵");
        c("chloro-sulfonic acid", "氯磺酸");
        c("chlorosulfonic acid", "氯磺酸");
        c("oleum", "发烟硫酸");
        c("ethylene diamine", "乙二胺");
        c("ethylenediamine", "乙二胺");
        c("ethyleneimine", "氮丙啶");
        c("2-aminoethanol", "2-氨基乙醇");
        c("aminoethanol", "氨基乙醇");
        c("oxygen", "氧气");
        c("polymerizing initiators", "聚合引发剂");
        c("polymerization initiators", "聚合引发剂");
        c("polymerization accelerators", "聚合促进剂");
        c("polymerization inhibitor", "阻聚剂");
        c("polymerization", "聚合");
        c("polymerization can occur", "可能发生聚合");
        c("driers", "干燥剂");
        c("drier", "干燥剂");
        c("electrophilic", "亲电");
        c("nucleophilic", "亲核");
        c("free-radical", "自由基");
        c("free radical", "自由基");
        c("agent", "试剂");
        c("agents", "试剂");
        c("electrophilic, free-radical, and nucleophilic agent", "亲电试剂、自由基和亲核试剂");
        c("electrophilic, free-radical, and nucleophilic agents", "亲电试剂、自由基和亲核试剂");
        c("nucleophilic agent", "亲核试剂");
        c("electrophilic agent", "亲电试剂");
        c("reacts violently in contact with", "与以下物质接触会剧烈反应：");
        c("react violently in contact with", "与以下物质接触会剧烈反应：");
        c("initiate polymerization", "引发聚合");
        c("different solubilities", "溶解度差异");
        c("the acid", "酸");
        c("acid and inhibitor", "酸与阻聚剂");
        c("index", "总索引");
        c("stabilized", "稳定化");
        c("partitioning one from the other", "两者相互分配");
        c("acrylic acid", "丙烯酸");
        c("non-combustible place", "不燃场所");
        c("cool", "阴凉");
        c("a detached, cool, well-ventilated, non-combustible place", "独立、阴凉、通风良好的不燃场所");
        c("detached, cool, well-ventilated, non-combustible place", "独立、阴凉、通风良好的不燃场所");
        // ===== 2026-09-23 补（丙烯酸长段落）=====
        c("mehq", "MeHQ");
        c("the monomethyl ether of hydroquinone", "对苯二酚单甲醚");
        c("monomethyl ether", "单甲醚");
        c("hydrolytic reactions", "水解反应");
        c("hydrolysis", "水解");
        c("inhibit polymerization", "抑制聚合");
        c("inhibitor system", "阻聚体系");
        c("the inhibitor", "阻聚剂");
        c("the acid and inhibitor", "酸与阻聚剂");
        c("elevated temperatures", "高温");
        c("as well as freezing", "以及冻结");
        c("freezing", "冻结");
        c("both", "两者");
        c("induce polymerization", "引发聚合");
        c("polyvalent metal salts", "多价金属盐");
        c("metal salts", "金属盐");
        c("such metallic materials", "此类金属材料");
        c("the above-mentioned metals", "上述金属");
        c("above-mentioned", "上述");
        c("equipment", "设备");
        c("vessels lined with", "内衬以下材料的容器：");
        c("the manufacturer", "生产厂家");
        c("temperature range of", "温度范围");
        c("a temperature range", "温度范围");
        c("transport and storage", "运输和储存");
        c("during transport and storage", "在运输和储存过程中");
        c("the storage", "储存");
        c("the transport", "运输");
        c("a major concern", "一个重要问题");
        c("the avoidance of", "要避免");
        c("avoidance", "避免");
        c("is required for", "是……所必需的");
        c("to be effective", "才能生效");
        c("the inhibitor to be effective", "阻聚剂才能生效");
        c("frequently", "常有");
        c("therefore", "因此");
        c("ideally", "理想情况下");
        c("since", "因为");
        c("both can lead to", "两者都可能导致");
        c("could also induce", "也可能引发");
        c("which contains", "含");
        c("be stored or transported", "被储存或运输");
        c("should be stored or transported", "应储存或运输");
        c("generates", "产生");
        c("generates a", "产生");
        c("unalloyed steel", "非合金钢");
        c("stainless steel", "不锈钢");
        c("easily oxidized materials", "易被氧化物质");
        c("unalloyed steel", "非合金钢");
        c("stainless steel", "不锈钢");
        c("carbon steel", "碳钢");
        c("mild steel", "低碳钢");
        c("brass", "黄铜");
        c("bronze", "青铜");
        c("monel", "蒙乃尔合金");
        c("polyethylene", "聚乙烯");
        c("polypropylene", "聚丙烯");
        c("glass", "玻璃");
        c("lined", "内衬");
        c("vessels", "容器");
        c("vessel", "容器");
        c("containers", "容器");
        c("container", "容器");
        c("physical damage", "物理损伤");
        c("deep discoloration", "严重变色");
        c("discoloration", "变色");
        c("hydrolysis", "水解");
        c("partitioning", "分配");
        c("solubilities", "溶解度");
        c("solubility", "溶解度");
        c("inhibitor", "阻聚剂");
        c("inhibitors", "阻聚剂");
        c("monomethyl ether of hydroquinone", "对苯二酚单甲醚");
        c("hydroquinone", "对苯二酚");
        c("ppm", "ppm");
        c("as freezing", "以及冻结");
        c("freezing", "冻结");
        c("elevated temperatures", "高温");
        c("non-combustible", "不燃");
        c("noncombustible", "不燃");
        c("well-ventilated", "通风良好");
        c("ventilated", "通风");
        c("detached", "独立的");
        c("corrosive agent", "腐蚀剂");
        c("corrosive", "腐蚀性");
        c("polyvalent metal salts", "多价金属盐");
        c("metal salts", "金属盐");
        c("metallic materials", "金属材料");
        c("metals", "金属");
        c("metal", "金属");
        c("decomposition", "分解");
        c("initiate", "引发");
        c("initiates", "引发");
        c("initiated", "引发");
        c("partition", "分配");
        c("partitioning one from the other", "彼此分配");
        c("due to", "由于");
        c("presence of", "存在");
        c("the presence of water", "水的存在");
        c("temperature range", "温度范围");
        c("ideally", "理想情况下");
        c("should be", "应当");
        c("protected against", "防止");
        c("in order to", "为了");
        c("during transport and storage", "在运输和储存过程中");
        c("during transport", "运输过程中");
        c("during storage", "储存过程中");
        c("avoidance of", "避免");
        c("avoidance", "避免");
        c("a major concern", "一个重要问题");
        c("a failure of", "失效");
        c("failure", "失效");
        c("inhibitor system", "阻聚体系");
        c("commonly added", "通常添加");
        c("is commonly added to", "通常添加到");
        c("by the manufacturer", "由生产厂家");
        c("the manufacturer", "生产厂家");
        c("depending on", "取决于");
        c("is required for", "是……所必需的");
        c("to be effective", "才能生效");
        c("both can lead to", "两者都可能导致");
        c("can lead to", "可能导致");
        c("lead to", "导致");
        c("may initiate", "可能引发");
        c("may cause", "可能导致");
        c("can occur", "可能发生");
        c("should be stored", "应储存于");
        c("can be stored", "可储存于");
        c("should be stored only in", "只能储存于");
        c("only in vessels lined with", "只能使用内衬以下材料的容器：");
        c("or", "或");
        c("and", "和");
        c("with", "用");
        c("such as", "例如");
        c("frequently", "常常");
        c("therefore", "因此");
        c("under no circumstances", "在任何情况下都不得");
        c("does not affect", "不影响");
        c("in contact with", "接触");
        c("incompatible materials", "禁配物");
        c("strong bases", "强碱");
        c("strong base", "强碱");
        c("bases", "碱类");
        c("base", "碱");
        c("note", "注");
        c("readily", "容易");
        c("free-radical, and nucleophilic agent", "自由基和亲核试剂");
    }

    /**
     * 句型级翻译。命中模板则给出通顺中文，否则返回 null。
     */
    public static String patternSentence(String s) {
        if (s == null) return null;
        String t = stripCitation(s.trim());

        Matcher m = DENSITY_TPL.matcher(t);
        if (m.matches()) {
            // ⚠️ 进到这里时 temp() 已把 °F 换成 ℃，group(2) 已是摄氏值，不能再 f2c；
            //    仅当原串还是 °F（未经 temp 处理，如 °F 前无空格）时才换算。
            String rawTempSeg = m.group(0);
            double c = rawTempSeg.contains("℃") || rawTempSeg.contains("°C")
                    ? Double.parseDouble(m.group(2)) : f2c(Double.parseDouble(m.group(2)));
            String note = translateText(m.group(3));
            // ⚠️ group(1) 是不带单位的相对密度/密度数值（如 "0.792" / "1.51"），
            //    直接拼「密度 0.792」在页面上不可读 → 补成「相对密度 0.792（20 ℃ 时）」。
            return "相对密度 " + m.group(1) + "（" + fmt(c) + " ℃ 时）"
                    + (note != null && hasCn(note) ? "，" + note : "");
        }

        m = CAUSE_DECOMP.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "可分解生成" + chem(m.group(2)) + "。";
        }

        m = CONTACT_IGNITE.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "与" + chem(m.group(2)) + "接触会引起着火。";
        }

        m = PREP_EXPLODE.matcher(t);
        if (m.matches()) {
            return "由" + chem(m.group(2)) + "加入" + chem(m.group(3)) + "制备" + chem(m.group(1))
                    + "时，" + chem(m.group(4)) + "。";
        }

        m = INCOMPAT_MAT.matcher(t);
        if (m.matches()) {
            String body = m.group(1).replaceAll("^materials\\s*:?\\s*", "");
            return "禁配物：" + chemList(body) + "。";
        }

        m = INCOMPAT.matcher(t);
        if (m.matches()) {
            return "禁配物：" + chemList(m.group(1)) + "。";
        }

        // "Can react vigorously with oxidizing materials."
        m = CAN_REACT.matcher(t);
        if (m.matches()) {
            String lvl = m.group(1) == null ? "" : "剧烈";
            return "能与" + chemList(m.group(2)) + (lvl.isEmpty() ? "" : lvl) + "反应。";
        }

        // "React readily with electrophilic, free-radical, and nucleophilic agent"
        // ⚠️⚠️ 必须排在 REACTS_ONLY 与 REACTS_WITH **之前**：
        //     REACTS_ONLY 会把 "(electrophilic, free-radical, and) nucleophilic agent" 交给 chemList
        //     按逗号切分，导致整短语词条永不命中；REACTS_WITH 会把 "React readily" 当主语。
        m = REACT_READILY.matcher(t);
        if (m.matches()) {
            String obj = m.group(1).trim();
            // ⚠️ chemList 会按 and/逗号切分，导致「整短语」词条永不命中 →
            //    先拿整串查一次 CHEMS，命中就直接用（如 "electrophilic, free-radical, and nucleophilic agent"）。
            String whole = CHEMS.get(obj.toLowerCase());
            if (whole == null) whole = CHEMS.get(obj.toLowerCase().replaceAll("\\.$", ""));
            if (whole == null) whole = WORDS.get(obj.toLowerCase());
            return "易与" + (whole != null ? whole : chemList(obj)) + "反应。";
        }

        // 无主语："REACTS VIOLENTLY WITH GALLIUM PERCHLORATE."（注意必须排在 REACTS_WITH 之前，
        // 否则会被 REACTS_WITH 的 ^(.+?)reacts?\s 以错误的主语切分命中）
        m = REACTS_ONLY.matcher(t);
        if (m.matches()) {
            String lvl = t.toLowerCase().contains("violently") || t.toLowerCase().contains("vigorously") ? "剧烈" : "";
            return "与" + chemList(m.group(1)) + "接触会" + lvl + "反应。";
        }

        // "Strong oxidizers, many fluorides & perchlorates, nitric acid"
        m = OXID_LIST.matcher(t);
        if (m.matches()) {
            String tail = m.group(3).replace('&', ',');
            return "禁配物：强氧化剂、" + chemList(tail) + "。";
        }

        // "Reacts violently in contact with acids, amines, driers, ..."
        // ⚠️ 必须排在 REACT_READILY 之前（后者的 "with" 会抢先匹配，丢掉 "in contact" 语义）
        m = REACTS_IN_CONTACT.matcher(t);
        if (m.matches()) {
            return "与" + chemList(m.group(1)) + "接触会剧烈反应。";
        }

        m = REACTS_WITH.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "与" + chemList(m.group(2)) + "反应。";
        }

        // "This generates fire and explosion hazard." / "generates heat and ..."
        m = THIS_GENERATES.matcher(t);
        if (m.matches()) {
            String o = m.group(1).toLowerCase();
            boolean fire = o.contains("fire"), expl = o.contains("explos"), heat = o.contains("heat");
            StringBuilder sb = new StringBuilder("这会带来");
            if (fire && expl) sb.append("火灾及爆炸危险");
            else if (fire) sb.append("火灾危险");
            else if (expl) sb.append("爆炸危险");
            else if (heat) sb.append("放热风险");
            else sb.append(chem(o));
            return sb.append("。").toString();
        }

        // ===== 2026-09-23 补的五个句式（丙烯酸页暴露）=====

        // "Acrylic acid can be stored only in vessels lined with glass, stainless steel, aluminum, or polyethylene."
        m = CAN_BE_STORED_ONLY.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "只能储存于内衬以下材料的容器中：" + chemList(m.group(2)) + "。";
        }

        // "X should be stored in a detached, cool, well-ventilated, non-combustible place, and its containers should be protected against physical damage."
        m = SHOULD_BE_STORED.matcher(t);
        if (m.matches()) {
            StringBuilder sb = new StringBuilder(chem(m.group(1)) + "应储存于");
            sb.append(chemList(m.group(2)));
            String tail = m.group(3);
            if (tail != null && !tail.isBlank()) {
                String tt = tail.toLowerCase();
                if (tt.contains("protected against") || tt.contains("physical damage")) {
                    sb.append("；容器应防止物理损伤");
                } else {
                    String z = translateText(tail);
                    if (z != null && hasCn(z)) sb.append("；").append(z.replaceAll("。$", ""));
                }
            }
            return sb.append("。").toString();
        }

        // "The presence of water, due to different solubilities of the acid and inhibitor, may initiate polymerization."
        m = PRESENCE_OF_MAY.matcher(t);
        if (m.matches()) {
            String what = chemList(m.group(1)), why = m.group(2), act = m.group(3);
            StringBuilder sb = new StringBuilder(what + "的存在");
            if (why != null && !why.isBlank()) {
                // "different solubilities of the acid and inhibitor" -> "酸与阻聚剂溶解度不同"
                String w2 = why.toLowerCase()
                        .replaceAll("^different\\s+solubilit(?:y|ies)\\s+of\\s+", " solubilitydiff ");
                String zw = w2.startsWith(" solubilitydiff ")
                        ? chemList(w2.substring(" solubilitydiff ".length())) + "溶解度不同"
                        : translateText(why);
                if (zw != null && hasCn(zw) && enWordRun(zw) < 2) {
                    sb.append("（由于").append(zw.replaceAll("。$", "")).append("）");
                }
            }
            sb.append("可能").append(chemList(act.replaceAll("^\\.", "").trim()));
            String out = sb.append("。").toString();
            if (isGoodZh(out)) return out;
        }

        // "X is a strong corrosive agent to many metals, such as unalloyed steel, copper, and brass."
        m = CORROSIVE_TO.matcher(t);
        if (m.matches()) {
            String subj = chem(m.group(1));
            String strong = m.group(2) != null ? "强" : "";
            String objs = m.group(3);
            String ex = m.group(4);
            String head = subj + "对" + (objs.toLowerCase().contains("metals") ? "多种金属" : chemList(objs))
                    + "具有" + strong + "腐蚀性";
            if (ex != null && !ex.isBlank()) head += "，如" + chemList(ex);
            return head + "。";
        }

        // "X does not affect Y."
        m = NOT_AFFECT.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "不会影响" + chemList(m.group(2)) + "。";
        }

        // "Frequently the hydrolysis of X generates Y."
        m = FREQUENTLY_GENERATES.matcher(t);
        if (m.matches()) {
            String act = chem(m.group(1));   // hydrolysis -> 水解
            String gen = m.group(3).trim().toLowerCase()
                    .replaceAll("^a\\s+", "").replaceAll("^an\\s+", "");
            String zg = gen;
            if (gen.startsWith("deep discoloration")) zg = "严重变色";
            else {
                String zz = translateText(gen);
                if (zz != null && hasCn(zz)) zg = zz.replaceAll("。$", "");
            }
            // ⚠️ 主语口径必须跟着「动作」走，"此类金属材料" 只适用于腐蚀类，不能写死
            boolean corro = act.contains("腐蚀");
            if (corro) return "此类金属材料常有" + act + "，会使" + chem(m.group(2)) + "产生" + zg + "。";
            return chem(m.group(2)) + "常发生" + act + "，会产生" + zg + "。";
        }

        // "Polyvalent metal salts formed during hydrolytic reactions could also induce polymerization."
        m = COULD_ALSO_INDUCE.matcher(t);
        if (m.matches()) {
            String subj = m.group(1).trim()
                    .replaceAll("(?i)\\s+formed\\s+during\\s+hydrolytic\\s+reactions$", "");
            String zc = chemList(subj);
            if (zc.equals(subj)) {
                String zt = translateText(subj);
                if (zt != null && hasCn(zt)) zc = zt;
            }
            return zc + "（水解反应中生成）也可能引发" + chemList(m.group(2)) + "。";
        }

        // "Therefore, under no circumstances should X be stored or transported with equipment which contains Y."
        m = UNDER_NO_CIRCUMSTANCES.matcher(t);
        if (m.matches()) {
            String y = m.group(3).toLowerCase().replaceAll("^the\\s+", "");
            String zy = chemList(y);
            if (zy.equals(y)) zy = "上述金属";
            return "因此，在任何情况下都不得使用含" + zy + "的设备储存或运输" + chem(m.group(1)) + "。";
        }

        // ===== IMDG 法规通用条款（2026-09-23）=====
        m = IMDG_DETAILED.matcher(t);
        if (m.matches()) {
            return "针对各具体物质的详细建议，以及若干良好操作规范建议，均收录于相应类别的条款中。";
        }
        m = SLASH_TERM.matcher(t);
        if (m.matches()) {
            // "/Acrylic acid, stabilized/" —— 斜杠内是完整物质名，", stabilized" 是修饰，不可当独立物质
            String inner = m.group(1).trim();
            boolean stab = inner.toLowerCase().endsWith(", stabilized");
            if (stab) inner = inner.substring(0, inner.length() - ", stabilized".length()).trim();
            String z = chem(inner);
            if (!hasCn(z)) z = chemList(inner);
            if (hasCn(z)) return z + (stab ? "(稳定化)" : "") + "。";
        }
        // 复合：splitSentences 会把 "...goods list. /Acrylic acid, stabilized/" 并成一句
        //     （斜杠不是句末符）→ ⚠️ 必须排在 INCLUDED_ON_DGL 之前，否则后者会把整段吞成主语
        Matcher mdgl = Pattern.compile(
                "(?i)^(.+?\\bis\\s+included\\s+on\\s+the\\s+dangerous\\s+goods\\s+list\\.?)\\s+/(.+?)/\\.?$")
                .matcher(t);
        if (mdgl.matches()) {
            String a = mdgl.group(1).trim(), b = mdgl.group(2).trim();
            String za = translateText(a), zb = chem(b);
            if (!hasCn(zb)) zb = chemList(b);
            if (hasCn(za) && hasCn(zb)) {
                return za.replaceAll("。$", "") + "。" + zb.replaceAll("。$", "") + "。";
            }
        }
        m = INCLUDED_ON_DGL.matcher(t);
        if (m.matches()) {
            String subj = m.group(1).trim().replaceAll("(?i),\\s*stabilized$", "").trim();
            String z = chem(subj);
            if (!hasCn(z)) z = chemList(subj);
            if (hasCn(z)) {
                return z + (m.group(1).toLowerCase().contains("stabilized") ? "(稳定化)" : "")
                        + "已列入危险货物清单。";
            }
        }

        // "In order to inhibit polymerization during transport and storage, 200 ppm MeHQ (...) is commonly added to acrylic acid by the manufacturer."
        // ⚠️ 必须排在 COMMONLY_ADDED 之前：否则 (.+?) 会把 "In order to ... , 200 ppm MeHQ (...)" 整段当主语吞掉
        m = IN_ORDER_TO_ADD.matcher(t);
        if (m.matches()) {
            String purpose = m.group(1).trim(), rest = m.group(2).trim();
            String zp = null;
            String p2 = purpose.toLowerCase()
                    .replaceFirst("^inhibit\\s+polymerization\\s+during\\s+", " INHIBITPOLY ");
            if (p2.startsWith(" INHIBITPOLY ")) {
                String when = p2.substring(" INHIBITPOLY ".length()).trim();
                zp = "在" + ("transport and storage".equals(when) ? "运输和储存过程中" : when) + "抑制聚合";
            }
            // 后半句 "200 ppm MeHQ (...) is commonly added to acrylic acid by the manufacturer."
            String zr = rest;
            Matcher m2 = COMMONLY_ADDED.matcher(rest);
            if (m2.matches()) {
                String subj = m2.group(1).replaceAll("\\s*\\(.*?\\)\\s*", " ").trim();
                zr = chemLoose(subj) + "通常由生产厂家添加到" + chem(m2.group(2)) + "中";
            } else {
                String zt = translateText(rest);
                if (zt != null && isGoodZh(zt)) zr = zt.replaceAll("。$", "");
            }
            if (zp != null && isGoodZh(zr + "。")) {
                return "为了" + zp + "，" + zr.replaceAll("。$", "") + "。";
            }
        }

        // "X is commonly added to Y by the manufacturer."
        m = COMMONLY_ADDED.matcher(t);
        if (m.matches()) {
            return chem(m.group(1)) + "通常由生产厂家添加到" + chem(m.group(2)) + "中。";
        }

        // "The presence of oxygen is required for the inhibitor to be effective."
        m = REQUIRED_FOR.matcher(t);
        if (m.matches()) {
            String a = chem(m.group(1)), b = chem(m.group(2)), c3 = m.group(3).trim().toLowerCase();
            String tail = c3.replaceAll("^(be\\s+)?", "");
            if (tail.equals("effective")) tail = "生效";
            else {
                String zt = translateText(tail);
                tail = (zt != null && hasCn(zt)) ? zt.replaceAll("。$", "") : tail;
            }
            return "必须有" + a + "存在，" + b + "才能" + tail + "。";
        }

        // "X should be stored within a temperature range of 15 to 25 °C."
        m = TEMP_RANGE_STORE.matcher(t);
        if (m.matches()) {
            String subj = m.group(1).toLowerCase().replaceAll("^ideally\\s+", "");
            String pre = m.group(1).toLowerCase().startsWith("ideally") ? "理想情况下，" : "";
            return pre + chem(subj) + "应储存在 " + fmt(Double.parseDouble(m.group(2))) + " ～ "
                    + fmt(Double.parseDouble(m.group(3))) + " ℃ 的温度范围内。";
        }

        // "A major concern during the storage of X is the avoidance of A as well as B, since both can lead to C."
        m = MAJOR_CONCERN.matcher(t);
        if (m.matches()) {
            StringBuilder sb = new StringBuilder(chem(m.group(2)) + "储存过程中的一个重要问题是要避免");
            String obj = m.group(3).replaceAll("\\bas well as\\b", ",");
            sb.append(chemList(obj));
            String since = m.group(4);
            if (since != null && !since.isBlank()) {
                // "a failure of the inhibitor system" -> "阻聚体系失效"
                String sc = since.toLowerCase().replaceAll("^a\\s+", "");
                String zs = sc.replaceAll("^failure\\s+of\\s+the\\s+", " failureof ");
                String zhSince = zs.startsWith(" failureof ")
                        ? chemList(zs.substring(" failureof ".length())) + "失效"
                        : (translateText(since) != null && hasCn(translateText(since))
                            ? translateText(since).replaceAll("。$", "") : since);
                sb.append("，因为两者都可能导致").append(zhSince);
            }
            return sb.append("。").toString();
        }

        // "X has resulted in Y" / "resulted in Y"
        m = RESULT_IN.matcher(t);
        if (m.matches()) {
            String a = m.group(1), b = m.group(2);
            // 主语过长时，用「危险操作提示」口径概括，避免半中半英
            if (a.length() > 90) {
                if (b.toLowerCase().contains("explos")) return "该操作曾导致严重爆炸事故。";
                return "该操作存在安全风险，曾导致事故。";
            }
            return chem(a) + "曾导致" + chem(b) + "。";
        }

        // "greater than or equal to N mg/mL at T °F" -> "T ℃ 时溶解度 ≥ N mg/mL"
        m = SOLUBILITY_TPL.matcher(t);
        if (m.matches()) {
            String rel = m.group(1).toLowerCase().contains("greater") ? "≥"
                    : (m.group(1).toLowerCase().contains("less") ? "≤" : "约");
            String amt = m.group(2).trim();
            String tempZh = "";
            if (m.group(3) != null) {
                // 注意：进到 patternSentence 时 °F 已被 temp() 换成 ℃，两种都要认
                Matcher tm = F_PAT.matcher(m.group(3));
                if (tm.find()) {
                    tempZh = Math.round(f2c(Double.parseDouble(tm.group(1)))) + " ℃ 时";
                } else {
                    Matcher tc = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*℃").matcher(m.group(3));
                    if (tc.find()) tempZh = tc.group(1) + " ℃ 时";
                }
            }
            return tempZh + "溶解度 " + rel + " " + amt;
        }

        // "X soluble" —— 仅当整体是「单词 + soluble」这种短结构才套用
        m = SOLUBLE_TPL.matcher(t);
        if (m.matches()) {
            String sub = m.group(1).trim();
            if (!sub.contains(".") && sub.split("\\s+").length <= 3) {
                return chem(sub) + "可溶。";
            }
        }

        // "Contact with X may cause Y"
        m = CONTACT_MAY.matcher(t);
        if (m.matches()) {
            return "接触" + chemList(m.group(1)) + "可能引起" + chem(m.group(2)) + "。";
        }

        // "X is highly flammable / flammable"
        m = FLAMMABLE_TPL.matcher(t);
        if (m.matches()) {
            String lvl = m.group(1) == null ? "" : m.group(1).trim().toLowerCase();
            String word = m.group(2).toLowerCase();
            String cnWord = word.startsWith("flammable") || word.startsWith("combustible") ? "易燃" : "可燃";
            String prefix = lvl.isEmpty() ? "" : (lvl.startsWith("high") || lvl.startsWith("extrem") ? "高度" : "轻微");
            return chem(m.group(0).split("\\s+(?:is|are)\\s+")[0]) + prefix + cnWord + "。";
        }

        // 气味描述："Slight alcoholic odor when pure; repulsive, pungent odor when crude"
        //          "Characteristic, sweet, balsamic, almost floral odor that is extremely penetrating"
        String odor = odorSentence(t);
        if (odor != null) return odor;

        // "greater than 300 ℃ at 760 mmHg (sublimes without melting)"
        m = GREATER_AT.matcher(t);
        if (m.matches()) {
            String num = m.group(1).trim();
            String extra = "";
            String tail = m.group(2) == null ? "" : m.group(2).toLowerCase();
            if (tail.contains("sublim")) extra = "（升华，不经过熔化）";
            else if (tail.contains("decompos")) extra = "（同时分解）";
            return "大于 " + num + " ℃（常压 760 mmHg 下）" + extra;
        }
        return null;
    }

    // ---------------------------------------------------------------- 气味句式

    private static final Pattern ODOR_PART = Pattern.compile(
            "^(.{0,120}?)\\s*odor\\s*(?:when|if)\\s+(\\S[^;；,，]{0,40}?)$", Pattern.CASE_INSENSITIVE);

    /**
     * 气味句 -> 中文。处理两类：
     *   ① 「<形容词> odor when <条件>」，多条以分号分隔 —— 逐条译为「<条件>时为<形容词>气味」
     *   ② 「<形容词串> odor that is extremely penetrating」—— 收成「<形容词>气味，穿透力极强」
     */
    private static String odorSentence(String s) {
        if (s == null || !Pattern.compile("\\bodor\\b", Pattern.CASE_INSENSITIVE).matcher(s).find()) return null;
        String t = s.trim();
        // ① 含 when：按分号/逗号拆成「形容词 + when + 条件」的若干片段
        if (Pattern.compile("\\bodor\\s+(?:when|if)\\b", Pattern.CASE_INSENSITIVE).matcher(t).find()) {
            String[] segs = t.split("\\s*;\\s*");
            List<String> out = new ArrayList<>();
            for (String seg : segs) {
                Matcher om = ODOR_PART.matcher(seg.trim());
                if (!om.matches()) continue;
                String adj = adjWords(om.group(1));
                String cond = translateText(om.group(2).trim());
                if (adj.isEmpty() || cond == null) continue;
                out.add(cond + "时为" + adj + "气味");
            }
            if (!out.isEmpty()) return String.join("；", out) + "。";
            return null;
        }
        // ② 「... odor that is extremely penetrating」/「... odor which is ...」
        Matcher om = Pattern.compile(
                "^(.{0,140}?)\\s*odor\\s+(?:that|which)\\s+is\\s+(.{0,60})$", Pattern.CASE_INSENSITIVE).matcher(t);
        if (om.matches()) {
            String adj = adjWords(om.group(1));
            String desc = adjWords(om.group(2));
            if (!adj.isEmpty()) {
                String body = adj + "气味";
                if (!desc.isEmpty()) body += "，" + desc;
                return body + "。";
            }
        }
        // ③ 纯形容词 + odor（无从句）—— 交给词典逐词即可，返回 null 走后续流程
        return null;
    }

    /** 把一串气味形容词译成中文并去掉「气味/的」噪声，用顿号连接 */
    private static String adjWords(String s) {
        if (s == null || s.isBlank()) return "";
        String x = s.trim().replaceAll("(?i)^(an?|the)\\s+", "");
        List<String> parts = new ArrayList<>();
        for (String w : x.split("\\s*(?:,|;|\\band\\b|\\bor\\b)\\s*")) {
            String p = w.trim();
            if (p.isEmpty() || p.equalsIgnoreCase("odor")) continue;
            String zh = translateText(p);
            if (zh == null || zh.isBlank()) zh = p;
            zh = zh.replaceAll("[。，、；]+$", "").replaceAll("的气味$", "").replaceAll("气味$", "").replaceAll("的$", "");
            zh = zh.replaceAll("\\s+", "");   // 形容词内部不留空格（"轻微 醇类" -> "轻微醇类"）
            if (!zh.isBlank()) parts.add(zh);
        }
        // 去重保序
        List<String> uniq = new ArrayList<>();
        for (String p : parts) if (!uniq.contains(p)) uniq.add(p);
        return String.join("、", uniq);
    }

    private static final Pattern GREATER_AT = Pattern.compile(
            "^(?:greater|more)\\s+than\\s+([0-9.]+)\\s*(?:℃|°C|C)\\s+(?:at|@)\\s*[0-9.]+\\s*mmHg"
            + "\\s*(?:\\((.+?)\\))?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern RESULT_IN = Pattern.compile(
            "^(.+?)\\s+(?:has\\s+|have\\s+)?resulted\\s+in\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /** "This generates fire and explosion hazard." -> "这会带来火灾及爆炸危险。" */
    private static final Pattern THIS_GENERATES = Pattern.compile(
            "^This\\s+generates\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    /** "This generates heat and pressure-rise explosion hazard." 含连字符也吃 */
    private static final Pattern GENERATES_HAZ = Pattern.compile(
            "^(?:This\\s+)?(?:also\\s+)?generates?\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);

    // ===== 2026-09-23 补：丙烯酸页暴露的失败句式 =====
    /** "React readily with electrophilic, free-radical, and nucleophilic agent" -> "易与亲电试剂、自由基和亲核试剂反应。" */
    private static final Pattern REACT_READILY = Pattern.compile(
            "^(?:They\\s+)?reacts?\\s+(?:readily|easily|vigorously|violently|slowly|rapidly)?\\s*with\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "Reacts violently in contact with acids, amines, driers, ... Polymerization can occur." */
    private static final Pattern REACTS_IN_CONTACT = Pattern.compile(
            "^(?:They\\s+)?reacts?\\s+(?:violently|vigorously|readily)\\s+in\\s+contact\\s+with\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "Acrylic acid should be stored in a detached, cool, well-ventilated, non-combustible place, and its containers should be protected against physical damage." */
    private static final Pattern SHOULD_BE_STORED = Pattern.compile(
            "^(.+?)\\s+should\\s+be\\s+stored\\s+in\\s+(?:a\\s+)?(.+?)(?:,\\s*and\\s+(.+))?\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "Acrylic acid can be stored only in vessels lined with glass, stainless steel, aluminum, or polyethylene." */
    private static final Pattern CAN_BE_STORED_ONLY = Pattern.compile(
            "^(.+?)\\s+can\\s+be\\s+stored\\s+only\\s+in\\s+(?:vessels|containers)\\s+lined\\s+with\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "The presence of water, due to X, may initiate polymerization." */
    private static final Pattern PRESENCE_OF_MAY = Pattern.compile(
            "^The\\s+presence\\s+of\\s+(.+?)\\s*(?:,\\s*(?:due\\s+to|because\\s+of)\\s+(.+?))?\\s*,?\\s*may\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "X is a strong corrosive agent to many metals, such as A, B, and C." */
    private static final Pattern CORROSIVE_TO = Pattern.compile(
            "^(.+?)\\s+is\\s+(?:a\\s+)?(strong\\s+)?corrosive\\s+(?:agent\\s+)?to\\s+(.+?)(?:,\\s*such\\s+as\\s+(.+?))?\\.?$",
            Pattern.CASE_INSENSITIVE);

    // ===== 2026-09-23 补（丙烯酸长段落）=====
    /** "X should be stored within a temperature range of 15 to 25 °C." */
    private static final Pattern TEMP_RANGE_STORE = Pattern.compile(
            "^(.+?)\\s+should\\s+be\\s+stored\\s+within\\s+a\\s+temperature\\s+range\\s+of\\s+(-?[0-9.]+)\\s*(?:to|-|–)\\s*(-?[0-9.]+)\\s*(?:°?\\s*C|℃)s?\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "The presence of oxygen is required for the inhibitor to be effective." */
    private static final Pattern REQUIRED_FOR = Pattern.compile(
            "^The\\s+presence\\s+of\\s+(.+?)\\s+is\\s+required\\s+for\\s+(.+?)\\s+to\\s+be\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "A major concern during the storage of X is the avoidance of A as well as B, since both can lead to C." */
    private static final Pattern MAJOR_CONCERN = Pattern.compile(
            "^A\\s+major\\s+concern\\s+during\\s+(?:the\\s+)?(\\w+)\\s+of\\s+(.+?)\\s+is\\s+the\\s+avoidance\\s+of\\s+(.+?)(?:,\\s*since\\s+both\\s+can\\s+lead\\s+to\\s+(.+?))?\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "X is commonly added to Y by the manufacturer."（主语禁含逗号，避免吞掉前置状语） */
    private static final Pattern COMMONLY_ADDED = Pattern.compile(
            "^([^,]+?)\\s+is\\s+commonly\\s+added\\s+to\\s+([^,]+?)\\s+by\\s+the\\s+manufacturer\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "In order to inhibit polymerization during transport and storage, X is commonly added to Y by the manufacturer." */
    private static final Pattern IN_ORDER_TO_ADD = Pattern.compile(
            "^In\\s+order\\s+to\\s+([^,]+?)\\s*,\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    // ===== 2026-09-23 补：IMDG 法规通用条款（多品种共用） =====
    /** "Detailed recommendations for individual substances and a number of recommendations for good practice are included in the classes dealing with such substances." */
    private static final Pattern IMDG_DETAILED = Pattern.compile(
            "^Detailed\\s+recommendations\\s+for\\s+individual\\s+substances\\s+and\\s+a\\s+number\\s+of\\s+recommendations\\s+for\\s+good\\s+practice\\s+are\\s+included\\s+in\\s+the\\s+classes\\s+dealing\\s+with\\s+such\\s+substances\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "X is included on the dangerous goods list."（X 可含 ", stabilized" 等修饰；禁止含句点，避免吞并后续斜杠名） */
    private static final Pattern INCLUDED_ON_DGL = Pattern.compile(
            "^([^./]+?)\\s+is\\s+included\\s+on\\s+the\\s+dangerous\\s+goods\\s+list\\.?$", Pattern.CASE_INSENSITIVE);
    /** "/Acrylic acid, stabilized/" —— 斜杠包夹的物质名，仅回显中文名 */
    private static final Pattern SLASH_TERM = Pattern.compile("^/(.+?)/\\.?$");

    /** "Frequently the hydrolysis of X generates Y." */
    private static final Pattern FREQUENTLY_GENERATES = Pattern.compile(
            "^Frequently\\s+the\\s+(.+?)\\s+of\\s+(.+?)\\s+generates?\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    /** "X could also induce polymerization." */
    private static final Pattern COULD_ALSO_INDUCE = Pattern.compile(
            "^(.+?)\\s+could\\s+also\\s+induce\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    /** "Therefore, under no circumstances should X be stored or transported with equipment which contains Y." */
    private static final Pattern UNDER_NO_CIRCUMSTANCES = Pattern.compile(
            "^(?:Therefore,?\\s+)?under\\s+no\\s+circumstances\\s+should\\s+(.+?)\\s+be\\s+(.+?)\\s+with\\s+(?:equipment|apparatus)\\s+which\\s+contains\\s+(.+?)\\.?$",
            Pattern.CASE_INSENSITIVE);
    /** "X does not affect Y." */
    private static final Pattern NOT_AFFECT = Pattern.compile(
            "^(.+?)\\s+does\\s+not\\s+affect\\s+(.+?)\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT_MAY = Pattern.compile(
            "^Contact\\s+with\\s+(.+?)\\s+may\\s+cause\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLAMMABLE_TPL = Pattern.compile(
            "^(.{0,240}?)\\s+(?:is|are)\\s+(highly\\s+|extremely\\s+)?(flammable|combustible)\\b.*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SOLUBILITY_TPL = Pattern.compile(
            "^(greater\\s+than\\s+or\\s+equal\\s+to|less\\s+than\\s+or\\s+equal\\s+to|approximately|about)\\s+"
            + "([0-9.]+\\s*(?:mg/mL|g/L|g/100mL|g/100 mL|mg/L|%))"
            + "(?:\\s*,?\\s*(?:and\\s+)?(?:at|in)?\\s*(.{0,60}))?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SOLUBLE_TPL = Pattern.compile(
            "^(.{1,40}?)\\s+(?:is\\s+)?soluble\\.?$", Pattern.CASE_INSENSITIVE);

    /** 单个化学名/短语 -> 中文（查表，未命中回退词典翻译） */
    private static String chem(String s) {
        if (s == null) return "";
        String k = s.trim().replaceAll("^[\\s,;。.]+|[\\s,;。.]+$", "");
        String lk = k.toLowerCase().replaceAll("^(the|a|an)\\s+", "");
        String hit = CHEMS.get(lk);
        if (hit != null) return hit;
        // 去掉数量/修饰词再查一次："many fluorides" -> "氟化物"
        String stripped = lk.replaceAll("^(many|various|several|some|all|other|strong|concentrated|dilute|aqueous)\\s+", "");
        if (!stripped.equals(lk)) {
            hit = CHEMS.get(stripped);
            if (hit != null) {
                String pre = lk.substring(0, lk.length() - stripped.length()).trim();
                return ("many".equals(pre) || "various".equals(pre) || "several".equals(pre) ? "多种" : "") + hit;
            }
        }
        // 去括号注释再查："oleum"、"2-aminoethanol [note: ...]" -> 去掉 [] 内容
        String nobracket = lk.replaceAll("\\s*\\[.*?\\]\\s*", "").replaceAll("\\s*\\(.*?\\)\\s*", "").trim();
        if (!nobracket.equals(lk) && !nobracket.isEmpty()) {
            hit = CHEMS.get(nobracket);
            if (hit != null) return hit;
        }
        // 去掉尾部的 " materials" / " substances" / " agents" / " place" 等泛化后缀再查
        String noSuffix = lk.replaceAll("\\s+(materials?|substances?|place|conditions?)$", "").trim();
        if (!noSuffix.equals(lk) && !noSuffix.isEmpty()) {
            hit = CHEMS.get(noSuffix);
            if (hit != null) return hit;
        }
        // 短语逐词查表（保留未命中词原样），避免整句残缺
        String zh = translateText(k);
        return zh != null ? zh : k;
    }

    /**
     * 宽松版物质名翻译：允许残留少量英文（如 "200 ppm MeHQ"），
     * 只把能查到的词换成中文，其余原样保留 —— 用于「一个短语里混着数字+代号」的场景。
     */
    private static String chemLoose(String s) {
        if (s == null) return "";
        String k = s.trim();
        String hit = CHEMS.get(k.toLowerCase());
        if (hit != null) return hit;
        // 逐词替换：数字/单位/代号保留，能查到的换中文
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("[A-Za-z][A-Za-z\\-']*|\\d+(?:\\.\\d+)?|[^A-Za-z\\d]+").matcher(k);
        while (m.find()) {
            String tok = m.group();
            if (tok.matches("\\d+(?:\\.\\d+)?")) { sb.append(tok); continue; }
            if (!tok.matches("[A-Za-z][A-Za-z\\-']*")) { sb.append(tok); continue; }
            String ci = CHEMS.get(tok.toLowerCase());
            if (ci == null) ci = WORDS.get(tok.toLowerCase());
            sb.append(ci != null ? ci : tok);
        }
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** 逗号/and 分隔的物质列表 -> 中文顿号列表 */
    private static String chemList(String s) {        if (s == null) return "";
        String[] parts = s.split("\\s*(?:,|;|\\band\\b|\\bor\\b|&)\\s*");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String x = p.trim();
            if (x.isEmpty()) continue;
            out.add(chem(x));
        }
        return String.join("、", out);
    }

    // ---------------------------------------------------------------- 单词词典

    private static final Map<String, String> WORDS = new LinkedHashMap<>();

    private static void w(String en, String cn) {
        WORDS.put(en, cn);
    }

    static {
        w("hg", "mmHg");
        w("mmhg", "mmHg");
        w("deg", "度");
        w("approx", "约");
        w("approximately", "约");
        w("about", "约");
        w("greater", "大于");
        w("less", "小于");
        w("than", "**");
        w("equal", "等于");
        w("or", "或");
        w("and", "及");
        w("at", "**时");
        w("in", "于**中");
        w("of", "的");
        w("with", "与**");
        w("from", "自**");
        w("to", "至**");
        w("by", "由**");
        w("for", "用于**");
        w("will", "会");
        w("may", "可能");
        w("can", "能");
        w("is", "为");
        w("are", "为");
        w("not", "不");
        w("no", "无");
        w("very", "极");
        w("highly", "高度");
        w("extremely", "极");
        w("almost", "近乎");
        w("nearly", "近乎");
        w("quite", "颇");
        w("rather", "较");
        w("fairly", "较");
        w("slightly", "微");
        w("violently", "剧烈");
        w("vigorously", "剧烈");
        w("readily", "容易");
        w("rapidly", "迅速");
        w("slowly", "缓慢");
        w("many", "多种");
        w("various", "多种");
        w("several", "若干");
        w("such", "此类");
        w("as", "如");
        w("like", "如");
        w("generates", "产生");
        w("generate", "产生");
        w("forms", "生成");
        w("form", "生成");
        w("strong", "强");
        w("weak", "弱");
        w("concentrated", "浓");
        w("dilute", "稀");
        w("pure", "纯");
        w("technical", "工业级");
        w("aqueous", "水溶液");
        w("solution", "溶液");
        w("mixture", "混合物");
        w("compound", "化合物");
        w("substance", "物质");
        w("material", "物料");
        w("chemical", "化学品");
        w("temperature", "温度");
        w("pressure", "压力");
        w("weight", "重量");
        w("volume", "体积");
        w("density", "密度");
        w("viscosity", "黏度");
        w("surface", "表面");
        w("tension", "张力");
        w("point", "点");
        w("range", "范围");
        w("level", "水平");
        w("limit", "限值");
        w("test", "试验");
        w("data", "数据");
        w("value", "值");
        w("report", "报告");
        w("record", "记录");
        w("page", "页");
        w("visit", "访问");
        w("see", "参见**");
        w("also", "另见**");
        w("more", "更多");
        w("information", "信息");
        w("below", "如下");
        w("above", "上述");
        w("following", "下列");
        w("other", "其他");
        w("various", "多种");
        w("several", "若干");
        w("most", "多数");
        w("some", "某些");
        w("all", "全部");
        w("any", "任何");
        w("such", "此类");
        w("including", "包括**");
        w("containing", "含**");
        w("produced", "生成");
        w("formed", "生成");
        w("used", "用于**");
        w("found", "发现");
        w("shown", "显示");
        w("given", "给出");
        w("taken", "取自");
        w("based", "基于**");
        w("due", "由于**");
        w("cause", "引起");
        w("causes", "造成");
        w("sublimes", "升华");
        w("sublime", "升华");
        w("sublimation", "升华");
        w("decomposes", "分解");
        w("decompose", "分解");
        w("fireproof", "防火");
        w("cool", "冷藏");
        w("keep", "保持");
        w("dark", "避光");
        w("noncombustible", "不燃");
        w("detached", "独立的");
        w("decomposition", "分解");
        w("soluble", "可溶");
        w("water", "水");
        w("the", "");
        w("a", "");
        w("an", "");
        w("as", "作为**");
        w("be", "为");
        w("been", "已");
        w("was", "为");
        w("were", "为");
        w("its", "其");
        w("this", "该");
        w("these", "这些");
        w("those", "那些");
        w("it", "其");
        w("they", "它们");
        w("their", "其");
        w("which", "该**");
        w("that", "该**");
        w("where", "其中**");
        w("when", "当**时");
        w("if", "若**");
        w("then", "则");
        w("also", "也");
        w("not", "不");
        w("without", "无**");
        w("within", "在**内");
        w("during", "在**期间");
        w("after", "在**之后");
        w("before", "在**之前");
        w("between", "在**之间");
        w("through", "通过**");
        w("into", "进入**");
        w("onto", "至**");
        w("over", "超过**");
        w("under", "在**下");
        w("above", "高于**");
        w("below", "低于**");
        w("per", "每");
        w("each", "每个");
        w("both", "两者");
        w("either", "任一");
        w("same", "相同");
        w("different", "不同");
        w("possible", "可能");
        w("required", "需");
        w("necessary", "必要的");
        w("available", "可用的");
        w("present", "存在");
        w("occurs", "发生");
        w("occur", "发生");
        w("result", "结果");
        w("results", "导致**");
        w("including", "包括**");
        w("exposed", "暴露");
        w("exposure", "接触");
        w("contact", "接触");
        w("skin", "皮肤");
        w("eyes", "眼睛");
        w("eye", "眼睛");
        w("lungs", "肺");
        w("breathing", "呼吸");
        w("inhalation", "吸入");
        w("ingestion", "食入");
        w("swallowing", "吞咽");
        w("liquid", "液体");
        w("vapor", "蒸气");
        w("vapors", "蒸气");
        w("mist", "雾");
        w("dust", "粉尘");
        w("powder", "粉末");
        w("solid", "固体");
        w("gas", "气体");
        w("solution", "溶液");
        w("acid", "酸");
        w("index", "总索引");
        w("stabilized", "稳定化");
        w("acids", "酸类");
        w("acidic", "酸性");
        w("alkaline", "碱性");
        w("neutral", "中性");
        w("organic", "有机");
        w("inorganic", "无机");
        w("metal", "金属");
        w("metals", "金属");
        w("alloy", "合金");
        w("salt", "盐");
        w("salts", "盐类");
        w("oxide", "氧化物");
        w("oxides", "氧化物");
        w("under", "在**下");
        w("normal", "常规");
        w("ordinary", "一般");
        w("room", "室温");
        w("ambient", "环境");
        w("storage", "储存");
        w("stored", "储存");
        w("store", "储存");
        w("handling", "操作");
        w("handle", "操作");
        w("use", "使用");
        w("wear", "佩戴");
        w("gloves", "手套");
        w("goggles", "护目镜");
        w("mask", "面罩");
        w("respirator", "呼吸器");
        w("ventilation", "通风");
        w("exhaust", "排风");
        w("spill", "泄漏");
        w("leak", "泄漏");
        w("waste", "废弃物");
        w("disposal", "处置");
        w("dispose", "处置");
        w("transport", "运输");
        w("shipping", "装运");
        w("shipment", "运输");
        w("container", "容器");
        w("containers", "容器");
        w("package", "包装");
        w("packaging", "包装");
        w("label", "标签");
        w("labels", "标签");
        w("placard", "标牌");
        w("marking", "标记");
        w("emergency", "应急");
        w("response", "响应");
        w("guide", "指南");
        w("hazard", "危险");
        w("hazards", "危害");
        w("risk", "风险");
        w("safe", "安全");
        w("safety", "安全");
        w("health", "健康");
        w("environment", "环境");
        w("environmental", "环境的");
        w("protection", "防护");
        w("equipment", "设备");
        w("training", "培训");
        w("permit", "许可");
        w("regulation", "法规");
        w("regulations", "法规");
        w("requirement", "要求");
        w("requirements", "要求");
        w("standard", "标准");
        w("standards", "标准");
        w("authority", "主管部门");
        w("authorities", "主管部门");
        w("nation", "国家");
        w("national", "国家");
        w("international", "国际");
        w("federal", "联邦");
        w("state", "州");
        w("local", "地方");
        w("law", "法律");
        w("laws", "法律");
        w("act", "法案");
        w("code", "规则");
        w("substance", "物质");
        w("substances", "物质");
        w("chemical", "化学品");
        w("chemicals", "化学品");
        w("product", "产品");
        w("products", "产品");
        w("material", "物料");
        w("materials", "物料");
        w("quantity", "数量");
        w("amount", "量");
        w("level", "水平");
        w("levels", "水平");
        w("concentration", "浓度");
        w("dilute", "稀释");
        w("mixture", "混合物");
        w("compound", "化合物");
        w("formula", "化学式");
        w("molecular", "分子");
        w("physical", "物理");
        w("chemical", "化学");
        w("property", "性质");
        w("properties", "性质");
        w("appearance", "外观");
        w("color", "颜色");
        w("colour", "颜色");
        w("colorless", "无色");
        w("colourless", "无色");
        w("white", "白色");
        w("yellow", "黄色");
        w("brown", "棕色");
        w("black", "黑色");
        w("odor", "气味");
        w("odour", "气味");
        w("odorless", "无味");
        w("alcoholic", "醇类");
        w("repulsive", "令人厌恶的");
        w("disagreeable", "令人不快的");
        w("unpleasant", "不愉快的");
        w("pleasant", "令人愉快的");
        w("pungent", "刺鼻的");
        w("penetrating", "刺鼻的");
        w("balsamic", "香脂样");
        w("floral", "花香样");
        w("ethereal", "醚样");
        w("characteristic", "特征性的");
        w("typical", "典型的");
        w("distinctive", "独特的");
        w("mild", "轻微的");
        w("faint", "微弱的");
        w("slight", "轻微的");
        w("strong", "强烈的");
        w("weak", "微弱的");
        w("sweet", "甜的");
        w("crude", "粗品");
        w("pure", "纯品");
        w("purified", "精制");
        w("commercial", "工业级");
        w("grade", "品级");
        w("detectable", "可察觉的");
        w("threshold", "阈值");
        w("recognizable", "可辨认的");
        w("aromatic", "芳香");
        w("ammonia", "氨");
        w("ammoniacal", "氨味");
        w("garlic", "大蒜");
        w("almond", "苦杏仁");
        w("chlorine", "氯");
        w("chlorinous", "氯味");
        w("ester", "酯");
        w("esterlike", "酯样");
        w("acetone", "丙酮");
        w("alcohol", "醇");
        w("solvent", "溶剂");
        w("gasoline", "汽油");
        w("petroleum", "石油");
        w("turpentine", "松节油");
        w("cresol", "甲酚");
        w("phenolic", "酚类的");
        w("phenol", "苯酚");
        w("camphor", "樟脑");
        w("minty", "薄荷样");
        w("musty", "霉味");
        w("suffocating", "令人窒息的");
        w("irritating", "刺激性的");
        w("nauseating", "令人作呕的");
        w("acrid", "辛辣的");
        w("taste", "味道");
        w("melting", "熔化");
        w("boiling", "沸腾");
        w("flash", "闪");
        w("point", "点");
        w("ignition", "着火");
        w("autoignition", "自燃");
        w("decomposition", "分解");
        w("vapor", "蒸气");
        w("pressure", "压力");
        w("density", "密度");
        w("viscosity", "黏度");
        w("solubility", "溶解度");
        w("miscible", "可混溶");
        w("insoluble", "不溶");
        w("volatile", "挥发性");
        w("volatility", "挥发性");
        w("stable", "稳定");
        w("stability", "稳定性");
        w("unstable", "不稳定");
        w("incompatible", "禁配");
        w("corrosive", "腐蚀性");
        w("corrosion", "腐蚀");
        w("oxidizer", "氧化剂");
        w("oxidizers", "氧化剂");
        w("oxidizing", "氧化性");
        w("reducing", "还原性");
        w("flammable", "易燃");
        w("flammability", "易燃性");
        w("combustible", "可燃");
        w("explosive", "爆炸性");
        w("explosion", "爆炸");
        w("fire", "火灾");
        w("toxic", "有毒");
        w("toxicity", "毒性");
        w("poison", "毒物");
        w("harmful", "有害");
        w("irritant", "刺激物");
        w("irritation", "刺激");
        w("sensitizer", "致敏物");
        w("carcinogen", "致癌物");
        w("carcinogenic", "致癌");
        w("mutagen", "致突变物");
        w("teratogen", "致畸物");
        w("reproductive", "生殖");
        w("target", "靶");
        w("organ", "器官");
        w("organs", "器官");
        w("nervous", "神经");
        w("system", "系统");
        w("respiratory", "呼吸");
        w("tract", "道");
        w("skin", "皮肤");
        w("eye", "眼");
        w("kidney", "肾");
        w("liver", "肝");
        w("blood", "血液");
        w("central", "中枢");
        w("peripheral", "外周");
        w("acute", "急性");
        w("chronic", "慢性");
        w("long-term", "长期");
        w("short-term", "短期");
        w("repeated", "反复");
        w("prolonged", "长时间");
        w("excessive", "过度");
        w("overexposure", "过度接触");
        w("dizziness", "头晕");
        w("headache", "头痛");
        w("nausea", "恶心");
        w("vomiting", "呕吐");
        w("unconsciousness", "失去知觉");
        w("death", "死亡");
        w("fatal", "致命");
        w("severe", "严重");
        w("slight", "轻微");
        w("mild", "轻度");
        w("moderate", "中度");
        w("strong", "强");
        w("weak", "弱");
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z\\-']*");

    /**
     * 单词级替换（兜底）。只替换已知词，未知词原样保留。
     * 用于短句（如 "Water soluble."），长句请走 translate()。
     */
    public static String words(String s) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = TOKEN.matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(s, last, m.start());
            String tk = m.group();
            String cn = singleWord(tk);
            sb.append(cn != null ? cn : tk);
            last = m.end();
        }
        sb.append(s.substring(last));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 准入判断

    /**
     * 是否「可安全直译」：只含已知词汇与数字/单位，且长度较短。
     * 长句一律走「概括 + 原文折叠」，避免机翻式生硬中文。
     */
    private static boolean translatable(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (t.length() > 200) return false;                 // 长句不硬翻
        if (hasCn(t)) return false;                          // 已含中文则无需再译
        Matcher m = TOKEN.matcher(t);
        int total = 0, known = 0;
        while (m.find()) {
            total++;
            if (WORDS.containsKey(m.group().toLowerCase())) known++;
        }
        if (total == 0) return true;                        // 纯数值
        return ((double) known / total) >= 0.75;
    }

    /**
     * 按句末标点切句（保留标点）。小数点（"0.78"）与缩写（"No."）不会被误切。
     * 切出的片段会去掉首尾空白；空片段丢弃。
     */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<>();
        // 句末点：前面不是单个大写字母（排除缩写）、后面是空白或结尾
        String[] parts = s.split("(?<=[.!?])\\s+(?=[A-Z0-9])");
        for (String p : parts) {
            String x = p.trim();
            if (!x.isEmpty()) out.add(x);
        }
        return out;
    }

    /** 主入口：把一条 PubChem 英文条目转成中文（保留原文在 origin 字段） */
    public static Map<String, String> translate(String raw) {        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        out.put("origin", raw.replace("\n", " ").trim());
        out.put("zh", translateText(raw));
        return out;
    }

    public static String translateText(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = stripCitation(raw.replace("\n", " ").replaceAll("\\s{2,}", " ").trim());
        if (s.isEmpty()) return null;
        // ⚠️ 不能只判「是否纯 ASCII」——°（U+00B0）、℃、µ 等在英文原文里很常见，
        //    必须用「是否已含汉字」判断，否则 "77.9 °F" 会被误判为已是中文而直接返回。
        if (hasCn(s)) return s;                              // 已是中文

        // ⓪-a 长段落优先尝试「人工概括」：运输法规这类原文动辄 40+ 词、含专有机构名，
        //     逐句机翻必然产出半中半英 → 直接给一句准确的中文概括，远好过垃圾译文。
        if (s.length() >= 180) {
            String sum = summarize(s);
            if (sum != null && isGoodZh(sum)) return sum;
        }

        // ⓪ 多句文本：先按句末切断，逐句翻译再拼回。
        //    整段套句型模板会把「Reacts with A. This generates B.」切碎重排成语序错乱的句子，
        //    逐句处理能保证每句自身语序正确（句数 >= 2 时才走这条路）。
        List<String> sents = splitSentences(s);
        if (sents.size() >= 2) {
            List<String> outs = new ArrayList<>();
            for (String one : sents) {
                String z = translateText(one);
                if (z != null && !z.isBlank()) outs.add(z.endsWith("。") ? z : z + "。");
            }
            if (!outs.isEmpty()) return String.join("", outs);
        }

        // ① 温度与单位换算（°F -> ℃）—— 这一步的成果必须保留到最终返回
        String t = temp(template(s));

        // ② 句型模板（长句优先，避免被词典切成碎片）
        String ps = patternSentence(t);
        if (ps != null) return cleanup(ps);

        // ③ 短语词典替换
        String p = phrase(t);

        // ④ 短句（已知词覆盖率高）才做单词级完结，避免长句被切碎
        if (translatable(p)) {
            String w = cleanup(words(p));
            if (hasCn(w)) return w;
        }

        // ⑤ 长句：中文占比够高、残留英文不多才接受
        String partial = cleanup(p);
        if (hasCn(partial) && cnRatio(partial) >= 0.75 && enWordRun(partial) <= 2) {
            return partial;
        }

        // ⑥ 不硬翻：只要温度/单位已成功换算（字面变化且产出 ℃ 或 ≥ 这类中文标记），
        //    就把换算结果给出去（"20 ℃ 时 31 mmHg" 远优于 "31 mmHg at 68 °F"）。
        //    注意：template() 会插入「时」字，此时 hasCn 为 true，故此处只比较字面差异 +
        //    确认确实发生了「值换算」，避免把纯英文长句误当已翻译。
        String conv = cleanup(t);
        if (!conv.equals(s) && (conv.contains("℃") || conv.contains("≥") || conv.contains("≤")
                || !conv.matches(".*[A-Za-z]{3,}.*"))) {
            return stripCitation(conv);
        }
        // ⑦ 纯物质清单（无动词，如 "Strong mineral acids, oxidizers"）—— 逐词查表
        if (isChemList(s)) {
            String list = chemList(s);
            if (hasCn(list)) return list + "。";
        }
        // ⑧ 兜底：逐词替换仍能产出足够中文时给出（保证「至少大部分看得懂」）
        String wordwise = cleanup(words(phrase(s)));
        if (hasCn(wordwise) && cnRatio(wordwise) >= 0.55) {
            return wordwise;
        }
        return s;
    }

    /** 判断是否「逗号分隔的物质清单」：无动词、无句号结尾整句 */
    private static boolean isChemList(String s) {
        String t = s.trim();
        if (t.isEmpty() || t.length() > 120) return false;
        if (t.split("\\s+").length > 12) return false;
        // 含 these 等动词/功能词就不当清单
        if (Pattern.compile("\\b(is|are|was|were|has|have|can|may|will|must|should|does|do|"
                + "cause|causes|result|react|reacts|resulted|use|used|contain|contains|"
                + "keep|store|avoid|prevent|contact|follow|see|visit)\\b",
                Pattern.CASE_INSENSITIVE).matcher(t).find()) return false;
        return t.contains(",") || t.contains(" and ") || t.contains(";");
    }

    /** 最长连续英文单词串长度（用于判断残留英文是否过多） */
    private static int enWordRun(String s) {
        Matcher m = Pattern.compile("(?:[A-Za-z][A-Za-z\\-']*\\s+){2,}[A-Za-z][A-Za-z\\-']*").matcher(s);
        int max = 0;
        while (m.find()) {
            int n = m.group().trim().split("\\s+").length;
            if (n > max) max = n;
        }
        return max;
    }

    private static final Pattern CN = Pattern.compile("[\\u4e00-\\u9fa5]");

    /**
     * 判断翻译结果是否「够中文、可直接展示」。
     * 拒绝半中半英：中文占比 < 0.5 或仍存在 >=3 个连续英文单词时视为不合格。
     * 调用方据此决定「给 zh」还是「只给英文原文」，避免出现
     * 「可能与strong oxidizers such as chlorine、permanganates、may form explosi」这类残句。
     */
    public static boolean isGoodZh(String s) {
        if (s == null || s.isBlank()) return false;
        if (!hasCn(s)) return false;
        if (cnRatio(s) < 0.6) return false;
        // 残留 3 个及以上连续英文词 -> 不合格（UN/CAS/mmHg 这类缩写不算）
        Matcher m = Pattern.compile("(?:[A-Za-z]{2,}[\\s\\-]+){2,}[A-Za-z]{2,}").matcher(s);
        while (m.find()) {
            String run = m.group();
            String[] ws = run.trim().split("[\\s\\-]+");
            int en = 0;
            for (String w : ws) if (!ALLOW_EN.contains(w.toUpperCase())) en++;
            if (en >= 3) return false;
        }
        // 逐词硬拼的残迹：中文之间仍夹着空格（"粉尘 可能 用于 爆炸性混合物"），
        // 说明语序并未理顺，不宜直接示人。**只统计"孤立单字"**：若空格两侧都是
        // 单字词（如「粉 尘」「可 能」），才判为硬拼残迹；正常词组之间的空格
        // 不在此列（中文术语本身极少用空格，故偶发空格由 cleanup 负责抹平）。
        Matcher hs = Pattern.compile("(?<![\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5])\\s+([\\u4e00-\\u9fa5])(?![\\u4e00-\\u9fa5])").matcher(s);
        int lonely = 0;
        while (hs.find()) lonely++;
        if (lonely >= 3) return false;
        // 英文单复数残留（"混合物s"、"反应s"）—— 汉字后紧跟小写 s 再接非字母边界。
        // ⚠️ 必须排除数词/单位场景（"0.05 s"、"3 s"），只拦「汉字+s」紧贴形式。
        if (Pattern.compile("[\\u4e00-\\u9fa5]s(?![A-Za-z])").matcher(s).find()) return false;
        // 分散的未译实义英文词：计数（不要求连续），>= 2 个即判不合格。
        // 例：「可能与强氧化剂…氯、permanganates、可能生成 explosive compounds 反应」里有
        //     permanganates / compounds / nitric 三个实词，属半成品，应拦下只留原文。
        int enReal = 0;
        Matcher we = WORD_EN.matcher(s);
        while (we.find()) {
            String w = we.group();
            if (ALLOW_EN.contains(w.toUpperCase())) continue;
            enReal++;
        }
        // 单个孤立实义英文词也拦（"…说明。Cool.。" / "…危险。Store only…"）——
        // 中文行文里几乎不可能只夹一个未译英文词，出现即为硬拼残迹。
        if (enReal >= 1) return false;
        // 中文与英文单词「紧贴」出现（无空格分隔，如「产生受热、pressure-rise」不存在空格则更难判；
        // 这类靠 enReal 兜），但「汉字+单个大写字母开头英文」紧贴是典型硬拼残迹
        // （"爆炸性 compounds" 里 compounds 已被计；"pressure-rise" 含连字符需单独看）。
        // 连字符英文短语（x-y）出现在中文中几乎必然是未译残留 → 计数。
        int hyph = 0;
        Matcher hy = Pattern.compile("[A-Za-z]{3,}-[A-Za-z]{3,}").matcher(s);
        while (hy.find()) hyph++;
        if (hyph >= 1) return false;
        // 英文句点 . 紧跟汉字或位于中文串末尾（"Cool.。" / "。Detailed ... "）→ 硬拼残迹
        if (Pattern.compile("[\\u4e00-\\u9fa5]\\.(?![0-9])").matcher(s).find()) return false;
        // 中文里出现 ASCII 句点后接空格再接大写英文（半句未译）
        if (Pattern.compile("\\.\\s+[A-Z][a-z]{3,}").matcher(s).find()) return false;
        return true;
    }

    private static final Pattern WORD_EN = Pattern.compile("[A-Za-z]{4,}");

    /** 允许出现在中文里的英文缩写/代号（不计入「残留英文」） */
    private static final java.util.Set<String> ALLOW_EN = new java.util.HashSet<>(java.util.Arrays.asList(
            "UN", "CAS", "IMDG", "IATA", "DOT", "ERG", "NFPA", "GHS", "EC", "EU", "OSHA", "ACGIH",
            "TLV", "REL", "PEL", "IDLH", "MG", "ML", "KG", "PPM", "MMHG", "KPA", "MPA", "PA",
            "VOL", "WT", "RN", "STEL", "TWA", "C", "F", "MM", "HG",
            // 化工常见缩写/牌号（PPM 已在上方；C1~C4 醇类等）
            "MEHQ", "HQ", "TBC", "BHT", "PTFE", "PVC", "PP", "PE", "PS", "ABS", "EPS", "PTA",
            "MEG", "PAM", "SDS", "MSDS", "PH", "CASRN", "EINECS", "RTECS", "NIOSH", "EPA",
            "TSCA", "CERCLA", "RCRA", "ASTM", "ISO", "GB", "EN", "DIN", "USCG", "NTP", "SRP"));

    /**
     * 摘要专用轻量闸门：{@link #summarize} 的产出是「人工模板 + 已确认术语」拼装的，
     * 结构上必然是通顺中文，残留英文只可能是专有名词（IMDG Code / Reaxys 之类）。
     * 因此这里只拦「中文占比过低」与「成串英文」，不拦单个英文实义词，
     * 否则会把《国际海运危险货物规则》（IMDG Code）这类优质摘要误杀。
     */
    public static boolean isGoodZhLoose(String s) {
        if (s == null || s.isBlank()) return false;
        if (!hasCn(s)) return false;
        if (cnRatio(s) < 0.55) return false;
        // 连续 4 个及以上英文词才算残留（专有名词组合如 "IMDG Code" 仅 2 词，放行）
        Matcher m = Pattern.compile("(?:[A-Za-z]{2,}[\\s\\-]+){3,}[A-Za-z]{2,}").matcher(s);
        if (m.find()) return false;
        // 汉字与 s 紧贴的硬拼残迹仍然拦
        if (Pattern.compile("[\\u4e00-\\u9fa5]s(?![A-Za-z])").matcher(s).find()) return false;
        return true;
    }

    private static boolean hasCn(String s) {
        return CN.matcher(s).find();
    }

    private static double cnRatio(String s) {
        if (s == null || s.isBlank()) return 0;
        int cn = 0, en = 0;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c) && c >= 0x4e00 && c <= 0x9fa5) cn++;
            else if (Character.isLetter(c)) en++;
        }
        return (cn + en) == 0 ? 1.0 : (double) cn / (cn + en);
    }

    /** 收拾替换留下的残迹：多余星号、空格、标点；并把英文句点换成中文句号 */
    private static String cleanup(String s) {
        String t = s;
        t = t.replaceAll("\\*\\*", "");
        t = t.replaceAll("\\*", "");
        // 中文语境里的 & -> 顿号（"氟化物 & 高氯酸盐" -> "氟化物、高氯酸盐"）
        // 中文语境里的半角逗号统一成顿号（"酰氯, 酸酐" -> "酰氯、酸酐"）
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s*,\\s*(?=[\\u4e00-\\u9fa5])", "、");
        // 汉字后接「, 英文词」也转顿号（", acids" -> "、acids"，再由后续步骤翻 acids）
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s*,\\s*(?=[A-Za-z])", "、");
        // 汉字后的半角冒号 -> 中文冒号
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s*:\\s*", "：");
        t = t.replaceAll("\\s*&\\s*", "、");
        // 中文语境下残留的孤立介词/冠词，直接抹掉（"库房 of 不燃结构" -> "库房，不燃结构"）
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5、])\\s+(of|to|in|at|by|for|with|and|the|a|an)\\s+(?=[\\u4e00-\\u9fa5])",
                "，");
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(of|to|in|at|by|for|with|and)\\s*$", "");
        t = t.replaceAll("\\s*、\\s*", "、");
        t = t.replaceAll("[（(]\\s*[)）]", "");
        t = t.replaceAll("\\s+([，。；：、）])", "$1");
        t = t.replaceAll("([（])\\s+", "$1");
        // 汉字之间的多余空格全部抹掉（"该 产生 火灾" -> "该产生火灾"），
        // 但保留「汉字 + 空格 + 数字/英文」这种必要间隔。
        t = t.replaceAll("(?<=[\\u4e00-\\u9fa5、，。；：])\\s+(?=[\\u4e00-\\u9fa5、，。；：])", "");
        t = t.replaceAll("\\s{2,}", " ");
        t = t.replaceAll("^[\\s,;.·，；、]+", "");
        t = t.replaceAll("[\\s,;·]+$", "");
        // 含中文时，把 ". " 形式的句点统一成中文句号
        if (hasCn(t)) {
            t = t.replaceAll("\\s+[.。]\\s*(?=[\\u4e00-\\u9fa5]|$)", "。");
            t = t.replaceAll("\\s*\\.\\s*(?=[\\u4e00-\\u9fa5]|$)", "。");
            t = t.replaceAll("[。]{2,}", "。");
            t = t.replaceAll("\\.。", "。");
            t = t.replaceAll("。\\s*\\.", "。");
            t = t.replaceAll("\\s*\\.\\s*$", "。");
            t = t.replaceAll("[。]\\s+", "。");
        }
        return t.trim();
    }

    // ---------------------------------------------------------------- GHS H 语句

    /** GHS H 语句标准中文（GB 30000 系列 / 联合国 GHS 第 9 修订版官方译本） */
    private static final Map<String, String> GHS_H = new LinkedHashMap<>();

    private static void h(String code, String cn) {
        GHS_H.put(code, cn);
    }

    static {
        h("H200", "不稳定爆炸物");
        h("H201", "爆炸物；整体爆炸危险");
        h("H202", "爆炸物；严重迸射危险");
        h("H203", "爆炸物；起火、爆炸或迸射危险");
        h("H204", "起火或迸射危险");
        h("H205", "遇火可引起整体爆炸");
        h("H206", "起火或爆炸可能性增大；迸射危险增大");
        h("H207", "火灾时可能起火或爆炸；迸射危险增大");
        h("H208", "火灾时起火可能性增大；迸射危险增大");
        h("H220", "极易燃气体");
        h("H221", "易燃气体");
        h("H222", "极易燃气雾剂");
        h("H223", "易燃气雾剂");
        h("H224", "极易燃液体和蒸气");
        h("H225", "高度易燃液体和蒸气");
        h("H226", "易燃液体和蒸气");
        h("H227", "可燃液体");
        h("H228", "易燃固体");
        h("H229", "压力容器：遇热可能爆裂");
        h("H230", "即使没有空气也可能爆炸反应");
        h("H231", "即使在较高压力下也可能爆炸反应");
        h("H240", "加热可能爆炸");
        h("H241", "加热可能起火或爆炸");
        h("H242", "加热可能起火");
        h("H250", "暴露在空气中会自发燃烧");
        h("H251", "自热；可能燃烧");
        h("H252", "大量自热；可能燃烧");
        h("H260", "遇水放出可能自燃的易燃气体");
        h("H261", "遇水放出易燃气体");
        h("H270", "可能引起或加剧燃烧；氧化剂");
        h("H271", "可能引起燃烧或爆炸；强氧化剂");
        h("H272", "可能加剧燃烧；氧化剂");
        h("H280", "内含高压气体；遇热可能爆炸");
        h("H281", "内含冷冻气体；可能造成低温灼伤或损伤");
        h("H290", "可能腐蚀金属");
        h("H300", "吞咽致命");
        h("H301", "吞咽有毒");
        h("H302", "吞咽有害");
        h("H303", "吞咽可能有害");
        h("H304", "吞咽并进入呼吸道可能致命");
        h("H305", "吞咽并进入呼吸道可能有害");
        h("H310", "皮肤接触致命");
        h("H311", "皮肤接触有毒");
        h("H312", "皮肤接触有害");
        h("H313", "皮肤接触可能有害");
        h("H314", "造成严重皮肤灼伤和眼损伤");
        h("H315", "造成皮肤刺激");
        h("H316", "造成轻微皮肤刺激");
        h("H317", "可能导致皮肤过敏反应");
        h("H318", "造成严重眼损伤");
        h("H319", "造成严重眼刺激");
        h("H320", "造成眼刺激");
        h("H330", "吸入致命");
        h("H331", "吸入有毒");
        h("H332", "吸入有害");
        h("H333", "吸入可能有害");
        h("H334", "吸入可能导致过敏或哮喘症状或呼吸困难");
        h("H335", "可能引起呼吸道刺激");
        h("H336", "可能引起昏昏欲睡或眩晕");
        h("H340", "可能导致遗传性缺陷");
        h("H341", "可能引起遗传性缺陷");
        h("H350", "可能致癌");
        h("H351", "怀疑致癌");
        h("H360", "可能损害生育能力或胎儿");
        h("H361", "怀疑损害生育能力或胎儿");
        h("H362", "可能对母乳喂养的儿童造成伤害");
        h("H370", "造成器官损害");
        h("H371", "可能造成器官损害");
        h("H372", "长期或反复接触造成器官损害");
        h("H373", "长期或反复接触可能造成器官损害");
        h("H400", "对水生生物毒性极大");
        h("H401", "对水生生物有毒");
        h("H402", "对水生生物有害");
        h("H410", "对水生生物毒性极大并具有长期持续影响");
        h("H411", "对水生生物有毒并具有长期持续影响");
        h("H412", "对水生生物有害并具有长期持续影响");
        h("H413", "可能对水生生物造成长期持续有害影响");
        h("H420", "破坏高层大气中的臭氧，危害公共健康和环境");
        h("EUH014", "遇水剧烈反应");
        h("EUH018", "使用中可能形成易燃/爆炸性蒸气-空气混合物");
        h("EUH019", "可能形成爆炸性过氧化物");
        h("EUH029", "遇水释放有毒气体");
        h("EUH031", "遇酸释放有毒气体");
        h("EUH032", "遇酸释放剧毒气体");
        h("EUH044", "封闭加热有爆炸危险");
        h("EUH066", "反复接触可能导致皮肤干燥或皲裂");
        h("EUH071", "对呼吸道有腐蚀性");
    }

    /** H 语句代码 -> 中文（命中不了返回 null，由调用方回退英文） */
    public static String hStatement(String code) {
        if (code == null) return null;
        return GHS_H.get(code.trim().toUpperCase());
    }

    // ---------------------------------------------------------------- 联合国危险货物分类串

    /**
     * 把 PubChem 的「UN Classification」串翻成中文。
     * 原文形如：
     *   UN Hazard Class: 3; UN Subsidiary Risks: 6.1; UN Pack Group: II
     *   UN Hazard Class: 8; UN Pack Group: II
     * 输出形如：
     *   危险类别 3（易燃液体）；次要危险性 6.1（毒性物质）；包装类别 Ⅱ（中危险）
     */
    public static String unClass(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        StringBuilder sb = new StringBuilder();
        Matcher m1 = Pattern.compile("UN\\s*Hazard\\s*Class\\s*:?\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m1.find()) {
            String c = m1.group(1);
            sb.append("危险类别 ").append(c);
            String cn = HAZARD_CLASS_CN.get(c);
            if (cn != null) sb.append("（").append(cn).append("）");
        }
        Matcher m2 = Pattern.compile("UN\\s*Subsidiary\\s*Risks?\\s*:?\\s*([0-9.\\s,]+?)(?=;|$)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m2.find()) {
            String[] parts = m2.group(1).trim().split("[,\\s]+");
            List<String> list = new ArrayList<>();
            for (String p : parts) {
                if (p.isBlank()) continue;
                String cn = HAZARD_CLASS_CN.get(p.trim());
                list.add(cn == null ? p.trim() : p.trim() + "（" + cn + "）");
            }
            if (!list.isEmpty()) {
                if (sb.length() > 0) sb.append("；");
                sb.append("次要危险性 ").append(String.join("、", list));
            }
        }
        Matcher m3 = Pattern.compile("UN\\s*Pack(?:ing)?\\s*Group\\s*:?\\s*([IVX]+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (m3.find()) {
            String g = m3.group(1).toUpperCase();
            String cn = packGroup(g);
            if (sb.length() > 0) sb.append("；");
            sb.append("包装类别 ").append(g).append(cn == null ? "" : "（" + cn + "）");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 联合国危险货物类别号 -> 中文名称（TDG / GB 6944） */
    private static final Map<String, String> HAZARD_CLASS_CN = new LinkedHashMap<>();
    static {
        HAZARD_CLASS_CN.put("1", "爆炸品");
        HAZARD_CLASS_CN.put("2.1", "易燃气体");
        HAZARD_CLASS_CN.put("2.2", "非易燃无毒气体");
        HAZARD_CLASS_CN.put("2.3", "毒性气体");
        HAZARD_CLASS_CN.put("3", "易燃液体");
        HAZARD_CLASS_CN.put("4.1", "易燃固体");
        HAZARD_CLASS_CN.put("4.2", "易于自燃的物质");
        HAZARD_CLASS_CN.put("4.3", "遇水放出易燃气体的物质");
        HAZARD_CLASS_CN.put("5.1", "氧化性物质");
        HAZARD_CLASS_CN.put("5.2", "有机过氧化物");
        HAZARD_CLASS_CN.put("6.1", "毒性物质");
        HAZARD_CLASS_CN.put("6.2", "感染性物质");
        HAZARD_CLASS_CN.put("7", "放射性物质");
        HAZARD_CLASS_CN.put("8", "腐蚀性物质");
        HAZARD_CLASS_CN.put("9", "杂项危险物质和物品");
    }

    /** 包装类别 -> 中文（TDG / GB 6944） */
    private static String packGroup(String g) {
        if (g == null) return null;
        switch (g.toUpperCase()) {
            case "I": return "高危险";
            case "II": return "中危险";
            case "III": return "低危险";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- 运输/储存常用概括

    /**
     * 对长句给出「一句中文概括」（不逐字翻），用于运输法规这类原文很长的条目。
     * 返回 null 表示没有合适的概括，前端只展示原文。
     */
    public static String summarize(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase();
        if (s.contains("iata") && s.contains("dangerous goods regulations")) {
            return "国际航空运输协会（IATA）《危险货物运输规则》：由 IATA 危险品委员会依据第 618、619 号决议发布，"
                    + "所有 IATA 成员航空公司在运输危险货物时均须遵守。";
        }
        if (s.contains("international maritime dangerous goods code")) {
            return "《国际海运危险货物规则》（IMDG Code）：规定运输危险化学品的基本原则，"
                    + "并按类别给出具体物质的详细建议与良好操作规范；装运任何物质或物品前应查阅其技术名称总索引，"
                    + "以确定适用的运输程序。";
        }
        if (s.contains("no person may") && s.contains("transport")) {
            return "法规规定：未经许可不得运输该危险货物；承运方须持有相应危险货物运输资质。";
        }
        if (s.contains("reportable quantity")) {
            return "美国法规规定该物质泄漏达到「应报告量」时，须向主管部门报告。";
        }
        if (s.contains("permissible exposure limit") || s.contains("threshold limit value")) {
            return "职业接触限值（工作场所空气中允许浓度）相关规定。";
        }
        return null;
    }
}
