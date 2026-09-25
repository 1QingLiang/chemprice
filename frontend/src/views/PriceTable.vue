<template>
  <div class="price-table">
    <!-- ===== 市场/企业价格 筛选区 ===== -->
    <section class="filters el-panel">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">商品</span>
          <el-select v-model="filters.varietiesId" placeholder="全部商品" clearable filterable class="filter-w">
            <el-option value="" label="全部商品" />
            <el-option-group v-for="g in domesticCommodityGroups" :key="g.category" :label="g.category">
              <el-option v-for="c in g.items" :key="c.varietiesId" :value="String(c.varietiesId)" :label="c.name" />
            </el-option-group>
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">价格类型</span>
          <el-select v-model="filters.tableType" placeholder="全部类型" clearable class="filter-w">
            <el-option value="" label="全部类型" />
            <el-option value="market" label="市场价格" />
            <el-option value="enterprise" label="企业价格" />
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">报价点</span>
          <el-select
            v-model="filters.marketName"
            placeholder="全部报价点"
            clearable
            filterable
            :loading="marketOptLoading"
            class="filter-w"
          >
            <el-option value="" label="全部报价点" />
            <el-option v-for="m in marketOptions" :key="m" :value="m" :label="m" />
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">日期范围</span>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            :shortcuts="dateShortcuts"
            class="filter-w-lg"
          />
        </div>
        <div class="filter-actions">
          <el-button type="primary" :icon="Search" @click="fetchDomestic">查询</el-button>
          <el-button @click="resetDomestic">重置</el-button>
        </div>
      </div>
      <div class="intraday-note" v-if="showIntradayNote">※ 当日数据为盘中动态值，可能随行情更新波动，以收盘后为准</div>
    </section>

    <!-- 市场/企业价格表格 -->
    <section class="section">
      <div class="card">
        <div class="card-header">
          <h3>{{ domesticTypeLabel }}</h3>
          <div style="display:flex;align-items:center;gap:12px">
            <div class="pagination-info">共 {{ domesticTotal }} 条，第 {{ domesticPage }} / {{ domesticPages }} 页</div>
            <button v-if="authStore.canExport" class="export-btn" @click="doExport('market')">📥 导出Excel</button>
          </div>
        </div>
        <!-- 桌面端：原表格 -->
        <div v-if="!isMobile" class="table-container">
          <table class="table">
            <thead>
              <tr>
                <th>商品</th><th>报价点</th><th>维度</th>
                <th>主流价</th><th>最低价</th><th>最高价</th>
                <th>单位</th><th>涨跌</th><th>数据日</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in domesticData" :key="i">
                <td class="font-semibold">{{ item.varieties_name }}</td>
                <td>
                  {{ item.market_name }}
                  <div v-if="item.specifications_name || item.brand_name" class="quote-sub">
                    {{ item.specifications_name || '' }}<span v-if="item.specifications_name && item.brand_name"> · </span>{{ item.brand_name || '' }}
                  </div>
                </td>
                <td><span class="badge" :class="item.business_type === 2 ? 'badge-e' : 'badge-m'">{{ item.business_type === 2 ? '企业价格' : '市场价格' }}</span></td>
                <td class="n">{{ fmtNum(item.middle_price) }}</td>
                <td class="n">{{ fmtNum(item.low_price) }}</td>
                <td class="n">{{ fmtNum(item.high_price) }}</td>
                <td class="unit-cell">{{ item.unit_valuation_name || '元/吨' }}</td>
                <td><span class="badge" :class="badgeCls(item)">{{ rateText(item) }}</span></td>
                <td>{{ item.data_date }}</td>
                <td>
                  <button
                    class="fav-btn"
                    :class="{ 'fav-active': isFav(item) }"
                    @click.stop="toggleFav(item)"
                  >{{ isFav(item) ? '★ 已关注' : '☆ 关注' }}</button>
                </td>
              </tr>
              <tr v-if="!domesticData.length"><td colspan="10" class="empty-row">暂无数据</td></tr>
            </tbody>
          </table>
        </div>

        <!-- 手机端：卡片化 -->
        <div v-else class="mc-list">
          <div v-for="(item, i) in domesticData" :key="i" class="mc-row">
            <div class="mc-hd">
              <span class="mc-name">{{ item.varieties_name }}</span>
              <span class="badge" :class="item.business_type === 2 ? 'badge-e' : 'badge-m'">{{ item.business_type === 2 ? '企业' : '市场' }}</span>
            </div>
            <div class="mc-sub">{{ item.market_name }}<template v-if="item.specifications_name || item.brand_name"> · {{ item.specifications_name || '' }}<span v-if="item.specifications_name && item.brand_name"> · </span>{{ item.brand_name || '' }}</template></div>
            <div class="mc-mid">
              <span class="mc-price n">{{ fmtNum(item.middle_price) }}</span>
              <span class="mc-unit">{{ item.unit_valuation_name || '元/吨' }}</span>
              <span class="badge mc-rb" :class="badgeCls(item)">{{ rateText(item) }}</span>
            </div>
            <div class="mc-meta">
              <span>低 {{ fmtNum(item.low_price) }}</span>
              <span>高 {{ fmtNum(item.high_price) }}</span>
              <span class="mc-date">{{ item.data_date }}</span>
              <button class="fav-btn mc-fav" :class="{ 'fav-active': isFav(item) }" @click.stop="toggleFav(item)">{{ isFav(item) ? '★ 已关注' : '☆ 关注' }}</button>
            </div>
          </div>
          <div v-if="!domesticData.length" class="mc-empty">暂无数据</div>
        </div>
        <!-- 市场/企业分页 -->
        <div class="card-footer" v-if="domesticPages > 1">
          <div class="pagination">
            <button v-if="domesticPages > 10" class="page-btn" title="第一页"
                    :disabled="domesticPage <= 1" @click="goDomestic(1)">«</button>
            <button class="page-btn" :disabled="domesticPage <= 1" @click="goDomestic(domesticPage-1)">‹</button>
            <button v-for="p in domesticVisible" :key="p" class="page-btn" :class="{ active: p === domesticPage }" @click="goDomestic(p)">{{ p }}</button>
            <button class="page-btn" :disabled="domesticPage >= domesticPages" @click="goDomestic(domesticPage+1)">›</button>
            <button v-if="domesticPages > 10" class="page-btn" title="最后一页"
                    :disabled="domesticPage >= domesticPages" @click="goDomestic(domesticPages)">»</button>
            <span v-if="domesticPages > 10" class="page-jump">
              跳至
              <input v-model="domesticJump" class="jump-input" type="text" inputmode="numeric"
                     :placeholder="String(domesticPage)" @keyup.enter="jumpDomestic" />
              页
              <button class="page-btn jump-go" @click="jumpDomestic">确定</button>
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- ===== 国际价格独立筛选区 ===== -->
    <section class="filters el-panel intl-section">
      <div class="intl-header">
        <span class="intl-tag">国际价格</span>
        <span class="intl-desc">含汇率换算，数据独立于市场/企业价格</span>
      </div>
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">商品</span>
          <el-select v-model="intlFilters.varietiesId" placeholder="全部商品" clearable filterable class="filter-w">
            <el-option value="" label="全部商品" />
            <el-option-group v-for="g in intlCommodityGroups" :key="g.category" :label="g.category">
              <el-option v-for="c in g.items" :key="c.varietiesId" :value="String(c.varietiesId)" :label="c.name" />
            </el-option-group>
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">报价点</span>
          <el-select
            v-model="intlFilters.marketName"
            placeholder="全部报价点"
            clearable
            filterable
            :loading="marketOptLoading"
            class="filter-w"
          >
            <el-option value="" label="全部报价点" />
            <el-option v-for="m in intlMarketOptions" :key="m" :value="m" :label="m" />
          </el-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">日期范围</span>
          <el-date-picker
            v-model="intlDateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            :shortcuts="dateShortcuts"
            class="filter-w-lg"
          />
        </div>
        <div class="filter-actions">
          <el-button type="primary" :icon="Search" @click="fetchIntl">查询</el-button>
          <el-button @click="resetIntl">重置</el-button>
        </div>
      </div>
      <div class="intraday-note" v-if="showIntradayNoteIntl">※ 当日数据为盘中动态值，可能随行情更新波动，以收盘后为准</div>
    </section>

    <!-- 国际价格表格 -->
    <section class="section">
      <div class="card">
        <div class="card-header">
          <h3>国际价格（含汇率换算）</h3>
          <div style="display:flex;align-items:center;gap:12px">
            <div class="pagination-info">共 {{ intlTotal }} 条，第 {{ intlPage }} / {{ intlPages }} 页</div>
            <button v-if="authStore.canExport" class="export-btn" @click="doExport('international')">📥 导出Excel</button>
          </div>
        </div>
        <!-- 桌面端：原表格 -->
        <div v-if="!isMobile" class="table-container">
          <table class="table intl-table">
            <thead>
              <tr>
                <th>商品</th><th>报价点</th><th>报价方式</th>
                <th>原始价格</th><th>当日汇率</th><th>人民币价格</th>
                <th>涨跌</th><th>数据日</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, i) in intlData" :key="i">
                <td class="font-semibold">{{ item.varieties_name }}</td>
                <td>{{ item.market_name }}</td>
                <td><span class="badge badge-i">{{ item.price_type_name || '国际' }}</span></td>
                <td class="n font-mono">{{ fmtIntlPrice(item) }}</td>
                <td class="font-mono" style="color:var(--ink3)">{{ fxLabel(item) }}</td>
                <td class="n font-mono" style="font-weight:600;color:var(--blue)">{{ fmtRmb(item) }}</td>
                <td><span class="badge" :class="badgeCls(item)">{{ rateText(item) }}</span></td>
                <td>{{ item.data_date }}</td>
                <td>
                  <button
                    class="fav-btn"
                    :class="{ 'fav-active': isFav(item) }"
                    @click.stop="toggleFav(item)"
                  >{{ isFav(item) ? '★ 已关注' : '☆ 关注' }}</button>
                </td>
              </tr>
              <tr v-if="!intlData.length"><td colspan="9" class="empty-row">暂无国际价格数据</td></tr>
            </tbody>
          </table>
        </div>

        <!-- 手机端：卡片化 -->
        <div v-else class="mc-list">
          <div v-for="(item, i) in intlData" :key="i" class="mc-row">
            <div class="mc-hd">
              <span class="mc-name">{{ item.varieties_name }}</span>
              <span class="badge badge-i">{{ item.price_type_name || '国际' }}</span>
            </div>
            <div class="mc-sub">{{ item.market_name }}</div>
            <div class="mc-mid mc-intl">
              <span class="mc-price n">{{ fmtIntlPrice(item) }}</span>
              <span class="mc-fx">{{ fxLabel(item) }}</span>
            </div>
            <div class="mc-rmb">折合人民币 <b>{{ fmtRmb(item) }}</b></div>
            <div class="mc-meta">
              <span class="badge mc-rb" :class="badgeCls(item)">{{ rateText(item) }}</span>
              <span class="mc-date">{{ item.data_date }}</span>
              <button class="fav-btn mc-fav" :class="{ 'fav-active': isFav(item) }" @click.stop="toggleFav(item)">{{ isFav(item) ? '★ 已关注' : '☆ 关注' }}</button>
            </div>
          </div>
          <div v-if="!intlData.length" class="mc-empty">暂无国际价格数据</div>
        </div>
        <!-- 国际价格分页 -->
        <div class="card-footer" v-if="intlPages > 1">
          <div class="pagination">
            <button v-if="intlPages > 10" class="page-btn" title="第一页"
                    :disabled="intlPage <= 1" @click="goIntl(1)">«</button>
            <button class="page-btn" :disabled="intlPage <= 1" @click="goIntl(intlPage-1)">‹</button>
            <button v-for="p in intlVisible" :key="p" class="page-btn" :class="{ active: p === intlPage }" @click="goIntl(p)">{{ p }}</button>
            <button class="page-btn" :disabled="intlPage >= intlPages" @click="goIntl(intlPage+1)">›</button>
            <button v-if="intlPages > 10" class="page-btn" title="最后一页"
                    :disabled="intlPage >= intlPages" @click="goIntl(intlPages)">»</button>
            <span v-if="intlPages > 10" class="page-jump">
              跳至
              <input v-model="intlJump" class="jump-input" type="text" inputmode="numeric"
                     :placeholder="String(intlPage)" @keyup.enter="jumpIntl" />
              页
              <button class="page-btn jump-go" @click="jumpIntl">确定</button>
            </span>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getCommodities, getPrices, getFavorites, addFavorite, removeFavorite, exportPrices, getMarketOptions } from '../api/index'
import { useAuthStore } from '../stores/auth'
import { groupByCategory } from '../utils/commodityGroups'
import { Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import gsap from 'gsap'

const authStore = useAuthStore()
const commodities = ref([])
const commodityGroups = computed(() => groupByCategory(commodities.value))
const favorites = ref(new Set()) // 存 "varietiesId|marketName|specificationsName|tableType" 组合键
const pageSize = 10

// 生成行的唯一关注键（含规格：同商品同报价点不同规格可分别关注）
function favKey(item) {
  const tt = item.business_type === 2 ? 'enterprise' : item.business_type === 4 ? 'international' : 'market'
  return `${item.varieties_id}|${item.market_name || ''}|${item.specifications_name || ''}|${tt}`
}
// 行对应的 tableType
function getTableType(item) {
  if (item.business_type === 2) return 'enterprise'
  if (item.business_type === 4) return 'international'
  return 'market'
}
function isFav(item) { return favorites.value.has(favKey(item)) }

const today = new Date().toISOString().slice(0, 10)
const defaultStart = new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10)

const dateShortcuts = [
  { text: '最近3天', value: () => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 2); return [s, e] } },
  { text: '最近7天', value: () => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 6); return [s, e] } },
  { text: '最近30天', value: () => { const e = new Date(); const s = new Date(); s.setDate(s.getDate() - 29); return [s, e] } },
  { text: '本月', value: () => { const e = new Date(); const s = new Date(e.getFullYear(), e.getMonth(), 1); return [s, e] } },
]

// ===== 市场/企业 =====
const domesticData = ref([])
const domesticTotal = ref(0)
const domesticPage = ref(1)
const route = useRoute()   // 接收首页数据源卡片带过来的 tableType
const filters = ref({ varietiesId: '', tableType: '', marketName: '', startDate: defaultStart, endDate: today })
const dateRange = ref([defaultStart, today])
watch(dateRange, (v) => {
  if (v && v[0] && v[1]) { filters.value.startDate = v[0]; filters.value.endDate = v[1] }
  else { filters.value.startDate = ''; filters.value.endDate = '' }
})
// 真实总页数：原实现硬性截断为 20 页，导致「共 3 万条」却只能翻到第 200 条（201 页以后无法到达）
// 实测深翻页 offset 到 3 万条仍约 110ms（idx_date_mid 覆盖过滤与排序），无性能风险，故不再截断
const domesticPages = computed(() => Math.max(1, Math.ceil(domesticTotal.value / pageSize)))
const domesticJump = ref('')   // 页码跳转输入框
const domesticVisible = computed(() => {
  const pages = []; const s = Math.max(1, domesticPage.value - 2); const e = Math.min(domesticPages.value, s + 4)
  for (let i = s; i <= e; i++) pages.push(i); return pages
})
const domesticTypeLabel = computed(() => {
  const t = filters.value.tableType
  if (t === 'market') return '市场价格'
  if (t === 'enterprise') return '企业价格'
  return '市场 + 企业价格'
})

// ===== 国际 =====
const intlData = ref([])
const intlTotal = ref(0)
const intlPage = ref(1)
const intlFilters = ref({ varietiesId: '', marketName: '', startDate: defaultStart, endDate: today })
const intlDateRange = ref([defaultStart, today])
const showIntradayNote = computed(() => filters.value.endDate >= today)
const showIntradayNoteIntl = computed(() => intlFilters.value.endDate >= today)
watch(intlDateRange, (v) => {
  if (v && v[0] && v[1]) { intlFilters.value.startDate = v[0]; intlFilters.value.endDate = v[1] }
  else { intlFilters.value.startDate = ''; intlFilters.value.endDate = '' }
})
const intlPages = computed(() => Math.max(1, Math.ceil(intlTotal.value / pageSize)))
const intlJump = ref('')
const intlVisible = computed(() => {
  const pages = []; const s = Math.max(1, intlPage.value - 2); const e = Math.min(intlPages.value, s + 4)
  for (let i = s; i <= e; i++) pages.push(i); return pages
})

// 移动端：表格改为卡片化渲染
  const isMobile = ref(typeof window !== 'undefined' && window.innerWidth < 768)
  function _onResize() { isMobile.value = window.innerWidth < 768 }

  // ===== 公共方法 =====
  function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }
function fmtIntlPrice(item) {
  if (item.middle_price == null) return '—'
  const unit = item.unit_valuation_name || ''
  // 美分/磅、美分/加仑：外盘习惯用"美分"计价，绝不能加 $（39美分≠$39）
  if (unit.includes('美分')) return Number(item.middle_price).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  const cur = item.fx_used_currency || ''
  const prefix = cur === 'USD' ? '$' : cur === 'EUR' ? '€' : cur === 'GBP' ? '£' : ''
  return prefix + Number(item.middle_price).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fxLabel(item) {
  // 美分计价行不做人民币折算，汇率列显示原币种汇率仍可参考
  // 后端已按 fx_rate 表当日汇率返回 fx_label（如 "USD/CNY ≈ 6.7769"）；缺失再走本地兜底
  if (item.fx_label) return item.fx_label
  const cur = item.fx_used_currency || ''
  if (cur === 'USD') return 'USD/CNY ≈ 7.25'
  if (cur === 'EUR') return 'EUR/CNY ≈ 7.90'
  return '—'
}
function fmtRmb(item) {
  // 人民币计价行（单位即元/吨）直接用原价；外币行用折算价；都没有则 —
  const cur = item.fx_used_currency || ''
  const v = (!cur || cur === 'CNY') ? (item.middle_price ?? item.calc_rmb_price) : item.calc_rmb_price
  if (v == null) return '—'
  return '¥' + Number(v).toLocaleString('en-US')
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

// ===== 报价点联动 =====
// 规则（用户 2026-09-11 指定）：
//   1) 选报价点(如"广东") → 商品下拉只保留在广东有报价的商品
//   2) 选商品(如 PP)     → 报价点下拉只保留 PP 存在的报价点
//   3) 换价格类型/日期   → 报价点列表跟着刷新
// 实现：以「当前另一端选择 + 日期范围」为条件向后端要 options。
const marketOptions = ref([])        // 市场+企业 区块的报价点候选
const intlMarketOptions = ref([])    // 国际 区块的报价点候选
const marketOptLoading = ref(false)
// 与报价点联动的商品下拉数据源（选了中国报价点后会被裁剪）
const domesticCommodityGroups = ref([])
const intlCommodityGroups = ref([])

// 联动用商品分组：未按报价点裁剪时与全量一致
watch(commodityGroups, (g) => {
  if (!filters.value.marketName) domesticCommodityGroups.value = g
  if (!intlFilters.value.marketName) intlCommodityGroups.value = g
}, { immediate: true })

/** 从后端拉报价点候选；scope=domestic 时 tableType 为空则聚合三表 */
async function loadMarketOptions(scope) {
  const isIntl = scope === 'intl'
  const f = isIntl ? intlFilters.value : filters.value
  const params = {}
  // 国际区块固定 international；国内区块用当前价格类型（空=全部类型）
  params.tableType = isIntl ? 'international' : (f.tableType || '')
  const vid = (f.varietiesId || '').split(',').filter(Boolean).join(',')
  if (vid) params.varietiesId = vid
  if (f.startDate) params.startDate = f.startDate
  if (f.endDate) params.endDate = f.endDate
  marketOptLoading.value = true
  try {
    const res = await getMarketOptions(params)
    const list = (res && res.code === 200 && Array.isArray(res.data)) ? res.data : []
    if (isIntl) intlMarketOptions.value = list
    else marketOptions.value = list
    // 已选报价点不在候选里 → 清空，避免"筛选后必然为空"
    if (f.marketName && !list.includes(f.marketName)) f.marketName = ''
  } catch (e) {
    console.error('loadMarketOptions', e)
    if (isIntl) intlMarketOptions.value = []
    else marketOptions.value = []
  } finally {
    marketOptLoading.value = false
  }
}

/** 选了报价点 → 反查该报价点下有数据的商品，裁剪商品下拉 */
async function loadCommoditiesForMarket(scope) {
  const isIntl = scope === 'intl'
  const f = isIntl ? intlFilters.value : filters.value
  if (!f.marketName) {
    // 没选报价点：恢复全量商品
    if (isIntl) intlCommodityGroups.value = commodityGroups.value
    else domesticCommodityGroups.value = commodityGroups.value
    return
  }
  const params = { marketName: f.marketName, page: 1, size: 500 }
  params.tableType = isIntl ? 'international' : (f.tableType || '')
  if (f.startDate) params.startDate = f.startDate
  if (f.endDate) params.endDate = f.endDate
  try {
    const res = await getPrices(params)
    const rows = (res && res.code === 200) ? (res.data?.data || []) : []
    const ids = new Set(rows.map(r => String(r.varieties_id)))
    const groups = commodityGroups.value
      .map(g => ({ ...g, items: g.items.filter(c => ids.has(String(c.varietiesId))) }))
      .filter(g => g.items.length > 0)
    if (isIntl) intlCommodityGroups.value = groups
    else domesticCommodityGroups.value = groups
    // 已选商品不在范围内 → 清空
    if (f.varietiesId && !ids.has(String(f.varietiesId))) f.varietiesId = ''
  } catch (e) {
    console.error('loadCommoditiesForMarket', e)
    if (isIntl) intlCommodityGroups.value = commodityGroups.value
    else domesticCommodityGroups.value = commodityGroups.value
  }
}

// 商品/价格类型/日期变化 → 刷新报价点候选（并同步裁剪商品，实现双向联动）
watch(() => [filters.value.tableType, filters.value.startDate, filters.value.endDate],
  () => { if (filters.value.marketName) loadCommoditiesForMarket('domestic'); loadMarketOptions('domestic') })
watch(() => filters.value.marketName,
  () => { loadCommoditiesForMarket('domestic') })
watch(() => filters.value.varietiesId,
  () => { loadMarketOptions('domestic') })

watch(() => [intlFilters.value.startDate, intlFilters.value.endDate],
  () => { if (intlFilters.value.marketName) loadCommoditiesForMarket('intl'); loadMarketOptions('intl') })
watch(() => intlFilters.value.marketName,
  () => { loadCommoditiesForMarket('intl') })
watch(() => intlFilters.value.varietiesId,
  () => { loadMarketOptions('intl') })

// ===== 数据加载 =====
async function fetchDomestic() {
  try {
    const params = { page: domesticPage.value, size: pageSize, ...filters.value }
    const res = await getPrices(params)
    if (res.code === 200) { domesticData.value = res.data?.data || []; domesticTotal.value = res.data?.total || 0 }
  } catch (e) { console.error(e) }
}
function goDomestic(p) { if (p < 1 || p > domesticPages.value) return; domesticPage.value = p; fetchDomestic() }
// 页码跳转：越界或非数字时给出提示，而不是静默不动
function jumpDomestic() {
  const n = parseInt(String(domesticJump.value).trim(), 10)
  if (!n || Number.isNaN(n)) { ElMessage.warning('请输入要跳转的页码'); return }
  if (n < 1 || n > domesticPages.value) { ElMessage.warning('页码需在 1 ~ ' + domesticPages.value + ' 之间'); return }
  domesticJump.value = ''
  goDomestic(n)
}
function resetDomestic() { filters.value = { varietiesId: '', tableType: '', marketName: '', startDate: defaultStart, endDate: today }; dateRange.value = [defaultStart, today]; domesticPage.value = 1; loadMarketOptions('domestic'); loadCommoditiesForMarket('domestic'); fetchDomestic() }

async function fetchIntl() {
  try {
    const params = { page: intlPage.value, size: pageSize, tableType: 'international', ...intlFilters.value }
    const res = await getPrices(params)
    if (res.code === 200) { intlData.value = res.data?.data || []; intlTotal.value = res.data?.total || 0 }
  } catch (e) { console.error(e) }
}

function resetIntl() { intlFilters.value = { varietiesId: '', marketName: '', startDate: defaultStart, endDate: today }; intlDateRange.value = [defaultStart, today]; intlPage.value = 1; loadMarketOptions('intl'); loadCommoditiesForMarket('intl'); fetchIntl() }
function goIntl(p) { if (p < 1 || p > intlPages.value) return; intlPage.value = p; fetchIntl() }
function jumpIntl() {
  const n = parseInt(String(intlJump.value).trim(), 10)
  if (!n || Number.isNaN(n)) { ElMessage.warning('请输入要跳转的页码'); return }
  if (n < 1 || n > intlPages.value) { ElMessage.warning('页码需在 1 ~ ' + intlPages.value + ' 之间'); return }
  intlJump.value = ''
  goIntl(n)
}

onMounted(async () => {
  window.addEventListener('resize', _onResize)
  // 从首页「数据源卡片」跳转过来：按 query 预选价格类型；国际价格则滚动到对应区块
  const _qt = String(route.query.tableType || '')
  if (_qt === 'market' || _qt === 'enterprise') {
    filters.value.tableType = _qt
  } else if (_qt === 'international') {
    setTimeout(() => {
      const el = document.querySelector('.intl-section')
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 400)
  }
  try { const r = await getCommodities(); if (r.code === 200) commodities.value = r.data || [] } catch (e) {}
  try {
    const r = await getFavorites()
    if (r.code === 200) {
      favorites.value = new Set((r.data || []).map(f => `${f.varieties_id}|${f.market_name || ''}|${f.specifications_name || ''}|${f.table_type || 'market'}`))
    }
  } catch (e) {}
  await Promise.all([fetchDomestic(), fetchIntl(), loadMarketOptions('domestic'), loadMarketOptions('intl')])
})

async function doExport(tableType) {
  if (!authStore.canExport) {
    ElMessage.warning('您没有数据导出权限，请联系管理员开通')
    return
  }
  try {
    const params = { tableType }
    if (tableType === 'market') {
      if (filters.value.varietiesId) params.varietiesId = filters.value.varietiesId
      if (filters.value.marketName) params.exportMarketName = filters.value.marketName
      if (filters.value.startDate) params.startDate = filters.value.startDate
      if (filters.value.endDate) params.endDate = filters.value.endDate
    } else {
      if (intlFilters.value.varietiesId) params.varietiesId = intlFilters.value.varietiesId
      if (intlFilters.value.marketName) params.exportMarketName = intlFilters.value.marketName
      if (intlFilters.value.startDate) params.startDate = intlFilters.value.startDate
      if (intlFilters.value.endDate) params.endDate = intlFilters.value.endDate
    }
    const qs = new URLSearchParams(params).toString()
    const token = localStorage.getItem('token') || ''
    const resp = await fetch('/api/prices/export?' + qs, {
      headers: { 'Authorization': 'Bearer ' + token }
    })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    const rows = await resp.json()
    if (!rows.length) { ElMessage.info('暂无数据可导出'); return }
    // 生成 CSV（UTF-8 BOM 让 Excel 正确识别中文）
    const BOM = '\uFEFF'
    const header = Object.keys(rows[0]).join(',')
    const csv = BOM + header + '\n' + rows.map(r =>
      Object.values(r).map(v => '"' + String(v).replace(/"/g, '""') + '"').join(',')
    ).join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = (tableType === 'international' ? '国际价格' : '市场价格') + '数据导出.csv'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => window.URL.revokeObjectURL(url), 1000)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error('导出失败: ' + (e.message || ''))
  }
}

async function toggleFav(item) {
  const key = favKey(item)
  const tt = getTableType(item)
  const params = { marketName: item.market_name || '', specificationsName: item.specifications_name || '', tableType: tt }
  if (favorites.value.has(key)) {
    await removeFavorite(item.varieties_id, params); favorites.value.delete(key)
  } else {
    await addFavorite(item.varieties_id, params); favorites.value.add(key)
  }
  favorites.value = new Set(favorites.value)
}

let ctx; onMounted(() => { ctx = gsap.context(() => { gsap.fromTo('.price-table > *', { y: 10, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.4, ease: 'power3.out', stagger: 0.05 }) }) })
onUnmounted(() => { ctx && ctx.revert(); window.removeEventListener('resize', _onResize) })
</script>

<style scoped>
.intraday-note { font-size: 12px; color: #b45309; background: #fffbeb; border: 1px solid #fde68a; border-radius: 6px; padding: 6px 10px; margin-top: 8px; }
.price-table { padding: 0; }

.el-panel { margin-bottom: 16px; }
.filter-row { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-end; }
.filter-item { display: flex; flex-direction: column; gap: 6px; }
.filter-label { font-size: 12px; font-weight: 500; color: #6b7280; }
.filter-w { width: 180px; }
.filter-w-lg { width: 280px; }
.filter-actions { display: flex; gap: 8px; padding-bottom: 1px; }
.intl-section { border-left: 3px solid #f59e0b; }
.intl-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.intl-tag { padding: 4px 10px; border-radius: 6px; background: #fef3c7; color: #92400e; font-size: 12px; font-weight: 600; }
.intl-desc { font-size: 11px; color: #71717a; }
.section { margin-bottom: 16px; }
.card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px; overflow: hidden;
}
.card-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid #f4f4f5; }
.card-header h3 { font-size: 14px; font-weight: 600; }
.card-footer { padding: 12px 16px; border-top: 1px solid #f4f4f5; display: flex; justify-content: center; }
.pagination-info { font-size: 12px; color: #71717a; }
.export-btn {
  padding: 6px 14px; border-radius: 8px; border: 1px solid #e4e4e7;
  background: var(--card); font-size: 12px; font-weight: 500; color: #374151;
  cursor: pointer; transition: all 0.15s; white-space: nowrap;
}
.export-btn:hover { background: #111827; color: white; border-color: #111827; }
.table-container { overflow-x: auto; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th { text-align: left; padding: 10px 12px; font-weight: 500; font-size: 11px; color: #71717a; background: #f9fafb; border-bottom: 1px solid #e4e4e7; text-transform: uppercase; letter-spacing: .3px; white-space: nowrap; }
.table td { padding: 10px 12px; border-bottom: 1px solid #f4f4f5; white-space: nowrap; }
.table tbody tr:hover { background: #fafafa; }
.empty-row { text-align: center; color: #71717a; padding: 32px !important; }
.font-mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.quote-sub { font-size: 11px; color: var(--ink3); margin-top: 2px; opacity: .85; }
.unit-cell { font-size: 11px; color: #71717a; background: #f9fafb; padding: 4px 8px; border-radius: 4px; display: inline-block; }
.badge { padding: 2px 8px; border-radius: 6px; font-size: 11px; font-weight: 500; white-space: nowrap; }
.badge-m { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.badge-e { background: #f3e8ff; color: #7e22ce; border-color: #e9d5ff; }
.badge-i { background: #fef3c7; color: #92400e; border-color: #fde68a; }
.badge-up { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
.badge-dn { background: #dcfce7; color: #15803d; border-color: #bbf7d0; }
.badge-fl { background: #f4f4f5; color: #71717a; }
.pagination { display: flex; justify-content: center; align-items: center; gap: 4px; flex-wrap: wrap; row-gap: 8px; }
.page-btn { width: 32px; height: 32px; border: 1px solid #e4e4e7; border-radius: 6px; background: var(--card); font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
.page-btn.active { background: #09090b; color: #fff; border-color: #09090b; }
.page-btn:disabled { opacity: .4; cursor: not-allowed; }
/* 页码跳转（页数较多时才出现） */
.page-jump { display: inline-flex; align-items: center; gap: 5px; margin-left: 6px; font-size: 12px; color: #71717a; white-space: nowrap; }
.jump-input { width: 52px; height: 32px; border: 1px solid #e4e4e7; border-radius: 6px; background: var(--card); text-align: center; font-size: 12px; font-family: inherit; color: inherit; outline: none; }
.jump-input:focus { border-color: #09090b; }
.page-btn.jump-go { width: auto; padding: 0 10px; }
.fav-btn { padding: 3px 10px; border: 1px solid #e4e4e7; border-radius: 12px; background: var(--card); font-size: 11px; cursor: pointer; font-family: inherit; color: #71717a; transition: all 0.15s; white-space: nowrap; }
.fav-btn:hover { border-color: #f59e0b; color: #f59e0b; }
.fav-btn.fav-active { background: #fef3c7; border-color: #f59e0b; color: #b45309; font-weight: 600; }
/* ===== 手机端适配 ===== */
@media (max-width: 768px) {
  /* 筛选区：完全堆叠，每个 filter-item 占满宽，按钮满宽 */
  .filter-row { flex-direction: column; align-items: stretch; gap: 12px; }
  .filter-item { width: 100%; }
  .filter-w, .filter-w-lg { width: 100%; }
  .filter-actions { width: 100%; padding-bottom: 0; gap: 10px; }
  .filter-actions :deep(.el-button) { flex: 1; }

  /* 让 el-date-picker、el-select 在窄屏撑满父容器 */
  .filter-item :deep(.el-select),
  .filter-item :deep(.el-date-editor) { width: 100%; }

  /* 卡片头：标题与导出按钮上下排，避免挤压 */
  .card-header { flex-direction: column; align-items: flex-start; gap: 8px; }
  .card-header > div { width: 100%; justify-content: space-between; }

  /* 桌面表格隐藏（v-if 已控制），本断点不再需要 overflow 兜底 */
  .table-container { display: none; }

  /* ===== 移动端卡片列表 ===== */
  .mc-list { padding: 8px 10px 12px; display: flex; flex-direction: column; gap: 8px; }
  .mc-row {
    background: rgba(255,255,255,.55);
    border: 1px solid #e4e4e7;
    border-radius: 12px;
    padding: 10px 12px 8px;
    box-shadow: 0 1px 2px rgba(15,23,42,.04);
  }
  .mc-row:active { background: rgba(255,255,255,.8); }
  .mc-hd { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
  .mc-name { font-size: 14px; font-weight: 600; color: #111827; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mc-sub { font-size: 11px; color: #71717a; margin-bottom: 8px; }
  .mc-mid { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
  .mc-price { font-size: 20px; font-weight: 700; color: #111827; letter-spacing: -.5px; }
  .mc-unit { font-size: 11px; color: #71717a; }
  .mc-rb { font-size: 11px; padding: 2px 7px; }
  .mc-fx { font-size: 11px; color: #71717a; font-family: 'JetBrains Mono', monospace; }
  .mc-intl { margin-bottom: 6px; }
  .mc-rmb { font-size: 12px; color: #3b82f6; margin-bottom: 8px; }
  .mc-rmb b { color: #1d4ed8; font-weight: 600; }
  .mc-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding-top: 6px; border-top: 1px dashed #eef0f2; font-size: 11px; color: #71717a; }
  .mc-meta > span:not(.mc-rb):not(.mc-fx) { color: #71717a; }
  .mc-date { margin-left: auto; font-family: 'JetBrains Mono', monospace; }
  .mc-fav { margin-left: 0; }
  .mc-empty { padding: 40px 0; text-align: center; color: #71717a; font-size: 12px; }

  /* 分页在卡片下方更紧凑 */
  .card-footer { padding: 10px; }
  .page-btn { width: 28px; height: 28px; }
  .jump-input { width: 46px; height: 28px; }
  .page-jump { margin-left: 0; }
}
</style>
