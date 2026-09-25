<template>
  <div class="emap-page">
    <!-- ===== 无权限：引导态 ===== -->
    <div v-if="state === 'denied'" class="emap-deny glass">
      <div class="dn-icon">{{ emapExpired ? '⏰' : '🔒' }}</div>
      <h2>{{ emapExpired ? '标点地图 · 已到期' : '标点地图 · 需开通' }}</h2>
      <p v-if="emapExpired">
        你的「<b>标点地图</b>」权限已于 <b>{{ emapExpireDate }}</b> <b>到期</b>，当前已停止使用。
        该功能按<b>用户单独授权</b>，请联系管理员<b>续期</b>后继续使用。
      </p>
      <p v-else>
        你当前的账号尚未开通「<b>标点地图</b>」。该功能按<b>用户单独授权</b>，需由管理员开启后使用。
      </p>
      <div class="dn-steps" v-if="!emapExpired">
        <div class="dn-step"><span class="dn-n">1</span><span class="dn-tx">联系平台管理员，说明需要开通「标点地图」</span></div>
        <div class="dn-step"><span class="dn-n">2</span><span class="dn-tx">管理员在「<b>用户管理</b>」中找到你的账号，把「<b>标点地图</b>」设为「<b>已开通</b>」，选择<b>开通时长</b>后保存（保存即生效）</span></div>
        <div class="dn-step"><span class="dn-n">3</span><span class="dn-tx">回到本页点下方「<b>重新检查权限</b>」，或刷新页面即可使用</span></div>
      </div>
      <div class="dn-steps" v-else>
        <div class="dn-step"><span class="dn-n">1</span><span class="dn-tx">联系平台管理员，说明需要<b>续期</b>「标点地图」</span></div>
        <div class="dn-step"><span class="dn-n">2</span><span class="dn-tx">管理员在「<b>用户管理</b>」中把你的「<b>标点地图</b>」重新设为「<b>已开通</b>」，并选择新的<b>开通时长</b>后保存</span></div>
        <div class="dn-step"><span class="dn-n">3</span><span class="dn-tx">回到本页点下方「<b>重新检查权限</b>」，即可继续使用</span></div>
      </div>
      <p class="dn-sub">
        开通后可查看全国化工企业报价点的<b>省份分布</b>，并<b>逐级下钻到城市</b>；
        也可按<b>产品维度</b>筛选后查看区域分布。
      </p>
      <button class="emap-btn primary" @click="load">重新检查权限</button>
    </div>

    <!-- ===== 加载中 ===== -->
    <div v-else-if="state === 'loading'" class="emap-loading">
      <div class="sp"></div>
      <p>正在加载分布数据…</p>
    </div>

    <!-- ===== 出错 ===== -->
    <div v-else-if="state === 'error'" class="emap-deny glass">
      <div class="dn-icon">⚠️</div>
      <h2>数据加载失败</h2>
      <p>{{ errMsg }}</p>
      <button class="emap-btn primary" @click="load">重试</button>
    </div>

    <!-- ===== 正文 ===== -->
    <template v-else>
      <!-- 标题栏 + 面包屑 -->
      <div class="back-bar">
        <span class="page-title">标点地图</span>
        <span class="badge badge-m">{{ isDrill ? '市级' : '省级' }}</span>
        <span v-if="emapExpireDate" class="badge badge-emap"
              :class="{ 'badge-emap-soon': emapDaysLeft !== null && emapDaysLeft <= 7 }"
              :title="'标点地图权限有效期至 ' + emapExpireDate + '（含当日有效）'">
          有效期至 {{ emapExpireDate }}<template v-if="emapDaysLeft !== null">·剩 {{ emapDaysLeft }} 天</template>
        </span>
        <nav class="crumbs">
          <a :class="{ cur: !isDrill }" @click="backToNation">全国</a>
          <template v-if="isDrill">
            <i class="cb-sep">›</i>
            <b>{{ drillProvFull }}</b>
          </template>
        </nav>
        <span class="bar-note">
          按报价点注册地聚合 · 点击省份可下钻到城市 · 数据来源：ChemPrice 化工价格平台
        </span>
      </div>

      <!-- 页签：企业分布 / 产品分布 -->
      <div class="ptabs">
        <button class="ptab" :class="{ on: tab === 'all' }" @click="switchTab('all')">
          <span class="pt-n">企业分布</span>
          <span class="pt-d">全部品种 · 看报价点聚集度</span>
        </button>
        <button class="ptab" :class="{ on: tab === 'var' }" @click="switchTab('var')">
          <span class="pt-n">产品分布</span>
          <span class="pt-d">切换品种看区域分布</span>
        </button>
      </div>

      <section class="kpi-row">
        <div class="kpi-card">
          <div class="kpi-label">{{ kpi.a.label }}</div>
          <div class="kpi-value n">{{ fmtNum(kpi.a.value) }}</div>
          <div class="kpi-sub">{{ kpi.a.sub }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">{{ kpi.b.label }}</div>
          <div class="kpi-value n">{{ fmtNum(kpi.b.value) }}</div>
          <div class="kpi-sub">{{ kpi.b.sub }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">{{ kpi.c.label }}</div>
          <div class="kpi-value n">{{ fmtNum(kpi.c.value) }}</div>
          <div class="kpi-sub">{{ kpi.c.sub }}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">{{ kpi.d.label }}</div>
          <div class="kpi-value n">{{ kpi.d.value }}</div>
          <div class="kpi-sub">{{ kpi.d.sub }}</div>
        </div>
      </section>

      <div class="emap-grid">
        <div class="chart-card">
          <div class="card-header">
            <div class="ch-tt">
              <h3>{{ mapTitle }}</h3>
              <span class="ch-hint">
                <template v-if="!isDrill">
                  口径：按各省的<b>报价点数 / 报价记录</b>填充颜色；<b>点击省份下钻到该省城市</b>
                </template>
                <template v-else>
                  口径：按该省<b>各城市的报价点数 / 报价记录</b>填充颜色；<b>点击城市查看报价点明细</b>
                </template>
              </span>
            </div>
            <div class="hd-right">
              <div v-if="tab === 'var'" class="var-picker">
                <input
                  v-model="varKw"
                  class="var-input"
                  type="text"
                  placeholder="搜索品种…"
                  @focus="varOpen = true"
                  @input="varOpen = true"
                />
                <button class="var-cur" @click="varOpen = !varOpen">
                  {{ varSel ? varSel.varietiesName : '选择品种' }}<i class="caret"></i>
                </button>
                <div v-if="varOpen" class="var-drop">
                  <div v-if="!varFiltered.length" class="var-empty">无匹配品种</div>
                  <div
                    v-for="v in varFiltered"
                    :key="v.varietiesId"
                    class="var-item"
                    :class="{ on: varSel && varSel.varietiesId === v.varietiesId }"
                    @click="selectVariety(v)"
                  >
                    <span class="vi-n">{{ v.varietiesName }}</span>
                    <span class="vi-m">{{ v.points }} 点 · {{ v.provinces }} 省</span>
                  </div>
                </div>
              </div>
              <div class="seg">
                <button class="tab" :class="{ on: mode === 'points' }" @click="setMode('points')">按报价点数</button>
                <button class="tab" :class="{ on: mode === 'records' }" @click="setMode('records')">按报价记录</button>
              </div>
            </div>
          </div>
          <div class="chart-body map-body">
            <div ref="mapRef" class="emap-canvas"></div>

            <div v-if="tipRow" class="map-tip">
              <h4>{{ isDrill ? tipRow.city : (tipRow.provinceFull || tipRow.province) }}</h4>
              <div class="tr"><span>报价点数</span><b>{{ tipRow.points }}</b></div>
              <div class="tr"><span>报价记录</span><b>{{ (tipRow.records || 0).toLocaleString() }}</b></div>
              <div v-if="!isDrill" class="tr"><span>覆盖城市</span><b>{{ tipRow.cities }}</b></div>
              <div v-else class="tr"><span>最后报价</span><b>{{ tipRow.lastDate || '—' }}</b></div>
              <div class="tr">
                <span>{{ isDrill ? '本省占比' : '全国占比' }}</span>
                <b>{{ share(tipRow) }}</b>
              </div>
            </div>

            <div class="map-legend">
              <div class="lg-tt">{{ mode === 'points' ? '报价点数' : '报价记录' }}</div>
              <div v-for="(b, i) in legendBands" :key="i" class="lg-row">
                <i :style="{ background: b.color }"></i><span>{{ b.label }}</span>
              </div>
              <div class="lg-row lg-none"><i class="none-box"></i><span>暂无数据</span></div>
            </div>

            <button v-if="isDrill" class="map-back" @click="backToNation">← 返回全国</button>

            <div v-if="scopeLoading" class="map-mask">
              <div class="sp"></div>
              <p>正在加载数据…</p>
            </div>
          </div>
        </div>

        <div class="chart-card side-card">
          <div class="card-header">
            <div class="ch-tt">
              <h3 v-if="detail">📍 {{ detail.city || drillProvFull }} · 报价点</h3>
              <h3 v-else-if="isDrill">{{ drillProvFull }} · 城市排名</h3>
              <h3 v-else>省份排名</h3>
              <span v-if="detail" class="ch-hint">
                共 {{ detail.totalPoints }} 个报价点 · 记录 {{ fmtNum(detail.totalRecords) }} 条
              </span>
              <span v-else-if="isDrill" class="ch-hint">
                共 {{ cityMeta.totalCities }} 个城市 · {{ cityMeta.totalPoints }} 个报价点 ·
                记录 {{ fmtNum(cityMeta.totalRecords) }} 条
                <template v-if="provOnlyCount">；另有 {{ provOnlyCount }} 个报价点仅到省级，未参与城市下钻</template>
              </span>
              <span v-else class="ch-hint">口径：{{ mode === 'points' ? '报价点数' : '报价记录条数' }}降序</span>
            </div>
            <button v-if="detail" class="an-btn" @click="closeCity">← 城市排名</button>
            <button v-else-if="isDrill" class="an-btn" @click="backToNation">← 返回全国</button>
          </div>

          <div class="side-body">
            <!-- 报价点明细 -->
            <template v-if="detail">
              <div class="dnote">
                报价点名称取自数据源的<b>报价点简称</b>；「代表企业」为按候选池
                （化工相关 + 开业 + 非疑似非经营主体）择优得到的工商主体。
                标注「<b>待核</b>」表示省市归属或主体匹配存在不确定性，建议业务侧复核。
              </div>
              <div v-if="!detail.points.length" class="empty-tip">该城市暂无可展示的报价点。</div>
              <div v-for="e in detail.points" :key="e.name" class="ecard">
                <div class="ec-main">
                  <div class="ec-n">{{ e.name }}</div>
                  <div class="ec-m">
                    {{ e.company || e.src || '—' }}<template v-if="e.lastDate"> · 最后报价 {{ e.lastDate }}</template>
                  </div>
                </div>
                <div class="ec-r n">{{ e.records.toLocaleString() }}</div>
                <span class="badge" :class="confOf(e.confidence).cls">{{ confOf(e.confidence).txt }}</span>
              </div>
            </template>

            <!-- 排名（省 / 市） -->
            <template v-else>
              <div v-if="!rankRows.length" class="empty-tip">
                {{ isDrill ? '该省暂无城市级数据。' : '暂无数据。' }}
              </div>
              <div
                v-for="(row, i) in rankRows"
                :key="row.key"
                class="prow"
                :class="{ act: isDrill && row.key === drillCity }"
                @click="onRowClick(row)"
                @mouseenter="tipRow = row.raw"
                @mouseleave="tipRow = null"
              >
                <div class="rk">{{ i + 1 }}</div>
                <div class="rn">
                  <span class="rn-n">{{ row.label }}</span>
                  <span v-if="row.sub" class="rn-s">{{ row.sub }}</span>
                </div>
                <div class="rb"><i :style="{ width: row.w }"></i></div>
                <div class="rv n">{{ row.value.toLocaleString() }}</div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- ==================== 图表区（全部随「省 → 市」下钻联动） ==================== -->
      <section class="chart-grid">
        <div class="chart-card">
          <div class="card-header">
            <div class="ch-tt">
              <h3>{{ topTitle }}</h3>
              <span class="ch-hint">
                口径与地图一致（{{ mode === 'points' ? '报价点数' : '报价记录' }}）·
                <b>点条形可下钻</b>
              </span>
            </div>
          </div>
          <div class="chart-body"><div ref="topRef" class="emap-chart"></div></div>
        </div>

        <div class="chart-card">
          <div class="card-header">
            <div class="ch-tt">
              <h3>集中度 · 头部效应</h3>
              <span class="ch-hint">
                条形 = 各{{ dimLabel }}数值，折线 = <b>累计占比</b>；
                前 3 名合计 <b>{{ top3Share }}</b>
              </span>
            </div>
          </div>
          <div class="chart-body"><div ref="paretoRef" class="emap-chart"></div></div>
        </div>
      </section>

      <section class="chart-card wide-card">
        <div class="card-header">
          <div class="ch-tt">
            <h3>{{ crossTitle }}</h3>
            <span class="ch-hint">{{ crossHint }}</span>
          </div>
        </div>
        <div class="chart-body">
          <div v-if="matrixLoading" class="chart-mask"><div class="sp"></div></div>
          <div ref="crossRef" class="emap-chart tall"></div>
        </div>
      </section>

      <p class="foot-note">
        说明：本页为<b>报价点口径</b>——依据报价点的注册地（工商登记地址）逐级归到省 / 市，
        与市场价格口径不同；报价记录数为平台累计收录条数。
        省市归属由「已有核定省份作锚点 + 合格候选企业注册地众数投票」交叉判定，
        置信度偏低或与锚点冲突者<b>只落到省</b>，无法确定省份的不予上图。
        当前共 {{ ov.totalPoints }} 个报价点参与统计，覆盖 {{ ov.totalProvinces }} 个省级行政区。
        数据更新时间：{{ ov.updatedAt || '—' }}。
      </p>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { api } from '../api/index'
import * as echarts from 'echarts'

const state = ref('loading')
const errMsg = ref('')
// 标点地图权限有效期（来自 /enterprise-map/access）
const emapExpired = ref(false)
const emapExpireDate = ref('')
const emapDaysLeft = ref(null)
const tab = ref('all')          // 'all' = 全部品种（企业分布） | 'var' = 选定品种（产品分布）

/* ---------- 品种 ---------- */
const varList = ref([])
const varSel = ref(null)
const varKw = ref('')
const varOpen = ref(false)

/* ---------- 聚合数据 ---------- */
const ov = ref({ totalPoints: 0, totalRecords: 0, totalProvinces: 0, provinces: [], updatedAt: '' })
const cities = ref([])
const cityMeta = ref({ totalPoints: 0, totalRecords: 0, totalCities: 0 })
const scopeLoading = ref(false)

/* ---------- 下钻状态 ---------- */
const drillProv = ref('')        // 省简称，如「山东」
const drillProvFull = ref('')    // 省全称，如「山东省」
const drillCity = ref('')        // 市全称，如「潍坊市」
const detail = ref(null)         // 报价点明细

/* ---------- 交互 ---------- */
const mode = ref('points')       // 'points' | 'records'
const tipRow = ref(null)

/* ---------- 图表 ---------- */
const topRef = ref(null)
const paretoRef = ref(null)
const crossRef = ref(null)
let topChart = null, paretoChart = null, crossChart = null
const matrix = ref(null)          // /points/matrix 结果（省份/城市 × 品种）
const matrixLoading = ref(false)
const mapRef = ref(null)
let mapInst = null, fillLayer = null

/* ---------- 资源 ---------- */
let TMapSDK = null, geoData = null, cityGeoData = null
let sdkPromise = null, geoPromise = null, cityGeoPromise = null

/* 蓝色系 5 档（浅 → 深），与 bandOf() 的 b1…b5 一一对应 */
const SCALE = ['#dbeafe', '#93c5fd', '#3b82f6', '#1e40af', '#172554']

/**
 * ⭐ 取值口径：mode 是交互语义（'points' | 'records'），
 * 省 / 市两级的字段名完全一致（points / records），故此处直接取；
 * 但仍做类型兜底，避免 undefined → 0 导致整图不上色。
 */
const FIELD = { points: 'points', records: 'records' }
function valOf(r) {
  if (!r) return 0
  const v = r[FIELD[mode.value]]
  return typeof v === 'number' ? v : 0
}

const scopeVid = computed(() =>
  tab.value === 'var' && varSel.value ? varSel.value.varietiesId : null)
const isDrill = computed(() => !!drillProv.value)
const mapTitle = computed(() => {
  const vn = tab.value === 'var' && varSel.value ? varSel.value.varietiesName : '全部品种'
  return isDrill.value ? `${drillProvFull.value} · 城市分布（${vn}）` : `省份热力分布 · ${vn}`
})

const curRows = computed(() => (isDrill.value ? cities.value : (ov.value.provinces || [])))
const ranked = computed(() => [...curRows.value].sort((a, b) => valOf(b) - valOf(a)))
const maxVal = computed(() => (ranked.value.length ? (valOf(ranked.value[0]) || 1) : 1))

/** 下钻时该省的权威聚合行（来自省级接口，含「仅到省级、无城市」的报价点） */
const drillProvRow = computed(() => (isDrill.value
  ? ((ov.value.provinces || []).find(x => x.province === drillProv.value) || null)
  : null))
/** 仅落到省、没有城市信息的报价点数（省级总数 − 可下钻城市之和） */
const provOnlyCount = computed(() => {
  const row = drillProvRow.value
  if (!row) return 0
  return Math.max(0, (row.points || 0) - (cityMeta.value.totalPoints || 0))
})

const rankRows = computed(() => {
  const mx = maxVal.value || 1
  return ranked.value.map(r => ({
    key: isDrill.value ? r.city : r.province,
    label: isDrill.value ? r.city : r.province,
    sub: isDrill.value ? (r.lastDate || '') : (r.cities ? r.cities + ' 个市' : ''),
    value: valOf(r),
    w: (valOf(r) / mx * 100).toFixed(1) + '%',
    raw: r
  }))
})

const kpi = computed(() => {
  const top = ranked.value[0]
  if (isDrill.value) {
    // ⭐ 本省口径必须用省级接口的值（含「仅到省级」的报价点），
    //    否则会出现「列表里山东 460、点进去变 447」的口径打架。
    const row = drillProvRow.value
    const pt = row ? row.points : cityMeta.value.totalPoints
    const rc = row ? row.records : cityMeta.value.totalRecords
    return {
      a: { label: '本省报价点', value: pt, sub: provOnlyCount.value
        ? '另 ' + provOnlyCount.value + ' 个未落到城市' : '去重后的报价点数量' },
      b: { label: '本省报价记录', value: rc, sub: '合计 ' + (rc || 0).toLocaleString() + ' 条' },
      c: { label: '覆盖城市', value: cityMeta.value.totalCities, sub: drillProvFull.value || '—' },
      d: { label: '报价点最多', value: top ? top.city : '—', sub: top ? top.points + ' 个报价点' : '—' }
    }
  }
  return {
    a: { label: '报价点数', value: ov.value.totalPoints, sub: '覆盖 ' + ov.value.totalProvinces + ' 个省级行政区' },
    b: { label: '报价记录', value: ov.value.totalRecords, sub: '合计 ' + (ov.value.totalRecords || 0).toLocaleString() + ' 条' },
    c: { label: '覆盖省份', value: ov.value.totalProvinces, sub: '有报价点注册的省区' },
    d: { label: '报价点最多', value: top ? top.province : '—', sub: top ? top.points + ' 个报价点' : '—' }
  }
})

/* ---------- 图表标题与提示 ---------- */
const dimLabel = computed(() => (isDrill.value ? '城市' : '省份'))
const topTitle = computed(() => `TOP 10 ${dimLabel.value}`)
const isVarMode = computed(() => tab.value === 'var' && !!varSel.value)
const crossTitle = computed(() => (isVarMode.value
  ? `${varSel.value.varietiesName} · 各省「报价点数 × 报价记录」`
  : (isDrill.value ? `${drillProvFull.value} · 城市 × 品种 分布矩阵` : '省份 × 品种 分布矩阵')))
const crossHint = computed(() => (isVarMode.value
  ? '每个气泡 = 一个有报价点的省份；横轴报价点数（货源广度）、纵轴报价记录（数据深度）、气泡大小 = 覆盖城市数'
  : '行 = TOP 12 ' + dimLabel.value + '（按报价点数）· 列 = TOP 12 品种（按报价记录）· 格子颜色与数字 = 该组合的报价点数 / 记录（随右上角口径开关切换）'))
/* 前 3 名合计占比 */
const top3Share = computed(() => {
  const r = ranked.value
  if (!r.length) return '—'
  const total = r.reduce((a, x) => a + valOf(x), 0) || 1
  const top3 = r.slice(0, 3).reduce((a, x) => a + valOf(x), 0)
  return (top3 / total * 100).toFixed(1) + '%'
})

const varFiltered = computed(() => {
  const kw = varKw.value.trim().toLowerCase()
  if (!kw) return varList.value
  return varList.value.filter(v => (v.varietiesName || '').toLowerCase().includes(kw))
})

/* 分档图例：与 bandOf() 使用同一套 step，保证「图例颜色 = 地图颜色」 */
function bandsOf(mx, unit) {
  const step = Math.max(1, Math.ceil((mx || 1) / 5))
  return [
    { label: `1 – ${step}${unit}`, color: SCALE[0] },
    { label: `${step + 1} – ${step * 2}${unit}`, color: SCALE[1] },
    { label: `${step * 2 + 1} – ${step * 3}${unit}`, color: SCALE[2] },
    { label: `${step * 3 + 1} – ${step * 4}${unit}`, color: SCALE[3] },
    { label: `> ${step * 4}${unit}`, color: SCALE[4] }
  ]
}
const legendBands = computed(() => bandsOf(maxVal.value, mode.value === 'points' ? ' 点' : ''))

function fmtNum(n) {
  n = typeof n === 'number' ? n : 0
  return n >= 10000 ? (n / 10000).toFixed(1) + '万' : n.toLocaleString()
}
function share(r) {
  const denomPoints = isDrill.value ? (cityMeta.value.totalPoints || 0) : (ov.value.totalPoints || 0)
  const denomRecords = isDrill.value ? (cityMeta.value.totalRecords || 0) : (ov.value.totalRecords || 0)
  const d = mode.value === 'points' ? denomPoints : denomRecords
  return d ? ((valOf(r) || 0) / d * 100).toFixed(1) + '%' : '—'
}
function confOf(c) {
  if (c === '高') return { cls: 'badge-m', txt: '已核' }
  if (c === '中') return { cls: 'badge-i', txt: '较可信' }
  return { cls: 'badge-n', txt: '待核' }
}
function qs(obj) {
  const parts = []
  Object.keys(obj).forEach(k => {
    const v = obj[k]
    if (v === null || v === undefined || v === '') return
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
  })
  return parts.length ? '?' + parts.join('&') : ''
}

/* ---------- 资源加载 ---------- */
function loadSDK() {
  if (window.TMap) { TMapSDK = window.TMap; return Promise.resolve(TMapSDK) }
  if (sdkPromise) return sdkPromise
  // 安全配置必须在 SDK 之前声明；占位符由运行时替换，勿改动
  window._TMapSecurityConfig = {
    serviceHost: 'http://127.0.0.1:__WB_HTTP_PORT__/_TMapService/_wbt/__WB_TMAP_SECRET__'
  }
  sdkPromise = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://map.qq.com/api/gljs?v=1.exp'
    s.async = true
    s.onload = () => { TMapSDK = window.TMap; TMapSDK ? resolve(TMapSDK) : reject(new Error('地图 SDK 初始化失败')) }
    s.onerror = () => reject(new Error('地图 SDK 加载失败，请检查网络'))
    document.head.appendChild(s)
  })
  return sdkPromise
}

function loadGeo() {
  if (window.CN_GEO) { geoData = window.CN_GEO; return Promise.resolve(geoData) }
  if (geoPromise) return geoPromise
  geoPromise = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = '/cn_geo.js'
    s.async = true
    s.onload = () => { geoData = window.CN_GEO; geoData ? resolve(geoData) : reject(new Error('边界数据解析失败')) }
    s.onerror = () => reject(new Error('边界数据加载失败'))
    document.head.appendChild(s)
  })
  return geoPromise
}

/**
 * 市级边界（2.4 MB）**按需加载** —— 只有首次下钻时才拉，不拖慢首屏。
 * 结构：window.CN_CITY_GEO = GeoJSON FeatureCollection（370 个市），
 *       properties = {name(全称，如「潍坊市」), adcode, prov(省简称), center}。
 */
function loadCityGeo() {
  if (window.CN_CITY_GEO) { cityGeoData = window.CN_CITY_GEO; return Promise.resolve(cityGeoData) }
  if (cityGeoPromise) return cityGeoPromise
  cityGeoPromise = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = '/cn_city_geo.js'
    s.async = true
    s.onload = () => {
      cityGeoData = window.CN_CITY_GEO
      cityGeoData ? resolve(cityGeoData) : reject(new Error('市级边界解析失败'))
    }
    s.onerror = () => { cityGeoPromise = null; reject(new Error('市级边界加载失败')) }
    document.head.appendChild(s)
  })
  return cityGeoPromise
}

/* ---------- 图层：按行政区形状填充（真热力图） ---------- */
/* 腾讯地图 MultiPolygon 的填充色只能由 styleId 决定（不支持逐 geometry 设色），
   因此按色档预建 5 个 styleId：b1…b5，加 no（无数据）/ act（选中）/ jd（南海界线）。 */
function bandOf(v, mx) {
  if (!v) return 'no'
  const step = Math.max(1, Math.ceil((mx || 1) / 5))
  if (v <= step) return 'b1'
  if (v <= step * 2) return 'b2'
  if (v <= step * 3) return 'b3'
  if (v <= step * 4) return 'b4'
  return 'b5'
}

function hexToRgb(hex) {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex || '')
  return m ? { r: parseInt(m[1], 16), g: parseInt(m[2], 16), b: parseInt(m[3], 16) } : null
}

function buildStyles() {
  const st = {}
  SCALE.forEach((c, i) => {
    const rgb = hexToRgb(c) || { r: 59, g: 130, b: 246 }
    st['b' + (i + 1)] = new TMapSDK.PolygonStyle({
      color: `rgba(${rgb.r},${rgb.g},${rgb.b},0.94)`,
      showBorder: true,
      borderColor: 'rgba(255,255,255,0.95)',
      borderWidth: 1
    })
  })
  st.no = new TMapSDK.PolygonStyle({
    color: 'rgba(226,229,234,0.85)',
    showBorder: true,
    borderColor: 'rgba(255,255,255,0.95)',
    borderWidth: 1
  })
  st.act = new TMapSDK.PolygonStyle({
    color: 'rgba(21,65,78,0.88)',
    showBorder: true,
    borderColor: '#15414E',
    // ⚠️ 必须用整数：腾讯地图 SDK 对非整数边框宽会打
    //    「MultiPolyline.styles: 样式id act 对应的 PolylineStyle.width 属性无效」警告
    borderWidth: 2
  })
  st.jd = new TMapSDK.PolygonStyle({
    color: 'rgba(226,229,234,0.85)',
    showBorder: true,
    borderColor: '#94a3b8',
    borderWidth: 1
  })
  return st
}

/** 多边形环数组（[[[lng,lat]…]]）→ 腾讯 LatLng 路径 */
function polyToPaths(polys) {
  const paths = []
  ;(polys || []).forEach(poly => {
    ;(poly || []).forEach(ring => {
      if (!ring || ring.length < 4) return
      paths.push(ring.map(pt => new TMapSDK.LatLng(pt[1], pt[0])))
    })
  })
  return paths
}

/** GeoJSON Polygon / MultiPolygon → 统一成「多边形数组」 */
function geojsonToPaths(g) {
  if (!g) return []
  if (g.type === 'Polygon') return polyToPaths([g.coordinates])
  if (g.type === 'MultiPolygon') return polyToPaths(g.coordinates)
  return []
}

/**
 * 生成填充几何。
 * - 省级：数据源 window.CN_GEO.provinces，名称字段 f.n（省简称），含 1 条南海界线（f.jd）
 * - 市级：数据源 window.CN_CITY_GEO.features，按 properties.prov 过滤当前省，
 *         名称字段 properties.name（市全称，如「潍坊市」）
 */
function buildGeoms(rows, activeName, mx) {
  const byName = {}
  ;(rows || []).forEach(r => {
    const nm = isDrill.value ? r.city : r.province
    if (nm) byName[nm] = r
  })

  const geoms = []
  if (isDrill.value) {
    const list = (cityGeoData && cityGeoData.features) || []
    list.forEach(f => {
      const pr = f.properties || {}
      if (pr.prov !== drillProv.value) return
      const nm = pr.name
      const stat = byName[nm]
      const paths = geojsonToPaths(f.geometry)
      if (!paths.length) return
      geoms.push({
        id: nm,
        styleId: nm === activeName ? 'act' : bandOf(stat ? valOf(stat) : 0, mx),
        paths,
        properties: { n: nm, v: stat ? valOf(stat) : 0 }
      })
    })
  } else {
    const list = (geoData && geoData.provinces) || []
    list.forEach(f => {
      const isJd = !!f.jd
      const stat = isJd ? null : byName[f.n]
      const paths = polyToPaths(f.p)
      if (!paths.length) return
      let styleId
      if (isJd) styleId = 'jd'
      else if (f.n === activeName) styleId = 'act'
      else styleId = bandOf(stat ? valOf(stat) : 0, mx)
      geoms.push({
        id: isJd ? '__jd' : f.n,
        styleId,
        paths,
        properties: { n: f.n, jd: isJd, v: stat ? valOf(stat) : 0 }
      })
    })
  }
  return geoms
}

/** 绘制 / 重绘填充层（省级与市级共用同一个图层实例，切换时只替换 geometries） */
function paint() {
  if (!TMapSDK || !geoData || !mapInst) return
  const activeName = isDrill.value ? drillCity.value : ''
  const styles = buildStyles()
  const geometries = buildGeoms(curRows.value, activeName, maxVal.value)

  if (fillLayer) {
    fillLayer.setStyles(styles)
    fillLayer.setGeometries(geometries)
    return
  }
  const newLayer = new TMapSDK.MultiPolygon({ map: mapInst, styles, geometries, zIndex: 10 })
  if (newLayer.setZIndex) newLayer.setZIndex(10)
  newLayer.on('click', e => {
    const id = e.geometry && e.geometry.id
    if (!id || id === '__jd') return
    if (isDrill.value) pickCity(id)
    else pickProvince(id)
  })
  fillLayer = newLayer
}

async function ensureMap() {
  await Promise.all([loadSDK(), loadGeo()])
  if (mapInst) { paint(); return }
  await nextTick()
  const el = mapRef.value
  if (!el) return
  mapInst = new TMapSDK.Map(el, {
    zoom: 4.2,
    center: new TMapSDK.LatLng(34.5, 108.5),
    baseMap: { type: 'vector' }
  })
  // ⛔ 必须补 resize：实测地图创建时容器宽度可能还是「栅格未稳定」的值
  //    （约 1262px，实际为 770px）→ TMap 内部视口偏大 → setCenter 后内容整体右移约 245px。
  setTimeout(() => { try { mapInst.resize() } catch (e) { /* 忽略 */ } }, 320)
  paint()
}

/**
 * 定位到某点。
 * ⚠️ 顺序必须是「先 setZoom 再 setCenter」：同帧内先改中心再改缩放，
 *    SDK 会按旧中心重算，导致定位偏移；末尾再补一次 setCenter 兜底。
 */
function zoomTo(lng, lat, z) {
  if (!mapInst || !TMapSDK) return
  const c = new TMapSDK.LatLng(lat, lng)
  try {
    if (mapInst.resize) mapInst.resize()
    mapInst.setZoom(z)
    mapInst.setCenter(c)
    setTimeout(() => { try { mapInst.setCenter(c) } catch (e) { /* 忽略 */ } }, 260)
  } catch (e) { /* 视野调整失败不影响数据展示 */ }
}


/* ==================== 图表（ECharts） ==================== */
/** 取值 → 与地图同源的 5 档蓝色（保证图表与地图配色一致） */
function scaleColor(v, mx) {
  const b = bandOf(v, mx)
  const idx = { b1: 0, b2: 1, b3: 2, b4: 3, b5: 4 }[b]
  return SCALE[idx === undefined ? 0 : idx]
}
function fmtShort(n) {
  n = n || 0
  if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿'
  if (n >= 10000) return (n / 10000).toFixed(1) + '万'
  return String(n)
}
/** 首次调用 init；后续复用同一实例（避免重复 init 与重复绑定事件） */
function ensureChart(el, inst, onClick) {
  if (!el) return null
  if (inst && !inst.isDisposed()) return inst
  const c = echarts.init(el)
  if (onClick) c.on('click', onClick)
  return c
}
const AXIS_LABEL = { color: '#9ca3af', fontSize: 10 }
const TOOLTIP = {
  backgroundColor: 'rgba(255,255,255,.98)', borderColor: '#e4e4e7',
  borderWidth: 1, textStyle: { color: '#111827', fontSize: 12 },
  extraCssText: 'box-shadow:0 6px 24px rgba(31,41,55,.14);border-radius:8px;'
}

/** ① TOP 10 条形图（点击可下钻） */
function renderTop() {
  const el = topRef.value
  if (!el) return
  topChart = ensureChart(el, topChart, onTopClick)
  if (!topChart) return
  const rows = ranked.value.slice(0, 10)
  const unit = mode.value === 'points' ? '个报价点' : '条记录'
  const labels = rows.map(r => (isDrill.value ? r.city : r.province)).reverse()
  const vals = rows.map(r => valOf(r)).reverse()
  topChart.setOption({
    animationDuration: 380,
    grid: { left: 6, right: 58, top: 10, bottom: 6, containLabel: true },
    tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' },
      formatter: p => `${p[0].name}<br/><b>${p[0].value.toLocaleString()}</b> ${unit}` }, TOOLTIP),
    xAxis: { type: 'value', axisLine: { show: false }, axisTick: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: AXIS_LABEL },
    yAxis: { type: 'category', data: labels, axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#374151', fontSize: 11.5 } },
    series: [{
      type: 'bar', data: vals, barWidth: '56%',
      itemStyle: { borderRadius: [0, 4, 4, 0], color: p => scaleColor(p.value, maxVal.value) },
      label: { show: true, position: 'right', fontSize: 10, color: '#6b7280',
        formatter: p => fmtShort(p.value) }
    }]
  }, true)
  topChart.resize()
}
function onTopClick(p) {
  if (!p || !p.name) return
  if (!isDrill.value) pickProvince(p.name)
  else pickCity(p.name)
}

/** ② 集中度帕累托（条形 + 累计占比折线） */
function renderPareto() {
  const el = paretoRef.value
  if (!el) return
  paretoChart = ensureChart(el, paretoChart)
  if (!paretoChart) return
  const all = ranked.value
  const rows = all.slice(0, 15)
  const labels = rows.map(r => (isDrill.value ? r.city : r.province))
  const vals = rows.map(r => valOf(r))
  const total = all.reduce((a, x) => a + valOf(x), 0) || 1
  let acc = 0
  const cum = vals.map(v => { acc += v; return +(acc / total * 100).toFixed(1) })
  const unit = mode.value === 'points' ? '个报价点' : '条记录'
  paretoChart.setOption({
    animationDuration: 380,
    grid: { left: 6, right: 44, top: 14, bottom: 46, containLabel: true },
    tooltip: Object.assign({ trigger: 'axis', axisPointer: { type: 'shadow' },
      formatter: ps => {
        const i = ps[0].dataIndex
        return `${labels[i]}<br/><b>${vals[i].toLocaleString()}</b> ${unit}<br/>累计占比 <b>${cum[i]}%</b>`
      } }, TOOLTIP),
    xAxis: { type: 'category', data: labels, axisLine: { lineStyle: { color: '#e4e4e7' } },
      axisTick: { show: false }, axisLabel: Object.assign({ interval: 0, rotate: 32, fontSize: 9.5 }, AXIS_LABEL) },
    yAxis: [
      { type: 'value', axisLine: { show: false }, axisTick: { show: false },
        splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: Object.assign({ formatter: v => fmtShort(v) }, AXIS_LABEL) },
      { type: 'value', max: 100, axisLine: { show: false }, axisTick: { show: false },
        splitLine: { show: false }, axisLabel: Object.assign({ formatter: '{value}%' }, AXIS_LABEL) }
    ],
    series: [
      { name: '数值', type: 'bar', data: vals, barWidth: '52%',
        itemStyle: { borderRadius: [4, 4, 0, 0], color: p => scaleColor(p.value, maxVal.value) } },
      { name: '累计占比', type: 'line', yAxisIndex: 1, data: cum, smooth: true, symbol: 'circle', symbolSize: 5,
        lineStyle: { color: '#C9A227', width: 2 }, itemStyle: { color: '#C9A227' },
        label: { show: true, position: 'top', fontSize: 9, color: '#a16207', formatter: '{c}%' } }
    ]
  }, true)
  paretoChart.resize()
}

/** ③ 交叉分析：全部品种 = 省/市 × 品种热力矩阵；选定品种 = 广度×深度气泡图 */
function renderCross() {
  const el = crossRef.value
  if (!el) return
  crossChart = ensureChart(el, crossChart)
  if (!crossChart) return
  if (isVarMode.value) { renderBubble(); return }

  const m = matrix.value
  if (!m || !m.rows || !m.rows.length || !m.cols || !m.cols.length) {
    crossChart.clear()
    crossChart.setOption({
      title: { text: '暂无矩阵数据', left: 'center', top: 'middle',
        textStyle: { color: '#9ca3af', fontSize: 12, fontWeight: 400 } }
    }, true)
    return
  }
  const colNames = m.cols.map(c => c.name)
  const rowNames = m.rows
  const pi = mode.value === 'points' ? 2 : 3
  const data = m.cells.map(c => [c[1], c[0], c[pi]])
  const maxV = Math.max(1, ...data.map(d => d[2]))
  const unit = mode.value === 'points' ? '个报价点' : '条记录'

  crossChart.setOption({
    animationDuration: 380,
    grid: { left: 10, right: 16, top: 12, bottom: 62, containLabel: true },
    tooltip: Object.assign({
      formatter: p => `<b>${rowNames[p.value[1]]}</b> · <b>${colNames[p.value[0]]}</b><br/>${p.value[2].toLocaleString()} ${unit}`
    }, TOOLTIP),
    xAxis: { type: 'category', data: colNames, splitArea: { show: true },
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: Object.assign({ interval: 0, rotate: 34, fontSize: 10 }, { color: '#374151' }) },
    yAxis: { type: 'category', data: rowNames, splitArea: { show: true },
      // ⭐ inverse: 类目轴 index 0 默认在【底部】，反转让 TOP1（行首/最大值）排到最上面
      inverse: true,
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#374151', fontSize: 11 } },
    visualMap: {
      min: 0, max: maxV, calculable: true, orient: 'horizontal',
      left: 'center', bottom: 0, itemWidth: 12, itemHeight: 90,
      inRange: { color: SCALE },
      textStyle: { fontSize: 10, color: '#9ca3af' }
    },
    series: [{
      type: 'heatmap', data,
      label: { show: true, fontSize: 9, color: '#334155', formatter: p => (p.value[2] ? fmtShort(p.value[2]) : '') },
      itemStyle: { borderColor: 'rgba(255,255,255,.9)', borderWidth: 1 },
      emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(31,41,55,.28)' } }
    }]
  }, true)
  crossChart.resize()
}

/** 选定品种时的气泡图：x=报价点数（广度）y=报价记录（深度）size=覆盖城市数 */
function renderBubble() {
  const rows = ov.value.provinces || []
  const pts = rows.map(r => ({
    name: r.provinceFull || r.province,
    value: [r.points || 0, r.records || 0, r.cities || 0]
  }))
  if (!pts.length) { crossChart.clear(); return }
  const maxPts = Math.max(1, ...pts.map(p => p.value[0]))
  const maxRec = Math.max(1, ...pts.map(p => p.value[1]))
  crossChart.setOption({
    animationDuration: 380,
    grid: { left: 10, right: 62, top: 18, bottom: 44, containLabel: true },
    tooltip: Object.assign({
      formatter: p => `<b>${p.value[0] ? rows[p.dataIndex].provinceFull || rows[p.dataIndex].province : ''}</b>`
        + `<br/>报价点数 <b>${p.value[0]}</b> · 报价记录 <b>${p.value[1].toLocaleString()}</b>`
        + `<br/>覆盖城市 <b>${p.value[2]}</b> 个`
    }, TOOLTIP),
    xAxis: { type: 'value', name: '报价点数 →', nameLocation: 'middle', nameGap: 24,
      nameTextStyle: { color: '#9ca3af', fontSize: 10 },
      axisLine: { show: false }, axisTick: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: AXIS_LABEL },
    yAxis: { type: 'value', name: '报价记录 →', nameLocation: 'middle', nameGap: 42,
      nameTextStyle: { color: '#9ca3af', fontSize: 10 },
      axisLine: { show: false }, axisTick: { show: false },
      splitLine: { lineStyle: { color: '#f1f5f9' } }, axisLabel: Object.assign({ formatter: v => fmtShort(v) }, AXIS_LABEL) },
    series: [{
      type: 'scatter', data: pts,
      symbolSize: p => 12 + Math.sqrt(p[2] || 0) * 6,
      itemStyle: { color: 'rgba(59,130,246,.62)', borderColor: '#1d4ed8', borderWidth: 1 },
      label: { show: true, position: 'right', fontSize: 10, color: '#6b7280',
        formatter: p => p.value[0] >= maxPts * 0.35 || p.value[1] >= maxRec * 0.35 ? p.name.replace(/(省|市|自治区|维吾尔|壮族|回族|特别行政区)/g, '') : '' }
    }]
  }, true)
  crossChart.resize()
}

async function loadMatrix() {
  matrixLoading.value = true
  try {
    const res = await api.get('/enterprise-map/points/matrix'
      + qs({ province: drillProv.value, topRow: 12, topCol: 12 }))
    if (res && res.code === 200) matrix.value = res.data
  } catch (e) { /* 矩阵失败不阻塞主视图 */ } finally {
    matrixLoading.value = false
  }
}

/** 统一入口：数据变化后重绘三张图（DOM 就绪后调用） */
async function renderCharts() {
  await nextTick()
  renderTop()
  renderPareto()
  renderCross()
}

function onWinResize() {
  if (topChart && !topChart.isDisposed()) topChart.resize()
  if (paretoChart && !paretoChart.isDisposed()) paretoChart.resize()
  if (crossChart && !crossChart.isDisposed()) crossChart.resize()
}

/* ---------- 数据 ---------- */
async function loadOverview() {
  const res = await api.get('/enterprise-map/points/overview' + qs({ vid: scopeVid.value }))
  if (!res || res.code !== 200) throw new Error((res && res.message) || '接口返回异常')
  ov.value = res.data || {}
}

async function loadCities() {
  if (!drillProv.value) {
    cities.value = []
    cityMeta.value = { totalPoints: 0, totalRecords: 0, totalCities: 0 }
    return
  }
  const res = await api.get('/enterprise-map/points/cities'
    + qs({ province: drillProv.value, vid: scopeVid.value }))
  if (res && res.code === 200) {
    const d = res.data || {}
    cities.value = d.cities || []
    cityMeta.value = {
      totalPoints: d.totalPoints || 0,
      totalRecords: d.totalRecords || 0,
      totalCities: d.totalCities || 0
    }
  } else {
    cities.value = []
    cityMeta.value = { totalPoints: 0, totalRecords: 0, totalCities: 0 }
  }
}

/** 按当前作用域（页签 + 品种）重新拉数并重绘，保留已下钻的层级 */
async function refresh() {
  scopeLoading.value = true
  try {
    await loadOverview()
    if (drillProv.value) await loadCities()
    await ensureMap()
    paint()
  } finally {
    scopeLoading.value = false
  }
}

async function loadVarieties() {
  if (!varList.value.length) {
    try {
      const res = await api.get('/enterprise-map/points/varieties')
      if (res && res.code === 200) {
        varList.value = (res.data && res.data.varieties) || []
        if (!varSel.value && varList.value.length) varSel.value = varList.value[0]
      }
    } catch (e) { /* 下拉加载失败不阻塞页面 */ }
  }
  await refresh()
}

/* ---------- 交互 ---------- */
function setMode(m) {
  if (mode.value === m) return
  mode.value = m
  tipRow.value = null
  paint()
  renderCharts()          // 口径切换 → 三张图同步换值
}

async function switchTab(t) {
  if (tab.value === t) return
  tab.value = t
  varOpen.value = false
  detail.value = null
  drillCity.value = ''
  tipRow.value = null
  if (t === 'var') await loadVarieties()
  else await refresh()
  await nextTick()
  if (mapInst && mapInst.resize) mapInst.resize()
  await renderCharts()    // 选定品种时第三张图切气泡
}

async function selectVariety(v) {
  varSel.value = v
  varOpen.value = false
  varKw.value = ''
  detail.value = null
  drillCity.value = ''
  tipRow.value = null
  await refresh()
  await renderCharts()
}

/** 点击省份 → 下钻到该省城市视图 */
async function pickProvince(name) {
  const p = (ov.value.provinces || []).find(x => x.province === name)
  if (!p) return
  if (!cityGeoData) {
    try {
      await loadCityGeo()
    } catch (e) {
      errMsg.value = '市级边界数据加载失败，请稍后重试'
      return
    }
  }
  drillProv.value = name
  drillProvFull.value = p.provinceFull || name
  drillCity.value = ''
  detail.value = null
  tipRow.value = null
  await loadCities()
  paint()
  zoomTo(p.lng, p.lat, 7)
  await loadMatrix()      // 矩阵随下钻换成「城市 × 品种」
  await renderCharts()
}

/** 返回全国（保留当前页签与品种） */
async function backToNation() {
  if (!isDrill.value) return
  drillProv.value = ''
  drillProvFull.value = ''
  drillCity.value = ''
  detail.value = null
  tipRow.value = null
  cities.value = []
  cityMeta.value = { totalPoints: 0, totalRecords: 0, totalCities: 0 }
  paint()
  zoomTo(108.5, 34.5, 4.2)
  await loadMatrix()      // 回到「省份 × 品种」
  await renderCharts()
}

/** 点击城市 → 侧栏列出该市报价点明细 */
async function pickCity(cityFullName) {
  drillCity.value = cityFullName
  tipRow.value = (cities.value || []).find(x => x.city === cityFullName) || null
  paint()
  detail.value = { city: cityFullName, totalPoints: 0, totalRecords: 0, points: [] }
  try {
    const res = await api.get('/enterprise-map/points/detail'
      + qs({ province: drillProv.value, city: cityFullName, vid: scopeVid.value }))
    if (res && res.code === 200) detail.value = res.data || detail.value
  } catch (e) { /* 明细失败不影响地图 */ }
}

function closeCity() {
  drillCity.value = ''
  detail.value = null
  paint()
}

function onRowClick(row) {
  if (isDrill.value) pickCity(row.key)
  else pickProvince(row.key)
}

/* ---------- 生命周期 ---------- */
async function load() {
  state.value = 'loading'
  try {
    const acc = await api.get('/enterprise-map/access')
    const a = acc && acc.data ? acc.data : {}
    emapExpired.value = !!a.expired
    emapExpireDate.value = a.expireDate || ''
    emapDaysLeft.value = (a.daysLeft === 0 || a.daysLeft) ? a.daysLeft : null
    if (!a.enabled) { state.value = 'denied'; return }

    await loadOverview()
    state.value = 'ready'
    await nextTick()
    await ensureMap()
    await loadMatrix()
    await renderCharts()
    window.addEventListener('resize', onWinResize)
  } catch (e) {
    const code = e && e.response && e.response.status
    if (code === 403) state.value = 'denied'
    else { errMsg.value = (e && e.message) || '网络异常'; state.value = 'error' }
  }
}

// 点击空白收起品种下拉
function onDocClick(e) {
  if (varOpen.value && !(e.target instanceof Element && e.target.closest('.var-picker'))) {
    varOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  load()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  window.removeEventListener('resize', onWinResize)
  if (topChart && !topChart.isDisposed()) topChart.dispose()
  if (paretoChart && !paretoChart.isDisposed()) paretoChart.dispose()
  if (crossChart && !crossChart.isDisposed()) crossChart.dispose()
  topChart = paretoChart = crossChart = null
  if (mapInst) { try { mapInst.destroy() } catch (e) {} mapInst = null }
  fillLayer = null
})
</script>

<style scoped>
.emap-page { min-height: 100%; }

.back-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.page-title { font-size: 16px; font-weight: 600; color: #111827; }
.bar-note { font-size: 11.5px; color: #52525b; }

/* 面包屑 */
.badge-emap { background: #eef2ff; color: #4338ca; font-weight: 500; }
.badge-emap-soon { background: #fef3c7; color: #b45309; }
.crumbs { display: flex; align-items: center; gap: 6px; font-size: 12.5px; }
.crumbs a { color: #2563eb; cursor: pointer; text-decoration: none; }
.crumbs a:hover { text-decoration: underline; }
.crumbs a.cur { color: #9ca3af; cursor: default; }
.crumbs a.cur:hover { text-decoration: none; }
.cb-sep { color: #d1d5db; font-style: normal; }
.crumbs b { color: #111827; font-weight: 600; }

/* 页签 */
.ptabs { display: flex; gap: 8px; margin-bottom: 18px; flex-wrap: wrap; }
.ptab {
  display: flex; flex-direction: column; gap: 1px; align-items: flex-start;
  padding: 9px 18px; border-radius: 10px; cursor: pointer; font-family: inherit;
  border: 1px solid #e4e4e7; background: rgba(255,255,255,.6); transition: all .15s;
  text-align: left;
}
.ptab:hover { border-color: #d1d5db; background: #fff; }
.ptab.on { background: #111827; border-color: #111827; }
.pt-n { font-size: 13px; font-weight: 600; color: #374151; }
.pt-d { font-size: 10.5px; color: #9ca3af; }
.ptab.on .pt-n { color: #fff; }
.ptab.on .pt-d { color: rgba(255,255,255,.6); }

.kpi-row { display: flex; gap: 12px; margin-bottom: 20px; flex-wrap: wrap; }
.kpi-card {
  flex: 1; min-width: 140px;
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%); -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px; padding: 16px;
}
.kpi-label { font-size: 12px; color: #6b7280; margin-bottom: 4px; }
.kpi-value { font-size: 22px; font-weight: 700; color: #111827; letter-spacing: -0.02em; }
.kpi-sub { font-size: 10px; color: #71717a; margin-top: 2px; }

.chart-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%); -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 14px;
  /* ⛔ 不能用 overflow:hidden —— 否则地图卡头里的品种下拉被裁掉。 */
  overflow: visible;
}
.card-header {
  padding: 14px 18px; border-bottom: 1px solid #f3f4f6;
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  /* ⭐ 关键：让卡头成为高于地图体的层叠上下文，否则腾讯地图 SDK
     在 .emap-canvas 内建的高 z-index 层会盖住 .var-drop，导致下拉项点不中。 */
  position: relative; z-index: 20;
  border-radius: 14px 14px 0 0;
}
.card-header h3 { font-size: 14px; font-weight: 600; color: #111827; margin: 0; }
.ch-tt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.ch-hint { font-size: 11.5px; font-weight: 400; color: #52525b; line-height: 1.5; }
.ch-hint b { color: #111827; }
.hd-right { display: flex; align-items: center; gap: 8px; flex: none; }

.emap-grid { display: grid; grid-template-columns: 1fr 372px; gap: 16px; align-items: start; }

.map-body {
  position: relative; padding: 0;
  /* 地图体恒在卡头之下，保证下拉可点击 */
  z-index: 1;
  border-radius: 0 0 14px 14px; overflow: hidden;
}
.emap-canvas { width: 100%; height: 560px; background: #eef2f7; }

.seg { display: flex; gap: 6px; flex-shrink: 0; }
.tab {
  padding: 5px 12px; border-radius: 7px; font-size: 12px; cursor: pointer;
  border: 1px solid #e4e4e7; background: #fff; color: #6b7280; transition: all .15s;
  font-family: inherit;
}
.tab:hover { border-color: #d1d5db; color: #111827; }
.tab.on { background: #111827; border-color: #111827; color: #fff; }

/* 品种选择器 */
.var-picker { position: relative; display: flex; gap: 6px; flex-shrink: 0; }
.var-input {
  width: 130px; padding: 5px 10px; border-radius: 7px; font-size: 12px;
  border: 1px solid #e4e4e7; background: #fff; color: #111827;
  font-family: inherit; outline: none;
}
.var-input:focus { border-color: #93c5fd; }
.var-cur {
  display: flex; align-items: center; gap: 5px;
  padding: 5px 12px; border-radius: 7px; font-size: 12px; cursor: pointer;
  border: 1px solid #111827; background: #111827; color: #fff;
  font-family: inherit; white-space: nowrap; max-width: 160px;
}
.caret {
  width: 0; height: 0; flex: none;
  border-left: 3.5px solid transparent; border-right: 3.5px solid transparent;
  border-top: 4px solid rgba(255,255,255,.75);
}
.var-drop {
  position: absolute; right: 0; top: calc(100% + 5px); z-index: 200;
  width: 240px; max-height: 288px; overflow-y: auto;
  background: #fff; border: 1px solid #e4e4e7; border-radius: 10px;
  box-shadow: 0 10px 30px rgba(31,41,55,.16); padding: 5px;
}
.var-drop::-webkit-scrollbar { width: 7px; }
.var-drop::-webkit-scrollbar-thumb { background: rgba(107,114,128,.3); border-radius: 4px; }
.var-item {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 7px 10px; border-radius: 7px; cursor: pointer; transition: background .12s;
}
.var-item:hover { background: rgba(59,130,246,.08); }
.var-item.on { background: rgba(59,130,246,.14); }
.vi-n { font-size: 12.5px; color: #111827; font-weight: 520; }
.vi-m { font-size: 10.5px; color: #9ca3af; flex: none; font-variant-numeric: tabular-nums; }
.var-empty { padding: 14px; text-align: center; font-size: 12px; color: #9ca3af; }

.map-mask {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  background: rgba(238,242,247,.86); font-size: 13px; color: #6b7280;
  /* 必须 > 1000：同 .map-back，否则加载中地图仍可被点到（TMap 覆盖层在 1000） */
  z-index: 1002;
}

/* 悬浮信息放左上，位于「返回全国」按钮之下，避开右上角缩放控件。
   ⛔ pointer-events:none 必须加 —— 否则悬浮卡会挡住它下方地图的点击（省份点不中）。 */
.map-tip {
  position: absolute; left: 14px; top: 58px;
  background: rgba(255,255,255,.97); border: 1px solid #e4e4e7;
  border-radius: 10px; padding: 12px 16px; min-width: 210px;
  box-shadow: 0 6px 24px rgba(31,41,55,.14);
  pointer-events: none;
}
.map-tip h4 {
  font-size: 14px; font-weight: 660; color: #111827; margin: 0 0 8px;
  padding-bottom: 7px; border-bottom: 1px solid #f3f4f6;
}
.map-tip .tr {
  display: flex; justify-content: space-between; gap: 18px;
  font-size: 12px; margin-bottom: 5px; white-space: nowrap;
}
.map-tip .tr span { color: #6b7280; }
.map-tip .tr b { color: #111827; font-weight: 560; font-variant-numeric: tabular-nums; }

/* 图例放右下，避开腾讯地图 logo。
   ⛔ pointer-events:none 必须加 —— 否则图例会挡住它下方地图的点击（如海南/云南等）。 */
.map-legend {
  position: absolute; right: 14px; bottom: 14px;
  background: rgba(255,255,255,.95); border: 1px solid #e4e4e7;
  border-radius: 9px; padding: 10px 12px; min-width: 108px;
  pointer-events: none;
}
.lg-tt { font-size: 11px; color: #6b7280; margin-bottom: 6px; }
.lg-row { display: flex; align-items: center; gap: 7px; font-size: 11px; color: #52525b; margin-bottom: 3px; white-space: nowrap; }
.lg-row i { width: 14px; height: 10px; border-radius: 2.5px; flex: none; }
.lg-none { margin-top: 4px; padding-top: 4px; border-top: 1px solid #f3f4f6; }
.none-box { background: #e5e7eb; border: 1px solid #d1d5db; }

/* 返回按钮放【左上角】。
   ⛔ 不能放左下角 —— 实测腾讯地图的比例尺控件固定在左下 (5,501)~(85,531)，会拦截点击。
   ⛔ z-index 必须 > 1000 —— 实测 TMap 用「770x560 全覆盖 + 内联 z-index:1000」的容器
      包住整个地图（canvas 本身只有 z-index:2，但那个覆盖层在所有地图内容之上），
      按钮设 6 会被 elementFromPoint 判为被遮挡、真指针点不中。
      1001 即可稳压整个 TMap 层；且 .map-body 自身是 z-index:1 的层叠上下文，
      再大也不会盖住 z-index:20 的 .card-header（品种下拉仍可点）。 */
.map-back {
  position: absolute; left: 14px; top: 14px; z-index: 1001;
  padding: 6px 14px; border-radius: 8px; font-size: 12px; cursor: pointer;
  border: 1px solid #d1d5db; background: rgba(255,255,255,.97); color: #374151;
  font-family: inherit; box-shadow: 0 4px 14px rgba(31,41,55,.14);
}
.map-back:hover { border-color: #9ca3af; color: #111827; }

.side-card { display: flex; flex-direction: column; }
.side-body { max-height: 560px; overflow-y: auto; padding: 8px 10px 12px; }
.side-body::-webkit-scrollbar { width: 7px; }
.side-body::-webkit-scrollbar-thumb { background: rgba(107,114,128,.3); border-radius: 4px; }

.prow {
  display: grid; grid-template-columns: 22px 78px 1fr 64px;
  align-items: center; gap: 8px; padding: 6px 8px; border-radius: 7px;
  cursor: pointer; transition: background .12s;
}
.prow:hover { background: rgba(59,130,246,.07); }
.prow.act { background: rgba(59,130,246,.13); }
.rk { font-size: 11px; color: #a1a1aa; text-align: right; font-variant-numeric: tabular-nums; }
.rn { min-width: 0; display: flex; flex-direction: column; }
.rn-n { font-size: 12.5px; color: #111827; font-weight: 520; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rn-s { font-size: 9.5px; color: #a1a1aa; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rb { height: 6px; background: #eef0f3; border-radius: 3px; overflow: hidden; }
.rb i { display: block; height: 100%; background: linear-gradient(90deg, #93c5fd, #1d4ed8); border-radius: 3px; }
.rv { font-size: 12px; color: #374151; text-align: right; font-variant-numeric: tabular-nums; }

.dnote {
  font-size: 11.5px; color: #52525b; line-height: 1.65;
  background: #f8fafc; border: 1px solid #eef0f3; border-radius: 8px;
  padding: 9px 11px; margin: 4px 4px 10px;
}
.dnote b { color: #111827; }
.empty-tip { padding: 26px 12px; text-align: center; font-size: 12px; color: #9ca3af; }
.ecard {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 8px; border: 1px solid #f1f2f4;
  margin-bottom: 6px; background: #fff;
}
.ec-main { min-width: 0; flex: 1; }
.ec-n { font-size: 12.5px; color: #111827; font-weight: 520; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ec-m { font-size: 10.5px; color: #9ca3af; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ec-r { font-size: 12px; color: #374151; flex: none; font-variant-numeric: tabular-nums; }
.badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 6px;
  font-size: 11px; font-weight: 500; border: 1px solid transparent; white-space: nowrap; flex: none; }
.badge-m { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.badge-i { background: #fef3c7; color: #92400e; border-color: #fde68a; }
.badge-n { background: #f1f5f9; color: #64748b; border-color: #e2e8f0; }

.an-btn {
  padding: 5px 12px; border-radius: 7px; background: #fff; border: 1px solid #e4e4e7;
  font-size: 12px; color: #374151; cursor: pointer; transition: all .15s; font-family: inherit;
  flex: none;
}
.an-btn:hover { border-color: #d1d5db; color: #111827; }

/* ==================== 图表区 ==================== */
.chart-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 16px;
  margin-top: 16px; align-items: start;
}
.wide-card { margin-top: 16px; }
.chart-body { position: relative; padding: 6px 10px 10px; }
.emap-chart { width: 100%; height: 300px; }
.emap-chart.tall { height: 432px; }
.chart-mask {
  position: absolute; inset: 0; z-index: 3; display: flex;
  align-items: center; justify-content: center; background: rgba(255,255,255,.7);
}

@media (max-width: 1080px) {
  .chart-grid { grid-template-columns: 1fr; }
  .emap-chart { height: 260px; }
  .emap-chart.tall { height: 340px; }
}

.foot-note { font-size: 11px; color: #71717a; line-height: 1.7; margin: 14px 2px 0; }
.foot-note b { color: #52525b; }

.emap-deny, .emap-loading {
  max-width: 560px; margin: 60px auto; text-align: center;
  border-radius: 14px; padding: 40px 34px;
}
.emap-deny h2 { font-size: 16px; color: #111827; margin: 14px 0 10px; }
.emap-deny p { font-size: 13px; color: #52525b; line-height: 1.8; margin-bottom: 8px; }
.emap-deny b { color: #111827; }
.dn-sub { font-size: 12px !important; color: #71717a !important; }
.dn-icon { font-size: 34px; }
.dn-steps {
  text-align: left; max-width: 430px; margin: 16px auto 6px;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px 16px;
}
.dn-step {
  display: flex; align-items: flex-start; gap: 9px;
  font-size: 12.5px; color: #475569; line-height: 1.7; padding: 4px 0;
}
.dn-step + .dn-step { border-top: 1px dashed #e2e8f0; }
.dn-n {
  flex-shrink: 0; width: 18px; height: 18px; margin-top: 2px; border-radius: 50%;
  background: #5ECBB0; color: #fff; font-size: 11px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
/* 文本必须整体作为单个 flex 子项，否则内部 <b> 会各自成为 flex 项导致竖排断行 */
.dn-tx { flex: 1; min-width: 0; }
.emap-btn { padding: 8px 18px; border-radius: 8px; font-size: 12.5px; cursor: pointer;
  border: 1px solid #e4e4e7; background: #fff; color: #374151; margin-top: 14px; font-family: inherit; }
.emap-btn.primary { background: #111827; border-color: #111827; color: #fff; }
.emap-loading { color: #6b7280; font-size: 13px; }
.sp {
  width: 26px; height: 26px; margin: 0 auto 12px; border-radius: 50%;
  border: 2.5px solid #e5e7eb; border-top-color: #3b82f6; animation: sp 0.8s linear infinite;
}
@keyframes sp { to { transform: rotate(360deg); } }

@media (max-width: 1080px) {
  .emap-grid { grid-template-columns: 1fr; }
  .emap-canvas { height: 420px; }
  .side-body { max-height: 420px; }
}
</style>
