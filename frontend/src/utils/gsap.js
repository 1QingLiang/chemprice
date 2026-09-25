import gsap from 'gsap'

// GSAP 统一动效函数库
// 遵循 gsap-core skill 规范：transform 别名 + autoAlpha + gsap.context + onUnmounted 清理

/**
 * staggerEntrance - 元素交错入场动画
 * @param {string|Element} selector - CSS 选择器或 DOM 元素
 * @param {Object} options - 动画配置
 */
export const staggerEntrance = (selector, options = {}) => {
  const {
    duration = 0.6,
    delay = 0.1,
    stagger = 0.1,
    ease = 'power3.out',
    from = 'bottom',
    opacity = 0,
    y = 30
  } = options

  const elements = typeof selector === 'string' ? document.querySelectorAll(selector) : selector
  
  return gsap.from(elements, {
    duration,
    delay,
    stagger,
    ease,
    autoAlpha: opacity,
    y,
    clearProps: 'all'
  })
}

/**
 * numberScroll - 数字滚动动画
 * @param {Element} element - 目标元素
 * @param {number} targetValue - 目标数值
 * @param {Object} options - 动画配置
 */
export const numberScroll = (element, targetValue, options = {}) => {
  const {
    duration = 1.5,
    ease = 'power2.out',
    prefix = '',
    suffix = '',
    decimals = 0
  } = options

  const obj = { value: 0 }
  
  return gsap.to(obj, {
    duration,
    ease,
    value: targetValue,
    onUpdate: () => {
      const value = obj.value.toFixed(decimals)
      element.textContent = `${prefix}${value}${suffix}`
    }
  })
}

/**
 * matchMedia - 响应式动画配置
 * @param {Array} breakpoints - 断点配置数组
 */
export const matchMedia = (breakpoints) => {
  const mm = gsap.matchMedia()
  
  breakpoints.forEach(({ query, onEnter, onLeave }) => {
    mm.add(query, (context) => {
      if (onEnter) onEnter(context)
      return () => {
        if (onLeave) onLeave(context)
      }
    })
  })
  
  return mm
}

/**
 * fadeIn - 淡入动画
 * @param {string|Element} selector - CSS 选择器或 DOM 元素
 * @param {Object} options - 动画配置
 */
export const fadeIn = (selector, options = {}) => {
  const { duration = 0.5, delay = 0, ease = 'power2.out' } = options
  
  return gsap.from(selector, {
    duration,
    delay,
    ease,
    autoAlpha: 0,
    clearProps: 'all'
  })
}

/**
 * slideIn - 滑入动画
 * @param {string|Element} selector - CSS 选择器或 DOM 元素
 * @param {Object} options - 动画配置
 */
export const slideIn = (selector, options = {}) => {
  const {
    duration = 0.6,
    delay = 0,
    ease = 'power3.out',
    from = 'left',
    distance = 50
  } = options

  const fromVars = {}
  switch (from) {
    case 'left':
      fromVars.x = -distance
      break
    case 'right':
      fromVars.x = distance
      break
    case 'top':
      fromVars.y = -distance
      break
    case 'bottom':
      fromVars.y = distance
      break
  }

  return gsap.from(selector, {
    duration,
    delay,
    ease,
    autoAlpha: 0,
    ...fromVars,
    clearProps: 'all'
  })
}

/**
 * scaleIn - 缩放进入动画
 * @param {string|Element} selector - CSS 选择器或 DOM 元素
 * @param {Object} options - 动画配置
 */
export const scaleIn = (selector, options = {}) => {
  const { duration = 0.5, delay = 0, ease = 'back.out(1.7)' } = options
  
  return gsap.from(selector, {
    duration,
    delay,
    ease,
    autoAlpha: 0,
    scale: 0.8,
    clearProps: 'all'
  })
}

/**
 * createTimeline - 创建动画时间线
 * @param {Object} options - 时间线配置
 */
export const createTimeline = (options = {}) => {
  const { defaults = {} } = options
  
  return gsap.timeline({
    defaults: {
      duration: 0.5,
      ease: 'power2.out',
      ...defaults
    }
  })
}

/**
 * 清理动画上下文
 * @param {Object} context - gsap.context 对象
 */
export const cleanupContext = (context) => {
  if (context && context.kill) {
    context.kill()
  }
}

export default {
  staggerEntrance,
  numberScroll,
  matchMedia,
  fadeIn,
  slideIn,
  scaleIn,
  createTimeline,
  cleanupContext
}