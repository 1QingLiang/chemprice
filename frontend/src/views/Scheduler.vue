<template>
  <div class="sch-page">
    <!-- 顶部：标题 + 操作 -->
    <div class="pg-head">
      <div class="pg-tt">
        <span class="page-title">定时服务监控</span>
        <span class="pg-hint">
          实时反映服务器上各<b>定时任务</b>与<b>常驻服务</b>的运行状态 —— 到点有没有跑、上次跑成功还是失败
        </span>
      </div>
      <div class="pg-act">
        <span v-if="updatedAt" class="upd">更新于 {{ updatedAt }}</span>
        <button class="btn-ref" :disabled="loading" @click="load()">
          <RefreshCw :class="{ spin: loading }" />{{ loading ? '刷新中' : '刷新' }}
        </button>
      </div>
    </div>

    <!-- 汇总条 -->
    <div v-if="state === 'ready'" class="sum-bar" :class="'lv-' + overallLevel">
      <div class="sum-ic"><component :is="overallIcon" /></div>
      <div class="sum-tx">
        <b>{{ overallText }}</b>
        <span>{{ tradingText }}</span>
      </div>
      <div class="sum-num">
        <div class="sn"><b>{{ summary.total }}</b><span>监控任务</span></div>
        <div class="sn ok"><b>{{ summary.normal }}</b><span>正常</span></div>
        <div class="sn" :class="summary.abnormal ? 'bad' : ''"><b>{{ summary.abnormal }}</b><span>需关注</span></div>
      </div>
    </div>

    <div v-if="state === 'loading'" class="ph">正在读取服务器状态…</div>
    <div v-else-if="state === 'error'" class="ph err">{{ errMsg }}</div>

    <template v-else>
      <!-- 常驻服务 -->
      <div class="sec-tt">
        <Server /> 常驻服务
        <span class="sec-hint">长时间运行的后台进程；非 active 即为掉线</span>
      </div>
      <div class="svc-grid">
        <div v-for="s in services" :key="s.name" class="svc" :class="{ off: !s.active }">
          <span class="dot" :class="s.active ? 'on' : 'off'"></span>
          <div class="svc-tx">
            <b>{{ s.label }}</b>
            <span>{{ s.state }}<template v-if="s.lastLogAgoSec != null"> · 日志 {{ ago(s.lastLogAgoSec) }}前</template></span>
          </div>
        </div>
      </div>

      <!-- 定时任务 -->
      <div class="sec-tt">
        <Timer /> 定时任务
        <span class="sec-hint">按 cron 计划执行的脚本；「上次运行」取自日志文件的最后写入时间</span>
      </div>

      <div v-for="g in grouped" :key="g.name" class="grp">
        <div class="grp-tt">{{ g.name }}</div>
        <table class="tbl">
          <thead>
            <tr>
              <th style="width:34px"></th>
              <th style="width:150px">任务</th>
              <th style="width:170px">执行计划</th>
              <th style="width:150px">上次运行</th>
              <th style="width:92px">间隔</th>
              <th>说明 / 状态</th>
              <th style="width:76px"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in g.items" :key="t.id" :class="'r-' + t.level">
              <td>
                <span class="dot" :class="t.level"></span>
              </td>
              <td><b>{{ t.name }}</b></td>
              <td><code>{{ t.cron }}</code></td>
              <td>
                <template v-if="t.lastRunAt">
                  <div class="lr">{{ t.lastRunAt.slice(5) }}</div>
                  <div class="lr-ago">{{ ago(t.lastRunAgoSec) }}前</div>
                </template>
                <span v-else class="muted">从未运行</span>
              </td>
              <td>{{ everyText(t.everyMin) }}</td>
              <td>
                <div class="desc">{{ t.desc }}</div>
                <div v-if="t.level !== 'ok'" class="why" :class="t.level">{{ t.reason }}</div>
              </td>
              <td>
                <button class="btn-mini" @click="showLog(t)">日志</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- 日志弹窗（统一弹层组件：自带 Teleport / Esc 关闭 / 背景滚动锁 / 统一 z-index）-->
    <AppModal
      v-model="logOpen"
      size="xl"
      :title="(logTask ? logTask.name : '') + ' · 日志'"
      :subtitle="logFile"
      :padded="false"
    >
      <pre class="log-pre">{{ logContent || '（空）' }}</pre>
      <template #footer>
        <span class="muted">仅显示末尾 {{ logLines }} 行</span>
        <div>
          <button class="btn-mini" @click="reloadLog(500)">看 500 行</button>
          <button class="btn-mini" @click="reloadLog(2000)">看 2000 行</button>
        </div>
      </template>
    </AppModal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import AppModal from '../components/AppModal.vue'
import { getSchedulerStatus, getSchedulerLog } from '../api'
import {
  RefreshCw, Server, Timer, CheckCircle2, AlertTriangle, XCircle
} from 'lucide-vue-next'

const state = ref('loading')
const errMsg = ref('')
const loading = ref(false)
const updatedAt = ref('')
const services = ref([])
const tasks = ref([])
const summary = ref({ total: 0, normal: 0, abnormal: 0 })
const tradingDay = ref(false)
let timer = null

const logOpen = ref(false)
const logTask = ref(null)
const logFile = ref('')
const logContent = ref('')
const logLines = ref(200)

async function load() {
  loading.value = true
  if (state.value !== 'ready') state.value = 'loading'
  try {
    const res = await getSchedulerStatus()
    if (res.code === 200) {
      const d = res.data || {}
      services.value = d.services || []
      tasks.value = d.tasks || []
      summary.value = d.summary || { total: 0, normal: 0, abnormal: 0 }
      tradingDay.value = !!d.isTradingDay
      updatedAt.value = (d.serverTime || '').slice(11, 19)
      state.value = 'ready'
    } else {
      errMsg.value = res.message || '读取失败'
      state.value = 'error'
    }
  } catch (e) {
    errMsg.value = (e && e.message) || '网络异常'
    state.value = 'error'
  } finally {
    loading.value = false
  }
}

const grouped = computed(() => {
  const order = ['数据采集', '消息推送', '公众号', '系统维护']
  const map = new Map()
  for (const t of tasks.value) {
    if (!map.has(t.group)) map.set(t.group, [])
    map.get(t.group).push(t)
  }
  return order.filter(n => map.has(n)).map(n => ({ name: n, items: map.get(n) }))
})

const overallLevel = computed(() => {
  if (tasks.value.some(t => t.level === 'error')) return 'error'
  if (tasks.value.some(t => t.level === 'warn')) return 'warn'
  return 'ok'
})
const overallIcon = computed(() =>
  overallLevel.value === 'ok' ? CheckCircle2
    : overallLevel.value === 'warn' ? AlertTriangle : XCircle)
const overallText = computed(() => {
  const a = summary.value.abnormal
  if (overallLevel.value === 'ok') return '全部定时任务运行正常'
  return `有 ${a} 项需要关注`
})
const tradingText = computed(() =>
  tradingDay.value ? '今日为交易日，全部任务正常运行' : '今日休市，行情/公众号类任务不运行属正常')

function ago(sec) {
  if (sec == null) return '—'
  if (sec < 60) return sec + ' 秒'
  if (sec < 3600) return Math.floor(sec / 60) + ' 分钟'
  if (sec < 86400) return (sec / 3600).toFixed(1) + ' 小时'
  return (sec / 86400).toFixed(1) + ' 天'
}

function everyText(m) {
  if (m <= 1) return '每分钟'
  if (m < 60) return m + ' 分钟'
  if (m === 60) return '每小时'
  if (m === 1440) return '每天'
  return m + ' 分钟'
}

async function showLog(t) {
  logTask.value = t
  logOpen.value = true
  logContent.value = '读取中…'
  await reloadLog(200)
}

async function reloadLog(n) {
  logLines.value = n
  try {
    const res = await getSchedulerLog(logTask.value.id, n)
    if (res.code === 200) {
      logFile.value = res.data.file
      logContent.value = res.data.content
    } else {
      logContent.value = res.message || '读取失败'
    }
  } catch (e) {
    logContent.value = (e && e.message) || '读取失败'
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 60000)   // 每分钟自动刷新
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.sch-page { padding: 0 0 28px; }

.pg-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; flex-wrap: wrap; }
.pg-tt { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.page-title { font-size: 17px; font-weight: 650; color: #111827; }
.pg-hint { font-size: 12px; color: #6b7280; line-height: 1.6; }
.pg-act { display: flex; align-items: center; gap: 10px; }
.upd { font-size: 11.5px; color: #9ca3af; }
.btn-ref {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 6px 13px; border-radius: 8px; font-size: 12.5px; cursor: pointer;
  border: 1px solid #111827; background: #111827; color: #fff; font-family: inherit;
}
.btn-ref:disabled { opacity: .6; cursor: default; }
.btn-ref svg { width: 14px; height: 14px; }
.spin { animation: sp 1s linear infinite; }
@keyframes sp { to { transform: rotate(360deg); } }

.sum-bar {
  display: flex; align-items: center; gap: 14px; padding: 14px 18px;
  border-radius: 12px; margin-bottom: 20px; border: 1px solid;
}
.sum-bar.lv-ok { background: #f0fdf4; border-color: #bbf7d0; }
.sum-bar.lv-warn { background: #fffbeb; border-color: #fde68a; }
.sum-bar.lv-error { background: #fef2f2; border-color: #fecaca; }
.sum-ic svg { width: 24px; height: 24px; }
.lv-ok .sum-ic svg { color: #16a34a; }
.lv-warn .sum-ic svg { color: #d97706; }
.lv-error .sum-ic svg { color: #dc2626; }
.sum-tx { display: flex; flex-direction: column; gap: 3px; flex: 1; min-width: 0; }
.sum-tx b { font-size: 14px; color: #111827; }
.sum-tx span { font-size: 11.5px; color: #6b7280; }
.sum-num { display: flex; gap: 22px; flex: none; }
.sn { display: flex; flex-direction: column; align-items: center; gap: 1px; }
.sn b { font-size: 18px; color: #111827; font-variant-numeric: tabular-nums; line-height: 1.1; }
.sn span { font-size: 10.5px; color: #9ca3af; }
.sn.ok b { color: #16a34a; }
.sn.bad b { color: #dc2626; }

.ph { padding: 40px; text-align: center; font-size: 13px; color: #9ca3af; }
.ph.err { color: #dc2626; }

.sec-tt {
  display: flex; align-items: center; gap: 7px;
  font-size: 13px; font-weight: 600; color: #111827; margin: 0 0 10px;
}
.sec-tt svg { width: 15px; height: 15px; color: #6b7280; }
.sec-hint { font-size: 11px; font-weight: 400; color: #9ca3af; margin-left: 4px; }

.svc-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 10px; margin-bottom: 22px;
}
.svc {
  display: flex; align-items: center; gap: 9px; padding: 11px 14px;
  background: #fff; border: 1px solid #e4e4e7; border-radius: 10px;
}
.svc.off { background: #fef2f2; border-color: #fecaca; }
.svc-tx { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.svc-tx b { font-size: 12.5px; color: #111827; }
.svc-tx span { font-size: 10.5px; color: #9ca3af; }
.svc.off .svc-tx span { color: #dc2626; }

.dot { width: 8px; height: 8px; border-radius: 50%; flex: none; display: inline-block; }
.dot.on, .dot.ok { background: #22c55e; box-shadow: 0 0 0 3px rgba(34,197,94,.16); }
.dot.off, .dot.error { background: #ef4444; box-shadow: 0 0 0 3px rgba(239,68,68,.16); }
.dot.warn { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245,158,11,.16); }
.dot.svc-off { background: #ef4444; }

.grp { margin-bottom: 18px; }
.grp-tt {
  font-size: 11.5px; font-weight: 600; color: #6b7280;
  padding: 0 0 6px; letter-spacing: .3px;
}

.tbl { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #e4e4e7; border-radius: 10px; overflow: hidden; }
.tbl th {
  font-size: 11px; font-weight: 600; color: #6b7280; text-align: left;
  padding: 9px 12px; background: #fafafa; border-bottom: 1px solid #e4e4e7; white-space: nowrap;
}
.tbl td { padding: 10px 12px; font-size: 12.5px; color: #111827; border-bottom: 1px solid #f4f4f5; vertical-align: middle; }
.tbl tr:last-child td { border-bottom: none; }
.tbl tr.r-warn td { background: #fffdf5; }
.tbl tr.r-error td { background: #fff8f8; }
.tbl code {
  font-size: 11px; color: #6b7280; background: #f4f4f5;
  padding: 2px 6px; border-radius: 5px; font-family: ui-monospace, Menlo, Consolas, monospace;
}
.lr { font-size: 12px; font-variant-numeric: tabular-nums; }
.lr-ago { font-size: 10.5px; color: #9ca3af; margin-top: 1px; }
.muted { color: #9ca3af; font-size: 11.5px; }
.desc { font-size: 11.5px; color: #6b7280; line-height: 1.55; }
.why { font-size: 11.5px; margin-top: 3px; font-weight: 500; }
.why.warn { color: #b45309; }
.why.error { color: #dc2626; }

.btn-mini {
  padding: 3px 9px; border-radius: 6px; font-size: 11px; cursor: pointer;
  border: 1px solid #e4e4e7; background: #fff; color: #6b7280; font-family: inherit;
}
.btn-mini:hover { border-color: #d1d5db; color: #111827; }
/* 日志正文（放在 AppModal 默认插槽里，保持深色等宽观感） */
.log-pre {
  margin: 0; padding: 14px 18px;
  background: #1e293b; color: #e2e8f0; font-size: 11.5px; line-height: 1.65;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  white-space: pre-wrap; word-break: break-all;
}

</style>
