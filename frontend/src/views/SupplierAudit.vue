<template>
  <div class="sa-page">
    <div class="pg-hd">
      <div class="pg-l">
        <h3 class="pg-t">供应商认证审核</h3>
        <span class="pg-tag">仅管理员可见</span>
      </div>
      <!-- 管理员工具区：危化品名单（发布黑名单） / 供需对接模块总开关 / 工商核验入口 -->
      <div class="pg-tools">
        <div class="tools-group">
          <span class="tools-cap">管理工具</span>
          <button class="tool-btn" @click="openDg">危化品名单</button>
          <!-- 收款码设置：免费实名后收款流程停用，切换支付宝认证时恢复
          <button class="tool-btn" @click="openPaySet">收款码设置</button>
          <span class="tool-sep"></span> -->
          <label class="tool-sw" title="关闭后普通用户将无法看到与使用供需对接功能；管理员不受影响">
            供需对接模块
            <el-switch v-model="supplyOn" :disabled="swBusy" @change="toggleSupply" />
          </label>
        </div>
        <!-- 工商核验入口：审核申请时在此核对企业的公示信息 -->
        <a class="tool-gsxt" href="https://www.gsxt.gov.cn/" target="_blank" rel="noopener">
          核验入口：国家企业信用信息公示系统 ↗
        </a>
      </div>
        <el-dialog v-model="dgOpen" title="危险化学品名单（发布黑名单）" width="560px">
          <p class="dg-tip">发布供需（含 AI 一句话发布）时，产品名称命中以下任一关键词即被拒绝。
            多个关键词用逗号或顿号分隔；清空保存 = 恢复平台内置默认名单。</p>
          <el-input v-model="dgDraft" type="textarea" :rows="10" placeholder="液氯,氯气,液氨,……" />
          <template #footer>
            <el-button @click="dgOpen = false">取消</el-button>
            <el-button type="primary" :loading="dgSaving" @click="saveDg">保存</el-button>
          </template>
        </el-dialog>
    </div>

    <!-- 汇总 -->
    <div class="sa-sum">
      <div class="ss-card" :class="{ on: q.status === 'pending' }" @click="q.status = 'pending'; load()">
        <div class="ss-l">待审核</div>
        <div class="ss-v warn">{{ counts.pending }}</div>
      </div>
      <div class="ss-card" :class="{ on: q.status === 'approved' }" @click="q.status = 'approved'; load()">
        <div class="ss-l">已通过</div>
        <div class="ss-v ok">{{ counts.approved }}</div>
      </div>
      <div class="ss-card" :class="{ on: q.status === 'rejected' }" @click="q.status = 'rejected'; load()">
        <div class="ss-l">已驳回/重认证</div>
        <div class="ss-v bad">{{ counts.rejected }}</div>
      </div>
      <div class="ss-card" :class="{ on: q.status === 'all' }" @click="q.status = 'all'; load()">
        <div class="ss-l">全部申请</div>
        <div class="ss-v">{{ counts.total }}</div>
      </div>
    </div>

    <div class="cd sa-tb-card">
      <div v-if="loading" class="sa-empty">加载中…</div>
      <div v-else-if="!list.length" class="sa-empty">
        <Inbox :size="26" style="color:#c3cbd6; margin-bottom:6px;" />
        <p>{{ q.status === 'pending' ? '没有待审核的申请' : '暂无记录' }}</p>
        <p class="dim">用户提交认证申请后会出现在这里。</p>
      </div>
      <table v-else class="sa-tb">
        <thead>
          <tr>
            <th style="width:96px">提交时间</th>
            <th style="width:110px">申请账号</th>
            <th>公司全称</th>
            <th style="width:170px">统一社会信用代码</th>
            <th style="width:80px">联系人</th>
            <th style="width:110px">联系电话</th>
            <th style="width:76px">状态</th>
            <th style="width:150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in list" :key="a.id">
            <td class="dim">{{ short(a.created_at) }}</td>
            <td>
              <div class="acc">{{ a.nickname || a.username }}</div>
              <div class="acc-s">{{ a.username }}</div>
            </td>
            <td class="co" :title="a.company_name">
              <a class="co-a" :href="gsxtUrl(a.company_name)" target="_blank" rel="noopener">
                {{ a.company_name }}
              </a>
            </td>
            <td class="num">{{ a.credit_code }}</td>
            <td>{{ a.contact_name || '—' }}</td>
            <td class="num">{{ a.contact_phone || '—' }}</td>
            <td><span class="st" :class="a.status">{{ ST[a.status] || a.status }}</span></td>
            <td class="op">
              <template v-if="a.status === 'pending'">
                <span class="lnk ok" @click="openReview(a, 'approve')">通过</span>
                <span class="lnk bad" @click="openReview(a, 'reject')">驳回</span>
              </template>
              <template v-else-if="a.status === 'approved'">
                <span class="lnk bad" @click="openMg(a, 'revoke')">取消认证</span>
                <span class="lnk warn" @click="openMg(a, 'require-reverify')">要求重新认证</span>
              </template>
              <template v-else>
                <span class="lnk" @click="openReview(a, 'approve')">查看</span>
                <span class="dim" :title="a.remark || ''" style="margin-left:10px;">
                  {{ a.reviewed_at ? short(a.reviewed_at) : '已处理' }}
                </span>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 取消认证 / 要求重新认证 -->
    <el-dialog v-model="mgDlg" :title="mgAct === 'revoke' ? '取消供应商认证' : '要求重新认证'"
               width="480px">
      <div v-if="mgCur" class="rv-body">
        <div class="rv-co">
          <div class="rv-co-n">{{ mgCur.company_name }}</div>
          <div class="rv-co-u">{{ mgCur.nickname || mgCur.username }} · {{ mgCur.contact_phone }}</div>
        </div>
        <div class="rv-check warn-box">
          <div class="rv-check-t">
            {{ mgAct === 'revoke'
              ? '取消后：该用户不再是认证供应商，其发布的所有供应信息将立即下架。'
              : '要求重新认证后：其供应信息将暂时下架，重新认证通过后自动恢复。' }}
          </div>
          <div class="rv-row">
            <label class="rv-l">{{ mgAct === 'revoke' ? '取消原因（选填）' : '重新认证原因（必填）' }}</label>
            <textarea v-model="mgRemark" class="rv-ta" rows="3"
                      :placeholder="mgAct === 'revoke' ? '如：发现资质造假 / 长期无交易' : '如：营业执照已过期，请重新上传'"></textarea>
          </div>
          <div class="rv-err" v-if="mgErr">{{ mgErr }}</div>
        </div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="mgDlg = false">取消</button>
        <button class="btn danger" :disabled="mgSaving" @click="doMg">
          {{ mgSaving ? '处理中…' : (mgAct === 'revoke' ? '确认取消认证' : '确认要求重新认证') }}
        </button>
      </template>
    </el-dialog>

    <!-- 审核弹窗 -->
    <el-dialog v-model="dlg" :title="act === 'approve' ? '通过认证' : '驳回认证'" width="520px">
      <div v-if="cur" class="rv-body">
        <div class="rv-co">
          <div class="rv-co-n">{{ cur.company_name }}</div>
          <div class="rv-co-c num">{{ cur.credit_code }}</div>
          <div class="rv-co-u">{{ cur.nickname || cur.username }} · {{ cur.contact_phone }}</div>
        </div>
        <div class="rv-check" v-if="act === 'approve'">
          <div class="rv-check-t">通过前请确认已在公示系统核验：</div>
          <label class="rv-li"><input type="checkbox" v-model="ck1" /> 公司名称与统一社会信用代码<b>相互匹配</b></label>
          <label class="rv-li"><input type="checkbox" v-model="ck2" /> 经营状态为「存续 / 在业」（非注销、吊销）</label>
          <label class="rv-li"><input type="checkbox" v-model="ck3" /> 联系人与公司信息可对应（可选）</label>
        </div>
        <div class="rv-row" v-if="cur && cur.license_url">
          <label class="rv-l">营业执照</label>
          <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
            <button class="btn ghost" @click="viewLicense">{{ licLoading ? '加载中…' : '查看图片' }}</button>
            <button class="btn ghost" @click="aiCompare" v-if="cur.license_url">{{ ocrLoading ? '比对中…' : 'AI 智能比对' }}</button>
            <span class="dim" style="font-size:11.5px;">仅管理员可查看，不会公开展示</span>
          </div>
          <img v-if="licSrc" :src="licSrc" style="max-width:320px;max-height:240px;border:1px solid #e8edf3;border-radius:8px;" />
          <div v-if="ocrResult" class="ocr-rv" :class="'ocr-' + ocrResult.verdict">
            <template v-if="ocrResult.verdict === 'match'">✓ AI 比对一致：执照上的公司名称与统一社会信用代码和申请填写完全相符，可放心通过。</template>
            <template v-else-if="ocrResult.verdict === 'partial'">⚠ 部分相符：公司名称{{ ocrResult.companyMatch ? '一致' : '不一致' }}、信用代码{{ ocrResult.codeMatch ? '一致' : '不一致' }}（识别到：{{ ocrResult.ocrCompany || '未识别' }} / {{ ocrResult.ocrCode || '未识别' }}），请人工核对。</template>
            <template v-else-if="ocrResult.verdict === 'mismatch'">✗ 比对不一致：执照识别到的信息与申请填写不符（识别到：{{ ocrResult.ocrCompany || '未识别' }} / {{ ocrResult.ocrCode || '未识别' }}），请谨慎处理。</template>
            <template v-else>— 无法识别：图片不清晰或格式特殊（{{ ocrResult.ocrCompany }}{{ ocrResult.ocrCode }}），请人工查看图片核对。</template>
          </div>
        </div>
        <div class="rv-row">
          <label class="rv-l">{{ act === 'approve' ? '备注（选填）' : '驳回原因（必填）' }}</label>
          <textarea v-model="remark" class="rv-ta" rows="3"
                    :placeholder="act === 'approve' ? '如：工商信息已核验通过' : '如：公司名称与信用代码不匹配 / 企业已注销'"></textarea>
        </div>
        <div class="rv-err" v-if="err">{{ err }}</div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="dlg = false">取消</button>
        <button class="btn" :class="act === 'approve' ? 'primary' : 'danger'" :disabled="saving" @click="doReview">
          {{ saving ? '处理中…' : (act === 'approve' ? '确认通过' : '确认驳回') }}
        </button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { Inbox } from 'lucide-vue-next'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSupplyStatus, setSupplyEnabled, getDangerousChem, saveDangerousChem } from '../api/index'

// ===== 供需对接模块总开关 =====
const supplyOn = ref(true)
const swBusy = ref(false)
onMounted(async () => {
  try {
    const st = await getSupplyStatus()
    const d = st && st.data ? st.data : st
    supplyOn.value = !!d.enabled
  } catch (e) { /* ignore */ }
})
async function toggleSupply(v) {
  try {
    await ElMessageBox.confirm(
      v ? '确认开启供需对接模块？所有用户将恢复访问。'
        : '确认关闭供需对接模块？所有普通用户将立即无法访问供需广场与供应商认证，发布、查看联系方式等接口一并停用（管理员不受影响）。',
      '供需对接模块开关',
      { type: 'warning', confirmButtonText: v ? '开启' : '关闭', cancelButtonText: '取消' }
    )
  } catch (e) { supplyOn.value = !v; return }
  swBusy.value = true
  try {
    await setSupplyEnabled(v)
    ElMessage.success(v ? '供需对接模块已开启' : '供需对接模块已关闭，普通用户入口已下架')
  } catch (e) {
    supplyOn.value = !v
    ElMessage.error('操作失败，请重试')
  } finally { swBusy.value = false }
}

// ===== 危化品名单维护 =====
const dgOpen = ref(false)
const dgDraft = ref('')
const dgSaving = ref(false)
async function openDg() {
  try {
    const res = await getDangerousChem()
    const d = res && res.data ? res.data : res
    dgDraft.value = d.keywords || ''
    dgOpen.value = true
  } catch (e) { ElMessage.error('名单加载失败') }
}
async function saveDg() {
  if (!dgDraft.value.trim()) {
    try {
      await ElMessageBox.confirm('名单为空将恢复平台内置默认名单，确定保存吗？', '提示', {
        type: 'warning', confirmButtonText: '保存', cancelButtonText: '取消' })
    } catch (e) { return }
  }
  dgSaving.value = true
  try {
    await saveDangerousChem(dgDraft.value.trim())
    dgOpen.value = false
    ElMessage.success('危化品名单已保存，立即生效')
  } catch (e) { ElMessage.error('保存失败，请重试') }
  finally { dgSaving.value = false }
}
import { getSupplierVerifies, reviewSupplier, revokeSupplier,
         requireReverifySupplier, api as http } from '../api/index'

const ST = { pending: '待审核', approved: '已通过', rejected: '已驳回',
  reverify: '需重新认证', revoked: '已取消认证' }

const q = ref({ status: 'pending' })
const list = ref([])
const total = ref(0)
const counts = ref({ total: 0, pending: 0, approved: 0, rejected: 0 })
const loading = ref(false)

const dlg = ref(false)
const cur = ref(null)
const act = ref('approve')
const remark = ref('')
const saving = ref(false)
const err = ref('')
const ck1 = ref(false), ck2 = ref(false), ck3 = ref(false)

function short(t) { return t ? String(t).replace('T', ' ').slice(5, 16) : '—' }
function gsxtUrl(name) {
  return 'https://www.gsxt.gov.cn/index.html'   // 官方检索需在站内搜索，这里直接给入口
}

async function load() {
  loading.value = true
  try {
    const res = await getSupplierVerifies({ status: q.value.status, page: 1, size: 100 })
    if (res.code === 200 && res.data) {
      list.value = res.data.list || []
      total.value = res.data.total || 0
      const ct = res.data.counts || {}
      counts.value = {
        total: Number(ct.total || 0),
        pending: Number(ct.pending || res.data.pending || 0),
        approved: Number(ct.approved || 0),
        rejected: Number(ct.rejected || 0) + Number(ct.reverify || 0) + Number(ct.revoked || 0)
      }
    }
  } catch (e) { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

const mgDlg = ref(false)
const mgCur = ref(null)
const mgAct = ref('revoke')
const mgRemark = ref('')
const mgSaving = ref(false)
const mgErr = ref('')
const licSrc = ref('')
const licLoading = ref(false)

function openMg(a, act) {
  mgCur.value = a; mgAct.value = act; mgRemark.value = ''; mgErr.value = ''
  mgDlg.value = true
}

async function doMg() {
  mgErr.value = ''
  if (mgAct.value === 'require-reverify' && !mgRemark.value.trim()) {
    mgErr.value = '请填写原因（会展示给该用户）'; return
  }
  mgSaving.value = true
  try {
    const fn = mgAct.value === 'revoke' ? revokeSupplier : requireReverifySupplier
    const res = await fn(mgCur.value.user_id, mgRemark.value.trim())
    if (res.code === 200) {
      ElMessage.success(res.data || '已处理')
      mgDlg.value = false
      load()
    } else mgErr.value = res.message || '处理失败'
  } catch (e) {
    mgErr.value = (e && e.response && e.response.data && e.response.data.message) || '处理失败'
  } finally { mgSaving.value = false }
}

async function viewLicense() {
  if (!cur.value || !cur.value.license_url) return
  licLoading.value = true
  try {
    const name = cur.value.license_url.replace(/^license\//, '')
    const res = await http.get('/upload/license/' + name, { responseType: 'blob' })
    licSrc.value = URL.createObjectURL(res.data)
  } catch (e) {
    ElMessage.error('营业执照加载失败')
  } finally { licLoading.value = false }
}

function openReview(a, action) {
  cur.value = a; act.value = action; remark.value = ''
  err.value = ''; ck1.value = false; ck2.value = false; ck3.value = false
  ocrResult.value = null
  dlg.value = true
}

// ===== AI 证照比对（OCR） =====
const ocrLoading = ref(false)
const ocrResult = ref(null)
async function aiCompare() {
  if (!cur.value) return
  ocrLoading.value = true; ocrResult.value = null
  try {
    const res = await http.post('/admin/supplier-verifies/' + cur.value.id + '/ocr')
    if (res.code === 200) ocrResult.value = res.data
    else ElMessage.error(res.message || '比对失败')
  } catch (e) { ElMessage.error('比对失败，请重试') }
  finally { ocrLoading.value = false }
}

async function doReview() {
  err.value = ''
  if (act.value === 'approve' && !(ck1.value && ck2.value)) {
    err.value = '请先勾选前两项核验确认'; return
  }
  if (act.value === 'reject' && !remark.value.trim()) {
    err.value = '驳回必须填写原因（会展示给申请人）'; return
  }
  saving.value = true
  try {
    const res = await reviewSupplier(cur.value.id, { action: act.value, remark: remark.value.trim() })
    if (res.code === 200) {
      ElMessage.success(res.data || '已处理')
      dlg.value = false
      load()
    } else err.value = res.message || '处理失败'
  } catch (e) {
    err.value = e?.response?.data?.message || '处理失败'
  } finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.sa-page { padding: 0; }
.pg-hd { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.pg-l { display: flex; align-items: center; gap: 10px; }
.pg-t { font-size: 17px; font-weight: 700; color: #111827; margin: 0; }
.pg-tag { font-size: 11px; color: #4f46e5; background: #eef2ff; border: 1px solid #e0e7ff; padding: 2px 8px; border-radius: 6px; }
.gsxt { font-size: 12px; color: #6b7280; }
.gsxt a { color: #2563eb; text-decoration: none; }
.gsxt a:hover { text-decoration: underline; }

.sa-sum { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.ss-card {
  background: #fff; border: 1px solid #e8edf3; border-radius: 11px; padding: 12px 14px; cursor: pointer;
  transition: all .15s;
}
.ss-card:hover { border-color: #c7d2fe; }
.ss-card.on { border-color: #2f6bff; background: #eef3ff; box-shadow: 0 0 0 2px rgba(47,107,255,.10); }
.ss-l { font-size: 11.5px; color: #6b7280; margin-bottom: 6px; }
.ss-v { font-size: 20px; font-weight: 700; color: #111827; font-variant-numeric: tabular-nums; }
.ss-v.warn { color: #b45309; } .ss-v.ok { color: #15803d; } .ss-v.bad { color: #dc2626; }

.sa-tb-card { padding: 0; overflow-x: auto; }
.sa-empty { text-align: center; padding: 44px 20px; color: #71717a; font-size: 13px; }
.sa-empty .dim { font-size: 12px; color: #a1a1aa; margin-top: 6px; }
.sa-tb { width: 100%; min-width: 1080px; border-collapse: collapse; font-size: 12.5px; }
.sa-tb th {
  text-align: left; padding: 10px 12px; font-size: 11px; color: #71717a;
  background: #f4f4f5; border-bottom: 1px solid #e4e4e7; white-space: nowrap;
}
.sa-tb td { padding: 10px 12px; border-bottom: 1px solid #f4f4f5; white-space: nowrap; vertical-align: middle; }
.num { font-variant-numeric: tabular-nums; }
.dim { color: #9ca3af; }
.acc { font-weight: 600; color: #111827; }
.acc-s { font-size: 11px; color: #9ca3af; }
.co { max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
.co-a { color: #2563eb; text-decoration: none; }
.co-a:hover { text-decoration: underline; }
.st { font-size: 11px; padding: 2px 8px; border-radius: 6px; }
.st.pending { background: #fef3c7; color: #92400e; }
.st.approved { background: #dcfce7; color: #15803d; }
.st.rejected { background: #fee2e2; color: #dc2626; }
.st.reverify { background: #fef3c7; color: #92400e; }
.st.revoked { background: #fee2e2; color: #dc2626; }
.ocr-rv { margin-top: 8px; font-size: 12.5px; line-height: 1.7; padding: 8px 12px; border-radius: 8px; width: 100%; box-sizing: border-box; }
.ocr-match { background: #f0fdf4; color: #15803d; border: 1px solid #bbf7d0; }
.ocr-partial { background: #fffbeb; color: #b45309; border: 1px solid #fde68a; }
.ocr-mismatch { background: #fef2f2; color: #dc2626; border: 1px solid #fecaca; }
.ocr-unreadable { background: #f8fafc; color: #6b7280; border: 1px solid #e4e4e7; }
.op { display: flex; gap: 10px; }
.lnk { cursor: pointer; font-size: 12.5px; }
.lnk.ok { color: #15803d; } .lnk.bad { color: #dc2626; } .lnk.warn { color: #b45309; }
.warn-box { background: #fff7ed; border-color: #fed7aa; }
.warn-box .rv-check-t { color: #9a3412; }
.lnk:hover { text-decoration: underline; }

/* 弹窗 */
.rv-body { display: flex; flex-direction: column; gap: 14px; }
.rv-co { background: #fafbfc; border: 1px solid #e8edf3; border-radius: 9px; padding: 12px 14px; }
.rv-co-n { font-size: 13.5px; font-weight: 700; color: #111827; }
.rv-co-c { font-size: 12px; color: #6b7280; margin-top: 4px; }
.rv-co-u { font-size: 12px; color: #9ca3af; margin-top: 2px; }
.rv-check { background: #fffbeb; border: 1px solid #fde68a; border-radius: 9px; padding: 12px 14px; }
.rv-check-t { font-size: 12px; color: #92400e; margin-bottom: 8px; }
.rv-li { display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: #374151; padding: 3px 0; cursor: pointer; }
.rv-row { display: flex; flex-direction: column; gap: 6px; }
.rv-l { font-size: 12.5px; color: #52525b; }
.rv-ta {
  width: 100%; padding: 9px 11px; border: 1px solid #e2e8f0; border-radius: 8px;
  font-size: 13px; font-family: inherit; outline: none; resize: vertical;
}
.rv-ta:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.rv-err { font-size: 12.5px; color: #dc2626; background: #fef2f2; border: 1px solid #fecaca; padding: 8px 12px; border-radius: 8px; }
.btn {
  height: 34px; padding: 0 16px; border: 1px solid #e4e4e7; border-radius: 8px; background: #fff;
  font-size: 12.5px; color: #374151; cursor: pointer; font-family: inherit; transition: all .15s;
}
.btn.ghost:hover { border-color: #d1d5db; }
.btn.primary { background: #2f6bff; border-color: #2f6bff; color: #fff; }
.btn.primary:hover { background: #1e4fd6; border-color: #1e4fd6; }
.btn.danger { background: #dc2626; border-color: #dc2626; color: #fff; }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.swx { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; color: #374151;
  background: #f8fafc; border: 1px solid #e4e4e7; border-radius: 8px; padding: 6px 12px; cursor: pointer; }
.dg-tip { margin: 0 0 10px; font-size: 12.5px; color: #6b7280; line-height: 1.7; }
.pg-tools { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
.tools-group { display: flex; align-items: center; gap: 12px; background: #f8fafc;
  border: 1px solid #e4e4e7; border-radius: 10px; padding: 7px 12px; }
.tools-cap { font-size: 11.5px; color: #9ca3af; }
.tool-btn { height: 28px; padding: 0 12px; border: 1px solid #d9d9e0; border-radius: 7px;
  background: #fff; font-size: 12.5px; color: #374151; cursor: pointer; font-family: inherit; }
.tool-btn:hover { border-color: #2f6bff; color: #2f6bff; }
.tool-sep { width: 1px; height: 16px; background: #e4e4e7; }
.tool-sw { display: inline-flex; align-items: center; gap: 8px; font-size: 12.5px; color: #374151; cursor: pointer; }
.tool-gsxt { font-size: 12px; color: #6b7280; text-decoration: none; }
.tool-gsxt:hover { color: #2563eb; }

/* ===== V3 视觉重设计覆盖层 ===== */

/* ===== V3 视觉重设计 · 页头 hero 化 ===== */
.pg-hd {
  background: linear-gradient(118deg, #1e3a8a 0%, #2f6bff 58%, #5a8dff 100%);
  border: none; border-radius: 16px; padding: 24px 28px;
  box-shadow: 0 14px 30px -16px rgba(47, 107, 255, .55);
  margin-bottom: 14px;
}
.pg-hd .pg-t { color: #fff; font-size: 21px; letter-spacing: .5px; }
.pg-hd .pg-tag { color: rgba(255,255,255,.9); background: rgba(255,255,255,.16); border-color: transparent; }

.sa-page .pg-hd .gsxt { color: rgba(255,255,255,.88); }
.sa-page .pg-hd .gsxt a { color: #fff; }
.sa-page .pg-hd .tool-btn { background: rgba(255,255,255,.14); color: #fff; border-color: rgba(255,255,255,.38); }
.sa-page .pg-hd .tool-btn:hover { background: rgba(255,255,255,.26); }
.sa-page .pg-hd .tool-lab { color: rgba(255,255,255,.9); }
.sa-page .pg-hd .tool-box { border-color: rgba(255,255,255,.35); background: rgba(255,255,255,.08); }

/* 统计卡：彩色顶边 + hover */
.sa-page .ss-card { border-radius: 14px; position: relative; overflow: hidden; }
.sa-page .ss-card::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 4px; }
.sa-page .ss-card:nth-child(1)::before { background: #2f6bff; }
.sa-page .ss-card:nth-child(2)::before { background: #10b981; }
.sa-page .ss-card:nth-child(3)::before { background: #f59e0b; }
.sa-page .ss-card:nth-child(4)::before { background: #94a3b8; }
.sa-page .ss-card { transition: all .16s; }
.sa-page .ss-card:hover { transform: translateY(-2px); box-shadow: 0 14px 28px -16px rgba(15,23,42,.25); }
.sa-page .sa-empty { border-radius: 14px; }

</style>
