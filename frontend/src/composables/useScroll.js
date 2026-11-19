/**
 * useScroll.js —— 单 rAF 滚动调度器 + 每帧布局读缓存
 *
 * 移植自参考站 scripts/inline.html.js 第 59-126 行（注释原文转述）：
 *   「每个滚动模块原先各自持有一个 scroll 监听和一个 rAF 闸门：18 个监听、约 14 个
 *     rAF 回调、每帧约 18 次布局读取。每个模块先读 rect 再写样式，而写样式会让布局变脏，
 *    于是下一个模块的读就强制一次同步重排 —— 3000 个元素的文档上每帧约 8 次强制重排，
 *    这才是滚动发沉的原因（卡片模糊、区块滤镜、柳树摇摆相比之下几乎免费）。
 *    现在只有 1 个监听、1 个 rAF。」
 *
 * 用法：
 *   import { Scroll, useScrollFrame } from '@/composables/useScroll'
 *
 *   // ① 组件内：自动在卸载时退订
 *   useScrollFrame(() => {
 *     const t = Scroll.rect(el);            // ← 只能这样读布局
 *     el.style.setProperty('--reveal', calc(t.top))  // 写放在最后
 *   })
 *
 *   // ② 任意模块：手动订阅 / 退订
 *   const off = Scroll.add(fn); off()
 *
 * 铁律（抄这条就不会踩回原坑）：
 *   · 在 Scroll 订阅回调里**不要**碰 getBoundingClientRect / offsetTop / innerHeight /
 *     getComputedStyle —— 一律用 Scroll.rect(el) / Scroll.y() / Scroll.vh() / Scroll.vw()。
 *   · 一个回调里如果既读又写，请先读完全部元素再写（原站注释：6 个元素混读混写
 *     就占了全页每帧强制重排的 75%）。
 *   · 布局变了（图片加载、字体落版、折叠面板展开）之后要 Scroll.kick() 补一帧。
 */

/* ════════════════════════════════════════════════════════════════════
   调度器本体（模块级单例，全应用只有这一份）
   ════════════════════════════════════════════════════════════════════ */

/** 写阶段订阅者（读值已在帧首备好，这里可以放心写样式） */
const subs = []
/** 读阶段订阅者：依赖「干净的树」的布局查询（命中测试等）放这里 */
const reads = []

let queued = false
let rafId = 0
/** 本帧的 rect 缓存：el -> DOMRect */
const cache = new Map()
/** 本帧被请求过的元素（= 下一帧要预取的元素集合） */
const want = new Set()
let prev = new Set()

let _y = 0
let _vh = 0
let _vw = 0

function measureViewport() {
  _y = window.scrollY || window.pageYOffset || 0
  _vh = window.innerHeight
  _vw = window.innerWidth
}

function frame() {
  queued = false
  rafId = 0

  /* ── READ 阶段：任何订阅者都还没跑过，树是干净的 ── */
  measureViewport()
  cache.clear()
  prev.forEach((el) => {
    if (el.isConnected) cache.set(el, el.getBoundingClientRect())
  })
  want.clear()

  /* 读阶段订阅者：必须跑在写之前（例如 nav 对比度的命中测试） */
  for (let i = 0; i < reads.length; i++) {
    try {
      reads[i]()
    } catch (e) {
      /* 一个模块坏掉不能拖停其余模块（原站同款处理） */
    }
  }

  /* ── WRITE 阶段：所有布局读已经是缓存命中，写不会再触发强制重排 ── */
  for (let i = 0; i < subs.length; i++) {
    try {
      subs[i]()
    } catch (e) {
      /* 同上 */
    }
  }

  prev = want
}

function schedule() {
  if (queued) return
  queued = true
  rafId = requestAnimationFrame(frame)
}

function onResize() {
  /* 尺寸变了 → 缓存整体失效，下一帧全量重测 */
  prev = new Set()
  schedule()
}

function listen() {
  window.addEventListener('scroll', schedule, { passive: true })
  window.addEventListener('resize', onResize, { passive: true })
  window.addEventListener('load', schedule)
  // 视图切回来时补一帧（路由返回、标签页切回都会让布局悄悄变化）
  document.addEventListener('visibilitychange', schedule)
}

if (typeof window !== 'undefined') listen()

/* ════════════════════════════════════════════════════════════════════
   共享 Scroll 对象 —— 所有滚动驱动模块的唯一入口
   ════════════════════════════════════════════════════════════════════ */

export const Scroll = {
  /**
   * 订阅「写阶段」。返回退订函数。
   * @param {() => void} fn
   * @returns {() => void}
   */
  add(fn) {
    subs.push(fn)
    schedule()
    return () => {
      const i = subs.indexOf(fn)
      if (i >= 0) subs.splice(i, 1)
    }
  },

  /**
   * 订阅「读阶段」：布局查询必须跑在干净树上时用它（例如 elementsFromPoint 命中测试）。
   * @param {() => void} fn
   * @returns {() => void}
   */
  addRead(fn) {
    reads.push(fn)
    schedule()
    return () => {
      const i = reads.indexOf(fn)
      if (i >= 0) reads.splice(i, 1)
    }
  },

  /**
   * 缓存版 getBoundingClientRect：命中返回帧首快照；未命中则现场读一次并加入预取表，
   * 从下一帧起就是命中。**回调里禁止直接调用 getBoundingClientRect**。
   * @param {Element} el
   * @returns {DOMRect}
   */
  rect(el) {
    let r = cache.get(el)
    if (!r) {
      r = el.getBoundingClientRect()
      cache.set(el, r)
    }
    want.add(el)
    return r
  },

  /** 本帧起始时的 scrollY（不触发同步布局） */
  y() {
    return _y
  },

  /** 本帧起始时的视口高 */
  vh() {
    return _vh || window.innerHeight
  },

  /** 本帧起始时的视口宽 */
  vw() {
    return _vw || window.innerWidth
  },

  /** 强制补一帧（布局变化后调用：图片加载完、字体落版、面板展开） */
  kick: schedule,

  /** 立即同步跑一次帧（首帧初始化用，避免闪一帧旧值） */
  flush() {
    if (typeof window === 'undefined') return
    if (rafId) cancelAnimationFrame(rafId)
    frame()
  }
}

/* ════════════════════════════════════════════════════════════════════
   Vue 组合式封装
   ════════════════════════════════════════════════════════════════════ */

import { onBeforeUnmount, watch, isRef } from 'vue'

/**
 * 在组件的生命周期内订阅滚动写阶段；卸载时自动退订。
 *
 * @param {() => void} cb 回调。内部只能通过 Scroll.* 读布局。
 * @param {object} [opts]
 * @param {boolean} [opts.immediate=true] 是否在挂载时立刻跑一次（默认跑，避免首帧不动）
 * @param {boolean} [opts.read=true] true → 写阶段（Scroll.add）；false → 读阶段（Scroll.addRead）
 * @returns {() => void} 退订函数（一般不用手动调）
 *
 * @example
 * useScrollFrame(() => {
 *   const vh = Scroll.vh()
 *   const top = Scroll.rect(heroEl.value).top
 *   progress.value = Math.min(1, Math.max(0, (vh - top) / (vh * 0.5)))
 * })
 */
export function useScrollFrame(cb, opts = {}) {
  const { immediate = true, read = false } = opts
  let off = null

  const wrapped = () => {
    if (typeof cb === 'function') cb()
  }

  const start = () => {
    if (off) off()
    off = read ? Scroll.addRead(wrapped) : Scroll.add(wrapped)
  }

  /* 组件挂载即订阅；SSR / 无 window 时静默跳过 */
  if (typeof window !== 'undefined') {
    start()
    if (immediate) {
      try {
        wrapped()
      } catch (e) {
        /* 首次同步执行失败不影响后续帧 */
      }
    }
  }

  onBeforeUnmount(() => {
    if (off) {
      off()
      off = null
    }
  })

  return () => {
    if (off) {
      off()
      off = null
    }
  }
}

/** 默认导出：方便 `import Scroll from '@/composables/useScroll'` 直接取调度器 */
export default Scroll