<template>
  <div>
    <!-- 页头：标题 + 口径说明（可折叠，默认收起，不占视线） -->
    <div class="pg-hd">
      <div class="pg-l">
        <h3 class="pg-t">AI 问答记录</h3>
        <span class="pg-tag">仅管理员可见</span>
      </div>
      <button class="pg-note-btn" :class="{ on: showNote }" @click="showNote = !showNote">
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="9" /><path d="M12 16v-5M12 8h.01" stroke-linecap="round" />
        </svg>
        统计口径
      </button>
    </div>
    <div v-if="showNote" class="pg-note">
      · <b>Token 与回答内容</b>自 2026-09-15 起记录；更早的历史记录只有问题与耗时。<br />
      · 一次提问可能包含多次模型调用（意图识别 + 生成回答），此处统计为<b>该题合计消耗</b>。<br />
      · 「识别为」是系统对问题的意图判定，显示「未识别」表示未能匹配到任何查询能力。
    </div>

    <!-- 汇总 -->
    <div class="ai-sum">
      <div class="sum-card">
        <div class="sc-ic blue">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
        </div>
        <div class="sc-b">
          <div class="sc-l">提问总数</div>
          <div class="sc-v">{{ fmt(summary.calls) }}<i>次</i></div>
          <div class="sc-s">覆盖 {{ summary.users || 0 }} 个用户</div>
        </div>
      </div>
      <div class="sum-card">
        <div class="sc-ic violet">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
        </div>
        <div class="sc-b">
          <div class="sc-l">Token 消耗</div>
          <div class="sc-v">{{ fmt(summary.tokens) }}</div>
          <div class="sc-s">输入 {{ fmt(summary.promptTokens) }} · 输出 {{ fmt(summary.completionTokens) }}</div>
        </div>
      </div>
      <div class="sum-card">
        <div class="sc-ic green">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/></svg>
        </div>
        <div class="sc-b">
          <div class="sc-l">今日</div>
          <div class="sc-v">{{ fmt(today.calls) }}<i>次</i></div>
          <div class="sc-s">{{ fmt(today.tokens) }} Token</div>
        </div>
      </div>
      <div class="sum-card">
        <div class="sc-ic amber">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>
        </div>
        <div class="sc-b">
          <div class="sc-l">平均响应</div>
          <div class="sc-v">{{ (Number(summary.avgCostMs || 0) / 1000).toFixed(1) }}<i>秒</i></div>
          <div class="sc-s">按当前筛选条件统计</div>
        </div>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="cd fi ai-filter">
      <!-- 第 1 行：时间范围 -->
      <div class="af-line">
        <span class="af-label">时间范围</span>
        <div class="range-tabs" role="tablist">
          <button v-for="r in RANGES" :key="r.key"
                  class="range-tab" :class="{ on: q.range === r.key }"
                  @click="pickRange(r.key)">{{ r.label }}</button>
        </div>
        <span class="vsep"></span>
        <input v-model="q.start" type="date" class="ipt w-date"
               :class="{ 'on-date': q.range === 'custom' }" @change="onPickDate" />
        <span class="sep">至</span>
        <input v-model="q.end" type="date" class="ipt w-date"
               :class="{ 'on-date': q.range === 'custom' }" @change="onPickDate" />
      </div>
      <!-- 第 2 行：用户 / 关键词 -->
      <div class="af-line">
        <span class="af-label">条件</span>
        <select v-model="q.username" class="ipt w-user">
          <option value="">全部用户</option>
          <option v-for="u in byUser" :key="u.username" :value="u.username">
            {{ u.username }} · {{ u.calls }} 次
          </option>
        </select>
        <div class="kw-wrap">
          <svg class="kw-ic" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>
          <input v-model="q.keyword" class="ipt w-kw has-ic" placeholder="搜索问题或回答内容" @keyup.enter="load(1)" />
          <span v-if="q.keyword" class="kw-clr" @click="q.keyword = ''; load(1)">×</span>
        </div>
        <button class="btn" @click="load(1)">查询</button>
        <button v-if="hasFilter" class="btn ghost" @click="resetQ">清空筛选</button>
      </div>
    </div>

    <!-- 列表 -->
    <div class="cd fi" style="padding:0">
      <div class="tb-wrap">
        <table class="tb log-tb">
          <thead>
            <tr>
              <th style="width:104px">时间</th>
              <th style="width:118px">用户</th>
              <th>问题</th>
              <th style="width:92px">识别为</th>
              <th style="width:78px" class="ta-c">耗时</th>
              <th style="width:86px" class="ta-r">Token</th>
              <th style="width:64px" class="ta-c">回答</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in list" :key="r.id" class="log-row" @click="r.answer && openDetail(r)">
              <td class="dim nowrap">
                <span :title="fmtTime(r.created_at)">{{ shortTime(r.created_at) }}</span>
              </td>
              <td>
                <div class="un">{{ r.nickname || r.username || '—' }}</div>
                <div v-if="r.nickname" class="nk">{{ r.username }}</div>
              </td>
              <td>
                <span class="q-txt" :title="r.question">{{ trunc(r.question, 42) }}</span>
                <span v-if="r.src === 'history'" class="tag-his">历史</span>
              </td>
              <td><span class="badge" :class="toolBadge(r.tool)">{{ toolName(r.tool, r.kind) }}</span></td>
              <td class="ta-c">
                <span class="cost" :class="costCls(r.cost_ms)">{{ (Number(r.cost_ms || 0) / 1000).toFixed(1) }}s</span>
              </td>
              <td class="ta-r num">
                <span v-if="Number(r.total_tokens) > 0">{{ fmt(r.total_tokens) }}</span>
                <span v-else class="none">—</span>
              </td>
              <td class="ta-c">
                <span v-if="r.answer" class="view-btn" @click.stop="openDetail(r)">查看</span>
                <span v-else class="none">—</span>
              </td>
            </tr>
            <tr v-if="loading"><td colspan="7" class="empty-state">加载中…</td></tr>
            <tr v-else-if="!list.length">
              <td colspan="7" class="empty-state">
                <template v-if="hasFilter">没有符合条件的记录，试试<b>清空筛选</b>或换个关键词。</template>
                <template v-else>暂无问答记录。</template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 分页 -->
    <div class="pager" v-if="total > 0">
      <span class="pg-info">共 <b>{{ fmt(total) }}</b> 条 · 第 {{ page }} / {{ maxPage }} 页</span>
      <button class="pg-btn" :disabled="page <= 1" @click="load(1)">首页</button>
      <button class="pg-btn" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <button class="pg-btn" :disabled="page >= maxPage" @click="load(page + 1)">下一页</button>
      <button class="pg-btn" :disabled="page >= maxPage" @click="load(maxPage)">末页</button>
    </div>

    <!-- 详情 -->
    <el-dialog v-model="dlg" title="AI 问答详情" width="760px">
      <div v-if="cur" class="dt">
        <div class="dt-grid">
          <div class="dt-i"><span class="dt-k">提问用户</span><span class="dt-v">{{ cur.nickname || cur.username }}<i v-if="cur.nickname">（{{ cur.username }}）</i></span></div>
          <div class="dt-i"><span class="dt-k">提问时间</span><span class="dt-v">{{ fmtTime(cur.created_at) }}</span></div>
          <div class="dt-i"><span class="dt-k">识别为</span><span class="dt-v"><span class="badge" :class="toolBadge(cur.tool)">{{ toolName(cur.tool, cur.kind) }}</span> <i>{{ cur.tool || '—' }}</i></span></div>
          <div class="dt-i"><span class="dt-k">耗时</span><span class="dt-v">{{ (Number(cur.cost_ms || 0) / 1000).toFixed(1) }} 秒</span></div>
          <div class="dt-i"><span class="dt-k">模型</span><span class="dt-v">{{ cur.model || '—' }}</span></div>
          <div class="dt-i">
            <span class="dt-k">Token</span>
            <span class="dt-v">
              <template v-if="Number(cur.total_tokens) > 0">
                共 <b>{{ fmt(cur.total_tokens) }}</b> <i>（输入 {{ fmt(cur.prompt_tokens) }} + 输出 {{ fmt(cur.completion_tokens) }}）</i>
              </template>
              <span v-else class="none">未采集（历史记录）</span>
            </span>
          </div>
        </div>

        <div class="dt-sec">用户提问</div>
        <div class="dt-box q">{{ cur.question }}</div>

        <div class="dt-sec">AI 回答</div>
        <div class="dt-box a">{{ cur.answer || '（未记录回答内容）' }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getAiLogs } from '../api/index'
import gsap from 'gsap'

const list = ref([])
const byUser = ref([])
const summary = ref({})
const today = ref({})
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const dlg = ref(false)
const cur = ref(null)
const showNote = ref(false)

const RANGES = [
  { key: 'all', label: '全部' },
  { key: '24h', label: '近24小时' },
  { key: '3d', label: '近3天' },
  { key: '7d', label: '近7天' }
]
const q = ref({ username: '', keyword: '', start: '', end: '', range: 'all' })
const maxPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const hasFilter = computed(() => !!(q.value.username || q.value.keyword || q.value.start || q.value.end || q.value.range !== 'all'))

const TOOL_NAME = {
  latest_price: '查最新价',
  price_trend: '查走势',
  price_overview: '综合概览',
  rank_movers: '涨跌排行',
  trend_outlook: '趋势解读',
  export_data: '数据导出',
  platform_help: '平台咨询'
}

function toolName(tool, kind) {
  if (kind === 'error' && (!tool || tool === 'none')) return '未识别'
  return TOOL_NAME[tool] || tool || '未识别'
}

function toolBadge(tool) {
  if (tool === 'latest_price') return 'badge-m'
  if (tool === 'export_data' || tool === 'platform_help') return 'badge-e'
  if (tool === 'price_trend' || tool === 'price_overview' || tool === 'trend_outlook') return 'badge-i'
  return 'badge-fl'
}

/** 列表里长文本只显示前 n 个字符（完整内容由 :title 悬停查看），避免长问题撑破表格。
    先把换行/连续空白压成单个空格，否则多行内容在单元格里会占很高。 */
function trunc(s, n) {
  const t = String(s == null ? '' : s).replace(/\s+/g, ' ').trim()
  return t.length > n ? t.slice(0, n) + '…' : t
}

function fmt(n) {
  return Number(n || 0).toLocaleString('zh-CN')
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

/** 表格里用紧凑格式：MM-DD HH:MM（完整时间放 title） */
function shortTime(t) {
  if (!t) return '—'
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

/** 耗时着色：>20s 偏慢、>10s 略慢 —— 便于一眼扫出异常 */
function costCls(ms) {
  const v = Number(ms || 0) / 1000
  if (v >= 20) return 'c-slow'
  if (v >= 10) return 'c-mid'
  return 'c-fast'
}

async function load(p) {
  if (p) page.value = p
  loading.value = true
  try {
    const res = await getAiLogs({
      page: page.value,
      size: pageSize,
      username: q.value.username || undefined,
      keyword: q.value.keyword || undefined,
      start: q.value.start || undefined,
      end: q.value.end || undefined,
      range: q.value.range && q.value.range !== 'custom' ? q.value.range : undefined
    })
    if (res.code === 200 && res.data) {
      const d = res.data
      list.value = d.list || []
      byUser.value = d.byUser || []
      summary.value = d.summary || {}
      today.value = d.today || {}
      total.value = d.total || 0
    }
  } catch (e) {
    console.warn('AI 问答记录接口异常', e)
  } finally {
    loading.value = false
  }
}

function resetQ() {
  q.value = { username: '', keyword: '', start: '', end: '', range: 'all' }
  load(1)
}

// 快捷时间窗与自定义日期互斥
function pickRange(key) {
  q.value.range = key
  if (key !== 'custom') {
    q.value.start = ''
    q.value.end = ''
  }
  load(1)
}
function onPickDate() {
  q.value.range = 'custom'
  load(1)
}

function openDetail(r) {
  cur.value = r
  dlg.value = true
}

onMounted(() => load(1))

let ctx
onMounted(() => {
  ctx = gsap.context(() => {
    gsap.fromTo('.fi', { y: 10, autoAlpha: 0 }, { y: 0, autoAlpha: 1, duration: 0.35, ease: 'power2.out', clearProps: 'transform' })
  })
})
onUnmounted(() => { ctx && ctx.revert() })
</script>

<style scoped>
/* ---------- 页头 ---------- */
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
.sc-s { font-size: 11.5px; color: #a1a1aa; margin-top: 5px; }

/* ---------- 筛选 ---------- */
.ai-filter { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
.af-line { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.af-label {
  font-size: 12px; color: #71717a; flex: none; width: 56px;
}

/* 快捷时间窗 */
.range-tabs {
  display: inline-flex; align-items: center; gap: 2px; padding: 2px;
  background: #f4f4f5; border-radius: 9px;
}
.range-tab {
  border: 0; background: transparent; cursor: pointer; font-family: inherit;
  font-size: 12px; color: #52525b; padding: 6px 12px; border-radius: 7px;
  transition: all .15s; white-space: nowrap;
}
.range-tab:hover { color: #18181b; }
.range-tab.on {
  background: #fff; color: #111827; font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .10);
}
.vsep { width: 1px; height: 20px; background: #e4e4e7; margin: 0 2px; }
.on-date { border-color: #93c5fd; background: #f8fbff; }
.ipt {
  height: 34px; padding: 0 10px; border: 1px solid #e2e8f0; border-radius: 8px;
  font-size: 13px; color: #18181b; background: #fff; font-family: inherit; outline: none;
}
.ipt:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59, 130, 246, .12); }
.w-user { width: 200px; }
.w-kw { width: 226px; }
.w-date { width: 146px; }
.sep { color: #a1a1aa; font-size: 13px; }
.kw-wrap { position: relative; display: flex; align-items: center; }
.kw-ic { position: absolute; left: 9px; color: #a1a1aa; pointer-events: none; }
.kw-wrap .has-ic { padding-left: 28px; padding-right: 24px; }
.kw-clr {
  position: absolute; right: 8px; width: 16px; height: 16px; border-radius: 50%;
  background: #e2e8f0; color: #64748b; font-size: 12px; line-height: 16px; text-align: center;
  cursor: pointer;
}
.kw-clr:hover { background: #cbd5e1; color: #334155; }

/* ---------- 表格 ---------- */
.tb-wrap { overflow-x: auto; }
.log-tb th { font-size: 12px; color: #64748b; font-weight: 600; text-transform: none; }
.log-tb .ta-c { text-align: center; }
.log-tb .ta-r { text-align: right; }
.log-row { cursor: pointer; transition: background .12s; }
.log-row:hover { background: #f8fbff; }
.un { font-weight: 600; font-size: 13px; color: #18181b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nk { font-size: 11.5px; color: #a1a1aa; margin-top: 1px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.q-txt {
  /* 必须给"绝对"上限：table-layout:auto 下 td 宽度由内容决定，max-width:100% 等于不生效 */
  display: inline-block; max-width: 340px; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; vertical-align: middle; color: #334155;
}
.tag-his {
  display: inline-block; margin-left: 6px; padding: 0 5px; border-radius: 4px;
  font-size: 10px; line-height: 15px; color: #92400e; background: #fef3c7;
  border: 1px solid #fde68a; vertical-align: middle;
}
.dim { color: #71717a; font-variant-numeric: tabular-nums; }
.nowrap { white-space: nowrap; }
.none { color: #cbd5e1; }
.num { font-variant-numeric: tabular-nums; color: #71717a; }
.cost { font-variant-numeric: tabular-nums; font-size: 12.5px; padding: 1px 6px; border-radius: 5px; }
.c-fast { color: #059669; background: #ecfdf5; }
.c-mid { color: #475569; background: #f1f5f9; }
.c-slow { color: #b45309; background: #fffbeb; }
.view-btn {
  display: inline-block; font-size: 12px; color: #2563eb; padding: 2px 8px;
  border: 1px solid #dbeafe; border-radius: 6px; background: #f8fbff;
}
.view-btn:hover { background: #eff6ff; border-color: #93c5fd; }

/* ---------- 分页 ---------- */
.pager { display: flex; align-items: center; gap: 8px; justify-content: flex-end; margin-top: 12px; font-size: 13px; color: #64748b; }
.pg-info { margin-right: auto; }
.pg-info b { color: #18181b; }
.pg-btn {
  height: 32px; padding: 0 12px; border: 1px solid #e2e8f0; background: #fff;
  border-radius: 8px; font-size: 13px; color: #334155; cursor: pointer; font-family: inherit;
}
.pg-btn:hover:not(:disabled) { border-color: #93c5fd; color: #2563eb; background: #f8fbff; }
.pg-btn:disabled { opacity: .4; cursor: not-allowed; }

/* ---------- 详情 ---------- */
.dt-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 18px; }
.dt-i { display: flex; gap: 10px; padding: 8px 0; border-bottom: 1px dashed #eef2f7; font-size: 13px; align-items: baseline; }
.dt-k { flex: none; width: 62px; color: #a1a1aa; }
.dt-v { color: #18181b; min-width: 0; }
.dt-v i { color: #a1a1aa; font-style: normal; font-size: 12px; }
.dt-sec { font-size: 12px; font-weight: 600; color: #64748b; margin: 15px 0 6px; }
.dt-box {
  font-size: 13px; line-height: 1.85; color: #18181b; white-space: pre-wrap;
  word-break: break-word; max-height: 220px; overflow: auto;
  padding: 11px 13px; border-radius: 9px;
}
.dt-box.q { background: #f6f8fb; border: 1px solid #e8edf3; }
.dt-box.a { background: #f8fbff; border: 1px solid #dbeafe; }

@media (max-width: 980px) {
  .ai-sum { grid-template-columns: repeat(2, 1fr); }
  .w-user, .w-kw { width: 100%; }
  .kw-wrap { flex: 1; }
  .dt-grid { grid-template-columns: 1fr; }
}
</style>
