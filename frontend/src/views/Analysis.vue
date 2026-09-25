<template>
  <div class="analysis-page" v-if="data">
    <!-- 顶部导航 -->
    <div class="back-bar">
      <button class="back-btn" @click="$router.back()">
        <ChevronLeft :size="14" /> 返回
      </button>
      <span class="page-title">{{ data.latest?.varieties_name || '商品' }} · {{ route.query.marketName }} 分析</span>
      <span class="page-type badge" :class="typeBadge">{{ typeLabel }}</span>
    </div>

    <!-- KPI 概览 -->
    <section class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-label">最新价</div>
        <div class="kpi-value n">{{ fmtNum(data.latest?.middle_price) }}</div>
        <div class="kpi-sub">{{ data.latest?.data_date }} · {{ data.latest?.unit_valuation_name || '元/吨' }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近30天均价</div>
        <div class="kpi-value n">{{ fmtNum(data.stats?.avgPrice) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近30天最高</div>
        <div class="kpi-value n text-red">{{ fmtNum(data.stats?.maxPrice) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">近30天最低</div>
        <div class="kpi-value n text-green">{{ fmtNum(data.stats?.minPrice) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">波动率</div>
        <div class="kpi-value n">{{ data.stats?.stdDev ? data.stats.stdDev + ' 元' : '—' }}</div>
      </div>
    </section>

    <!-- 图表区域 -->
    <div class="charts-grid">
      <!-- 月度趋势：均价+最高+最低 -->
      <div class="chart-card span-2">
        <div class="card-header"><div class="ch-tt"><h3>近12月价格趋势</h3><span class="ch-hint">口径：按月汇总 —— 均价为该月中间价平均，最高/最低为该月中间价极值</span></div></div>
        <div class="chart-body"><div ref="trendRef" class="chart"></div></div>
      </div>

      <!-- 涨跌频率饼图 -->
      <div class="chart-card">
        <div class="card-header"><div class="ch-tt"><h3>涨跌频率（近30天）</h3><span class="ch-hint">口径：近 30 天逐日按数据源涨跌字段归类，统计涨/跌/平各多少天</span></div></div>
        <div class="chart-body"><div ref="pieRef" class="chart"></div></div>
      </div>

      <!-- 月度波动幅度 -->
      <div class="chart-card">
        <div class="card-header"><div class="ch-tt"><h3>月度波动幅度</h3><span class="ch-hint">口径：该月最高中间价 − 最低中间价（元/吨），反映当月振幅</span></div></div>
        <div class="chart-body"><div ref="spreadRef" class="chart"></div></div>
      </div>

      <!-- 同商品不同报价点对比 -->
      <div class="chart-card span-2">
        <div class="card-header">
          <div class="ch-tt"><h3>同商品各报价点均价对比（近30天）</h3><span class="ch-hint">口径：该品种在全部报价点近 30 天的中间价平均，按均价降序排列；当前报价点高亮</span></div>
          <span class="peer-count" v-if="peerCount">{{ peerCount }} 个报价点</span>
        </div>
        <div class="chart-body chart-scroll"><div class="chart-inner"><div ref="peerRef" class="chart"></div></div></div>
      </div>

      <!-- 日度板块 -->
      <div class="chart-card span-2">
        <div class="card-header"><div class="ch-tt"><h3>近30天日度价格走势</h3><span class="ch-hint">口径：近 30 天逐日取本报价点的中间价（元/吨）</span></div></div>
        <div class="chart-body"><div ref="dailyLineRef" class="chart"></div></div>
      </div>

      <div class="chart-card span-2">
        <div class="card-header"><div class="ch-tt"><h3>近30天日涨跌</h3><span class="ch-hint">口径：近 30 天逐日的涨跌额（元/吨，数据源口径），红涨绿跌</span></div></div>
        <div class="chart-body"><div ref="dailyBarRef" class="chart"></div></div>
      </div>
    </div>
  </div>

  <!-- 无参数进入 / 加载失败时的兜底（原先是一片空白） -->
  <!-- 加载中：接口较慢，必须给明确反馈 -->
  <div class="an-empty an-loading" v-else-if="loading">
    <div class="an-empty-icon">⏳</div>
    <h3>正在加载分析数据…</h3>
    <p class="an-empty-sub">该接口需要几秒钟，请稍候</p>
  </div>

  <div class="an-empty" v-else>
    <div class="an-empty-icon">📊</div>
    <h3>{{ loadErr ? '数据加载失败' : '缺少分析参数' }}</h3>
    <p v-if="loadErr" class="an-empty-sub">{{ loadErr }}</p>
    <p v-else class="an-empty-sub">
      本页用于查看某个「商品 + 报价点」的价格分析。<br />
      请从<strong>首页的关注商品卡片</strong>，或<strong>商品详情页</strong>点击进入。
    </p>
    <div class="an-empty-btns">
      <button class="an-btn primary" @click="$router.push('/commodities')">去商品中心</button>
      <button class="an-btn" @click="$router.push('/')">回首页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ChevronLeft } from 'lucide-vue-next'
import { getAnalysis } from '../api/index'
import * as echarts from 'echarts'
import gsap from 'gsap'

const route = useRoute()
const data = ref(null)
const loadErr = ref('')
// 分析接口约 4~5 秒才返回：没有加载态时，页面会长时间显示「缺少分析参数」，
// 极易被误读成参数丢失/页面损坏（2026-09-17 实测发现）
const loading = ref(false)
const trendRef = ref(null)
const pieRef = ref(null)
const spreadRef = ref(null)
const peerRef = ref(null)
const dailyLineRef = ref(null)
const dailyBarRef = ref(null)
let trendChart, pieChart, spreadChart, peerChart, dailyLineChart, dailyBarChart

const typeLabel = computed(() => {
  const t = route.query.tableType
  if (t === 'enterprise') return '企业价格'
  if (t === 'international') return '国际价格'
  return '市场价格'
})
const typeBadge = computed(() => {
  const t = route.query.tableType
  if (t === 'enterprise') return 'badge-e'
  if (t === 'international') return 'badge-i'
  return 'badge-m'
})

const peerCount = computed(() => (data.value && data.value.peers) ? data.value.peers.length : 0)

function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }

function initCharts() {
  if (!data.value) return
  const d = data.value

  // 1. 月度趋势（三线：均价+最高+最低）
  if (trendRef.value && d.monthlyTrend?.length) {
    trendChart = echarts.init(trendRef.value)
    trendChart.setOption({
      color: ['#3b82f6', '#ef4444', '#22c55e'],
      tooltip: { trigger: 'axis', confine: true },
      legend: { data: ['均价', '最高', '最低'], bottom: 0, itemGap: 18, textStyle: { fontSize: 11 } },
      grid: { left: 55, right: 20, top: 15, bottom: 45 },
      xAxis: { type: 'category', data: d.monthlyTrend.map(m => m.month) },
      yAxis: { type: 'value', scale: true },
      series: [
        { name: '均价', type: 'line', data: d.monthlyTrend.map(m => m.avgPrice), smooth: true, showSymbol: false },
        { name: '最高', type: 'line', data: d.monthlyTrend.map(m => m.highPrice), lineStyle: { type: 'dashed' }, symbol: 'none' },
        { name: '最低', type: 'line', data: d.monthlyTrend.map(m => m.lowPrice), lineStyle: { type: 'dashed' }, symbol: 'none' }
      ]
    })
  }

  // 2. 涨跌频率饼图
  if (pieRef.value && d.riseFall?.length) {
    pieChart = echarts.init(pieRef.value)
    const colors = { up: '#ef4444', down: '#22c55e', flat: '#9ca3af' }
    const names = { up: '涨', down: '跌', flat: '平' }
    pieChart.setOption({
      color: d.riseFall.map(r => colors[r.dir] || '#9ca3af'),
      tooltip: { trigger: 'item' },
      series: [{
        type: 'pie', radius: ['40%', '70%'],
        label: { formatter: '{b}\n{d}%', fontSize: 12 },
        data: d.riseFall.map(r => ({ value: r.cnt, name: names[r.dir] || r.dir }))
      }]
    })
  }

  // 3. 月度波动幅度柱状图
  if (spreadRef.value && d.monthlyTrend?.length) {
    spreadChart = echarts.init(spreadRef.value)
    spreadChart.setOption({
      color: ['#f59e0b'],
      tooltip: { trigger: 'axis', confine: true },
      grid: { left: 50, right: 15, top: 15, bottom: 25 },
      xAxis: { type: 'category', data: d.monthlyTrend.map(m => m.month) },
      yAxis: { type: 'value' },
      series: [{
        type: 'bar', data: d.monthlyTrend.map(m => m.spread),
        barMaxWidth: 24, itemStyle: { borderRadius: [4, 4, 0, 0] }
      }]
    })
  }

  // 4. 同商品各报价点对比
  if (peerRef.value && d.peers?.length) {
    const n = d.peers.length
    const narrow = (peerRef.value.clientWidth || 800) < 520
    // 高度随报价点数量自适应（每条约 28px），保证 bar 不被压扁、标签不重叠
    peerRef.value.style.height = Math.max(280, n * 28 + 40) + 'px'
    peerChart = echarts.init(peerRef.value)
    const isCurrent = d.peers.map(p => p.market_name === route.query.marketName)
    // 后端已按均价降序；reverse 后让最高价显示在最上方
    const peerRows = d.peers.map((p, i) => ({
      name: p.market_name,
      value: p.avgPrice,
      days: p.days,
      cur: isCurrent[i]
    })).reverse()
    peerChart.setOption({
      tooltip: {
        trigger: 'axis',
        confine: true,
        axisPointer: { type: 'shadow' },
        formatter: ps => {
          const r = peerRows[ps[0]?.dataIndex]
          if (!r) return ''
          return r.name + (r.cur ? '（当前报价点）' : '') +
                 '<br/>近30天均价：' + fmtNum(r.value) + ' 元/吨' +
                 (r.days ? '<br/>覆盖交易日：' + r.days + ' 天' : '')
        }
      },
      grid: { left: narrow ? 74 : 112, right: narrow ? 46 : 60, top: 14, bottom: 14 },
      xAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      yAxis: {
        type: 'category',
        data: peerRows.map(r => r.name),
        axisLabel: { fontSize: 11, interval: 0, width: narrow ? 62 : 100, overflow: 'truncate' }
      },
      series: [{
        type: 'bar',
        data: peerRows.map(r => ({
          value: r.value,
          itemStyle: {
            color: r.cur ? '#3b82f6' : '#d1d5db',
            borderRadius: [0, 4, 4, 0]
          }
        })),
        barMaxWidth: 20,
        label: { show: true, position: 'right', fontSize: 10, formatter: '{c}' }
      }]
    })
  }

  // 5. 日度走势（面积折线）
  if (dailyLineRef.value && d.dailyTrend?.length) {
    dailyLineChart = echarts.init(dailyLineRef.value)
    dailyLineChart.setOption({
      color: ['#3b82f6'],
      tooltip: { trigger: 'axis', confine: true },
      grid: { left: 55, right: 20, top: 15, bottom: 25 },
      xAxis: { type: 'category', data: d.dailyTrend.map(r => r.dt?.slice(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', scale: true },
      series: [{
        type: 'line', data: d.dailyTrend.map(r => r.price), smooth: true, symbol: 'circle', symbolSize: 4,
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(59,130,246,0.2)' }, { offset: 1, color: 'rgba(59,130,246,0)' }
        ])}
      }]
    })
  }

  // 6. 日度涨跌柱状图
  if (dailyBarRef.value && d.dailyTrend?.length) {
    dailyBarChart = echarts.init(dailyBarRef.value)
    const barData = d.dailyTrend.map(r => ({
      value: r.changeAmt || 0,
      itemStyle: { color: (r.changeAmt || 0) > 0 ? '#ef4444' : (r.changeAmt || 0) < 0 ? '#22c55e' : '#d1d5db' }
    }))
    dailyBarChart.setOption({
      tooltip: { trigger: 'axis', formatter: p => p[0]?.name + '<br/>涨跌: ' + (p[0]?.value ?? '—') },
      grid: { left: 55, right: 20, top: 15, bottom: 25 },
      xAxis: { type: 'category', data: d.dailyTrend.map(r => r.dt?.slice(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', scale: true },
      series: [{ type: 'bar', data: barData, barMaxWidth: 16, itemStyle: { borderRadius: [2, 2, 0, 0] } }]
    })
  }
}

function handleResize() {
  trendChart?.resize(); pieChart?.resize()
  spreadChart?.resize(); peerChart?.resize()
  dailyLineChart?.resize(); dailyBarChart?.resize()
}

onMounted(async () => {
  const { varietiesId, marketName, tableType } = route.query
  // 缺少品种 ID 时不必请求后端（会得到一个无意义的 500 提示）
  if (!varietiesId) {
    loadErr.value = ''
    return
  }
  loading.value = true
  try {
    const res = await getAnalysis({ varietiesId, marketName, tableType })
    if (res.code === 200 && res.data) {
      data.value = res.data
      await nextTick()
      initCharts()
    } else {
      loadErr.value = res.message || '未查询到该商品在所选报价点的数据'
    }
  } catch (e) {
    console.error(e)
    loadErr.value = '网络异常或服务不可用，请稍后重试'
  } finally {
    loading.value = false
  }

  ctx = gsap.context(() => {
    gsap.fromTo('.back-bar', { y: 10, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.3, ease: 'power3.out' })
    gsap.fromTo('.kpi-card', { y: 15, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out', stagger: 0.05, delay: 0.1 })
    gsap.fromTo('.chart-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out', stagger: 0.08, delay: 0.3 })
  })
  window.addEventListener('resize', handleResize)
})

let ctx
onUnmounted(() => {
  ctx?.revert()
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose(); pieChart?.dispose()
  spreadChart?.dispose(); peerChart?.dispose()
  dailyLineChart?.dispose(); dailyBarChart?.dispose()
})
</script>

<style scoped>
.analysis-page { padding: 0; }

/* 无参数/失败兜底 */
.an-empty {
  max-width: 460px; margin: 64px auto; text-align: center;
  background: var(--card, #fff); border: 1px solid var(--border, #e8edf3);
  border-radius: 14px; padding: 36px 28px;
}
.an-empty-icon { font-size: 34px; line-height: 1; margin-bottom: 14px; }
.an-loading { border-color: #e5e7eb; }
.an-loading .an-empty-icon { animation: anPulse 1.4s ease-in-out infinite; }
@keyframes anPulse { 0%, 100% { opacity: 1 } 50% { opacity: .38 } }
.an-empty h3 { font-size: 15px; color: #111827; margin: 0 0 10px; }
.an-empty-sub { font-size: 12.5px; color: #6b7280; line-height: 1.8; margin: 0 0 20px; }
.an-empty-sub strong { color: #374151; }
.an-empty-btns { display: flex; gap: 10px; justify-content: center; }
.an-btn {
  padding: 7px 16px; border-radius: 8px; font-size: 12.5px; cursor: pointer;
  border: 1px solid #e4e4e7; background: #fff; color: #374151; transition: all .15s;
}
.an-btn:hover { border-color: #d1d5db; color: #111827; }
.an-btn.primary { background: #111827; border-color: #111827; color: #fff; }
.an-btn.primary:hover { background: #1f2937; }

.back-bar {
  display: flex; align-items: center; gap: 12px;
  margin-bottom: 24px;
}
.back-btn {
  display: flex; align-items: center; gap: 4px;
  padding: 6px 12px; border-radius: 8px; background: var(--card);
  border: 1px solid #e4e4e7; font-size: 12px; color: #6b7280;
  cursor: pointer; transition: all 0.15s;
}
.back-btn:hover { border-color: #d1d5db; color: #111827; }
.page-title { font-size: 16px; font-weight: 600; color: #111827; }
.page-type { font-size: 11px; }
.badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 6px; font-weight: 500; }
.badge-m { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.badge-e { background: #f3e8ff; color: #7e22ce; border-color: #e9d5ff; }
.badge-i { background: #fef3c7; color: #92400e; border-color: #fde68a; }

.kpi-row {
  display: flex; gap: 12px; margin-bottom: 24px; flex-wrap: wrap;
}
.kpi-card {
  flex: 1; min-width: 130px;
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%); -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px;
  padding: 16px;
}
.kpi-label { font-size: 12px; color: #6b7280; margin-bottom: 4px; }
.kpi-value { font-size: 22px; font-weight: 700; color: #111827; letter-spacing: -0.02em; }
.kpi-sub { font-size: 10px; color: #71717a; margin-top: 2px; font-family: 'JetBrains Mono', monospace; }
.text-red { color: #ef4444; }
.text-green { color: #22c55e; }

.charts-grid {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px;
}
.chart-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%); -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 14px;
  overflow: hidden;
}
.chart-card.span-2 { grid-column: span 2; }

.card-header { padding: 16px 20px; border-bottom: 1px solid #f3f4f6; display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-header h3 { font-size: 14px; font-weight: 600; color: #111827; margin: 0; }
/* 图表口径小字（2026-09-17）*/
.ch-tt { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.ch-hint { font-size: 11.5px; font-weight: 400; color: #52525b; line-height: 1.5; }

.peer-count { font-size: 11px; color: #6b7280; flex-shrink: 0; white-space: nowrap; }

.chart-body { padding: 16px; }
.chart { width: 100%; height: 280px; }

/* 报价点较多时：容器限高 + 可滚动，避免所有 bar 被压扁 */
.chart-scroll {
  max-height: 520px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0;
  scrollbar-width: thin;
  scrollbar-color: rgba(107,114,128,.35) transparent;
}
/* 内边距放在滚动内容内部，滚动时上下留白都保留 */
.chart-inner { padding: 16px 16px 20px; }
.chart-scroll::-webkit-scrollbar { width: 8px; }
.chart-scroll::-webkit-scrollbar-track { background: transparent; }
.chart-scroll::-webkit-scrollbar-thumb { background: rgba(107,114,128,.28); border-radius: 4px; }
.chart-scroll::-webkit-scrollbar-thumb:hover { background: rgba(107,114,128,.45); }

@media (max-width: 768px) {
  .charts-grid { grid-template-columns: 1fr; }
  .chart-card.span-2 { grid-column: span 1; }
  .kpi-row { flex-direction: column; }
  .chart-scroll { max-height: 400px; }
}
.n { font-variant-numeric: tabular-nums; font-family: 'JetBrains Mono', monospace; }
</style>
