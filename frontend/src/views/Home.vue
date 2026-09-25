<template>
  <div class="home">
    <!-- Hero Section -->
    <section class="hero">
      <canvas ref="particleCanvas" class="hero-particles"></canvas>
      <div class="hero-content">
        <div class="hero-tag">ChemPrice v2.0</div>
        <h1 class="hero-title">
          <span class="gradient-text">化工品价格</span>
          <br>实时监控与分析
        </h1>
        <p class="hero-desc">
          覆盖{{ commodities.length || '—' }}种产品，为企业采购定价提供数据支撑
        </p>
        <div class="hero-stats">
          <div class="stat-card">
            <div class="stat-value n">{{ fmt(stats.totalCount) }}</div>
            <div class="stat-label">历史数据条</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-card">
            <div class="stat-value n">{{ commodities.length || '—' }}</div>
            <div class="stat-label">产品数量</div>
          </div>
          <div class="stat-divider"></div>
          <div class="stat-card">
            <div class="stat-value n">3</div>
            <div class="stat-label">价格维度</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 三维度状态卡 -->
    <section class="section">
      <div class="source-grid">
        <div 
          v-for="s in sources" 
          :key="s.name" 
          class="source-card clickable"
          :class="{ 'delayed': s.delayed }"
          :title="s.tip"
          @click="goSource(s)"
        >
          <div class="pulse" :class="'pulse-' + s.tone" :title="s.tip"></div>
          <div class="source-info">
            <div class="source-name">{{ s.name }}</div>
            <div class="source-count">{{ s.desc }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 关注商品最新价格 -->
    <section class="section" v-if="favPrices.length">
      <div class="sec-head-row">
        <h3 class="section-title">关注商品最新价格</h3>
        <button class="push-set-btn" @click="$router.push('/push-settings')">邮件推送设置</button>
      </div>
      <!-- 关注筛选：商品 / 报价点 / 规格（三者联动，候选集互相收窄） -->
      <div class="fav-filter">
        <el-select v-model="fProduct" clearable filterable placeholder="全部商品" class="ff-sel ff-w1">
          <el-option v-for="n in optProducts" :key="'p' + n" :label="n" :value="n" />
        </el-select>
        <el-select v-model="fMarket" clearable filterable placeholder="全部报价点" class="ff-sel ff-w2">
          <el-option v-for="m in optMarkets" :key="'m' + m" :label="m" :value="m" />
        </el-select>
        <el-select v-model="fSpec" clearable filterable placeholder="全部规格" class="ff-sel ff-w3">
          <el-option v-for="sv in optSpecs" :key="'s' + sv" :label="specLabel(sv)" :value="sv" />
        </el-select>
        <button v-if="hasFavFilter" class="ff-reset" @click="resetFavFilter">清空筛选</button>
        <span class="ff-sum" :class="{ on: hasFavFilter }">
          {{ hasFavFilter ? '筛出 ' + filteredFavs.length + ' / ' + favPrices.length + ' 项' : '共 ' + favPrices.length + ' 项' }}
        </span>
      </div>

      <div class="fav-grid" v-if="filteredFavs.length">
        <div class="fav-card" v-for="p in filteredFavs" :key="p.varieties_id + '-' + p.table_type + '-' + p.market_name + '-' + (p.specifications_name || '')"
          :ref="el => { if (el) favCardRefs[p.varieties_id + '-' + p.table_type + '-' + p.market_name + '-' + (p.specifications_name || '')] = el }"
          @click="$router.push({ path: '/analysis', query: { tableType: p.table_type, varietiesId: p.varieties_id, marketName: p.market_name, specificationsName: p.specifications_name || '' } })"
          style="cursor:pointer">
          <div class="fav-card-head">
            <span class="fav-name">{{ p.name }}</span>
            <div style="display:flex;align-items:center;gap:6px">
              <span class="badge" :class="favTypeBadge(p)">{{ favTypeLabel(p.table_type) }}</span>
              <button class="fav-unfollow" @click.stop="unfollow(p)" title="取消关注">✕</button>
            </div>
          </div>
          <div class="fav-card-market">{{ p.market_name }}<span v-if="p.specifications_name" class="fav-card-spec"> · {{ p.specifications_name }}</span></div>
          <div class="fav-card-main">
            <span class="fav-main-price n">{{ fmtPrice(p) }}</span>
            <span class="fav-main-unit">{{ p.unit_valuation_name || '元/吨' }}</span>
            <span class="badge" :class="badgeCls(p)">{{ rateText(p) }}</span>
          </div>
          <div class="fav-card-detail">
            <div class="fav-detail-item">
              <span class="fav-detail-label">最高</span>
              <span class="fav-detail-value n">{{ fmtNum(p.high_price) }}</span>
            </div>
            <div class="fav-detail-item">
              <span class="fav-detail-label">最低</span>
              <span class="fav-detail-value n">{{ fmtNum(p.low_price) }}</span>
            </div>
            <div class="fav-detail-item">
              <span class="fav-detail-label">日期</span>
              <span class="fav-detail-value fav-date" :class="{ stale: isStaleFav(p) }"
                    :title="isStaleFav(p) ? '该报价点尚未更新到最新交易日（最新：' + latestFavDate + '）' : ''">
                {{ p.data_date }}<em v-if="isStaleFav(p)" class="fav-stale-tag">待更新</em>
              </span>
            </div>
          </div>
        </div>
      </div>
      <div class="empty-fav" v-else>没有符合条件的关注项，试试放宽筛选条件</div>
    </section>
    <section class="section" v-else>
      <div class="sec-head-row">
        <h3 class="section-title">关注商品最新价格</h3>
        <button class="push-set-btn" @click="$router.push('/push-settings')">邮件推送设置</button>
      </div>
      <div class="empty-fav">暂无关注商品，请在数据查询页面点击 ☆ 关注</div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCommodities, getPrices, getDashboardStats, getFavoriteLatestPrices, removeFavorite } from '../api/index'
import { ElMessage } from 'element-plus'
import gsap from 'gsap'

const router = useRouter()

/** 点数据源卡片 → 进数据查询页（带上价格类型，页面会按需过滤） */
function goSource(s) {
  router.push(s && s.type ? { path: '/price-table', query: { tableType: s.type } } : '/price-table')
}

const stats = ref({})
const commodities = ref([])
const latest = ref([])
const ranges = ref([])
const favPrices = ref([])
const favCardRefs = ref({})

// ===== 关注筛选（商品 / 报价点 / 规格，三者联动）=====
const SPEC_NONE = '__NONE__'          // 空规格的哨兵值（后端用 COALESCE 把 NULL 归一成 ''）
const fProduct = ref(null)
const fMarket = ref(null)
const fSpec = ref(null)

/** 规格归一：空规格统一成哨兵，避免 '' 与 null 在筛选里语义分裂 */
const specKey = (p) => ((p.specifications_name || '') === '' ? SPEC_NONE : p.specifications_name)
function specLabel(v) { return v === SPEC_NONE ? '无规格' : v }

/**
 * 按「除 exclude 之外」的已选条件过滤。
 * 每个下拉都用自己的 exclude 调一次，从而得到与其余条件兼容的候选集 —— 这就是联动。
 */
function pickFavs(exclude) {
  return favPrices.value.filter((p) => {
    if (exclude !== 'product' && fProduct.value && p.name !== fProduct.value) return false
    if (exclude !== 'market' && fMarket.value && p.market_name !== fMarket.value) return false
    if (exclude !== 'spec' && fSpec.value && specKey(p) !== fSpec.value) return false
    return true
  })
}

const optProducts = computed(() => [...new Set(pickFavs('product').map((p) => p.name))].sort())
const optMarkets = computed(() => [...new Set(pickFavs('market').map((p) => p.market_name))].sort())
const optSpecs = computed(() => [...new Set(pickFavs('spec').map(specKey))].sort())
const filteredFavs = computed(() => pickFavs(null))
const hasFavFilter = computed(() => !!(fProduct.value || fMarket.value || fSpec.value))

function resetFavFilter() {
  fProduct.value = null
  fMarket.value = null
  fSpec.value = null
}

// 联动兜底：已选值在新候选集里不存在时自动清空，避免「选了却筛不出结果」的死状态
watch([optProducts, optMarkets, optSpecs], () => {
  if (fProduct.value && !optProducts.value.includes(fProduct.value)) fProduct.value = null
  if (fMarket.value && !optMarkets.value.includes(fMarket.value)) fMarket.value = null
  if (fSpec.value && !optSpecs.value.includes(fSpec.value)) fSpec.value = null
})
const particleCanvas = ref(null)
let particleAnimId = null
let particleResizeObs = null
const sources = ref([
  { name: '市场价格', type: 'market', desc: '— 条', delayed: false, tone: 'blue',
    tip: '市场价格：全国主要产销区现货报价，每 30 分钟自动采集 · 点击进入数据查询' },
  { name: '企业价格', type: 'enterprise', desc: '— 条', delayed: false, tone: 'purple',
    tip: '企业价格：各生产企业出厂价 / 送到价 · 点击进入数据查询' },
  { name: '国际价格', type: 'international', desc: '— 条', delayed: true, tone: 'amber',
    tip: '国际价格：外盘基准价（多以美元计价），更新节奏较国内滞后 · 点击进入数据查询' }
])

/** 关注项里最新的数据日期（作为"是否滞后"的基准） */
const latestFavDate = computed(() => {
  const ds = favPrices.value.map(p => String(p.data_date || '')).filter(Boolean).sort()
  return ds.length ? ds[ds.length - 1] : ''
})
/** 该关注项的报价日期是否落后于最新交易日 */
function isStaleFav(p) {
  return !!(latestFavDate.value && p.data_date && String(p.data_date) < latestFavDate.value)
}

function fmt(n) { return n == null ? '—' : Number(n).toLocaleString('en-US') }
function fmtNum(v) { return v == null ? '—' : Number(v).toLocaleString('en-US') }
function fmtPrice(p) { return p.middle_price != null ? fmtNum(p.middle_price) : '—' }
function rateText(p) {
  if (p.data_rate && p.data_rate !== '') return p.data_rate
  const v = Number(p.data_rise_or_fall || 0)
  return (v > 0 ? '+' : '') + v.toFixed(2) + '%'
}
function badgeCls(p) {
  const v = Number(p.data_rise_or_fall || 0)
  return v > 0 ? 'badge-up' : v < 0 ? 'badge-dn' : 'badge-fl'
}
function favTypeBadge(p) {
  const t = p.table_type || 'market'
  if (t === 'enterprise') return 'badge-e'
  if (t === 'international') return 'badge-i'
  return 'badge-m'
}
function favTypeLabel(t) {
  if (t === 'enterprise') return '企业价格'
  if (t === 'international') return '国际价格'
  return '市场价格'
}

async function unfollow(p) {
  const key = p.varieties_id + '-' + p.table_type + '-' + p.market_name + '-' + (p.specifications_name || '')
  const el = favCardRefs.value[key]
  if (el) {
    gsap.to(el, {
      scale: 0.8, autoAlpha: 0, y: -10,
      duration: 0.3, ease: 'power2.in',
      onComplete: async () => {
        await removeFavorite(p.varieties_id, { marketName: p.market_name || '', specificationsName: p.specifications_name || '', tableType: p.table_type || 'market' })
        favPrices.value = favPrices.value.filter(x => !(x.varieties_id === p.varieties_id && x.table_type === p.table_type && x.market_name === p.market_name && (x.specifications_name || '') === (p.specifications_name || '')))
      }
    })
  } else {
    await removeFavorite(p.varieties_id, { marketName: p.market_name || '', specificationsName: p.specifications_name || '', tableType: p.table_type || 'market' })
    favPrices.value = favPrices.value.filter(x => !(x.varieties_id === p.varieties_id && x.table_type === p.table_type && x.market_name === p.market_name && (x.specifications_name || '') === (p.specifications_name || '')))
  }
}
function typeBadge(p) {
  const t = p.business_type
  if (t === 2 || t === '2') return 'badge-e'
  if (t === 4 || t === '4') return 'badge-i'
  return 'badge-m'
}

// ===== 邮件推送设置已拆至独立页 /push-settings =====

onMounted(async () => {
  try {
    const [sRes, cRes] = await Promise.all([
      getDashboardStats(),
      getCommodities()
    ])
    if (sRes.code === 200) {
      stats.value = sRes.data || {}
      sources.value[0].desc = `${fmt(stats.value.marketCount || 0)} 条`
      sources.value[1].desc = `${fmt(stats.value.enterpriseCount || 0)} 条`
      sources.value[2].desc = `${fmt(stats.value.intlCount || 0)} 条`
    }
    if (cRes.code === 200) {
      commodities.value = cRes.data || []
    }
    // 加载关注数据
    const favRes = await getFavoriteLatestPrices()
    if (favRes.code === 200) favPrices.value = favRes.data || []
  } catch (e) {
    console.error('首页数据加载失败:', e)
  }
})

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    const mm = gsap.matchMedia()
    // 默认（动效开启）
    mm.add('(prefers-reduced-motion: no-preference)', () => {
      gsap.fromTo('.hero', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out' })
      gsap.fromTo('.section', { y: 20, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.5, ease: 'power3.out', stagger: 0.08 })
      // Hero stat 卡呼吸
      gsap.to('.stat-card', {
        scale: 1.02, autoAlpha: 0.95,
        duration: 2.4, ease: 'sine.inOut',
        repeat: -1, yoyo: true, stagger: { each: 0.4, from: 'random' }
      })
      // 数据源卡片脉冲点强化
      gsap.to('.pulse', { scale: 1.6, autoAlpha: 0, duration: 1.6, ease: 'power2.out', repeat: -1, stagger: 0.5 })
    })
    // 降级模式
    mm.add('(prefers-reduced-motion: reduce)', () => {
      gsap.fromTo('.hero', { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.3 })
      gsap.fromTo('.section', { autoAlpha: 0 }, { autoAlpha: 1, duration: 0.3, stagger: 0.04 })
    })
  })
  // 启动粒子背景
  startParticleBg()
})
onUnmounted(() => {
  ctx && ctx.revert()
  if (particleAnimId) cancelAnimationFrame(particleAnimId)
  if (particleResizeObs) particleResizeObs.disconnect()
})

// 粒子背景（Canvas 2D + requestAnimationFrame，遵循 reduced-motion 规范）
function startParticleBg() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const canvas = particleCanvas.value
  if (!canvas) return
  const ctx2d = canvas.getContext('2d')
  let particles = []
  const NUM = 24
  const LINK_DIST = 100
  let skip = 0

  function resize() {
    const hero = canvas.parentElement
    const rect = hero.getBoundingClientRect()
    canvas.width = rect.width * window.devicePixelRatio
    canvas.height = rect.height * window.devicePixelRatio
    canvas.style.width = rect.width + 'px'
    canvas.style.height = rect.height + 'px'
    ctx2d.setTransform(window.devicePixelRatio, 0, 0, window.devicePixelRatio, 0, 0)
  }
  resize()

  function init() {
    const hero = canvas.parentElement
    const rect = hero.getBoundingClientRect()
    particles = Array.from({ length: NUM }, () => ({
      x: Math.random() * rect.width,
      y: Math.random() * rect.height,
      vx: (Math.random() - 0.5) * 0.25,
      vy: (Math.random() - 0.5) * 0.25,
      r: Math.random() * 1.6 + 0.6
    }))
  }
  init()

  let mouseX = -1000, mouseY = -1000
  canvas.addEventListener('mousemove', (e) => {
    const rect = canvas.getBoundingClientRect()
    mouseX = e.clientX - rect.left
    mouseY = e.clientY - rect.top
  })
  canvas.addEventListener('mouseleave', () => { mouseX = -1000; mouseY = -1000 })

  function tick() {
    skip = (skip + 1) % 2
    if (skip !== 0) { particleAnimId = requestAnimationFrame(tick); return }
    if (document.hidden) { particleAnimId = requestAnimationFrame(tick); return }
    const hero = canvas.parentElement
    const rect = hero.getBoundingClientRect()
    ctx2d.clearRect(0, 0, rect.width, rect.height)

    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > rect.width) p.vx *= -1
      if (p.y < 0 || p.y > rect.height) p.vy *= -1

      // 鼠标排斥
      const dx = p.x - mouseX, dy = p.y - mouseY
      const d2 = dx * dx + dy * dy
      if (d2 < 9000) {
        const d = Math.sqrt(d2) || 1
        p.x += (dx / d) * 0.6
        p.y += (dy / d) * 0.6
      }

      // 圆点
      ctx2d.beginPath()
      ctx2d.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx2d.fillStyle = 'rgba(147, 197, 253, 0.7)'
      ctx2d.fill()
    }

    // 连接线
    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const a = particles[i], b = particles[j]
        const dx = a.x - b.x, dy = a.y - b.y
        const d = Math.sqrt(dx * dx + dy * dy)
        if (d < LINK_DIST) {
          ctx2d.beginPath()
          ctx2d.moveTo(a.x, a.y)
          ctx2d.lineTo(b.x, b.y)
          ctx2d.strokeStyle = `rgba(147, 197, 253, ${0.25 * (1 - d / LINK_DIST)})`
          ctx2d.lineWidth = 0.6
          ctx2d.stroke()
        }
      }
    }
    particleAnimId = requestAnimationFrame(tick)
  }
  if (!reduce) tick()

  // 自适应窗口
  particleResizeObs = new ResizeObserver(() => {
    resize()
    init()
  })
  particleResizeObs.observe(canvas.parentElement)
}
</script>

<style scoped>
.home {
  padding: 0;
}

.hero {
  background: linear-gradient(135deg, #1e3a5f 0%, #0f172a 100%);
  border-radius: 20px;
  padding: 60px 40px;
  margin-bottom: 32px;
  position: relative;
  overflow: hidden;
}

.hero-particles {
  position: absolute;
  inset: 0;
  pointer-events: auto;
  z-index: 0;
}
.hero-content { position: relative; z-index: 1; }

.hero::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -20%;
  width: 600px;
  height: 600px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.15) 0%, transparent 70%);
  pointer-events: none;
}

.hero-content {
  max-width: 600px;
  position: relative;
  z-index: 1;
}

.hero-tag {
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: rgba(255,255,255,0.6);
  margin-bottom: 16px;
}

.hero-title {
  font-size: 48px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.1;
  color: white;
  margin: 0 0 20px 0;
}

.gradient-text {
  background: linear-gradient(135deg, #60a5fa, #34d399);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.hero-desc {
  font-size: 16px;
  line-height: 1.5;
  color: rgba(255,255,255,0.7);
  margin: 0 0 40px 0;
}

.hero-stats {
  display: flex;
  align-items: center;
  gap: 32px;
}

.stat-card {
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: white;
}

.stat-label {
  font-size: 12px;
  color: rgba(255,255,255,0.5);
  margin-top: 4px;
}

.stat-divider {
  width: 1px;
  height: 40px;
  background: rgba(255,255,255,0.2);
}

.section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
  margin: 0 0 16px 0;
}

.horizontal-scroll {
  display: flex;
  gap: 16px;
  overflow-x: auto;
  padding-bottom: 8px;
  scrollbar-width: thin;
}
.horizontal-scroll::-webkit-scrollbar { height: 4px; }
.horizontal-scroll::-webkit-scrollbar-thumb { background: #d4d4d8; border-radius: 4px; }

.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.fav-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 12px;
  padding: 16px;
  transition: transform 0.15s, box-shadow 0.15s;
}
.fav-card:hover { transform: translateY(-2px); box-shadow: 0 8px 24px rgba(31,41,55,.09); }

.fav-card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.fav-name { font-weight: 600; font-size: 14px; color: #111827; }
.fav-unfollow {
  width: 20px; height: 20px; border-radius: 50%; border: none;
  background: #f3f4f6; color: #4b5563; font-size: 11px; line-height: 20px;
  text-align: center; cursor: pointer; transition: all 0.15s;
}
.fav-unfollow:hover { background: #ef4444; color: white; }
.fav-card-market { font-size: 11.5px; color: #374151; font-weight: 500; margin-bottom: 10px; }
.fav-card-spec { font-size: 11.5px; color: #374151; font-weight: 500; }

.fav-card-main { display: flex; align-items: baseline; gap: 6px; margin-bottom: 10px; }
.fav-main-price { font-size: 24px; font-weight: 700; color: #111827; letter-spacing: -0.02em; }
.fav-main-unit { font-size: 11.5px; color: #4b5563; }

.fav-card-detail {
  display: flex; gap: 12px; padding-top: 10px;
  border-top: 1px solid #f3f4f6;
}
.fav-detail-item { display: flex; flex-direction: column; gap: 2px; }
.fav-detail-label { font-size: 10.5px; color: #52525b; font-weight: 500; text-transform: uppercase; letter-spacing: 0.03em; }
.fav-detail-value { font-size: 12px; color: #1f2937; font-weight: 500; font-family: 'JetBrains Mono', monospace; }
.fav-date { font-size: 11.5px; color: #4b5563; }

.empty-fav { padding: 32px; text-align: center; color: #52525b; background: var(--card); border-radius: 12px; border: 1px dashed #e4e4e7; }

.source-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.source-card {
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 16px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.source-card:hover {
  transform: scale(0.98);
  box-shadow: 0 8px 24px rgba(31,41,55,.09);
}

.source-card.delayed {
  border: 1px solid #f59e0b;
}

.pulse {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #3b82f6;
  position: relative;
}

.pulse::after {
  content: '';
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  background: rgba(59, 130, 246, 0.2);
  animation: pulse 2s infinite;
}

.pulse-amber {
  background: #f59e0b;
}

.pulse-amber::after {
  background: rgba(245, 158, 11, 0.2);
}

.pulse-green {
  background: #22c55e;
}

.pulse-green::after {
  background: rgba(34, 197, 94, 0.2);
}

.pulse-blue {
  background: #3b82f6;
}

.pulse-blue::after {
  background: rgba(59, 130, 246, 0.2);
}

.pulse-purple {
  background: #a855f7;
}

.pulse-purple::after {
  background: rgba(168, 85, 247, 0.2);
}

@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.2); opacity: 0.5; }
}

.source-name {
  font-size: 14px;
  font-weight: 500;
  color: #111827;
}

.source-count {
  font-size: 13px;
  color: #6b7280;
  margin-top: 2px;
  font-variant-numeric: tabular-nums;
}

.price-scroll {
  display: flex;
  gap: 16px;
  overflow-x: auto;
  padding-bottom: 8px;
  scrollbar-width: thin;
  scrollbar-color: #e5e7eb transparent;
}

.price-card {
  min-width: 240px;
  background: rgba(255,255,255,.42);
  backdrop-filter: blur(16px) saturate(150%);
  -webkit-backdrop-filter: blur(16px) saturate(150%);
  border: 1px solid rgba(255,255,255,.9);
  box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10);
  border-radius: 16px;
  padding: 20px;
  flex-shrink: 0;
  transition: transform 0.2s, box-shadow 0.2s;
}

.price-card:hover {
  transform: scale(0.98);
  box-shadow: 0 8px 24px rgba(31,41,55,.09);
}

.price-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.price-name {
  font-size: 14px;
  font-weight: 600;
  color: #111827;
}

.price-market {
  font-size: 11px;
  color: #6b7280;
}

.price-body {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}

.price-value {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: #111827;
}

.price-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.price-unit {
  font-size: 11px;
  color: #6b7280;
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

.badge-m {
  /* 市场价格 → 蓝（类型色避开红绿，全站统一） */
  background: #dbeafe;
  color: #1d4ed8;
  border-color: #bfdbfe;
}

.badge-e {
  /* 企业价格 → 紫（类型色避开红绿，全站统一） */
  background: #f3e8ff;
  color: #7e22ce;
  border-color: #e9d5ff;
}

.badge-i {
  background: #fef3c7;
  color: #92400e;
  border-color: #fde68a;
}

.badge-up {
  background: #fee2e2;
  color: #dc2626;
  border-color: #fecaca;
}

.badge-dn {
  background: #dcfce7;
  color: #15803d;
  border-color: #bbf7d0;
}

.badge-fl {
  background: #eceef1;
  color: #71717a;
  border-color: #d5d9e0;
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

.table-container {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.table th.text-right, .table td.text-right {
  text-align: right !important;
  font-variant-numeric: tabular-nums;
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

.text-right {
  text-align: right !important;
}

.font-semibold {
  font-weight: 600;
}

.text-primary {
  color: #3b82f6;
}

.n {
  font-variant-numeric: tabular-nums;
  font-family: 'JetBrains Mono', monospace;
}

@media (max-width: 900px) {
  .source-grid {
    grid-template-columns: 1fr;
  }
  
  .hero {
    padding: 40px 24px;
  }
  
  .hero-title {
    font-size: 36px;
  }
  
  .hero-stats {
    flex-direction: column;
    gap: 20px;
  }
  
  .stat-divider {
    width: 40px;
    height: 1px;
  }
}
.source-card.clickable { cursor: pointer; transition: transform .18s ease, box-shadow .18s ease; }
.source-card.clickable:hover { transform: translateY(-3px); box-shadow: 0 10px 26px rgba(0, 0, 0, .10); }
.source-card.clickable:active { transform: translateY(-1px); }

.fav-date.stale { color: #b45309; }
.fav-stale-tag {
  font-style: normal; font-size: 10px; margin-left: 4px;
  padding: 0 4px; border-radius: 4px;
  color: #b45309; background: #fffbeb; border: 1px solid #fde68a;
}

/* ===== 关注筛选栏（商品 / 报价点 / 规格）===== */
.fav-filter {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: -6px 0 16px;
}
.fav-filter .ff-sel { flex: none; }
.fav-filter .ff-w1 { width: 210px; }
.fav-filter .ff-w2 { width: 175px; }
.fav-filter .ff-w3 { width: 150px; }
.ff-reset {
  background: transparent; border: 1px solid #e4e4e7; color: #71717a;
  font-size: 12px; border-radius: 6px; padding: 6px 12px; cursor: pointer;
  transition: all .2s;
}
.ff-reset:hover { background: #f4f4f5; color: #374151; }
.ff-sum { font-size: 12px; color: #a1a1aa; margin-left: 4px; }
.ff-sum.on { color: #2563eb; font-weight: 600; }
@media (max-width: 760px) {
  .fav-filter .ff-w1, .fav-filter .ff-w2, .fav-filter .ff-w3 { width: 100%; }
  .ff-sum { margin-left: 0; }
}
</style>

<style scoped>
.sec-head-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.sec-head-row .section-title { margin: 0; }
.push-set-btn { background: transparent; border: 1px solid #bfdbfe; color: #2563eb; font-size: 12px; border-radius: 6px; padding: 5px 12px; cursor: pointer; transition: all .2s; }
.push-set-btn:hover { background: #eff6ff; }
</style>
