/**
 * useLenis —— 首页的平滑滚动。
 *
 * 只在 LandingImmersive 挂载：功能页是多栏固定布局，全局 Lenis 会把 wheel
 * 拦去滚整页，面板内部（会话、每日规划、行程面板）就滚不动了。
 * 组件内需要拿实例的话用 getLenis()，不暴露到 window。
 *
 * 铁律（对齐 lenis skill）：
 *   · prefers-reduced-motion 直接不启用，保持原生滚动。
 *   · 不劫持触屏滚动（Lenis 默认只管 wheel，触屏保持原生）——这条不用额外配。
 *   · autoRaf:true 由 Lenis 自己管 rAF，避免与项目 useScroll 单 rAF 调度器打架
 *     （useScroll 依然通过原生 scroll 事件工作，Lenis 每次 rAF 会驱动 window 滚动，
 *     事件照常发出，读写阶段分离的收益不丢）。
 *   · 卸载时 destroy()，不留监听器。
 */
import { onBeforeUnmount, onMounted, shallowRef } from 'vue'
import Lenis from 'lenis'

// 模块级单例句柄：给 router 的 scrollBehavior 平滑回顶用。
// 这是全站唯一的滚动引擎，取用而非到处传，是这类"全局输入基础设施"的常规做法；
// 不挂到 window，避免污染全局命名空间。
let current = null
export function getLenis() {
  return current
}

export function useLenis(options = {}) {
  const instance = shallowRef(null)

  onMounted(() => {
    if (typeof window === 'undefined') return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    instance.value = new Lenis({
      // 与项目现有 ease-out-cubic 曲线基调一致，避免"黏"过久
      duration: 1.05,
      easing: (t) => Math.min(1, 1.001 - Math.pow(2, -10 * t)),
      smoothWheel: true,
      autoRaf: true,
      anchors: false,
      wheelMultiplier: 1,
      touchMultiplier: 1.2,
      ...options,
    })
    current = instance.value
  })

  onBeforeUnmount(() => {
    instance.value?.destroy()
    instance.value = null
    current = null
  })

  return instance
}

export default useLenis
