/**
 * 滚动揭示指令：给元素挂 v-reveal，进入视口时加 .is-in。
 * 配合 base.css 里的 .reveal / .reveal-rise 使用；值可传毫秒做错峰延迟，
 * 也可在元素上写 style="--d:120ms"。
 */
let io = null

function observer() {
  if (io) return io
  io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          e.target.classList.add('is-in')
          io.unobserve(e.target)
        }
      }
    },
    { threshold: 0.12, rootMargin: '0px 0px -8% 0px' }
  )
  return io
}

export const reveal = {
  mounted(el, binding) {
    if (typeof binding.value === 'number') {
      el.style.setProperty('--d', `${binding.value}ms`)
    }
    // 已在视口内的首屏元素：下一帧直接显影，避免闪一下
    observer().observe(el)
  },
  unmounted(el) {
    if (io) io.unobserve(el)
  }
}

export default reveal
