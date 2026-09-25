import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import router from './router'
import App from './App.vue'
import './style.css'

const app = createApp(App)

// 注册 Pinia
const pinia = createPinia()
app.use(pinia)

// 注册 Vue Router
app.use(router)

// 注册 Element Plus（中文本地化，日期筛选器等组件显示中文）
app.use(ElementPlus, { locale: zhCn })

// 注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 挂载应用
app.mount('#app')
// ===== 部署自愈：旧页面引用的 chunk 已被新版本替换、加载失败时，自动刷新一次 =====
// 用户无需手动退出重登；刷新后会拿到最新的 index.html 与新资源。
const CHUNK_ERR = /Failed to fetch dynamically imported module|Importing a module script failed|Loading( CSS)? chunk [\d]+ failed/i
let __lastChunkReload = 0
function recoverFromChunkError() {
  const now = Date.now()
  if (now - __lastChunkReload < 15000) return   // 防止循环刷新
  __lastChunkReload = now
  console.warn('[ChemPrice] 检测到平台已更新，正在自动刷新页面…')
  window.location.reload()
}
router.onError((error) => {
  if (CHUNK_ERR.test(String((error && error.message) || ''))) recoverFromChunkError()
})
window.addEventListener('unhandledrejection', (e) => {
  const m = String((e && e.reason && e.reason.message) || '')
  if (CHUNK_ERR.test(m)) recoverFromChunkError()
})

