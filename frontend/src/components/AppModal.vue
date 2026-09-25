<template>
  <Teleport to="body">
    <Transition name="am">
      <div
        v-if="modelValue"
        class="am-mask"
        :class="{ 'am-blur': blur }"
        :style="{ zIndex: myZ }"
        @click.self="onMaskClick"
      >
        <!-- bare 模式：只提供遮罩与弹层行为，卡片外观由调用方自备（迁移零样式风险） -->
        <slot v-if="bare" />

        <div
          v-else
          class="am-box"
          :class="['am-' + size]"
          :style="boxStyle"
          role="dialog"
          aria-modal="true"
        >
          <!-- 头部：有标题/副标题或自定义 header 时显示 -->
          <div v-if="title || subtitle || $slots.header" class="am-h">
            <div class="am-h-t">
              <b v-if="title">{{ title }}</b>
              <span v-if="subtitle" class="am-sub">{{ subtitle }}</span>
            </div>
            <slot name="header" />
            <button v-if="showClose" class="am-x" title="关闭（Esc）" @click="close">×</button>
          </div>

          <!-- 内容 -->
          <div class="am-body" :class="{ 'am-nopad': !padded }">
            <slot />
          </div>

          <!-- 底部 -->
          <div v-if="$slots.footer" class="am-f">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script>
/**
 * ⛔⛔ 这些共享状态必须写在【普通 <script> 块】里，不能写进 <script setup>。
 *
 * 原因：`<script setup>` 顶层声明的变量会被编译进 `setup()` 函数体 → **每个组件实例各一份**，
 * 于是「弹层栈 / z-index 序列 / 滚动锁引用计数」全部变成实例私有 ——
 * 结果就是多个弹层 z-index 相同、后开的压不住先开的、滚动锁被别的弹层提前解锁
 * （实测：详情弹层 z=2102、其内层数据权限弹层 z=2101，即内层反而更靠下）。
 *
 * 普通 `<script>` 块与 `<script setup>` 会被合并到同一模块作用域，但**只在模块加载时执行一次**，
 * 因此这里的变量是全应用共享的。两个块的变量可以互相直接访问。
 */
const STACK = []          // 打开顺序：元素为实例 uid
let zSeq = 2100           // 起点高于 TMap 覆盖层(1000) 与 Element Plus 起点(2000)
let idSeq = 0
let lockCount = 0         // body 滚动锁引用计数
let savedOverflow = null  // 首次加锁前的 body.overflow

function nextId() { idSeq += 1; return idSeq }
function nextZ() { zSeq += 1; return zSeq }

function lockBody() {
  if (lockCount === 0) {
    savedOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  lockCount += 1
}
function unlockBody() {
  lockCount = Math.max(0, lockCount - 1)
  if (lockCount === 0) document.body.style.overflow = savedOverflow || ''
}
</script>

<script setup>
/**
 * ⭐ 统一弹层组件（全站弹窗基底）
 *
 * 解决的历史问题：项目里原有 **4 套** 各写各的弹层实现，能力与层级都不一致 ——
 *   · `el-dialog`（Element Plus，9 个文件）      z-index 2000+（自动递增）
 *   · `UserManage .modal-mask`                  z-index 1000  ← 与腾讯地图覆盖层同值
 *   · `Scheduler  .mask`                        z-index 900
 *   · `OpenApi    .op-mask`                     z-index 60    ← 会被页面内容盖住
 * 且自定义那三套都**没有 `body` 滚动锁**、多数**没有 Esc 关闭**、多数**没有 Teleport**
 * （受父级 overflow / transform / z-index 影响）。
 *
 * 本组件一次性提供：
 *   ① `Teleport to="body"` —— 脱离父级层叠上下文，避免被页面卡片/地图盖住
 *   ② **Esc 关闭**（只关最上层）+ **遮罩点击关闭**（可分别关闭）
 *   ③ **body 滚动锁**（全局引用计数，嵌套多弹层不会提前解锁）
 *   ④ **全局弹层栈管理 z-index**（后开的必然在上）
 *   ⑤ 尺寸档位 + 自定义宽/高、header 默认样式与自定义 slot、footer slot
 *   ⑥ `bare` 模式 —— 只给遮罩与行为，卡片外观由调用方自备（老弹层迁移零样式风险）
 *
 * ⛔ 三个已踩过的坑，改动前务必先读：
 *   1. 共享状态必须放【普通 <script> 块】（见上方注释）——放 <script setup> 会退化成实例私有。
 *   2. z-index 必须存 `ref`；若用 `computed(() => 普通变量)`，computed 依赖不到它，会永久缓存同值。
 *   3. 滚动锁必须用「本实例是否持有锁」的 `bound` 布尔量保护；否则 `watch(immediate)`
 *      在弹层以 `false` 初始挂载时会**减掉别的弹层持有的锁**，导致外层提前解锁。
 */
import { computed, ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  /** sm=420 / md=560 / lg=760 / xl=920 / full=94vw */
  size: { type: String, default: 'md' },
  /** 覆盖 size 的宽度，如 "880px" 或 880 */
  width: { type: [String, Number], default: '' },
  maxHeight: { type: String, default: '86vh' },
  /** 遮罩点击是否关闭 */
  closeOnClickModal: { type: Boolean, default: true },
  /** Esc 是否关闭 */
  closeOnEsc: { type: Boolean, default: true },
  /** 是否显示右上角 × */
  showClose: { type: Boolean, default: true },
  /** 是否锁定 body 滚动 */
  lockScroll: { type: Boolean, default: true },
  /** 遮罩是否加毛玻璃（默认不加，与多数页面观感一致） */
  blur: { type: Boolean, default: false },
  /** 内容区是否加内边距（日志、表格类可关掉自行控制） */
  padded: { type: Boolean, default: true },
  /**
   * bare 模式：只渲染「遮罩 + 弹层行为」，不渲染自带卡片/头部/底部。
   * 供样式已自成体系的老弹层平滑迁移用 —— 保留其原有 `.xxx-modal` 卡片与全部 scoped CSS，
   * 但同样获得 Teleport / Esc / 滚动锁 / 统一 z-index。
   */
  bare: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'close', 'open'])

const uid = nextId()
const myZ = ref(0)        // ⭐ 必须是 ref
const bound = ref(false)  // ⭐ 本实例当前是否持有锁/监听

function isTop() {
  return STACK.length > 0 && STACK[STACK.length - 1] === uid
}

/* ================= Esc ================= */
function onKeydown(e) {
  if (e.key !== 'Escape') return
  if (!props.closeOnEsc) return
  if (!isTop()) return              // 只让最上层响应，避免一次 Esc 关掉整叠弹层
  e.stopPropagation()
  close()
}

/* ================= 开 / 关 ================= */
function open() {
  if (bound.value) return
  bound.value = true
  myZ.value = nextZ()
  STACK.push(uid)
  if (props.lockScroll) lockBody()
  window.addEventListener('keydown', onKeydown, true)
  emit('open')
}

function shut() {
  if (!bound.value) return
  bound.value = false
  window.removeEventListener('keydown', onKeydown, true)
  if (props.lockScroll) unlockBody()
  const i = STACK.indexOf(uid)
  if (i >= 0) STACK.splice(i, 1)
}

// ⭐ immediate 时若 modelValue 为 false，open/shut 都是空操作（靠 bound 保护），不会误减别人的锁
watch(() => props.modelValue, (v) => (v ? open() : shut()), { immediate: true })

function close() {
  emit('update:modelValue', false)
  emit('close')
}
function onMaskClick() {
  if (props.closeOnClickModal) close()
}

onBeforeUnmount(() => { shut() })

/* ================= 宽度 ================= */
const boxStyle = computed(() => {
  const st = { maxHeight: props.maxHeight }
  if (props.width) {
    st.width = typeof props.width === 'number' ? props.width + 'px' : props.width
  }
  return st
})
</script>

<style scoped>
.am-mask {
  position: fixed; inset: 0;
  background: rgba(17, 24, 39, .45);
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
  overflow: hidden;
}
.am-blur { backdrop-filter: blur(4px); -webkit-backdrop-filter: blur(4px); }

.am-box {
  width: min(560px, 100%);
  background: #fff;
  border-radius: 14px;
  display: flex; flex-direction: column;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(17, 24, 39, .28);
}
.am-sm   { width: min(420px, 100%); }
.am-md   { width: min(560px, 100%); }
.am-lg   { width: min(760px, 100%); }
.am-xl   { width: min(920px, 100%); }
.am-full { width: 94vw; }

.am-h {
  display: flex; align-items: flex-start; justify-content: space-between;
  gap: 12px; padding: 14px 18px; border-bottom: 1px solid #f3f4f6;
  flex: none;
}
.am-h-t { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.am-h-t b { font-size: 14px; font-weight: 600; color: #111827; }
.am-sub {
  font-size: 10.5px; color: #9ca3af;
  word-break: break-all; line-height: 1.5;
}
.am-x {
  border: none; background: none; font-size: 22px; line-height: 1;
  color: #9ca3af; cursor: pointer; padding: 0 4px; flex: none;
  font-family: inherit;
}
.am-x:hover { color: #111827; }

.am-body {
  flex: 1; min-height: 0; overflow: auto;
  padding: 16px 18px;
}
.am-nopad { padding: 0; }

.am-f {
  display: flex; align-items: center; justify-content: space-between;
  gap: 10px; padding: 10px 18px; border-top: 1px solid #f3f4f6;
  flex: none; flex-wrap: wrap;
}

/* 过渡：遮罩淡入 + 卡片轻微上浮 */
.am-enter-active, .am-leave-active { transition: opacity .18s ease; }
.am-enter-active .am-box, .am-leave-active .am-box { transition: transform .18s ease, opacity .18s ease; }
.am-enter-from, .am-leave-to { opacity: 0; }
.am-enter-from .am-box, .am-leave-to .am-box { transform: translateY(10px) scale(.985); opacity: 0; }

@media (max-width: 640px) {
  .am-mask { padding: 10px; }
  .am-full { width: 100%; }
}
</style>
