<template>
  <div class="ua">
    <!-- 页头 -->
    <div class="pg-hd">
      <div class="pg-l">
        <h3 class="pg-t">用户看板</h3>
        <span class="pg-tag">仅管理员可见</span>
      </div>
      <div class="pg-r">
        <label class="ua-tgl" :class="{ on: includeTest }" title="自动化测试账号操作量极大，默认不计入任何统计">
          <input type="checkbox" v-model="includeTest" @change="reload" />
          <span class="ua-tgl-track"><i></i></span>
          包含测试账号
        </label>
        <button class="pg-note-btn" :class="{ on: showNote }" @click="showNote = !showNote">
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="9" /><path d="M12 16v-5M12 8h.01" stroke-linecap="round" />
          </svg>
          统计口径
        </button>
      </div>
    </div>
    <div v-if="showNote" class="pg-note">
      · <b>测试账号默认不计入任何统计</b>（用户名含 test 或昵称含「测试」，如 mobile_test / ui_test / test01 / pushtest*）。
      这类自动化账号操作量极大，不排除会霸占活跃榜、并把用户总数一起抬高。右上「包含测试账号」可切换口径。<br />
      · <b>登录</b>＝输入账号密码成功（取自审计日志）。JWT 有效期 7 天，期间免登录不重复计数，所以这个数<b>低于实际使用人数</b>。<br />
      · <b>活跃</b>＝当天有任意操作留痕（登录 / 问价 / 导出 / 发布等），纯浏览不记录，更接近「真的来过」。<br />
      · 两者均<b>只统计现存账号</b>：已删除账号留下的 {{ num(sleep.ghostRows) }} 条历史日志已排除，否则活跃数会超过用户总数。<br />
      · 「用户构成」「用户参与度」是<b>累计快照</b>，不随上方趋势区间变化。
    </div>

    <div v-if="loading" class="ua-empty">正在加载用户数据…</div>

    <template v-else>
      <div v-if="!includeTest && testUsers > 0" class="ua-excl">
        <TriangleAlert :size="13" />
        已排除 <b>{{ testUsers }}</b> 个测试账号（自动化测试用，非真实用户）
        <span class="muted">— 勾选右上「包含测试账号」可查看含测试号的完整口径</span>
      </div>

      <!-- 今日汇总 -->
      <div class="ai-sum">
        <div class="sum-card">
          <div class="sc-ic blue"><UserPlus :size="18" /></div>
          <div class="sc-b">
            <div class="sc-l">今日新增用户</div>
            <div class="sc-v n">{{ num(today.newUsers) }}<i>人</i></div>
            <div class="sc-s">昨日 {{ num(today.yNewUsers) }} 人</div>
          </div>
        </div>
        <div class="sum-card">
          <div class="sc-ic violet"><LogIn :size="18" /></div>
          <div class="sc-b">
            <div class="sc-l">今日登录人数</div>
            <div class="sc-v n">{{ num(today.loginUsers) }}<i>人</i></div>
            <div class="sc-s">共 {{ num(today.loginTimes) }} 人次 · 昨日 {{ num(today.yLoginUsers) }} 人</div>
          </div>
        </div>
        <div class="sum-card">
          <div class="sc-ic amber"><Activity :size="18" /></div>
          <div class="sc-b">
            <div class="sc-l">今日活跃用户</div>
            <div class="sc-v n">{{ num(today.activeUsers) }}<i>人</i></div>
            <div class="sc-s">昨日 {{ num(today.yActiveUsers) }} 人</div>
          </div>
        </div>
        <div class="sum-card">
          <div class="sc-ic green"><Users :size="18" /></div>
          <div class="sc-b">
            <div class="sc-l">用户总数</div>
            <div class="sc-v n">{{ num(total.users) }}<i>人</i></div>
            <div class="sc-s">管理员 {{ num(total.admins) }} · 禁用 {{ num(total.disabled) }}</div>
          </div>
        </div>
      </div>

      <!-- 趋势 -->
      <div class="card">
        <div class="card-hd">
          <div class="ch-tt">
            <h4>注册与登录趋势</h4>
            <div class="ch-hint">柱＝当日新增注册；折线＝当日登录人数 / 活跃人数。三者都是「人数」，同尺度对比，均按账号去重</div>
          </div>
          <div class="range-tabs">
            <button v-for="r in RANGES" :key="r.d" class="range-tab" :class="{ on: days === r.d }"
                    @click="switchDays(r.d)">{{ r.t }}</button>
          </div>
        </div>
        <div class="chart-box"><div ref="trendRef" class="chart"></div></div>
      </div>

      <!-- 构成 + 活跃榜 -->
      <div class="ua-row">
        <div class="card">
          <div class="card-hd">
            <div class="ch-tt">
              <h4>用户构成</h4>
              <div class="ch-hint">按账号当前属性统计（累计快照，非当日值）</div>
            </div>
          </div>
          <div class="comp-wrap">
            <div v-for="g in composition" :key="g.title" class="comp">
              <div class="comp-hd">
                <span class="comp-title">{{ g.title }}</span>
                <span class="comp-total n">{{ g.segs.reduce((a, s) => a + (s.v || 0), 0) }}</span>
              </div>
              <div class="comp-bar">
                <span v-for="(s, i) in g.segs" v-show="s.v > 0" :key="i" class="seg"
                      :style="{ width: pct(s.v, g.segs), background: s.c }" :title="s.label + '：' + s.v"></span>
              </div>
              <div class="comp-lg">
                <span v-for="(s, i) in g.segs" :key="i" class="lg-item" :class="{ zero: !s.v }">
                  <i :style="{ background: s.c }"></i>{{ s.label }} <b class="n">{{ s.v }}</b>
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-hd">
            <div class="ch-tt">
              <h4>近 7 天活跃榜</h4>
              <div class="ch-hint">按操作留痕条数排序（登录 / 问价 / 导出 / 发布等，纯浏览不计）</div>
            </div>
          </div>
          <table class="ua-tb">
            <thead>
              <tr><th class="c-rk">#</th><th>用户</th><th class="tr">操作数</th><th class="tr">活跃天数</th><th class="tr">最近操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="(u, i) in topActive" :key="u.username">
                <td class="c-rk"><span class="rk" :class="'rk' + (i + 1)">{{ i + 1 }}</span></td>
                <td>
                  <div class="u-nm">
                    {{ u.nickname || u.username }}
                    <span v-if="u.role === 'ADMIN'" class="u-adm">管理员</span>
                  </div>
                  <div class="u-un">{{ u.username }}</div>
                </td>
                <td class="tr n">{{ u.cnt }}</td>
                <td class="tr n">{{ u.days }}</td>
                <td class="tr n u-time">{{ u.lastAt }}</td>
              </tr>
              <tr v-if="!topActive.length"><td colspan="5" class="empty">近 7 天暂无操作记录</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 参与度 -->
      <div class="card">
        <div class="card-hd">
          <div class="ch-tt">
            <h4>用户参与度</h4>
            <div class="ch-hint">每个指标＝有多少个账号到达过这一步（累计，分母为全部 {{ num(funnel.total) }} 个账号）</div>
          </div>
        </div>
        <div class="fn-wrap">
          <div v-for="f in funnelRows" :key="f.k" class="fn-row">
            <span class="fn-label">{{ f.t }}</span>
            <div class="fn-track"><div class="fn-fill" :style="{ width: fpct(f.v), background: f.c }"></div></div>
            <span class="fn-val"><b class="n">{{ f.v }}</b><em>{{ fpct(f.v) }}</em></span>
          </div>
        </div>
      </div>

      <!-- 沉睡 & 来源 -->
      <div class="ua-row">
        <div class="card note-card">
          <div class="note-hd"><TriangleAlert :size="15" /> 沉睡与流失</div>
          <ul>
            <li>
              从未登录过：<b class="n">{{ num(sleep.neverLogin) }}</b> 个账号
              <span class="muted">（其中启用中 {{ num(sleep.neverLoginActive) }} 个，才是可激活的目标）</span>
            </li>
            <li>
              近 7 天无任何操作：<b class="n">{{ num(total.users - sleep.active7) }}</b> 个账号
              <span class="muted">（近 {{ days }} 天活跃 {{ num(sleep.activeN) }} 个）</span>
            </li>
            <li>已禁用账号：<b class="n">{{ num(sleep.disabled) }}</b> 个</li>
          </ul>
        </div>
        <div class="card note-card">
          <div class="note-hd"><CalendarDays :size="15" /> 账号来源（近 {{ days }} 天）</div>
          <ul>
            <li>自助注册：<b class="n">{{ num(source.selfReg) }}</b> 个</li>
            <li>管理员创建：<b class="n">{{ num(source.adminCreate) }}</b> 个</li>
            <li class="muted">该区间内没有新增账号时显示 0，属正常。</li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Users, UserPlus, LogIn, Activity, TriangleAlert, CalendarDays } from 'lucide-vue-next'
import { getUserStats } from '../api/index'

const RANGES = [
  { d: 7, t: '近 7 天' },
  { d: 30, t: '近 30 天' },
  { d: 90, t: '近 90 天' }
]

const showNote = ref(false)
const loading = ref(true)
const days = ref(30)

const includeTest = ref(false)
const testUsers = ref(0)

const today = ref({})
const total = ref({})
const funnel = ref({})
const sleep = ref({})
const source = ref({})
const topActive = ref([])
const trend = ref([])

const trendRef = ref(null)
let chart = null

function num(v) {
  return v == null ? '—' : Number(v).toLocaleString('en-US')
}

/* 堆叠条：单段占比（按各段之和归一，容错 0） */
function pct(v, segs) {
  const tot = segs.reduce((a, s) => a + (s.v || 0), 0) || 1
  return (((v || 0) / tot) * 100).toFixed(3) + '%'
}
function fpct(v) {
  const tot = funnel.value.total || 1
  return (((v || 0) / tot) * 100).toFixed(0) + '%'
}

/* 用户构成（累计快照）——颜色避开红绿涨跌语义，仅「禁用」用红以示警示 */
const composition = computed(() => {
  const t = total.value
  const u = t.users || 0
  const admins = t.admins || 0
  const disabled = t.disabled || 0
  const rn = t.realnamed || 0
  const sup = t.suppliers || 0
  const pend = t.supplierPending || 0
  const rev = t.supplierReverify || 0
  return [
    {
      title: '账号角色',
      segs: [
        { label: '管理员', v: admins, c: '#7c3aed' },
        { label: '普通用户', v: Math.max(0, u - admins), c: '#3b82f6' }
      ]
    },
    {
      title: '启用状态',
      segs: [
        { label: '启用', v: Math.max(0, u - disabled), c: '#0ea5e9' },
        { label: '禁用', v: disabled, c: '#dc2626' }
      ]
    },
    {
      title: '实名认证',
      segs: [
        { label: '已实名', v: rn, c: '#14b8a6' },
        { label: '未实名', v: Math.max(0, u - rn), c: '#cbd5e1' }
      ]
    },
    {
      title: '企业认证',
      segs: [
        { label: '已认证', v: sup, c: '#f59e0b' },
        { label: '审核中', v: pend, c: '#fbbf24' },
        { label: '需重新认证', v: rev, c: '#f87171' },
        { label: '未认证', v: Math.max(0, u - sup - pend - rev), c: '#e2e8f0' }
      ]
    }
  ]
})

/* 参与度阶梯（累计快照） */
const funnelRows = computed(() => {
  const f = funnel.value
  return [
    { k: 'logged', t: '登录过（已激活）', v: f.logged || 0, c: '#3b82f6' },
    { k: 'phone', t: '绑定手机号', v: f.phoneBound || 0, c: '#0ea5e9' },
    { k: 'fav', t: '关注过商品', v: f.favorited || 0, c: '#8b5cf6' },
    { k: 'ai', t: '用过 AI 问价', v: f.aiUser || 0, c: '#a855f7' },
    { k: 'rn', t: '完成实名认证', v: f.realnamed || 0, c: '#14b8a6' },
    { k: 'demand', t: '发布过供需', v: f.demandUser || 0, c: '#f59e0b' },
    { k: 'sup', t: '完成企业认证', v: f.suppliers || 0, c: '#f97316' },
    { k: 'exp', t: '有导出权限', v: total.value.exporters || 0, c: '#64748b' }
  ]
})

function renderTrend() {
  const el = trendRef.value
  if (!el) return
  // ⚠️ loading 切换会让 v-else 整块被卸载重建，旧实例绑的 DOM 已脱离文档，
  // 此时 setOption 画在看不见的元素上（表现为图表区域一片空白）→ 必须重建实例
  if (chart && chart.getDom() !== el) { chart.dispose(); chart = null }
  if (!chart) chart = echarts.init(el)
  const d = trend.value || []
  const n = d.length
  const step = n > 60 ? 7 : n > 30 ? 4 : n > 14 ? 2 : 0
  chart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: {
      data: ['新增注册', '登录人数', '活跃人数'],
      bottom: 0, itemWidth: 12, itemHeight: 8,
      textStyle: { fontSize: 12, color: '#4b5563' }
    },
    grid: { left: 6, right: 12, top: 26, bottom: 46, containLabel: true },
    xAxis: {
      type: 'category', data: d.map(r => r.d),
      axisLine: { lineStyle: { color: '#e5e7eb' } },
      axisTick: { show: false },
      axisLabel: { color: '#6b7280', fontSize: 11, interval: step }
    },
    yAxis: {
      type: 'value', minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f3f6' } },
      axisLabel: { color: '#6b7280', fontSize: 11 }
    },
    series: [
      {
        name: '新增注册', type: 'bar', barMaxWidth: 16,
        data: d.map(r => r.reg),
        itemStyle: { color: '#3b82f6', borderRadius: [3, 3, 0, 0] }
      },
      {
        name: '登录人数', type: 'line', smooth: true,
        symbol: 'circle', symbolSize: 5, data: d.map(r => r.loginUsers),
        itemStyle: { color: '#a855f7' }, lineStyle: { width: 2 }
      },
      {
        name: '活跃人数', type: 'line', smooth: true,
        symbol: 'circle', symbolSize: 5, data: d.map(r => r.activeUsers),
        itemStyle: { color: '#f59e0b' }, lineStyle: { width: 2 }
      }
    ]
  }, true)
  chart.resize()
}

async function load(isFirst = false) {
  if (isFirst) loading.value = true
  try {
    const res = await getUserStats({ days: days.value, includeTest: includeTest.value })
    if (res.code === 200) {
      const d = res.data || {}
      testUsers.value = d.testUsers || 0
      today.value = d.today || {}
      total.value = d.total || {}
      funnel.value = d.funnel || {}
      sleep.value = d.sleep || {}
      source.value = d.source || {}
      topActive.value = d.topActive || []
      trend.value = d.trend || []
    } else {
      ElMessage.error(res.message || '加载失败')
    }
  } catch (e) {
    console.error(e)
  } finally {
    if (isFirst) {
      loading.value = false
      await nextTick()
    }
    renderTrend()
  }
}

async function switchDays(d) {
  if (days.value === d) return
  days.value = d
  await load(false)   // 不切 loading：切区间时若卸载 DOM，会连带废掉图表实例
}

async function reload() {
  await load(false)
}

function onResize() { if (chart) chart.resize() }

onMounted(() => {
  load(true)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.ua { padding-bottom: 24px; }

/* ---------- 页头（对齐 AiLogs.vue 约定） ---------- */
.pg-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.pg-l { display: flex; align-items: center; gap: 9px; }
.pg-t { font-size: 17px; font-weight: 700; color: #18181b; margin: 0; letter-spacing: .2px; }
.pg-tag {
  font-size: 11px; color: #7c3aed; background: #f5f3ff; border: 1px solid #e9d5ff;
  padding: 1px 7px; border-radius: 20px; line-height: 17px;
}
.pg-note-btn {
  display: inline-flex; align-items: center; gap: 4px;
  height: 28px; padding: 0 10px; border: 1px solid #e2e8f0; background: #fff;
  border-radius: 7px; font-size: 12px; color: #64748b; cursor: pointer; font-family: inherit;
}
.pg-note-btn:hover, .pg-note-btn.on { border-color: #bfdbfe; color: #2563eb; background: #f8fbff; }
.pg-note {
  background: #f8fafc; border: 1px solid #e8edf3; border-radius: 10px;
  padding: 11px 14px; font-size: 12px; color: #64748b; line-height: 1.9; margin-bottom: 12px;
}
.pg-note b { color: #334155; }

.pg-r { display: flex; align-items: center; gap: 10px; }

/* 「包含测试账号」开关 */
.ua-tgl {
  display: inline-flex; align-items: center; gap: 6px; height: 28px;
  padding: 0 9px 0 6px; border: 1px solid #e2e8f0; background: #fff; border-radius: 7px;
  font-size: 12px; color: #64748b; cursor: pointer; user-select: none; transition: all .15s;
}
.ua-tgl input { position: absolute; opacity: 0; width: 0; height: 0; }
.ua-tgl-track {
  width: 30px; height: 16px; border-radius: 9px; background: #cbd5e1;
  position: relative; transition: background .18s; flex: none;
}
.ua-tgl-track i {
  position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%;
  background: #fff; transition: transform .18s; box-shadow: 0 1px 2px rgba(0,0,0,.2);
}
.ua-tgl:hover { border-color: #cbd5e1; color: #475569; }
.ua-tgl.on { color: #2563eb; border-color: #bfdbfe; background: #f8fbff; }
.ua-tgl.on .ua-tgl-track { background: #2563eb; }
.ua-tgl.on .ua-tgl-track i { transform: translateX(14px); }

/* 测试账号排除提示 */
.ua-excl {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px;
  padding: 9px 13px; font-size: 12px; color: #92400e; margin-bottom: 12px;
}
.ua-excl svg { flex: none; color: #d97706; }
.ua-excl b { color: #78350f; }
.ua-excl .muted { color: #b45309; }

.ua-empty {
  padding: 44px; text-align: center; color: #94a3b8; font-size: 13px;
  background: #fff; border: 1px solid #e8edf3; border-radius: 12px;
}

/* ---------- 汇总卡 ---------- */
.ai-sum { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 12px; }
.sum-card {
  display: flex; gap: 12px; align-items: flex-start;
  background: #fff; border: 1px solid #e8edf3; border-radius: 12px; padding: 15px 16px;
  transition: box-shadow .18s, border-color .18s;
}
.sum-card:hover { border-color: #dbeafe; box-shadow: 0 4px 14px rgba(37, 99, 235, .08); }
.sc-ic {
  flex: none; width: 34px; height: 34px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
}
.sc-ic.blue { background: #eff6ff; color: #2563eb; }
.sc-ic.violet { background: #f5f3ff; color: #7c3aed; }
.sc-ic.green { background: #ecfdf5; color: #059669; }
.sc-ic.amber { background: #fffbeb; color: #d97706; }
.sc-b { min-width: 0; }
.sc-l { font-size: 12px; color: #94a3b8; margin-bottom: 3px; }
.sc-v { font-size: 23px; font-weight: 700; color: #18181b; line-height: 1.2; font-variant-numeric: tabular-nums; }
.sc-v i { font-size: 12px; font-weight: 500; color: #94a3b8; font-style: normal; margin-left: 3px; }
.sc-s { font-size: 11.5px; color: #6b7280; margin-top: 5px; }

/* ---------- 卡片 ---------- */
.card {
  background: #fff; border: 1px solid #e8edf3; border-radius: 12px;
  margin-bottom: 12px; overflow: hidden;
}
.card-hd {
  padding: 14px 16px; border-bottom: 1px solid #f1f5f9;
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.ch-tt { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
.ch-tt h4 { margin: 0; font-size: 14px; font-weight: 700; color: #18181b; letter-spacing: .2px; }
.ch-hint { font-size: 11.5px; color: #52525b; line-height: 1.55; }

/* 时间区间切换（对齐 AiLogs） */
.range-tabs {
  display: inline-flex; align-items: center; gap: 2px; padding: 2px;
  background: #f4f4f5; border-radius: 9px; flex: none;
}
.range-tab {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  font-size: 12px; color: #52525b; padding: 6px 12px; border-radius: 7px;
  transition: all .15s; white-space: nowrap;
}
.range-tab:hover { color: #18181b; }
.range-tab.on { background: #fff; color: #111827; font-weight: 600; box-shadow: 0 1px 2px rgba(0,0,0,.06); }

/* ---------- 图表 ---------- */
.chart-box { padding: 12px 16px 16px; }
.chart { width: 100%; height: 320px; }

/* ---------- 两列 ---------- */
.ua-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.ua-row > .card { margin-bottom: 12px; }

/* ---------- 用户构成 ---------- */
.comp-wrap { padding: 14px 16px 16px; display: flex; flex-direction: column; gap: 15px; }
.comp-hd { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 6px; }
.comp-title { font-size: 12.5px; color: #334155; font-weight: 600; }
.comp-total { font-size: 12px; color: #94a3b8; }
.comp-bar {
  display: flex; height: 10px; border-radius: 5px; overflow: hidden;
  background: #f1f5f9;
}
.seg { height: 100%; transition: width .4s ease; }
.comp-lg { display: flex; flex-wrap: wrap; gap: 4px 14px; margin-top: 7px; }
.lg-item {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 11.5px; color: #52525b;
}
.lg-item.zero { color: #a1a1aa; }
.lg-item i { width: 8px; height: 8px; border-radius: 2px; flex: none; }
.lg-item b { color: #1f2937; font-weight: 600; }
.lg-item.zero b { color: #a1a1aa; }

/* ---------- 活跃榜 ---------- */
.ua-tb { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.ua-tb th {
  text-align: left; font-weight: 600; color: #64748b; font-size: 11.5px;
  padding: 9px 10px; border-bottom: 1px solid #f1f5f9; white-space: nowrap;
}
.ua-tb td { padding: 9px 10px; border-bottom: 1px solid #f8fafc; color: #334155; vertical-align: middle; }
.ua-tb tr:last-child td { border-bottom: 0; }
.ua-tb .tr { text-align: right; }
.c-rk { width: 34px; }
.rk {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px; border-radius: 6px; font-size: 11px; font-weight: 700;
  background: #f1f5f9; color: #64748b;
}
.rk1 { background: #fef3c7; color: #b45309; }
.rk2 { background: #e2e8f0; color: #475569; }
.rk3 { background: #ffedd5; color: #c2410c; }
.u-nm { font-weight: 600; color: #18181b; display: flex; align-items: center; gap: 6px; }
.u-adm {
  font-size: 10px; font-weight: 600; color: #7c3aed; background: #f5f3ff;
  border: 1px solid #e9d5ff; border-radius: 20px; padding: 0 6px; line-height: 15px;
}
.u-un { font-size: 11px; color: #94a3b8; margin-top: 2px; }
.u-time { color: #64748b; }
.empty { text-align: center; color: #94a3b8; padding: 26px 0 !important; }

/* ---------- 参与度 ---------- */
.fn-wrap { padding: 14px 16px 18px; display: flex; flex-direction: column; gap: 11px; }
.fn-row { display: grid; grid-template-columns: 128px 1fr 86px; align-items: center; gap: 12px; }
.fn-label { font-size: 12.5px; color: #334155; }
.fn-track { height: 12px; background: #f1f5f9; border-radius: 6px; overflow: hidden; }
.fn-fill { height: 100%; border-radius: 6px; transition: width .45s ease; min-width: 2px; }
.fn-val { text-align: right; font-size: 12px; color: #64748b; }
.fn-val b { color: #1f2937; font-size: 13px; margin-right: 6px; }
.fn-val em { font-style: normal; color: #94a3b8; }

/* ---------- 提示卡 ---------- */
.note-card { padding: 14px 16px 16px; }
.note-hd {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; font-weight: 700; color: #18181b; margin-bottom: 9px;
}
.note-hd svg { color: #d97706; }
.note-card ul { margin: 0; padding-left: 2px; list-style: none; }
.note-card li {
  font-size: 12.5px; color: #52525b; line-height: 2.1;
  display: flex; align-items: baseline; gap: 4px; flex-wrap: wrap;
}
.note-card li::before { content: '·'; color: #cbd5e1; margin-right: 2px; }
.note-card b { color: #18181b; font-weight: 700; }
.note-card .muted { color: #94a3b8; }

/* ---------- 响应式 ---------- */
@media (max-width: 1100px) {
  .ai-sum { grid-template-columns: repeat(2, 1fr); }
  .ua-row { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .fn-row { grid-template-columns: 96px 1fr 74px; gap: 8px; }
  .fn-label { font-size: 12px; }
  .chart { height: 260px; }
  .card-hd { flex-direction: column; align-items: flex-start; }
}
@media (max-width: 520px) {
  .ai-sum { grid-template-columns: 1fr; }
  .ua-tb th:nth-child(4), .ua-tb td:nth-child(4) { display: none; }
}
</style>
