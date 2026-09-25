<template>
  <div class="commodity-list">
    <section class="section">
      <div class="card">
        <div class="card-header">
          <h3>商品中心</h3>
          <div class="search-box">
            <input 
              type="text" 
              v-model="searchQuery" 
              placeholder="搜索商品..."
              class="search-input"
            />
          </div>
        </div>
        <!-- 一级大类导航 -->
        <div class="cat-nav">
          <button class="cat-chip" :class="{ active: activeCat === '全部' }" @click="activeCat = '全部'">
            全部 <span class="cat-count">{{ commodities.length }}</span>
          </button>
          <button v-for="cat in cats" :key="cat" class="cat-chip" :class="{ active: activeCat === cat }" @click="activeCat = cat">
            {{ cat }} <span class="cat-count">{{ countByCat(cat) }}</span>
          </button>
        </div>
        <template v-for="group in displayGroups" :key="group.category">
          <div class="group-title" v-if="activeCat === '全部'">
            <span class="group-name">{{ group.category }}</span>
            <span class="group-count">{{ group.items.length }} 个商品</span>
          </div>
          <div class="grid">
            <div 
              v-for="commodity in group.items" 
              :key="commodity.varietiesId"
              class="commodity-card"
              @click="viewDetail(commodity.varietiesId)"
            >
            <div class="card-top">
              <div class="commodity-name">{{ commodity.name }}</div>
              <div class="badge-i" v-if="commodity.intlCount > 0"
                   title="含国际报价数据（美元计价）">I</div>
            </div>
            <div class="stats-head">数据条数</div>
            <div class="stats-grid">
              <div class="stat">
                <div class="stat-value n">{{ commodity.marketCount || 0 }}</div>
                <div class="stat-label market">市场</div>
              </div>
              <div class="stat">
                <div class="stat-value n">{{ commodity.enterpriseCount || 0 }}</div>
                <div class="stat-label enterprise">企业</div>
              </div>
              <div class="stat">
                <div class="stat-value n">{{ commodity.intlCount || 0 }}</div>
                <div class="stat-label international">国际</div>
              </div>
            </div>
            <div class="card-footer">
              <span class="date-range" v-if="commodity.earliestDate && commodity.latestDate">
                数据区间 {{ commodity.earliestDate }} ~ {{ commodity.latestDate }}
              </span>
              <span class="date-range" v-else>暂无数据日期</span>
            </div>
          </div>
          </div>
        </template>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCommodities } from '../api/index'
import { groupByCategory, categoryNames } from '../utils/commodityGroups'
import gsap from 'gsap'

const router = useRouter()
const commodities = ref([])
const searchQuery = ref('')
const activeCat = ref('全部')

const cats = computed(() => categoryNames(commodities.value))

const filteredCommodities = computed(() => {
  let list = commodities.value
  const query = searchQuery.value.toLowerCase()
  if (query) {
    list = list.filter(c =>
      c.name.toLowerCase().includes(query) ||
      (c.category && c.category.toLowerCase().includes(query))
    )
  }
  return list
})

// 按大类分组展示：全部 -> 多组；选中某类 -> 单组
const displayGroups = computed(() => {
  const list = filteredCommodities.value
  if (activeCat.value === '全部') return groupByCategory(list)
  const groups = groupByCategory(list).filter(g => g.category === activeCat.value)
  return groups.length ? groups : [{ category: activeCat.value, items: [] }]
})

function countByCat(cat) {
  return commodities.value.filter(c => (c.category || '未分类') === cat).length
}

function viewDetail(id) {
  router.push(`/commodity/${id}`)
}

onMounted(async () => {
  try {
    const res = await getCommodities()
    if (res.code === 200) {
      commodities.value = res.data || []
    }
  } catch (e) {
    console.error('商品列表加载失败:', e)
  }
})

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    gsap.fromTo('.card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out' })
    gsap.fromTo('.commodity-card', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', stagger: 0.05, delay: 0.1 })
  })
})

onUnmounted(() => { ctx && ctx.revert() })
</script>

<style scoped>
.commodity-list {
  padding: 0;
}

.section {
  margin-bottom: 24px;
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
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  margin: 0;
}

.search-box {
  position: relative;
}

.search-input {
  padding: 10px 12px 10px 36px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  font-size: 13px;
  color: #374151;
  background: var(--card);
  width: 200px;
}

.search-input:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.cat-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 24px 4px;
}

.cat-chip {
  padding: 6px 14px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid #e4e4e7;
  background: #fff;
  color: #374151;
  cursor: pointer;
  font-family: inherit;
  transition: all .15s;
}

.cat-chip:hover { border-color: #3b82f6; color: #1d4ed8; }
.cat-chip.active { background: #09090b; color: #fff; border-color: #09090b; }

.cat-count { font-size: 11px; opacity: .7; margin-left: 2px; }

.group-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 16px 24px 0;
}

.group-title:first-of-type { padding-top: 12px; }

.group-name { font-size: 14px; font-weight: 600; color: #111827; }

.group-count { font-size: 12px; color: #6b7280; }

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  padding: 24px;
}

.commodity-card {
  background: #fafafa;
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all 0.2s;
}

.commodity-card:hover {
  transform: scale(0.98);
  background: #f3f4f6;
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
}

.card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.commodity-name {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}

.badge-i {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: #fef3c7;
  color: #92400e;
  border-radius: 6px;
  font-size: 10px;
  font-weight: 600;
}

.stats-head {
  font-size: 10.5px; color: #9ca3af; letter-spacing: .5px;
  margin: -6px 0 6px;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat {
  text-align: center;
}

.stat-value {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: #111827;
  margin-bottom: 4px;
}

.stat-label {
  font-size: 11px;
  color: #6b7280;
}

.stat-label.market { color: #1d4ed8; }
.stat-label.enterprise { color: #7e22ce; }
.stat-label.international { color: #b45309; }

.card-footer {
  border-top: 1px solid #e5e7eb;
  padding-top: 12px;
}

.date-range {
  font-size: 11px;
  color: #6b7280;
}

.n {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', monospace;
}

@media (max-width: 768px) {
  .card-header {
    flex-direction: column;
    gap: 16px;
    align-items: stretch;
  }
  
  .search-input {
    width: 100%;
  }
}
</style>