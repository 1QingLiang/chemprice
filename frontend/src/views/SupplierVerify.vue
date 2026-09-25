<template>
  <div class="sv-page">
    <div class="pg-hd">
      <div class="pg-l">
        <h3 class="pg-t">供应商认证</h3>
        <span class="pg-tag">企业实名 · 人工核验</span>
      </div>
    </div>

    <!-- 认证进度：实名 → 企业 两步 -->
    <div class="sv-steps">
      <div class="svs-step" :class="rn.done ? 'done' : 'cur'">
        <span class="svs-n">{{ rn.done ? '✓' : '1' }}</span>
        <div class="svs-x"><b>实名认证</b><span>{{ rn.done ? '已完成 · 公安库核验' : '免费 · 公安库二要素核验' }}</span></div>
      </div>
      <div class="svs-lnk" :class="{ lit: rn.done }"></div>
      <div class="svs-step" :class="me.supplier_status === 2 ? 'done' : (me.supplier_status === 1 ? 'wait' : (rn.done ? 'cur' : 'todo'))">
        <span class="svs-n">{{ me.supplier_status === 2 ? '✓' : '2' }}</span>
        <div class="svs-x"><b>企业认证</b><span>{{ me.supplier_status === 2 ? '已认证' : (me.supplier_status === 1 ? '审核中 · 等待管理员核验' : (me.supplier_status === 3 ? '需重新提交' : '上传执照 · 人工核验')) }}</span></div>
      </div>
    </div>

    <!-- 状态卡 -->
    <div class="cd sv-status" :class="statusCls">
      <div class="svs-ic"><component :is="statusIcon" :size="20" /></div>
      <div class="svs-b">
        <div class="svs-t">{{ statusTitle }}</div>
        <div class="svs-s">{{ statusDesc }}</div>
        <div class="svs-m" v-if="me.supplier_company">
          <Medal :size="12" /> {{ me.supplier_company }}
          <span v-if="apply && apply.valid_until" class="svs-until">
            有效期至 {{ String(apply.valid_until).slice(0, 10) }}
          </span>
        </div>
      </div>
      <div class="svs-act">
        <button v-if="me.supplier_status === 2" class="btn primary" @click="$router.push('/supply-demand')">
          去发布供应信息
        </button>
      </div>
    </div>

    <!-- 求购邮件通知（仅企业认证通过可见） -->
    <div class="cd dn-card" v-if="me.supplier_status === 2">
      <div class="dn-hd">
        <div class="dn-hd-x">
          <div class="dn-t"><Mail :size="15" /> 接收新求购邮件通知</div>
          <div class="dn-s">网站有新求购时，把求购产品信息发到你的邮箱。仅企业认证用户可用，可随时关闭。</div>
        </div>
        <label class="dn-switch" :class="{ on: dn.enabled, busy: dn.saving }">
          <input type="checkbox" v-model="dn.enabled" @change="saveDn" :disabled="dn.saving" />
          <span class="dn-knob"></span>
        </label>
      </div>
      <div class="dn-row">
        <span class="dn-label">接收邮箱</span>
        <input class="dn-input" type="email" v-model="dn.email"
               :placeholder="dn.placeholder || '用于接收求购通知的邮箱'"
               :disabled="dn.saving" @keyup.enter="saveDn" />
        <button class="dn-btn" @click="saveDn" :disabled="dn.saving">
          {{ dn.saving ? '保存中…' : '保存' }}
        </button>
      </div>
      <div class="dn-msg" v-if="dn.msg" :class="{ bad: dn.msgErr }">{{ dn.msg }}</div>
      <div class="dn-note">说明：只推送「求购」信息（供应信息不推送）；邮件只列出产品与需求，联系方式请到网站供需广场查看。</div>
    </div>

    <!-- 实名认证 -->
    <div class="cd rn-card">
      <div class="rn-hd">
        <span class="rn-ic"><ShieldCheck :size="15" /></span>
        <span class="rn-t">实名认证</span>
        <span class="rn-badge" :class="rn.done ? 'ok' : 'todo'">{{ rn.done ? '已实名' : '未实名' }}</span>
      </div>
      <p class="rn-d">发布求购 / 供应信息前需完成实名认证。认证信息仅平台留存，不对外展示。</p>
      <div class="rn-feats">
        <span class="rn-ft"><ShieldCheck :size="13" />公安库身份证二要素核验</span>
        <span class="rn-ft"><ShieldCheck :size="13" />免费 · 即时出结果</span>
        <span class="rn-ft"><ShieldCheck :size="13" />认证信息不对外展示</span>
      </div>
      <button v-if="!rn.done" class="btn primary rn-cta" @click="startRealname">免费实名认证 →</button>
      <div v-else class="rn-okline">已完成实名认证，可正常发布供需信息</div>
    </div>
    <el-dialog v-model="rnDlg" title="实名信息核验" width="420px">
      <div class="rnv">
        <div class="rnv-banner">
          <ShieldCheck :size="22" style="flex:none;margin-top:2px;" />
          <div>
            <b>公安库二要素核验 · 免费</b>
            <span>即时出结果；信息仅平台留存，不对外展示</span>
          </div>
        </div>
        <label class="rnv-l">真实姓名</label>
        <input v-model="rnName" class="rnv-i" placeholder="与身份证一致，如：张三" maxlength="30" />
        <label class="rnv-l" style="margin-top:12px;">身份证号</label>
        <input v-model="rnIdcard" class="rnv-i" placeholder="18 位居民身份证号码" maxlength="18"
               style="letter-spacing:.5px;" @input="rnIdcard = rnIdcard.toUpperCase()" />
        <div v-if="rnIdErr" class="rnv-err">{{ rnIdErr }}</div>
        <div class="rnv-foot">还可尝试 {{ rn.triesLeft }} 次 · 核验通道：阿里云市场（公安权威库）</div>
      </div>
      <template #footer>
        <el-button @click="rnDlg = false">取消</el-button>
        <el-button type="primary" :loading="rnVerifying" @click="verifyIdcard">提交核验</el-button>
      </template>
    </el-dialog>

    <!-- 审核中 / 已认证：显示申请详情，不给重复提交 -->
    <div class="cd sv-card" v-if="me.supplier_status === 1 || me.supplier_status === 2">
      <div class="sv-card-t">申请信息</div>
      <div class="sv-kv">
        <div class="sv-kv-i"><span class="sv-k">公司全称</span><span>{{ apply && apply.company_name }}</span></div>
        <div class="sv-kv-i"><span class="sv-k">信用代码</span><span class="num">{{ apply && apply.credit_code }}</span></div>
        <div class="sv-kv-i"><span class="sv-k">联系人</span><span>{{ apply && apply.contact_name }}</span></div>
        <div class="sv-kv-i"><span class="sv-k">联系电话</span><span class="num">{{ apply && apply.contact_phone }}</span></div>
        <div class="sv-kv-i"><span class="sv-k">提交时间</span><span>{{ fmt(apply && apply.created_at) }}</span></div>
        <div class="sv-kv-i" v-if="apply && apply.reviewed_at">
          <span class="sv-k">审核时间</span><span>{{ fmt(apply.reviewed_at) }}</span></div>
      </div>
      <div class="sv-note-ok" v-if="me.supplier_status === 2">
        <CircleCheck :size="13" /> 认证已通过。你可以在「供需广场」发布供应信息，并查看他人留下的完整联系方式。
      </div>
      <div class="sv-note-wait" v-else>
        <Clock :size="13" /> 申请已提交，管理员会在 1 个工作日内完成核验（核对「国家企业信用信息公示系统」的公开信息）。请留意站内通知。
      </div>
    </div>

    <!-- 未认证 / 已驳回：表单 -->
    <div class="cd sv-card" v-else>
      <div class="sv-card-t">{{ me.supplier_status === 3 ? '重新提交认证申请' : '提交认证申请' }}</div>
      <div class="sv-reject" v-if="me.supplier_status === 3 && apply && apply.remark">
        <TriangleAlert :size="13" /> 上次未通过：{{ apply.remark }}
      </div>

      <div class="sv-form">
        <div class="fm-row">
          <label class="fm-l"><i>*</i>营业执照图片</label>
          <div class="fm-c">
            <div class="lic-row">
              <label class="lic-btn">{{ licUploading ? '上传中…' : '选择图片' }}
                <input type="file" accept="image/jpeg,image/png,image/webp" style="display:none"
                       @change="onLicense" />
              </label>
              <span class="lic-name" v-if="licenseName">{{ licenseName }}</span>
              <span class="fm-h" style="margin:0;">jpg / png / webp，≤5MB；仅管理员可查看</span>
            </div>
            <div v-if="licPreview" class="lic-prev"><img :src="licPreview" alt="营业执照预览" /></div>
            <div v-if="licAi" class="lic-expired" :class="licAi.state">
              <TriangleAlert v-if="licAi.state === 'bad'" :size="13" />
              <CircleCheck v-else-if="licAi.state === 'ok'" :size="13" />
              <Clock v-else :size="13" />
              <span><b>AI 验证{{ licAi.state === 'ok' ? '通过' : licAi.state === 'bad' ? '未通过' : '' }}：</b>{{ licAi.message }}</span>
            </div>
            <div class="fm-h">必传。系统会自动识别执照上的营业期限，已过期将被拒绝；管理员会核对执照与公司信息的真实性。</div>
          </div>
        </div>

        <div class="fm-row">
          <label class="fm-l"><i>*</i>公司全称</label>
          <div class="fm-c">
            <input v-model="f.companyName" class="ipt" maxlength="128" placeholder="请与营业执照完全一致，例如：山东某某化工有限公司" />
            <div class="fm-h">必须与营业执照一致，我们会与公示系统比对。</div>
          </div>
        </div>
        <div class="fm-row">
          <label class="fm-l"><i>*</i>统一社会信用代码</label>
          <div class="fm-c">
            <input v-model="f.creditCode" class="ipt num" maxlength="18"
                   placeholder="18 位，例如 91310115MA1K3Q9R2X" @input="f.creditCode = f.creditCode.toUpperCase()" />
            <div class="fm-h">在营业执照上可找到；查询网址：国家企业信用信息公示系统（gsxt.gov.cn）。</div>
          </div>
        </div>
        <div class="fm-row">
          <label class="fm-l"><i>*</i>联系人 / 电话</label>
          <div class="fm-c" style="display:flex; gap:10px;">
            <input v-model="f.contactName" class="ipt" maxlength="64" placeholder="姓名" />
            <input v-model="f.contactPhone" class="ipt num" maxlength="11" placeholder="11 位手机号" />
          </div>
        </div>
        <div class="sv-err" v-if="err"><TriangleAlert :size="13" /> {{ err }}</div>

        <div class="sv-submit">
          <button class="btn primary" :disabled="submitting || (licAi && licAi.state === 'bad')" @click="submit">
            {{ submitting ? '提交中…' : (licAi && licAi.state === 'bad' ? 'AI 验证未通过，请更换执照' : '提交认证申请') }}
          </button>
          <span class="sv-agree">提交即表示你确认所填信息真实有效，虚假信息将被撤销认证并封禁账号。</span>
        </div>
      </div>
    </div>

    <!-- 说明 -->
    <div class="cd sv-help">
      <div class="sv-help-t">认证说明</div>
      <ol class="sv-help-l">
        <li><b>认证有什么用？</b>可查看供需信息里的<b>完整联系方式</b>；可发布<b>供应信息</b>，展示公司名称与「已认证」标识。</li>
        <li><b>怎么认证？</b>填写公司全称 + 统一社会信用代码。管理员会到「国家企业信用信息公示系统」核对：公司名与代码是否匹配、经营状态是否正常。免费、无需上传证件。</li>
        <li><b>要多久？</b>一般 1 个工作日内完成，结果通过站内通知告知。</li>
        <li><b>为什么会不通过？</b>常见原因：公司名与信用代码不匹配、企业已注销/吊销、信息填写与执照不一致。</li>
        <li><b>隐私提示？</b>供需信息中的手机号、邮箱默认脱敏展示；认证供应商每次查看完整联系方式都会留痕，且每日上限 50 条。</li>
      </ol>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Medal, CircleCheck, Clock, TriangleAlert, Hourglass, BadgeX, ShieldCheck, Mail } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import { getSupplierMe, applySupplier, uploadLicense, getRealnameStatus, createRealnameOrder, mockPayRealname, getAccount, claimRealnameOrder, getRealnameOrder, verifyRealnameId, getDemandNotify, saveDemandNotify } from '../api/index'

const me = ref({ supplier_status: 0, supplier_level: 0, supplier_company: null })
const apply = ref(null)
const submitting = ref(false)
const err = ref('')
const f = ref({ companyName: '', creditCode: '', contactName: '', contactPhone: '', licenseUrl: '' })
const licenseName = ref('')
const licPreview = ref('')
const licAi = ref(null)
const licUploading = ref(false)

// 求购邮件通知设置
const dn = ref({ enabled: false, email: '', placeholder: '', saving: false, msg: '', msgErr: false })

async function loadDn() {
  try {
    const res = await getDemandNotify()
    const d = res && res.data ? res.data : (res || {})
    dn.value.enabled = !!d.enabled
    dn.value.email = d.email || ''
    dn.value.placeholder = d.accountEmail ? ('默认：' + d.accountEmail) : '用于接收求购通知的邮箱'
  } catch (e) { /* 未登录或未认证时静默 */ }
}

async function saveDn() {
  dn.value.saving = true
  dn.value.msg = ''
  dn.value.msgErr = false
  try {
    const res = await saveDemandNotify({ enabled: dn.value.enabled, email: dn.value.email })
    const d = res && res.data ? res.data : (res || {})
    dn.value.enabled = !!d.enabled
    if (d.email) dn.value.email = d.email
    dn.value.msg = dn.value.enabled ? '已开启：之后有新求购会发到该邮箱' : '已关闭：不再接收求购邮件'
  } catch (e) {
    dn.value.msg = (e && (e.message || e.msg)) || '保存失败，请稍后重试'
    dn.value.msgErr = true
  } finally {
    dn.value.saving = false
  }
}

async function onLicense(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  if (file.size > 5 * 1024 * 1024) { err.value = '图片不能超过 5MB'; return }
  licUploading.value = true
  try {
    const res = await uploadLicense(file)
    if (res.code === 200) {
      f.value.licenseUrl = res.data.url
      licenseName.value = file.name
      licPreview.value = URL.createObjectURL(file)
      err.value = ''
      // AI 自动识别执照并回填公司名/信用代码（可手动修改）
      const o = res.data.ocr
      if (o && (o.company_name || o.credit_code)) {
        if (o.company_name) f.value.companyName = o.company_name
        if (o.credit_code) f.value.creditCode = o.credit_code
        ElMessage.success('已自动识别执照信息并填入，请核对')
      }
      const biz = res.data.business
      if (biz && biz.pass === false) licAi.value = { state: 'bad', message: biz.message }
      else if (biz && biz.pass === true) licAi.value = { state: 'ok', message: biz.message }
      else licAi.value = { state: 'unknown', message: (biz && biz.message) || '已识别执照信息，存续状态由管理员人工核验' }
    } else err.value = res.message || '上传失败'
  } catch (e2) {
    err.value = (e2 && e2.response && e2.response.data && e2.response.data.message) || '上传失败，请稍后重试'
  } finally { licUploading.value = false }
}

// ===== 实名认证（发布供需的前置条件） =====

/**
 * 实名认证完成后的统一收尾：提示 + 自动刷新页面。
 * 必须刷新：侧边栏「供需广场」菜单的可见性由 MainLayout 在挂载时拉取一次
 * （supplyOn = admin || (enabled && realname)），不刷新用户会看不到入口。
 * 延迟 800ms 是为了让用户看清成功提示。
 */
function doneRealname() {
  ElMessage.success('实名认证完成，正在刷新页面…')
  setTimeout(() => window.location.reload(), 800)
}
const rn = ref({ done: false, price: '0', mode: 'manual', triesLeft: 3, ordering: false, paying: false, step: 1, idcardChannel: false, qrUrl: '', payee: '', claiming: false })
const rnPayerNo = ref('')
const rnClaimErr = ref('')
const rnDlg = ref(false)
let rnOrderNo = ''
async function loadRealname() {
  try {
    const res = await getRealnameStatus()
    const d = res && res.data ? res.data : res
    rn.value.done = !!d.realname
    rn.value.price = d.price || '0'
    rn.value.mode = d.mode || 'manual'
    rn.value.idcardChannel = !!d.idcardChannel
    rn.value.triesLeft = (d.triesLeft === 0 || d.triesLeft) ? d.triesLeft : 3
  } catch (e) { /* 忽略 */ }
}
async function startRealname() {
  rnIdErr.value = ''
  rnDlg.value = true
}

let pollTimer = null
function pollOrder(orderNo) {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const res = await getRealnameOrder(orderNo)
      const d = res && res.data ? res.data : res
      if (d.realname) {
        clearInterval(pollTimer); pollTimer = null
        rn.value.done = true
        doneRealname()
      }
    } catch (e) { /* 忽略 */ }
  }, 3000)
}

async function claimPay() {
  rnIdErr.value = ''
  if (!/^\d{6,32}$/.test(rnPayerNo.value.trim())) {
    rnClaimErr.value = '请填写支付宝订单号（6-32 位数字，账单详情里可查）'; return
  }
  rn.value.claiming = true
  try {
    await claimRealnameOrder(rnOrderNo, rnPayerNo.value.trim())
    rnDlg.value = false
    ElMessage.success('已提交，管理员核对到账后实名即生效（通常几分钟内）')
  } catch (e) {
    rnClaimErr.value = (e && e.response && e.response.data && e.response.data.message) || '提交失败，请重试'
  } finally { rn.value.claiming = false }
}

async function confirmPay() {
  if (rn.value.mode !== 'mock') { ElMessage.warning('请使用收款码方式支付'); return }
  rn.value.paying = true
  try {
    const res = await mockPayRealname(rnOrderNo)
    if ((res && res.code) === 200) {
      if (rn.value.idcardChannel) {
        // 已配置身份证核验通道：进入第二步「姓名 + 身份证号」
        rn.value.step = 2
      } else {
        rn.value.done = true
        rnDlg.value = false
        doneRealname()
      }
    } else ElMessage.error((res && res.message) || '支付失败')
  } catch (e) { ElMessage.error('支付失败，请重试') }
  finally { rn.value.paying = false }
}

// ===== 第二步：身份证二要素核验 =====
const rnName = ref(''); const rnIdcard = ref(''); const rnIdErr = ref(''); const rnVerifying = ref(false)
async function verifyIdcard() {
  rnIdErr.value = ''
  if (rnName.value.trim().length < 2) { rnIdErr.value = '请填写真实姓名'; return }
  if (!/^\d{6}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9X]$/i.test(rnIdcard.value.trim())) {
    rnIdErr.value = '身份证号格式不正确'; return
  }
  rnVerifying.value = true
  try {
    // 手机浏览器（自带网络加速/代理分流）可能拦截 POST：网络类失败自动重试一次
    let res = null
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        res = await verifyRealnameId({ name: rnName.value.trim(), idcard: rnIdcard.value.trim() })
        break
      } catch (e3) {
        if (attempt === 2 || (e3 && e3.response)) throw e3   // 有 HTTP 响应的错误不重试
        await new Promise(r => setTimeout(r, 1500))
      }
    }
    const d = res && res.data ? res.data : res
    if (d && d.realname) {
      rn.value.done = true; rn.value.step = 1; rnDlg.value = false
      rnName.value = ''; rnIdcard.value = ''
      doneRealname()
    } else {
      rnIdErr.value = (d && d.message) || '姓名与身份证号不一致，请核对'
      loadRealname()   // 刷新剩余尝试次数
    }
  } catch (e) {
    const st = e && e.response && e.response.status
    const msg = e && e.response && e.response.data && e.response.data.message
    if (msg) rnIdErr.value = msg
    else if (st) rnIdErr.value = '核验服务异常（HTTP ' + st + '），请稍后重试'
    else rnIdErr.value = '提交失败，请稍后重试；如多次失败，可联系管理员协助完成实名认证'
    loadRealname()
  } finally { rnVerifying.value = false }
}

const statusCls = computed(() => ['s-none', 's-wait', 's-ok', 's-bad'][me.value.supplier_status || 0])
const statusTitle = computed(() => ['尚未认证', '认证审核中', '已认证供应商', '认证未通过'][me.value.supplier_status || 0])
const statusDesc = computed(() => [
  '完成企业认证后，可查看完整联系方式并发布供应信息。',
  '申请已提交，管理员正在核验工商信息。',
  '你已是认证供应商，可发布供应信息并查看完整联系方式。',
  '本次认证未通过，请核对信息后重新提交。'
][me.value.supplier_status || 0])
const statusIcon = computed(() => [TriangleAlert, Hourglass, CircleCheck, BadgeX][me.value.supplier_status || 0])

function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }

async function load() {
  try {
    const res = await getSupplierMe()
    if (res.code === 200 && res.data) {
      me.value = res.data
      apply.value = res.data.apply || null
      if (me.value.supplier_status === 3 && apply.value) {
        f.value.companyName = apply.value.company_name || ''
        f.value.creditCode = apply.value.credit_code || ''
        f.value.contactName = apply.value.contact_name || ''
        f.value.contactPhone = apply.value.contact_phone || ''
        if (apply.value.license_url) {
          f.value.licenseUrl = apply.value.license_url
          licenseName.value = '已保留上次上传的图片（可重新上传）'
        }
      }
    }
  } catch (e) { /* 静默 */ }
}

async function submit() {
  err.value = ''
  const v = f.value
  if (!v.companyName.trim() || v.companyName.trim().length < 4) { err.value = '请填写完整的公司全称'; return }
  if (!v.creditCode.trim()) { err.value = '请填写统一社会信用代码'; return }
  if (!v.contactName.trim()) { err.value = '请填写联系人'; return }
  if (!/^1[3-9]\d{9}$/.test(v.contactPhone.trim())) { err.value = '请填写正确的 11 位手机号'; return }
  if (!v.licenseUrl) { err.value = '请上传营业执照图片'; return }

  submitting.value = true
  try {
    const res = await applySupplier({ ...v, companyName: v.companyName.trim(), creditCode: v.creditCode.trim() })
    if (res.code === 200) { ElMessage.success('申请已提交，请等待审核'); await load() }
    else err.value = res.message || '提交失败'
  } catch (e) {
    err.value = e?.response?.data?.message || '提交失败，请稍后重试'
  } finally { submitting.value = false }
}

onMounted(async () => {
  load(); loadRealname(); loadDn()
  // 已绑定手机号直接带出到认证表单联系电话
  try {
    const res = await getAccount()
    const d = res && res.data ? res.data : res
    if (d && d.phoneBound && d.phone && !f.value.contactPhone) f.value.contactPhone = d.phone
  } catch (e) { /* 忽略 */ }
})
</script>

<style scoped>
.sv-page { padding: 0; max-width: 880px; }
.pg-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.pg-l { display: flex; align-items: center; gap: 10px; }
.pg-t { font-size: 17px; font-weight: 700; color: #111827; margin: 0; }
.pg-tag { font-size: 11px; color: #4f46e5; background: #eef2ff; border: 1px solid #e0e7ff; padding: 2px 8px; border-radius: 6px; }

/* 状态卡 */
.sv-status { display: flex; align-items: flex-start; gap: 14px; padding: 16px 18px; margin-bottom: 12px; }
.svs-ic { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex: none; }
.sv-status.s-none .svs-ic { background: #f4f4f5; color: #71717a; }
.sv-status.s-wait .svs-ic { background: #fef3c7; color: #b45309; }
.sv-status.s-ok   .svs-ic { background: #dcfce7; color: #15803d; }
.sv-status.s-bad  .svs-ic { background: #fee2e2; color: #dc2626; }
.svs-b { flex: 1; min-width: 0; }
.svs-t { font-size: 14.5px; font-weight: 700; color: #111827; margin-bottom: 4px; }
.svs-s { font-size: 12.5px; color: #6b7280; line-height: 1.6; }
.svs-m { display: inline-flex; align-items: center; gap: 5px; margin-top: 8px; font-size: 12.5px; color: #92400e; background: #fffbeb; border: 1px solid #fde68a; padding: 3px 9px; border-radius: 6px; }
.svs-until { color: #b45309; }
.svs-act { flex: none; }

/* 通用卡 */
.sv-card { padding: 18px; margin-bottom: 12px; }
.sv-card-t { font-size: 13.5px; font-weight: 700; color: #111827; margin-bottom: 14px; }
.sv-kv { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 20px; }
.sv-kv-i { display: flex; gap: 10px; font-size: 12.5px; color: #374151; }
.sv-k { color: #9ca3af; min-width: 66px; flex: none; }
.num { font-variant-numeric: tabular-nums; }
.sv-note-ok, .sv-note-wait, .sv-reject {
  display: flex; align-items: flex-start; gap: 7px; font-size: 12.5px; line-height: 1.7;
  padding: 10px 12px; border-radius: 9px; margin-top: 14px;
}
.sv-note-ok { background: #ecfdf5; border: 1px solid #d1fae5; color: #047857; }
.sv-note-wait { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; }
.sv-reject { background: #fef2f2; border: 1px solid #fecaca; color: #dc2626; margin: 0 0 14px; }

/* 表单 */
.sv-form { display: flex; flex-direction: column; gap: 14px; }
.fm-row { display: flex; gap: 14px; align-items: flex-start; }
.fm-l { width: 116px; flex: none; font-size: 12.5px; color: #52525b; padding-top: 10px; }
.fm-l i { color: #dc2626; font-style: normal; margin-right: 2px; }
.fm-c { flex: 1; min-width: 0; }
.ipt {
  width: 100%; height: 38px; padding: 0 11px; border: 1px solid #e2e8f0; border-radius: 8px;
  font-size: 13px; color: #18181b; background: #fff; font-family: inherit; outline: none;
}
.ipt:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.fm-h { font-size: 11.5px; color: #a1a1aa; margin-top: 5px; line-height: 1.6; }
.sv-err {
  display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: #dc2626;
  background: #fef2f2; border: 1px solid #fecaca; padding: 9px 12px; border-radius: 8px;
}
.sv-submit { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.btn {
  height: 36px; padding: 0 18px; border: 1px solid #e4e4e7; border-radius: 8px;
  background: #fff; font-size: 13px; color: #374151; cursor: pointer; font-family: inherit; transition: all .15s;
}
.btn.primary { background: #2f6bff; border-color: #2f6bff; color: #fff; }
.btn.primary:hover { background: #1e4fd6; }
.btn.primary:disabled { opacity: .55; cursor: not-allowed; }
.sv-agree { font-size: 11.5px; color: #a1a1aa; flex: 1; min-width: 200px; line-height: 1.6; }
.lic-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lic-btn {
  display: inline-block; padding: 8px 16px; border: 1px dashed #93c5fd; border-radius: 8px;
  font-size: 12.5px; color: #1d4ed8; background: #f8fbff; cursor: pointer;
}
.lic-btn:hover { background: #eff6ff; }
.lic-name { font-size: 12px; color: #374151; }
.lic-prev { margin-top: 8px; }
.lic-prev img {
  max-width: 260px; max-height: 180px; border: 1px solid #e8edf3;
  border-radius: 8px; display: block;
}

/* 说明 */
.sv-help { padding: 18px; }
.sv-help-t { font-size: 13.5px; font-weight: 700; color: #111827; margin-bottom: 10px; }
.sv-help-l { margin: 0; padding-left: 20px; }
.sv-help-l li { font-size: 12.5px; color: #4b5563; line-height: 1.9; margin-bottom: 4px; }
.sv-help-l b { color: #111827; }

@media (max-width: 640px) {
  .sv-kv { grid-template-columns: 1fr; }
  .fm-row { flex-direction: column; gap: 6px; }
  .fm-l { width: auto; padding-top: 0; }
}
.rn-card { margin-bottom: 14px; padding: 16px 18px; }
.rn-hd { display: flex; align-items: center; gap: 9px; margin-bottom: 10px; }
.rn-ic {
  width: 27px; height: 27px; border-radius: 8px; flex: none;
  background: #eef3ff; color: #2f6bff;
  display: inline-flex; align-items: center; justify-content: center;
}
.rn-t { font-size: 15px; font-weight: 700; color: #111827; flex: 1; }
.rn-badge { font-size: 11px; padding: 2px 9px; border-radius: 6px; }
.rn-badge.ok { background: #dcfce7; color: #15803d; }
.rn-badge.todo { background: #fef3c7; color: #b45309; }
.rn-d { margin: 0 0 12px; font-size: 12.5px; color: #6b7280; line-height: 1.7; }
.rn-feats { display: flex; flex-direction: column; gap: 7px; margin-bottom: 14px; }
.rn-ft { display: inline-flex; align-items: center; gap: 7px; font-size: 12.5px; color: #4b5563; }
.rn-ft svg { color: #059669; flex: none; }
.rn-cta { width: 100%; height: 38px; font-size: 13.5px; }
.rn-okline {
  font-size: 12.5px; color: #047857; background: #ecfdf5;
  border: 1px solid #d1fae5; border-radius: 8px; padding: 9px 12px;
}
.pay-box { text-align: center; padding: 8px 0 4px; }
.pay-amount { font-size: 34px; font-weight: 800; color: #111827; font-variant-numeric: tabular-nums; }
.pay-desc { font-size: 13px; color: #374151; margin-top: 4px; }
.pay-mode { font-size: 11.5px; color: #b45309; background: #fffbeb; border: 1px solid #fde68a;
  border-radius: 8px; padding: 7px 10px; margin-top: 12px; line-height: 1.6; }
.rnv-banner { display:flex; gap:10px; align-items:flex-start; background:#eef3ff;
  border:1px solid #d5e2ff; border-radius:10px; padding:12px 14px; margin-bottom:6px; color:#2f6bff; }
.rnv-banner b { display:block; font-size:13.5px; color:#1c2330; line-height:1.5; }
.rnv-banner span { font-size:12px; color:#6b7486; }
.rnv-l { display:block; font-size:12.5px; color:#52525b; margin:12px 0 6px; }
.rnv-i { width:100%; height:40px; padding:0 12px; border:1px solid #dbe1ea; border-radius:8px;
  font-size:14px; box-sizing:border-box; color:#1c2330; transition:border-color .15s, box-shadow .15s; }
.rnv-i:focus { outline:none; border-color:#2f6bff; box-shadow:0 0 0 3px rgba(47,107,255,.12); }
.rnv-err { margin-top:10px; font-size:12.5px; color:#c0392b; background:#fceeec;
  border-radius:6px; padding:7px 10px; }
.rnv-foot { margin-top:14px; font-size:11.5px; color:#8a94a6; }

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

/* 认证进度步骤条 */
.sv-steps {
  display: flex; align-items: center; gap: 12px;
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 18px; margin-bottom: 14px;
  box-shadow: 0 8px 22px -14px rgba(15,23,42,.14);
}
.svs-step { display: flex; align-items: center; gap: 10px; }
.svs-n {
  width: 26px; height: 26px; border-radius: 50%; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700;
  background: #eef2f7; color: #94a3b8;
}
.svs-step.done .svs-n { background: #10b981; color: #fff; }
.svs-step.cur .svs-n { background: #2f6bff; color: #fff; box-shadow: 0 0 0 4px rgba(47,107,255,.16); }
.svs-step.wait .svs-n { background: #f59e0b; color: #fff; }
.svs-x b { display: block; font-size: 13.5px; color: #101828; }
.svs-x span { font-size: 11.5px; color: #94a3b8; }
.svs-step.done .svs-x b { color: #059669; }
.svs-step.cur .svs-x b { color: #2f6bff; }
.svs-lnk { flex: 1; height: 2px; background: #e8edf3; border-radius: 2px; }
.svs-lnk.lit { background: linear-gradient(90deg, #10b981, #2f6bff); }

/* 状态卡与各卡统一轻量阴影 */
.sv-page .cd { border-radius: 14px; box-shadow: 0 8px 22px -14px rgba(15,23,42,.14); border-color: #eef2f7; }


/* 执照 AI 验证横幅：三态配色 */
.lic-expired {
  display: flex; align-items: center; gap: 6px; margin-top: 8px;
  font-size: 12px; color: #b91c1c; background: #fef2f2;
  border: 1px solid #fecaca; border-radius: 8px; padding: 7px 10px;
}
.lic-expired.bad { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }
.lic-expired.ok { color: #047857; background: #ecfdf5; border-color: #a7f3d0; }
.lic-expired.unknown { color: #92400e; background: #fffbeb; border-color: #fde68a; }


/* ===== 求购邮件通知卡 ===== */
.dn-card { padding: 16px 18px; }
.dn-hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.dn-t { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 700; color: var(--tx, #0f172a); }
.dn-s { margin-top: 6px; font-size: 12.5px; line-height: 1.7; color: var(--tx2, #64748b); max-width: 560px; }
.dn-switch { position: relative; flex: 0 0 auto; width: 46px; height: 26px; border-radius: 999px;
  background: #cbd5e1; transition: background .18s ease; cursor: pointer; }
.dn-switch input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.dn-switch .dn-knob { position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%;
  background: #fff; box-shadow: 0 1px 3px rgba(15,23,42,.28); transition: transform .18s ease; }
.dn-switch.on { background: #3b82f6; }
.dn-switch.on .dn-knob { transform: translateX(20px); }
.dn-switch.busy { opacity: .6; }
.dn-row { display: flex; align-items: center; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.dn-label { font-size: 12.5px; color: var(--tx2, #64748b); flex: 0 0 auto; }
.dn-input { flex: 1 1 240px; min-width: 200px; height: 34px; padding: 0 12px; font-size: 13px;
  border: 1px solid var(--bd, #dbe3ec); border-radius: 8px; background: var(--card, #fff); color: var(--tx, #0f172a); }
.dn-input:focus { outline: none; border-color: #3b82f6; }
.dn-btn { height: 34px; padding: 0 16px; font-size: 13px; border-radius: 8px; border: 1px solid var(--bd, #dbe3ec);
  background: var(--card, #fff); color: var(--tx, #0f172a); cursor: pointer; }
.dn-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.dn-btn:disabled { opacity: .6; cursor: not-allowed; }
.dn-msg { margin-top: 10px; font-size: 12.5px; color: #059669; }
.dn-msg.bad { color: #dc2626; }
.dn-note { margin-top: 8px; font-size: 11.5px; color: #94a3b8; }

/* ===== 认证页两栏排版（宽屏） ===== */
@media (min-width: 1240px) {
  .sv-page {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 350px;
    gap: 14px;
    align-items: start;
  }
  .sv-page > .pg-hd { grid-area: 1 / 1 / 2 / 3; }
  .sv-page > .sv-steps { grid-area: 2 / 1 / 3 / 3; }
  .sv-page > .sv-status { grid-area: 3 / 1; margin-bottom: 0; }
  .sv-page > .sv-card { grid-area: 4 / 1; }
  .sv-page > .rn-card { grid-area: 3 / 2 / 5 / 3; margin-bottom: 0; }
  .sv-page > .dn-card { grid-area: 5 / 1; margin-bottom: 0; }
  .sv-page > .sv-help { grid-area: 6 / 1 / 7 / 3; }
}
@media (max-width: 1239.9px) {
  .sv-page > .rn-card { order: 0; }
}

</style>
