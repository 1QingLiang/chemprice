<template>
  <div class="login-wrap">
    <!-- 粒子背景 -->
    <canvas ref="particleCanvas" class="login-particles"></canvas>
    <!-- 背景光斑 -->
    <div class="login-glow g1"></div>
    <div class="login-glow g2"></div>
    <div class="login-glow g3"></div>
    <!-- ===== 登录 ===== -->
    <div class="login-box" v-if="mode === 'login'">
      <img class="lg-big" src="/logo-dark.svg" alt="ChemPrice" width="68" height="68" />
      <h2>ChemPrice</h2>
      <div class="sub">化工品价格数据监控平台</div>
      <form @submit.prevent="handleLogin">
        <div class="fl">
          <label>用户名 / 邮箱</label>
          <input v-model="username" placeholder="请输入用户名或邮箱" autocomplete="username" />
          <label style="margin-top:14px">密码</label>
          <input v-model="password" type="password" placeholder="请输入密码" autocomplete="current-password" />
        </div>
        <button class="fl-b" type="submit" :disabled="loading">{{ loading ? '登录中...' : '登 录' }}</button>
        <div v-if="errorMsg" class="fl-err">{{ errorMsg }}</div>
      </form>
      <div class="login-links">
        <span class="link" @click="mode='forgot-email'">忘记密码</span>
        <span class="link" @click="mode='register'">注册账号</span>
      </div>
    </div>

    <!-- ===== 忘记密码：输入邮箱 ===== -->
    <div class="login-box" v-if="mode === 'forgot-email'">
      <h2>找回密码</h2>
      <div class="sub">输入注册邮箱，发送验证码重置密码</div>
      <form @submit.prevent="sendForgotCode">
        <div class="fl">
          <label>邮箱</label>
          <input v-model="forgotEmail" type="email" placeholder="请输入注册邮箱" />
        </div>
        <button class="fl-b" type="submit" :disabled="loading || cooldown > 0">
          {{ cooldown > 0 ? `重新发送(${cooldown}s)` : '发送验证码' }}
        </button>
        <div v-if="forgotMsg" class="fl-err" :style="{color: forgotOk?'#22c55e':'#ef4444'}">{{ forgotMsg }}</div>
      </form>
      <div class="login-links"><span class="link" @click="mode='login'">返回登录</span></div>
    </div>

    <!-- ===== 忘记密码：输入验证码+新密码 ===== -->
    <div class="login-box" v-if="mode === 'forgot-verify'">
      <h2>重置密码</h2>
      <div class="sub">验证码已发送到 {{ forgotEmail }}</div>
      <form @submit.prevent="handleResetPassword">
        <div class="fl">
          <label>验证码</label>
          <input v-model="forgotCode" placeholder="请输入6位验证码" maxlength="6" />
          <label style="margin-top:14px">新密码</label>
          <input v-model="forgotNewPwd" type="password" placeholder="请输入新密码（至少6位）" />
        </div>
        <button class="fl-b" type="submit" :disabled="loading">{{ loading ? '重置中...' : '重置密码' }}</button>
        <div v-if="forgotMsg" class="fl-err" :style="{color: forgotOk?'#22c55e':'#ef4444'}">{{ forgotMsg }}</div>
      </form>
      <div class="login-links"><span class="link" @click="mode='login'">返回登录</span></div>
    </div>

    <!-- ===== 注册：单页（用户名/昵称/邮箱/密码/确认密码/验证码） ===== -->
    <div class="login-box" v-if="mode === 'register'">
      <h2>注册账号</h2>
      <div class="sub">注册必须使用邮箱，通过邮箱验证码完成</div>
      <form @submit.prevent="handleSubmitRegister">
        <div class="fl">
          <label>用户名</label>
          <input v-model="regForm.username" placeholder="2-30 位字母/数字/下划线，不能含中文" maxlength="30" />
          <label style="margin-top:10px">昵称</label>
          <input v-model="regForm.nickname" placeholder="请输入昵称" />
          <label style="margin-top:10px">邮箱</label>
          <input v-model="regForm.email" type="email" placeholder="请输入邮箱" />
          <label style="margin-top:10px">密码</label>
          <input v-model="regForm.password" type="password" placeholder="请输入密码（至少6位）" />
          <label style="margin-top:10px">确认密码</label>
          <input v-model="regForm.confirmPassword" type="password" placeholder="再次输入密码" />
        </div>
        <button class="fl-b fl-b-ghost" type="button" :disabled="loading || cooldown > 0" @click="handleSendCode">
          {{ cooldown > 0 ? `重新发送(${cooldown}s)` : '发送验证码' }}
        </button>
        <div class="fl" style="margin-top:14px">
          <label>验证码</label>
          <input v-model="regCode" placeholder="请输入6位验证码" maxlength="6" />
        </div>
        <button class="fl-b" type="submit" :disabled="loading">{{ loading ? '注册中...' : '完 成 注 册' }}</button>
        <div v-if="regMsg" class="fl-err" :style="{color: regOk?'#22c55e':'#ef4444'}">{{ regMsg }}</div>
      </form>
      <div class="login-links"><span class="link" @click="mode='login'">已有账号？去登录</span></div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { sendCode, register, resetPasswordByEmail } from '../api/index'
import gsap from 'gsap'

const router = useRouter()
const authStore = useAuthStore()
const particleCanvas = ref(null)
let particleAnimId = null
let particleResizeObs = null

const mode = ref('login')
const loading = ref(false)
const cooldown = ref(0)
let cooldownTimer = null

function startCooldown() {
  cooldown.value = 60
  cooldownTimer = setInterval(() => {
    cooldown.value--
    if (cooldown.value <= 0) clearInterval(cooldownTimer)
  }, 1000)
}

// ===== 登录 =====
const username = ref('')
const password = ref('')
const errorMsg = ref('')

async function handleLogin() {
  if (!username.value || !password.value) { errorMsg.value = '请输入用户名/邮箱和密码'; return }
  loading.value = true; errorMsg.value = ''
  try {
    const res = await authStore.login({ username: username.value, password: password.value })
    if (res && res.code === 200) router.push(router.currentRoute.value.query.redirect || '/')
    else errorMsg.value = res?.message || '登录失败'
  } catch (e) {
    const msg = e?.response?.data?.message
    errorMsg.value = msg || (e?.code === 'ERR_NETWORK' ? '网络错误' : '登录失败')
  } finally { loading.value = false }
}

// ===== 忘记密码 =====
const forgotEmail = ref('')
const forgotCode = ref('')
const forgotNewPwd = ref('')
const forgotMsg = ref('')
const forgotOk = ref(false)

async function sendForgotCode() {
  if (!forgotEmail.value) { forgotMsg.value = '请输入邮箱'; forgotOk.value = false; return }
  loading.value = true; forgotMsg.value = ''
  try {
    const res = await sendCode(forgotEmail.value, 'forgot')
    if (res.code === 200) { forgotMsg.value = '验证码已发送，请查收邮箱'; forgotOk.value = true; mode.value = 'forgot-verify'; startCooldown() }
    else { forgotMsg.value = res.message; forgotOk.value = false }
  } catch (e) { forgotMsg.value = e?.response?.data?.message || '发送失败'; forgotOk.value = false }
  finally { loading.value = false }
}

async function handleResetPassword() {
  if (!forgotCode.value || !forgotNewPwd.value) { forgotMsg.value = '请输入验证码和新密码'; forgotOk.value = false; return }
  if (forgotNewPwd.value.length < 6) { forgotMsg.value = '密码至少6位'; forgotOk.value = false; return }
  loading.value = true; forgotMsg.value = ''
  try {
    const res = await resetPasswordByEmail(forgotEmail.value, forgotCode.value, forgotNewPwd.value)
    if (res.code === 200) { forgotMsg.value = '密码重置成功！请登录'; forgotOk.value = true; setTimeout(() => { mode.value = 'login'; password.value = '' }, 1500) }
    else { forgotMsg.value = res.message; forgotOk.value = false }
  } catch (e) { forgotMsg.value = e?.response?.data?.message || '重置失败'; forgotOk.value = false }
  finally { loading.value = false }
}

// ===== 注册（单页：用户名/昵称/邮箱/密码/确认密码/验证码） =====
const regForm = ref({ username: '', nickname: '', email: '', password: '', confirmPassword: '' })
const regCode = ref('')
const regMsg = ref('')
const regOk = ref(false)

// 用户名规则：2-30 位字母/数字/下划线，禁止中文与特殊字符
const USERNAME_RE = /^[A-Za-z0-9_]{2,30}$/
// 简单邮箱格式校验（与后端一致）
const EMAIL_RE = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/

function checkRegUsername() {
  const u = regForm.value.username.trim()
  if (!u) { regMsg.value = '请输入用户名'; regOk.value = false; return false }
  if (!USERNAME_RE.test(u)) { regMsg.value = '用户名仅支持 2-30 位字母、数字或下划线，不能包含中文或特殊字符'; regOk.value = false; return false }
  return true
}

function checkRegPassword() {
  if (!regForm.value.password) { regMsg.value = '请输入密码'; regOk.value = false; return false }
  if (regForm.value.password.length < 6) { regMsg.value = '密码至少6位'; regOk.value = false; return false }
  if (!regForm.value.confirmPassword) { regMsg.value = '请再次输入密码'; regOk.value = false; return false }
  if (regForm.value.password !== regForm.value.confirmPassword) { regMsg.value = '两次密码不一致'; regOk.value = false; return false }
  return true
}

function checkRegEmail() {
  const e = regForm.value.email?.trim() || ''
  if (!e) { regMsg.value = '请输入邮箱'; regOk.value = false; return false }
  if (!EMAIL_RE.test(e)) { regMsg.value = '邮箱格式不正确'; regOk.value = false; return false }
  return true
}

async function handleSendCode() {
  if (!checkRegUsername()) return
  if (!checkRegEmail()) return
  if (!checkRegPassword()) return
  loading.value = true; regMsg.value = ''
  try {
    const res = await sendCode(regForm.value.email.trim(), 'register')
    if (res.code === 200) { regMsg.value = '验证码已发送到邮箱，请输入下方'; regOk.value = true; startCooldown() }
    else { regMsg.value = res.message; regOk.value = false }
  } catch (e) { regMsg.value = e?.response?.data?.message || '发送失败'; regOk.value = false }
  finally { loading.value = false }
}

async function handleSubmitRegister() {
  if (!checkRegUsername()) return
  if (!checkRegEmail()) return
  if (!checkRegPassword()) return
  if (!regCode.value) { regMsg.value = '请输入验证码'; regOk.value = false; return }
  loading.value = true; regMsg.value = ''
  try {
    // 提交时移除 confirmPassword（仅前端校验用，不传给后端）
    const { confirmPassword, ...payload } = regForm.value
    const res = await register({ ...payload, code: regCode.value })
    if (res.code === 200) {
      regMsg.value = '注册成功！请登录'; regOk.value = true
      setTimeout(() => {
        mode.value = 'login'
        username.value = regForm.value.username
        regForm.value = { username: '', nickname: '', email: '', password: '', confirmPassword: '' }
        regCode.value = ''
      }, 1500)
    } else { regMsg.value = res.message; regOk.value = false }
  } catch (e) { regMsg.value = e?.response?.data?.message || '注册失败'; regOk.value = false }
  finally { loading.value = false }
}

let ctx
onMounted(() => {
  if (authStore.token) { router.replace('/'); return }
  ctx = gsap.context(() => {
    const mm = gsap.matchMedia()
    mm.add('(prefers-reduced-motion: no-preference)', () => {
      gsap.from('.login-box', { y: 15, autoAlpha: 0, duration: 0.4, ease: 'power2.out' })
      // 登录框呼吸光晕
      gsap.to('.login-box', { boxShadow: '0 0 80px rgba(59,130,246,.18)', duration: 3, ease: 'sine.inOut', repeat: -1, yoyo: true })
      // 光斑缓慢漂移
      gsap.to('.login-glow.g1', { x: 60, y: 30, duration: 14, ease: 'sine.inOut', repeat: -1, yoyo: true })
      gsap.to('.login-glow.g2', { x: -50, y: 40, duration: 18, ease: 'sine.inOut', repeat: -1, yoyo: true })
      gsap.to('.login-glow.g3', { x: 30, y: -40, duration: 16, ease: 'sine.inOut', repeat: -1, yoyo: true })
    })
    mm.add('(prefers-reduced-motion: reduce)', () => {
      gsap.from('.login-box', { autoAlpha: 0, duration: 0.3 })
    })
  })
  startLoginParticles()
})
onUnmounted(() => {
  ctx && ctx.revert()
  clearInterval(cooldownTimer)
  if (particleAnimId) cancelAnimationFrame(particleAnimId)
  if (particleResizeObs) particleResizeObs.disconnect()
})

// 登录页粒子背景（深色科技感，亮色粒子 + 连线）
function startLoginParticles() {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const canvas = particleCanvas.value
  if (!canvas) return
  const ctx2d = canvas.getContext('2d')
  const NUM = 70
  const LINK_DIST = 150
  let particles = []

  function resize() {
    canvas.width = window.innerWidth * window.devicePixelRatio
    canvas.height = window.innerHeight * window.devicePixelRatio
    canvas.style.width = window.innerWidth + 'px'
    canvas.style.height = window.innerHeight + 'px'
    ctx2d.setTransform(window.devicePixelRatio, 0, 0, window.devicePixelRatio, 0, 0)
  }
  resize()

  function init() {
    particles = Array.from({ length: NUM }, () => ({
      x: Math.random() * window.innerWidth,
      y: Math.random() * window.innerHeight,
      vx: (Math.random() - 0.5) * 0.3,
      vy: (Math.random() - 0.5) * 0.3,
      r: Math.random() * 1.8 + 0.6,
      hue: Math.random() > 0.5 ? '147,197,253' : '165,180,252' // 浅蓝 / 淡紫
    }))
  }
  init()

  let mouseX = -1000, mouseY = -1000
  canvas.addEventListener('mousemove', (e) => {
    mouseX = e.clientX
    mouseY = e.clientY
  })
  canvas.addEventListener('mouseleave', () => { mouseX = -1000; mouseY = -1000 })

  function tick() {
    ctx2d.clearRect(0, 0, window.innerWidth, window.innerHeight)
    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > window.innerWidth) p.vx *= -1
      if (p.y < 0 || p.y > window.innerHeight) p.vy *= -1
      // 鼠标吸引（登录页用吸引，和首页排斥相反更聚焦）
      const dx = mouseX - p.x, dy = mouseY - p.y
      const d2 = dx * dx + dy * dy
      if (d2 < 12000) {
        const d = Math.sqrt(d2) || 1
        p.x += (dx / d) * 0.35
        p.y += (dy / d) * 0.35
      }
      ctx2d.beginPath()
      ctx2d.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx2d.fillStyle = `rgba(${p.hue},0.8)`
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
          ctx2d.strokeStyle = `rgba(129,140,248,${0.3 * (1 - d / LINK_DIST)})`
          ctx2d.lineWidth = 0.6
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
.login-links { display: flex; justify-content: center; gap: 24px; margin-top: 16px; }
.link { font-size: 12px; color: #71717a; cursor: pointer; }
.link:hover { color: #3b82f6; }
.fl-b-ghost { background: transparent; color: #6366f1; border: 1px solid rgba(99,102,241,.45); margin-top: 6px; }
.fl-b-ghost:hover:not(:disabled) { background: rgba(99,102,241,.08); }
</style>
