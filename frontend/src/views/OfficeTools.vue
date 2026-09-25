<template>
  <div class="ot">
    <!-- ===== 顶部说明 ===== -->
    <section class="ot-hero">
      <div class="ot-hero-txt">
        <h1>办公工具</h1>
        <p>
          日常办公常用网站合集：二维码生成、Excel 函数查询、PDF 转换、JSON 格式化、化工数据查询……
          点开即用，<b>均在新窗口打开</b>，不会离开本平台。
        </p>
      </div>
      <div class="ot-hero-stat">
        <div class="s"><b>{{ tools.length }}</b><span>收录工具</span></div>
        <div class="s"><b>{{ catList.length }}</b><span>个分类</span></div>
      </div>
    </section>

    <!-- ===== 搜索 + 分类筛选 ===== -->
    <section class="ot-bar">
      <label class="ot-search">
        <Search />
        <input
          v-model="kw"
          type="text"
          placeholder="搜索工具：二维码 / Excel / JSON / PDF / HS编码 …"
        />
        <button v-if="kw" class="ot-clear" type="button" title="清空" @click="kw = ''">×</button>
      </label>
      <div class="ot-chips">
        <button class="chip" :class="{ on: active === 'all' }" type="button" @click="active = 'all'">
          全部<i>{{ tools.length }}</i>
        </button>
        <button
          v-for="c in catList"
          :key="c.id"
          class="chip"
          :class="{ on: active === c.id }"
          type="button"
          @click="active = active === c.id ? 'all' : c.id"
        >
          <component :is="c.icon" />{{ c.name }}<i>{{ c.items.length }}</i>
        </button>
        <!-- 「休闲娱乐」开关：该类默认收起，点此才显示（选择记在浏览器里） -->
        <button
          class="chip chip-fun"
          :class="{ on: showFun }"
          type="button"
          :title="showFun ? '收起娱乐类工具（与办公业务无关）' : '娱乐类工具默认收起，点此显示'
          "
          @click="toggleFun"
        >
          <PartyPopper />{{ showFun ? '收起休闲娱乐' : '显示休闲娱乐' }}<i>{{ funCount }}</i>
        </button>
      </div>
    </section>

    <!-- ===== 工具列表 ===== -->
    <div v-if="groups.length" class="ot-groups">
      <section v-for="g in groups" :key="g.id" class="ot-group">
        <header class="ot-ghead">
          <span class="ot-gicon" :style="{ background: g.tint, color: g.color }">
            <component :is="g.icon" />
          </span>
          <h2>{{ g.name }}</h2>
          <span class="ot-gnum">{{ g.items.length }}</span>
          <span class="ot-gdesc">{{ g.desc }}</span>
        </header>
        <div class="ot-grid">
          <a
            v-for="t in shown(g)"
            :key="t.url"
            class="ot-card"
            :href="t.url"
            target="_blank"
            rel="noopener noreferrer"
            :title="t.url"
          >
            <span v-if="t.hot" class="ot-hot">常用</span>
            <div class="ot-c-top">
              <img
                v-if="!badIcon[t.url]"
                class="ot-c-img"
                :src="iconOf(t.url)"
                :alt="t.name"
                @error="badIcon[t.url] = true"
              />
              <span v-else class="ot-c-logo" :style="{ background: g.tint, color: g.color }">{{ t.name.slice(0, 1) }}</span>
              <span class="ot-c-name">{{ t.name }}</span>
              <ExternalLink class="ot-c-ext" />
            </div>
            <p class="ot-c-desc">{{ t.desc }}</p>
            <span class="ot-c-host">{{ hostOf(t.url) }}</span>
          </a>
        </div>
        <button v-if="isFolded(g)" class="ot-more" type="button" @click="toggleFold(g.id)">
          展开「{{ g.name }}」全部 {{ g.items.length }} 个 ▾
        </button>
        <button v-else-if="expanded[g.id] && g.items.length > FOLD_LIMIT && !kw.trim()"
                class="ot-more ot-more-up" type="button" @click="toggleFold(g.id)">
          收起 ▴
        </button>
      </section>
    </div>

    <div v-else class="ot-empty">
      <Search />
      <p>没有找到与「{{ kw }}」相关的工具</p>
      <p v-if="funHiddenMatch" class="ot-empty-tip">
        娱乐类工具当前是收起的，其中有 {{ funHiddenMatch }} 个匹配「{{ kw }}」
      </p>
      <button type="button" @click="clearAll">清空筛选，看全部</button>
      <button v-if="funHiddenMatch" class="ot-empty-alt" type="button" @click="toggleFun">
        显示休闲娱乐类
      </button>
    </div>

    <p class="ot-foot">
      以上均为第三方网站，点击后在新窗口打开；平台仅作收录整理，不对其内容与安全性负责。
      有想补充的工具，告诉我们即可。
    </p>
  </div>
</template>

<script setup>
/* 办公工具导航页
   —— 纯前端静态清单，不请求后端、不引外部 CDN（与全站「零外链资源」策略一致）。
   新增工具：只需往 CATS 里对应分类的 items 追加 { name, url, desc }，无需改其它文件。
   「休闲娱乐」分类（id: 'fun'）默认收起，由顶部开关控制显示（见 showFun / toggleFun）。
   图标：构建期已把各站 favicon 抓到本站 `public/tool-icons/<域名>.png`，
         运行时零外部请求；个别站点没有 favicon 或文件缺失时，
         @error 会自动回退成「首字方块」，不会出现裂图。
   ⚠️ 收录前必须核实链接可用：curl 批量探测看状态码 + content-type；
      被 Cloudflare 等 WAF 拦（403/429）但真实浏览器正常的站（如 UL Prospector 系）
      可以收录，但要在描述里说明「需注册 / 需人机验证」，不要默认所有人都能直达。 */
import { computed, ref } from 'vue'
import { Search, ExternalLink, QrCode, FileSpreadsheet, FileText, Image, Braces, Palette, FlaskConical, ShieldCheck, Newspaper, Globe, Code, Video, Cpu, Server, Wrench, PartyPopper } from 'lucide-vue-next'

const CATS = [
  {
    id: 'qr',
    name: '二维码与标签',
    desc: '把网址、文本、文件做成二维码，方便贴到物料、设备、名片上',
    icon: QrCode,
    color: '#7e22ce',
    tint: '#f3e8ff',
    items: [
      { name: '草料二维码', url: 'https://cli.im/', hot: 1, desc: '国内最常用的二维码平台，支持文本/网址/文件，二维码长期有效' },
      { name: '草料 · 批量生码', url: 'https://cli.im/batch', desc: '用 Excel 导入批量生成二维码，适合给多个物料/设备统一贴码' },
      { name: '草料 · 网址转二维码', url: 'https://cli.im/url', desc: '把平台里的报价页、商品页链接直接转成二维码发客户' },
      { name: '联图二维码', url: 'https://www.liantu.com/', desc: '老牌免费生成器，支持加 Logo、改颜色，不用注册' }
    ]
  },
  {
    id: 'excel',
    name: 'Excel 与表格',
    desc: '查函数写法、找模板、多人协作表格',
    icon: FileSpreadsheet,
    color: '#15803d',
    tint: '#dcfce7',
    items: [
      { name: 'WPS 学堂', url: 'https://www.wps.cn/learning/', hot: 1, desc: '中文最全的 Excel/Word/PPT 教程，函数用法带实例，免费' },
      { name: 'ExcelJet 函数速查', url: 'https://exceljet.net/', desc: '按函数名/用途检索，带公式示例，查 VLOOKUP、XLOOKUP 最快（英文）' },
      { name: 'ExcelHome', url: 'https://www.excelhome.net/', desc: '中文 Excel 社区，函数疑难、模板、图表问题都能搜到' },
      { name: '金山文档', url: 'https://www.kdocs.cn/', desc: '在线表格，多人同时编辑，适合共享报价单/台账' },
      { name: '腾讯文档', url: 'https://docs.qq.com/', desc: '在线文档与表格，可做收集表让客户自己填需求' },
      { name: '1PPT 模板', url: 'https://www.1ppt.com/', desc: '免费 PPT 模板下载，做汇报/产品介绍省时间' }
    ]
  },
  {
    id: 'pdf',
    name: 'PDF 与文件转换',
    desc: '合并拆分、格式互转、压缩、大文件传输',
    icon: FileText,
    color: '#b45309',
    tint: '#fef3c7',
    items: [
      { name: 'PDF24 工具箱', url: 'https://tools.pdf24.org/zh/', hot: 1, desc: '合并/拆分/压缩/OCR/加水印，功能全、免费、无水印' },
      { name: 'iLovePDF', url: 'https://www.ilovepdf.com/zh-cn', desc: 'PDF 转 Word/Excel/图片，加密解密，处理速度快' },
      { name: 'iLoveIMG', url: 'https://www.iloveimg.com/zh-cn', desc: '图片压缩、裁剪、转格式，也能图片批量转 PDF' },
      { name: '在线转换器', url: 'https://cn.office-converter.com/', desc: 'Office、PDF、图片、音视频等格式互转，格式覆盖最广' },
      { name: 'docsmall', url: 'https://docsmall.com/', desc: '图片 / PDF / GIF 压缩，单文件 25M 内免费，画质损失小' },
      { name: '文叔叔', url: 'https://www.wenshushu.cn/', desc: '大文件传输，无需注册，发样品资料、检测报告方便' },
      { name: '微信文件传输助手', url: 'https://filehelper.weixin.qq.com/', desc: '电脑与手机互传文件，替代数据线，扫码即用' },
      { name: 'PDF 扫描件效果', url: 'https://toolwa.com/pdf-scanner/', desc: '把 PDF 做成带噪点、阴影的扫描件样子' },
    ]
  },
  {
    id: 'img',
    name: '图片处理',
    desc: '压缩、抠图、改尺寸、在线 PS',
    icon: Image,
    color: '#be185d',
    tint: '#fce7f3',
    items: [
      { name: 'TinyPNG', url: 'https://tinypng.com/', hot: 1, desc: 'PNG / JPG 智能压缩，体积常能减 60% 以上，肉眼几乎无损' },
      { name: 'remove.bg', url: 'https://www.remove.bg/zh', desc: '一键去掉背景，做产品图、宣传图很方便' },
      { name: 'Photopea', url: 'https://www.photopea.com/', desc: '浏览器里的 Photoshop，能打开和保存 PSD 文件' },
      { name: '改图宝', url: 'https://www.gaitubao.com/', desc: '在线改尺寸、裁剪、加水印、压缩，不用装软件' },
      { name: '图片加水印', url: 'https://toolwa.com/watermark/', desc: '给产品图、报价单批量加水印，防止被同行直接盗用' },
      { name: '图片 EXIF 信息查看', url: 'https://toolwa.com/exif/', desc: '看照片的拍摄设备与 GPS 定位 —— 发图给客户前建议先查一眼' },
      { name: '在线修图 TUI-EDITOR', url: 'https://toolwa.com/image-editor/', desc: '浏览器里直接修图，支持图层与滤镜' },
      { name: 'GIF 压缩', url: 'https://toolwa.com/gif-compress/', desc: '缩小动图体积，可缩放尺寸、抽帧降速' },
      { name: '圆角图片生成', url: 'https://toolwa.com/image-round/', desc: '给图片批量加圆角，做卡片图用' },
    ]
  },
  {
    id: 'dev',
    name: '数据与开发',
    desc: 'JSON 格式化、编码转换、正则、时间戳',
    icon: Braces,
    color: '#1d4ed8',
    tint: '#dbeafe',
    items: [
      { name: 'BEJSON', url: 'https://www.bejson.com/', hot: 1, desc: 'JSON 格式化/校验/转 Excel，另有几十种编码与转换工具' },
      { name: 'JSON 中文网', url: 'https://www.json.cn/', desc: 'JSON 在线解析、格式化、压缩，结构一目了然' },
      { name: '在线工具 tool.lu', url: 'https://tool.lu/', desc: '上百个小工具合集：时间戳、编码、文本对比、图片处理' },
      { name: '时间戳转换', url: 'https://tool.lu/timestamp/', desc: 'Unix 时间戳与北京时间互转，对接口数据很有用' },
      { name: '正则测试 regex101', url: 'https://regex101.com/', desc: '调试正则表达式，实时解释每一段的含义' },
      { name: '菜鸟工具', url: 'https://c.runoob.com/', desc: '在线编译、格式化、转换工具合集，前端后端都有' },
      { name: 'ToolHelper', url: 'https://www.toolhelper.cn/', desc: '编码转换、CRC 校验、进制转换等偏专业的小工具' },
      { name: 'Markdown 编辑器', url: 'https://markdown.com.cn/editor/', desc: '写公众号、说明文档先在这里排版，再复制出去' },
      { name: '文本对比', url: 'https://tool.lu/diff/', desc: '两段文本或代码找差异，核对合同、报价改动很方便' },
      { name: 'OCR 文字识别', url: 'https://toolwa.com/ocr/', desc: '把图片里的文字提出来 —— 报价单、名片、单据拍照即可转文字' }
    ]
  },
  {
    id: 'design',
    name: '设计排版',
    desc: '流程图、思维导图、图标、配色',
    icon: Palette,
    color: '#0e7490',
    tint: '#cffafe',
    items: [
      { name: 'ProcessOn', url: 'https://www.processon.com/', hot: 1, desc: '在线画流程图、思维导图、组织架构图，免费额度够用' },
      { name: 'draw.io', url: 'https://app.diagrams.net/', desc: '完全免费的流程图工具，文件可存本地，不注册也能用' },
      { name: '百度脑图', url: 'https://naotu.baidu.com/', desc: '轻量思维导图，登录即用，适合快速理清思路' },
      { name: '阿里图标库 iconfont', url: 'https://www.iconfont.cn/', desc: '免费图标下载，做 PPT、宣传图找图标首选' },
      { name: '中国色', url: 'https://www.zhongguose.com/', desc: '中国传统色卡，配色拿不准时可以参考取色' },
      { name: '在线白板', url: 'https://toolwa.com/whiteboard/', desc: '支持压感手绘，讲流程、画工艺时可直接标注' },
    ]
  },
  {
    id: 'chem',
    name: '化工与外贸',
    desc: '查 CAS、理化性质、材料数据库、MSDS、HS 编码与单位换算',
    icon: FlaskConical,
    color: '#c2410c',
    tint: '#ffedd5',
    items: [
      { name: 'ChemicalBook', url: 'https://www.chemicalbook.com/', hot: 1, desc: '按中文名/CAS 号查理化性质、上下游、供应商，行业常用' },
      { name: '化源网', url: 'https://www.chemsrc.com/', desc: '化学品 MSDS、海关编码、价格行情与供应商信息' },
      { name: 'UL Prospector 材料库', url: 'https://www.ulprospector.com/session/new?redirect=https%3A%2F%2Fmaterials.ulprospector.com%2Fen%2Fsearch', hot: 1, desc: '塑料 / 橡胶 / 涂料 / 胶粘剂原料库，查牌号、性能参数与供应商（需免费注册）' },
      { name: 'MSDS 查询', url: 'https://www.somsds.com/', desc: '安全技术说明书检索，出运、报关、安全审核常要' },
      { name: 'PubChem', url: 'https://pubchem.ncbi.nlm.nih.gov/', desc: '全球权威化学品数据库，结构与性质数据最可信（英文）' },
      { name: 'ChemCalc 分子量计算', url: 'https://www.chemcalc.org/', desc: '输入分子式自动算分子量、元素分析百分比' },
      { name: '单位换算 convertworld', url: 'https://www.convertworld.com/zh-hans/', hot: 1, desc: '压力、温度、粘度、密度、体积等 100+ 单位互换' },
      { name: 'HS 编码查询', url: 'https://www.hsbianma.com/', desc: '出口报关 HS 编码、申报要素与退税率查询' },
      { name: '海关编码查询', url: 'https://www.i5a6.com/', desc: 'HS 编码与监管条件查询，外贸报关用得上' },
      { name: '百度翻译', url: 'https://fanyi.baidu.com/', desc: '外贸邮件、单据、技术资料翻译，可整篇翻' },
      { name: '万能条码生成', url: 'https://toolwa.com/barcode-generator/', desc: '支持 100+ 种条码（CODE128/EAN13 等），做物料、包装、库房标签用' },
      { name: '高级条码生成', url: 'https://toolwa.com/barcode/', desc: 'CODE128、EAN13、UPC、CODE39 等常见规格' },
    ]
  },
  {
    id: 'cert',
    name: '认证与合规查询',
    desc: '查阻燃等级、消防产品认证、标准全文与第三方检测证书真伪',
    icon: ShieldCheck,
    color: '#4338ca',
    tint: '#e0e7ff',
    items: [
      { name: 'UL Product iQ（UL 黄卡）', url: 'https://productiq.ulprospector.com/', hot: 1, desc: '查塑料 UL 94 阻燃等级（V-0/5VA 等）与黄卡认证，国际最权威；需注册，首次打开需过人机验证' },
      { name: '消防产品认证信息查询', url: 'https://www.cccf.com.cn:8088/certSearch/page/qzxrzxxgbnew', hot: 1, desc: '应急管理部消防产品强制性认证（CCCF）官方查询，防火/阻燃制品证书可查真伪' },
      { name: '消防产品合格评定中心', url: 'https://www.cccf.net.cn/', desc: 'CCCF 发证机构，查认证规则、实施细则与技术鉴定要求' },
      { name: '应急管理部四川消防研究所', url: 'https://www.scfri.cn/', desc: '国家防火建筑材料质量检验检测中心，GB 8624 建筑材料燃烧性能分级检测' },
      { name: '国家标准全文公开系统', url: 'https://openstd.samr.gov.cn/bzgk/gb/', hot: 1, desc: '免费看国标全文：GB 8624（建材燃烧性能）、GB/T 2408（塑料燃烧性能）等' },
      { name: '中国质量认证中心 CQC', url: 'https://www.cqc.com.cn/', desc: 'CQC 自愿性认证证书查询，含部分材料的阻燃与安全认证' },
      { name: 'TÜV 莱茵 Certipedia', url: 'https://www.certipedia.com/', desc: '德国莱茵证书与检测报告编号查询，验证真伪' },
      { name: 'SGS 中国', url: 'https://www.sgsgroup.com.cn/', desc: 'SGS 检测报告与证书查询，外贸客户常认这个' },
      { name: 'Intertek 天祥', url: 'https://www.intertek.com/', desc: 'Intertek 检测认证与证书查询，建材/塑料阻燃测试' }
    ]
  },
  {
    id: 'text',
    name: '文本与统计',
    desc: '写文案、整理名单、算化验数据的平均值与偏差',
    icon: Newspaper,
    color: '#0f766e',
    tint: '#ccfbf1',
    items: [
      { name: '文章字符统计', url: 'https://toolwa.com/char-count/', hot: 1, desc: '统计字数、中英文与标点数量、段句数并估算阅读时长 —— 写公众号标题和摘要时卡字数很好用' },
      { name: '文本去除重复行', url: 'https://toolwa.com/deduplication/', desc: '导入客户名单、产品清单后一键去重，并显示重复了多少行' },
      { name: '数理统计分析', url: 'https://toolwa.com/stats/', desc: '输入一组数值自动算平均值、标准偏差、相对偏差，并生成频数柱状图（化验/质检数据用得上）' },
      { name: '文本对比合并', url: 'https://toolwa.com/merge/', desc: '找出两段文本的差异并合并' },
    ]
  },
  {
    id: 'site',
    name: '网站与备案',
    desc: '维护自家网站：查备案、查微信拦截、做图标',
    icon: Globe,
    color: '#a21caf',
    tint: '#fae8ff',
    items: [
      { name: '域名备案查询', url: 'https://toolwa.com/beian/', hot: 1, desc: '查域名在工信部 / 公安部的备案信息与备案号，合作前核实对方资质也用得上' },
      { name: '域名微信拦截检测', url: 'https://toolwa.com/check-weixin/', desc: '检查网址在微信里是否被拦截 —— 在公众号、朋友圈推广前先自检一次' },
      { name: 'Favicon 图标制作', url: 'https://toolwa.com/favicon/', desc: '把 jpg/png 转成浏览器标签页用的 .ico 图标，自己做站或做小程序时用' }
    ]
  },
  {
    id: 'code',
    name: '代码工具',
    desc: '格式化、加解密、编码转换等开发常用小工具',
    icon: Code,
    color: '#334155',
    tint: '#e2e8f0',
    items: [
      { name: 'JSON 格式化', url: 'https://toolwa.com/json/', desc: '格式化与校验 JSON，还能转 XML / YAML / CSV' },
      { name: 'XML 转 JSON', url: 'https://toolwa.com/xml2json/', desc: 'XML 与 JSON 互转，对接老接口时常用' },
      { name: 'JSON 转 XML', url: 'https://toolwa.com/json2xml/', desc: 'JSON 与 XML 互转' },
      { name: 'HTML 代码格式化', url: 'https://toolwa.com/html-formatter/', desc: '一键排版，缩进混乱的代码立刻变整齐' },
      { name: 'CSS 代码格式化', url: 'https://toolwa.com/css-formatter/', desc: '一键排版 CSS 代码' },
      { name: 'JavaScript 代码格式化', url: 'https://toolwa.com/js-formatter/', desc: '一键排版 JS 代码' },
      { name: 'SQL 代码格式化', url: 'https://toolwa.com/sql-formatter/', desc: '一键排版 SQL，长语句看得清' },
      { name: 'XML 代码格式化', url: 'https://toolwa.com/xml-formatter/', desc: '一键排版 XML 代码' },
      { name: '去除代码注释', url: 'https://toolwa.com/remove-comments/', desc: '清理注释、空行与首尾空格' },
      { name: 'SVG 压缩优化', url: 'https://toolwa.com/svg-compress/', desc: '可粘贴代码或批量上传' },
      { name: 'CSS 虚线边框生成', url: 'https://toolwa.com/dashed-border-generator/', desc: '调好参数直接复制 CSS' },
      { name: 'JavaScript 混淆加密', url: 'https://toolwa.com/js-obfuscator/', desc: '保护前端代码不被直接抄走' },
      { name: 'JS 压缩与 eval 加解密', url: 'https://toolwa.com/js-packer/', desc: '一键还原 eval 打包过的代码' },
      { name: 'JS aaEncode 加密解密', url: 'https://toolwa.com/aaencode/', desc: '把 JS 变成颜文字' },
      { name: 'JS jjEncode 加密解密', url: 'https://toolwa.com/jjencode/', desc: 'JS 代码的 jjEncode 编解码' },
      { name: '编码解码', url: 'https://toolwa.com/encode/', desc: 'Unicode / URL / UTF-8 / Base64 互转' },
      { name: 'CRC 校验计算', url: 'https://toolwa.com/crc/', desc: '支持 CRC8/16/24/32/64 与自定义参数' },
      { name: 'UUID 生成与校验', url: 'https://toolwa.com/uuid/', desc: '可批量生成' },
      { name: '串口调试助手', url: 'https://toolwa.com/serial/', desc: '网页版串口调试，配合硬件设备用' },
      { name: 'WebSocket 调试助手', url: 'https://toolwa.com/websocket/', desc: '建立连接测试通讯' },
      { name: '正则表达式在线测试', url: 'https://toolwa.com/regex/', desc: '边写边看匹配结果' },
      { name: '正则生成多语言代码', url: 'https://toolwa.com/regex-code/', desc: 'JS / Python / Java / PHP 等一次生成' },
      { name: '正则表达式可视化', url: 'https://toolwa.com/regexper/', desc: '用图解方式看懂正则' },
      { name: '正则表达式大全', url: 'https://toolwa.com/regex-list/', desc: '常用正则速查，拿来即用' },
      { name: 'ASCII 转换', url: 'https://toolwa.com/ascii/', desc: '字符与 ASCII 互转，附完整码表' },
      { name: '进制转换', url: 'https://toolwa.com/base-conv/', desc: '2~36 进制互转，支持浮点数' },
      { name: 'Unix 时间戳转换', url: 'https://toolwa.com/timestamp/', desc: '时间戳与时间文本互转' },
      { name: '变量命名风格转换', url: 'https://toolwa.com/var-convert/', desc: '大驼峰、小驼峰、下划线等一键切换' },
      { name: '英文大小写转换', url: 'https://toolwa.com/case-convert/', desc: '全大写 / 全小写 / 首字母大写 / 反转' },
      { name: '文件夹目录树生成', url: 'https://toolwa.com/dir-tree/', desc: '选目录即可生成目录树文本' },
    ]
  },
  {
    id: 'av',
    name: '音视频工具',
    desc: '录屏、剪辑、音频处理与拍摄辅助',
    icon: Video,
    color: '#0369a1',
    tint: '#e0f2fe',
    items: [
      { name: '在线屏幕录制', url: 'https://toolwa.com/record/', desc: '免安装录屏，做操作演示、教程用' },
      { name: '在线音频编辑器', url: 'https://toolwa.com/audiomass/', desc: '浏览器里剪辑音频、看波形' },
      { name: '全能在线视频处理', url: 'https://toolwa.com/video/', desc: '格式转换、剪辑裁剪、画面调整等 30+ 种操作' },
      { name: '在线扩音器', url: 'https://toolwa.com/amplifier/', desc: '把麦克风变成扩音器，支持变声与录音' },
      { name: '在线调音器', url: 'https://toolwa.com/tuner/', desc: '吉他、钢琴、古筝等乐器调音' },
      { name: '在线提词器', url: 'https://toolwa.com/tcq/', desc: '拍视频、直播时照着念稿子' },
    ]
  },
  {
    id: 'hw',
    name: '硬件与设备检测',
    desc: '鼠标键盘、屏幕、显卡、麦克风等设备自检',
    icon: Cpu,
    color: '#57534e',
    tint: '#f5f5f4',
    items: [
      { name: '鼠标检测', url: 'https://toolwa.com/mouse-test/', desc: '测按键、滚轮、移动轨迹与点击速度' },
      { name: '键盘检测', url: 'https://toolwa.com/keyboard-test/', desc: '测按键、多键无冲、打字速度' },
      { name: '游戏手柄检测', url: 'https://toolwa.com/gamepad-test/', desc: '测按键、摇杆死区与震动马达' },
      { name: '麦克风检测', url: 'https://toolwa.com/mic-test/', desc: '实时看音量、波形、底噪，支持回放' },
      { name: '扬声器 / 耳机检测', url: 'https://toolwa.com/speaker-test/', desc: '测左右声道、频率响应与相位' },
      { name: '摄像头检测', url: 'https://toolwa.com/camera-test/', desc: '测清晰度、对焦、坏点与色彩' },
      { name: 'CPU 性能测试', url: 'https://toolwa.com/cpu-test/', desc: '八个维度跑分，分单核与多核' },
      { name: 'GPU 显卡性能测试', url: 'https://toolwa.com/gpu-test/', desc: 'WebGL 渲染压测，读显卡型号' },
      { name: '传感器检测', url: 'https://toolwa.com/sensor-test/', desc: '加速度、陀螺仪、磁力计，兼作指南针' },
      { name: '触摸屏检测', url: 'https://toolwa.com/touch-test/', desc: '测多点触控、划线与死区' },
      { name: '屏幕检测', url: 'https://toolwa.com/screentest/', desc: '对显示设备做一次全面体检' },
      { name: '显示器坏点修复', url: 'https://toolwa.com/screen-fix/', desc: '用高频色彩循环刺激卡死的像素' },
      { name: '刷新率与帧率测试', url: 'https://toolwa.com/fps-test/', desc: '测真实刷新率与掉帧（UFO Test）' },
      { name: '显示器参数查看', url: 'https://toolwa.com/monitor-info/', desc: '分辨率、刷新率、色深、色域、PPI' },
      { name: '手机震动测试', url: 'https://toolwa.com/vibration/', desc: '自定义时长与节奏' },
    ]
  },
  {
    id: 'web',
    name: '站长工具',
    desc: '域名、服务器、SEO 与网站运营相关查询',
    icon: Server,
    color: '#4d7c0f',
    tint: '#ecfccb',
    items: [
      { name: 'Whois 域名查询', url: 'https://toolwa.com/whois/', desc: '查域名是否被注册及注册信息' },
      { name: '网站排名查询', url: 'https://toolwa.com/apppc/', desc: '查网站世界排名与访问量' },
      { name: 'Punycode 编解码', url: 'https://toolwa.com/punycode/', desc: '中文域名编码解码' },
      { name: 'URL 解析拆分', url: 'https://toolwa.com/url-parser/', desc: '拆出域名各部分与参数' },
      { name: '链接批量生成器', url: 'https://toolwa.com/link-gen/', desc: '按等差/等比/字母规律批量生成网址' },
      { name: 'HTTP 状态码对照表', url: 'https://toolwa.com/http-status-codes/', desc: '排查网页、接口问题时速查' },
      { name: '搜索下拉词查询', url: 'https://toolwa.com/dropdown-word/', desc: '百度 / 必应 / 搜狗等下拉词获取' },
      { name: '定时刷新网页', url: 'https://toolwa.com/timed-refresh/', desc: '设定间隔自动刷新页面' },
      { name: '网页响应式缩略图', url: 'https://toolwa.com/responsive/', desc: '一次预览网站在多种尺寸下的效果' },
      { name: 'UserAgent 分析', url: 'https://toolwa.com/ua/', desc: '从 UA 解析系统与浏览器信息' },
      { name: '浏览器信息查询', url: 'https://toolwa.com/browserinfo/', desc: '查当前客户端的系统与浏览器信息' },
      { name: 'IP 地址查询', url: 'https://toolwa.com/ip/', desc: '查指定 IP 的归属地' },
      { name: '子网掩码计算器', url: 'https://toolwa.com/subnet-calc/', desc: '算网络地址、广播地址与可用主机范围' },
      { name: '硬盘整数分区计算', url: 'https://toolwa.com/disk-partition/', desc: '算出整 GiB 的分区方案' },
      { name: 'MAC 地址查厂商', url: 'https://toolwa.com/mac/', desc: '由 MAC 反查设备生产厂商' },
      { name: 'DNS 检测', url: 'https://toolwa.com/dns/', desc: '判断 DNS 是否设置正确、是否被劫持' },
    ]
  },
  {
    id: 'util',
    name: '便民工具',
    desc: '计算、查询、密码等日常小工具',
    icon: Wrench,
    color: '#a16207',
    tint: '#fef9c3',
    items: [
      { name: '亲戚关系计算器', url: 'https://toolwa.com/relationship/', desc: '逢年过节算称呼不出错' },
      { name: '生日年龄计算器', url: 'https://toolwa.com/age/', desc: '支持公历农历，含生肖星座与下次生日' },
      { name: 'BMI 与体脂计算器', url: 'https://toolwa.com/bmi/', desc: '算 BMI、测体脂一体' },
      { name: '表达式计算器', url: 'https://toolwa.com/free-calc/', desc: '支持变量与 JS 语法，随手算账很方便' },
      { name: '特殊符号大全', url: 'https://toolwa.com/symbol/', desc: '各类符号一键复制，排版文案用' },
      { name: '剪切板内容查看', url: 'https://toolwa.com/clipboard/', desc: '看看剪贴板里到底装了什么' },
      { name: '手机号归属地查询', url: 'https://toolwa.com/phone/', desc: '查归属地与运营商' },
      { name: '车架号（VIN）查询', url: 'https://toolwa.com/vin/', desc: '查汽车产地、厂商与年份' },
      { name: '2FA 动态口令', url: 'https://toolwa.com/totp/', desc: '在线管理 TOTP 一次性密码' },
      { name: '随机密码生成', url: 'https://toolwa.com/password-gen/', desc: '按规则批量生成强密码' },
      { name: '密码安全检测', url: 'https://toolwa.com/pwcheck/', desc: '看你的密码被破解需要多久' },
      { name: '保持屏幕常亮', url: 'https://toolwa.com/keep-screen-on/', desc: '演示、看盘时防止屏幕休眠' },
      { name: '在线水平仪', url: 'https://toolwa.com/spy/', desc: '打开手机就能当水平仪用' },
    ]
  },
  {
    id: 'fun',
    name: '休闲娱乐',
    desc: '解压放松用，工作之余换换脑子',
    icon: PartyPopper,
    color: '#db2777',
    tint: '#fce7f3',
    items: [
      { name: '聆 · 音 白噪音', url: 'https://toolwa.com/relax/', desc: '免下载的在线白噪音，专注或休息时放一放' },
      { name: '几枝 · 每日诗词', url: 'https://toolwa.com/jizhi/', desc: '经典诗词配传统色的层叠波浪动画' },
      { name: '手鼓猫 Bongo Cat', url: 'https://toolwa.com/bongocat/', desc: '会跟着敲键盘的手鼓猫' },
      { name: 'Mikutap 音游', url: 'https://toolwa.com/mikutap/', desc: '初音未来主题的点击发声互动页' },
      { name: '音乐旋律生成器', url: 'https://toolwa.com/blossom/', desc: '轻点几下生成一段旋律' },
      { name: '魔法键盘', url: 'https://toolwa.com/magic-keyboard/', desc: '黑客风格打字音效' },
      { name: '声控弹力球', url: 'https://toolwa.com/bouncy-balls/', desc: '小球随音量跳动，也能当噪音监测' },
      { name: '氛围频谱', url: 'https://toolwa.com/pp/', desc: '把环境声音做成可视化频谱' },
      { name: '摸头杀生成器', url: 'https://toolwa.com/petpet/', desc: '万物皆可摸头' },
      { name: '到账音效生成器', url: 'https://toolwa.com/receipt/', desc: '支付宝到账音效，做段子用' },
      { name: '电子木鱼', url: 'https://toolwa.com/wooden-fish/', desc: '随时随地刷功德' },
      { name: '架子鼓模拟器', url: 'https://toolwa.com/durms/', desc: '在线敲架子鼓' },
      { name: '水桶鼓', url: 'https://toolwa.com/bucket-drums/', desc: '锅碗瓢盆打击乐' },
      { name: '烟花模拟器', url: 'https://toolwa.com/firework/', desc: '放一场属于自己的烟花' },
      { name: '捏泡泡模拟器', url: 'https://toolwa.com/bubble/', desc: '在线捏泡泡纸，解压' },
      { name: '在线空调', url: 'https://toolwa.com/ac/', desc: '便携小空调（缺点是没有风）' },
      { name: '云壁炉', url: 'https://toolwa.com/fireplace/', desc: '电子火炉，看着暖和' },
      { name: '云吸猫模拟器', url: 'https://toolwa.com/cat/', desc: '一只会呼噜的在线猫' },
      { name: '喵语翻译', url: 'https://toolwa.com/miao/', desc: '把人的话翻译成喵语' },
      { name: '兽音译者', url: 'https://toolwa.com/beast/', desc: '把人的话翻译成兽语' },
      { name: '答案之书', url: 'https://toolwa.com/book-of-answers/', desc: '拿不定主意时翻一页' },
      { name: '在线冥想', url: 'https://toolwa.com/mx/', desc: '60 秒冥想，帮助走出困境' },
      { name: '摸鱼办日历提醒', url: 'https://toolwa.com/myb/', desc: '距下班、距放假还有多久' },
      { name: 'QQ 号估值', url: 'https://toolwa.com/qq-value/', desc: '从位数与数字组合估算价值' },
      { name: '今天吃什么', url: 'https://toolwa.com/eat/', desc: '解决午饭这一人生难题' },
      { name: '外卖红包', url: 'https://toolwa.com/coupons/', desc: '美团、淘宝闪购红包领取入口' },
      { name: '舔狗日记', url: 'https://toolwa.com/dog/', desc: '舔狗舔狗，舔到最后一无所有' },
      { name: '毒鸡汤', url: 'https://toolwa.com/soup/', desc: '1000+ 条扎心文案，可当素材库' },
      { name: '营销号生成器', url: 'https://toolwa.com/yxh/', desc: '一键生成营销号文风' },
      { name: '彩虹屁生成器', url: 'https://toolwa.com/chp/', desc: '夸人词穷时的救命工具' },
      { name: '狗屁不通文章生成器', url: 'https://toolwa.com/bullshit/', desc: '生成大段占位文本，测排版用' },
      { name: '互联网黑话生成器', url: 'https://toolwa.com/bullshit-internet/', desc: '赋能、抓手、闭环…一键成文' },
      { name: '菊花文生成器', url: 'https://toolwa.com/juhua/', desc: '文字变形效果，发着玩' },
      { name: '藏头诗生成器', url: 'https://toolwa.com/cts/', desc: '输入名字生成藏头诗' },
      { name: '流体模拟器', url: 'https://toolwa.com/fluid-simulation/', desc: 'WebGL 流体效果，看着解压' },
      { name: '程序员求签', url: 'https://toolwa.com/code-divine/', desc: '求一签看看今天的编程运势' },
      { name: '塞尔达在线地图', url: 'https://toolwa.com/zelda/', desc: '旷野之息全地图' },
      { name: '迷宫生成器', url: 'https://toolwa.com/maze/', desc: '随机生成各种迷宫图形' },
      { name: '程序员易错发音单词', url: 'https://toolwa.com/cpwp/', desc: '常读错的技术词汇与正确读音' },
      { name: '故障风格图片生成', url: 'https://toolwa.com/glitch-image/', desc: '一键做出赛博故障风效果图' },
      { name: '头像生成器', url: 'https://toolwa.com/avatars/', desc: '30+ 种风格一键生成头像' },
      { name: '丑萌头像生成器', url: 'https://toolwa.com/ugly-avatar/', desc: '生成别具一格的搞笑头像' },
      { name: 'ASCII 艺术字生成', url: 'https://toolwa.com/ascii-art/', desc: '300+ 复古字体，可复制或导出 TXT' },
      { name: '声音倒放', url: 'https://toolwa.com/rs/', desc: '录音或上传音频后倒放，听倒放彩蛋' },
      { name: '毒蘑菇性能测试', url: 'https://toolwa.com/vsbm/', desc: '恶搞版显卡压力测试，蘑菇越冒越欢' },
      { name: '鱼缸性能测试', url: 'https://toolwa.com/fish/', desc: '鱼越多说明机器越强，直观有趣' },
    ]
  }
]

const kw = ref('')
const active = ref('all')
const badIcon = ref({})   // 图标加载失败的条目（回退首字方块）
const expanded = ref({})  // 分类展开状态（大分类默认只显示前 12 个，避免上百张卡片铺一屏）
const FOLD_LIMIT = 12

/* 「休闲娱乐」类默认收起：工具页代表平台形象，娱乐类（39 个）一上来就铺出来不合适，
   但收录是完整的，所以做成开关 —— 点按钮才显示，选择记在 localStorage，刷新后保持。 */
const FUN_ID = 'fun'
const showFun = ref(false)
try { showFun.value = localStorage.getItem('chemprice_tools_fun') === '1' } catch (e) { /* 无痕模式等 */ }
function toggleFun() {
  showFun.value = !showFun.value
  if (!showFun.value && active.value === FUN_ID) active.value = 'all'  // 收起时若正筛着该类，退回全部
  try { localStorage.setItem('chemprice_tools_fun', showFun.value ? '1' : '0') } catch (e) { /* ignore */ }
}

/* 默认视图（未搜索、未筛选分类）下，超长分类先折叠；一旦搜索或选中某类就全部展开 */
function shown(g) {
  if (kw.value.trim() || active.value !== 'all') return g.items
  return expanded.value[g.id] ? g.items : g.items.slice(0, FOLD_LIMIT)
}
function isFolded(g) {
  return !kw.value.trim() && active.value === 'all' && !expanded.value[g.id] && g.items.length > FOLD_LIMIT
}
function toggleFold(id) {
  expanded.value = { ...expanded.value, [id]: !expanded.value[id] }
}
const funCat = CATS.find((c) => c.id === FUN_ID)
const funCount = funCat ? funCat.items.length : 0
const catList = computed(() => CATS.filter((c) => showFun.value || c.id !== FUN_ID))
const tools = computed(() => catList.value.flatMap((c) => c.items))

/* 娱乐类收起时，搜索命中它的数量 —— 用于空态提示，避免「明明有却搜不到」的困惑 */
const funHiddenMatch = computed(() => {
  const q = kw.value.trim()
  if (showFun.value || !funCat || !q) return 0
  return funCat.items.filter((t) => match(t, funCat, q)).length
})

/* 域名 → 本地图标文件名（与抓取脚本的 slug 规则保持一致：非字母数字转下划线、小写） */
function iconOf(u) {
  let h = u
  try {
    h = new URL(u).hostname
  } catch (e) { /* 保底用原串 */ }
  return '/tool-icons/' + h.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').toLowerCase() + '.png'
}

/* 搜索：名称 / 描述 / 分类名 命中任一即可（不区分大小写） */
/* 搜索：名称 / 描述 / 分类名 / 域名 命中任一即可（不区分大小写）
   ⚠️ 字段必须做空值保护：部分工具（如「XML 转 JSON」）没有 desc 字段，
      直接 t.desc.toLowerCase() 会在「一搜索就整页崩」——2026-09-20 真实踩过。 */
function match(t, c, q) {
  if (!q) return true
  const s = q.toLowerCase()
  return (
    (t.name || '').toLowerCase().includes(s) ||
    (t.desc || '').toLowerCase().includes(s) ||
    (c.name || '').toLowerCase().includes(s) ||
    (t.url || '').toLowerCase().includes(s)
  )
}

const groups = computed(() => {
  const q = kw.value.trim()
  return catList.value
    .filter((c) => active.value === 'all' || c.id === active.value)
    .map((c) => ({ ...c, items: c.items.filter((t) => match(t, c, q)) }))
    .filter((c) => c.items.length)
})

function hostOf(u) {
  try {
    return new URL(u).hostname.replace(/^www\./, '')
  } catch (e) {
    return u
  }
}

function clearAll() {
  kw.value = ''
  active.value = 'all'
}
</script>

<style scoped>
.ot { display: flex; flex-direction: column; gap: 16px; }

/* ---- 顶部卡片 ---- */
.ot-hero {
  display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap;
  background: rgba(247,248,250,.86); backdrop-filter: blur(10px);
  border: 1px solid var(--border); border-radius: var(--rl); padding: 18px 20px;
}
.ot-hero-txt h1 { font-size: 20px; font-weight: 600; letter-spacing: .2px; }
.ot-hero-txt p { margin-top: 6px; font-size: 13px; color: var(--ink3); line-height: 1.7; max-width: 640px; }
.ot-hero-txt b { color: var(--ink2); }
.ot-hero-stat { display: flex; gap: 10px; }
.ot-hero-stat .s {
  min-width: 88px; text-align: center; padding: 10px 14px;
  background: #fff; border: 1px solid var(--border); border-radius: var(--r);
}
.ot-hero-stat .s b { display: block; font-size: 20px; font-weight: 600; color: var(--blue); line-height: 1.2; }
.ot-hero-stat .s span { font-size: 11px; color: var(--ink3); }

/* ---- 搜索与筛选 ---- */
.ot-bar {
  display: flex; flex-direction: column; gap: 12px;
  background: rgba(247,248,250,.86); backdrop-filter: blur(10px);
  border: 1px solid var(--border); border-radius: var(--rl); padding: 14px 16px;
  position: sticky; top: 0; z-index: 5;
}
.ot-search {
  display: flex; align-items: center; gap: 9px;
  background: #fff; border: 1px solid var(--border); border-radius: var(--r);
  padding: 0 12px; height: 38px; transition: border-color .15s, box-shadow .15s;
}
.ot-search:focus-within { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.ot-search svg { width: 16px; height: 16px; color: var(--ink4); flex-shrink: 0; }
.ot-search input { flex: 1; height: 100%; border: 0; outline: 0; background: none; font: inherit; font-size: 13px; color: var(--ink); }
.ot-search input::placeholder { color: var(--ink4); }
.ot-clear {
  width: 20px; height: 20px; border: 0; border-radius: 50%; cursor: pointer;
  background: var(--ink5); color: var(--ink3); font-size: 14px; line-height: 1;
  display: flex; align-items: center; justify-content: center;
}
.ot-clear:hover { background: #e4e4e7; color: var(--ink2); }

.ot-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.ot-chips .chip {
  display: inline-flex; align-items: center; gap: 6px;
  height: 28px; padding: 0 11px; border-radius: 999px; cursor: pointer;
  font: inherit; font-size: 12px; color: var(--ink2);
  background: #fff; border: 1px solid var(--border); transition: all .12s;
}
.ot-chips .chip:hover { border-color: #b9c0cc; background: #fbfbfd; }
.ot-chips .chip.on { background: var(--ink2); border-color: var(--ink2); color: #fff; }
.ot-chips .chip svg { width: 13px; height: 13px; }
.ot-chips .chip i { font-style: normal; opacity: .6; font-size: 11px; }
/* 「休闲娱乐」开关：虚线 + 粉色系，一眼区别于「已选中的分类」（后者是实心深色） */
.ot-chips .chip-fun { border-style: dashed; color: #be185d; border-color: #fbcfe8; }
.ot-chips .chip-fun:hover { border-color: #f9a8d4; background: #fff7fb; }
.ot-chips .chip-fun svg { color: #db2777; }
/* 开启态用实底粉色 + 白字：与「休闲娱乐」筛选 chip 的浅色区分开，一眼看出这是开关且已打开 */
.ot-chips .chip-fun.on { background: #db2777; border-color: #db2777; color: #fff; }
.ot-chips .chip-fun.on:hover { background: #be185d; border-color: #be185d; }
.ot-chips .chip-fun.on svg { color: #fff; }
.ot-chips .chip-fun.on i { color: #fff; opacity: .8; }

/* ---- 分组 ---- */
.ot-groups { display: flex; flex-direction: column; gap: 18px; }
.ot-group {
  background: rgba(247,248,250,.86); backdrop-filter: blur(10px);
  border: 1px solid var(--border); border-radius: var(--rl); padding: 16px;
}
.ot-ghead { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.ot-gicon {
  width: 30px; height: 30px; border-radius: var(--r); flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
}
.ot-gicon svg { width: 16px; height: 16px; }
.ot-ghead h2 { font-size: 14px; font-weight: 600; }
.ot-gnum {
  font-size: 11px; color: var(--ink3); background: var(--ink5);
  border-radius: 999px; padding: 1px 7px;
}
.ot-gdesc { font-size: 12px; color: var(--ink4); margin-left: 2px; }

.ot-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(232px, 1fr)); gap: 10px; }

/* ---- 工具卡 ---- */
.ot-card {
  position: relative; display: flex; flex-direction: column; gap: 7px;
  background: #fff; border: 1px solid var(--border); border-radius: var(--r);
  padding: 12px 13px; text-decoration: none; color: inherit;
  transition: transform .12s, border-color .12s, box-shadow .12s;
}
.ot-card:hover {
  transform: translateY(-2px); border-color: var(--blue);
  box-shadow: 0 6px 18px -8px rgba(9,9,11,.28);
}
.ot-hot {
  position: absolute; top: 9px; right: 10px;
  font-size: 10px; font-weight: 500; color: #92400e;
  background: #fef3c7; border: 1px solid #fde68a; border-radius: 5px; padding: 0 5px;
}
.ot-c-top { display: flex; align-items: center; gap: 8px; }
/* 站点真实图标（构建期抓取到本站，运行时零外部请求）。
   浅底 + 极细描边：白色/透明的 logo 放在纯白卡片上会看不见。 */
.ot-c-img {
  width: 24px; height: 24px; border-radius: 6px; flex-shrink: 0;
  object-fit: contain; box-sizing: border-box; padding: 1px;
  background: #f7f8fa; border: 1px solid #edeff2;
}
/* 没有 favicon 或加载失败时的回退：首字方块 */
.ot-c-logo {
  width: 24px; height: 24px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
}
.ot-c-name {
  font-size: 13px; font-weight: 500; flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ot-c-ext { width: 13px; height: 13px; color: var(--ink4); flex-shrink: 0; opacity: 0; transition: opacity .12s; }
.ot-card:hover .ot-c-ext { opacity: 1; }
.ot-c-desc { font-size: 11.5px; color: var(--ink3); line-height: 1.65; flex: 1; }
.ot-c-host { font-size: 10.5px; color: var(--ink4); }

/* ---- 空态与页脚 ---- */
.ot-more {
  display: block; width: 100%; margin-top: 10px; padding: 8px 0;
  border: 1px dashed var(--border); border-radius: var(--r); cursor: pointer;
  font-family: inherit; font-size: 12px; color: var(--ink3); background: #fff;
  transition: all .12s;
}
.ot-more:hover { border-color: var(--blue); color: var(--blue); background: #f8fbff; }

.ot-empty {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 56px 20px; background: rgba(247,248,250,.86);
  border: 1px dashed var(--border); border-radius: var(--rl); color: var(--ink3);
}
.ot-empty svg { width: 26px; height: 26px; color: var(--ink4); }
.ot-empty p { font-size: 13px; }
.ot-empty button {
  height: 30px; padding: 0 14px; border-radius: var(--r); cursor: pointer;
  font: inherit; font-size: 12px; color: #fff; background: var(--blue); border: 0;
}
.ot-empty .ot-empty-tip { font-size: 12px; color: var(--ink4); }
/* 次要按钮（描边）：权重需高于上面的 `.ot-empty button`，故带上 button 元素选择器 */
.ot-empty button.ot-empty-alt {
  color: var(--ink2); background: #fff; border: 1px solid var(--border);
}
.ot-empty button.ot-empty-alt:hover { border-color: var(--blue); color: var(--blue); background: #f8fbff; }
.ot-foot { font-size: 11.5px; color: var(--ink4); text-align: center; padding: 2px 0 6px; line-height: 1.7; }

@media (max-width: 760px) {
  .ot-hero { flex-direction: column; align-items: flex-start; }
  .ot-bar { position: static; }
  .ot-grid { grid-template-columns: 1fr; }
}
</style>
