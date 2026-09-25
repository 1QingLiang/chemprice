<template>
  <div class="push-settings">
    <!-- 顶部导航 -->
    <div class="back-bar">
      <button class="back-btn" @click="$router.back()">‹ 返回</button>
      <span class="page-title">邮件推送任务设置</span>
    </div>

    <!-- 引导 -->
    <section class="guide-card card">
      <div class="guide-title" @click="showGuide = !showGuide">
        <span>💡 怎么设置推送？</span>
        <span class="guide-tg">{{ showGuide ? '收起' : '展开' }}<i :class="{ up: showGuide }">›</i></span>
      </div>
      <ol v-show="showGuide" class="guide-list">
        <li>进入「<router-link to="/price-table">数据查询</router-link>」页面，点击报价行右侧「☆ 关注」，把想追踪的报价（商品+报价点+规格）加入关注；</li>
        <li>回到本页点击「＋ 新建推送任务」，选择要推送的关注报价、发送时间并开启；</li>
        <li>可创建 <b>多个任务</b>：例如"尿素早报" 08:30、"国际行情晚报" 17:30；</li>
        <li>所有任务共享<b>商品推送额度</b>（默认每人可推送若干条商品），额度不足时可联系管理员开通更多。</li>
      </ol>
    </section>

    <!-- 额度条 -->
    <section class="card quota-bar">
      <div class="quota-left">
        <span class="quota-icon">📮</span>
        <div class="quota-info">
          <span class="quota-label">推送商品额度（跨任务共享）</span>
          <span class="quota-val" v-if="isAdmin">不限（管理员）</span>
          <span class="quota-val" v-else>{{ used }} / {{ quota }} 条</span>
        </div>
        <template v-if="tasks.length">
          <span class="quota-sep"></span>
          <div class="quota-info">
            <span class="quota-label">任务概况</span>
            <span class="quota-val">{{ enabledCount }} 个启用 / 共 {{ tasks.length }} 个</span>
          </div>
        </template>
      </div>
      <div class="quota-right">
        <button class="new-task-btn" @click="openCreate">＋ 新建推送任务</button>
      </div>
      <div class="quota-tip" v-if="!isAdmin">所有任务合计最多推送 {{ quota }} 条商品（已用 {{ used }} 条）；新建任务不受数量限制，但添加商品不能超出剩余额度，超出时请联系管理员开通。</div>
    </section>

    <!-- 加载 -->
    <section v-if="loading" class="empty-state">加载中...</section>

    <!-- 无关注引导 -->
    <section v-else-if="!loading && favorites.length === 0 && tasks.length === 0" class="card empty-state">
      <p>暂无关注报价</p>
      <p style="font-size:12px;color: #71717a">请先在「数据查询」页面点 ☆ 关注报价点，再回来创建推送任务。</p>
      <el-button size="small" type="primary" @click="$router.push('/price-table')">去数据查询关注</el-button>
    </section>

    <!-- 任务列表 -->
    <section v-else-if="!loading">
      <div v-if="tasks.length === 0" class="card empty-state">
        <p>还没有推送任务</p>
        <p style="font-size:12px;color: #71717a">点击上方「＋ 新建推送任务」创建你的第一个每日推送。</p>
      </div>

      <div v-for="t in tasks" :key="t.id" class="card task-card" :class="{ 'task-off': !t.enabled }">
        <div class="task-head">
          <div class="task-title-row">
            <span class="task-name">{{ t.name }}</span>
            <span class="badge" :class="t.enabled ? 'badge-on' : 'badge-off'">{{ t.enabled ? '已启用' : '已停用' }}</span>
            <span class="task-time">🕐 每日 {{ t.sendTime }}</span>
            <span v-if="t.enabled" class="task-next">下次 {{ nextSend(t) }}</span>
          </div>
          <div class="task-actions">
            <button class="mini-btn test" @click="doTest(t)" :disabled="t.testing">{{ t.testing ? '发送中…' : '测试发送' }}</button>
            <button class="mini-btn" @click="openEdit(t)">编辑</button>
            <button class="mini-btn danger" @click="doDelete(t)">删除</button>
          </div>
        </div>
        <div class="task-items">
          <template v-if="t.items && t.items.length">
            <span v-for="(it, i) in t.items" :key="i" class="item-chip">
              <span class="item-type" :class="'item-type-' + it.tableType">{{ typeName(it.tableType) }}</span>
              {{ it.varietiesName }}
              <span class="item-mkt">{{ it.marketName }}{{ it.specificationsName ? ' · ' + it.specificationsName : '' }}</span>
            </span>
          </template>
          <span v-else class="item-empty">该任务暂未选择推送报价（请编辑勾选）</span>
        </div>
        <div class="task-foot">
          <span class="item-count">共 {{ (t.items || []).length }} 条报价</span>
          <span class="last-sent" v-if="t.lastSentDate">最近发送数据日 {{ t.lastSentDate }}</span>
          <span class="last-sent none" v-else>尚未发送过</span>
        </div>
      </div>
    </section>

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dlg.visible" :title="dlg.isEdit ? '编辑推送任务' : '新建推送任务'" width="560px" destroy-on-close>
      <div class="dlg-body">
        <div class="dlg-row">
          <span class="dlg-label">任务名称</span>
          <el-input v-model="dlg.form.name" maxlength="50" placeholder="如：尿素早报" style="width:260px" />
        </div>
        <div class="dlg-row">
          <span class="dlg-label">每日发送时间</span>
          <el-select v-model="dlg.form.sendTime" style="width:140px">
            <el-option v-for="t in pushSlots" :key="t" :label="t" :value="t" />
          </el-select>
        </div>
        <div class="dlg-row">
          <span class="dlg-label">启用任务</span>
          <el-switch v-model="dlg.form.enabled" />
          <span class="dlg-hint">关闭后到点不会发送</span>
        </div>
        <div class="dlg-divider"></div>
        <div class="dlg-subtitle">选择要推送的关注报价（{{ dlgCheckedCount }} 条已选）</div>
        <div v-if="favorites.length === 0" class="dlg-empty">暂无关注报价，请先到「数据查询」页关注。</div>
        <div v-else class="fav-list">
          <!-- 表头：与数据行共用同一套 grid 网格，保证三列严格对齐 -->
          <div class="fav-head">
            <div class="fav-cols">
              <span></span><span></span>
              <span>产品名称</span>
              <span class="ta-c">推荐推送时间<i class="th-q" title="推荐推送时间＝该报价昨日数据入库时间，用作每日推送最新消息的参考">!</i></span>
              <span class="ta-r">报价点</span>
            </div>
          </div>
          <label v-for="f in favorites" :key="favKey(f)" class="fav-row" :class="{ checked: isChecked(f) }">
            <input type="checkbox" :checked="isChecked(f)" @change="toggleCheck(f)" />
            <span class="push-badge" :class="'push-badge-' + f.tableType">{{ typeName(f.tableType) }}</span>
            <span class="fav-vname">{{ f.varietiesName }}</span>
            <span class="fav-hint">{{ f.suggestSendTime || '—' }}</span>
            <span class="fav-mname">{{ f.marketName }}{{ f.specificationsName ? ' · ' + f.specificationsName : '' }}</span>
          </label>
        </div>
      </div>
      <template #footer>
        <el-button @click="dlg.visible = false">取消</el-button>
        <el-button type="primary" :loading="dlg.saving" @click="saveTask">{{ dlg.saving ? '保存中...' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { getPushTasks, createPushTask, updatePushTask, deletePushTask, testPushTask } from '../api/index'
import { useAuthStore } from '../stores/auth'
import { ElMessage, ElMessageBox } from 'element-plus'

const authStore = useAuthStore()
const isAdmin = computed(() => authStore.user?.role === 'ADMIN')
const pushSlots = ['08:30', '10:30', '11:00', '12:00', '14:00', '16:00', '17:00', '17:30', '18:00', '19:00', '20:00']

const loading = ref(false)
const quota = ref(1)
const used = ref(0)
const tasks = ref([])
const favorites = ref([])

const taskCount = computed(() => tasks.value.length)
const enabledCount = computed(() => tasks.value.filter(t => t.enabled).length)
/* 引导卡默认收起（老用户不必每次滑过）；只有在没有任何任务时才自动展开 */
const showGuide = ref(false)

/** 下次发送时间：今天的该时刻还没到 → 今天，否则明天 */
function nextSend(t) {
  if (!t || !t.enabled) return ''
  const now = new Date()
  const [h, m] = String(t.sendTime || '00:00').split(':').map(Number)
  const at = new Date(now.getFullYear(), now.getMonth(), now.getDate(), h || 0, m || 0)
  return (at > now ? '今天 ' : '明天 ') + t.sendTime
}
const dlg = reactive({ visible: false, isEdit: false, editId: null, saving: false,
  form: { name: '', sendTime: '17:30', enabled: true },
  checkedKeys: new Set() })

const dlgCheckedCount = computed(() => dlg.checkedKeys.size)

function typeName(t) {
  if (t === 'enterprise') return '企业'
  if (t === 'international') return '国际'
  return '市场'
}
function favKey(f) { return `${f.varietiesId}|${f.marketName || ''}|${f.specificationsName || ''}|${f.tableType || 'market'}` }
function isChecked(f) { return dlg.checkedKeys.has(favKey(f)) }
function toggleCheck(f) {
  const k = favKey(f)
  if (dlg.checkedKeys.has(k)) dlg.checkedKeys.delete(k)
  else dlg.checkedKeys.add(k)
  dlg.checkedKeys = new Set(dlg.checkedKeys)
}

async function load() {
  loading.value = true
  try {
    const res = await getPushTasks()
    if (res.code === 200) {
      const d = res.data
      quota.value = d.unlimited ? Infinity : (d.quota || 1)
      used.value = d.used || 0
      tasks.value = d.tasks || []
      favorites.value = d.favorites || []
      if (!tasks.value.length) showGuide.value = true   // 新用户自动展开引导
    } else ElMessage.warning(res.message || '加载失败')
  } catch (e) { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function openCreate() {
  dlg.isEdit = false; dlg.editId = null
  dlg.form = { name: '', sendTime: '17:30', enabled: true }
  dlg.checkedKeys = new Set()
  dlg.visible = true
}

function openEdit(t) {
  dlg.isEdit = true; dlg.editId = t.id
  dlg.form = { name: t.name, sendTime: t.sendTime || '17:30', enabled: !!t.enabled }
  dlg.checkedKeys = new Set((t.items || []).map(it => `${it.varietiesId}|${it.marketName || ''}|${it.specificationsName || ''}|${it.tableType || 'market'}`))
  dlg.visible = true
}

function buildItems() {
  return favorites.value.filter(f => dlg.checkedKeys.has(favKey(f))).map(f => ({
    varietiesId: f.varietiesId,
    varietiesName: f.varietiesName,
    marketName: f.marketName || '',
    specificationsName: f.specificationsName || '',
    tableType: f.tableType || 'market'
  }))
}

async function saveTask() {
  if (!dlg.form.name.trim()) { ElMessage.warning('请填写任务名称'); return }
  const checkCnt = dlg.checkedKeys.size
  if (!isAdmin.value) {
    if (checkCnt === 0) { ElMessage.warning('请至少选择 1 条要推送的商品'); return }
    // 其余任务已占用的商品数 = 总额度已用 - 本任务现有条数（编辑场景）
    const selfItems = dlg.isEdit
      ? ((tasks.value.find(x => x.id === dlg.editId) || {}).items || []).length
      : 0
    const otherUsed = used.value - selfItems
    const remain = quota.value - otherUsed
    if (checkCnt > remain) {
      ElMessage.warning(`推送商品额度不足：其他任务已用 ${Math.max(otherUsed, 0)} 条 / 共 ${quota.value} 条，本次勾选 ${checkCnt} 条将超出上限（剩余 ${remain} 条可用），请减少商品或联系管理员开通更多额度`)
      return
    }
  }
  dlg.saving = true
  try {
    const payload = {
      name: dlg.form.name.trim(),
      sendTime: dlg.form.sendTime,
      enabled: dlg.form.enabled,
      items: buildItems()
    }
    const res = dlg.isEdit
      ? await updatePushTask(dlg.editId, payload)
      : await createPushTask(payload)
    if (res.code === 200) {
      ElMessage.success(dlg.isEdit ? '任务已更新' : '任务已创建')
      dlg.visible = false
      await load()
    } else ElMessage.warning(res.message || '保存失败')
  } catch (e) { ElMessage.error('保存失败') }
  finally { dlg.saving = false }
}

async function doDelete(t) {
  try {
    await ElMessageBox.confirm(`确定删除推送任务「${t.name}」吗？`, '删除确认', { type: 'warning' })
  } catch (e) { return }
  try {
    const res = await deletePushTask(t.id)
    if (res.code === 200) { ElMessage.success('任务已删除'); await load() }
    else ElMessage.warning(res.message || '删除失败')
  } catch (e) { ElMessage.error('删除失败') }
}

async function doTest(t) {
  t.testing = true
  const loading = ElMessage({ message: '正在发送测试邮件（约 20-30 秒），请稍候…', type: 'info', duration: 0 })
  try {
    const res = await testPushTask(t.id)
    if (res.code === 200) ElMessage.success(res.data || '测试邮件已发送')
    else ElMessage.warning(res.data || res.message || '测试发送失败')
  } catch (e) {
    const msg = e?.code === 'ECONNABORTED' ? '发送超时，请稍后查收邮箱或重试' : '测试发送失败'
    ElMessage.error(msg)
  } finally { loading.close(); t.testing = false }
}

onMounted(load)
</script>

<style scoped>
.push-settings { padding: 0; }
.back-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.back-btn { display: flex; align-items: center; gap: 4px; padding: 6px 12px; border-radius: 8px; background: var(--card); border: 1px solid #e4e4e7; font-size: 12px; color: #6b7280; cursor: pointer; transition: all .15s; }
.back-btn:hover { border-color: #d1d5db; color: #111827; }
.page-title { font-size: 16px; font-weight: 600; color: #111827; }
.guide-card { padding: 16px 20px; margin-bottom: 16px; }
.guide-title { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 600; color: #1f2937; margin-bottom: 10px; cursor: pointer; user-select: none; }
.guide-tg { font-size: 12px; font-weight: 400; color: #94a3b8; display: inline-flex; align-items: center; gap: 2px; }
.guide-tg:hover { color: #2563eb; }
.guide-tg i { font-style: normal; font-size: 15px; line-height: 1; display: inline-block; transition: transform .18s; }
.guide-tg i.up { transform: rotate(90deg); }
.guide-list { margin: 0; padding-left: 20px; color: #52525b; font-size: 13px; line-height: 1.9; }
.guide-list a { color: #2563eb; text-decoration: none; }
.quota-bar { padding: 14px 20px; margin-bottom: 16px; display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.quota-left { display: flex; align-items: center; gap: 10px; }
.quota-icon { font-size: 20px; }
.quota-label { font-size: 13px; color: #52525b; }
.quota-val { font-size: 15px; font-weight: 700; color: #111827; }
.new-task-btn { padding: 8px 18px; border-radius: 8px; border: none; background: #111827; color: #fff; font-size: 13px; font-weight: 600; cursor: pointer; transition: all .15s; }
.new-task-btn:hover:not(:disabled) { background: #1d4ed8; }
.new-task-btn:disabled { opacity: .45; cursor: not-allowed; }
.quota-tip { width: 100%; font-size: 12px; color: #f59e0b; }
.card { background: rgba(255,255,255,.42); backdrop-filter: blur(16px) saturate(150%); -webkit-backdrop-filter: blur(16px) saturate(150%); border: 1px solid rgba(255,255,255,.9); box-shadow: 0 1px 0 rgba(255,255,255,.75) inset, 0 4px 24px rgba(31,41,55,.10); border-radius: 12px; overflow: hidden; }
.task-card { padding: 16px 20px; margin-bottom: 14px; }
.task-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.task-title-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.task-name { font-size: 15px; font-weight: 700; color: #111827; }
.task-time { font-size: 12px; color: #71717a; }
.badge { padding: 2px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
.badge-on { background: #dcfce7; color: #15803d; }
.badge-off { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
.task-actions { display: flex; gap: 8px; }
.mini-btn { padding: 5px 12px; border: 1px solid #e4e4e7; border-radius: 8px; background: var(--card); font-size: 12px; cursor: pointer; color: #374151; transition: all .15s; }
.mini-btn:hover:not(:disabled) { border-color: #111827; color: #111827; }
.mini-btn.test { border-color: #2563eb; color: #2563eb; }
.mini-btn.test:hover:not(:disabled) { background: #2563eb; color: #fff; }
.mini-btn.danger { border-color: #ef4444; color: #ef4444; }
.mini-btn.danger:hover:not(:disabled) { background: #ef4444; color: #fff; }
.mini-btn:disabled { opacity: .5; cursor: not-allowed; }
.task-items { margin: 12px 0 8px; display: flex; flex-wrap: wrap; gap: 6px; }
.item-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 20px; font-size: 12px; color: #334155; }
.item-type { font-size: 10px; font-weight: 700; padding: 1px 5px; border-radius: 4px; }
.item-type-market { background: #dbeafe; color: #1d4ed8; }
.item-type-enterprise { background: #f3e8ff; color: #7e22ce; }
.item-type-international { background: #fef3c7; color: #92400e; }
.item-mkt { color: #6b7280; }
.item-empty { font-size: 12px; color: #71717a; }
.task-foot { display: flex; gap: 14px; font-size: 11px; color: #71717a; }
/* 额度条：额度 + 任务概况 两段 */
.quota-info { display: flex; flex-direction: column; gap: 1px; }
.quota-sep { width: 1px; height: 30px; background: #e8edf3; margin: 0 18px; }
/* 任务卡：下次发送提示 + 停用降级 */
.task-next { font-size: 11.5px; color: #047857; background: #ecfdf5; border: 1px solid #d1fae5; padding: 1px 8px; border-radius: 20px; white-space: nowrap; }
.task-card.task-off { background: #fbfbfc; border-color: #eef2f7; }
/* 只让文字内容变淡，按钮保持正常可读（否则看着像不能点） */
.task-card.task-off .task-name,
.task-card.task-off .task-time,
.task-card.task-off .task-items,
.task-card.task-off .task-foot { opacity: .62; }
.task-card.task-off .badge-off { opacity: 1; }
.task-card.task-off .task-name { color: #64748b; }
.last-sent.none { color: #cbd5e1; }
.empty-state { padding: 28px 20px; text-align: center; color: #71717a; }
.empty-state p { margin: 4px 0; }
.empty-state .el-button { margin-top: 8px; }
.dlg-body { padding: 4px 2px; }
.dlg-row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.dlg-label { width: 110px; font-size: 13px; color: #52525b; flex-shrink: 0; }
.dlg-hint { font-size: 12px; color: #71717a; }
.dlg-divider { height: 1px; background: #f1f5f9; margin: 14px 0; }
.dlg-subtitle { font-size: 13px; font-weight: 600; color: #1f2937; margin-bottom: 8px; }
.dlg-empty { font-size: 12px; color: #71717a; padding: 10px 0; }
.fav-list { max-height: 268px; overflow-y: auto; border: 1px solid #e4e4e7; border-radius: 8px; padding: 6px 8px; }
/* 表头与数据行共用同一套 5 列网格 → 三列严格对齐 */
.fav-cols, .fav-row { display: grid; grid-template-columns: 16px 38px minmax(0, 1fr) 118px 132px; align-items: center; gap: 8px; }
.fav-head { position: sticky; top: -6px; z-index: 2; margin: -6px -8px 4px; padding: 9px 8px; background: #f8fafc; border-bottom: 1px solid #e4e4e7; border-radius: 7px 7px 0 0; }
.fav-cols { font-size: 11.5px; font-weight: 600; color: #475569; }
/* 表头标题右上角的小叹号：悬浮显示说明（原生 title，不占布局） */
.th-q { display: inline-flex; align-items: center; justify-content: center; width: 12px; height: 12px; border-radius: 50%; background: #cbd5e1; color: #fff; font-size: 8px; font-weight: 700; font-style: normal; line-height: 1; margin-left: 3px; cursor: help; vertical-align: 2px; }
.th-q:hover { background: #94a3b8; }
.ta-c { text-align: center; }
.ta-r { text-align: right; }
.fav-row { padding: 7px 0; border-radius: 6px; cursor: pointer; }
.fav-row:hover { background: #f8fafc; }
.fav-row input { width: 16px; height: 16px; accent-color: #2563eb; }
.push-badge { font-size: 11px; padding: 1px 6px; border-radius: 4px; flex: 0 0 auto; }
.push-badge-market { background: #dbeafe; color: #1d4ed8; }
.push-badge-enterprise { background: #f3e8ff; color: #7e22ce; }
.push-badge-international { background: #fef3c7; color: #92400e; }
.fav-vname { font-size: 13px; color: #18181b; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fav-mname { font-size: 12px; color: #71717a; text-align: right; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fav-hint { font-size: 12px; color: #475569; text-align: center; white-space: nowrap; font-variant-numeric: tabular-nums; }
</style>
