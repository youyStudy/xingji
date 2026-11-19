/**
 * useMagnetic —— 磁性 CTA:指针在按钮附近时,按钮微微吸附到光标;离开弹回。
 *
 * 用途:放在最显眼的 CTA(如 SkyHero 播放圆钮)上,给一下"这玩意儿有实体"的触感,
 *      比单纯 hover:scale 更活。
 *
 * 铁律:
 *   · prefers-reduced-motion 直接不启用,按钮保持原生样式。
 *   · 只在 pointer: fine(桌面/触控板)环境跑;触屏没有 hover,不劫持点击手感。
 *   · 用 gsap.quickTo 做跟手 + 弹性归位,避免每次 move 都新建 tween 的开销。
 *   · 卸载时移除监听并 killTweensOf,不留残留。
 */
import { onBeforeUnmount, onMounted, isRef, unref } from 'vue'
import { gsap } from 'gsap'

export function useMagnetic(target, opts = {}) {
  const {
    strength = 0.35,     // 横向吸附系数
    verticalStrength = 0.55, // 纵向吸附系数(视觉重心一般偏下,给一点更强跟随)
    radius = 90,         // 影响半径(px);超出即弹回
  } = opts

  let el = null
  let q = null
  let attached = false

  const canRun = () => {
    if (typeof window === 'undefined') return false
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false
    return window.matchMedia('(hover: hover) and (pointer: fine)').matches
  }

  function attach(node) {
    detach()
    if (!node) return
    el = node
    if (!canRun()) return

    q = {
      x: gsap.quickTo(el, 'x', { duration: 0.5, ease: 'power3.out' }),
      y: gsap.quickTo(el, 'y', { duration: 0.5, ease: 'power3.out' }),
    }

    el.addEventListener('pointermove', onMove)
    el.addEventListener('pointerleave', onLeave)
    attached = true
  }

  function onMove(e) {
    if (!el) return
    const r = el.getBoundingClientRect()
    const dx = e.clientX - (r.left + r.width / 2)
    const dy = e.clientY - (r.top + r.height / 2)
    // 超出影响半径即弹回,避免鼠标在按钮"边缘但不在上面"时还拽着
    if (Math.abs(dx) > radius || Math.abs(dy) > radius) {
      onLeave()
      return
    }
    q.x.set(dx * strength)
    q.y.set(dy * verticalStrength)
  }

  function onLeave() {
    if (!q) return
    q.x.set(0)
    q.y.set(0)
  }

  function detach() {
    if (el && attached) {
      el.removeEventListener('pointermove', onMove)
      el.removeEventListener('pointerleave', onLeave)
      gsap.killTweensOf(el)
    }
    attached = false
    q = null
    el = null
  }

  onMounted(() => {
    const node = isRef(target) ? target.value : unref(target)
    attach(node)
  })

  onBeforeUnmount(detach)

  return detach
}

export default useMagnetic
