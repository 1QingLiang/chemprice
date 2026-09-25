<template>
  <div class="movers-page">
    <!-- 涨跌方向切换 -->
    <section class="tabs-section">
      <div class="tabs">
        <button
          v-for="tab in directionTabs"
          :key="tab.key"
          class="tab"
          :class="{ active: activeDirection === tab.key }"
          @click="activeDirection = tab.key"
        >
          <span class="tab-indicator" :class="tab.key"></span>
          {{ tab.name }}
        </button>
      </div>
    </section>

    <!-- 三维度并排排行榜 -->
    <div class="boards-grid">
      <div v-for="dim in dimensions" :key="dim.key" class="board-card">
        <div class="board-header" :class="dim.key">
          <span class="board-icon">{{ dim.icon }}</span>
          <div>
            <h3>{{ dim.name }} {{ directionLabel }}</h3>
            <span class="board-date">{{ latestDates[dim.key] || (loaded[dim.key] ? '该维度暂无授权商品数据' : '加载中…') }}</span>
          </div>
        </div>
        <div class="table-wrap">
          <table class="mini-table">
            <colgroup>
              <col class="col-rank">
              <col class="col-name">
              <col class="col-market">
              <col class="col-price">
              <col class="col-rate">
            </colgroup>
            <thead>
              <tr>
                <th style="width:36px">#</th>
                <th>商品</th>
                <th>报价点</th>
                <th class="text-right">主流价</th>
                <th class="text-right">{{ activeDirection === 'flat' ? '价格' : '涨跌' }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in boardData[dim.key]" :key="i">
                <td class="rank" :class="{ top3: i < 3 }">{{ i + 1 }}</td>
                <td class="name-cell">{{ item.varieties_name }}</td>
                <td class="market-cell">{{ item.market_name }}</td>
                <td class="n text-right">{{ fmtNum(item.middle_price) }}</td>
                <td class="n text-right" :class="activeDirection === 'flat' ? 'text-gray' : rateClass(item)">
                  {{ activeDirection === 'flat' ? '持平' : rateText(item) }}
                </td>
              </tr>
              <tr v-if="!boardData[dim.key]?.length">
                <td colspan="5" class="empty">暂无数据</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { getDashboardMovers } from '../api/index'
import gsap from 'gsap'

const activeDirection = ref('rise')
const directionTabs = [
  { key: 'rise', name: '涨幅排行' },
  { key: 'fall', name: '跌幅排行' },
  { key: 'flat', name: '平盘排行' }
]

const dimensions = [
  { key: 'market', name: '市场价格', icon: '📊' },
  { key: 'enterprise', name: '企业价格', icon: '🏭' },
  { key: 'international', name: '国际价格', icon: '🌐' }
]

const boardData = ref({ market: [], enterprise: [], international: [] })
const latestDates = ref({})
const loaded = ref({ market: false, enterprise: false, international: false })

const directionLabel = computed(() => {
  return directionTabs.find(t => t.key === activeDirection.value)?.name || ''
})

function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }
function rateText(p) {
  if (p.data_rate && p.data_rate !== '' && p.data_rate !== '0%') return p.data_rate
  const v = Number(p.data_rise_or_fall || 0)
  return (v > 0 ? '+' : '') + v.toFixed(2)
}
function rateClass(p) {
  const v = Number(p.data_rise_or_fall || 0)
  return v > 0 ? 'text-red' : v < 0 ? 'text-green' : 'text-gray'
}

async function loadBoard(dimKey, direction) {
  try {
    const res = await getDashboardMovers({ type: direction, limit: 10, tableType: dimKey })
    if (res.code === 200) {
      boardData.value[dimKey] = res.data || []
      latestDates.value[dimKey] = res.data?.[0]?.data_date || ''
    }
  } catch (e) {
    console.error(`${dimKey} 排行加载失败:`, e)
  } finally {
    loaded.value[dimKey] = true
  }
}

async function loadAll() {
  const dir = activeDirection.value
  await Promise.all(dimensions.map(d => loadBoard(d.key, dir)))
}

watch(activeDirection, () => loadAll())
onMounted(() => loadAll())

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    gsap.fromTo('.tabs-section', { y: 15, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out' })
    gsap.fromTo('.board-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out', stagger: 0.08, delay: 0.1 })
  })
})
onUnmounted(() => { ctx && ctx.revert() })
</script>

<style scoped>
.movers-page { padding: 0; }
.tabs-section { margin-bottom: 24px; }

.tabs { display: flex; gap: 8px; }

.tab {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 20px; background: var(--card);
  border: 1px solid #e5e7eb; border-radius: 12px;
  font-size: 14px; font-weight: 500; color: #6b7280;
  cursor: pointer; transition: all 0.2s;
}
.tab:hover { border-color: #d1d5db; color: #374151; }
.tab.active { background: #111827; border-color: #111827; color: white; }

.tab-indicator { width: 8px; height: 8px; border-radius: 50%; }
.tab-indicator.rise { background: #ef4444; }
.tab-indicator.fall { background: #22c55e; }
.tab-indicator.flat { background: #9ca3af; }

/* 三列网格 */
.boards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

@media (max-width: 1024px) {
  .boards-grid { grid-template-columns: 1fr; }
}

.board-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 14px;
  overflow: hidden;
}

.board-header {
  display: flex; align-items: center; gap: 10px;
  padding: 16px 18px;
  border-bottom: 1px solid #f3f4f6;
}
.board-header.market { border-left: 3px solid #3b82f6; }
.board-header.enterprise { border-left: 3px solid #a855f7; }
.board-header.international { border-left: 3px solid #f59e0b; }

.board-icon { font-size: 20px; }

.board-header h3 {
  font-size: 14px; font-weight: 600; color: #111827; margin: 0;
}
.board-date {
  font-size: 11px; color: #6b7280;
  font-family: 'JetBrains Mono', monospace;
}

.table-wrap { overflow-x: auto; }

.mini-table {
  width: 100%; border-collapse: collapse; font-size: 12px;
  table-layout: fixed;
}
.mini-table th,
.mini-table td {
  padding: 10px 8px;
}
.mini-table th {
  text-align: left;
  font-weight: 500; color: #6b7280;
  border-bottom: 1px solid #f3f4f6; background: #fafafa;
  font-size: 10px; text-transform: uppercase; letter-spacing: 0.03em;
}
.mini-table th.text-right { text-align: right; }
.mini-table td {
  border-bottom: 1px solid #f9fafb; color: #374151;
}

/* 固定列宽 */
.mini-table col.col-rank { width: 28px; }
.mini-table col.col-name { width: 25%; }
.mini-table col.col-market { width: 22%; }
.mini-table col.col-price { width: 22%; }
.mini-table col.col-rate { width: 23%; }
.mini-table tbody tr:hover { background: #f9fafb; }

.rank { font-weight: 600; color: #9ca3af; }
.rank.top3 { color: #d97706; }

.name-cell { font-weight: 600; max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.market-cell { color: #6b7280; max-width: 80px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.text-right { text-align: right; }
.text-red { color: #ef4444; }
.text-green { color: #22c55e; }
.text-gray { color: #6b7280; }
.empty { text-align: center; color: #9ca3af; padding: 30px 12px; }

.n {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', monospace;
}
</style>
