<template>
  <div class="ai-wrap">
    <!-- 悬浮入口（圆形；可拖动，松手自动吸附到最近的左/右边缘） -->
    <button v-if="!open" ref="fabRef" class="ai-fab" :style="fab.style"
            :class="{ 'fab-dragging': fab.dragging }"
            title="AI 智能问价（可拖动，松手自动吸附边缘）"
            @pointerdown="fab.onPointerDown">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 3l1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9z"/>
        <path d="M19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"/>
        <path d="M5 15l.7 1.6L7.5 17l-1.8.7L5 19.5l-.7-1.8L2.5 17l1.8-.4z"/>
      </svg>
      <span class="fab-txt">AI问价</span>
    </button>

    <!-- 聊天面板 -->
    <Transition name="ai-pop">
      <section v-if="open" class="ai-panel" :class="{ fullscreen }">
        <header class="ai-hd">
          <div class="ai-hd-l">
            <span class="ai-dot"></span>
            <b>AI 智能问价</b>
            <span class="ai-sub">基于本站数据 · 遵循你的数据权限</span>
          </div>
          <div class="ai-hd-r">
            <button class="ai-ic" title="清空对话" @click="clearChat">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4h8v2m1 0v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V6"/></svg>
            </button>
            <button class="ai-ic" :title="fullscreen ? '退出全屏' : '全屏'" @click="toggleFull">
              <svg v-if="!fullscreen" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/></svg>
              <svg v-else viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/></svg>
            </button>
            <button class="ai-ic" title="收起" @click="open = false">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
            </button>
          </div>
        </header>

        <div ref="scrollRef" class="ai-msgs">
          <template v-for="(m, i) in messages" :key="i">
            <!-- 用户消息 -->
            <div v-if="m.role === 'user'" class="msg user">
              <div class="bub user-bub">{{ m.text }}</div>
            </div>
            <!-- AI 消息 -->
            <div v-else class="msg ai">
              <div class="ai-ava">
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3l1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9z"/></svg>
              </div>
              <div class="ai-col">
                <div class="bub ai-bub">
                  <!-- 错误/提示类 -->
                  <div v-if="m.kind === 'error'" class="ai-err">
                    <span class="err-ic">⚠️</span>{{ m.reply }}
                  </div>
                  <!-- 澄清类：问题 + 可点选项 -->
                  <div v-else-if="m.kind === 'clarify'">
                    <p class="cl-txt">{{ m.reply || m.hint }}</p>
                    <div v-if="m.options && m.options.length" class="cl-opts">
                      <button v-for="(o, oi) in m.options" :key="oi" class="cl-chip" @click="quickAsk(m.reasks && m.reasks[oi] ? m.reasks[oi] : ('最新价格 ' + o))">
                        {{ o }}
                      </button>
                    </div>
                    <div v-if="m.actions && m.actions.length" class="ai-acts">
                      <button v-for="(a, aiIdx) in m.actions" :key="'a' + aiIdx" class="ai-act"
                              @click="goAction(a)">{{ a.label }} →</button>
                    </div>
                  </div>
                  <!-- 普通回答 -->
                  <div v-else class="ai-txt">
                    <p v-for="(seg, si) in splitLines(m.reply)" :key="si" class="ai-para">{{ seg }}</p>
                  </div>

                  <!-- 危险化学品拦截提示（AI 代发时被跳过的品种） -->
                  <div v-if="m.blocked && m.blocked.length" class="ai-blocked">
                    <div class="abk-head">
                      <span class="abk-ic">⚠</span>
                      <span>以下 {{ m.blocked.length }} 个品种属于危险化学品/易制毒易制爆品类，<b>平台禁止发布</b>，已自动跳过：</span>
                    </div>
                    <div class="abk-list">
                      <span v-for="(b, bi) in m.blocked" :key="'b' + bi" class="abk-chip">
                        {{ b.name }}<template v-if="b.quantity">｜{{ b.quantity }}{{ b.unit || '吨' }}</template>
                      </span>
                    </div>
                    <div class="abk-foot">如需发布其他合规品种，可继续直接告诉我，或到「供需广场」手动发布。</div>
                  </div>
                </div>

                <!-- 走势图 -->
                <div v-if="m.chart && m.chart.values && m.chart.values.length" class="ai-chart-box">
                  <div class="chart-cap">{{ m.chart.name }}</div>
                  <div :ref="el => chartEls[i] = el" class="ai-chart"></div>
                </div>

                <!-- 报价表格 -->
                <div v-if="m.quotes && m.quotes.length" class="ai-q-wrap">                  <table class="ai-q">
                    <thead>
                      <tr><th>商品</th><th>报价点</th><th>价格</th><th>涨跌</th><th>日期</th></tr>
                    </thead>
                    <tbody>
                      <tr v-for="(q, qi) in trimQuotes(m.quotes)" :key="qi">
                        <td class="c-name">{{ q.name }}</td>
                        <td>{{ q.market }}<span v-if="q.spec" class="q-spec"> · {{ q.spec }}</span></td>
                        <td class="c-price">{{ fmtNum(q.price) }}<span v-if="q.unit" class="q-unit">{{ q.unit }}</span></td>
                        <td>
                          <span v-if="q.change == null && q.rate == null" class="c-flat">—</span>
                          <span v-else-if="q.change == null || q.change === 0" class="c-flat">持平</span>
                          <span v-else :class="q.change > 0 ? 'c-up' : 'c-down'">
                            {{ q.change > 0 ? '+' : '' }}{{ fmtNum(q.change) }}<span v-if="q.rate != null"> ({{ q.rate > 0 ? '+' : '' }}{{ fmtNum(q.rate) }}%)</span>
                          </span>
                        </td>
                        <td class="c-date">{{ q.date }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <!-- 数据导出下载 -->
                <div v-if="m.exportInfo" class="ai-exp">
                  <div class="exp-h">
                    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/></svg>
                    <span>Excel 已生成</span>
                  </div>
                  <p class="exp-meta">
                    {{ m.exportInfo.product }} · {{ m.exportInfo.start }} ~ {{ m.exportInfo.end }}
                    （近 {{ m.exportInfo.days }} 天）· 共 {{ m.exportInfo.rows }} 行
                  </p>
                  <a class="exp-btn" :href="m.exportInfo.url" :download="m.exportInfo.filename">
                    下载 {{ m.exportInfo.filename }}
                  </a>
                  <p class="exp-tip">下载链接 30 分钟内有效；文件保留 2 天，请及时保存。</p>
                </div>

                <!-- 跳转按钮（如：去供需广场查看） -->
                <div v-if="m.actions && m.actions.length" class="ai-acts">
                  <button v-for="(a, aiIdx) in m.actions" :key="aiIdx" class="ai-act"
                          @click="goAction(a)">{{ a.label }} →</button>
                </div>
              </div>
            </div>
          </template>

          <!-- 正在思考 -->
          <div v-if="loading" class="msg ai">
            <div class="ai-ava">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3l1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9z"/></svg>
            </div>
            <div class="typing"><i></i><i></i><i></i></div>
          </div>
        </div>

        <footer class="ai-ft">
          <!-- 常用功能用法：点一下让 AI 讲解该功能怎么操作（走后端 platform_help 意图） -->
          <div class="ai-howto">
            <span class="hw-label">用法</span>
            <button v-for="(h, hi) in howto" :key="hi" class="hw-chip"
                    :disabled="loading" @click="quickAsk(h.q)">{{ h.label }}</button>
          </div>
          <div v-if="suggest.length" class="ai-sugg">
            <button v-for="(s, si) in suggest" :key="si" class="sg-chip" @click="quickAsk(s)">{{ s }}</button>
          </div>
          <div class="ai-input">
            <input
              v-model="draft"
              class="ai-in"
              placeholder="问问价格… 例如：丙烯今天多少钱"
              maxlength="200"
              :disabled="loading"
              @keyup.enter="send"
            />
            <button class="ai-send" :disabled="loading || !draft.trim()" @click="send">
              <svg v-if="!loading" viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4z"/></svg>
              <span v-else class="send-wait">…</span>
            </button>
          </div>
        </footer>
      </section>
    </Transition>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import { aiChat } from '../api'
import { useDragFab } from '../composables/useDragFab'
import { useRouter } from 'vue-router'

const open = ref(false)
const router = useRouter()

/** 回复里的跳转按钮：关闭面板并跳转（如去供需广场） */
function goAction(a) {
  open.value = false
  if (a && a.to) router.push(a.to)
}

// 悬浮球：可拖动，松手吸附到最近的左/右边缘，位置记忆在 localStorage
// key 带 -round 后缀：改圆形前的旧位置是按药丸宽度算的，复用会偏位
const fabRef = ref(null)
const fab = useDragFab({
  elRef: fabRef,
  key: 'ai-round',
  defaultBottom: 150,
  onTap: () => { open.value = true },
})
const fullscreen = ref(false)
const loading = ref(false)
const draft = ref('')
const scrollRef = ref(null)
const messages = ref([])
const chartEls = reactive({})
let chartInstances = {}

const suggest = ['丙烯 今天多少钱', '甲醇 近 30 天走势', '今天涨最多的品种 Top5', '纯苯 的综合概览']

/** 常用功能用法：点击即问「怎么用」，由后端 platformHelp 返回操作步骤 */
const howto = [
  { label: '怎么问价', q: '问价功能怎么用' },
  { label: '怎么发布', q: '发布功能怎么用' },
  { label: '怎么导出', q: '导出功能怎么用' },
]

const WELCOME = '你好，我是 ChemPrice AI 助手。我可以基于本站数据帮你查化工品的最新报价、价格走势、涨跌排行和综合概览。试着点下面一个问题，或直接输入你想问的。'

function push(m) {
  messages.value.push(m)
  if (messages.value.length > 60) messages.value.splice(0, messages.value.length - 60)
}

function ensureWelcome() {
  if (!messages.value.length) push({ role: 'ai', kind: 'text', reply: WELCOME })
}

async function send() {
  const text = (draft.value || '').trim()
  if (!text || loading.value) return
  draft.value = ''
  push({ role: 'user', text })
  await ask(text)
}

async function quickAsk(text) {
  if (loading.value) return
  push({ role: 'user', text })
  await ask(text)
}

async function ask(text, _retry = 0) {
  loading.value = true
  scrollBottom()
  try {
    const res = await aiChat(text)
    const d = res && res.code === 200 ? res.data : null
    if (!d) {
      push({ role: 'ai', kind: 'error', reply: (res && res.message) || '服务暂时不可用，请稍后再试。' })
    } else if (d.kind === 'answer') {
      push({ role: 'ai', kind: 'text', reply: d.reply, chart: d.chart, quotes: d.quotes,
             exportInfo: d.export, actions: d.actions, blocked: d.blocked })
    } else if (d.kind === 'blocked') {
      // 全部品种都是危化品：不发布任何信息，只给出红色警示
      push({ role: 'ai', kind: 'text', reply: d.reply, blocked: d.blocked, actions: d.actions })
    } else if (d.kind === 'clarify') {
      push({ role: 'ai', kind: 'clarify', reply: '', hint: d.hint, options: d.options,
             reasks: d.reasks, actions: d.actions })
    } else if (d.retryAfterSec && _retry < 2) {
      // 咨询高峰期：后端要求稍后重试 —— 前端自动等待并重发，用户不用重新输入
      const wait = Math.max(3, Math.min(30, Number(d.retryAfterSec) || 10))
      push({ role: 'ai', kind: 'text', reply: `当前咨询的用户较多，正在为你排队重试（第 ${_retry + 1} 次，约 ${wait} 秒）…` })
      scrollBottom()
      loading.value = false
      await new Promise((r) => setTimeout(r, wait * 1000))
      return ask(text, _retry + 1)
    } else {
      push({ role: 'ai', kind: 'error', reply: d.reply || d.hint || '出错了，换个问法试试。' })
    }
  } catch (e) {
    const isTimeout = e && (e.code === 'ECONNABORTED' || /timeout/i.test(e.message || ''))
    push({
      role: 'ai',
      kind: 'error',
      reply: isTimeout
        ? '这次查询耗时较长，已超过等待上限。可以换个更小的范围再问一次（例如把「近半年」换成「近30天」）。'
        : '请求失败：' + ((e && e.message) || '网络异常')
    })
  } finally {
    loading.value = false
    scrollBottom()
  }
}

function clearChat() {
  messages.value = []
  disposeCharts()
  ensureWelcome()
}

function toggleFull() {
  fullscreen.value = !fullscreen.value
  // 全屏切换后图表需要按新尺寸重绘
  nextTick(() => renderAllCharts())
}

function scrollBottom() {
  nextTick(() => {
    if (scrollRef.value) scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  })
}

/* ---------- 渲染辅助 ---------- */
function splitLines(text) {
  if (!text) return ['']
  return String(text).split('\n').filter((s, idx, arr) => !(s === '' && idx === arr.length - 1))
}

function trimQuotes(rows) {
  return rows && rows.length > 12 ? rows.slice(0, 12) : rows
}

function fmtNum(v) {
  if (v == null || v === '') return ''
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  return n % 1 === 0 ? String(n) : String(Math.round(n * 100) / 100)
}

/* ---------- ECharts 渲染 ---------- */
watch(
  () => messages.value.length,
  () => {
    scrollBottom()
    nextTick(renderAllCharts)
  }
)

function renderAllCharts() {
  messages.value.forEach((m, idx) => {
    if (m.role === 'ai' && m.chart && m.chart.values && m.chart.values.length && chartEls[idx]) {
      if (chartInstances[idx]) {
        chartInstances[idx].dispose()
      }
      const chart = echarts.init(chartEls[idx])
      chart.setOption(buildChartOpt(m.chart))
      chartInstances[idx] = chart
    }
  })
}

function buildChartOpt(c) {
  // 数据点较多时（如「近一年」248 个点）加缩放条，否则窄面板里挤成一团看不清
  const many = (c.dates || []).length > 120
  const opt = {
    grid: { left: 12, right: 16, top: 18, bottom: many ? 34 : 6, containLabel: true },
    xAxis: {
      type: 'category',
      data: c.dates || [],
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#d5d9e0' } },
      axisLabel: { fontSize: 10, color: '#71717a', hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      scale: true,
      splitLine: { lineStyle: { color: '#eceef1' } },
      axisLabel: { fontSize: 10, color: '#71717a' }
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      valueFormatter: (v) => (v == null ? '' : String(v))
    },
    series: [
      {
        type: 'line',
        data: c.values,
        smooth: true,
        showSymbol: false,
        symbol: 'circle',
        symbolSize: 4,
        lineStyle: { color: '#3b82f6', width: 2 },
        itemStyle: { color: '#3b82f6' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(59,130,246,0.25)' },
            { offset: 1, color: 'rgba(59,130,246,0.02)' }
          ])
        }
      }
    ]
  }
  if (many) {
    opt.dataZoom = [
      { type: 'inside', start: 0, end: 100 },
      {
        type: 'slider', height: 14, bottom: 2, start: 0, end: 100,
        borderColor: 'transparent', backgroundColor: '#f4f5f7',
        fillerColor: 'rgba(59,130,246,0.12)',
        handleStyle: { color: '#3b82f6' },
        moveHandleStyle: { color: '#c7d2fe' },
        textStyle: { fontSize: 9, color: '#71717a' },
        dataBackground: { lineStyle: { color: '#c7d2fe' }, areaStyle: { color: '#eef2ff' } }
      }
    ]
  }
  return opt
}

function disposeCharts() {
  Object.values(chartInstances).forEach((c) => {
    try { c.dispose() } catch (e) { /* noop */ }
  })
  chartInstances = {}
  Object.keys(chartEls).forEach((k) => { delete chartEls[k] })
}

onBeforeUnmount(disposeCharts)
</script>

<style scoped>
.ai-wrap { font-family: inherit; }

/* 悬浮入口（圆形，与右下角「联系/赞赏」圆钮风格统一；可拖动，松手吸附边缘） */
.ai-fab {
  /* 与「联系客服」悬浮按钮 .contact-fab 同一结构：圆形 + 图标在上、文字在下 */
  position: fixed; right: 22px; bottom: 150px; z-index: 3000;
  width: 50px; height: 50px; padding: 0;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 2px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff; border: none; cursor: grab;
  border-radius: 50%;
  font-family: inherit; font-size: 9px; font-weight: 700; letter-spacing: -.2px;
  text-shadow: 0 1px 2px rgba(0, 0, 0, .12);
  box-shadow: 0 6px 18px rgba(37, 99, 235, 0.38);
  transition: transform .15s, box-shadow .18s;
  animation: fab-breathe 3.4s ease-in-out infinite;
  touch-action: none;          /* 关键：移动端让 pointermove 生效，不被滚动手势吞掉 */
  user-select: none;
  -webkit-user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.ai-fab .fab-txt { white-space: nowrap; line-height: 1; }
/* 呼吸光晕：只动 box-shadow，不做位移，避免与拖动/吸附冲突 */
@keyframes fab-breathe {
  0%, 100% { box-shadow: 0 6px 18px rgba(37, 99, 235, 0.34); }
  50%      { box-shadow: 0 6px 26px rgba(37, 99, 235, 0.62); }
}
.ai-fab:hover {
  transform: translateY(-3px) scale(1.05);
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.55);
  animation: none;
}
.ai-fab.fab-dragging { cursor: grabbing; transform: none !important; animation: none; box-shadow: 0 12px 26px rgba(37, 99, 235, 0.55); }
@media (prefers-reduced-motion: reduce) {
  .ai-fab { animation: none; }
}

/* 聊天面板 */
.ai-panel {
  position: fixed; right: 18px; bottom: 18px; z-index: 3100;
  width: 428px; max-width: calc(100vw - 28px);
  height: min(660px, calc(100vh - 60px));
  display: flex; flex-direction: column;
  background: #fff; border: 1px solid #e2e8f0; border-radius: 16px;
  box-shadow: 0 20px 50px rgba(2, 6, 23, 0.22);
  overflow: hidden;
  transition: width .22s ease, height .22s ease, right .22s ease, bottom .22s ease, border-radius .22s ease;
}
.ai-panel.fullscreen {
  right: 0; bottom: 0;
  width: 100vw; height: 100vh; max-width: 100vw;
  border-radius: 0;
  box-shadow: none;
  border-color: transparent;
}
.ai-hd {
  flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px;
  background: linear-gradient(135deg, #1e3a8a, #1d4ed8);
  color: #fff;
}
.ai-hd-l { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ai-dot { width: 8px; height: 8px; border-radius: 50%; background: #4ade80; box-shadow: 0 0 6px #4ade80; flex: none; }
.ai-hd b { font-size: 14px; }
.ai-sub { font-size: 10px; opacity: .75; margin-left: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ai-hd-r { display: flex; gap: 2px; flex: none; }
.ai-ic {
  background: transparent; border: none; color: #dbeafe; cursor: pointer;
  width: 26px; height: 26px; border-radius: 7px; display: grid; place-items: center;
}
.ai-ic:hover { background: rgba(255,255,255,.15); color: #fff; }

/* 消息区 */
.ai-msgs {
  flex: 1; overflow-y: auto; padding: 14px 12px 8px;
  background: #f8fafc;
}
.msg { display: flex; margin-bottom: 12px; }
.msg.user { justify-content: flex-end; }
.bub {
  max-width: 82%; padding: 9px 12px; border-radius: 12px;
  font-size: 13px; line-height: 1.65; word-break: break-word;
  white-space: pre-wrap;
}
.user-bub {
  background: #2563eb; color: #fff;
  border-top-right-radius: 4px;
}
.ai { align-items: flex-start; }
.ai-ava {
  flex: none; width: 26px; height: 26px; border-radius: 8px; margin-right: 8px;
  background: linear-gradient(135deg, #3b82f6, #2563eb); color: #fff;
  display: grid; place-items: center;
}
.ai-col { min-width: 0; max-width: calc(100% - 34px); display: flex; flex-direction: column; }
.ai-bub {
  background: #fff; border: 1px solid #e6eaf0;
  border-top-left-radius: 4px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}
.ai-txt p { margin: 0; }
.ai-para + .ai-para { margin-top: 5px; }
/* 危险化学品拦截提示块 */
.ai-blocked {
  margin-top: 9px;
  padding: 10px 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-left: 3px solid #dc2626;
  border-radius: 8px;
}
.ai-blocked .abk-head {
  display: flex;
  gap: 6px;
  font-size: 12.5px;
  line-height: 1.6;
  color: #b91c1c;
  font-weight: 500;
}
.ai-blocked .abk-head .abk-ic { flex: none; font-size: 13px; }
.ai-blocked .abk-head b { font-weight: 700; }
.ai-blocked .abk-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.ai-blocked .abk-chip {
  display: inline-block;
  padding: 2px 9px;
  font-size: 12px;
  color: #b91c1c;
  background: #fff;
  border: 1px solid #fca5a5;
  border-radius: 999px;
  white-space: nowrap;
}
.ai-blocked .abk-foot {
  margin-top: 8px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #9a3412;
  opacity: .85;
}
.ai-err { display: flex; gap: 6px; align-items: flex-start; color: #b45309; }
.err-ic { flex: none; }
.cl-txt { margin: 0; color: #374151; }
.cl-opts { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.cl-chip {
  border: 1px solid #bfdbfe; background: #eff6ff; color: #1d4ed8;
  border-radius: 999px; padding: 4px 12px; font-size: 12px; cursor: pointer;
}
.cl-chip:hover { background: #dbeafe; }

/* 走势图卡片 */
.ai-chart-box {
  margin-top: 8px; background: #fff; border: 1px solid #e6eaf0;
  border-radius: 10px; padding: 8px 8px 4px;
}
.chart-cap { font-size: 11px; color: #475569; padding: 0 4px 4px; font-weight: 600; }
.ai-chart { width: 100%; height: 150px; }

/* 报价表 */
/* ===== 数据导出下载卡片 ===== */
.ai-exp {
  margin-top: 10px; padding: 12px 13px; border-radius: 10px;
  background: #f0f7ff; border: 1px solid #cfe3fb;
}
.exp-h { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: #1d4ed8; }
.exp-meta { margin: 6px 0 9px; font-size: 12px; color: #475569; line-height: 1.6; }
.exp-btn {
  display: inline-block; padding: 8px 14px; border-radius: 8px;
  background: #2563eb; color: #fff; font-size: 13px; font-weight: 600;
  text-decoration: none; word-break: break-all;
}
.exp-btn:hover { background: #1d4ed8; }
.exp-tip { margin: 7px 0 0; font-size: 11px; color: #64748b; }

.ai-q-wrap {
  margin-top: 8px; background: #fff; border: 1px solid #e6eaf0;
  border-radius: 10px; overflow: hidden; overflow-x: auto;
}
.ai-q { width: 100%; border-collapse: collapse; font-size: 12px; }
.ai-q th {
  background: #f1f5f9; color: #475569; font-weight: 600; text-align: left;
  padding: 6px 9px; white-space: nowrap;
}
.ai-q td { padding: 5px 9px; border-top: 1px solid #f1f5f9; white-space: nowrap; color: #1e293b; }
.c-name { color: #1d4ed8; font-weight: 600; }
.c-price { font-weight: 700; }
.q-unit { color: #6b7280; font-size: 10px; margin-left: 2px; }
.q-spec { color: #6b7280; font-size: 11px; }
.c-date { color: #64748b; }
.c-up { color: #dc2626; font-weight: 600; }
.c-down { color: #22c55e; font-weight: 600; }
.c-flat { color: #6b7280; }

/* 正在思考 */
.typing { display: flex; gap: 4px; padding: 10px 14px; background: #fff; border: 1px solid #e6eaf0; border-radius: 12px; border-top-left-radius: 4px; }
.typing i {
  width: 6px; height: 6px; border-radius: 50%; background: #94a3b8;
  animation: ai-blink 1.2s infinite;
}
.typing i:nth-child(2) { animation-delay: .2s; }
.typing i:nth-child(3) { animation-delay: .4s; }
@keyframes ai-blink { 0%,60%,100% { opacity: .25; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-3px); } }

/* 底部输入 */
.ai-ft { flex: none; border-top: 1px solid #eef1f5; background: #fff; padding: 8px 10px 10px; }
.ai-sugg { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 8px; }
.sg-chip {
  border: 1px dashed #cbd5e1; background: #f8fafc; color: #475569;
  border-radius: 999px; padding: 2px 10px; font-size: 11px; cursor: pointer;
}
.sg-chip:hover { border-color: #93c5fd; color: #1d4ed8; background: #eff6ff; }

/* 常用功能用法按钮行 */
.ai-howto {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 8px 0 4px;
}
.hw-label { font-size: 11px; color: #9ca3af; flex: none; }
.hw-chip {
  font-size: 11.5px; color: #4338ca; background: #eef2ff;
  border: 1px solid #e0e7ff; border-radius: 999px;
  padding: 3px 10px; cursor: pointer; transition: all .15s;
}
.hw-chip:hover { background: #dbeafe; border-color: #bfdbfe; }
.hw-chip:disabled { opacity: .5; cursor: default; }
.ai-input { display: flex; align-items: center; gap: 8px; }
.ai-in {
  flex: 1; border: 1px solid #d5d9e0; border-radius: 10px;
  padding: 9px 12px; font-size: 13px; outline: none;
}
.ai-in:focus { border-color: #3b82f6; box-shadow: 0 0 0 2px rgba(59,130,246,.15); }
.ai-send {
  flex: none; width: 36px; height: 36px; border-radius: 10px;
  background: #2563eb; color: #fff; border: none; cursor: pointer;
  display: grid; place-items: center; transition: background .15s;
}
.ai-send:hover:not(:disabled) { background: #1d4ed8; }
.ai-send:disabled { opacity: .45; cursor: not-allowed; }
.send-wait { font-size: 15px; line-height: 1; }

/* 弹出动画 */
.ai-pop-enter-active, .ai-pop-leave-active { transition: all .18s ease; }
.ai-pop-enter-from, .ai-pop-leave-to { opacity: 0; transform: translateY(14px) scale(.97); }

::-webkit-scrollbar { width: 6px; }
::-webkit-scrollbar-thumb { background: #d5d9e0; border-radius: 3px; }
/* 回复里的跳转按钮 */
.ai-acts { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.ai-act {
  padding: 6px 14px; border-radius: 8px; font-size: 12.5px; font-family: inherit;
  border: 1px solid #1d4ed8; background: #1d4ed8; color: #fff; cursor: pointer;
  transition: all .15s;
}
.ai-act:hover { background: #1e40af; border-color: #1e40af; }
</style>
