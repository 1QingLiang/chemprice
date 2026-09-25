<template>
  <div class="commodity-detail" v-if="commodity">
    <!-- 顶部商品信息 -->
    <section class="header">
      <div class="header-content">
        <div style="display:flex;align-items:center;gap:10px;margin-bottom:6px">
          <button @click="$router.back()" style="display:flex;align-items:center;gap:4px;padding:6px 12px;border-radius:8px;background:var(--ink5);border:1px solid var(--border);font-size:12px;color:var(--ink2);cursor:pointer;font-family:inherit;transition:all .15s">
            <ChevronLeft :size="14" /> 返回商品中心
          </button>
        </div>
        <h1 class="commodity-name">{{ commodity.name }}</h1>
        <div class="price-cards">
          <div class="price-card market">
            <div class="price-label">市场价格</div>
            <div class="price-point" :title="pointLabel(marketPrice)" v-if="pointLabel(marketPrice)">{{ pointLabel(marketPrice) }}</div>
            <div class="price-value n">{{ fmtPrice(marketPrice) }}</div>
            <div class="price-unit">{{ marketPrice?.unit_valuation_name || '元/吨' }}</div>
            <div class="price-note">
              <span class="note-date" v-if="marketPrice">{{ marketPrice.data_date }}</span>
              <span class="note-intraday" v-if="isTodayDate(marketPrice?.data_date)">盘中</span>
              <span class="note-rate" :class="badgeCls(marketPrice || {})" v-if="marketPrice">{{ rateText(marketPrice) }}</span>
            </div>
          </div>
          <div class="price-card enterprise">
            <div class="price-label">企业价格</div>
            <div class="price-point" :title="pointLabel(enterprisePrice)" v-if="pointLabel(enterprisePrice)">{{ pointLabel(enterprisePrice) }}</div>
            <div class="price-value n">{{ fmtPrice(enterprisePrice) }}</div>
            <div class="price-unit">{{ enterprisePrice?.unit_valuation_name || '元/吨' }}</div>
            <div class="price-note">
              <span class="note-date" v-if="enterprisePrice">{{ enterprisePrice.data_date }}</span>
              <span class="note-intraday" v-if="isTodayDate(enterprisePrice?.data_date)">盘中</span>
              <span class="note-rate" :class="badgeCls(enterprisePrice || {})" v-if="enterprisePrice">{{ rateText(enterprisePrice) }}</span>
            </div>
          </div>
          <div class="price-card international" v-if="internationalPrice">
            <div class="price-label">国际价格</div>
            <div class="price-point" :title="pointLabel(internationalPrice)" v-if="pointLabel(internationalPrice)">{{ pointLabel(internationalPrice) }}</div>
            <div class="price-value n">{{ fmtPrice(internationalPrice) }}</div>
            <div class="price-unit">{{ internationalPrice?.unit_valuation_name || '美元/吨' }}</div>
            <div class="price-note">
              <span class="note-date" v-if="internationalPrice">{{ internationalPrice.data_date }}</span>
              <span class="note-intraday" v-if="isTodayDate(internationalPrice?.data_date)">盘中</span>
              <span class="note-rate" :class="badgeCls(internationalPrice || {})" v-if="internationalPrice">{{ rateText(internationalPrice) }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Tab 切换 -->
    <section class="tabs-section">
      <div class="tabs">
        <button 
          v-for="tab in tabs" 
          :key="tab.key"
          class="tab"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >
          <span class="badge" :class="tab.badge">{{ tab.label }}</span>
          {{ tab.name }}
        </button>
      </div>
    </section>

    <!-- 筛选器面板 -->
    <section class="filter-panel" v-if="availableMarkets.length">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
            {{ activeTab === 'market' ? '报价地区' : activeTab === 'enterprise' ? '企业名称' : '国际来源' }}
          </span>
          <el-select
            v-model="selectedMarkets"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="全部"
            class="filter-w-xl"
          >
            <el-option v-for="m in availableMarkets" :key="m" :value="m" :label="m" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button @click="selectedMarkets = []" size="small">清空选择</el-button>
          <el-tag v-if="selectedMarkets.length" type="info" size="small">已选 {{ selectedMarkets.length }} 项</el-tag>
        </div>
      </div>
    </section>

    <!-- 月度走势 + 日度走势 并排 -->
    <section class="section">
      <div class="trend-grid">
        <div class="card">
          <div class="card-header">
            <div class="ch-tt"><h3>月均价走势</h3><span class="ch-hint">口径：近 12 个月按月平均中间价（不区分报价点），跟随当前价格类型</span></div>
          </div>
          <div class="chart-container">
            <div ref="monthlyChartRef" class="chart"></div>
          </div>
        </div>
        <div class="card">
        <div class="card-header">
          <div class="ch-tt"><h3>近期走势{{ selectedMarkets.length === 1 ? ' · 最高价' : '' }}</h3><span class="ch-hint">口径：近 7 天按日聚合 —— 选 1 个报价点取当日最高价，多选或全部取日均价</span></div>
          </div>
          <div class="chart-container">
            <div ref="dailyChartRef" class="chart"></div>
          </div>
        </div>
      </div>
    </section>

    <!-- 区域报价 -->
    <section class="section" v-if="currentPrices.length">
      <div class="card">
        <div class="card-header">
          <h3>{{ activeTab === 'market' ? '市场报价（按地区）' : activeTab === 'enterprise' ? '企业报价（按企业）' : '国际报价（按国家）' }}</h3>
        </div>
        <div class="bar-chart">
          <div 
            v-for="(item, i) in currentPrices.slice(0, 10)" 
            :key="i" 
            class="bar-item"
          >
            <div class="bar-label">
              {{ item.market_name }}
              <span v-if="item.specifications_name" class="spec-tag">{{ item.specifications_name }}</span>
            </div>
            <div class="bar-track">
              <div 
                class="bar-fill" 
                :style="{ width: getBarWidth(item.middle_price) + '%' }"
              ></div>
            </div>
            <div class="bar-value n">{{ fmtNum(item.middle_price) }}</div>
            <div class="bar-unit">{{ item.unit_valuation_name || '元/吨' }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 数据表格 -->
    <section class="section">
      <div class="card">
        <div class="card-header">
          <h3>{{ activeTab === 'market' ? '市场价格' : activeTab === 'enterprise' ? '企业价格' : '国际价格' }}数据</h3>
        </div>
        <div class="table-container">
          <table class="table">
            <thead>
              <tr>
                <th>商品</th>
                <th>报价点</th>
                <th>规格</th>
                <th>主流价</th>
                <th>最低价</th>
                <th>最高价</th>
                <th>涨跌</th>
                <th>数据日</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in currentPrices" :key="i">
                <td class="font-semibold">{{ item.varieties_name }}</td>
                <td>{{ item.market_name }}</td>
                <td>{{ item.specifications_name || '—' }}</td>
                <td class="n">{{ fmtNum(item.middle_price) }}</td>
                <td class="n">{{ fmtNum(item.low_price) }}</td>
                <td class="n">{{ fmtNum(item.high_price) }}</td>
                <td>
                  <span class="badge" :class="badgeCls(item)">{{ rateText(item) }}</span>
                </td>
                <td>{{ item.data_date }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch as vueWatch } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronLeft } from 'lucide-vue-next'
import { getCommodities, getPrices, getDashboardTrend } from '../api/index'
import * as echarts from 'echarts'
import gsap from 'gsap'

const route = useRoute()
const commodity = ref(null)
const marketPrices = ref([])
const enterprisePrices = ref([])
const internationalPrices = ref([])
const activeTab = ref('market')

const monthlyChartRef = ref(null)
const dailyChartRef = ref(null)
let monthlyChart = null
let dailyChart = null

// 筛选器状态
const selectedMarkets = ref([])
const availableMarkets = computed(() => {
  const prices = currentPrices.value
  return [...new Set(prices.map(p => p.market_name).filter(Boolean))].sort()
})

// 日期筛选
const today = new Date().toISOString().slice(0, 10)
const threeDaysAgo = new Date(Date.now() - 2 * 86400000).toISOString().slice(0, 10)
// monthly | daily

const tabs = [
  { key: 'market', name: '市场价格', label: 'M', badge: 'badge-m' },
  { key: 'enterprise', name: '企业价格', label: 'E', badge: 'badge-e' },
  { key: 'international', name: '国际价格', label: 'I', badge: 'badge-i' }
]

const marketPrice = computed(() => marketPrices.value[0] || null)
const enterprisePrice = computed(() => enterprisePrices.value[0] || null)
const internationalPrice = computed(() => internationalPrices.value[0] || null)

const currentPrices = computed(() => {
  let data = []
  switch (activeTab.value) {
    case 'market': data = marketPrices.value; break
    case 'enterprise': data = enterprisePrices.value; break
    case 'international': data = internationalPrices.value; break
    default: data = []
  }
  if (selectedMarkets.value.length > 0) {
    data = data.filter(p => selectedMarkets.value.includes(p.market_name))
  }
  return data
})

const regionPrices = computed(() => marketPrices.value.slice(0, 10))

function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }
function fmtPrice(p) { return p?.middle_price != null ? fmtNum(p.middle_price) : '—' }
function isTodayDate(d) {
  if (!d) return false
  const n = new Date()
  const t = n.getFullYear() + '-' + String(n.getMonth() + 1).padStart(2, '0') + '-' + String(n.getDate()).padStart(2, '0')
  return String(d).slice(0, 10) === t
}
function pointLabel(p) {
  if (!p) return ''
  return [p.market_name, p.specifications_name].filter(Boolean).join(' · ')
}
function rateText(p) {
  if (p.data_rate && p.data_rate !== '') return p.data_rate
  const v = Number(p.data_rise_or_fall || 0)
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
function badgeCls(p) {
  const v = Number(p.data_rise_or_fall || 0)
  return v > 0 ? 'badge-up' : v < 0 ? 'badge-dn' : 'badge-fl'
}

function getBarWidth(price) {
  if (!price || !currentPrices.value.length) return 0
  const prices = currentPrices.value.slice(0, 10).map(p => Number(p.middle_price) || 0)
  const max = Math.max(...prices)
  return max > 0 ? (Number(price) / max) * 100 : 0
}

// ===== 走势图 =====
function getChartColor(type) {
  if (type === 'enterprise') return '#a855f7'
  if (type === 'international') return '#f59e0b'
  return '#3b82f6'
}

function renderChart(domRef, chartInstance, xData, yData, type, label) {
  if (!domRef) return chartInstance
  const color = getChartColor(type)
  if (!chartInstance) chartInstance = echarts.init(domRef)
  chartInstance.setOption({
    color: [color],
    grid: { left: 50, right: 15, top: 15, bottom: 25 },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#e5e7eb', textStyle: { color: '#111827', fontSize: 11 } },
    xAxis: { type: 'category', data: xData, axisLine: { lineStyle: { color: '#e5e7eb' } }, axisLabel: { color: '#6b7280', fontSize: 10 } },
    yAxis: { type: 'value', scale: true, axisLine: { show: false }, splitLine: { lineStyle: { color: '#f3f4f6' } }, axisLabel: { color: '#6b7280', fontSize: 10 } },
    series: [{
      name: label, type: 'line', data: yData, smooth: 0.1, symbol: 'circle', symbolSize: 4,
      lineStyle: { width: 2, color }, itemStyle: { color },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: color + '26' }, { offset: 1, color: color + '00' }
        ])
      }
    }]
  }, true)
  return chartInstance
}

async function loadTrend() {
  const id = route.params.id
  const type = activeTab.value
  const singleMode = selectedMarkets.value.length === 1
  const filterSet = new Set(selectedMarkets.value)

  // 月度：走 trend 接口（独立 SQL，按 tableType 查对应表），窗口=最近约12个月动态（不写死起始日，防超13个月后走势图丢最新月份）
  try {
    const endDate = new Date().toISOString().slice(0, 10)
    const startDate = new Date(Date.now() - 365 * 86400000).toISOString().slice(0, 10)
    const res = await getDashboardTrend({ varietiesId: id, startDate, endDate, tableType: type })
    if (res.code === 200 && res.data?.length) {
      const months = res.data.map(d => d.month)
      const prices = res.data.map(d => d.avgPrice)
      await nextTick()
      monthlyChart = renderChart(monthlyChartRef.value, monthlyChart, months, prices, type, '月均价')
    }
  } catch (e) { console.error(e) }

  // 日度：以最近有数据的日期为终点，往前 7 天（避免最新数据非当日时图空白）
  const allDates = currentPrices.value.map(p => p.data_date).filter(Boolean).sort()
  const latestAvail = allDates.length ? allDates[allDates.length - 1] : new Date().toISOString().slice(0, 10)
  const ed = latestAvail
  const sd = new Date(new Date(latestAvail).getTime() - 6 * 86400000).toISOString().slice(0, 10)
  try {
    const tableType = type === 'international' ? 'international' : type
    const res = await getPrices({ page: 1, size: 500, tableType, varietiesId: id, startDate: sd, endDate: ed })
    if (res.code === 200) {
      let rows = res.data?.data || []
      // 根据筛选器过滤
      if (filterSet.size > 0) {
        rows = rows.filter(r => filterSet.has(r.market_name))
      }
      const dateMap = {}
      rows.forEach(r => {
        const d = r.data_date; const p = Number(r.middle_price)
        if (!d || isNaN(p)) return
        if (!dateMap[d]) dateMap[d] = { vals: [] }
        dateMap[d].vals.push(p)
      })
      const sorted = Object.entries(dateMap).sort((a, b) => a[0].localeCompare(b[0]))
      const days = sorted.map(([d]) => d.slice(5))
      // 单一来源用最高价，多来源或全量用均价
      const prices = sorted.map(([, v]) => {
        if (singleMode) return Math.max(...v.vals)
        const sum = v.vals.reduce((a, b) => a + b, 0)
        return Math.round(sum / v.vals.length)
      })
      const label = singleMode ? '最高价' : '日均价'
      await nextTick()
      dailyChart = renderChart(dailyChartRef.value, dailyChart, days, prices, type, label)
    }
  } catch (e) { console.error(e) }
}

vueWatch(activeTab, () => { loadAll() })
vueWatch(selectedMarkets, () => { loadTrend() })

// 加载所有数据（三维度表格 + 走势图）
async function loadAll() {
  const id = route.params.id
  try {
    const params = { page: 1, size: 50, varietiesId: id }
    const [mRes, eRes, iRes] = await Promise.all([
      getPrices({ ...params, tableType: 'market' }),
      getPrices({ ...params, tableType: 'enterprise' }),
      getPrices({ ...params, tableType: 'international' })
    ])
    if (mRes.code === 200) marketPrices.value = mRes.data?.data || []
    if (eRes.code === 200) enterprisePrices.value = eRes.data?.data || []
    if (iRes.code === 200) internationalPrices.value = iRes.data?.data || []
    // ⭐ 自动切到「有数据」的价格类型：
    //    部分品种上游只提供企业价（如 2-丙基庚醇），若默认停在「市场价格」，
    //    下方报价表与走势图会全空，用户会误判成"该品种没有数据"。
    const hasData = {
      market: marketPrices.value.length > 0,
      enterprise: enterprisePrices.value.length > 0,
      international: internationalPrices.value.length > 0
    }
    if (!hasData[activeTab.value]) {
      const firstWithData = tabs.find(t => hasData[t.key])
      if (firstWithData) activeTab.value = firstWithData.key
    }
    await nextTick()
    loadTrend()
  } catch (e) { console.error('加载失败:', e) }
}

onMounted(async () => {
  const id = route.params.id
  try {
    const cRes = await getCommodities()
    if (cRes.code === 200) commodity.value = (cRes.data || []).find(c => c.varietiesId == id)
    await loadAll()
  } catch (e) { console.error('加载失败:', e) }
})

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    gsap.fromTo('.header', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out' })
    gsap.fromTo('.section', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', stagger: 0.08, delay: 0.1 })
  })
})

onUnmounted(() => {
  ctx && ctx.revert()
  monthlyChart && monthlyChart.dispose()
  dailyChart && dailyChart.dispose()
})

window.addEventListener('resize', () => {
  monthlyChart && monthlyChart.resize()
  dailyChart && dailyChart.resize()
})
</script>

<style scoped>
.commodity-detail {
  padding: 0;
}

.header {
  background: linear-gradient(135deg, #1e3a5f 0%, #0f172a 100%);
  border-radius: 20px;
  padding: 40px;
  margin-bottom: 32px;
  position: relative;
  overflow: hidden;
}

.header::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -20%;
  width: 500px;
  height: 500px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.15) 0%, transparent 70%);
  pointer-events: none;
}

.header-content {
  position: relative;
  z-index: 1;
}

.commodity-name {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: white;
  margin: 0 0 24px 0;
}

.price-cards {
  display: flex;
  gap: 16px;
}

.price-card {
  background: rgba(255,255,255,0.1);
  border-radius: 12px;
  padding: 20px;
  min-width: 160px;
  backdrop-filter: blur(10px);
}

.price-card.market { border-left: 3px solid #3b82f6; }
.price-card.enterprise { border-left: 3px solid #a855f7; }
.price-card.international { border-left: 3px solid #f59e0b; }

.price-label {
  font-size: 12px;
  color: rgba(255,255,255,0.6);
  margin-bottom: 8px;
}

.price-point {
  font-size: 10px;
  color: rgba(255,255,255,0.5);
  margin-top: -4px;
  margin-bottom: 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.price-value {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: white;
  margin-bottom: 4px;
}

.price-unit {
  font-size: 11px;
  color: rgba(255,255,255,0.5);
}
.price-note { display: flex; gap: 8px; align-items: center; margin-top: 6px; }
.price-note .note-intraday { font-size: 10px; color: #fbbf24; border: 1px solid rgba(251,191,36,0.5); border-radius: 4px; padding: 0 4px; line-height: 14px; }
.price-note .note-date { font-size: 10px; color: rgba(255,255,255,0.4); }
.price-note .note-rate { font-size: 11px; font-weight: 600; padding: 1px 6px; border-radius: 4px; }
.price-note .note-rate.badge-up { background: rgba(239,68,68,0.2); color: #fca5a5; }
.price-note .note-rate.badge-dn { background: rgba(34,197,94,0.2); color: #86efac; }
.price-note .note-rate.badge-fl { background: rgba(255,255,255,0.1); color: rgba(255,255,255,0.5); }

.tabs-section {
  margin-bottom: 16px;
  padding: 12px 16px;
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px;
}

.filter-panel {
  margin-bottom: 20px;
  padding: 12px 16px;
  background: #fafbfc;
  border-radius: 10px;
  border: 1px solid #e4e4e7;
}
.filter-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.filter-item { display: flex; align-items: center; gap: 8px; }
.filter-label { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 500; color: #6b7280; white-space: nowrap; }
.filter-w-xl { width: 320px; }
.filter-actions { display: flex; align-items: center; gap: 8px; }

.tabs {
  display: flex;
  gap: 8px;
}

.tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--card);
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s;
}

.tab:hover {
  border-color: #d1d5db;
  color: #374151;
}

.tab.active {
  background: #111827;
  border-color: #111827;
  color: white;
}

.section {
  margin-bottom: 24px;
}

.trend-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.trend-grid .card {
  min-width: 0;
}

.card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 16px;
  overflow: hidden;
}

.card-header {
  padding: 20px 24px;
  border-bottom: 1px solid #f3f4f6;
}

.card-header h3 {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  margin: 0;
}

.card-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f4f4f5; }
.card-header h3 { font-size: 14px; font-weight: 600; margin: 0; }
.trend-mode-tabs { display: flex; gap: 4px; }
/* 图表口径小字（2026-09-17）*/
.ch-tt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.ch-hint { font-size: 11.5px; font-weight: 400; color: #52525b; line-height: 1.5; }
.mode-tab { padding: 4px 12px; border-radius: 16px; border: 1px solid #e4e4e7; background: var(--card); font-size: 11px; font-weight: 500; cursor: pointer; font-family: inherit; color: #71717a; transition: all 0.15s; }
.mode-tab:hover { border-color: #d4d4d8; }
.mode-tab.active { background: #09090b; color: #fff; border-color: #09090b; }

.chart-container {
  padding: 24px;
}

.chart {
  width: 100%;
  height: 300px;
}

.bar-chart {
  padding: 24px;
}

.bar-item {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;
}

.bar-label {
  width: 130px;
  font-size: 13px;
  color: #374151;
  text-align: right;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.spec-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 500;
  background: rgba(99, 102, 241, 0.08);
  color: #6366f1;
  white-space: nowrap;
}

.bar-track {
  flex: 1;
  height: 24px;
  background: #f3f4f6;
  border-radius: 6px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
  border-radius: 6px;
  transition: width 0.3s ease;
}

.bar-value {
  width: 80px;
  font-size: 14px;
  font-weight: 600;
  color: #111827;
  flex-shrink: 0;
}

.bar-unit {
  width: 60px;
  font-size: 11px;
  color: #6b7280;
  flex-shrink: 0;
}

.table-container {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.table th {
  text-align: left;
  padding: 12px 16px;
  font-weight: 500;
  color: #6b7280;
  border-bottom: 1px solid #f3f4f6;
  background: #fafafa;
  font-size: 11px;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

.table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #374151;
}

.table tbody tr:hover {
  background: #f9fafb;
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.badge-m { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.badge-e { background: #f3e8ff; color: #7e22ce; border-color: #e9d5ff; }
.badge-i { background: #fef3c7; color: #92400e; border-color: #fde68a; }
.badge-up { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
.badge-dn { background: #dcfce7; color: #15803d; border-color: #bbf7d0; }
.badge-fl { background: #eceef1; color: #71717a; border-color: #d5d9e0; }

.font-semibold { font-weight: 600; }

.n {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', monospace;
}

@media (max-width: 768px) {
  .header {
    padding: 24px;
  }
  
  .commodity-name {
    font-size: 24px;
  }
  
  .price-cards {
    flex-direction: column;
  }
  
  .price-card {
    min-width: auto;
  }
}
</style>