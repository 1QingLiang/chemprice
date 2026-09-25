<template>
  <div class="dashboard">
    <!-- KPI Cards -->
    <section class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon blue">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>
        </div>
        <div class="kpi-content">
          <div class="kpi-value n">{{ fmt(stats.totalCount) }}</div>
          <div class="kpi-label">总数据量</div>
        </div>
      </div>
      
      <div class="kpi-card">
        <div class="kpi-icon green">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 7H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2Z"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/></svg>
        </div>
        <div class="kpi-content">
          <div class="kpi-value n">{{ fmt(stats.varietiesCount) }}</div>
          <div class="kpi-label">产品数量</div>
        </div>
      </div>
      
      <div class="kpi-card">
        <div class="kpi-icon amber">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="18" height="18" x="3" y="4" rx="2" ry="2"/><line x1="16" x2="16" y1="2" y2="6"/><line x1="8" x2="8" y1="2" y2="6"/><line x1="3" x2="21" y1="10" y2="10"/></svg>
        </div>
        <div class="kpi-content">
          <div class="kpi-value n">{{ latestDate || '—' }}</div>
          <div class="kpi-label">最新日期</div>
        </div>
      </div>
      
      <div class="kpi-card">
        <div class="kpi-icon purple">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
        </div>
        <div class="kpi-content">
          <div class="kpi-value">
            <span class="text-red">↑{{ augStats.upCount || 0 }}</span>
            <span class="text-gray"> / </span>
            <span class="text-green">↓{{ augStats.downCount || 0 }}</span>
          </div>
          <div class="kpi-label">今日涨跌</div>
        </div>
      </div>
    </section>

    <!-- Charts Row -->
    <section class="charts-row">
      <!-- 涨跌环形图 -->
      <div class="chart-card">
        <div class="card-header">
          <div class="ch-tt"><h3>今日涨跌分布</h3><span class="ch-hint">口径：最新交易日全部报价行，按数据源涨跌字段统计的条数（非品种数）</span></div>
        </div>
        <div class="chart-container">
          <div ref="pieChartRef" class="chart"></div>
        </div>
      </div>
      
      <!-- 月均价走势（商品下拉按大类分组切换） -->
      <div class="chart-card wide">
        <div class="card-header">
          <div class="ch-tt"><h3>{{ selectedVariety ? selectedVariety.name : '—' }} 月均价走势</h3><span class="ch-hint">口径：按月汇总该品种全部报价点的中间价，取月平均并取整（非月末价）</span></div>
          <el-select
            v-model="selectedVariety"
            filterable
            size="small"
            class="trend-select"
            placeholder="选择商品"
            :value-key="'id'"
            @change="refreshAll"
          >
            <el-option-group v-for="g in commodityGroups" :key="g.category" :label="g.category">
              <el-option v-for="c in g.items" :key="c.varietiesId" :value="{ id: c.varietiesId, name: c.name }" :label="c.name" />
            </el-option-group>
          </el-select>
        </div>
        <div class="chart-container">
          <div ref="lineChartRef" class="chart"></div>
        </div>
      </div>
    </section>

    <!-- Tables Row -->
    <section class="tables-row">
      <!-- 今日涨幅TOP10 -->
      <div class="table-card">
        <div class="card-header">
          <div class="ch-tt"><h3 class="text-red">今日涨幅 TOP10</h3><span class="ch-hint">口径：按涨跌额（元/吨）降序取前 10，不是按涨跌幅排序</span></div>
        </div>
        <div class="table-container">
          <table class="table">
            <thead>
              <tr>
                <th>#</th>
                <th>商品</th>
                <th>报价点</th>
                <th>维度</th>
                <th class="text-right">主流价</th>
                <th class="text-right">涨跌额</th>
                <th class="text-right">涨跌幅</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in topRise" :key="i">
                <td class="rank" :class="{ 'top3': i < 3 }">{{ i + 1 }}</td>
                <td class="font-semibold">{{ item.varieties_name }}</td>
                <td class="cell-point">
                  <div>{{ item.market_name }}</div>
                  <div v-if="item.data_date" class="cell-date">{{ fmtDate(item.data_date) }}</div>
                </td>
                <td>
                  <span class="badge" :class="typeBadge(item)">{{ item.business_type_name || '市场' }}</span>
                </td>
                <td class="n text-right">{{ fmtNum(item.middle_price) }}</td>
                <td class="n text-right text-red">{{ fmtSigned(item.data_rise_or_fall) }}</td>
                <td class="n text-right text-red">{{ item.data_rate || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      
      <!-- 今日跌幅TOP10 -->
      <div class="table-card">
        <div class="card-header">
          <div class="ch-tt"><h3 class="text-green">今日跌幅 TOP10</h3><span class="ch-hint">口径：按涨跌额（元/吨）升序取前 10，不是按涨跌幅排序</span></div>
        </div>
        <div class="table-container">
          <table class="table">
            <thead>
              <tr>
                <th>#</th>
                <th>商品</th>
                <th>报价点</th>
                <th>维度</th>
                <th class="text-right">主流价</th>
                <th class="text-right">涨跌额</th>
                <th class="text-right">涨跌幅</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in topFall" :key="i">
                <td class="rank" :class="{ 'top3': i < 3 }">{{ i + 1 }}</td>
                <td class="font-semibold">{{ item.varieties_name }}</td>
                <td class="cell-point">
                  <div>{{ item.market_name }}</div>
                  <div v-if="item.data_date" class="cell-date">{{ fmtDate(item.data_date) }}</div>
                </td>
                <td>
                  <span class="badge" :class="typeBadge(item)">{{ item.business_type_name || '市场' }}</span>
                </td>
                <td class="n text-right">{{ fmtNum(item.middle_price) }}</td>
                <td class="n text-right text-green">{{ fmtSigned(item.data_rise_or_fall) }}</td>
                <td class="n text-right text-green">{{ item.data_rate || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { getCommodities, getDashboardStats, getAugStats, getDashboardTrend, getDashboardMovers } from '../api/index'
import { groupByCategory } from '../utils/commodityGroups'
import * as echarts from 'echarts'
import gsap from 'gsap'

const stats = ref({})
const augStats = ref({})
const latestDate = ref('')
const topRise = ref([])
const topFall = ref([])

const pieChartRef = ref(null)
const lineChartRef = ref(null)
let pieChart = null
let lineChart = null

// 商品来源：与数据页一致，按大类分组下拉选择
const commodities = ref([])
const commodityGroups = computed(() => groupByCategory(commodities.value))
const selectedVariety = ref(null)

// 走势默认取最近 12 个月（到“今天”），不再写死结束日期
function trendRange() {
  const e = new Date()
  const s = new Date(e.getTime() - 365 * 864e5)
  const fmt = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
  return { start: fmt(s), end: fmt(e) }
}

function fmt(n) { return n == null ? '—' : Number(n).toLocaleString('en-US') }
function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }
// 涨跌额带符号显示：+2,000 / -1,250（单位元/吨）
function fmtSigned(v) {
  if (v == null || v === '') return '—'
  const n = Number(v)
  if (!isFinite(n) || n === 0) return '—'
  return (n > 0 ? '+' : '') + Number(n.toFixed(2)).toLocaleString('en-US')
}
function typeBadge(p) {
  const t = p.business_type
  if (t === 2 || t === '2') return 'badge-e'
  if (t === 4 || t === '4') return 'badge-i'
  return 'badge-m'
}
// 数据日期：显示成「9月11日」（不是当年时带上年份）
function fmtDate(v) {
  if (!v) return ''
  let y, m, d
  if (Array.isArray(v)) {
    [y, m, d] = v
  } else {
    const mt = String(v).match(/(\d{4})[-/](\d{1,2})[-/](\d{1,2})/)
    if (mt) {
      y = +mt[1]; m = +mt[2]; d = +mt[3]
    } else {
      const dt = new Date(v)
      if (isNaN(dt.getTime())) return String(v)
      y = dt.getFullYear(); m = dt.getMonth() + 1; d = dt.getDate()
    }
  }
  if (!y || !m || !d) return ''
  return (y === new Date().getFullYear() ? '' : y + '年') + m + '月' + d + '日'
}

async function refreshAll() {
  const v = selectedVariety.value
  if (!v) return
  try {
    const [upRes, downRes] = await Promise.all([
      getDashboardMovers({ type: 'up', limit: 10, tableType: 'market' }),
      getDashboardMovers({ type: 'down', limit: 10, tableType: 'market' })
    ])
    if (upRes.code === 200) topRise.value = (upRes.data || []).slice(0, 10)
    if (downRes.code === 200) topFall.value = (downRes.data || []).slice(0, 10)
    await loadTrend()
  } catch (e) { console.error(e) }
}

function initPieChart() {
  if (!pieChartRef.value) return
  
  pieChart = echarts.init(pieChartRef.value)
  const option = {
    color: ['#ef4444', '#9ca3af', '#22c55e'],
    series: [{
      type: 'pie',
      // 半径收小到 58%，给外侧标签留出空间；原来 70% 会把「上涨: 1443」裁掉
      radius: ['40%', '58%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: true,
      itemStyle: {
        borderRadius: 8,
        borderColor: '#fff',
        borderWidth: 3
      },
      label: {
        show: true,
        formatter: '{b} {c}',
        fontSize: 11,
        fontFamily: 'Inter, Noto Sans SC, system-ui, sans-serif'
      },
      labelLine: { length: 8, length2: 8, smooth: true },
      data: [
        { value: augStats.value.upCount || 0, name: '上涨' },
        { value: augStats.value.flatCount || 0, name: '平盘' },
        { value: augStats.value.downCount || 0, name: '下跌' }
      ]
    }]
  }
  pieChart.setOption(option)
}

async function loadTrend() {
  const v = selectedVariety.value
  if (!v) return
  const { start, end } = trendRange()
  try {
    const res = await getDashboardTrend({
      varietiesId: v.id,
      startDate: start,
      endDate: end
    })
    if (res.code !== 200 || !res.data?.length) return
    const months = res.data.map(d => d.month)
    const prices = res.data.map(d => d.avgPrice)
    await nextTick()
    if (!lineChart && lineChartRef.value) lineChart = echarts.init(lineChartRef.value)
    if (!lineChart) return
    lineChart.setOption({
      color: ['#3b82f6'],
      grid: { left: 55, right: 20, top: 20, bottom: 30 },
      tooltip: { trigger: 'axis', backgroundColor: 'rgba(255,255,255,0.95)', borderColor: '#e5e7eb', textStyle: { color: '#111827' } },
      xAxis: { type: 'category', data: months, axisLine: { lineStyle: { color: '#e5e7eb' } }, axisLabel: { color: '#6b7280', fontSize: 10 } },
      yAxis: { type: 'value', scale: true, axisLine: { show: false }, splitLine: { lineStyle: { color: '#f3f4f6' } }, axisLabel: { color: '#6b7280', fontSize: 10 } },
      series: [{
        name: v.name,
        type: 'line',
        data: prices,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: '#3b82f6', width: 2.5 },
        itemStyle: { color: '#3b82f6' },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(59,130,246,0.2)' }, { offset: 1, color: 'rgba(59,130,246,0)' }
        ])}
      }]
    }, true)
  } catch (e) {
    console.error('走势加载失败:', e)
  }
}

onMounted(async () => {
  try {
    // 先取商品清单（普通用户仅授权品种，管理员为全部）
    const cRes = await getCommodities()
    if (cRes.code === 200 && cRes.data && cRes.data.length) {
      commodities.value = cRes.data
      const first = cRes.data[0]
      selectedVariety.value = { id: first.varietiesId, name: first.name }
    }
  } catch (e) { console.error('商品清单加载失败:', e) }

  try {
    const [statsRes, augRes] = await Promise.all([
      getDashboardStats(),
      getAugStats()
    ])

    if (statsRes.code === 200) {
      stats.value = statsRes.data || {}
      latestDate.value = stats.value.latestDate || '—'
    }

    if (augRes.code === 200) {
      augStats.value = augRes.data || {}
    }

    await nextTick()
    if (selectedVariety.value) await refreshAll()

    await nextTick()
    initPieChart()
  } catch (e) {
    console.error('看板数据加载失败:', e)
  }
})

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    const mm = gsap.matchMedia()
    mm.add('(prefers-reduced-motion: no-preference)', () => {
      gsap.fromTo('.kpi-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', stagger: 0.05 })
      gsap.fromTo('.chart-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', delay: 0.2 })
      gsap.fromTo('.table-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', delay: 0.3 })
      // KPI 卡呼吸
      gsap.to('.kpi-card', {
        scale: 1.015, autoAlpha: 0.97,
        duration: 2.8, ease: 'sine.inOut',
        repeat: -1, yoyo: true, stagger: { each: 0.5, from: 'start' }
      })
      // KPI 图标脉冲
      gsap.to('.kpi-icon', {
        scale: 1.08, duration: 2, ease: 'sine.inOut',
        repeat: -1, yoyo: true, stagger: 0.4
      })
    })
    mm.add('(prefers-reduced-motion: reduce)', () => {
      gsap.fromTo('.kpi-card', { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.3 })
      gsap.fromTo('.chart-card', { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.3, delay: 0.15 })
      gsap.fromTo('.table-card', { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.3, delay: 0.3 })
    })
  })
})

onUnmounted(() => {
  ctx && ctx.revert()
  pieChart && pieChart.dispose()
  lineChart && lineChart.dispose()
})

window.addEventListener('resize', () => {
  pieChart && pieChart.resize()
  lineChart && lineChart.resize()
})
</script>

<style scoped>
.dashboard {
  padding: 0;
}

/* 报价点单元格：报价点 + 下方小字数据日期，各自不折行 */
.cell-point {
  white-space: nowrap;
}
.cell-date {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.2;
  font-weight: 400;
  color: #71717a;
}

.trend-select { width: 220px; }

.product-selector {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  padding: 12px 16px;
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px;
}
.selector-label {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  white-space: nowrap;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}

.kpi-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 16px;
  padding: 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.kpi-card:hover {
  transform: scale(0.98);
  box-shadow: 0 8px 24px rgba(31,41,55,.09);
}

.kpi-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.kpi-icon.blue { background: rgba(59, 130, 246, 0.1); color: #3b82f6; }
.kpi-icon.green { background: rgba(34, 197, 94, 0.1); color: #22c55e; }
.kpi-icon.amber { background: rgba(245, 158, 11, 0.1); color: #f59e0b; }
.kpi-icon.purple { background: rgba(168, 85, 247, 0.1); color: #a855f7; }

.kpi-value {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #111827;
  line-height: 1.2;
}

.kpi-label {
  font-size: 13px;
  color: #6b7280;
  margin-top: 4px;
}

.charts-row {
  display: grid;
  grid-template-columns: 1fr 2fr;
  gap: 16px;
  margin-bottom: 32px;
}

.chart-card {
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
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}

.card-header h3 {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  margin: 0;
}
/* 图表口径小字（2026-09-17）*/
.ch-tt { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
.ch-hint { font-size: 11.5px; font-weight: 400; color: #52525b; line-height: 1.5; }


.chart-container {
  padding: 24px;
}

.chart {
  width: 100%;
  height: 300px;
}

.tables-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.table-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 16px;
  overflow: hidden;
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

.rank {
  font-weight: 600;
  color: #6b7280;
}

.rank.top3 {
  color: #ef4444;
}

.text-right { text-align: right; }
/* ⚠️ 表头右对齐必须单独提升特异性：`.table th` 是 (0,1,1)，高于 `.text-right` 的 (0,1,0)，
   否则表头会被 .table th 的 text-align:left 覆盖 —— 表现为「表头靠左、数字靠右」看起来没对齐 */
.table th.text-right { text-align: right; }
.font-semibold { font-weight: 600; }
.text-red { color: #ef4444; }
.text-green { color: #22c55e; }
.text-gray { color: #6b7280; }

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

.n {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', monospace;
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  
  .charts-row {
    grid-template-columns: 1fr;
  }
  
  .tables-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
/* Tab bar */
.tab-bar {
  display: flex;
  gap: 4px;
}
.tab-pill {
  padding: 5px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--border, #e4e4e7);
  background: var(--card);
  color: var(--ink3, #71717a);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.tab-pill:hover {
  border-color: var(--ink4, #d4d4d8);
  color: var(--ink2, #27272a);
}
.tab-pill.active {
  background: var(--ink, #09090b);
  color: #fff;
  border-color: var(--ink, #09090b);
}
</style>