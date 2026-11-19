/**
 * useTilt —— 容器内一批元素各自跟随指针做 3D 倾斜(明信片翻转手感)。
 *
 * 用法：`useTilt(containerRef, { item: '.nz-tile', maxDeg: 6 })`
 *   · 只处理容器内匹配 `item` 的元素；不传 item 就作用在容器本身。
 *   · gsap 的 transformPerspective 给每张卡独立透视原点，比父层加 perspective
 *     更稳（避免边缘卡片被中心透视拽偏）。
 *   · prefers-reduced-motion / 触屏（无 hover 语义）自动跳过。
 *   · onBeforeUnmount 里移除监听 + killTweensOf + clearProps，不留残留。
 *
 * 注意：目标元素自身不要有 CSS `transform`。本项目的 4 张 tile 只走 box-shadow /
 *       overlay 的过渡，transform 完全空出来给 gsap。
 */
import { onBeforeUnmount, onMounted } from 'vue'
import { gsap } from 'gsap'

export function useTilt(container, opts = {}) {
  const {
    item = null,
    maxDeg = 7,
    perspective = 900,
    followDuration = 0.45,
    releaseDuration = 0.7,
  } = opts

  const handlers = [] // { el, move, leave }

  function canRun() {
    if (typeof window === 'undefined') return false
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
    return window.matchMedia('(hover: hover) and (pointer: fine)').matches
  }

  function attach(el) {
    if (!el || handlers.some((h) => h.el === el)) return
    if (!canRun()) return

    // 每张卡独立透视 + 保留子层 3D 空间
    gsap.set(el, {
      transformPerspective: perspective,
      transformStyle: 'preserve-3d',
      willChange: 'transform',
    })

    const qrx = gsap.quickTo(el, 'rotationX', { duration: followDuration, ease: 'power2.out' })
    const qry = gsap.quickTo(el, 'rotationY', { duration: followDuration, ease: 'power2.out' })

    const move = (e) => {
      // 每次 pointermove 只读一次 rect（事件驱动、不是 rAF 循环），可接受
      const r = el.getBoundingClientRect()
      const px = (e.clientX - (r.left + r.width / 2)) / (r.width / 2)
      const py = (e.clientY - (r.top + r.height / 2)) / (r.height / 2)
      qry.set(Math.max(-1, Math.min(1, px)) * maxDeg)
      qrx.set(-Math.max(-1, Math.min(1, py)) * maxDeg)
    }

    const leave = () => {
      // overwrite:'auto' 会杀掉 quickTo 内部针对同一 prop 的 tween，让归位动画接管
      gsap.to(el, {
        rotationX: 0,
        rotationY: 0,
        duration: releaseDuration,
        ease: 'power3.out',
        overwrite: 'auto',
      })
    }

    el.addEventListener('pointermove', move)
    el.addEventListener('pointerleave', leave)
    handlers.push({ el, move, leave })
  }

  function cleanup() {
    for (const { el, move, leave } of handlers) {
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerleave', leave)
      gsap.killTweensOf(el)
      gsap.set(el, { clearProps: 'transform,transformStyle,willChange' })
    }
    handlers.length = 0
  }

  onMounted(() => {
    const root = container.value
    if (!root) return
    if (item) root.querySelectorAll(item).forEach(attach)
    else attach(root)
  })

  onBeforeUnmount(cleanup)

  return cleanup
}

export default useTilt
