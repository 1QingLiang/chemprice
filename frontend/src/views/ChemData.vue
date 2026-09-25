<template>
  <div class="cq">
    <!-- ===== 网页水印（覆盖整页，穿透点击） ===== -->
    <div class="cq-wm" aria-hidden="true">
      <span v-for="i in wmTiles" :key="i" class="cq-wm-t">{{ WM_TEXT }}</span>
    </div>

    <!-- ===== 顶部 ===== -->
    <section class="cq-hero">
      <div class="cq-hero-txt">
        <h1>物性查询</h1>
        <p>
          输入平台品种名，获取该化学品的<b>结构化资料</b>：基础简介、危化品判定、运输要求与现货价格参考，
          用于采购、运输与报价决策。物性与法规数据来自国际权威机构，价格数据来自平台自主采集行情。
        </p>
      </div>
      <div class="cq-hero-stat">
        <div class="s"><b>{{ coveredCount }}</b><span>已收录物性品种</span></div>
        <div class="s"><b>{{ sources.length }}</b><span>个权威数据源</span></div>
      </div>
    </section>

    <!-- ===== 搜索 ===== -->
    <section class="cq-bar">
      <label class="cq-search">
        <Search />
        <input
          v-model="kw"
          type="text"
          placeholder="搜索品种：叔丁醇 / 甲醇 / 苯乙烯 / PTA …"
          @keyup.enter="doSearch"
        />
        <button v-if="kw" class="cq-clear" type="button" title="清空" @click="kw = ''">×</button>
      </label>
      <button class="cq-go" type="button" :disabled="loading" @click="doSearch">
        {{ loading ? '查询中…' : '查询' }}
      </button>
    </section>

    <div class="cq-quick">
      <span class="cq-quick-lb">常用：</span>
      <button v-for="n in quickList" :key="n" class="cq-chip" type="button" @click="pick(n)">
        {{ n }}
      </button>
    </div>

    <!-- ===== 结果 ===== -->
    <div v-if="result" class="cq-result">
      <!-- 品种名 + 状态 -->
      <div class="cq-rhead">
        <div class="cq-rname">
          <h2>{{ result.name }}</h2>
          <span v-if="result.covered" class="cq-badge ok">已收录</span>
          <span v-else class="cq-badge no">无单一化合物标识</span>
          <span v-if="result.isPolymer" class="cq-badge poly">聚合物 · 按单体展示</span>
          <span v-if="isHaz === true" class="cq-badge danger">危险化学品</span>
          <span v-else-if="isHaz === false" class="cq-badge safe">非危化运输品类</span>
        </div>
        <div class="cq-rhead-right">
          <div class="cq-ident">
            <span v-if="result.cas" class="id-item">CAS <b>{{ result.cas }}</b></span>
            <span v-if="props.formula" class="id-item">分子式 <b v-html="formulaHtml(props.formula)"></b></span>
            <span v-if="props.weight" class="id-item">分子量 <b>{{ props.weight }}</b></span>
          </div>
          <button class="cq-pdf" type="button" title="导出为 PDF（含中文与英文原文）" @click="exportPdf">
            <FileDown /> 导出 PDF
          </button>
        </div>
      </div>

      <!-- 未收录说明 -->
      <div v-if="!result.covered" class="cq-nocover">{{ result.reason }}</div>

      <!-- ===== 免责声明（醒目提示） ===== -->
      <div class="cq-disclaimer">
        <span class="cq-disc-tag">免责声明</span>
        <div class="cq-disc-body">
          <p><b>本页为「物性资料」（物性 / 危险性 / 运输 / 行情信息汇编），并非检测报告、分析报告、合规报告或法律文书，仅供参考，不构成任何采购、报价、合规、安全或法律依据。</b></p>
          <p>物性与法规信息由系统自动抓取并机器翻译自公开权威数据源，可能存在抓取不全、翻译偏差或更新滞后；英文原文以“原文”标注，如与中文译文有出入，以英文原文及来源机构发布的内容为准。实际业务请以最新法规原文、主管部门要求及供应商出具的 SDS/MSDS 为准。价格数据为平台自主采集的市场行情，实际成交受规格、包装、运输距离与账期影响，与本页数值可能存在差异。</p>
        </div>
      </div>

      <!-- ===== 一、基础简介 ===== -->
      <section v-if="result.covered" class="cq-sec">
        <div class="cq-sec-head">
          <span class="cq-sec-no">一</span>
          <h3>基础简介</h3>
          <span class="cq-sec-src">来源：PubChem（美国国立卫生研究院 NIH）</span>
        </div>
        <div class="cq-grid">
          <div class="cq-cell">
            <span class="k">中文名</span><b class="v">{{ result.name }}</b>
          </div>
          <div class="cq-cell">
            <span class="k">英文名</span><b class="v">{{ result.enName || '—' }}</b>
          </div>
          <div class="cq-cell">
            <span class="k">CAS 号</span><b class="v mono">{{ result.cas || '—' }}</b>
          </div>
          <div class="cq-cell">
            <span class="k">分子式</span><b class="v mono" v-html="props.formula ? formulaHtml(props.formula) : '—'"></b>
          </div>
          <div class="cq-cell">
            <span class="k">分子量</span>
            <b class="v mono">{{ props.weight ? props.weight + ' g/mol' : '—' }}</b>
          </div>
          <div v-if="basic.appearance" class="cq-cell">
            <span class="k">外观性状</span><b class="v">{{ basic.appearance }}</b>
          </div>
          <div v-if="basic.odor" class="cq-cell">
            <span class="k">气味</span><b class="v">{{ basic.odor }}</b>
          </div>
          <div v-if="basic.melting" class="cq-cell">
            <span class="k">熔点</span><b class="v">{{ basic.melting }}</b>
          </div>
          <div v-if="basic.boiling" class="cq-cell">
            <span class="k">沸点</span><b class="v">{{ basic.boiling }}</b>
          </div>
          <div v-if="basic.density" class="cq-cell">
            <span class="k">密度</span><b class="v">{{ basic.density }}</b>
          </div>
          <div v-if="basic.vaporPressure" class="cq-cell">
            <span class="k">蒸气压</span><b class="v">{{ basic.vaporPressure }}</b>
          </div>
          <div v-if="basic.solubility" class="cq-cell">
            <span class="k">溶解性</span><b class="v">{{ basic.solubility }}</b>
          </div>
        </div>

        <div v-if="basic.specs && basic.specs.length" class="cq-sub">
          <span class="cq-sub-lb">主流规格</span>
          <div class="cq-tags">
            <span v-for="s in basic.specs" :key="s" class="cq-tag">{{ s }}</span>
          </div>
          <span class="cq-sub-note">来源：平台行情（近 90 天实际报价中出现过的规格）</span>
        </div>

        <div v-if="props.iupac" class="cq-line">
          <span class="k">IUPAC 名称</span><b class="v mono sm">{{ props.iupac }}</b>
        </div>
        <div v-if="props.smiles" class="cq-line">
          <span class="k">SMILES</span><b class="v mono sm">{{ props.smiles }}</b>
        </div>
        <div v-if="props.inchi" class="cq-line">
          <span class="k">InChI</span><b class="v mono sm">{{ props.inchi }}</b>
        </div>

        <details v-if="hasRawDiff" class="cq-raw cq-raw-block">
          <summary>查看英文原文（PubChem 原始记录）</summary>
          <table class="cq-raw-table">
            <tbody>
              <tr v-for="(v, k) in basic.raw" :key="k" v-show="rawDiff(v, basic[k])">
                <td class="k">{{ RAW_LB[k] || k }}</td>
                <td>{{ annotateF(v) }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </section>

      <!-- ===== 二、是不是危化品 ===== -->
      <section v-if="result.covered" class="cq-sec" :class="{ 'sec-danger': isHaz }">
        <div class="cq-sec-head">
          <span class="cq-sec-no">二</span>
          <h3>是不是危化品</h3>
          <span class="cq-sec-src">来源：PubChem / HSDB / ECHA</span>
        </div>

        <div v-if="isHaz === false" class="cq-safe-note">
          <b>未查询到危险货物运输分类记录</b><br />
          {{ hazard.note }}
        </div>

        <div class="cq-grid">
          <div v-if="hazard.hazardClassCn" class="cq-cell hl">
            <span class="k">危险分类</span>
            <b class="v">{{ hazard.hazardClassCn }}</b>
            <span class="cq-cell-note">UN 危险类别 {{ hazard.hazardClass }}</span>
          </div>
          <div v-if="hazard.unClassificationCn" class="cq-cell hl">
            <span class="k">联合国运输分类</span>
            <b class="v sm">{{ hazard.unClassificationCn }}</b>
            <details v-if="hazard.unClassification" class="cq-raw">
              <summary>英文原文</summary>
              <div class="cq-raw-body mono">{{ hazard.unClassification }}</div>
            </details>
          </div>
          <div v-if="hazard.unNumber" class="cq-cell hl">
            <span class="k">UN 编号</span><b class="v mono">{{ hazard.unNumber }}</b>
          </div>
          <div v-if="hazard.packGroup" class="cq-cell hl">
            <span class="k">包装类别</span><b class="v">{{ hazard.packGroupCn || hazard.packGroup + ' 类' }}</b>
            <span class="cq-cell-note">Ⅰ 类最严 · Ⅲ 类最宽</span>
          </div>
          <div v-if="hazard.flashPoint" class="cq-cell">
            <span class="k">闪点</span><b class="v">{{ hazard.flashPoint }}</b>
          </div>
          <div v-if="hazard.autoignition" class="cq-cell">
            <span class="k">自燃温度</span><b class="v">{{ hazard.autoignition }}</b>
          </div>
          <div v-if="hazard.ecClassificationCn || hazard.ecClassification" class="cq-cell">
            <span class="k">EC 分类（欧盟）</span>
            <b class="v sm">{{ hazard.ecClassificationCn || hazard.ecClassification }}</b>
            <span v-if="hazard.ecClassificationCn" class="cq-cell-note mono">{{ hazard.ecClassification }}</span>
          </div>
        </div>

        <div v-if="hasGhs" class="cq-sub cq-ghs">
          <span class="cq-sub-lb">GHS 危险性说明</span>
          <div class="cq-ghs-head">
            <span v-if="hazard.ghs.signal" class="cq-signal" :class="signalClass">
              {{ hazard.ghs.signal }}
            </span>
            <span class="cq-sub-note">GHS = 联合国化学品分类和标签全球协调制度</span>
          </div>
          <table v-if="hazard.ghs.hStatements" class="cq-htable">
            <tbody>
              <tr v-for="h in hazard.ghs.hStatements" :key="h.code">
                <td class="code">{{ h.code }}</td>
                <td>
                  {{ h.desc }}
                  <span v-if="h.origin" class="cq-origin-inline" :title="h.origin">原文</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="cq-legal">
          <b>经营与运输资质提示</b>
          <p>
            本品种{{ isHaz ? '已列入危险货物运输分类' : '未查询到危险货物运输分类' }}。从事危险化学品的生产、经营、储存、运输，
            需依法取得相应资质许可，具体范围以现行法规及主管部门要求为准。
          </p>
          <p class="cq-legal-disclaimer">
            以上信息整理自公开权威数据源，仅供交易参考，不构成合规、安全或法律意见。
            实际业务请以最新法规原文、主管部门要求及供应商出具的 SDS/MSDS 为准。
          </p>
        </div>
      </section>

      <!-- ===== 三、运输要求 ===== -->
      <section v-if="result.covered && hasTransport" class="cq-sec">
        <div class="cq-sec-head">
          <span class="cq-sec-no">三</span>
          <h3>运输要求</h3>
          <span class="cq-sec-src">来源：PubChem / HSDB（美国国家医学图书馆）</span>
        </div>

        <div v-if="transport.dotGuide" class="cq-cell hl full">
          <span class="k">运输应急指南</span><b class="v">{{ transport.dotGuide }}</b>
        </div>

        <div v-if="transport.incompatible && transport.incompatible.length" class="cq-sub">
          <span class="cq-sub-lb">运输与储存禁忌（不相容物质）</span>
          <ul class="cq-list warn">
            <li v-for="(s, i) in transport.incompatible" :key="i">
              {{ txtOf(s) }}
              <span v-if="inlineOriginOf(s)" class="cq-origin-inline">原文：{{ inlineOriginOf(s) }}</span>
              <details v-if="foldOriginOf(s)" class="cq-raw">
                <summary>点击查看原文</summary>
                <p>{{ foldOriginOf(s) }}</p>
              </details>
            </li>
          </ul>
        </div>

        <div v-if="transport.regulations && transport.regulations.length" class="cq-sub">
          <span class="cq-sub-lb">运输法规要点</span>
          <ul class="cq-list">
            <li v-for="(s, i) in transport.regulations" :key="i">
              {{ txtOf(s) }}
              <span v-if="inlineOriginOf(s)" class="cq-origin-inline">原文：{{ inlineOriginOf(s) }}</span>
              <details v-if="foldOriginOf(s)" class="cq-raw">
                <summary>点击查看原文</summary>
                <p>{{ foldOriginOf(s) }}</p>
              </details>
            </li>
          </ul>
        </div>

        <div v-if="transport.storage" class="cq-sub">
          <span class="cq-sub-lb">储存条件</span>
          <p class="cq-para">{{ txtOf(transport.storage) }}</p>
          <span v-if="inlineOriginOf(transport.storage)" class="cq-origin-inline">原文：{{ inlineOriginOf(transport.storage) }}</span>
          <details v-if="foldOriginOf(transport.storage)" class="cq-raw">
            <summary>点击查看原文</summary>
            <div class="cq-raw-body">{{ foldOriginOf(transport.storage) }}</div>
          </details>
        </div>

        <div v-if="transport.stability" class="cq-sub">
          <span class="cq-sub-lb">稳定性与反应性</span>
          <p class="cq-para">{{ txtOf(transport.stability) }}</p>
          <span v-if="inlineOriginOf(transport.stability)" class="cq-origin-inline">原文：{{ inlineOriginOf(transport.stability) }}</span>
          <details v-if="foldOriginOf(transport.stability)" class="cq-raw">
            <summary>点击查看原文</summary>
            <div class="cq-raw-body">{{ foldOriginOf(transport.stability) }}</div>
          </details>
        </div>
      </section>

      <!-- ===== 四、贸易简要参考（个性化） ===== -->
      <section v-if="trade && trade.available" class="cq-sec sec-trade">
        <div class="cq-sec-head">
          <span class="cq-sec-no">四</span>
          <h3>贸易简要参考</h3>
          <span v-if="trade.date" class="cq-date-badge">数据时间 {{ trade.date }}</span>
          <span class="cq-sec-src">来源：ChemPrice 化工价格平台自主采集行情</span>
        </div>

        <div class="cq-trade-top">
          <div class="cq-price-box">
            <span class="k">最新交易日价格区间</span>
            <b class="v">
              <template v-if="trade.min !== null && trade.max !== null">
                {{ fmt(trade.min) }} – {{ fmt(trade.max) }} <i>元/吨</i>
              </template>
              <template v-else>—</template>
            </b>
            <span class="cq-cell-note">数据时间 {{ trade.date }} · 共 {{ trade.count }} 个报价点 · 更新于 {{ trade.date }}</span>
          </div>
          <div class="cq-trend-box">
            <span class="k">近 30 日走势<template v-if="trendRange"><i class="cq-k-sub">（{{ trendRange }}）</i></template></span>
            <div v-if="trade.trend && trade.trend.length" class="cq-spark">
              <span
                v-for="(p, i) in trade.trend"
                :key="i"
                class="bar"
                :style="{ height: barH(p.price) }"
                :title="p.date + '　' + fmt(p.price) + ' 元/吨'"
              ></span>
            </div>
            <span v-else class="cq-cell-note">暂无走势数据</span>
            <span v-if="trendDelta !== null" class="cq-delta" :class="trendDelta >= 0 ? 'up' : 'down'">
              {{ trendDelta >= 0 ? '↑' : '↓' }} {{ Math.abs(trendDelta) }}%
            </span>
          </div>
        </div>

        <div v-if="trade.personalized && trade.personalized.length" class="cq-sub cq-personal">
          <span class="cq-sub-lb">为你关注的价格</span>
          <table class="cq-ptable">
            <thead>
              <tr><th>地区</th><th>规格</th><th class="num">价格（元/吨）</th></tr>
            </thead>
            <tbody>
              <tr v-for="(q, i) in trade.personalized" :key="i">
                <td>{{ q.market || q.region || '—' }}</td>
                <td>{{ q.spec || '通用' }}</td>
                <td class="num strong">{{ fmt(q.middle) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="cq-sub">
          <span class="cq-sub-lb">各地区 / 各规格报价<template v-if="trade.date"><i class="cq-lb-date">数据时间 {{ trade.date }}</i></template></span>
          <div class="cq-tbl-wrap">
            <table class="cq-ptable">
              <thead>
                <tr>
                  <th>地区</th><th>规格</th>
                  <th class="num">低端</th><th class="num">高端</th><th class="num">主流价</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(q, i) in trade.quotes" :key="i">
                  <td>{{ q.market || q.region || '—' }}</td>
                  <td>{{ q.spec || '通用' }}</td>
                  <td class="num">{{ q.low ? fmt(q.low) : '—' }}</td>
                  <td class="num">{{ q.high ? fmt(q.high) : '—' }}</td>
                  <td class="num strong">{{ q.middle ? fmt(q.middle) : '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <span class="cq-sub-note">
            价格为主流成交参考价（元/吨），通常为含税自提口径。实际成交受规格、包装、运输距离与账期影响。
          </span>
        </div>
      </section>

      <div v-else-if="trade && trade.available === false" class="cq-nocover">
        平台暂无该品种的现货报价，可前往「数据查询」查看同类品种行情。
      </div>

      <!-- 物性不可用提示 -->
      <div v-if="result.propsErr" class="cq-warn">{{ result.propsErr }}</div>

      <!-- 权威外链 -->
      <div class="cq-links">
        <div class="cq-lhead">
          <h3>进一步检索（合成路线 / 工艺 / 法规）</h3>
          <p>
            点击跳转至权威数据库。标注「需授权」的 Reaxys / SciFinder 为付费版权库，
            本平台仅提供带名跳转，不抓取、不转载其内容。
          </p>
        </div>
        <div class="cq-link-grid">
          <a v-for="l in result.links" :key="l.site" class="cq-link"
             :href="l.url" target="_blank" rel="noopener">
            <span class="cq-link-site">{{ l.site }}</span>
            <span class="cq-link-desc">{{ l.desc }}</span>
            <span class="cq-link-arrow">↗</span>
          </a>
        </div>
      </div>
    </div>

    <!-- ===== 数据来源总表 ===== -->
    <section class="cq-sources">
      <h3>本页数据来源说明</h3>
      <p class="cq-sources-note">
        本平台自身价格数据为自主采集的化工市场行情；本页物性、法规与运输信息来自下列<b>公开权威机构</b>，
        版权归各机构所有，本平台仅提供检索聚合与来源标注，不转载受版权保护的原始内容。
        所有信息仅供交易参考，不构成合规、安全或法律意见。
      </p>
      <div class="cq-src-table">
        <table>
          <thead>
            <tr><th>数据源</th><th>所属机构</th><th>覆盖内容</th><th>访问方式</th></tr>
          </thead>
          <tbody>
            <tr v-for="s in sources" :key="s.name">
              <td class="nm">
                <a :href="s.url" target="_blank" rel="noopener">{{ s.name }} ↗</a>
              </td>
              <td>{{ s.org }}</td>
              <td>{{ s.scope }}</td>
              <td>
                <span class="pill" :class="s.access === 'free' ? 'free' : 'paid'">
                  {{ s.access === 'free' ? '免费公开' : '需授权' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>

  <!-- ===== 打印 / 导出 PDF 专用容器（屏幕隐藏，仅打印可见） =====
       ⚠️ 必须 Teleport 到 body 直接子级：留在组件内时，打印时父级外壳
       （含超长页面 + 数据来源表）仍占文档流，Chromium 分页会在第一页截断，
       导致「三、运输要求 / 四、贸易简要参考」丢失（实测第 2 页全空）。 -->
  <Teleport to="body">
  <div v-if="result" class="cq-print" aria-hidden="true">
    <!-- ⭐ PDF 水印：position:fixed 在 Chrome 打印时逐页重复 -->
    <div class="p-wm" aria-hidden="true">
      <span v-for="i in wmTilesPrint" :key="i" class="p-wm-t">{{ WM_TEXT }}</span>
    </div>
    <div class="p-head">
      <h1>ChemPrice 化工价格平台 · 物性资料</h1>
      <div class="p-doctype">文档性质：物性资料（物性 / 危险性 / 运输 / 行情信息汇编）　｜　非检测报告、非分析报告、非法律文书</div>
      <div class="p-title">
        <span class="p-cn">{{ result.name }}</span>
        <span v-if="result.enName" class="p-en">{{ result.enName }}</span>
      </div>
      <div class="p-meta">
        <span v-if="result.cas">CAS {{ result.cas }}</span>
        <span v-if="props.formula">分子式 {{ props.formula }}</span>
        <span v-if="props.weight">分子量 {{ props.weight }}</span>
        <span class="p-date">导出时间 {{ pdfTime }}</span>
      </div>
    </div>

    <div class="p-disclaimer">
      <b>免责声明：</b>本文档为<b>物性资料</b>（物性 / 危险性 / 运输 / 行情信息汇编），
      <b>并非检测报告、分析报告、合规报告或法律文书</b>，<b>仅供参考，不构成任何采购、报价、合规、安全或法律依据</b>。
      物性与法规信息由系统自动抓取并机器翻译自公开权威数据源，可能存在抓取不全、翻译偏差或更新滞后；
      英文原文以“〔原文〕”标注，如与中文译文有出入，以英文原文及来源机构发布的内容为准。
      实际业务请以最新法规原文、主管部门要求及供应商出具的 SDS/MSDS 为准。
      价格数据为平台自主采集的市场行情，实际成交受规格、包装、运输距离与账期影响。
    </div>

    <!-- 一、基础简介 -->
    <section v-if="result.covered" class="p-sec">
      <h2>一、基础简介</h2>
      <table class="p-kv">
        <tbody>
          <tr><td class="k">中文名</td><td>{{ result.name }}</td></tr>
          <tr v-if="result.enName"><td class="k">英文名</td><td>{{ result.enName }}</td></tr>
          <tr v-if="result.cas"><td class="k">CAS 号</td><td>{{ result.cas }}</td></tr>
          <tr v-if="props.formula"><td class="k">分子式</td><td>{{ props.formula }}</td></tr>
          <tr v-if="props.weight"><td class="k">分子量</td><td>{{ props.weight }} g/mol</td></tr>
          <tr v-for="f in pdfBasic" :key="f.k">
            <td class="k">{{ f.k }}</td>
            <td>
              <div>{{ f.zh }}</div>
              <div v-if="f.raw && f.raw !== f.zh" class="p-origin">〔原文〕{{ annotateF(f.raw) }}</div>
            </td>
          </tr>
          <tr v-if="basic.specs && basic.specs.length">
            <td class="k">主流规格</td><td>{{ basic.specs.join('、') }}</td>
          </tr>
          <tr v-if="props.iupac"><td class="k">IUPAC 名称</td><td class="mono">{{ props.iupac }}</td></tr>
          <tr v-if="props.smiles"><td class="k">SMILES</td><td class="mono">{{ props.smiles }}</td></tr>
          <tr v-if="props.inchi"><td class="k">InChI</td><td class="mono">{{ props.inchi }}</td></tr>
        </tbody>
      </table>
      <p class="p-src">数据来源：PubChem（美国国立卫生研究院 NIH）</p>
    </section>

    <!-- 二、是不是危化品 -->
    <section v-if="result.covered" class="p-sec">
      <h2>二、是不是危化品</h2>
      <table class="p-kv">
        <tbody>
          <tr v-if="isHaz === false">
            <td class="k">结论</td>
            <td>未查询到危险货物运输分类记录，通常不属于危险化学品运输范畴。</td>
          </tr>
          <tr v-if="hazard.hazardClassCn">
            <td class="k">危险分类</td>
            <td>UN 危险类别 {{ hazard.hazardClass }} · {{ hazard.hazardClassCn }}</td>
          </tr>
          <tr v-if="hazard.unClassificationCn">
            <td class="k">联合国运输分类</td>
            <td>
              <div>{{ hazard.unClassificationCn }}</div>
              <div v-if="hazard.unClassification" class="p-origin">〔原文〕{{ hazard.unClassification }}</div>
            </td>
          </tr>
          <tr v-if="hazard.unNumber"><td class="k">UN 编号</td><td>{{ hazard.unNumber }}</td></tr>
          <tr v-if="hazard.packGroup">
            <td class="k">包装类别</td><td>{{ hazard.packGroupCn || hazard.packGroup + ' 类' }}</td>
          </tr>
          <tr v-if="hazard.flashPoint"><td class="k">闪点</td><td>{{ hazard.flashPoint }}</td></tr>
          <tr v-if="hazard.autoignition"><td class="k">自燃温度</td><td>{{ hazard.autoignition }}</td></tr>
          <tr v-if="hazard.ecClassificationCn || hazard.ecClassification">
            <td class="k">EC 分类（欧盟）</td>
            <td>
              <div>{{ hazard.ecClassificationCn || hazard.ecClassification }}</div>
              <div v-if="hazard.ecClassificationCn" class="p-origin">〔原文〕{{ hazard.ecClassification }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <template v-if="hasGhs">
        <h3 class="p-h3">GHS 危险性说明<span v-if="hazard.ghs.signal">（信号词：{{ hazard.ghs.signal }}）</span></h3>
        <table class="p-h">
          <tbody>
            <tr v-for="h in hazard.ghs.hStatements" :key="h.code">
              <td class="code">{{ h.code }}</td>
              <td>
                <div>{{ h.desc }}</div>
                <div v-if="h.origin" class="p-origin">〔原文〕{{ h.origin }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </template>
      <p class="p-src">数据来源：PubChem / HSDB / ECHA</p>
    </section>

    <!-- 三、运输要求 -->
    <section v-if="result.covered && hasTransport" class="p-sec">
      <h2>三、运输要求</h2>
      <table class="p-kv">
        <tbody>
          <tr v-if="transport.dotGuide"><td class="k">运输应急指南</td><td>{{ transport.dotGuide }}</td></tr>
        </tbody>
      </table>

      <template v-if="transport.incompatible && transport.incompatible.length">
        <h3 class="p-h3">运输与储存禁忌（不相容物质）</h3>
        <ul class="p-list">
          <li v-for="(s, i) in transport.incompatible" :key="i">
            <div>{{ txtOf(s) }}</div>
            <div v-if="originOf(s)" class="p-origin">〔原文〕{{ originOf(s) }}</div>
          </li>
        </ul>
      </template>

      <template v-if="transport.regulations && transport.regulations.length">
        <h3 class="p-h3">运输法规要点</h3>
        <ul class="p-list">
          <li v-for="(s, i) in transport.regulations" :key="i">
            <div>{{ txtOf(s) }}</div>
            <div v-if="originOf(s)" class="p-origin">〔原文〕{{ originOf(s) }}</div>
          </li>
        </ul>
      </template>

      <template v-if="transport.storage">
        <h3 class="p-h3">储存条件</h3>
        <p class="p-para">{{ txtOf(transport.storage) }}</p>
        <p v-if="originOf(transport.storage)" class="p-origin">〔原文〕{{ originOf(transport.storage) }}</p>
      </template>

      <template v-if="transport.stability">
        <h3 class="p-h3">稳定性与反应性</h3>
        <p class="p-para">{{ txtOf(transport.stability) }}</p>
        <p v-if="originOf(transport.stability)" class="p-origin">〔原文〕{{ originOf(transport.stability) }}</p>
      </template>
      <p class="p-src">数据来源：PubChem / HSDB（美国国家医学图书馆）</p>
    </section>

    <!-- 四、贸易简要参考 -->
    <section v-if="trade && trade.available" class="p-sec">
      <h2>四、贸易简要参考<template v-if="trade.date"><span class="p-sec-date">数据时间 {{ trade.date }}</span></template></h2>
      <table class="p-kv">
        <tbody>
          <tr>
            <td class="k">最新交易日价格区间</td>
            <td>
              <template v-if="trade.min !== null && trade.max !== null">
                {{ fmt(trade.min) }} – {{ fmt(trade.max) }} 元/吨
              </template>
              <template v-else>—</template>
              （数据时间 {{ trade.date }} · 共 {{ trade.count }} 个报价点）
            </td>
          </tr>
        </tbody>
      </table>
      <table class="p-price">
        <thead>
          <tr><th>地区</th><th>规格</th><th class="num">低端</th><th class="num">高端</th><th class="num">主流价（元/吨）</th></tr>
        </thead>
        <tbody>
          <tr v-for="(q, i) in trade.quotes" :key="i">
            <td>{{ q.market || q.region || '—' }}</td>
            <td>{{ q.spec || '通用' }}</td>
            <td class="num">{{ q.low ? fmt(q.low) : '—' }}</td>
            <td class="num">{{ q.high ? fmt(q.high) : '—' }}</td>
            <td class="num strong">{{ q.middle ? fmt(q.middle) : '—' }}</td>
          </tr>
        </tbody>
      </table>
      <p class="p-src">数据来源：ChemPrice 化工价格平台自主采集行情。价格为主流成交参考价（元/吨），通常为含税自提口径。</p>
    </section>

    <section v-else-if="trade && trade.available === false" class="p-sec p-keep">
      <h2>四、贸易简要参考</h2>
      <p class="p-para">平台暂无该品种的现货报价，可前往「数据查询」查看同类品种行情。</p>
    </section>

    <!-- 权威外链 -->
    <section v-if="result.covered && result.links && result.links.length" class="p-sec p-keep">
      <h2>进一步检索（合成路线 / 工艺 / 法规）</h2>
      <table class="p-links">
        <tbody>
          <tr v-for="l in result.links" :key="l.site">
            <td class="k">{{ l.site }}</td>
            <td>
              <div>{{ l.desc }}</div>
              <div class="p-url">{{ l.url }}</div>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="p-src">标注「需授权」的 Reaxys / SciFinder 为付费版权库，本平台仅提供带名跳转，不抓取、不转载其内容。</p>
    </section>

    <div class="p-foot">
      <p>
        本资料由 ChemPrice 化工价格平台自动生成，数据来源于 PubChem（NIH）/ HSDB（NLM）/ ECHA 等公开权威机构，
        版权归各机构所有。平台仅提供检索聚合与来源标注，不转载受版权保护的原始内容。
      </p>
      <p>所有信息仅供交易参考，不构成合规、安全或法律意见。请以最新法规原文、主管部门要求及供应商出具的 SDS/MSDS 为准。</p>
    </div>
  </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search, FileDown } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { getChemSources, chemLookup, getChemCommodities } from '../api'

const kw = ref('')
const loading = ref(false)
const result = ref(null)
const sources = ref([])
const allCommodities = ref([])

const quickList = ['叔丁醇', '甲醇', '苯乙烯', 'PTA', '双酚A', '己内酰胺', '纯苯', '丙烯酸']

const coveredCount = computed(() => allCommodities.value.filter((c) => c.covered).length)

const props = computed(() => result.value?.props || {})
const basic = computed(() => result.value?.basic || {})
const hazard = computed(() => result.value?.hazard || {})
const transport = computed(() => result.value?.transport || {})
const trade = computed(() => result.value?.trade || {})

// 「英文原文」折叠区字段名中文化
const RAW_LB = {
  appearance: '外观性状',
  odor: '气味',
  melting: '熔点',
  boiling: '沸点',
  density: '密度',
  vaporPressure: '蒸气压',
  solubility: '溶解性',
}

// 只有「中文与原文不同」的字段才值得折叠展示，避免重复刷屏
function rawDiff(raw, zh) {
  if (!raw) return false
  if (!zh) return true
  return String(raw).trim() !== String(zh).trim()
}
const hasRawDiff = computed(() => {
  const r = basic.value.raw
  if (!r) return false
  return Object.keys(r).some((k) => rawDiff(r[k], basic.value[k]))
})

// storage / stability / incompatible / regulations 的字段可能是「字符串」或「{zh, origin}」
function txtOf(v) {
  if (v == null) return ''
  if (typeof v === 'string') return v
  return v.zh || v.origin || ''
}
// 需要折叠展示原文的条件：origin 存在，且与当前展示文本不同
// （翻译不合格时 txtOf 返回 origin，两者相同 → 不重复折叠）
function originOf(v) {
  if (v == null || typeof v === 'string') return ''
  const t = txtOf(v)
  return v.origin && v.origin.trim() !== t.trim() ? v.origin : ''
}
// ⭐ 原文展示策略（2026-09-23）：短句（≤ 80 字符）直接在译文下方用小字标注，
//    长句收进「点击查看原文」折叠区 —— 避免长英文段落占满版面。
const INLINE_RAW_MAX = 80
function inlineOriginOf(v) {
  const o = originOf(v)
  return o && o.length <= INLINE_RAW_MAX ? o : ''
}
function foldOriginOf(v) {
  const o = originOf(v)
  return o && o.length > INLINE_RAW_MAX ? o : ''
}

// ⭐ 原始英文记录里的华氏温度补一个摄氏注释：原文保持不动（它是「原始记录」），
//    但在旁边追加 "（≈ 82 ℃）"，这样即使折叠区也不至于让人对不上温度。
function annotateF(v) {
  if (v == null) return ''
  const s = String(v)
  return s.replace(/(-?\d+(?:\.\d+)?)\s*°?\s*F\b/g, (m, num) => {
    const c = Math.round(((parseFloat(num) - 32) * 5) / 9)
    return m + '（≈ ' + c + ' ℃）'
  })
}

// ⭐ 分子式下标化：PubChem 返回的是 "C4H10O" 这种平铺写法，直接显示易被误读成
//    「C4H100」（数字 100）。转成 C₄H₁₀O 才是化学界通行的呈现方式。
//    规则：化学式里紧跟元素符号/右括号的数字 → 下标；电荷（结尾 +/-）不动。
function formulaHtml(f) {
  if (!f || typeof f !== 'string') return ''
  const esc = f.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc.replace(/(\d+)/g, '<sub>$1</sub>')
}

const isHaz = computed(() => {
  const h = hazard.value
  if (h.isHazardous === undefined) return null
  return h.isHazardous
})

const hasGhs = computed(() => {
  const g = hazard.value.ghs
  return !!(g && (g.signal || (g.hStatements && g.hStatements.length)))
})

const hasTransport = computed(() => {
  const t = transport.value
  return !!(t.dotGuide || (t.incompatible && t.incompatible.length)
    || (t.regulations && t.regulations.length) || t.storage || t.stability)
})

const signalClass = computed(() => {
  const s = hazard.value?.ghs?.signal || ''
  if (s.includes('Danger')) return 'sig-danger'
  if (s.includes('Warning')) return 'sig-warning'
  return ''
})

// 走势数据的起止日期（用于在「近 30 日走势」后标注真实数据时间范围）
const trendRange = computed(() => {
  const tr = trade.value?.trend
  if (!tr || !tr.length) return ''
  const f = tr[0]?.date
  const l = tr[tr.length - 1]?.date
  if (!f || !l) return ''
  return f === l ? f : `${f} ~ ${l}`
})

const trendDelta = computed(() => {
  const tr = trade.value?.trend
  if (!tr || tr.length < 2) return null
  const first = tr[0].price
  const last = tr[tr.length - 1].price
  if (!first || !last) return null
  return Math.round(((last - first) / first) * 1000) / 10
})

function fmt(n) {
  if (n === null || n === undefined) return '—'
  return Number(n).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}

function barH(p) {
  const tr = trade.value?.trend || []
  const vals = tr.map((x) => x.price).filter((x) => x)
  if (!vals.length || !p) return '2px'
  const max = Math.max(...vals)
  const min = Math.min(...vals)
  const range = max - min || 1
  const pct = 20 + ((p - min) / range) * 80
  return pct + '%'
}

async function doSearch() {
  const name = kw.value.trim()
  if (!name) {
    ElMessage.warning('请输入品种名称')
    return
  }
  let target = name
  const hit = allCommodities.value.find((c) => c.name === name)
  if (hit) target = hit.name

  loading.value = true
  try {
    const r = await chemLookup({ name: target })
    if (r.code === 200) {
      result.value = r.data
    } else {
      ElMessage.error(r.message || '查询失败')
    }
  } catch (e) {
    ElMessage.error('查询失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function pick(n) {
  kw.value = n
  doSearch()
}

// ⭐ 导出 PDF（2026-09-23）：走浏览器原生打印 → 「另存为 PDF」。
//    为什么不用 jsPDF：中文需内嵌几十 MB 字体且常乱码；原生打印中文完美、零依赖。
//    打印内容由 .cq-print 容器承载（屏幕 display:none，打印时 display:block），
//    中文与英文原文并列展示（原文以「〔原文〕」标注）。
// ⭐ 水印文案（网页 + PDF 共用）
const WM_TEXT = '物性资料仅供参考'
// 网页水印瓦片数（flex-wrap 铺满整页，多给一些覆盖长页面）
const wmTiles = 240
// PDF 水印瓦片数（覆盖单个 A4 页面即可，fixed 会逐页重复）
const wmTilesPrint = 60

const pdfTime = ref('')
const PDF_BASIC_FIELDS = [
  ['appearance', '外观性状'],
  ['odor', '气味'],
  ['melting', '熔点'],
  ['boiling', '沸点'],
  ['density', '密度'],
  ['vaporPressure', '蒸气压'],
  ['solubility', '溶解性'],
]
// 基础简介里「中文 + 英文原文」成对收集，PDF 里逐条并列
const pdfBasic = computed(() => {
  const b = basic.value || {}
  const r = b.raw || {}
  return PDF_BASIC_FIELDS.filter(([k]) => b[k]).map(([k, label]) => ({
    k: label,
    zh: txtOf(b[k]),
    raw: r[k] || '',
  }))
})

// ⭐ PDF 文件名 =「XX物性资料」（浏览器「另存为 PDF」默认取 document.title）
const pdfFileName = computed(() => {
  const n = result.value?.name || '物性'
  // 去掉文件名非法字符（如 PC/ABS 的斜杠）
  return String(n).replace(/[\\/:*?"<>|]/g, '-') + '物性资料'
})

async function exportPdf() {
  if (!result.value) return
  const d = new Date()
  const p2 = (n) => String(n).padStart(2, '0')
  pdfTime.value = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())}`
  // ⭐ 浏览器「另存为 PDF」的默认文件名取 document.title →
  //    打印前临时改成「XX物性资料」，打印后立刻还原（含用户取消打印的情况）。
  const savedTitle = document.title
  document.title = pdfFileName.value
  let restored = false
  const restore = () => {
    if (restored) return
    restored = true
    document.title = savedTitle
    window.removeEventListener('afterprint', restore)
  }
  window.addEventListener('afterprint', restore)
  // 等 Vue 把打印容器的时间戳渲染完再唤起打印对话框
  await new Promise((r) => setTimeout(r, 120))
  window.print()
  // 兜底：个别浏览器 afterprint 不触发 / 打印被取消，延迟还原
  setTimeout(restore, 1200)
}

onMounted(async () => {
  try {
    const [s, c] = await Promise.all([getChemSources(), getChemCommodities({})])
    if (s.code === 200) sources.value = s.data || []
    if (c.code === 200) allCommodities.value = c.data || []
  } catch (e) {
    console.error('物性查询初始化失败:', e)
  }
})
</script>

<style scoped>
.cq {
  padding: 20px 24px 40px;
  max-width: 1180px;
  margin: 0 auto;
  position: relative;          /* 供整页水印定位 */
}

/* ================= 网页水印（物性资料仅供参考） ================= */
/* z-index 高于内容 + pointer-events:none → 铺在最上层但不挡点击 */
.cq-wm {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  justify-content: flex-start;
  gap: 64px 96px;
  padding: 34px 0;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
}
.cq-wm-t {
  flex: 0 0 auto;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #15414e;
  opacity: 0.075;
  transform: rotate(-30deg);
  white-space: nowrap;
}
/* ---- hero ---- */
.cq-hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 22px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--rl);
  margin-bottom: 16px;
}
.cq-hero-txt h1 {
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 8px;
  color: var(--ink);
}
.cq-hero-txt p {
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink3);
  margin: 0;
  max-width: 720px;
}
.cq-hero-txt p b { color: var(--ink2); }
.cq-hero-stat { display: flex; gap: 18px; flex-shrink: 0; }
.cq-hero-stat .s { text-align: center; }
.cq-hero-stat .s b {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: var(--blue);
  font-family: 'JetBrains Mono', monospace;
}
.cq-hero-stat .s span { font-size: 10.5px; color: var(--ink4); }
/* ---- 搜索 ---- */
.cq-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.cq-search {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  height: 40px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
  color: var(--ink4);
}
.cq-search input {
  flex: 1;
  border: 0;
  outline: 0;
  font-size: 13px;
  color: var(--ink);
  background: transparent;
}
.cq-clear {
  border: 0;
  background: transparent;
  font-size: 18px;
  color: var(--ink4);
  cursor: pointer;
  line-height: 1;
}
.cq-go {
  height: 40px;
  padding: 0 26px;
  border: 0;
  border-radius: var(--r);
  background: var(--blue);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.cq-go:disabled { opacity: 0.6; cursor: default; }
/* ---- 快捷 ---- */
.cq-quick {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 18px;
}
.cq-quick-lb { font-size: 11.5px; color: var(--ink4); }
.cq-chip {
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: #fff;
  font-size: 11.5px;
  color: var(--ink3);
  cursor: pointer;
}
.cq-chip:hover { border-color: var(--blue); color: var(--blue); }
/* ---- 结果 ---- */
.cq-result {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--rl);
  padding: 18px 20px;
  margin-bottom: 20px;
}
.cq-rhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 14px;
  border-bottom: 2px solid var(--border);
}
.cq-rname { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cq-rname h2 {
  font-size: 21px;
  font-weight: 700;
  margin: 0;
  color: var(--ink);
}
.cq-badge {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 600;
}
.cq-badge.ok { background: rgba(34, 197, 94, 0.12); color: #15803d; }
.cq-badge.no { background: rgba(113, 113, 122, 0.12); color: var(--ink3); }
.cq-badge.poly { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.cq-badge.danger { background: rgba(239, 68, 68, 0.13); color: #b91c1c; }
.cq-badge.safe { background: rgba(34, 197, 94, 0.1); color: #15803d; }
.cq-ident { display: flex; gap: 16px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink4); }
.cq-ident .id-item b {
  font-family: 'JetBrains Mono', monospace;
  color: var(--ink2);
  font-weight: 600;
  margin-left: 3px;
}
.cq-nocover {
  padding: 12px 14px;
  background: rgba(245, 158, 11, 0.08);
  border-left: 3px solid var(--amber);
  border-radius: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--ink2);
  margin-top: 14px;
}
/* ---- 免责声明 ---- */
.cq-disclaimer {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-top: 14px;
  padding: 12px 14px;
  background: rgba(226, 75, 74, 0.05);
  border: 1px solid rgba(226, 75, 74, 0.22);
  border-left: 3px solid #e24b4a;
  border-radius: 4px;
}
.cq-disc-tag {
  flex: none;
  padding: 2px 7px;
  font-size: 11px;
  font-weight: 500;
  color: #fff;
  background: #e24b4a;
  border-radius: 3px;
  letter-spacing: 0.5px;
}
.cq-disc-body { font-size: 12px; line-height: 1.7; color: var(--ink2); }
.cq-disc-body p { margin: 0; }
.cq-disc-body p + p { margin-top: 5px; color: var(--ink3); }
/* ---- 段落 ---- */
.cq-sec {
  padding: 18px 0 6px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 8px;
}
.cq-sec:last-of-type { border-bottom: 0; }
.cq-sec-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.cq-sec-no {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: var(--ink5);
  border: 1px solid var(--border);
  color: var(--ink2);
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.cq-sec-head h3 {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--ink);
}
.cq-sec-src {
  font-size: 10.5px;
  color: var(--ink4);
  padding: 2px 8px;
  background: var(--ink5);
  border-radius: 999px;
}

/* ---- 「四、贸易简要参考」数据时间徽标（用户反馈：要显式标注数据时间）---- */
.cq-date-badge {
  font-size: 11px;
  font-weight: 600;
  color: #0f6b57;
  padding: 2px 9px;
  background: #e6f5f0;
  border: 1px solid #b7e2d5;
  border-radius: 999px;
  white-space: nowrap;
}
.cq-k-sub {
  font-style: normal;
  font-weight: 400;
  font-size: 10.5px;
  color: var(--ink4);
}
.cq-lb-date {
  font-style: normal;
  font-weight: 400;
  font-size: 10.5px;
  color: var(--ink4);
  margin-left: 8px;
}
.cq-sec.sec-danger .cq-sec-no {
  background: rgba(239, 68, 68, 0.1);
  border-color: rgba(239, 68, 68, 0.3);
  color: #b91c1c;
}
.cq-sec.sec-trade .cq-sec-no {
  background: rgba(20, 184, 166, 0.12);
  border-color: rgba(20, 184, 166, 0.3);
  color: #0f766e;
}
/* ---- 网格 ---- */
.cq-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(215px, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}
.cq-cell {
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
}
.cq-cell.full { grid-column: 1 / -1; margin-bottom: 12px; }
.cq-cell.hl { border-color: rgba(239, 68, 68, 0.25); background: rgba(239, 68, 68, 0.03); }
.cq-cell .k {
  display: block;
  font-size: 10.5px;
  color: var(--ink4);
  margin-bottom: 4px;
}
.cq-cell .v {
  font-size: 13.5px;
  color: var(--ink);
  word-break: break-word;
  line-height: 1.6;
}
.cq-cell .v.mono { font-family: 'JetBrains Mono', monospace; }
.cq-cell .v.sm { font-size: 11.5px; }
.cq-cell-note {
  display: block;
  font-size: 10px;
  color: var(--ink4);
  margin-top: 4px;
}
/* ---- 子块 ---- */
.cq-sub { margin-bottom: 14px; }
.cq-sub-lb {
  display: block;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink2);
  margin-bottom: 7px;
}
.cq-sub-note {
  display: block;
  font-size: 10.5px;
  color: var(--ink4);
  margin-top: 7px;
  line-height: 1.6;
}
.cq-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.cq-tag {
  padding: 3px 10px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 6px;
  font-size: 11.5px;
  color: var(--ink2);
  font-family: 'JetBrains Mono', monospace;
}
.cq-line {
  display: flex;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px solid var(--border);
  font-size: 11.5px;
}
.cq-line .k { color: var(--ink4); flex-shrink: 0; width: 92px; }
.cq-line .v { color: var(--ink2); word-break: break-all; }
.cq-line .v.mono { font-family: 'JetBrains Mono', monospace; }
.cq-line .v.sm { font-size: 11px; line-height: 1.65; }
/* ---- 安全提示 ---- */
.cq-safe-note {
  padding: 11px 14px;
  background: rgba(34, 197, 94, 0.06);
  border-left: 3px solid #22c55e;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink2);
  margin-bottom: 14px;
}
/* ---- GHS ---- */
.cq-ghs-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 9px;
  flex-wrap: wrap;
}
.cq-signal {
  padding: 3px 11px;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 700;
}
.cq-signal.sig-danger { background: rgba(239, 68, 68, 0.12); color: #b91c1c; }
.cq-signal.sig-warning { background: rgba(245, 158, 11, 0.13); color: #b45309; }
.cq-htable {
  width: 100%;
  border-collapse: collapse;
  font-size: 11.5px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
}
.cq-htable td {
  padding: 7px 11px;
  border-bottom: 1px solid var(--border);
  color: var(--ink2);
  line-height: 1.6;
}
.cq-htable tr:last-child td { border-bottom: 0; }
.cq-htable td.code {
  width: 66px;
  font-family: 'JetBrains Mono', monospace;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
}
/* ---- 英文原文：短句内联小字 ---- */
.cq-origin-inline {
  display: block;
  margin-top: 3px;
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--ink4);
  font-style: italic;
  word-break: break-word;
}
/* ---- 英文原文折叠 ---- */
.cq-raw { margin-top: 5px; }
.cq-raw > summary {
  cursor: pointer;
  font-size: 10.5px;
  color: var(--ink4);
  list-style: none;
  user-select: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.cq-raw > summary::-webkit-details-marker { display: none; }
.cq-raw > summary::before { content: '▸'; font-size: 9px; }
.cq-raw[open] > summary::before { content: '▾'; }
.cq-raw > summary:hover { color: var(--ink2); }
.cq-raw > p {
  margin: 5px 0 0;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.65;
  color: var(--ink3);
  background: var(--bg2, rgba(0, 0, 0, 0.025));
  border-left: 2px solid var(--border);
  border-radius: 0 var(--r) var(--r) 0;
}
.cq-raw-block > summary { display: inline-flex; margin-top: 10px; }
.cq-raw-table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 11px; }
.cq-raw-table td {
  padding: 6px 9px;
  border-bottom: 1px solid var(--border);
  color: var(--ink3);
  line-height: 1.6;
  vertical-align: top;
}
.cq-raw-table td.k { width: 82px; color: var(--ink4); white-space: nowrap; }
.cq-raw-table tr:last-child td { border-bottom: 0; }
.cq-origin-inline {
  display: inline-block;
  margin-left: 6px;
  padding: 0 5px;
  font-size: 9.5px;
  color: var(--ink4);
  border: 1px solid var(--border);
  border-radius: 3px;
  cursor: help;
}

/* ---- 资质 ---- */
.cq-legal {
  padding: 13px 15px;
  background: rgba(245, 158, 11, 0.07);
  border: 1px solid rgba(245, 158, 11, 0.25);
  border-radius: var(--r);
  margin-top: 4px;
}
.cq-legal > b {
  display: block;
  font-size: 12px;
  color: #92400e;
  margin-bottom: 7px;
}
.cq-legal p {
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink2);
  margin: 0 0 7px;
}
.cq-legal p:last-child { margin-bottom: 0; }
.cq-legal-disclaimer {
  padding-top: 8px;
  border-top: 1px dashed rgba(245, 158, 11, 0.35);
  color: var(--ink3) !important;
  font-size: 11px !important;
}
/* ---- 列表 ---- */
.cq-list {
  margin: 0;
  padding-left: 18px;
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--ink2);
}
.cq-list li { margin-bottom: 5px; }
.cq-list.warn li::marker { color: #dc2626; }
.cq-para {
  font-size: 11.5px;
  line-height: 1.8;
  color: var(--ink2);
  margin: 0;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
}
/* ---- 贸易 ---- */
.cq-trade-top {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 14px;
}
.cq-price-box, .cq-trend-box {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
}
.cq-price-box .k, .cq-trend-box .k {
  display: block;
  font-size: 10.5px;
  color: var(--ink4);
  margin-bottom: 8px;
}
.cq-price-box .v {
  display: block;
  font-size: 21px;
  font-weight: 700;
  color: #0f766e;
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.3;
}
.cq-price-box .v i {
  font-style: normal;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink4);
}
.cq-spark {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 44px;
  margin-bottom: 6px;
}
.cq-spark .bar {
  flex: 1;
  min-width: 2px;
  background: linear-gradient(180deg, #5eead4, #14b8a6);
  border-radius: 2px 2px 0 0;
}
.cq-delta {
  font-size: 11.5px;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
}
.cq-delta.up { color: #dc2626; }
.cq-delta.down { color: #16a34a; }
.cq-personal {
  padding: 12px 14px;
  background: rgba(20, 184, 166, 0.06);
  border: 1px solid rgba(20, 184, 166, 0.25);
  border-radius: var(--r);
}
.cq-tbl-wrap { overflow-x: auto; }
.cq-ptable {
  width: 100%;
  border-collapse: collapse;
  font-size: 11.5px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
  min-width: 420px;
}
.cq-ptable th {
  text-align: left;
  padding: 8px 11px;
  background: var(--ink5);
  color: var(--ink3);
  font-weight: 600;
  font-size: 10.5px;
  white-space: nowrap;
}
.cq-ptable td {
  padding: 8px 11px;
  border-bottom: 1px solid var(--border);
  color: var(--ink2);
  font-family: 'JetBrains Mono', monospace;
}
.cq-ptable tr:last-child td { border-bottom: 0; }
.cq-ptable th.num, .cq-ptable td.num { text-align: right; }
.cq-ptable td.strong { font-weight: 700; color: var(--ink); }
/* ---- 警示 ---- */
.cq-warn {
  padding: 10px 12px;
  background: rgba(239, 68, 68, 0.07);
  border-left: 3px solid var(--red);
  border-radius: 4px;
  font-size: 12px;
  color: #b91c1c;
  margin-top: 12px;
}
/* ---- 外链 ---- */
.cq-links { border-top: 1px solid var(--border); padding-top: 14px; margin-top: 8px; }
.cq-lhead h3 {
  font-size: 14px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--ink);
}
.cq-lhead p {
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink4);
  margin: 0 0 12px;
  max-width: 780px;
}
.cq-link-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 8px;
}
.cq-link {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: var(--r);
  text-decoration: none;
  position: relative;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.cq-link:hover {
  border-color: var(--blue);
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.1);
}
.cq-link-site { font-size: 12.5px; font-weight: 600; color: var(--ink); }
.cq-link-desc { font-size: 10.5px; color: var(--ink4); }
.cq-link-arrow {
  position: absolute;
  top: 9px;
  right: 10px;
  font-size: 11px;
  color: var(--ink4);
}
/* ---- 来源总表 ---- */
.cq-sources {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--rl);
  padding: 18px 20px;
}
.cq-sources h3 {
  font-size: 14px;
  font-weight: 700;
  margin: 0 0 6px;
  color: var(--ink);
}
.cq-sources-note {
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink3);
  margin: 0 0 14px;
  max-width: 900px;
}
.cq-src-table { overflow-x: auto; }
.cq-src-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  min-width: 620px;
}
.cq-src-table th {
  text-align: left;
  padding: 8px 10px;
  background: var(--ink5);
  color: var(--ink3);
  font-weight: 600;
  font-size: 11px;
  white-space: nowrap;
}
.cq-src-table td {
  padding: 9px 10px;
  border-bottom: 1px solid var(--border);
  color: var(--ink2);
  vertical-align: top;
}
.cq-src-table td.nm a {
  color: var(--blue);
  text-decoration: none;
  font-weight: 600;
  white-space: nowrap;
}
.cq-src-table td.nm a:hover { text-decoration: underline; }
.pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
  white-space: nowrap;
}
.pill.free { background: rgba(34, 197, 94, 0.12); color: #15803d; }
.pill.paid { background: rgba(168, 85, 247, 0.12); color: #7e22ce; }

/* ---- 导出 PDF 按钮 ---- */
.cq-rhead-right { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; justify-content: flex-end; }
.cq-pdf {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid var(--brand, #5ecbb0);
  border-radius: 8px;
  background: #fff;
  color: var(--brand-deep, #15414e);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}
.cq-pdf:hover { background: var(--brand, #5ecbb0); color: #fff; }
.cq-pdf svg { width: 14px; height: 14px; }

/* ================= 打印 / 导出 PDF ================= */
/* ⚠️ 隐藏规则放在文件末尾的「全局非 scoped」块里 —— 因为容器已 Teleport 到 body，
   scoped 的 data-v 属性在 Teleport 场景下不保证命中，用全局块最稳。 */

/* ---- 响应式 ---- */
@media (max-width: 768px) {
  .cq { padding: 14px 12px 30px; }
  .cq-hero { flex-direction: column; gap: 14px; }
  .cq-bar { flex-wrap: wrap; }
  .cq-go { width: 100%; }
  .cq-trade-top { grid-template-columns: 1fr; }
  .cq-ident { gap: 10px; }
  .cq-rhead { align-items: flex-start; }
}
</style>

<!--
  打印 / 导出 PDF 专用样式（⚠️ 全局非 scoped）
  为什么必须非 scoped：打印时要隐藏「侧栏、顶栏、页脚」这些不在本组件内的元素，
  scoped 选择器加不上 data-v 属性 → 隐藏不掉。故用全局块，并靠 .cq-print 作用域收敛影响面。
-->
<style>
/* 屏幕上彻底隐藏打印容器（全局块，因容器已 Teleport 到 body） */
.cq-print { display: none; }

@media print {
  @page { size: A4; margin: 14mm 12mm; }

  /* 1) 彻底隐藏应用外壳（⚠️ 必须 display:none 而不是 visibility:hidden！
        visibility:hidden 的元素仍占据布局空间，会把打印容器挤出第一页 →
        Chromium 分页截断，后半段内容全丢）。
        打印容器已 Teleport 到 body 直接子级，故不受 #app 隐藏影响。 */
  html, body { background: #fff !important; margin: 0 !important; padding: 0 !important; }
  #app, .shell { display: none !important; }

  /* 2) 打印容器回归正常文档流，独占整页 */
  .cq-print {
    display: block !important;
    width: 100% !important;
    margin: 0 !important;
    padding: 0 !important;
    background: #fff !important;
    color: #111 !important;
    font-size: 10.5pt;
    line-height: 1.55;
  }

  /* 2.5) ⭐ PDF 水印：position:fixed → Chrome 打印逐页重复 */
  .cq-print .p-wm {
    position: fixed;
    inset: 0;
    z-index: 9999;
    display: flex;
    flex-wrap: wrap;
    align-content: space-around;
    justify-content: space-around;
    gap: 48px 64px;
    padding: 16px;
    overflow: hidden;
    pointer-events: none;
  }
  .cq-print .p-wm-t {
    flex: 0 0 auto;
    font-size: 16pt;
    font-weight: 700;
    letter-spacing: 3px;
    color: #15414e;
    opacity: 0.085;
    transform: rotate(-30deg);
    white-space: nowrap;
  }

  /* 3) 打印容器内部排版 */
  .cq-print .p-head { border-bottom: 2px solid #15414e; padding-bottom: 8px; margin-bottom: 10px; }
  .cq-print .p-head h1 { font-size: 11pt; color: #15414e; margin: 0 0 4px; font-weight: 600; }
  /* 文档性质一行（醒目标注这是物性资料，不是报告） */
  .cq-print .p-doctype {
    font-size: 8.5pt; color: #92400e; font-weight: 600;
    background: #fffbeb; border: 1px solid #fcd34d; border-radius: 3px;
    padding: 2px 7px; margin: 0 0 6px; display: inline-block;
  }
  .cq-print .p-title { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
  .cq-print .p-cn { font-size: 19pt; font-weight: 700; }
  .cq-print .p-en { font-size: 11pt; color: #555; font-style: italic; }
  .cq-print .p-meta {
    display: flex; gap: 14px; flex-wrap: wrap;
    margin-top: 5px; font-size: 9pt; color: #444;
  }
  .cq-print .p-meta .p-date { margin-left: auto; color: #777; }

  .cq-print .p-disclaimer {
    border: 1px solid #e05252; border-left: 3px solid #e05252;
    background: #fffafa; padding: 6px 9px; margin: 10px 0 14px;
    font-size: 8.5pt; line-height: 1.5; color: #444;
    page-break-inside: avoid;
  }
  .cq-print .p-disclaimer b { color: #c53030; }

  .cq-print .p-sec { margin-bottom: 14px; }
  /* 短 section（如「进一步检索」）整体不跨页，避免尾部只落两三行到新页 */
  .cq-print .p-sec.p-keep { break-inside: avoid; page-break-inside: avoid; }
  .cq-print .p-sec h2 {
    font-size: 12pt; font-weight: 700; color: #15414e;
    margin: 0 0 7px; padding-bottom: 3px; border-bottom: 1px solid #cfd8dc;
    page-break-after: avoid;
  }
  /* PDF 里 h2 右侧的数据时间（小字，不抢标题） */
  .cq-print .p-sec-date {
    font-size: 9pt; font-weight: 600; color: #0f6b57;
    margin-left: 8px; padding: 1px 6px;
    background: #eaf6f2; border: 1px solid #b7e2d5; border-radius: 3px;
  }
  .cq-print .p-h3 {
    font-size: 10pt; font-weight: 700; color: #1f2937;
    margin: 9px 0 4px; page-break-after: avoid;
  }

  .cq-print table { width: 100%; border-collapse: collapse; margin-bottom: 6px; }
  .cq-print .p-kv td, .cq-print .p-links td {
    border: 1px solid #dde3e7; padding: 4px 7px; vertical-align: top;
    font-size: 9.5pt; word-break: break-word;
  }
  .cq-print .p-kv td.k, .cq-print .p-links td.k {
    width: 96px; background: #f5f8f9; font-weight: 600; color: #37474f;
  }
  .cq-print .p-kv tr, .cq-print .p-links tr { page-break-inside: avoid; break-inside: avoid; }
  /* 链接源名（ChemSpider / Organic Syntheses）不折行 */
  .cq-print .p-links td.k { white-space: nowrap; }

  .cq-print .p-origin {
    color: #6b7280; font-size: 8.5pt; font-style: italic;
    margin-top: 2px; line-height: 1.4;
  }
  .cq-print .p-para { margin: 3px 0; font-size: 9.5pt; orphans: 2; widows: 2; }
  .cq-print .p-origin { orphans: 2; widows: 2; }
  .cq-print .p-list { margin: 3px 0 6px; padding-left: 17px; }
  .cq-print .p-list li { margin-bottom: 5px; font-size: 9.5pt; page-break-inside: avoid; }

  .cq-print .p-h td { border: 1px solid #dde3e7; padding: 3px 7px; font-size: 9pt; vertical-align: top; }
  .cq-print .p-h td.code { width: 58px; font-weight: 700; color: #c53030; }
  .cq-print .p-h tr { page-break-inside: avoid; }

  .cq-print .p-price th, .cq-print .p-price td {
    border: 1px solid #dde3e7; padding: 3px 7px; font-size: 9pt;
  }
  .cq-print .p-price th { background: #f5f8f9; font-weight: 600; color: #37474f; }
  .cq-print .p-price .num { text-align: right; }
  .cq-print .p-price .strong { font-weight: 700; }
  .cq-print .p-price tr { page-break-inside: avoid; }

  .cq-print .mono { font-family: ui-monospace, Consolas, monospace; font-size: 8.5pt; word-break: break-all; }
  .cq-print .p-url { font-size: 8pt; color: #2563eb; word-break: break-all; }
  .cq-print .p-src { font-size: 8pt; color: #6b7280; margin: 3px 0 0; }

  .cq-print .p-foot {
    margin-top: 14px; padding-top: 8px; border-top: 1px solid #cfd8dc;
    font-size: 8pt; color: #6b7280; line-height: 1.5;
    break-inside: avoid; page-break-inside: avoid;
  }
  .cq-print .p-foot p { margin: 2px 0; }
}
</style>
