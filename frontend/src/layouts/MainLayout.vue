<template>
  <div class="shell">
    <!-- 遮罩（手机端侧栏打开时） -->
    <div class="sb-overlay" :class="{ open: sidebarOpen }" @click="sidebarOpen = false"></div>
    <!-- 二维码点击放大：根级预览层（避免落在带 v-if 的容器里，移动端也要渲染） -->
    <Teleport to="body">
      <div v-if="zoomSrc" class="qr-zoom" @click="zoomSrc = ''">
        <img :src="zoomSrc" class="qr-zoom-img" alt="二维码" />
        <div class="qr-zoom-tip">点击任意处关闭</div>
      </div>
    </Teleport>
    <!-- 深色侧栏 -->
    <aside class="sb" :class="{ open: sidebarOpen }">
      <div class="sb-h">
        <img class="lg" src="/logo-dark.svg" alt="ChemPrice" width="34" height="34" />
        <span class="nm">ChemPrice</span>
        <span class="ver">v1.0</span>
      </div>
      <nav class="sb-nav">
        <div class="sb-sec">概览</div>
        <RouterLink
          v-for="item in groupOverview"
          :key="item.path"
          :to="item.path"
          class="sb-lk"
          :class="{ on: isActive(item.path) }"
        >
          <component :is="item.icon" />
          {{ item.title }}
        </RouterLink>
        <div class="sb-sec">供需对接</div>
        <!-- 供需广场：总开关 + 实名门禁（未实名不显示）；供应商认证始终可见（未实名用户要去这里实名） -->
        <RouterLink
          v-if="supplyOn"
          :to="'/supply-demand'"
          class="sb-lk"
          :class="{ on: isActive('/supply-demand') }"
        >
          <component :is="Handshake" />
          供需广场
        </RouterLink>
        <RouterLink
          :to="'/supplier-verify'"
          class="sb-lk"
          :class="{ on: isActive('/supplier-verify') }"
        >
          <component :is="BadgeCheck" />
          供应商认证
        </RouterLink>
        <div class="sb-sec">数据分析</div>
        <RouterLink
          v-for="item in groupData"
          :key="item.path"
          :to="item.path"
          class="sb-lk"
          :class="{ on: isActive(item.path) }"
        >
          <component :is="item.icon" />
          {{ item.title }}
        </RouterLink>
        <!-- 标点地图：菜单对所有用户常显；未开通者点进去会看到「需开通」引导（联系管理员） -->
        <RouterLink
          to="/enterprise-map"
          class="sb-lk"
          :class="{ on: isActive('/enterprise-map') }"
        >
          <component :is="MapPin" />
          标点地图
          <span v-if="emapOn === false" class="sb-need" :class="{ 'need-over': emapExpired }"
                :title="emapExpired ? ('权限已于 ' + emapExpireDate + ' 到期，请联系管理员续期') : '需管理员开通'"
                >{{ emapExpired ? '已到期' : '需开通' }}</span>
        </RouterLink>
        <template v-if="groupSystem.length">
          <div class="sb-sec">系统</div>
          <RouterLink
            v-for="item in groupSystem"
            :key="item.path"
            :to="item.path"
            class="sb-lk"
            :class="{ on: isActive(item.path) }"
          >
            <component :is="item.icon" />
            {{ item.title }}
          </RouterLink>
        
</template>
        <!-- 帮助手册：平台内嵌（/help 路由 -> iframe 载入 /guide/?embed=1），不跳新页面 -->
        <div class="sb-sec">帮助</div>
        <RouterLink to="/help" class="sb-lk sb-lk-help" :class="{ on: isActive('/help') }">
          <component :is="BookOpen" />
          帮助手册
        </RouterLink>
        <RouterLink to="/tools" class="sb-lk" :class="{ on: isActive('/tools') }">
          <component :is="Wrench" />
          办公工具
        </RouterLink>
        <RouterLink to="/chem-data" class="sb-lk" :class="{ on: isActive('/chem-data') }">
          <component :is="FlaskConical" />
          物性查询
        </RouterLink>
        <RouterLink to="/open-api" class="sb-lk" :class="{ on: isActive('/open-api') }">
          <component :is="KeyRound" />
          开放 API
        </RouterLink>
      </nav>
      <div class="sb-ft">
        <div class="nm">
          <b>{{ user.nickname || user.username || '管理员' }}</b><br />
          {{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}
        </div>
      </div>
    </aside>

    <!-- 主区 -->
    <div class="main">
      <canvas ref="bgCanvas" class="bg-particles"></canvas>
      <header class="hdr">
        <button class="burger" @click="sidebarOpen = !sidebarOpen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h18M3 6h18M3 18h18"/></svg>
        </button>
        <span class="hdr-t">{{ pageTitle }}</span>
        <span class="hdr-c">{{ pageCrumb }}</span>
        <!-- 公告走马灯（桌面：header 中部空白区） -->
        <div v-if="announcement" class="ann-bar ann-desktop" :class="announcement.level || 'normal'" @click="annDlg = true" title="点击查看完整公告">
          <span class="ann-horn">📢</span>
          <div class="ann-viewport">
            <div class="ann-track" :class="{ rolling: annRolling }">
              <span class="ann-text">{{ announcement.content }}</span>
              <span v-if="annRolling" class="ann-text">{{ announcement.content }}</span>
            </div>
          </div>
        </div>
        <div class="hdr-r">
          <!-- 头像：点击弹出悬浮窗，账号入口与「退出登录」都收在窗里 -->
          <div class="u-wrap">
            <button class="u-av" :class="{ on: uOpen }" title="账号与快捷入口"
                    @click.stop="toggleUser">
              <span class="u-av-c">{{ avatarChar }}</span>
              <span class="u-av-n">{{ user.nickname || user.username }}</span>
              <ChevronDown :size="13" class="u-av-caret" />
            </button>
            <div v-if="uOpen" class="u-pop" @click.stop>
              <div class="u-ph">
                <span class="u-ph-av">{{ avatarChar }}</span>
                <div class="u-ph-t">
                  <b>{{ user.nickname || user.username }}</b>
                  <span class="u-ph-sub">
                    <span class="u-role" :class="{ adm: user.role === 'ADMIN' }">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
                    <span v-if="user.username" class="u-uname">@{{ user.username }}</span>
                  </span>
                </div>
              </div>
              <div class="u-pb">
                <button class="u-item" @click="uGoAccount">
                  <CircleUser :size="15" /><span>账号与安全</span>
                </button>
                <button class="u-item" @click="uGo('/help')">
                  <BookOpen :size="15" /><span>使用手册</span>
                </button>
                <button class="u-item" @click="uGo('/tools')">
                  <Wrench :size="15" /><span>办公工具</span>
                </button>
              </div>
              <div class="u-pf">
                <button class="u-out" @click="logout">
                  <LogOut :size="15" /><span>退出登录</span>
                </button>
              </div>
            </div>
          </div>
          <div class="nt-wrap">
            <button class="hdr-btn nt-bell" :class="{ on: ntOpen }" title="通知"
                    @click.stop="toggleNt">
              <Bell :size="15" />
              <span v-if="ntUnread > 0" class="nt-badge">{{ ntUnread > 99 ? '99+' : ntUnread }}</span>
            </button>
            <div v-if="ntOpen" class="nt-panel" @click.stop>
              <div class="nt-ph">
                <span class="nt-pt">通知</span>
                <span v-if="ntUnread > 0" class="nt-pa" @click="readAllNt">全部已读</span>
              </div>
              <div class="nt-pb">
                <div v-if="!ntList.length" class="nt-empty">暂无通知</div>
                <div v-else v-for="msg in ntList" :key="msg.id" class="nt-item"
                     :class="{ unread: !msg.is_read }" @click="readNt(msg)">
                  <div class="nt-it">
                    <span class="nt-dot" v-if="!msg.is_read"></span>
                    <span class="nt-it-t">{{ msg.title }}</span>
                  </div>
                  <div class="nt-ic" v-if="msg.content">{{ msg.content }}</div>
                  <div class="nt-itime">{{ ntShort(msg.created_at) }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </header>
      <!-- 公告通栏（移动端：header 下方一行） -->
      <div v-if="announcement" class="ann-bar ann-mobile" :class="announcement.level || 'normal'" @click="annDlg = true">
        <span class="ann-horn">📢</span>
        <div class="ann-viewport">
          <div class="ann-track" :class="{ rolling: annRolling }">
            <span class="ann-text">{{ announcement.content }}</span>
            <span v-if="annRolling" class="ann-text">{{ announcement.content }}</span>
          </div>
        </div>
      </div>
      <div class="bdy" :class="{ 'bdy-flush': route.path === '/help' }">
        <router-view />
      </div>
    </div>

    <!-- 全站悬浮按钮：联系客服（可拖动，松手吸附到最近的左/右边缘） -->
    <button ref="contactFabRef" class="contact-fab" :style="fabContact.style"
            :class="{ 'fab-dragging': fabContact.dragging }"
            title="联系客服 / 关注公众号问价 / 赞赏支持（可拖动）"
            @pointerdown="fabContact.onPointerDown">
      <span class="fab-heart">💛</span><span class="fab-text">联系客服</span>
    </button>

    <el-dialog v-model="contactDlg" title="联系客服 · 赞赏支持" width="400px" :close-on-click-modal="true" class="contact-dlg">
      <div style="font-family:inherit">
        <div class="ct-sec gzh-sec">
          <div class="gzh-main">
          <div class="ct-title">📢 关注公众号 · 微信直接问价格</div>
          <div style="font-size:13px;color:#374151;line-height:1.8">
            关注公众号 <b style="color:#ef4444">「化工散文」</b>，在微信里发消息即可直接查价，无需登录：<br />
            <span class="gzh-eg">PP 昨日报价</span><span class="gzh-eg">甲醇 各地区价格</span><span class="gzh-eg">今天涨得最多的品种</span>
            <div style="font-size:11px;color:#9ca3af;margin-top:4px">公众号每天推送化工行情早报，涨跌动向不错过</div>
          </div>
          </div>
          <div class="gzh-qrcode">
            <img :src="'/gzh-qr.jpg'" alt="公众号二维码" class="gzh-qr qr-clickable"
                 @click="zoomSrc = '/gzh-qr.jpg'" />
            <div class="gzh-qr-tip">扫码关注</div>
          </div>
        </div>
        <div class="ct-sec gzh-sec grp-sec">
          <div class="gzh-main">
          <div class="ct-title">💬 加入化工交流群 · 同行一起看行情</div>
          <div style="font-size:13px;color:#374151;line-height:1.8">
            扫码加入 <b style="color:#c2410c">ChemPrice 化工交流群</b>，与同行交流行情、第一时间收新求购：
            <div style="font-size:11px;color:#9ca3af;margin-top:4px">群内自动同步「新求购」信息；进群请填写所属行业，方便审核</div>
          </div>
          </div>
          <div class="gzh-qrcode">
            <img v-if="!grpQrFail" :src="grpQrSrc" alt="化工交流群二维码" class="gzh-qr qr-clickable"
                 @error="grpQrFail = true" @click="zoomSrc = grpQrSrc" />
            <div v-else class="gzh-qr-fail">群二维码<br/>即将开放</div>
            <div class="gzh-qr-tip">{{ grpQrFail ? '即将开放' : '扫码入群' }}</div>
          </div>
        </div>
        <div class="ct-sec">
          <div class="ct-title">📮 联系管理员</div>
          <div style="font-size:13px;color:#374151;line-height:1.8">
            平台使用问题、数据咨询与商务合作，欢迎随时来信：<br />
            <a href="mailto:chemprice@163.com" style="color:#2563eb;font-weight:600;text-decoration:none">chemprice@163.com</a><br />
            工作日邮件将在 24 小时内回复。
          </div>
        </div>
        <div class="ct-sec pay-sec">
          <div class="gzh-main pay-main">
          <div class="ct-title">💛 赞赏支持</div>
          <div style="font-size:13px;color:#374151;line-height:1.8">
            ChemPrice 持续为你提供化工品价格数据服务。如果你觉得平台有帮助，欢迎自愿赞赏支持运营；<br />
            <b>赞赏后请联系管理员，可为你的账号开通更多「关注产品邮件推送」额度</b>（普通用户默认 <b>3</b> 个额度；<b>赞赏满 5 元可获得 10 额度/月</b>，开通后可推送多个关注产品的最新报价）。
          </div>
          </div>
          <div class="pay-qrcode">
            <img v-if="!qrFail" :src="qrSrc" alt="赞赏收款码" class="pay-qr qr-clickable"
                 @error="qrFail = true" @click="zoomSrc = qrSrc" />
            <div v-else class="pay-qr-fail">收款码<br/>筹备中</div>
            <div class="pay-qr-tip">微信 / 支付宝扫码赞赏</div>
          </div>
        </div>
        <!-- 赞赏收款码已并入上方赞赏支持区块（右侧） -->
        <div style="font-size:11px;color:#9ca3af;text-align:center;margin-top:6px">赞赏自愿 · 不赞赏不影响任何基础功能使用</div>
      </div>
    </el-dialog>

    <!-- 公告全文弹窗 -->
    <el-dialog v-model="annDlg" title="📢 平台公告" width="420px">
      <div style="font-size:14px;line-height:1.9;color:#374151;white-space:pre-wrap">{{ announcement?.content }}</div>
      <div v-if="announcement?.created_at" style="font-size:11px;color:#9ca3af;margin-top:10px;text-align:right">发布于 {{ announcement.created_at }}</div>
    </el-dialog>

    <!-- AI 智能问答悬浮窗（全站可用） -->
    <AiAssistant />

    <!-- 账号与安全（绑定手机号） -->
    <el-dialog v-model="acctOpen" title="账号与安全" width="540px">
      <div class="acct2">
        <!-- 用户头卡 -->
        <div class="au2-head">
          <div class="au2-av">{{ (user.nickname || user.username || '?').slice(0, 1) }}</div>
          <div class="au2-id">
            <b>{{ user.nickname || user.username }}</b>
            <span>{{ acct.username || user.username }} · {{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span>
          </div>
        </div>

        <!-- 双认证状态卡 -->
        <div class="au2-cards">
          <div class="au2-card" :class="acct.realnameStatus === 1 ? 'ok' : 'todo'">
            <div class="au2-ch">
              <ShieldCheck v-if="acct.realnameStatus === 1" :size="14" />
              <Clock v-else :size="14" />
              实名认证
            </div>
            <div class="au2-st" :class="{ ok: acct.realnameStatus === 1 }">{{ acct.realnameStatus === 1 ? '已实名' : '未实名' }}</div>
            <button v-if="acct.realnameStatus !== 1" class="au2-btn" @click="goVerify">免费实名 →</button>
            <div v-else class="au2-d">公安库二要素核验通过</div>
          </div>
          <div class="au2-card" :class="acct.supplierStatus === 2 ? 'ok' : ((acct.supplierStatus === 1 || acct.supplierStatus === 3) ? 'wait' : 'todo')">
            <div class="au2-ch"><Medal :size="14" /> 企业认证</div>
            <div class="au2-st" :class="{ ok: acct.supplierStatus === 2 }">
              {{ acct.supplierStatus === 2 ? '已认证' : (acct.supplierStatus === 1 ? '审核中' : (acct.supplierStatus === 3 ? '需重新认证' : '未认证')) }}
            </div>
            <button v-if="acct.supplierStatus !== 2" class="au2-btn" @click="goVerify">
              {{ acct.realnameStatus !== 1 ? '先去实名 →' : (acct.supplierStatus === 1 || acct.supplierStatus === 3 ? '查看进度 →' : '去认证 →') }}
            </button>
            <div v-else-if="acct.supplierCompany" class="au2-d">{{ acct.supplierCompany }}</div>
            <div v-else class="au2-d">可发布供应信息</div>
          </div>
        </div>

        <!-- 手机号区 -->
        <div class="au2-ph">
          <div class="au2-ph-hd">
            <span class="au2-k">手机号</span>
            <span class="au2-ph-v">{{ acct.phoneBound ? (acct.phone || '已绑定') : '未绑定' }}</span>
            <span v-if="acct.phoneBound" class="au2-bnd">已绑定账号</span>
          </div>
          <div v-if="!editingPhone && !acct.phoneBound" style="margin-top:8px;">
            <button class="au2-btn" @click="editingPhone = true; newPhone = ''">绑定手机号</button>
          </div>
          <div v-else-if="editingPhone && !acct.phoneBound" style="margin-top:8px;">
            <input v-model="newPhone" class="ac-in" maxlength="11" placeholder="11 位手机号"
                   @keyup.enter="savePhone" />
            <div class="ac-btns">
              <button class="au2-btn ghost" @click="editingPhone = false">取消</button>
              <button class="au2-btn" :disabled="phoneSaving" @click="savePhone">
                {{ phoneSaving ? '保存中…' : '保存' }}
              </button>
            </div>
          </div>
          <div class="au2-tip">绑定后不可修改，特殊情况请联系管理员。发布求购需「实名认证 + 手机号」，发布供应货源还需「企业认证」。</div>
        </div>
        <div v-if="acctErr" class="ac-err">{{ acctErr }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Home, LayoutDashboard, Table2, BarChart3, TrendingUp, Users, Boxes, Bell, Sparkles, Handshake, BadgeCheck, ClipboardList, CircleUser, ShieldCheck, Medal, Clock, Activity, BookOpen, Wrench, LogOut, ChevronDown, KeyRound, MessageSquare, FlaskConical, MapPin, Timer } from 'lucide-vue-next'
import { useAuthStore } from '../stores/auth'
import { getCurrentAnnouncement, getNotifications, getNtUnread,
         readNotification, readAllNotifications,
         getAccount, bindPhone, getSupplyStatus,
         getEnterpriseMapAccess } from '../api/index'
import gsap from 'gsap'
import AiAssistant from '../components/AiAssistant.vue'
import { useDragFab } from '../composables/useDragFab'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const user = computed(() => authStore.user || {})
const bgCanvas = ref(null)
let particleAnimId = null
const sidebarOpen = ref(false)
const contactDlg = ref(false)

// ===== 供需对接模块开关（管理员在「供应商审核」页控制；关闭时普通用户隐藏菜单） =====
const supplyOn = ref(true)
async function loadSupplyOn() {
  try {
    const st = await getSupplyStatus()
    const d = st && st.data ? st.data : st
    supplyOn.value = !!d.admin || (!!d.enabled && !!d.realname)
  } catch (e) { /* 查询失败不影响菜单显示 */ }
}

// ===== 标点地图权限状态（菜单常显，此状态仅用于是否打「需开通」标记） =====
// null = 未知/查询失败（不打标记，避免误报）；false = 明确未开通（打标记）；true = 已开通
const emapOn = ref(null)
const emapExpired = ref(false)
const emapExpireDate = ref('')
async function loadEmapOn() {
  try {
    const r = await getEnterpriseMapAccess()
    const d = r && r.data ? r.data : r
    emapOn.value = !!(d && d.enabled)
    // 已到期 → 标为「已到期」，比笼统的「需开通」更能说明原因
    emapExpired.value = !!(d && d.expired)
    emapExpireDate.value = (d && d.expireDate) || ''
  } catch (e) { /* 查询失败保持 null：不打「需开通」，以免对已开通用户造成误提示 */ }
}

// ===== 全站公告 =====
const announcement = ref(null)
const annDlg = ref(false)
const annRolling = ref(false)
async function loadAnnouncement() {
  try {
    const r = await getCurrentAnnouncement()
    if (r.code === 200 && r.data) {
      announcement.value = r.data
      annRolling.value = String(r.data.content || '').length > 28   // 超过约一行宽度就滚动
    } else { announcement.value = null }
  } catch (e) { console.error('公告加载失败', e) }
}

// 悬浮按钮：可拖动 + 松手吸附边缘（位置记忆在 localStorage）
const contactFabRef = ref(null)
const fabContact = useDragFab({
  elRef: contactFabRef,
  key: 'contact',
  defaultBottom: 84,
  onTap: () => { contactDlg.value = true },
})
const qrFail = ref(false)
const grpQrFail = ref(false) // 交流群二维码文件缺失时降级显示占位
const zoomSrc = ref('') // 二维码点击放大：非空时显示全屏预览层

onMounted(() => { loadAnnouncement() })

// ---------------- 账号与安全（绑定手机号） ----------------
const acctOpen = ref(false)
const acct = ref({})
const editingPhone = ref(false)
const newPhone = ref('')
const phoneSaving = ref(false)
const acctErr = ref('')

async function openAccount() {
  acctOpen.value = true
  acctErr.value = ''
  editingPhone.value = false
  try {
    const res = await getAccount()
    if (res.code === 200 && res.data) acct.value = res.data
  } catch (e) { /* 静默 */ }
}

async function savePhone() {
  acctErr.value = ''
  if (!/^1[3-9]\d{9}$/.test(newPhone.value.trim())) {
    acctErr.value = '请填写正确的 11 位手机号'; return
  }
  phoneSaving.value = true
  try {
    const res = await bindPhone(newPhone.value.trim())
    if (res.code === 200) {
      acct.value.phoneMasked = (res.data && res.data.phoneMasked) || ''
      acct.value.phoneBound = true
      editingPhone.value = false
      window.alert('手机号已绑定')
    } else acctErr.value = res.message || '保存失败'
  } catch (e) {
    acctErr.value = (e && e.response && e.response.data && e.response.data.message) || '保存失败'
  } finally { phoneSaving.value = false }
}

function goVerify() {
  acctOpen.value = false
  window.location.href = '/supplier-verify'
}

// ---------------- 站内通知 ----------------
const ntOpen = ref(false)
const uOpen = ref(false)   // 顶栏头像悬浮窗
const ntList = ref([])
const ntUnread = ref(0)
let ntTimer = null

function ntShort(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function loadNtCount() {
  try {
    const res = await getNtUnread()
    if (res.code === 200 && res.data) ntUnread.value = res.data.count || 0
  } catch (e) { /* 静默：铃铛拿不到数不影响页面 */ }
}

async function loadNtList() {
  try {
    const res = await getNotifications()
    if (res.code === 200 && res.data) {
      ntList.value = res.data.list || []
      ntUnread.value = res.data.unread || 0
    }
  } catch (e) { /* 静默 */ }
}

function toggleNt() {
  ntOpen.value = !ntOpen.value
  if (ntOpen.value) { uOpen.value = false; loadNtList() }
}

/* ===== 顶栏头像悬浮窗 ===== */
function toggleUser() {
  uOpen.value = !uOpen.value
  if (uOpen.value) ntOpen.value = false   // 两个浮层互斥，不同时压着
}
function closeUser() { uOpen.value = false }
function uGo(path) { uOpen.value = false; router.push(path) }
function uGoAccount() { uOpen.value = false; openAccount() }

/* 点击空白处 / 按 ESC 收起浮层 */
function onDocClickNt() {
  if (ntOpen.value) ntOpen.value = false
  if (uOpen.value) uOpen.value = false
}
function onKeyEsc(e) {
  if (e.key === 'Escape') { uOpen.value = false; ntOpen.value = false }
}

async function readNt(msg) {
  if (msg.is_read) return
  try {
    const res = await readNotification(msg.id)
    if (res.code === 200) { msg.is_read = 1; ntUnread.value = Math.max(0, ntUnread.value - 1) }
  } catch (e) { /* 静默 */ }
}

async function readAllNt() {
  try {
    const res = await readAllNotifications()
    if (res.code === 200) { ntList.value.forEach(m => { m.is_read = 1 }); ntUnread.value = 0 }
  } catch (e) { /* 静默 */ }
}

onMounted(() => {
  loadNtCount()
  ntTimer = setInterval(loadNtCount, 60000)
  document.addEventListener('click', onDocClickNt)
  window.addEventListener('keydown', onKeyEsc)
})
onUnmounted(() => {
  if (ntTimer) clearInterval(ntTimer)
  document.removeEventListener('click', onDocClickNt)
  window.removeEventListener('keydown', onKeyEsc)
})
const qrSrc = '/contact-qr.png' // 赞赏收款码（静态文件放网站根目录 contact-qr.png）
const grpQrSrc = '/group-qr.png' // 化工交流群二维码（静态文件放网站根目录 group-qr.png）

// 路由变化时自动关闭手机端侧栏
watch(() => route.path, () => { sidebarOpen.value = false; loadSupplyOn(); loadEmapOn() })

const avatarChar = computed(() => {
  const n = user.value.nickname || user.value.username || 'A'
  return n.charAt(0).toUpperCase()
})

const groupOverview = [
  { path: '/', title: '首页', icon: Home }
]
const groupData = [
  { path: '/dashboard', title: '看板', icon: LayoutDashboard },
  { path: '/commodities', title: '商品中心', icon: Boxes },
  { path: '/price-table', title: '数据查询', icon: Table2 },
  { path: '/movers', title: '涨跌排行', icon: TrendingUp },
  { path: '/push-settings', title: '邮件推送', icon: Bell }
]
const groupSupply = [
  { path: '/supply-demand', title: '供需广场', icon: Handshake },
  { path: '/supplier-verify', title: '供应商认证', icon: BadgeCheck }
]
const groupSystem = computed(() => {
  if (user.value.role !== 'ADMIN') return []
  return [
    { path: '/user-manage', title: '用户管理', icon: Users },
    { path: '/user-analytics', title: '用户看板', icon: Activity },
    { path: '/supplier-audit', title: '供应商审核', icon: ClipboardList },
    { path: '/ai-logs', title: 'AI 问答记录', icon: Sparkles },
    { path: '/scheduler', title: '定时服务监控', icon: Timer }
  ]
})

const pageMeta = {
  '/': { title: '首页', crumb: '' },
  '/dashboard': { title: '看板', crumb: '' },
  '/commodities': { title: '商品中心', crumb: '' },
  '/price-table': { title: '数据查询', crumb: '' },
  '/movers': { title: '涨跌排行', crumb: '' },
  '/push-settings': { title: '邮件推送', crumb: '' },
  '/user-manage': { title: '用户管理', crumb: '' },
  '/user-analytics': { title: '用户看板', crumb: '' },
  '/ai-logs': { title: 'AI 问答记录', crumb: '' },
  '/scheduler': { title: '定时服务监控', crumb: '' },
  '/analysis': { title: '报价点分析', crumb: '' },
  '/supply-demand': { title: '供需广场', crumb: '' },
  '/supplier-verify': { title: '供应商认证', crumb: '' },
  '/supplier-audit': { title: '供应商审核', crumb: '' },
  '/help': { title: '使用手册', crumb: '' },
  '/tools': { title: '办公工具', crumb: '' },
  '/chem-data': { title: '物性查询', crumb: '' },
  '/enterprise-map': { title: '标点地图', crumb: '' }
}
// 先查路径映射，再退回路由 meta.title，最后才是通用名
// （原先写死 '商品详情'，导致 /ai-logs、/analysis 等页顶栏标题串味）
const pageTitle = computed(
  () => pageMeta[route.path]?.title || route.meta?.title || 'ChemPrice'
)
const pageCrumb = computed(() => pageMeta[route.path]?.crumb || '')

function isActive(path) {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

function logout() {
  authStore.clearAuth()
  window.location.href = '/login'
}

let ctx
onMounted(() => {
  // 进入主界面时自动用后端最新信息刷新用户缓存（角色/权限变更后无需重新登录）
  authStore.refreshUser()
  loadSupplyOn()
  loadEmapOn()
  startBgParticles()
  ctx = gsap.context(() => {
    gsap.fromTo('.bdy > *', { y: 10, autoAlpha: 0 }, {
      y: 0, autoAlpha: 1, duration: 0.35, ease: 'power2.out', clearProps: 'transform'
    })
  })
})
onUnmounted(() => {
  ctx && ctx.revert()
  if (particleAnimId) cancelAnimationFrame(particleAnimId)
})

// 全站粒子背景（浅色风格：蓝/紫/青粒子 + 连线，毛玻璃卡片的"底色"）
function startBgParticles() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const canvas = bgCanvas.value
  if (!canvas) return
  const ctx2d = canvas.getContext('2d')
  // 低负载：减少粒子数量与连线距离（高流量页面避免卡顿）
  const NUM = 18
  const LINK_DIST = 90
  let particles = []
  let skip = 0 // 跳帧计数（每 2 帧重绘一次 ≈ 30fps）

  function resize() {
    const main = canvas.parentElement
    canvas.width = main.clientWidth * window.devicePixelRatio
    canvas.height = main.clientHeight * window.devicePixelRatio
    canvas.style.width = main.clientWidth + 'px'
    canvas.style.height = main.clientHeight + 'px'
    ctx2d.setTransform(window.devicePixelRatio, 0, 0, window.devicePixelRatio, 0, 0)
  }
  resize()

  function init() {
    particles = Array.from({ length: NUM }, () => ({
      x: Math.random() * canvas.width / window.devicePixelRatio,
      y: Math.random() * canvas.height / window.devicePixelRatio,
      vx: (Math.random() - 0.5) * 0.18,
      vy: (Math.random() - 0.5) * 0.18,
      r: Math.random() * 1.2 + 0.5,
      c: ['59,130,246', '168,85,247', '6,182,212', '34,197,94'][Math.floor(Math.random() * 4)]
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
    // 页面隐藏时不绘制
    if (document.hidden) { particleAnimId = requestAnimationFrame(tick); return }
    const w = canvas.width / window.devicePixelRatio
    const h = canvas.height / window.devicePixelRatio
    ctx2d.clearRect(0, 0, w, h)

    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > w) p.vx *= -1
      if (p.y < 0 || p.y > h) p.vy *= -1

      // 鼠标排斥
      const dx = p.x - mouseX, dy = p.y - mouseY
      const d2 = dx * dx + dy * dy
      if (d2 < 9000) {
        const d = Math.sqrt(d2) || 1
        p.x += (dx / d) * 0.5
        p.y += (dy / d) * 0.5
      }

      ctx2d.beginPath()
      ctx2d.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx2d.fillStyle = `rgba(${p.c},0.26)`
      ctx2d.fill()
    }

    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const a = particles[i], b = particles[j]
        const dx = a.x - b.x, dy = a.y - b.y
        const d = Math.sqrt(dx * dx + dy * dy)
        if (d < LINK_DIST) {
          ctx2d.beginPath()
          ctx2d.moveTo(a.x, a.y)
          ctx2d.lineTo(b.x, b.y)
          ctx2d.strokeStyle = `rgba(99,102,241,${0.10 * (1 - d / LINK_DIST)})`
          ctx2d.lineWidth = 0.5
          ctx2d.stroke()
        }
      }
    }
    particleAnimId = requestAnimationFrame(tick)
  }
  if (!reduce) tick()

  window.addEventListener('resize', () => { resize(); init() })
}
</script>

<style scoped>
/* 侧栏「帮助手册」：与其它分组一致，用 .sb-sec 分类小字「帮助」区隔；
   分类标题本身就是分隔，不再额外画线。 */

/* ===== 账号与安全 ===== */
/* ===== 顶栏头像悬浮菜单 ===== */
.u-wrap { position: relative; }
.u-av {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 3px 9px 3px 4px; border-radius: 999px; cursor: pointer; font-family: inherit;
  border: 1px solid var(--border); background: var(--card); color: #374151;
  transition: border-color .12s, background .12s, box-shadow .12s;
}
.u-av:hover, .u-av.on { border-color: #b9c0cc; background: #fff; box-shadow: 0 1px 3px rgba(9, 9, 11, .06); }
.u-av-c {
  width: 24px; height: 24px; border-radius: 50%; flex: none;
  background: linear-gradient(135deg, var(--purple), var(--blue));
  color: #fff; font-size: 11.5px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.u-av-n { font-size: 12.5px; max-width: 96px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.u-av-caret { color: var(--ink4); flex: none; transition: transform .15s; }
.u-av.on .u-av-caret { transform: rotate(180deg); }

.u-pop {
  position: absolute; right: 0; top: calc(100% + 9px); width: 252px;
  background: #fff; border: 1px solid var(--border); border-radius: 12px;
  box-shadow: 0 14px 36px -12px rgba(15, 23, 42, .30); overflow: hidden; z-index: 80;
}
.u-ph {
  display: flex; align-items: center; gap: 10px;
  padding: 13px 14px; background: #fafbfc; border-bottom: 1px solid #f1f5f9;
}
.u-ph-av {
  width: 38px; height: 38px; border-radius: 50%; flex: none;
  background: linear-gradient(135deg, var(--purple), var(--blue));
  color: #fff; font-size: 15px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.u-ph-t { min-width: 0; }
.u-ph-t b { display: block; font-size: 13.5px; font-weight: 600; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.u-ph-sub { display: flex; align-items: center; gap: 6px; margin-top: 3px; }
.u-role { font-size: 11px; color: #71717a; background: #eceef1; border-radius: 5px; padding: 1px 6px; flex: none; }
.u-role.adm { color: #1d4ed8; background: #dbeafe; }
.u-uname { font-size: 11px; color: var(--ink4); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.u-pb { padding: 6px; display: flex; flex-direction: column; gap: 1px; }
.u-item {
  display: flex; align-items: center; gap: 9px; width: 100%; text-align: left;
  padding: 8px 10px; border: 0; border-radius: var(--r); cursor: pointer;
  font-family: inherit; font-size: 13px; color: #374151; background: none;
}
.u-item:hover { background: #f4f5f7; color: var(--ink); }
.u-item svg { color: var(--ink4); flex: none; }
.u-item:hover svg { color: var(--ink3); }
.u-pf { padding: 6px; border-top: 1px solid #f1f5f9; background: #fcfcfd; }
.u-out {
  display: flex; align-items: center; gap: 9px; width: 100%;
  padding: 8px 10px; border: 0; border-radius: var(--r); cursor: pointer;
  font-family: inherit; font-size: 13px; font-weight: 500; color: #dc2626; background: none;
}
.u-out:hover { background: #fef2f2; }
@media (max-width: 768px) { .u-av-n { display: none; } .u-pop { width: 236px; } }
.acct { display: flex; flex-direction: column; gap: 12px; }
.ac-row { display: flex; gap: 12px; font-size: 13.5px; color: #374151; align-items: center; }
.ac-k { color: #9ca3af; min-width: 66px; flex: none; font-size: 12.5px; }
.ac-sup { color: #15803d; background: #dcfce7; padding: 2px 9px; border-radius: 6px; font-size: 12.5px; }
.ac-nsup { color: #9ca3af; }
.ac-link { color: #2563eb; cursor: pointer; }
.ac-link:hover { text-decoration: underline; }
.ac-ph { border: 1px solid #e8edf3; border-radius: 10px; padding: 12px 14px; background: #fafbfc; }
.ac-ph-hd { display: flex; align-items: center; justify-content: space-between; }
.ac-mask { font-size: 13.5px; color: #111827; letter-spacing: .5px; }
.ac-in {
  width: 100%; height: 36px; padding: 0 11px; border: 1px solid #e2e8f0; border-radius: 8px;
  font-size: 13px; font-family: inherit; outline: none;
}
.ac-in:focus { border-color: #93c5fd; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
.ac-btns { display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px; }
.ac-save { background: #111827; color: #fff; border-color: #111827; }
.ac-save:hover { background: #1f2937; }
.ac-save:disabled { opacity: .55; }
.ac-tip { font-size: 11.5px; color: #9ca3af; line-height: 1.7; margin-top: 10px; }
.ac-err {
  font-size: 12.5px; color: #dc2626; background: #fef2f2;
  border: 1px solid #fecaca; padding: 8px 12px; border-radius: 8px;
}

/* ===== 站内通知（头部铃铛） ===== */
.nt-wrap { position: relative; }
.nt-bell {
  display: inline-flex; align-items: center; justify-content: center;
  padding: 7px 9px; position: relative; color: #374151;
}
.nt-bell.on, .nt-bell:hover { background: var(--ink); color: #fff; border-color: var(--ink); }
.nt-badge {
  position: absolute; top: -6px; right: -7px; min-width: 17px; height: 17px;
  background: #ef4444; color: #fff; border-radius: 9px; font-size: 10px; font-weight: 700;
  display: flex; align-items: center; justify-content: center; padding: 0 4px; line-height: 1;
  border: 2px solid #fff; box-sizing: content-box;
}
.nt-panel {
  position: absolute; right: 0; top: calc(100% + 9px); width: 344px; max-height: 470px;
  background: #fff; border: 1px solid var(--border); border-radius: 12px;
  box-shadow: 0 14px 36px -12px rgba(15, 23, 42, .30); overflow: hidden; z-index: 70;
}
.nt-ph {
  display: flex; align-items: center; justify-content: space-between;
  padding: 11px 14px; border-bottom: 1px solid #f1f5f9; background: #fafbfc;
}
.nt-pt { font-size: 13px; font-weight: 700; color: #111827; }
.nt-pa { font-size: 12px; color: #2563eb; cursor: pointer; }
.nt-pa:hover { text-decoration: underline; }
.nt-pb { max-height: 390px; overflow-y: auto; }
.nt-empty { text-align: center; padding: 36px 16px; color: #9ca3af; font-size: 12.5px; }
.nt-item { padding: 11px 14px; border-bottom: 1px solid #f4f4f5; cursor: pointer; transition: background .12s; }
.nt-item:last-child { border-bottom: 0; }
.nt-item:hover { background: #fafafa; }
.nt-item.unread { background: #f6f9ff; }
.nt-it { display: flex; align-items: center; gap: 6px; }
.nt-dot { width: 7px; height: 7px; border-radius: 50%; background: #2563eb; flex: none; }
.nt-it-t { font-size: 13px; font-weight: 600; color: #111827; }
.nt-item:not(.unread) .nt-it-t { font-weight: 500; color: #374151; }
.nt-ic {
  font-size: 12px; color: #6b7280; line-height: 1.65; margin-top: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.nt-itime { font-size: 11px; color: #a1a1aa; margin-top: 4px; }
.contact-fab {
  position: fixed;
  right: 22px;
  bottom: 84px;
  width: 50px;
  height: 50px;
  padding: 0;
  border-radius: 50%;
  border: none;
  cursor: grab;
  gap: 0;
  flex-direction: column;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0;
  text-shadow: 0 1px 2px rgba(0,0,0,.12);
  background: linear-gradient(135deg, #f97316, #ef4444);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(239, 68, 68, 0.42);
  z-index: 999;
  transition: transform .18s ease, box-shadow .18s ease;
  touch-action: none;          /* 移动端拖动必需 */
  user-select: none;
  -webkit-user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.contact-fab .fab-heart { font-size: 15px; line-height: 1.1; }
/* 文案为 4 字「联系客服」，与旁边「AI问价」按钮统一 9px / -0.2px，避免字挤到圆边 */
.contact-fab .fab-text { font-size: 9px; letter-spacing: -.2px; white-space: nowrap; margin-top: 1px; }
.contact-fab:hover { transform: translateY(-3px) scale(1.05); box-shadow: 0 10px 24px rgba(239, 68, 68, 0.5); }
.contact-fab.fab-dragging { cursor: grabbing; transform: none !important; box-shadow: 0 12px 26px rgba(239, 68, 68, 0.55); }
.ct-sec { margin-bottom: 14px; }

/* ===== 公告走马灯 ===== */
.ann-bar {
  display: flex; align-items: center; gap: 8px;
  background: linear-gradient(90deg, #fffbeb, #fef3c7);
  border: 1px solid #fde68a; border-radius: 8px;
  color: #92400e; cursor: pointer; overflow: hidden;
}
.ann-desktop { flex: 1; min-width: 0; height: 30px; padding: 0 10px; margin: 0 12px; }
.ann-mobile { display: none; height: 32px; padding: 0 12px; margin: 8px 12px 0; }
.ann-horn { flex: none; font-size: 15px; }
.ann-viewport { flex: 1; min-width: 0; overflow: hidden; }
.ann-track { display: inline-flex; white-space: nowrap; }
.ann-text { font-size: 13px; font-weight: 600; padding-right: 60px; }
.ann-track.rolling { animation: ann-scroll 18s linear infinite; }
.ann-bar:hover .ann-track.rolling { animation-play-state: paused; }
@keyframes ann-scroll { from { transform: translateX(0); } to { transform: translateX(-50%); } }
@media (max-width: 768px) {
  .ann-desktop { display: none; }
  .ann-mobile { display: flex; }
}
.gzh-sec { background: linear-gradient(135deg, #fff7ed, #fef2f2); border: 1px solid #fed7aa; border-radius: 10px; padding: 10px 12px; display: flex; gap: 10px; align-items: center; }
.gzh-main { flex: 1; min-width: 0; }
.gzh-qrcode { flex-shrink: 0; text-align: center; }
.gzh-qr { width: 92px; height: 92px; border-radius: 8px; border: 1px solid #fed7aa; background: #fff; display: block; }
.gzh-qr-tip { font-size: 11px; color: #c2410c; margin-top: 3px; font-weight: 600; }
.gzh-qr-fail { width: 92px; height: 92px; border: 1px dashed #fed7aa; border-radius: 8px; color: #c2410c;
  font-size: 12px; display: flex; align-items: center; justify-content: center; text-align: center; background: #fff; }
/* 交流群区块：与公众号区块同构，色调换成蓝色系以便一眼区分 */
.grp-sec { background: linear-gradient(135deg, #eff6ff, #f0f9ff); border-color: #bfdbfe; }
.grp-sec .ct-title { color: #1e3a8a; }
.grp-sec .gzh-qr { border-color: #bfdbfe; }
.grp-sec .gzh-qr-tip { color: #1d4ed8; }
.grp-sec .gzh-qr-fail { border-color: #bfdbfe; color: #1d4ed8; }
.pay-sec { display: flex; gap: 10px; align-items: center; background: #fffbeb; border: 1px dashed #fdba74; border-radius: 10px; padding: 10px 12px; }
.pay-main { flex: 1; min-width: 0; }
.pay-qrcode { flex-shrink: 0; text-align: center; }
.pay-qr { display: block; margin: 0 auto; max-width: 96px; max-height: 130px; border-radius: 8px; border: 1px dashed #fdba74; background: #fff; padding: 3px; }
.pay-qr-tip { font-size: 11px; color: #c2410c; margin-top: 3px; font-weight: 600; }
.pay-qr-fail { width: 96px; height: 96px; border: 1px dashed #fdba74; border-radius: 8px; color: #c2410c; font-size: 12px; display: flex; align-items: center; justify-content: center; text-align: center; background: #fff; }
.gzh-eg { display: inline-block; background: #fff; border: 1px solid #fdba74; border-radius: 12px; padding: 1px 8px; font-size: 12px; color: #c2410c; margin: 3px 6px 0 0; }
.ct-title { font-size: 14px; font-weight: 700; color: #111827; margin-bottom: 6px; }
.contact-dlg :deep(.el-dialog__title) { font-weight: 700; color: #111827; }

.qr-clickable { cursor: zoom-in; transition: transform .15s; }
.qr-clickable:active { transform: scale(.96); }
.qr-zoom {
  position: fixed; inset: 0; z-index: 4000;
  background: rgba(0, 0, 0, .82);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  cursor: zoom-out; animation: qrFadeIn .18s ease-out;
}
.qr-zoom-img {
  max-width: 76vw; max-height: 70vh;
  background: #fff; padding: 14px; border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .45);
}
.qr-zoom-tip { color: #fff; font-size: 13px; margin-top: 16px; opacity: .85; letter-spacing: 1px; }
@keyframes qrFadeIn { from { opacity: 0; } to { opacity: 1; } }
.ac-wait { color: #b45309; font-size: 12.5px; }

/* ===== 账号与安全 V2 ===== */
.au2-head { display: flex; align-items: center; gap: 12px; padding: 2px 2px 14px; }
.au2-av {
  width: 48px; height: 48px; border-radius: 50%; flex: none;
  background: linear-gradient(135deg, #2f6bff, #5a8dff); color: #fff;
  font-size: 21px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 16px -8px rgba(47, 107, 255, .55);
}
.au2-id b { display: block; font-size: 16px; color: #101828; }
.au2-id span { font-size: 12px; color: #94a3b8; }
.au2-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }
.au2-card { border: 1px solid #eef2f7; border-radius: 12px; padding: 12px; background: #fbfcfe; }
.au2-card.ok { background: #f2fbf7; border-color: #c9f0e0; }
.au2-card.wait { background: #fffbeb; border-color: #fde68a; }
.au2-ch { display: flex; align-items: center; gap: 5px; font-size: 12px; color: #64748b; font-weight: 600; }
.au2-st { margin: 6px 0 8px; font-size: 14px; font-weight: 700; color: #334155; }
.au2-st.ok { color: #059669; }
.au2-card.wait .au2-st { color: #b45309; }
.au2-btn {
  border: none; background: #2f6bff; color: #fff; font-size: 12px;
  padding: 6px 12px; border-radius: 8px; cursor: pointer; font-family: inherit;
}
.au2-btn:hover { background: #1e4fd6; }
.au2-btn.ghost { background: #fff; color: #52525b; border: 1px solid #e4e4e7; }
.au2-d { font-size: 11px; color: #94a3b8; line-height: 1.5; }
.au2-ph { border-top: 1px solid #f1f5f9; padding-top: 12px; }
.au2-ph-hd { display: flex; align-items: center; gap: 10px; }
.au2-k { font-size: 12px; color: #94a3b8; }
.au2-ph-v { font-size: 15px; font-weight: 700; color: #101828; letter-spacing: .5px; }
.au2-bnd {
  font-size: 10.5px; font-weight: 600; color: #059669; background: #ecfdf5;
  border: 1px solid #a7f3d0; padding: 0 6px; border-radius: 999px; line-height: 16px;
}
.au2-tip { margin-top: 10px; font-size: 11px; color: #a1a1aa; line-height: 1.6; }


/* 公告按级别配色（与「公告管理」里的级别对应） */
.ann-bar.important { background: #fff7ed; border-color: #fdba74; }
.ann-bar.important .ann-text { color: #c2410c; font-weight: 600; }
.ann-bar.maintenance { background: #f5f3ff; border-color: #c7d2fe; }
.ann-bar.maintenance .ann-text { color: #6d28d9; font-weight: 600; }

/* 侧栏「需开通」小标记（标点地图未授权时提示；点进去有开通引导） */
.sb-need {
  margin-left: auto; flex-shrink: 0;
  font-size: 10px; font-weight: 500; line-height: 16px;
  color: #fbbf24; background: rgba(251, 191, 36, .12);
  border: 1px solid rgba(251, 191, 36, .35);
  border-radius: 999px; padding: 0 6px;
}
.sb-lk.on .sb-need { color: #fcd34d; }
/* 「已到期」：换警示色，和「需开通」区分开 */
.sb-need.need-over {
  color: #f87171; background: rgba(248, 113, 113, .12);
  border-color: rgba(248, 113, 113, .45);
}
.sb-lk.on .sb-need.need-over { color: #fca5a5; }
</style>
