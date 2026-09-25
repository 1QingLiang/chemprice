package com.datamarket.controller;

import com.datamarket.common.Result;
import com.datamarket.util.ChemZh;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import com.datamarket.service.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物性查询：聚合权威化工数据源，输出「四段式」结构化资料。
 *
 * 四段结构（面向采购 / 运输 / 报价决策）：
 *   一、基础简介        —— CAS · 分子式 · 分子量 · 外观气味 · 熔点沸点密度 · 主流规格
 *   二、是不是危化品    —— 危险类别 · UN 编号 · 包装类别 · 闪点 · 自燃温度 · GHS · 资质提示
 *   三、运输要求        —— DOT 应急指南 · 运输法规 · 运输禁忌 · 储存条件
 *   四、贸易简要参考    —— 平台现货价格区间（按地区/规格）· 近 30 日走势 · 个性化
 *
 * 数据来源（全部为公开、可合法引用的权威机构）：
 *   - PubChem PUG-View（美国国立卫生研究院 NIH）—— 危险分类/UN/包装类别/闪点/熔点/运输禁忌等
 *   - PubChem PUG-REST（NIH）—— 分子式/分子量/CAS/SMILES/InChI/IUPAC 名
 *   - HSDB（美国国家医学图书馆 NLM）—— 经 PubChem 聚合的危险物质数据
 *   - 平台自营行情 —— 第四段价格数据，ChemPrice 自主采集
 *
 * 说明：
 *   1. PubChem 不支持中文名检索（实测中文名直查 0 命中），故服务端维护品种中文名 -> 英文名 映射。
 *   2. 包装类别藏在 PUG-View 的 "UN Classification" 里（形如 "UN Hazard Class: 3; UN Pack Group: II"）。
 *      按 heading="Packing Group" 单独查会返回 400（实测），必须从 UN Classification 解析。
 *   3. 非危险品（如 PTA、己内酰胺）本来就没有 UN 号/包装类别，这是正常现象。
 *   4. ⛔ Reaxys / SciFinder 为付费版权库，只做带名跳转，绝不抓取落地。
 */
@RestController
@RequestMapping("/api/chem")
@RequiredArgsConstructor
public class ChemController {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    private static final String PUBCHEM_BASE =
            "https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/";
    private static final String PUGVIEW_BASE =
            "https://pubchem.ncbi.nlm.nih.gov/rest/pug_view/data/compound/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object[]> CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000;

    // ============================================================ 映射表
    private static final Map<String, String[]> COMPOUNDS = new HashMap<>();
    private static final Map<String, String[]> POLYMERS = new HashMap<>();

    private static void c(String cn, String en, String cas) {
        COMPOUNDS.put(cn, new String[]{en, cas});
    }

    private static void p(String cn, String en, String cas) {
        POLYMERS.put(cn, new String[]{en, cas});
    }

    // ==== 2026-09-23 一档扩展（PubChem 逐条核验，有唯一 CID） ====
    static {
        c("乙烷", "ethane", "74-84-0");
        c("氯乙烯", "vinyl chloride", "75-01-4");
        c("亚硝酸钠", "sodium nitrite", "7632-00-0");
        c("亚磷酸", "phosphorous acid", "10294-56-1");
        c("合成氨", "ammonia", "7664-41-7");
        c("工业氨水", "ammonium hydroxide", "1336-21-6");
        c("吡虫啉", "imidacloprid", "138261-41-3");
        c("啶虫脒", "acetamiprid", "160430-64-8");
        c("毒死蜱", "chlorpyrifos", "2921-88-2");
        c("氧化铝", "aluminium oxide", "1344-28-1");
        c("氯化胆碱", "choline chloride", "67-48-1");
        c("氯化铵", "ammonium chloride", "12125-02-9");
        c("液氯", "chlorine", "7782-50-5");
        c("液碱", "sodium hydroxide", "1310-73-2");
        c("焦亚硫酸钠", "sodium metabisulfite", "7681-57-4");
        c("片碱", "sodium hydroxide", "1310-73-2");
        c("硝酸钠", "sodium nitrate", "7631-99-4");
        c("硝酸钾", "potassium nitrate", "7757-79-1");
        c("硝酸铵钙", "calcium ammonium nitrate", "15245-12-2");
        c("硫磺粉", "sulfur", "7704-34-9");
        c("硫酸亚铁", "iron(2+) sulfate", "7720-78-7");
        c("硫酸镁", "magnesium sulfate", "7487-88-9");
        c("碳酸氢铵", "ammonium bicarbonate", "1066-33-7");
        c("磷酸一钠", "sodium dihydrogen phosphate", "7558-80-7");
        c("磷酸三钠", "trisodium phosphate", "7601-54-9");
        c("磷酸二氢钾", "potassium dihydrogen phosphate", "7778-77-0");
        c("磷酸二钠", "sodium hydrogen phosphate", "7558-79-4");
        c("磷酸氢钙", "calcium hydrogen phosphate", "7757-93-9");
        c("草甘膦", "glyphosate", "1071-83-6");
        c("草铵膦", "glufosinate", "77182-82-2");
        c("赤磷", "red phosphorus", "12185-10-3");
        c("车用尿素", "urea", "57-13-6");
        c("DMP", "dimethyl phthalate", "131-11-3");
        c("DIBP", "diisobutyl phthalate", "84-69-5");
        c("DOA", "dioctyl adipate", "123-79-5");
        c("ATBC", "acetyl tributyl citrate", "77-90-7");
        c("TOTM", "trioctyl trimellitate", "3319-31-1");
        c("DPHP", "di(2-propylheptyl) phthalate", "53306-54-0");
        c("DCP", "dicumyl peroxide", "80-43-3");
        c("ADC", "azodicarbonamide", "123-77-3");
        c("TGIC", "triglycidyl isocyanurate", "2451-62-9");
        c("PM", "propylene glycol methyl ether", "1320-67-8");
        c("CAC", "ethylene glycol monoethyl ether acetate", "111-15-9");
        c("对苯二甲酸", "terephthalic acid", "100-21-0");
        c("1,4-丁二醇", "1,4-butanediol", "110-63-4");
        c("己二酸", "adipic acid", "124-04-9");
        c("顺酐", "maleic anhydride", "108-31-6");
        c("苯酐", "phthalic anhydride", "85-44-9");
    }

    static {
        c("甲醇", "methanol", "67-56-1");
        c("甲醛", "formaldehyde", "50-00-0");
        c("MMA", "methyl methacrylate", "80-62-6");
        c("乌洛托品", "methenamine", "100-97-0");
        c("丙二醇", "propylene glycol", "57-55-6");
        c("糠醇", "furfuryl alcohol", "98-00-0");
        c("对硝基氯苯", "1-chloro-4-nitrobenzene", "100-00-5");
        c("邻硝基氯苯", "1-chloro-2-nitrobenzene", "88-73-3");
        c("丙烯酸异冰片酯", "isobornyl acrylate", "5888-33-5");
        c("三氯氧磷", "phosphorus oxychloride", "10025-87-3");
        c("马拉硫磷", "malathion", "121-75-5");
        c("糠醛", "furfural", "98-01-1");
        c("乙醇胺", "ethanolamine", "141-43-5");
        c("叔丁胺", "tert-butylamine", "75-64-9");
        c("双环戊二烯", "dicyclopentadiene", "77-73-6");
        c("异戊二烯", "isoprene", "78-79-5");
        c("间戊二烯", "1,3-pentadiene", "504-60-9");
        c("异戊烯", "2-methyl-2-butene", "513-35-9");
        c("松香", "rosin", "8050-09-7");
        c("正戊烷", "pentane", "109-66-0");
        c("异戊烷", "isopentane", "78-78-4");
        c("环戊烷", "cyclopentane", "287-92-3");
        c("石油萘", "naphthalene", "91-20-3");
        c("乙烯", "ethylene", "74-85-1");
        c("1-辛烯", "1-octene", "111-66-0");
        c("1-己烯", "1-hexene", "592-41-6");
        c("丁烯-1", "1-butene", "106-98-9");
        c("丙烯", "propylene", "115-07-1");
        c("丁二烯", "1,3-butadiene", "106-99-0");
        c("异丁烯", "isobutylene", "115-11-7");
        c("纯苯", "benzene", "71-43-2");
        c("加氢苯", "benzene", "71-43-2");
        c("氯化苯", "chlorobenzene", "108-90-7");
        c("甲苯", "toluene", "108-88-3");
        c("二甲苯", "xylene", "1330-20-7");
        c("苯乙烯", "styrene", "100-42-5");
        c("PX", "p-xylene", "106-42-3");
        c("邻二甲苯", "o-xylene", "95-47-6");
        c("间二甲苯", "m-xylene", "108-38-3");
        c("乙苯", "ethylbenzene", "100-41-4");
        c("异丙基苯", "cumene", "98-82-8");
        c("均四甲苯", "durene", "95-93-2");
        c("间苯二甲酸", "isophthalic acid", "121-91-5");
        c("乙醇", "ethanol", "64-17-5");
        c("二乙二醇", "diethylene glycol", "111-46-6");
        c("碳酸二甲酯", "dimethyl carbonate", "616-38-6");
        c("正丙醇", "1-propanol", "71-23-8");
        c("二甲醚", "dimethyl ether", "115-10-6");
        c("甲缩醛", "dimethoxymethane", "109-87-5");
        c("二氯甲烷", "dichloromethane", "75-09-2");
        c("二氯丙烷", "1,2-dichloropropane", "78-87-5");
        c("三氯甲烷", "chloroform", "67-66-3");
        c("季戊四醇", "pentaerythritol", "115-77-5");
        c("苯酚", "phenol", "108-95-2");
        c("丙酮", "acetone", "67-64-1");
        c("丁酮", "butanone", "78-93-3");
        c("环己酮", "cyclohexanone", "108-94-1");
        c("双酚A", "bisphenol A", "80-05-7");
        c("MIBK", "methyl isobutyl ketone", "108-10-1");
        c("丙酮氰醇", "acetone cyanohydrin", "75-86-5");
        c("异佛尔酮", "isophorone", "78-59-1");
        c("异丙醚", "diisopropyl ether", "108-20-3");
        c("异丙醇", "isopropyl alcohol", "67-63-0");
        c("正丁醇", "1-butanol", "71-36-3");
        c("异丁醇", "isobutanol", "78-83-1");
        c("辛醇", "1-octanol", "111-87-5");
        c("2-丙基庚醇", "2-propylheptanol", "10042-59-8");
        c("异丁醛", "isobutyraldehyde", "78-84-2");
        c("叔丁醇", "tert-butanol", "75-65-0");
        c("二丙二醇", "dipropylene glycol", "25265-71-8");
        c("三丙二醇", "tripropylene glycol", "24800-44-0");
        c("新戊二醇", "neopentyl glycol", "126-30-7");
        c("冰醋酸", "acetic acid", "64-19-7");
        c("醋酸甲酯", "methyl acetate", "79-20-9");
        c("醋酸乙酯", "ethyl acetate", "141-78-6");
        c("醋酸丁酯", "butyl acetate", "123-86-4");
        c("醋酸正丙酯", "propyl acetate", "109-60-4");
        c("氯乙酸", "chloroacetic acid", "79-11-8");
        c("醋酸乙烯", "vinyl acetate", "108-05-4");
        c("醋酐", "acetic anhydride", "108-24-7");
        c("丙烯酸", "acrylic acid", "79-10-7");
        c("丙烯酸甲酯", "methyl acrylate", "96-33-3");
        c("丙烯酸乙酯", "ethyl acrylate", "140-88-5");
        c("丙烯酸丁酯", "butyl acrylate", "141-32-2");
        c("丙烯酸异辛酯", "2-ethylhexyl acrylate", "103-11-7");
        c("甲基丙烯酸羟乙酯", "2-hydroxyethyl methacrylate", "868-77-9");
        c("甲基丙烯酸羟丙酯", "2-hydroxypropyl methacrylate", "27813-02-1");
        c("丙烯酸羟乙酯", "2-hydroxyethyl acrylate", "818-61-1");
        c("丙烯酸羟丙酯", "2-hydroxypropyl acrylate", "999-61-1");
        c("甲基丙烯酸", "methacrylic acid", "79-41-4");
        c("甲基丙烯酸正丁酯", "butyl methacrylate", "97-88-1");
        c("甲基丙烯酸缩水甘油酯", "glycidyl methacrylate", "106-91-2");
        c("乙二醇", "ethylene glycol", "107-21-1");
        c("PTA", "terephthalic acid", "100-21-0");
        c("丙烯腈", "acrylonitrile", "107-13-1");
        c("己内酰胺", "caprolactam", "105-60-2");
        c("苯胺", "aniline", "62-53-3");
        c("环氧丙烷", "propylene oxide", "75-56-9");
        c("环氧乙烷", "ethylene oxide", "75-21-8");
        c("二氯乙烷", "1,2-dichloroethane", "107-06-2");
        c("环氧氯丙烷", "epichlorohydrin", "106-89-8");
        c("环己烷", "cyclohexane", "110-82-7");
        c("乙腈", "acetonitrile", "75-05-8");
        c("丙烯酰胺", "acrylamide", "79-06-1");
        c("碳酸二乙酯", "diethyl carbonate", "105-58-8");
        c("碳酸甲乙酯", "ethyl methyl carbonate", "623-53-0");
        c("碳酸丙烯酯", "propylene carbonate", "108-32-7");
        c("PMA", "propylene glycol methyl ether acetate", "108-65-6");
        c("乙二醇丁醚", "2-butoxyethanol", "111-76-2");
        c("二乙二醇丁醚", "diethylene glycol monobutyl ether", "112-34-5");
        c("二乙二醇单乙烯基醚", "diethylene glycol monovinyl ether", "929-37-3");
        c("甲基烯丙醇", "methallyl alcohol", "513-42-8");
        c("异戊烯醇", "prenol", "556-82-1");
        c("环己胺", "cyclohexylamine", "108-91-8");
        c("乙二胺", "ethylenediamine", "107-15-3");
        c("二乙烯三胺", "diethylenetriamine", "111-40-0");
        c("二乙醇单异丙醇胺", "diethanolisopropanolamine", "6712-98-7");
        c("甘氨酸", "glycine", "56-40-6");
        c("苯甲酸", "benzoic acid", "65-85-0");
        c("氯化苄", "benzyl chloride", "100-44-7");
        c("一氯甲烷", "chloromethane", "74-87-3");
        c("苯酐", "phthalic anhydride", "85-44-9");
        c("顺酐", "maleic anhydride", "108-31-6");
        c("偏苯三酸酐", "trimellitic anhydride", "552-30-7");
        c("均苯四甲酸酐", "pyromellitic dianhydride", "89-32-7");
        c("均苯四甲酸二酐", "pyromellitic dianhydride", "89-32-7");
        c("甲基四氢苯酐", "methyltetrahydrophthalic anhydride", "26590-20-5");
        c("DOS", "dioctyl sebacate", "2432-87-3");
        c("TBC", "tributyl citrate", "77-94-1");
        c("氯化石蜡", "chlorinated paraffin", "63449-39-8");
        c("三氯乙烯", "trichloroethylene", "79-01-6");
        c("四氯乙烯", "tetrachloroethylene", "127-18-4");
        c("三氯乙烷", "1,1,1-trichloroethane", "71-55-6");
        c("四氯化碳", "carbon tetrachloride", "56-23-5");
        c("二甲苯胺", "N,N-dimethylaniline", "121-69-7");
        c("萘", "naphthalene", "91-20-3");
        c("蒽", "anthracene", "120-12-7");
        c("喹啉", "quinoline", "91-22-5");
        c("吡啶", "pyridine", "110-86-1");
        c("哌啶", "piperidine", "110-89-4");
        c("吗啉", "morpholine", "110-91-8");
        c("硫脲", "thiourea", "62-56-6");
        c("二硫化碳", "carbon disulfide", "75-15-0");
        c("硫酸二甲酯", "dimethyl sulfate", "77-78-1");
        c("氯磺酸", "chlorosulfonic acid", "7790-94-5");
        c("发烟硫酸", "sulfuric acid", "7664-93-9");
        c("磷酸", "phosphoric acid", "7664-38-2");
        c("氢氟酸", "hydrofluoric acid", "7664-39-3");
        c("硝酸", "nitric acid", "7697-37-2");
        c("盐酸", "hydrochloric acid", "7647-01-0");
        c("硫酸", "sulfuric acid", "7664-93-9");
        c("烧碱", "sodium hydroxide", "1310-73-2");
        c("纯碱", "sodium carbonate", "497-19-8");
        c("硫化钠", "sodium sulfide", "1313-82-2");
        c("硫氢化钠", "sodium hydrosulfide", "16721-80-5");
        c("氯化钙", "calcium chloride", "10043-52-4");
        c("氯化钠", "sodium chloride", "7647-14-5");
        c("双氧水", "hydrogen peroxide", "7722-84-1");
        c("钛白粉", "titanium dioxide", "13463-67-7");
        c("黄磷", "white phosphorus", "7723-14-0");
        c("磷酸一铵", "monoammonium phosphate", "7722-76-1");
        c("磷酸二铵", "diammonium phosphate", "7783-28-0");
        c("尿素", "urea", "57-13-6");
        c("氯化钾", "potassium chloride", "7447-40-7");
        c("硫酸钾", "potassium sulfate", "7778-80-5");
        c("硝酸铵", "ammonium nitrate", "6484-52-2");
        c("硫酸铵", "ammonium sulfate", "7783-20-2");
        c("碳酸锂", "lithium carbonate", "554-13-2");
        c("氢氧化锂", "lithium hydroxide", "1310-65-2");
        c("硫酸钴", "cobalt sulfate", "10124-43-3");
        c("硫酸镍", "nickel sulfate", "7786-81-4");
        c("硫酸锰", "manganese sulfate", "7785-87-7");
        c("氯化钴", "cobalt chloride", "7646-79-9");
        c("环氧大豆油", "epoxidized soybean oil", "8013-07-8");
        c("三乙胺", "triethylamine", "121-44-8");
        c("二甲基甲酰胺", "dimethylformamide", "68-12-2");
        c("二甲基乙酰胺", "dimethylacetamide", "127-19-5");
        c("NMP", "N-methyl-2-pyrrolidone", "872-50-4");
        c("二甲基亚砜", "dimethyl sulfoxide", "67-68-5");
        c("四氢呋喃", "tetrahydrofuran", "109-99-9");
        c("二氧六环", "1,4-dioxane", "123-91-1");
        c("二甲硫醚", "dimethyl sulfide", "75-18-3");
        c("甲硫醇", "methanethiol", "74-93-1");
        c("乙硫醇", "ethanethiol", "75-08-1");
        c("正己烷", "hexane", "110-54-3");
        c("正庚烷", "heptane", "142-82-5");
        c("石油醚", "petroleum ether", "8032-32-4");
        c("白油", "mineral oil", "8042-47-5");
        c("液体石蜡", "liquid paraffin", "8042-47-5");
        c("甲醇钠", "sodium methoxide", "124-41-4");
        c("乙醇钠", "sodium ethoxide", "141-52-6");
        c("叔丁醇钾", "potassium tert-butoxide", "865-47-4");
        c("氢化钠", "sodium hydride", "7646-69-7");
        c("硼氢化钠", "sodium borohydride", "16940-66-2");
        c("金属钠", "sodium", "7440-23-5");
        c("锌粉", "zinc", "7440-66-6");
        c("铝粉", "aluminum", "7429-90-5");
        c("镁锭", "magnesium", "7439-95-4");
        c("硅铁", "ferrosilicon", "8049-17-0");
        c("电石", "calcium carbide", "75-20-7");
        c("炭黑", "carbon black", "1333-86-4");
        c("活性炭", "activated carbon", "7440-44-0");
        c("石墨", "graphite", "7782-42-5");
        c("硫磺", "sulfur", "7704-34-9");
        c("三聚氰胺", "melamine", "108-78-1");
        c("己二酸", "adipic acid", "124-04-9");
        c("癸二酸", "sebacic acid", "111-20-6");
        c("富马酸", "fumaric acid", "110-17-8");
        c("柠檬酸", "citric acid", "77-92-9");
        c("乳酸", "lactic acid", "50-21-5");
        c("草酸", "oxalic acid", "144-62-7");
        c("甲酸", "formic acid", "64-18-6");
        c("丙酸", "propionic acid", "79-09-4");
        c("丙烯酸异丁酯", "isobutyl acrylate", "106-63-8");
        c("醋酸异丙酯", "isopropyl acetate", "108-21-4");
        c("醋酸仲丁酯", "sec-butyl acetate", "105-46-4");
        c("醋酸异丁酯", "isobutyl acetate", "110-19-0");
        c("乙酰乙酸甲酯", "methyl acetoacetate", "105-45-3");
        c("乙酰乙酸乙酯", "ethyl acetoacetate", "141-97-9");
        c("氯乙酸甲酯", "methyl chloroacetate", "96-34-4");
        c("溴素", "bromine", "7726-95-6");
        c("碘", "iodine", "7553-56-2");
        c("氯化亚砜", "thionyl chloride", "7719-09-7");
        c("氢溴酸", "hydrobromic acid", "10035-10-6");
        c("五氧化二磷", "phosphorus pentoxide", "1314-56-3");
        c("三氯化磷", "phosphorus trichloride", "7719-12-2");
        c("五氯化磷", "phosphorus pentachloride", "10026-13-8");
        c("聚合氯化铝", "polyaluminium chloride", "1327-41-9");
        c("硫酸铝", "aluminum sulfate", "10043-01-3");
        c("氯化镁", "magnesium chloride", "7786-30-3");
        c("氧化镁", "magnesium oxide", "1309-48-4");
        c("氢氧化铝", "aluminum hydroxide", "21645-51-2");
        c("硅胶", "silica gel", "112926-00-8");
        c("白炭黑", "silicon dioxide", "7631-86-9");
        c("乙醛", "acetaldehyde", "75-07-0");
        c("丙醛", "propionaldehyde", "123-38-6");
        c("正丁醛", "butyraldehyde", "123-72-8");
        c("丙烯醛", "acrolein", "107-02-8");
        c("苯甲醛", "benzaldehyde", "100-52-7");
        c("甲基环己烷", "methylcyclohexane", "108-87-2");
        c("对苯二酚", "hydroquinone", "123-31-9");
        c("间苯二酚", "resorcinol", "108-46-3");
        c("对羟基苯甲醚", "4-methoxyphenol", "150-76-5");
        c("BHT", "butylated hydroxytoluene", "128-37-0");
        c("抗氧剂1010", "pentaerythritol tetrakis", "6683-19-8");
        c("硬脂酸", "stearic acid", "57-11-4");
        c("硬脂酸钙", "calcium stearate", "1592-23-0");
        c("硬脂酸锌", "zinc stearate", "557-05-1");
        c("油酸", "oleic acid", "112-80-1");
        c("甘油", "glycerol", "56-81-5");
        c("月桂酸", "lauric acid", "143-07-7");
        c("棕榈酸", "palmitic acid", "57-10-3");
        c("山梨醇", "sorbitol", "50-70-4");
        c("木糖醇", "xylitol", "87-99-0");
        c("葡萄糖", "glucose", "50-99-7");
        c("蔗糖", "sucrose", "57-50-1");
        c("淀粉", "starch", "9005-25-8");
        c("木质素", "lignin", "9005-53-2");
        c("纤维素", "cellulose", "9004-34-6");
        c("醋酸纤维素", "cellulose acetate", "9004-35-7");
        c("羧甲基纤维素", "carboxymethyl cellulose", "9004-32-4");
        c("羟乙基纤维素", "hydroxyethyl cellulose", "9004-62-0");
        c("聚乙烯醇", "polyvinyl alcohol", "9002-89-5");
        c("PVP", "polyvinylpyrrolidone", "9003-39-8");
        c("聚乙二醇", "polyethylene glycol", "25322-68-3");
        c("聚丙烯酰胺", "polyacrylamide", "9003-05-8");
        c("环氧树脂", "bisphenol A diglycidyl ether", "1675-54-3");
        c("酚醛树脂", "phenol formaldehyde resin", "9003-35-4");
        c("MDI", "methylene diphenyl diisocyanate", "101-68-8");
        c("TDI", "toluene diisocyanate", "26471-62-5");
        c("己二胺", "hexamethylenediamine", "124-09-4");
        c("DOP", "dioctyl phthalate", "117-81-7");
        c("DBP", "dibutyl phthalate", "84-74-2");
        c("DINP", "diisononyl phthalate", "28553-12-0");
        c("DOTP", "dioctyl terephthalate", "6422-86-2");
        c("氯化聚乙烯", "chlorinated polyethylene", "64754-90-1");
        c("发泡剂", "azodicarbonamide", "123-77-3");
        c("AC发泡剂", "azodicarbonamide", "123-77-3");
        c("偶氮二异丁腈", "azobisisobutyronitrile", "78-67-1");
        c("过硫酸铵", "ammonium persulfate", "7727-54-0");
        c("过硫酸钾", "potassium persulfate", "7727-21-1");
        c("过氧化苯甲酰", "benzoyl peroxide", "94-36-0");
        c("叔丁基过氧化氢", "tert-butyl hydroperoxide", "75-91-2");
        c("异丙苯过氧化氢", "cumene hydroperoxide", "80-15-9");

        p("PP", "propylene", "115-07-1");
        p("PE", "ethylene", "74-85-1");
        p("PVC", "vinyl chloride", "75-01-4");
        p("PS", "styrene", "100-42-5");
        p("ABS", "acrylonitrile", "107-13-1");
        p("EPS", "styrene", "100-42-5");
        p("PET", "ethylene glycol", "107-21-1");
        p("PC", "bisphenol A", "80-05-7");
        p("PA6", "caprolactam", "105-60-2");
        p("PA66", "hexamethylenediamine", "124-09-4");
        p("POM", "formaldehyde", "50-00-0");
        p("PMMA", "methyl methacrylate", "80-62-6");
        p("EVA", "vinyl acetate", "108-05-4");
        p("SBS", "styrene", "100-42-5");
        p("SBR", "1,3-butadiene", "106-99-0");
        p("EPDM", "ethylene", "74-85-1");
        p("TPU", "methylene diphenyl diisocyanate", "101-68-8");
        p("PBT", "terephthalic acid", "100-21-0");
        p("PPS", "dichlorobenzene", "106-46-7");
        p("PVDF", "vinylidene fluoride", "75-38-7");
        p("PTFE", "tetrafluoroethylene", "116-14-3");
        p("PEEK", "hydroquinone", "123-31-9");
        p("PU", "methylene diphenyl diisocyanate", "101-68-8");
        p("SAP", "acrylic acid", "79-10-7");
        p("PPG", "propylene oxide", "75-56-9");
        p("PEG", "ethylene oxide", "75-21-8");
        p("PVA", "vinyl acetate", "108-05-4");
        p("PA", "caprolactam", "105-60-2");
        p("PPO", "2,6-dimethylphenol", "576-26-1");
        p("ASA", "acrylonitrile", "107-13-1");
        p("HIPS", "styrene", "100-42-5");
        p("GPPS", "styrene", "100-42-5");
        p("LDPE", "ethylene", "74-85-1");
        p("HDPE", "ethylene", "74-85-1");
        p("LLDPE", "ethylene", "74-85-1");
        p("PP-R", "propylene", "115-07-1");
        p("CPE", "ethylene", "74-85-1");
        p("TPE", "styrene", "100-42-5");
        p("TPV", "ethylene", "74-85-1");
        p("硅橡胶", "dimethylsiloxane", "9006-65-9");
    }

    // ============================================================ 权威数据源
    private static final List<Map<String, String>> SOURCES = List.of(
            src("PubChem", "美国国立卫生研究院（NIH）", "物性 · 分子式 · CAS · 危险分类 · UN 号 · 运输",
                    "https://pubchem.ncbi.nlm.nih.gov/", "free"),
            src("HSDB 危险物质数据库", "美国国家医学图书馆（NLM）", "危害 · 运输 · 储存 · 应急处理",
                    "https://pubchem.ncbi.nlm.nih.gov/source/hsdb", "free"),
            src("NIST Chemistry WebBook", "美国国家标准与技术研究院（NIST）", "热力学 · 光谱 · 相变数据",
                    "https://webbook.nist.gov/chemistry/", "free"),
            src("ChemSpider", "英国皇家化学会（RSC）", "物性 · 文献 · 光谱",
                    "https://www.chemspider.com/", "free"),
            src("Organic Syntheses", "同行评议有机合成实验集", "经验证的合成步骤",
                    "https://www.orgsyn.org/", "free"),
            src("Google Patents", "Google 专利检索", "合成工艺专利", "https://patents.google.com/", "free"),
            src("ECHA", "欧洲化学品管理局", "法规 · MSDS · 危害分类",
                    "https://echa.europa.eu/", "free"),
            src("Reaxys", "Elsevier（爱思唯尔）", "合成路线 · 反应检索", "https://www.reaxys.com/", "paid"),
            src("SciFinderⁿ", "CAS（美国化学文摘社）", "合成方法 · 文献检索",
                    "https://scifinder-n.cas.org/", "paid")
    );

    private static Map<String, String> src(String name, String org, String scope,
                                           String url, String access) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("org", org);
        m.put("scope", scope);
        m.put("url", url);
        m.put("access", access);
        return m;
    }

    // ============================================================ 接口

    @GetMapping("/commodities")
    public Result<List<Map<String, Object>>> commodities(@RequestParam(defaultValue = "") String q) {
        String sql = "SELECT varieties_id, name, category FROM commodity WHERE status=1";
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql += " AND (name LIKE ? OR category LIKE ?)";
            args.add("%" + q.trim() + "%");
            args.add("%" + q.trim() + "%");
        }
        sql += " ORDER BY category, name";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args.toArray());
        for (Map<String, Object> r : rows) {
            String name = String.valueOf(r.get("name"));
            r.put("covered", COMPOUNDS.containsKey(name) || POLYMERS.containsKey(name));
            r.put("isPolymer", POLYMERS.containsKey(name));
        }
        return Result.ok(rows);
    }

    /**
     * 物性查询主接口（四段式结构化资料）。
     *
     * @param name 品种中文名（优先）
     * @param id   品种 varietiesId（二选一）
     */
    @GetMapping("/lookup")
    public Result<Map<String, Object>> lookup(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Integer id) {

        String cn = name != null ? name.trim() : "";
        Integer vid = id;
        if (cn.isEmpty() && vid != null) {
            List<Map<String, Object>> r = jdbcTemplate.queryForList(
                    "SELECT name FROM commodity WHERE varieties_id=? LIMIT 1", vid);
            if (!r.isEmpty()) cn = String.valueOf(r.get(0).get("name"));
        }
        if (cn.isEmpty()) return Result.error("请提供 name 或 id");

        if (vid == null) {
            List<Map<String, Object>> r = jdbcTemplate.queryForList(
                    "SELECT varieties_id FROM commodity WHERE name=? AND status=1 LIMIT 1", cn);
            if (!r.isEmpty()) vid = ((Number) r.get(0).get("varieties_id")).intValue();
        }

        // ⭐ 记录物性查询（运营洞察 + 审计）：谁在查哪个品种、是否已收录
        try {
            boolean cov = COMPOUNDS.containsKey(cn) || POLYMERS.containsKey(cn);
            auditService.ok("CHEM_LOOKUP", "CHEM", "CHEM",
                    vid == null ? null : String.valueOf(vid), null, null,
                    "查询物性资料：" + cn + (cov ? "（已收录）" : "（未收录）"));
        } catch (Exception ignored) {
            // 记日志失败不影响查询
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", cn);
        out.put("varietiesId", vid);
        out.put("sources", SOURCES);
        out.put("trade", buildTrade(vid));

        String[] mapped = COMPOUNDS.get(cn);
        boolean isPolymer = false;
        if (mapped == null) {
            mapped = POLYMERS.get(cn);
            isPolymer = mapped != null;
        }
        if (mapped == null) {
            out.put("covered", false);
            out.put("reason", "该品种为混合物/复配产品/制品，无单一化合物标识，未收录结构化物性。"
                    + "可通过下方权威数据源进一步检索。");
            out.put("links", buildLinks(cn, null));
            return Result.ok(out);
        }

        String en = mapped[0];
        String cas = mapped[1];
        out.put("covered", true);
        out.put("isPolymer", isPolymer);
        out.put("enName", en);
        out.put("cas", cas);
        out.put("links", buildLinks(cn, cas));

        Map<String, Object> props = fetchPubChem(en);
        if (props != null) out.put("props", props);
        else out.put("propsErr", "物性数据源暂时不可用，请稍后重试或点下方链接查询");

        Object cid = props == null ? null : props.get("cid");
        if (cid instanceof Number) {
            long cidL = ((Number) cid).longValue();

            Map<String, Object> basicSec = new LinkedHashMap<>();
            putZh(basicSec, "appearance", firstOf(cidL, "Appearance"));
            putZh(basicSec, "odor", firstOf(cidL, "Odor"));
            putZh(basicSec, "melting", firstOf(cidL, "Melting Point"));
            putZh(basicSec, "boiling", firstOf(cidL, "Boiling Point"));
            putZh(basicSec, "density", firstOf(cidL, "Density"));
            putZh(basicSec, "vaporPressure", firstOf(cidL, "Vapor Pressure"));
            putZh(basicSec, "solubility", firstOf(cidL, "Solubility"));
            Map<String, Object> basicRaw = new LinkedHashMap<>();
            basicRaw.put("appearance", firstOf(cidL, "Appearance"));
            basicRaw.put("odor", firstOf(cidL, "Odor"));
            basicRaw.put("melting", firstOf(cidL, "Melting Point"));
            basicRaw.put("boiling", firstOf(cidL, "Boiling Point"));
            basicRaw.put("density", firstOf(cidL, "Density"));
            basicRaw.put("vaporPressure", firstOf(cidL, "Vapor Pressure"));
            basicRaw.put("solubility", firstOf(cidL, "Solubility"));
            removeEmpty(basicRaw);
            if (!basicRaw.isEmpty()) basicSec.put("raw", basicRaw);
            removeEmpty(basicSec);
            basicSec.put("specs", platformSpecs(vid));
            out.put("basic", basicSec);

            out.put("hazard", buildHazard(cidL));
            out.put("transport", buildTransport(cidL));
        }
        return Result.ok(out);
    }

    /* ================= 供 AI 客服调用的紧凑摘要 ================= */

    /**
     * 把物性查询结果压成一段紧凑文本，供 AI 客服（AiChatService）直接转述。
     * <p>返回 null 表示**未收录**（不在映射表内，或属混合物/复配产品/制品）——
     * 调用方据此回「数据库暂未收录」，绝不能让模型编造物性数值。
     * <p>刻意不含外链与英文原文（那些在「物性查询」页展示），控制长度、便于消息阅读。
     */
    public String aiSummary(String name) {
        if (name == null || name.isBlank()) return null;
        Map<String, Object> d;
        try {
            Result<Map<String, Object>> r = lookup(name.trim(), null);
            d = r == null ? null : r.getData();
        } catch (Exception e) {
            return null;
        }
        if (d == null || !Boolean.TRUE.equals(d.get("covered"))) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(d.get("name")).append("】");
        if (d.get("enName") != null) sb.append(" ").append(d.get("enName"));
        if (d.get("cas") != null) sb.append("　CAS ").append(d.get("cas"));
        if (d.get("props") instanceof Map<?, ?> p) {
            if (p.get("formula") != null) sb.append("　分子式 ").append(p.get("formula"));
            if (p.get("weight") != null) sb.append("　分子量 ").append(p.get("weight"));
        }
        if (Boolean.TRUE.equals(d.get("isPolymer"))) {
            sb.append("\n（注：该品种为聚合物，下列物性以单体计，成品指标另计）");
        }

        // ---- 一、关键物性 ----
        if (d.get("propsErr") != null || d.get("props") == null) {
            sb.append("\n【关键物性】\n· 物性数据源暂时不可用，本次未能取到物性数值；"
                    + "请稍后重试，或到「物性查询」页查看（页面会自动重试并展示权威数据源链接）");
        }
        if (d.get("basic") instanceof Map<?, ?> basic) {
            String[][] fs = {{"appearance", "外观"}, {"odor", "气味"}, {"melting", "熔点"},
                    {"boiling", "沸点"}, {"density", "密度"}, {"vaporPressure", "蒸气压"},
                    {"solubility", "溶解性"}};
            // raw 存的是未翻译原文 —— 中文译文被质量闸门（宁缺毋滥）拦下时，
            // **必须回退到原文**，否则用户问「沸点」却一句不提，模型极易凭记忆编造数值。
            Map<?, ?> rawMap = basic.get("raw") instanceof Map<?, ?> rm ? rm : null;
            StringBuilder bs = new StringBuilder();
            for (String[] f : fs) {
                String v = zhOf(basic.get(f[0]));
                if (v == null && rawMap != null) {
                    String rv = zhOf(rawMap.get(f[0]));
                    if (rv != null) {
                        // 熔沸点常见华氏度原文（NTP/HSDB 口径），给中文用户补注摄氏度
                        if ("melting".equals(f[0]) || "boiling".equals(f[0])) rv = f2c(rv);
                        v = clip(rv, 160) + "（原文）";
                    }
                }
                aiLine(bs, f[1], v);
            }
            if (bs.length() > 0) sb.append("\n【关键物性】").append(bs);
        }

        // ---- 二、是不是危化品 ----
        if (d.get("hazard") instanceof Map<?, ?> hz) {
            StringBuilder hs = new StringBuilder();
            String cls = zhOf(hz.get("hazardClassCn")) != null ? zhOf(hz.get("hazardClassCn")) : zhOf(hz.get("hazardClass"));
            String pg = zhOf(hz.get("packGroupCn")) != null ? zhOf(hz.get("packGroupCn")) : zhOf(hz.get("packGroup"));
            aiLine(hs, "危险货物分类", cls);
            aiLine(hs, "包装类别", pg);
            aiLine(hs, "UN 编号", zhOf(hz.get("unNumber")));
            if (hz.get("ghs") instanceof Map<?, ?> g) {
                aiLine(hs, "GHS 信号词", signalCn(g.get("signal")));
                if (g.get("statements") instanceof List<?> lst2 && !lst2.isEmpty()) {
                    StringBuilder x = new StringBuilder();
                    for (Object o : lst2) {
                        String v = zhOf(o);
                        if (v == null) continue;
                        if (x.length() > 0) x.append("；");
                        x.append(v);
                    }
                    aiLine(hs, "危险性说明", x.length() == 0 ? null : x.toString());
                }
            }
            if (Boolean.FALSE.equals(hz.get("isHazardous"))) {
                // ⚠️ 措辞必须保守：上游 PubChem 分段抓取偶发失败时，这里会是「查不到」而非「确实没有」。
                //    原文案「通常不属于危险化学品运输范畴」在这种情况下就是危险误报（如苯乙烯实为第 3 类易燃液体）。
                aiLine(hs, "结论", "未查询到该品种的危险货物运输分类记录"
                        + "（也可能因数据源暂时不可用）；如需作为运输依据，请以供应商 SDS/MSDS 与最新法规原文为准");
            }
            if (hs.length() > 0) sb.append("\n【是不是危化品】").append(hs);
        }

        // ---- 三、运输要求 ----
        if (d.get("transport") instanceof Map<?, ?> tr) {
            StringBuilder ts = new StringBuilder();
            aiLine(ts, "禁配物", joinZh(tr.get("incompatible")));
            if (tr.get("dotGuide") instanceof Map<?, ?> dg) {
                String v = zhOf(dg.get("zh")) != null ? zhOf(dg.get("zh")) : zhOf(dg.get("origin"));
                aiLine(ts, "DOT 应急指南", v);
            }
            aiLine(ts, "运输法规要点", joinZh(tr.get("regulations")));
            if (ts.length() > 0) sb.append("\n【运输要求】").append(ts);
        }

        // ---- 四、行情参考（有则给，无则不提）----
        if (d.get("trade") instanceof Map<?, ?> t && Boolean.TRUE.equals(t.get("available"))) {
            StringBuilder ps = new StringBuilder();
            aiLine(ps, "最新交易日", String.valueOf(t.get("date")));
            if (t.get("min") != null && t.get("max") != null) {
                aiLine(ps, "价格区间", fmtNum(t.get("min")) + " – " + fmtNum(t.get("max")) + " 元/吨");
            }
            if (t.get("count") != null) aiLine(ps, "报价点数", String.valueOf(t.get("count")) + " 个");
            sb.append("\n【行情参考】").append(ps);
        }

        sb.append("\n\n完整资料（含来源机构与英文原文）可在平台「物性查询」页查看。");
        sb.append("数据来源：ChemPrice 化工价格平台；物性与法规信息来自公开权威数据源，仅供参考。");
        return sb.toString();
    }

    /** 取 {zh, origin} 对里的中文；没有 zh 时退回 origin；空则 null */
    private static String zhOf(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) {
            Object zh = m.get("zh");
            if (zh != null && !String.valueOf(zh).isBlank()) return String.valueOf(zh);
            Object o = m.get("origin");
            return (o == null || String.valueOf(o).isBlank()) ? null : String.valueOf(o);
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /** 追加「· 标签：值」一行（值为空则跳过；值开头与标签重复时去掉，避免「禁配物：禁配物：…」） */
    private static void aiLine(StringBuilder sb, String label, String val) {
        if (val == null || val.isBlank()) return;
        String v = val.trim();
        if (v.startsWith(label)) {
            v = v.substring(label.length());
            while (v.startsWith("：") || v.startsWith(":") || v.startsWith(" ")) v = v.substring(1);
        }
        if (v.isBlank()) return;
        sb.append("\n· ").append(label).append("：").append(v);
    }

    /**
     * 把列表里的 {zh,origin} 拼成**纯中文**串。
     * <p>只取 zh：未通过质量闸门的英文长段直接跳过（聊天消息里塞大段英文既难读又占长度）；
     * 全都未翻译时返回 null，由调用方按「该数据未收录」处理。
     */
    private static String joinZh(Object listO) {
        if (!(listO instanceof List<?> lst)) return null;
        if (lst.isEmpty()) return null;
        StringBuilder x = new StringBuilder();
        for (Object o : lst) {
            String v = null;
            if (o instanceof Map<?, ?> m) {
                Object zh = m.get("zh");
                if (zh != null && !String.valueOf(zh).isBlank()) v = String.valueOf(zh);
            } else if (o != null && !String.valueOf(o).isBlank()) {
                v = String.valueOf(o);
            }
            if (v == null) continue;
            v = v.trim();
            if (x.length() > 0) {
                // 与上一条重复的内容不再叠加
                if (x.indexOf(v) >= 0) continue;
                x.append("；");
            }
            x.append(v);
        }
        return x.length() == 0 ? null : x.toString();
    }

    /**
     * 华氏度补注摄氏度：把「148.3 °F」这类原文补成「148.3 °F（64.6 ℃）」。
     * <p>PubChem / NTP 的熔沸点常以华氏度给出，中文用户不直观；已有 ℃/°C 的原文不动。
     */
    private static String f2c(String s) {
        if (s == null || s.isBlank()) return s;
        if (s.contains("℃") || s.contains("°C")) return s;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(-?\\d+(?:\\.\\d+)?)\\s*°?\\s*F\\b").matcher(s);
        StringBuffer out = new StringBuffer();
        boolean any = false;
        while (m.find()) {
            try {
                double f = Double.parseDouble(m.group(1));
                double c = (f - 32.0) * 5.0 / 9.0;
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        m.group(0) + "（" + String.format("%.1f", c) + " ℃）"));
                any = true;
            } catch (Exception ignored) {
                // 单条转换失败不影响整体
            }
        }
        if (!any) return s;
        m.appendTail(out);
        return out.toString();
    }

    /** 截断过长文本（保持可读） */
    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** GHS 信号词英译中 */
    private static String signalCn(Object s) {
        String v = zhOf(s);
        if (v == null) return null;
        if (v.equalsIgnoreCase("Danger")) return "危险（Danger）";
        if (v.equalsIgnoreCase("Warning")) return "警告（Warning）";
        return v;
    }

    /** 数值格式化（去掉多余小数位） */
    private static String fmtNum(Object n) {
        if (n == null) return "—";
        if (n instanceof Number num) return String.format("%,.0f", num.doubleValue());
        return String.valueOf(n);
    }

    @GetMapping("/sources")
    public Result<List<Map<String, String>>> sources() {
        return Result.ok(SOURCES);
    }

    /**
     * 把一条英文 PubChem 条目翻译成中文写入 map。
     * ⚠️ 必须过 {@link ChemZh#isGoodZh}：翻不动的长句宁可不写，
     *    由前端回退展示英文原文，也不要留下「The finely…hazard.。不溶于水。」这种半成品。
     */
    private static void putZh(Map<String, Object> m, String key, String raw) {
        if (raw == null || raw.isBlank()) return;
        String zh = ChemZh.translateText(raw);
        if (zh == null || zh.isBlank()) return;
        if (!ChemZh.isGoodZh(zh)) return;
        m.put(key, zh);
    }

    /** 长句概括：优先用人工概括，其次词典，最后保留原文 */
    private static String sumZh(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = ChemZh.summarize(raw);
        if (s != null) return s;
        return ChemZh.translateText(raw);
    }

    /**
     * 写入「{zh, origin}」结构：翻译合格则 zh + origin 都给（前端折叠原文），
     * 不合格则只给 origin（前端只展示英文原文，不展示半成品中文）。
     */
    private static void putZhPair(Map<String, Object> m, String key, String raw) {
        putZhPair(m, key, raw, false);
    }

    /**
     * @param loose true 时走 {@link ChemZh#isGoodZhLoose}（摘要类文本用），
     *              false 时走严格闸门（逐句翻译的正文用）。
     */
    private static void putZhPair(Map<String, Object> m, String key, String raw, boolean loose) {
        if (raw == null || raw.isBlank()) return;
        String zh = loose ? ChemZh.translateText(raw) : ChemZh.translateText(raw);
        if (loose) {
            // 摘要优先：能概括就概括（长段落概括比逐句直译更易读）
            String s = ChemZh.summarize(raw);
            if (s != null) zh = s;
        }
        Map<String, String> e = new LinkedHashMap<>();
        boolean ok = loose ? ChemZh.isGoodZhLoose(zh) : ChemZh.isGoodZh(zh);
        if (ok) {
            e.put("zh", zh);
            if (!zh.equals(raw)) e.put("origin", raw);
        } else {
            e.put("origin", raw);
        }
        m.put(key, e);
    }

    /** 平台行情里出现过的规格（用于「一、基础简介」的规格行） */    private List<String> platformSpecs(Integer vid) {
        List<String> out = new ArrayList<>();
        if (vid == null) return out;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT DISTINCT specifications_name FROM market_price "
                            + "WHERE varieties_id=? AND specifications_name IS NOT NULL AND specifications_name<>'' "
                            + "AND data_date >= DATE_SUB(CURDATE(), INTERVAL 90 DAY) LIMIT 12", vid);
            for (Map<String, Object> r : rows) {
                String s = String.valueOf(r.get("specifications_name")).trim();
                if (!s.isEmpty() && !"null".equals(s)) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ============================================================ 二、危化品判定
    private Map<String, Object> buildHazard(long cid) {
        Map<String, Object> h = new LinkedHashMap<>();
        String unClass = firstOf(cid, "UN Classification");
        h.put("unClassification", unClass);
        String unClassCn = ChemZh.unClass(unClass);
        if (unClassCn != null) h.put("unClassificationCn", unClassCn);

        String hazardClass = null, packGroup = null, hazardClassCn = null;
        if (unClass != null) {
            Matcher m1 = Pattern.compile("Hazard\\s*Class\\s*:?\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(unClass);
            if (m1.find()) {
                hazardClass = m1.group(1);
                hazardClassCn = unClassCn(hazardClass);
            }
            Matcher m2 = Pattern.compile("Pack\\s*Group\\s*:?\\s*([IVX]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(unClass);
            if (m2.find()) packGroup = m2.group(1);
        }
        h.put("hazardClass", hazardClass);
        h.put("hazardClassCn", hazardClassCn);
        h.put("packGroup", packGroup);
        h.put("packGroupCn", packGroupCn(packGroup));
        h.put("unNumber", cleanUnNumber(firstOf(cid, "UN Number")));
        String fpRaw = firstOf(cid, "Flash Point");
        putZh(h, "flashPoint", fpRaw);
        String aiRaw = firstOf(cid, "Autoignition Temperature");
        putZh(h, "autoignition", aiRaw);
        h.put("ecClassification", firstOf(cid, "EC Classification"));
        String ecCn = ecCn(firstOf(cid, "EC Classification"));
        if (ecCn != null) h.put("ecClassificationCn", ecCn);
        h.put("ghs", parseGhs(cid));

        boolean isHaz = unClass != null || h.get("unNumber") != null;
        h.put("isHazardous", isHaz);
        if (!isHaz) {
            h.put("note", "该品种未查询到危险货物运输分类记录，通常不属于危险化学品运输范畴。");
        }
        removeEmpty(h);
        return h;
    }

    /** UN 危险类别编号 -> 中文类别名（联合国 TDG / GB 6944 常见类别） */
    private static String unClassCn(String no) {
        if (no == null) return null;
        switch (no.trim()) {
            case "1": return "第 1 类 爆炸品";
            case "2": return "第 2 类 气体";
            case "2.1": return "第 2.1 类 易燃气体";
            case "2.2": return "第 2.2 类 非易燃无毒气体";
            case "2.3": return "第 2.3 类 毒性气体";
            case "3": return "第 3 类 易燃液体";
            case "3.1": return "第 3.1 类 低闪点易燃液体";
            case "3.2": return "第 3.2 类 中闪点易燃液体";
            case "3.3": return "第 3.3 类 高闪点易燃液体";
            case "4": return "第 4 类 易燃固体、自燃物质、遇水放出易燃气体物质";
            case "4.1": return "第 4.1 类 易燃固体";
            case "4.2": return "第 4.2 类 易于自燃的物质";
            case "4.3": return "第 4.3 类 遇水放出易燃气体的物质";
            case "5": return "第 5 类 氧化性物质和有机过氧化物";
            case "5.1": return "第 5.1 类 氧化性物质";
            case "5.2": return "第 5.2 类 有机过氧化物";
            case "6": return "第 6 类 毒性物质和感染性物质";
            case "6.1": return "第 6.1 类 毒性物质";
            case "6.2": return "第 6.2 类 感染性物质";
            case "7": return "第 7 类 放射性物质";
            case "8": return "第 8 类 腐蚀性物质";
            case "9": return "第 9 类 杂项危险物质和物品";
            default: return "第 " + no + " 类";
        }
    }

    /** 欧盟 EC 危险符号字母 -> 中文含义 */
    private static String ecCn(String ec) {
        if (ec == null || ec.isBlank()) return null;
        Matcher m = Pattern.compile("Symbol\\s*:?\\s*([A-Za-z, ]+?)\\s*(?:;|$)", Pattern.CASE_INSENSITIVE).matcher(ec);
        if (!m.find()) return null;
        List<String> out = new ArrayList<>();
        for (String sym : m.group(1).split("[,\\s]+")) {
            switch (sym.trim().toUpperCase()) {
                case "T":  out.add("T 有毒"); break;
                case "T+": out.add("T+ 剧毒"); break;
                case "F":  out.add("F 高度易燃"); break;
                case "F+": out.add("F+ 极易燃"); break;
                case "C":  out.add("C 腐蚀性"); break;
                case "X":  out.add("Xn 有害"); break;
                case "XN": out.add("Xn 有害"); break;
                case "XI": out.add("Xi 刺激性"); break;
                case "O":  out.add("O 氧化性"); break;
                case "E":  out.add("E 爆炸性"); break;
                case "N":  out.add("N 环境危险"); break;
                case "K":  out.add("K 生殖毒性"); break;
                default: break;
            }
        }
        return out.isEmpty() ? null : String.join(" · ", out);
    }

    /** 包装类别罗马数字 -> 中文（联合国 TDG / GB 6944） */
    private static String packGroupCn(String g) {
        if (g == null || g.isBlank()) return null;
        switch (g.trim().toUpperCase()) {
            case "I":   return "Ⅰ 类（大危险）";
            case "II":  return "Ⅱ 类（中危险）";
            case "III": return "Ⅲ 类（小危险）";
            default:    return g + " 类";
        }
    }

    // ============================================================ 三、运输要求
    private Map<String, Object> buildTransport(long cid) {
        Map<String, Object> t = new LinkedHashMap<>();
        List<String> list = pickMany(cid, "Transport Information", 16);
        t.put("dotGuide", findDotGuide(list));

        // 运输法规要点：长句用人工概括，同时保留英文原文供折叠查看
        List<String> regs = filterRegulations(list);
        List<Map<String, String>> regCn = new ArrayList<>();
        List<String> regRaw = new ArrayList<>();
        for (String r : regs) {
            Map<String, String> e = new LinkedHashMap<>();
            String zh = sumZh(r);
            // ⚠️ 用宽松闸门：summarize() 的产出是人工模板拼装，残留英文只可能是
            //    "IMDG Code" 这类专有名词，用严格闸门会误杀优质摘要。
            if (ChemZh.isGoodZhLoose(zh)) {
                e.put("zh", zh);
                e.put("origin", r);
            } else {
                e.put("origin", r);
            }
            regCn.add(e);
            regRaw.add(r);
        }
        if (!regCn.isEmpty()) t.put("regulations", regCn);

        // 运输与储存禁忌：逐条翻译 + 保留原文
        // ⚠️ 兜底翻译可能只产出半中半英（长句/全大写原文命中不了词典），
        //    此时宁可不给 zh，让前端只展示英文原文，也不要甩出「半成品中文」。
        List<String> inc = pickMany(cid, "Hazardous Reactivities and Incompatibilities", 6);
        List<Map<String, String>> incCn = new ArrayList<>();
        for (String s : inc) {
            Map<String, String> e = new LinkedHashMap<>();
            String zh = ChemZh.translateText(s);
            if (ChemZh.isGoodZh(zh)) {
                e.put("zh", zh);
                if (!zh.equals(s)) e.put("origin", s);
            } else {
                e.put("origin", s);
            }
            incCn.add(e);
        }
        if (!incCn.isEmpty()) t.put("incompatible", incCn);

        // 储存条件 / 稳定性：长段落走「概括 + 逐句」双路，取先过关的那个
        String storage = firstOf(cid, "Storage Conditions");
        putZhPair(t, "storage", storage, true);
        List<String> react = pickMany(cid, "Stability and Reactivity", 3);
        if (!react.isEmpty()) putZhPair(t, "stability", react.get(0), true);
        removeEmpty(t);
        return t;
    }

    // ============================================================ 第四段：贸易参考（个性化）
    private Map<String, Object> buildTrade(Integer vid) {
        if (vid == null) return null;
        Map<String, Object> t = new LinkedHashMap<>();

        String latestDate = null;
        try {
            List<Map<String, Object>> d = jdbcTemplate.queryForList(
                    "SELECT MAX(data_date) AS d FROM market_price WHERE varieties_id=? AND middle_price>=0", vid);
            if (!d.isEmpty() && d.get(0).get("d") != null) latestDate = String.valueOf(d.get(0).get("d"));
        } catch (Exception ignored) {
        }
        if (latestDate == null) {
            t.put("available", false);
            t.put("reason", "平台暂无该品种的现货报价");
            return t;
        }
        t.put("available", true);
        t.put("date", latestDate);

        List<Map<String, Object>> quotes = jdbcTemplate.queryForList(
                "SELECT market_name, region_name, specifications_name, low_price, high_price, middle_price "
                        + "FROM market_price WHERE varieties_id=? AND data_date=? AND middle_price>=0 "
                        + "ORDER BY middle_price DESC", vid, latestDate);
        List<Map<String, Object>> list = new ArrayList<>();
        double min = Double.MAX_VALUE, max = -1;
        for (Map<String, Object> q : quotes) {
            double mp = q.get("middle_price") == null ? 0 : ((Number) q.get("middle_price")).doubleValue();
            if (mp > 0) {
                min = Math.min(min, mp);
                max = Math.max(max, mp);
            }
            list.add(tradeRow(q));
        }
        t.put("quotes", list);
        t.put("count", list.size());
        t.put("min", min == Double.MAX_VALUE ? null : min);
        t.put("max", max < 0 ? null : max);

        // 个性化：登录用户若关注过该品种，优先展示其关注的地区 / 规格
        String username = currentUsername();
        if (username != null) {
            try {
                List<Map<String, Object>> favs = jdbcTemplate.queryForList(
                        "SELECT f.market_name, f.specifications_name FROM user_favorite f "
                                + "JOIN sys_user u ON u.id=f.user_id "
                                + "WHERE u.username=? AND f.varieties_id=? LIMIT 5", username, vid);
                if (!favs.isEmpty()) {
                    List<Map<String, Object>> personalized = new ArrayList<>();
                    for (Map<String, Object> f : favs) {
                        String mk = f.get("market_name") == null ? null : String.valueOf(f.get("market_name"));
                        String sp = f.get("specifications_name") == null ? null
                                : String.valueOf(f.get("specifications_name"));
                        StringBuilder sql = new StringBuilder(
                                "SELECT market_name, region_name, specifications_name, low_price, high_price, middle_price "
                                        + "FROM market_price WHERE varieties_id=? AND data_date=? AND middle_price>=0");
                        List<Object> args = new ArrayList<>();
                        args.add(vid);
                        args.add(latestDate);
                        if (mk != null && !mk.isBlank()) {
                            sql.append(" AND (market_name=? OR region_name=?)");
                            args.add(mk);
                            args.add(mk);
                        }
                        if (sp != null && !sp.isBlank()) {
                            sql.append(" AND specifications_name=?");
                            args.add(sp);
                        }
                        sql.append(" LIMIT 3");
                        for (Map<String, Object> q : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
                            personalized.add(tradeRow(q));
                        }
                    }
                    if (!personalized.isEmpty()) t.put("personalized", personalized);
                }
            } catch (Exception ignored) {
            }
        }

        try {
            List<Map<String, Object>> tr = jdbcTemplate.queryForList(
                    "SELECT data_date, AVG(middle_price) AS avg_price FROM market_price "
                            + "WHERE varieties_id=? AND middle_price>=0 "
                            + "AND data_date >= DATE_SUB(?, INTERVAL 30 DAY) "
                            + "GROUP BY data_date ORDER BY data_date", vid, latestDate);
            List<Map<String, Object>> trend = new ArrayList<>();
            for (Map<String, Object> r : tr) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("date", String.valueOf(r.get("data_date")));
                o.put("price", num(r.get("avg_price")));
                trend.add(o);
            }
            t.put("trend", trend);
        } catch (Exception ignored) {
        }
        return t;
    }

    private Map<String, Object> tradeRow(Map<String, Object> q) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("market", q.get("market_name"));
        o.put("region", q.get("region_name"));
        o.put("spec", q.get("specifications_name"));
        o.put("low", num(q.get("low_price")));
        o.put("high", num(q.get("high_price")));
        o.put("middle", num(q.get("middle_price")));
        return o;
    }

    private static Double num(Object o) {
        if (o == null) return null;
        double d = ((Number) o).doubleValue();
        return Math.round(d * 100.0) / 100.0;
    }

    private String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
            return auth.getName();
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================ PubChem 取值

    @SuppressWarnings("unchecked")
    private List<String> pugHeading(long cid, String heading) {
        String key = "pugv:" + cid + ":" + heading;
        Object[] hit = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[0] < CACHE_TTL_MS) {
            return (List<String>) hit[1];
        }
        List<String> result = new ArrayList<>();
        try {
            String url = PUGVIEW_BASE + cid + "/JSON?heading=" + encodePath(heading);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "ChemPrice/1.0 (chemical price platform)")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                collectStrings(JSON.readValue(resp.body(), Map.class), result);
            }
        } catch (Exception ignored) {
        }
        CACHE.put(key, new Object[]{now, result});
        return result;
    }

    /** URLEncoder 把空格编成 '+'，PubChem 要求 '%20'（否则多词 heading 全 400） */
    private static String encodePath(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    @SuppressWarnings("unchecked")
    private static void collectStrings(Object node, List<String> out) {
        if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            Object info = m.get("Information");
            if (info instanceof List) {
                for (Object i : (List<Object>) info) {
                    if (!(i instanceof Map)) continue;
                    Object val = ((Map<String, Object>) i).get("Value");
                    if (val instanceof Map) {
                        Map<String, Object> vm = (Map<String, Object>) val;
                        Object swm = vm.get("StringWithMarkup");
                        if (swm instanceof List) {
                            for (Object s : (List<Object>) swm) {
                                if (s instanceof Map) {
                                    Object str = ((Map<String, Object>) s).get("String");
                                    if (str != null) {
                                        String t = String.valueOf(str).trim();
                                        if (!t.isEmpty() && t.length() < 900) out.add(t);
                                    }
                                }
                            }
                        }
                        Object nums = vm.get("Number");
                        if (nums instanceof List) {
                            for (Object n : (List<Object>) nums) if (n != null) out.add(String.valueOf(n));
                        }
                    }
                }
            }
            for (Object v : m.values()) {
                if (v instanceof Map || v instanceof List) collectStrings(v, out);
            }
        } else if (node instanceof List) {
            for (Object v : (List<Object>) node) {
                if (v instanceof Map || v instanceof List) collectStrings(v, out);
            }
        }
    }

    private String firstOf(long cid, String heading) {
        for (String s : pugHeading(cid, heading)) {
            if (s != null && !s.isBlank() && !isNoise(s)) return s.replace("\n", " ").trim();
        }
        return null;
    }

    private List<String> pickMany(long cid, String heading, int max) {
        List<String> out = new ArrayList<>();
        for (String s : pugHeading(cid, heading)) {
            if (s == null || s.isBlank() || isNoise(s)) continue;
            String t = s.replace("\n", " ").trim();
            if (!out.contains(t)) out.add(t);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** 过滤「请访问 HSDB 查看更多」这类噪声行 */
    private static boolean isNoise(String s) {
        return s.contains("please visit the HSDB record page") || s.contains("For more ");
    }

    private static String cleanUnNumber(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!t.toUpperCase().startsWith("UN")) t = "UN " + t;
        return t;
    }

    private static String findDotGuide(List<String> list) {
        for (String s : list) {
            Matcher m = Pattern.compile("GUIDE\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(s);
            if (m.find()) {
                String no = m.group(1);
                // 形如 "GUIDE 129: FLAMMABLE LIQUIDS (POLAR / WATER-MISCIBLE)" 或 "GUIDE 129 - FLAMMABLE LIQUIDS"
                String cat = "";
                Matcher m2 = Pattern.compile("GUIDE\\s+\\d+\\s*[:\\-–—]?\\s*(.+)$").matcher(s);
                if (m2.find()) {
                    String rest = m2.group(1).trim();
                    cat = rest.split("[(/\\[\\]|]")[0].trim().replaceAll("[\\s,;:]+$", "");
                    // 太短或全是说明文字就不要
                    if (cat.length() > 90) cat = cat.substring(0, 90);
                }
                String catCn = dotCatCn(cat);
                return catCn.isEmpty() ? ("DOT 应急指南 " + no) : ("DOT 应急指南 " + no + " · " + catCn);
            }
        }
        return null;
    }

    /** DOT 应急指南类别名 -> 中文（美国交通部 ERG2024 常见类别） */
    private static String dotCatCn(String cat) {
        if (cat == null) return "";
        String c = cat.toUpperCase();
        if (c.contains("FLAMMABLE LIQUID")) return "易燃液体";
        if (c.contains("FLAMMABLE GAS")) return "易燃气体";
        if (c.contains("NON-FLAMMABLE GAS")) return "非易燃气体";
        if (c.contains("CORROSIVE")) return "腐蚀性物质";
        if (c.contains("OXIDIZER") || c.contains("OXIDIZING")) return "氧化性物质";
        if (c.contains("ORGANIC PEROXIDE")) return "有机过氧化物";
        if (c.contains("TOXIC") || c.contains("POISON")) return "毒性物质";
        if (c.contains("EXPLOSIVE")) return "爆炸品";
        if (c.contains("SPONTANEOUSLY COMBUSTIBLE")) return "自燃物质";
        if (c.contains("DANGEROUS WHEN WET")) return "遇水放出易燃气体物质";
        if (c.contains("RADIOACTIVE")) return "放射性物质";
        if (c.contains("WATER-REACTIVE")) return "遇水反应性物质";
        if (c.contains("INFECTIOUS")) return "感染性物质";
        if (c.contains("POLYMERIZING")) return "可聚合物质";
        if (c.contains("ALCOHOL")) return "醇类";
        if (c.contains("KETONE")) return "酮类";
        if (c.contains("ESTER")) return "酯类";
        if (c.contains("AMINE")) return "胺类";
        if (c.contains("HYDROCARBON")) return "烃类";
        return "";
    }

    private static List<String> filterRegulations(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (s.contains("no person may") || s.contains("IATA") || s.contains("International Maritime")) {
                out.add(s);
            }
            if (out.size() >= 3) break;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGhs(long cid) {
        Map<String, Object> ghs = new LinkedHashMap<>();
        List<String> items;
        String key = "ghs:" + cid;
        Object[] hit = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[0] < CACHE_TTL_MS) {
            items = (List<String>) hit[1];
        } else {
            items = new ArrayList<>();
            try {
                String url = PUGVIEW_BASE + cid + "/JSON?heading=GHS%20Classification";
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "ChemPrice/1.0 (chemical price platform)")
                        .GET().build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    collectStrings(JSON.readValue(resp.body(), Map.class), items);
                }
            } catch (Exception ignored) {
            }
            CACHE.put(key, new Object[]{now, items});
        }
        if (items.isEmpty()) return ghs;

        String signal = null;
        LinkedHashMap<String, String> hCodes = new LinkedHashMap<>();
        for (String s : items) {
            String t = s.trim();
            if (t.equalsIgnoreCase("Danger")) {
                if (signal == null) signal = "Danger（危险）";
                continue;
            }
            if (t.equalsIgnoreCase("Warning")) {
                if (signal == null) signal = "Warning（警告）";
                continue;
            }
            Matcher m = Pattern.compile("^(H\\d{3}):\\s*(.+?)\\s*(?:\\[|$)").matcher(t);
            if (m.find()) {
                String code = m.group(1);
                String desc = m.group(2).trim();
                if (!hCodes.containsKey(code)) hCodes.put(code, desc);
            }
        }
        if (signal != null) ghs.put("signal", signal);
        if (!hCodes.isEmpty()) {
            List<Map<String, String>> hs = new ArrayList<>();
            for (Map.Entry<String, String> e : hCodes.entrySet()) {
                Map<String, String> o = new LinkedHashMap<>();
                o.put("code", e.getKey());
                String zh = ChemZh.hStatement(e.getKey());
                if (zh != null) {
                    o.put("desc", zh);
                    o.put("origin", e.getValue());
                } else {
                    o.put("desc", e.getValue());
                }
                hs.add(o);
            }
            ghs.put("hStatements", hs);
        }
        return ghs;
    }

    private static void removeEmpty(Map<String, Object> m) {
        m.entrySet().removeIf(e -> e.getValue() == null
                || (e.getValue() instanceof String && ((String) e.getValue()).isBlank())
                || (e.getValue() instanceof List && ((List<?>) e.getValue()).isEmpty())
                || (e.getValue() instanceof Map && ((Map<?, ?>) e.getValue()).isEmpty()));
    }

    // ============================================================ 外链

    private List<Map<String, String>> buildLinks(String cn, String cas) {
        String keyEnc = java.net.URLEncoder.encode(cas != null ? cas : cn,
                java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> ls = new ArrayList<>();
        ls.add(link("PubChem", "https://pubchem.ncbi.nlm.nih.gov/#query=" + keyEnc, "物性 · 分子式 · 危险分类"));
        ls.add(link("HSDB 危险物质库", "https://pubchem.ncbi.nlm.nih.gov/source/hsdb", "危害 · 运输 · 储存"));
        ls.add(link("NIST WebBook", "https://webbook.nist.gov/cgi/cbook.cgi?Name=" + keyEnc, "热力学 · 光谱"));
        ls.add(link("ChemSpider", "https://www.chemspider.com/Search.aspx?q=" + keyEnc, "物性 · 文献"));
        ls.add(link("Organic Syntheses", "https://www.orgsyn.org/search?q=" + keyEnc, "合成实验步骤"));
        ls.add(link("Google Patents", "https://patents.google.com/?q=(" + keyEnc + ")+synthesis", "合成工艺专利"));
        ls.add(link("ECHA", "https://echa.europa.eu/search-for-chemicals?p_auth=&_searchForChemicals_WAR_echarevchemportlet_search=" + keyEnc, "法规 · MSDS"));
        ls.add(link("Reaxys", "https://www.reaxys.com/#/search/quick?query=" + keyEnc, "合成路线（需授权）"));
        ls.add(link("SciFinderⁿ", "https://scifinder-n.cas.org/searchDetail/substance?query=" + keyEnc, "合成方法（需授权）"));
        return ls;
    }

    private Map<String, String> link(String site, String url, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("site", site);
        m.put("url", url);
        m.put("desc", desc);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchPubChem(String enName) {
        String key = "pubchem:" + enName.toLowerCase();
        Object[] hit = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - (Long) hit[0] < CACHE_TTL_MS) {
            return (Map<String, Object>) hit[1];
        }
        String url = PUBCHEM_BASE + encodePath(enName)
                + "/property/MolecularFormula,MolecularWeight,CanonicalSMILES,InChI,IUPACName/JSON";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "ChemPrice/1.0 (chemical price platform)")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            Map<String, Object> parsed = JSON.readValue(resp.body(), Map.class);
            Map<String, Object> table = (Map<String, Object>) parsed.get("PropertyTable");
            List<Map<String, Object>> list = (List<Map<String, Object>>) table.get("Properties");
            if (list == null || list.isEmpty()) return null;
            Map<String, Object> p = list.get(0);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cid", p.get("CID"));
            out.put("formula", p.get("MolecularFormula"));
            out.put("weight", p.get("MolecularWeight"));
            out.put("smiles", p.get("CanonicalSMILES"));
            out.put("inchi", p.get("InChI"));
            out.put("iupac", p.get("IUPACName"));
            out.put("source", "PubChem（美国国立卫生研究院 NIH）");
            if (out.get("cid") != null) {
                out.put("pubchemUrl", "https://pubchem.ncbi.nlm.nih.gov/compound/" + out.get("cid"));
            }
            CACHE.put(key, new Object[]{now, out});
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
