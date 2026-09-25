import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'

/**
 * 让 fixed 定位的悬浮控件「可拖动 + 松手后悬挂（吸附到最近的左/右边缘）」。
 * 位置写入 localStorage，刷新后保持。
 *
 * 用法：
 *   const fabRef = ref(null)
 *   const fab = useDragFab({ elRef: fabRef, key: 'ai', defaultBottom: 150,
 *                            onTap: () => { open.value = true } })
 *   <button ref="fabRef" :style="fab.style" :class="{ 'fab-dragging': fab.dragging }"
 *           @pointerdown="fab.onPointerDown">…</button>
 *
 * 说明：
 * - 拖动距离小于 tapThreshold 视为「点击」，会调用 onTap；否则只移动、不触发点击。
 * - 移动端需要 CSS `touch-action: none`，否则 pointermove 会被滚动手势吞掉。
 */
export function useDragFab({
  elRef,
  key = 'default',
  defaultBottom = 20,
  defaultRight = 18,
  edgeGap = 12,
  tapThreshold = 6,
  onTap = null,
} = {}) {
  const x = ref(null)
  const y = ref(null)
  const dragging = ref(false)

  let el = null
  let startX = 0
  let startY = 0
  let originX = 0
  let originY = 0

  const LS_KEY = 'chemprice.fab.' + key

  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi)

  function loadSaved() {
    try {
      const s = JSON.parse(localStorage.getItem(LS_KEY) || 'null')
      if (s && typeof s.x === 'number' && typeof s.y === 'number') return s
    } catch (_) { /* ignore */ }
    return null
  }

  function save() {
    try {
      localStorage.setItem(LS_KEY, JSON.stringify({ x: x.value, y: y.value }))
    } catch (_) { /* ignore */ }
  }

  /** 首次挂载 / 元素重新出现时定位 */
  function place() {
    const e = elRef && elRef.value
    if (!e) return
    el = e
    const r = e.getBoundingClientRect()
    const vw = window.innerWidth
    const vh = window.innerHeight
    const saved = loadSaved()
    if (saved) {
      x.value = clamp(saved.x, edgeGap, vw - r.width - edgeGap)
      y.value = clamp(saved.y, edgeGap, vh - r.height - edgeGap)
    } else {
      x.value = vw - r.width - defaultRight
      y.value = vh - r.height - defaultBottom
    }
  }

  function onPointerDown(e) {
    if (e.button != null && e.button !== 0) return   // 仅左键 / 触摸
    el = e.currentTarget
    const r = el.getBoundingClientRect()
    startX = e.clientX
    startY = e.clientY
    originX = r.left
    originY = r.top
    dragging.value = false
    try { el.setPointerCapture(e.pointerId) } catch (_) { /* ignore */ }
    window.addEventListener('pointermove', onPointerMove)
    window.addEventListener('pointerup', onPointerUp)
    window.addEventListener('pointercancel', onPointerUp)
  }

  function onPointerMove(e) {
    const dx = e.clientX - startX
    const dy = e.clientY - startY
    if (!dragging.value) {
      // 超过阈值才算拖动，避免轻微抖动把"点击"吃掉
      if (Math.abs(dx) < tapThreshold && Math.abs(dy) < tapThreshold) return
      dragging.value = true
      if (el) el.style.transition = 'none'
    }
    const r = el.getBoundingClientRect()
    x.value = clamp(originX + dx, 0, window.innerWidth - r.width)
    y.value = clamp(originY + dy, 0, window.innerHeight - r.height)
  }

  function onPointerUp() {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)

    if (!dragging.value) {
      if (typeof onTap === 'function') onTap()
      return
    }

    // —— 悬挂：吸附到最近的左/右边缘 ——
    const r = el.getBoundingClientRect()
    const vw = window.innerWidth
    const vh = window.innerHeight
    const snapLeft = x.value + r.width / 2 < vw / 2
    if (el) el.style.transition = ''
    x.value = snapLeft ? edgeGap : vw - r.width - edgeGap
    y.value = clamp(y.value, edgeGap, vh - r.height - edgeGap)
    save()
    // 让本次拖动的收尾 click 不再触发
    setTimeout(() => { dragging.value = false }, 0)
  }

  function onResize() {
    if (x.value == null || !elRef || !elRef.value) return
    const r = elRef.value.getBoundingClientRect()
    x.value = clamp(x.value, edgeGap, window.innerWidth - r.width - edgeGap)
    y.value = clamp(y.value, edgeGap, window.innerHeight - r.height - edgeGap)
    save()
  }

  onMounted(() => {
    // 元素尺寸要等渲染完成才能量
    requestAnimationFrame(place)
    window.addEventListener('resize', onResize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', onResize)
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onPointerUp)
  })

  // 元素因 v-if 被重建时（如 AI 面板开关）重新定位
  if (elRef) watch(elRef, () => requestAnimationFrame(place))

  const style = computed(() => {
    if (x.value == null) return { visibility: 'hidden' }   // 定位前先藏起来，避免闪一下
    return { left: x.value + 'px', top: y.value + 'px', right: 'auto', bottom: 'auto' }
  })

  // ⚠️ 必须用 reactive 包装后再返回：
  // 模板里 `fab.style` / `fab.dragging` 属于「嵌套在对象里的 ref」，
  // Vue 只会自动解包顶层 ref，嵌套 ref 不解包 —— 直接用 raw 对象会导致
  // :style 收到一个 ComputedRef（样式完全不生效、元素停在 CSS 默认位置）。
  return reactive({ style, dragging, onPointerDown })
}
