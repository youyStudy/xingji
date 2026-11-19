<script setup>
/**
 * 行迹 · 页脚（参考站 .footer node 128:4842 的移植版，1440×938 画布）
 * ─ 顶部波浪草边（mask 波浪，自己画的 1440×198 路径）
 * ─ 底部自绘山丘 + 泥土层
 * ─ 中央文字组：副标题 / 「一起出发吧」（随滚动生长）/ 开始规划 CTA
 * ─ 程序化花园：~14 株自绘 SVG 植物，点击 = 浇水生长 + bloom 脉冲
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { gsap } from 'gsap'
import { Scroll, useScrollFrame } from '../../composables/useScroll'
import { useUiStore } from '../../stores/ui'

const ui = useUiStore()

/* ════ 顶部波浪草边（自绘；1440×198，只取 alpha，颜色来自 --surface-raised） ════
   波浪规则：三个不同周期的正弦叠加，让周期看起来是自然随机的，
   而不是一条重复的「波浪尺」。path 在这里直接字符串拼出来，避免转义出错。 */
function buildCrestPath() {
  const W = 1440
  const H = 198
  const N = 24
  let d = 'M0 ' + H.toFixed(2)
  for (let i = 0; i <= N; i++) {
    const t = i / N
    const x = t * W
    // 三道不同频率的起伏：低频肩背 + 中频浪头 + 高频草叶毛边
    const y =
      56 +
      Math.sin(t * Math.PI * 2 * 1.5 + 0.4) * 34 +
      Math.sin(t * Math.PI * 2 * 3.5 + 1.9) * 15 -
      Math.sin(t * Math.PI * 2 * 6.5 + 0.7) * 7
    d += ' L' + x.toFixed(2) + ' ' + Math.max(2, y).toFixed(2)
  }
  d += ' L' + W + ' ' + H + ' L0 ' + H + ' Z'
  return d
}
const CREST_PATH = buildCrestPath()
// 只编码会截断 CSS url() 的字符（# 已手写成 %23），保留数字、字母与逗号
function encodeSvg(svg) {
  return svg
    .replace(/%/g, '%25')
    .replace(/</g, '%3C')
    .replace(/>/g, '%3E')
    .replace(/#/g, '%23')
    .replace(/"/g, "'")
    .replace(/\s+/g, '%20')
}
// 波浪 SVG：填色随意（遮罩只看 alpha），颜色来自 --surface-raised
const CREST_SVG =
  "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1440 198' preserveAspectRatio='none'>" +
  "<path fill='currentColor' d='" + CREST_PATH + "'/></svg>"
const CREST_MASK = 'url("data:image/svg+xml,' + encodeSvg(CREST_SVG) + '")'

/* ════ 山丘 / 泥土 —— 两层自绘路径（1440×221 的坐标），宽 100vw ════
   渐变用固定 id，由 CSS 按 body.night 切换色标（脚本里不依赖 body.night，
   避免主题切换时组件不重渲染导致颜色卡住）。 */
const HILL_GRAD_ID = 'nz-hill-grad'
const HILL_FRONT_GRAD_ID = 'nz-hill-front-grad'

// 远山轮廓：左低右高的一道长坡，带两个小丘
const HILL_BACK =
  'M0 118 C 120 104 208 66 316 60 C 424 54 470 82 560 96 ' +
  'C 650 110 706 74 796 68 C 886 62 948 92 1032 100 ' +
  'C 1116 108 1180 78 1268 62 C 1330 51 1390 58 1440 50 L1440 221 L0 221 Z'
// 前面的近坡：更低更平，压住山的根
const HILL_FRONT =
  'M0 156 C 150 140 240 158 372 150 C 504 142 590 118 700 126 ' +
  'C 810 134 880 162 1000 158 C 1120 154 1210 128 1320 132 ' +
  'C 1380 134 1416 142 1440 146 L1440 221 L0 221 Z'

/* ════ 花园根植高度 ════
   山丘 SVG 贴底、高 221px；植株根部落在前坡的顶棱（约 130px 处），
   再按层级往下沉 8 / 22 / 36px，让后排的草看起来长在更远的坡上。 */
const LAND_H = 221
const ROOTS = [130 + 8, 130 + 22, 130 + 36] // depth 0 / 1 / 2

/* ════ 文字生长：--ft-grow 0→1 ════
   footer 顶边从视口底部升到视口中部时线性 clamp（参考站 initFooterGrow）： */
const footerEl = ref(null)
const gardenEl = ref(null)
const grow = ref(0)
const playState = ref('running')
const reduce =
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

onMounted(() => {
  if (reduce) {
    grow.value = 1
    return
  }
  if (!footerEl.value) return
  // useScrollFrame 订阅任务 A 的共享单 rAF 调度器；Scroll.rect 走它每帧的布局读缓存
  useScrollFrame(updateGrow)
  // 首帧 + 窗口尺寸变化时手动补一次（调度器只在 scroll/resize 时自己 kick）
  updateGrow()
  window.addEventListener('resize', updateGrow, { passive: true })
  window.addEventListener('load', updateGrow)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateGrow)
  window.removeEventListener('load', updateGrow)
  bloomTimers.forEach((t) => clearTimeout(t))
  bloomFrames.forEach((id) => cancelAnimationFrame(id))
})

function updateGrow() {
  const el = footerEl.value
  if (!el || reduce) return
  // 注意：这里不能用 getBoundingClientRect / Scroll.rect——共享调度器里若有
  // 模块给祖先写了 transform，rect 会脱离滚动位置（实际踩到过：页面一能滚动，
  // 页脚被读成跑到屏幕上方）。用「文档坐标 − 滚动距离」自己算视口位置最稳。
  const footTop = el.getBoundingClientRect().top + (window.scrollY || 0)
  const vh = Scroll.vh()
  const rTop = footTop - (window.scrollY || 0)
  // start：footer 顶边在视口底部时 = 0；end：顶边升到视口中部时 = 1
  const start = vh
  const end = vh * 0.5
  let p = (start - rTop) / (start - end)
  p = p < 0 ? 0 : p > 1 ? 1 : p
  // 只写 4 位小数，避免每帧都触发一次样式失效
  const v = Math.round(p * 10000) / 10000
  if (v !== grow.value) grow.value = v
}

/* ════ 程序化花园 ════
   gouache 水粉调色（取自参考站插画的色相，色值自己配）：
   叶 #5b7a3a / 浅叶 #8aa45c / 花心 #d9a441 / 陶土 #b8522f / 薰衣草 #6b5b95 */
const LEAF = ['#5b7a3a', '#8aa45c', '#4b6832', '#6f8f4a']
const BLOOMC = ['#d9a441', '#b8522f', '#6b5b95', '#e7c56a', '#8aa45c']

// 固定种子伪随机：同一台机器每次刷新花园长得一样（也让人可以复现 bug）
function mulberry32(a) {
  return function () {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rand = mulberry32(20260427)
const rnd = (a, b) => a + rand() * (b - a)
const pick = (arr) => arr[(rand() * arr.length) | 0]
const fx = (n) => Math.round(n * 10) / 10

/* ── 三种植物：都从 (w/2, h) 的根部向上长，head 组留给 bloom 动画 ── */

// 1) 蒲公英：细长茎 + 叶子 + 一团白色绒球（种子伞是同心圆点）
function dandelion(kind) {
  const h = fx(rnd(190, 250))
  const w = 84
  const cx = w / 2
  const stemC = pick(LEAF)
  const leafC = pick(LEAF)
  const seeds = '#f6f1e2'
  const seedEdge = '#cfc6ae'
  const bend = rnd(-8, 8)
  const leaves =
    '<path d="M' + cx + ' ' + h + ' C ' + (cx - 8) + ' ' + fx(h * 0.86) + ' ' + (cx - 26) + ' ' + fx(h * 0.8) + ' ' + (cx - 34) + ' ' + fx(h * 0.68) + ' ' +
    'C ' + (cx - 20) + ' ' + fx(h * 0.82) + ' ' + (cx - 10) + ' ' + fx(h * 0.86) + ' ' + cx + ' ' + fx(h * 0.9) + ' Z" fill="' + leafC + '"/>' +
    '<path d="M' + cx + ' ' + h + ' C ' + (cx + 8) + ' ' + fx(h * 0.82) + ' ' + (cx + 24) + ' ' + fx(h * 0.78) + ' ' + (cx + 33) + ' ' + fx(h * 0.62) + ' ' +
    'C ' + (cx + 18) + ' ' + fx(h * 0.8) + ' ' + (cx + 9) + ' ' + fx(h * 0.84) + ' ' + cx + ' ' + fx(h * 0.9) + ' Z" fill="' + leafC + '"/>'
  const stem =
    '<path d="M' + cx + ' ' + h + ' C ' + fx(cx + bend) + ' ' + fx(h * 0.6) + ' ' + fx(cx - bend) + ' ' + fx(h * 0.36) + ' ' + cx + ' ' + fx(h * 0.2) + '" ' +
    'stroke="' + stemC + '" stroke-width="3.4" fill="none" stroke-linecap="round"/>'
  // 绒球：一圈伞梗 + 一圈绒点
  const cy = 26
  const rHead = rnd(19, 24)
  let head = ''
  for (let i = 0; i < 22; i++) {
    const a = (Math.PI * 2 * i) / 22 + rnd(-0.08, 0.08)
    head += '<line x1="' + cx + '" y1="' + cy + '" x2="' + fx(cx + Math.cos(a) * rHead) + '" y2="' + fx(cy + Math.sin(a) * rHead) + '" stroke="' + seedEdge + '" stroke-width="0.8" opacity="0.75"/>'
  }
  for (let i = 0; i < 46; i++) {
    const a = rnd(0, Math.PI * 2)
    const rr = rHead * Math.sqrt(rand())
    head += '<circle cx="' + fx(cx + Math.cos(a) * rr) + '" cy="' + fx(cy + Math.sin(a) * rr) + '" r="' + fx(rnd(1.6, 2.8)) + '" fill="' + seeds + '" opacity="' + fx(rnd(0.72, 1)) + '"/>'
  }
  // 花托：一小团暖灰，让绒球有实心感
  head = '<circle cx="' + cx + '" cy="' + cy + '" r="' + fx(rHead * 0.42) + '" fill="#e7dfc6"/>' +
         '<circle cx="' + cx + '" cy="' + cy + '" r="' + fx(rHead * 0.42) + '" fill="none" stroke="#cfc6ae" stroke-width="1"/>' + head
  return { kind, w, h, body: leaves + stem, head }
}

// 2) 薰衣草：绿茎 + 两片窄叶 + 紫色小花穗
function lavender(kind) {
  const h = fx(rnd(175, 235))
  const w = 58
  const cx = w / 2
  const leafC = pick(LEAF)
  const lav = ['#6b5b95', '#7d6aa8', '#584a7e', '#8f7db7']
  const leaves =
    '<path d="M' + cx + ' ' + h + ' Q ' + (cx - 16) + ' ' + fx(h * 0.76) + ' ' + (cx - 20) + ' ' + fx(h * 0.58) + ' Q ' + (cx - 6) + ' ' + fx(h * 0.74) + ' ' + cx + ' ' + fx(h * 0.88) + ' Z" fill="' + leafC + '"/>' +
    '<path d="M' + cx + ' ' + h + ' Q ' + (cx + 16) + ' ' + fx(h * 0.74) + ' ' + (cx + 19) + ' ' + fx(h * 0.54) + ' Q ' + (cx + 6) + ' ' + fx(h * 0.72) + ' ' + cx + ' ' + fx(h * 0.88) + ' Z" fill="' + leafC + '"/>'
  const stemTop = fx(h * 0.34)
  const stem = '<path d="M' + cx + ' ' + h + ' C ' + fx(cx + 4) + ' ' + fx(h * 0.66) + ' ' + fx(cx - 3) + ' ' + fx(h * 0.5) + ' ' + cx + ' ' + stemTop + '" stroke="' + leafC + '" stroke-width="3" fill="none" stroke-linecap="round"/>'
  // 花穗：从下往上收窄，一串错落的小圆
  const rows = 12
  let head = ''
  for (let i = 0; i <= rows; i++) {
    const t = i / rows
    const y = stemTop + (14 - stemTop) * t
    const spread = (1 - t) * 8.5 + 3
    for (let k = 0; k < 4; k++) {
      head += '<circle cx="' + fx(cx + rnd(-spread, spread)) + '" cy="' + fx(y + rnd(-2.5, 2.5)) + '" r="' + fx(rnd(3, 4.6)) + '" fill="' + pick(lav) + '" opacity="' + fx(rnd(0.82, 1)) + '"/>'
    }
  }
  return { kind, w, h, body: leaves + stem, head }
}

// 3) 草叶：一丛不同高度的弯叶片（无花，用来填缝和做层次）
function grass(kind) {
  const h = fx(rnd(105, 165))
  const w = 96
  const cx = w / 2
  let head = ''
  const n = 7
  for (let i = 0; i < n; i++) {
    const t = (i - (n - 1) / 2) / ((n - 1) / 2) // -1..1
    const tipX = cx + t * rnd(24, 40)
    const tipY = h - h * rnd(0.6, 0.98)
    head +=
      '<path d="M' + cx + ' ' + h + ' Q ' + fx(cx + t * 10) + ' ' + fx(h - h * 0.45) + ' ' + fx(tipX) + ' ' + fx(tipY) + '" ' +
      'stroke="' + pick(LEAF) + '" stroke-width="' + fx(rnd(3.2, 5.4)) + '" fill="none" stroke-linecap="round"/>'
  }
  // 一朵给草加点颜色的绒穗
  if (rand() < 0.55) {
    const sx = cx + rnd(-6, 6)
    const sy = h - h * rnd(0.78, 0.95)
    head += '<ellipse cx="' + fx(sx) + '" cy="' + fx(sy) + '" rx="' + fx(rnd(3, 4.5)) + '" ry="' + fx(rnd(7, 11)) + '" fill="' + pick(BLOOMC) + '" opacity="0.9"/>'
  }
  return { kind, w, h, body: '', head }
}

const BUILDERS = [dandelion, lavender, grass, lavender, dandelion, grass]

/* 固定 14 个位置（百分比）：几处成簇，留白比均匀排列更像真的草地 */
const SLOTS = [4, 9, 13, 17, 22, 31, 38, 47, 55, 61, 68, 76, 84, 92]

const plants = ref(
  SLOTS.map((xPct, i) => {
    const built = BUILDERS[i % BUILDERS.length]('p' + i)
    const depth = i % 3 // 0/1/2 → 前后层次：z-index、根部高度与初始大小
    return {
      id: 'p' + i,
      x: xPct,
      kind: built.kind,
      w: built.w,
      h: built.h,
      body: built.body,
      head: built.head,
      g: fx(depth === 2 ? rnd(0.62, 0.78) : rnd(0.8, 1.05)),
      sway: fx(rnd(4, 7)),
      swayA: fx(rnd(1.4, 3.4)),
      swayD: fx(rnd(-3, 0)),
      depth,
      // 每株根部离页脚底边的距离 = 山丘高度 − 该层的前坡顶棱高度
      bottom: Math.round(LAND_H - ROOTS[depth]),
      blooming: false
    }
  })
)

/* ── 生长 / 开花 ── */
const REACH = 80 // 点击判定半径（px），参考站同值
const STEP = 0.12 // 每次 +0.12
const G_MAX = 1.6

// 一次性 bloom：先摘类、下一帧再挂上，才能重新触发同一个 CSS 动画
const bloomTimers = new Map()
function bloom(p) {
  p.blooming = false
  const id = requestAnimationFrame(() => {
    p.blooming = true
    clearTimeout(bloomTimers.get(p.id))
    bloomTimers.set(
      p.id,
      setTimeout(() => {
        p.blooming = false
      }, 720)
    )
  })
  bloomFrames.set(p.id, id)
}
const bloomFrames = new Map()

/* ── 浇水壶光标：跟随指针；只有靠近植株（80px 判定半径内）才显示「点一下浇水」 ── */
const canEl = ref(null)
const dropsEl = ref(null)
const canOn = ref(false)
const tipOn = ref(false)
let pointerX = 0
let pointerY = 0
let nearTimer = 0

const isFinePointer = () =>
  typeof window !== 'undefined' && window.matchMedia('(hover: hover) and (pointer: fine)').matches
const reduceMotion = () =>
  typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

function nearestPlantDist(x, y) {
  const root = gardenEl.value
  if (!root) return Infinity
  const box = root.getBoundingClientRect()
  const fx0 = x - box.left
  const fy0 = y - box.top
  let best = Infinity
  plants.value.forEach((p) => {
    const bx = (box.width * p.x) / 100
    const byY = box.height - p.bottom
    const cy = Math.min(Math.max(fy0, byY - p.h * p.g - 10), byY)
    const d = Math.hypot(fx0 - bx, fy0 - cy)
    if (d < best) best = d
  })
  return best
}

function paintCan() {
  const can = canEl.value
  const tip = document.querySelector('.nz-tip')
  if (can) can.style.transform = `translate3d(${pointerX + 14}px, ${pointerY - 26}px, 0)`
  if (tip) tip.style.transform = `translate3d(${pointerX + 74}px, ${pointerY - 6}px, 0)`
}

function onGardenMove(e) {
  if (!isFinePointer() || reduceMotion()) return
  pointerX = e.clientX
  pointerY = e.clientY
  canOn.value = true
  paintCan()
  clearTimeout(nearTimer)
  nearTimer = setTimeout(() => {
    tipOn.value = nearestPlantDist(pointerX, pointerY) <= REACH
  }, 16)
}

function onGardenLeave() {
  canOn.value = false
  tipOn.value = false
}

/* 水滴：沿着壶嘴方向抛出的粒子，落地前淡出 */
function spawnDrops(x, y) {
  const layer = dropsEl.value
  if (!layer || reduceMotion()) return
  const box = layer.getBoundingClientRect()
  for (let i = 0; i < 7; i++) {
    const drop = document.createElement('i')
    drop.className = 'nz-drop'
    const dx = 40 + Math.random() * 70
    const dy = 70 + Math.random() * 60
    const size = 3 + Math.random() * 4
    drop.style.cssText = `left:${x - box.left + 6}px;top:${y - box.top - 10}px;width:${size}px;height:${size}px;--dx:${dx}px;--dy:${dy}px;animation-delay:${(i * 26).toFixed(0)}ms`
    layer.appendChild(drop)
    setTimeout(() => drop.remove(), 1100)
  }
}

/* 点击浇水时的「抽出」补间：作用在新增的内层 .nz-grow 上（这一层没有任何其它 transform，
   不碰 .nz-plant 的 scale(--g) 生长、也不碰 .nz-sway 的摇摆旋转）。
   先压到 0.82 再以 back.out 弹回，和 CSS 的持续生长叠加成"往上窜一下"的手感。 */
function popGrow(id) {
  const root = gardenEl.value
  if (!root) return
  const g = root.querySelector('.nz-plant[data-id="' + id + '"] .nz-grow')
  if (!g) return
  gsap.fromTo(
    g,
    { scaleY: 0.82, scaleX: 1.08, transformOrigin: 'bottom center' },
    { scaleX: 1, scaleY: 1, duration: 0.8, ease: 'back.out(2.6)', overwrite: 'auto' }
  )
}

function water(e) {
  const root = gardenEl.value
  if (!root) return
  let bloomed = false
  const box = root.getBoundingClientRect()
  const fx0 = e.clientX - box.left
  const fy0 = e.clientY - box.top
  plants.value.forEach((p) => {
    const bx = (box.width * p.x) / 100 // 根部横坐标
    // 根部纵坐标：植株锚在页脚底边上方 p.bottom 处（与 CSS 的 bottom 一致）
    const byY = box.height - p.bottom
    // 点到「茎」的最近点：把光标 y 夹在 [花头顶端-10, 根部] 之间，
    // 就得到竖直茎段上离光标最近的那一点（比包围盒更贴近人的直觉）
    const cy = Math.min(Math.max(fy0, byY - p.h * p.g - 10), byY)
    const dist = Math.hypot(fx0 - bx, fy0 - cy)
    if (dist <= REACH) {
      // 每浇一次长高 0.12（封顶 1.6）；到顶后仍会给花头一次脉冲
      if (p.g < G_MAX) p.g = Math.min(G_MAX, fx(p.g + STEP))
      bloom(p)
      if (!reduceMotion()) popGrow(p.id)
      bloomed = true
    }
  })
  if (bloomed) spawnDrops(e.clientX, e.clientY)
}

/* ── 离屏暂停：IntersectionObserver 给 footer 打 .anim-idle ── */
let io = null
onMounted(() => {
  if (!gardenEl.value || typeof IntersectionObserver !== 'function') return
  const garden = gardenEl.value
  if (garden && isFinePointer() && !reduceMotion()) {
    garden.addEventListener('pointermove', onGardenMove, { passive: true })
    garden.addEventListener('pointerleave', onGardenLeave, { passive: true })
    garden.addEventListener('pointerdown', onGardenLeave, { passive: true })
  }
  io = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        // 离屏 → 暂停全部 swipe / pulse；在屏 → 恢复
        playState.value = e.isIntersecting ? 'running' : 'paused'
      })
    },
    { rootMargin: '120px 0px', threshold: 0 }
  )
  io.observe(gardenEl.value)
})
onBeforeUnmount(() => {
  io?.disconnect()
})

/* ════ 底部条目（站点目前没有社交账号，放占位链接并注明） ════ */
const socials = [
  { label: 'GitHub', href: 'https://github.com/', title: 'GitHub（占位链接，尚未指向行迹仓库）' },
  { label: '反馈', href: 'mailto:hello@example.com', title: '反馈（占位邮箱，尚未开通）' },
  { label: '关于', href: '#honest', title: '关于 · 跳到「边界」一节' }
]

function onStart() {
  ui.openRegister()
  // 未登录时先打开注册浮层；已登录时同一按钮把意图留给后续行程创建流程
}

const year = computed(() => new Date().getFullYear())
</script>

<template>
  <footer
    ref="footerEl"
    class="nz-footer"
    :class="{ 'anim-idle': playState === 'paused' }"
    :style="{ '--ft-grow': grow, '--anim-play': playState }"
    aria-label="页脚"
  >
    <!-- 顶部波浪草边：一块奶油/藏青色的实心方块，上边被波浪 SVG 遮罩切出来 -->
    <div class="nz-crest" :style="{ '--nz-crest-mask': CREST_MASK }" aria-hidden="true"></div>

    <!-- 山丘与泥土（渐变占位符由 CSS 按主题填色标） -->
    <svg class="nz-hill-defs" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient :id="HILL_GRAD_ID" x1="0" y1="0" x2="0" y2="1">
          <stop class="nz-hill-stop-0" offset="0%" />
          <stop class="nz-hill-stop-1" offset="100%" />
        </linearGradient>
        <linearGradient :id="HILL_FRONT_GRAD_ID" x1="0" y1="0" x2="0" y2="1">
          <stop class="nz-land-stop-0" offset="0%" />
          <stop class="nz-land-stop-1" offset="100%" />
        </linearGradient>
      </defs>
    </svg>

    <div class="nz-land-clip" aria-hidden="true">
      <svg class="nz-land" viewBox="0 0 1440 221" preserveAspectRatio="none" focusable="false">
        <!-- 远山：深一点的橄榄绿 -->
        <path :d="HILL_BACK" :fill="'url(#' + HILL_GRAD_ID + ')'" />
        <!-- 前坡：更暗，压住山根 -->
        <path :d="HILL_FRONT" :fill="'url(#' + HILL_FRONT_GRAD_ID + ')'" />
        <!-- 坡面上的草毛边 -->
        <path
          class="nz-tufts"
          d="M120 150 l-4 -12 M180 144 l3 -13 M420 132 l-5 -11 M520 138 l4 -12
             M640 142 l4 -12 M700 130 l-3 -11 M880 152 l-4 -12 M980 150 l5 -12
             M1080 148 l5 -13 M1180 140 l-4 -11 M1300 140 l-4 -11 M1360 134 l3 -10"
        />
      </svg>
    </div>

    <!-- 中央文字组：中心落在 footer 底边上方 (50vh + 48px) -->
    <div class="nz-ft-inner">
      <p class="nz-ft-sub">
        从一句模糊的想法，到一份能照着走的行程——行迹把每个地点都对过真实地图。
      </p>
      <h2 class="nz-ft-head">一起出发吧</h2>
      <button type="button" class="nz-ft-btn" @click="onStart">开始规划</button>
    </div>

    <!-- 浇水壶光标 + 提示（参考站 .ft-cursor / .ft-tip 的等价物，全部自绘） -->
    <div ref="canEl" class="nz-can" :class="{ on: canOn }" aria-hidden="true">
      <svg viewBox="0 0 64 64" width="64" height="64">
        <g transform="rotate(-32 32 32)">
          <path d="M18 26 h20 a4 4 0 0 1 4 4 v10 a4 4 0 0 1 -4 4 h-20 a6 6 0 0 1 -6 -6 v-6 a6 6 0 0 1 6 -6 z" fill="#c98d72" stroke="#8c5a43" stroke-width="1.6" />
          <path d="M42 30 l14 -6" stroke="#8c5a43" stroke-width="3.4" stroke-linecap="round" />
          <path d="M56 24 l5 -2" stroke="#8c5a43" stroke-width="5.4" stroke-linecap="round" />
          <path d="M20 20 a6 6 0 0 1 12 0" fill="none" stroke="#8c5a43" stroke-width="1.8" />
          <path d="M24 26 v22 M32 26 v22" stroke="#a9715a" stroke-width="1.2" opacity=".6" />
        </g>
      </svg>
      <span class="nz-can-mist">
        <i v-for="n in 5" :key="n" :style="{ '--i': n }"></i>
      </span>
    </div>
    <div class="nz-tip" :class="{ on: tipOn }">点一下浇水</div>
    <div class="nz-drops" ref="dropsEl" aria-hidden="true"></div>

    <!-- 程序化花园 -->
    <div ref="gardenEl" class="nz-garden" @click="water">
      <div
        v-for="pl in plants"
        :key="pl.id"
        class="nz-plant"
        :class="{ bloomed: pl.blooming }"
        :style="{
          left: pl.x + '%',
          bottom: pl.bottom + 'px',
          '--g': pl.g,
          zIndex: 10 + pl.depth
        }"
        :data-kind="pl.kind"
        :data-id="pl.id"
      >
        <div
          class="nz-sway"
          :style="{ '--sway': pl.sway + 's', '--swayA': pl.swayA + 'deg', '--swayD': pl.swayD + 's' }"
        >
          <div class="nz-grow">
            <svg
              :width="pl.w"
              :height="pl.h"
              :viewBox="'0 0 ' + pl.w + ' ' + pl.h"
              aria-hidden="true"
              focusable="false"
            >
              <g v-html="pl.body"></g>
              <g class="nz-head" v-html="pl.head"></g>
            </svg>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部条目：左版权，右占位社交 -->
    <p class="nz-ft-credit">© {{ year }} 行迹 · 个人项目</p>
    <nav class="nz-ft-social" aria-label="页脚链接">
      <a
        v-for="s in socials"
        :key="s.label"
        :href="s.href"
        :title="s.title"
      >{{ s.label }}</a>
    </nav>
  </footer>
</template>

<style scoped>
/* ════ 浇水壶光标 / 提示 / 水滴（参考站 .ft-cursor / .ft-tip / .ft-drop 的等价物） ════ */
.nz-can {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 41;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.18s ease;
  transform: translate3d(-999px, -999px, 0);
  filter: drop-shadow(0 4px 6px rgba(40, 60, 40, 0.28));
}
.nz-can.on { opacity: 1; }
.nz-can-mist {
  position: absolute;
  right: -2px;
  top: 6px;
  width: 24px;
  height: 18px;
}
.nz-can-mist i {
  position: absolute;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #9fdcf7;
  opacity: 0;
  animation: nz-mist 0.9s ease-out infinite;
  animation-delay: calc(var(--i) * 0.12s);
  left: calc(var(--i) * 3px);
  top: 0;
}
/* 水壶光标不显示时（指针不在花园里）这 5 颗粒子不必一直空转 */
.nz-can:not(.on) .nz-can-mist i {
  animation-play-state: paused;
}
@keyframes nz-mist {
  0% { opacity: 0.85; transform: translate(0, 0) scale(0.8); }
  100% { opacity: 0; transform: translate(14px, 16px) scale(1.15); }
}

/* 提示：只有指针真的在一株的判定半径内才出现（不会指着空地说"浇水"） */
.nz-tip {
  position: fixed;
  left: 0;
  top: 0;
  z-index: 42;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.18s ease;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 500;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  white-space: nowrap;
  color: var(--text-heading, #060606);
  text-shadow: 0 0 3px var(--surface-raised, #eee8d2), 0 0 7px var(--surface-raised, #eee8d2),
    0 0 12px var(--surface-raised, #eee8d2);
  transform: translate3d(-999px, -999px, 0);
}
.nz-tip.on { opacity: 1; }

.nz-drops {
  position: absolute;
  inset: 0;
  z-index: 6;
  pointer-events: none;
  overflow: hidden;
}
.nz-drops .nz-drop {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 28%, #cdeeff 0%, #6cc2ee 55%, #3f9fd6 100%);
  box-shadow: 0 0 4px rgba(80, 180, 230, 0.5);
  animation: nz-drop-fall 0.95s cubic-bezier(0.4, 0, 0.8, 0.4) forwards;
}
@keyframes nz-drop-fall {
  0% { opacity: 0; transform: translate3d(0, 0, 0) scale(0.6); }
  18% { opacity: 1; }
  100% { opacity: 0; transform: translate3d(var(--dx, 60px), var(--dy, 90px), 0) scale(1); }
}
@media (prefers-reduced-motion: reduce) {
  .nz-can, .nz-tip, .nz-drops { display: none; }
}
/* ════ 页脚本体 —— 1440×938 画布（Figma 128:4842） ════
   overflow 必须保持 visible：波浪草边要向上「长出」页脚盒子。 */
.nz-footer {
  position: relative;
  z-index: 12;
  height: 938px;
  background: var(--surface-raised, #eee8d2);
  overflow: visible;
}

/* 只裁土地的水平溢出（它是唯一会横向出血的元素），页脚本身不受裁切 */
.nz-land-clip {
  position: absolute;
  inset: 0;
  overflow-x: clip;
  overflow-y: visible;
  z-index: 3;
  pointer-events: none;
}

/* 波浪草边：top -6.6vw（把最深的波谷抬到页脚上边沿之上），
   高度 13.75vw = 100vw × 198/1440，与旧 SVG 的固有比例一致 */
.nz-crest {
  position: absolute;
  top: -6.6vw;
  left: 0;
  width: 100%;
  height: 13.75vw;
  z-index: 1;
  pointer-events: none;
  background: var(--surface-raised, #eee8d2);
  -webkit-mask: var(--nz-crest-mask) 0 0 / 100% 100% no-repeat;
  mask: var(--nz-crest-mask) 0 0 / 100% 100% no-repeat;
}

/* 山丘：贴底，宽 100vw，高 221px。
   用 width:100vw + margin-left:-50vw 而不是 translateX(-50%)：后者在横向
   不能滚动的窄屏上会被算成向左偏移，把山丘推出左边缘（实测 390px 宽时露蓝）。 */
.nz-land {
  position: absolute;
  bottom: 0;
  left: 50%;
  margin-left: -50vw;
  width: 100vw;
  height: 221px;
  display: block;
  overflow: hidden;
}

/* 渐变定义块本身不可见：0×0 的 <svg> 只装 <defs> */
.nz-hill-defs {
  position: absolute;
  width: 0;
  height: 0;
  overflow: hidden;
  pointer-events: none;
}

/* 山丘渐变（日间）：远山橄榄 → 深橄榄；前坡更深 */
.nz-hill-stop-0 { stop-color: #8aa45c; }
.nz-hill-stop-1 { stop-color: #5b7a3a; }
.nz-land-stop-0 { stop-color: #4b6832; }
.nz-land-stop-1 { stop-color: #2c3f1d; }

/* 坡面草毛边 */
.nz-tufts {
  fill: none;
  stroke: #8aa45c;
  stroke-width: 2;
  stroke-linecap: round;
  opacity: 0.5;
}

/* 夜间：山丘转藏青-雾绿，前坡更深，草毛边提亮一档保持可读 */
body.night .nz-hill-stop-0 { stop-color: #2f4a52; }
body.night .nz-hill-stop-1 { stop-color: #1d3038; }
body.night .nz-land-stop-0 { stop-color: #17272e; }
body.night .nz-land-stop-1 { stop-color: #0d1a20; }
body.night .nz-tufts { stroke: #6f8f7a; opacity: 0.35; }

/* ════ 中央文字组 ════ */
.nz-ft-inner {
  position: absolute;
  left: 50%;
  bottom: calc(50vh + 48px);
  transform: translate(-50%, 50%);
  /* 参考站按 1440 画布定位；视口更窄时必须跟随容器，否则固定宽会撑出横向滚动条 */
  width: 100%;
  max-width: 1440px;
  height: auto;
  z-index: 4;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 64px;
  text-align: center;
  pointer-events: none; /* 花园要能接到点击；组内控件单独恢复 */
}
.nz-ft-inner > * { pointer-events: auto; }

/* 矮屏（≈13 寸笔记本）：花园的植物会长得比山脊高，把文字组再抬一点 */
@media (min-width: 1101px) and (max-height: 860px) {
  .nz-ft-inner { bottom: calc(50vh + 112px); }
}
@media (min-width: 1101px) and (max-height: 740px) {
  .nz-ft-inner { bottom: calc(50vh + 140px); }
}

/* 副标题：Inter Light 24px / 行高 1.358 / 字距 -0.96px（中文走 --f-sans） */
.nz-ft-sub {
  margin-inline: auto;
  max-width: 809.849px;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", "Microsoft YaHei", sans-serif);
  font-weight: 300;
  font-size: 24px;
  line-height: 1.358;
  letter-spacing: -0.96px;
  text-align: center;
  color: var(--text-heading, #000000);
  word-break: break-word;
}

/* 主标题：DM Serif Display，单行，随滚动从 0 长到原尺寸（DM Serif 无中文字形，
   中文落到 theme 里的衬线兜底，保持同一种「刻本」气质） */
.nz-ft-head {
  margin: 0;
  font-family: var(--f-serif, "DM Serif Display", "Songti SC", "Noto Serif SC", serif);
  font-weight: 400;
  /* 每一档宽度都是一行：390px 时约占宽 83%，1440px 时约 64%，更宽则封顶 */
  font-size: min(110px, calc(10px + 5.7vw), 8.2vw);
  line-height: normal;
  letter-spacing: -0.04em;
  word-spacing: 0.08em;
  color: var(--text-heading, #000000);
  white-space: nowrap;
  text-box-trim: trim-both;
  text-box-edge: cap alphabetic;
  /* 从中心等比放大，--ft-grow 由滚动 0→1 驱动 */
  transform: scale(var(--ft-grow, 0));
  transform-origin: center;
  will-change: transform;
}

/* 开始规划：白底蓝字 pill（与参考站 footer CTA 同一语汇） */
.nz-ft-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 14px 28px;
  border: 0;
  border-radius: 9999px;
  background: #fff;
  color: #1f3b6d;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  font-size: 18px;
  line-height: 1.2;
  letter-spacing: -0.36px;
  cursor: pointer;
  white-space: nowrap;
  box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.35);
  transition: transform var(--t-fast, 0.18s) cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.2s ease;
}
.nz-ft-btn:hover {
  transform: scale(1.03);
  box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.4);
}
.nz-ft-btn:active { transform: scale(0.97); }
.nz-ft-btn:focus-visible {
  outline: 2px solid var(--focus-ring, #000);
  outline-offset: 3px;
}

/* ════ 底部条目：参考站 top 893px / left 18.31px 与 top 886px / right 16px ════ */
.nz-ft-credit {
  position: absolute;
  top: 893px;
  left: 18.31px;
  z-index: 5;
  margin: 0;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  font-weight: 400;
  font-size: 16px;
  line-height: 1.358;
  letter-spacing: -0.64px;
  color: #fff;
  white-space: nowrap;
}
.nz-ft-social {
  position: absolute;
  top: 886px;
  right: 16px;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: 16px;
}
.nz-ft-social a {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  font-size: 16px;
  line-height: normal;
  color: #fff;
  text-decoration: none;
  white-space: nowrap;
  transition: color 0.3s ease, transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.nz-ft-social a:hover { color: var(--coral, #fc9073); transform: translateY(-1px); }
.nz-ft-social a:active { transform: translateY(0) scale(0.97); }
.nz-ft-social a:focus-visible {
  outline: 2px solid var(--coral, #fc9073);
  outline-offset: 2px;
  border-radius: 4px;
}

/* ════ 程序化花园 ════ */
.nz-garden {
  position: absolute;
  inset: 0;
  z-index: 3; /* 与土地同层，靠 DOM 顺序压在前面 */
  pointer-events: auto; /* 点击浇水需要命中层 */
  overflow: visible; /* 不能裁：花头会长到自己盒子的上边界之外 */
}

/* 每株：根部锚在山脊上（bottom 由脚本按层次给出），--g 驱动生长缩放 */
.nz-plant {
  position: absolute;
  bottom: 0;
  transform: translateX(-50%) scale(var(--g, 0.6));
  transform-origin: bottom center;
  transition: transform 1.15s cubic-bezier(0.22, 1, 0.36, 1);
}
.nz-plant svg { display: block; overflow: visible; }

/* 抽出补间专用内层：只有 popGrow 会写它的 transform（scale）。这一层不叠其它 transform，
   所以既不影响 .nz-plant 的 scale(--g) 生长，也不打断 .nz-sway 的摇摆动画。 */
.nz-grow {
  transform-origin: bottom center;
}

/* 摇曳放在内层：外层只负责生长，这样两者互不覆盖 transform */
.nz-sway {
  transform-origin: bottom center;
  animation: nz-sway var(--sway, 5s) ease-in-out infinite alternate;
  animation-delay: var(--swayD, 0s);
  animation-play-state: var(--anim-play, running);
  will-change: transform;
}
@keyframes nz-sway {
  from { transform: rotate(calc(var(--swayA, 3deg) * -1)); }
  to { transform: rotate(var(--swayA, 3deg)); }
}

/* 花头：以自身包围盒中心为原点脉冲 */
.nz-head {
  transform-box: fill-box;
  transform-origin: center;
}
.nz-plant.bloomed .nz-head {
  animation: nz-bloom 0.7s cubic-bezier(0.34, 1.56, 0.64, 1);
}
@keyframes nz-bloom {
  0% { transform: scale(0.92); }
  45% { transform: scale(1.22); }
  100% { transform: scale(1); }
}

/* 离屏：暂停全部无限动画（.nz-sway 也读 --anim-play，双保险） */
.nz-footer.anim-idle .nz-sway,
.nz-footer.anim-idle .nz-head {
  animation-play-state: paused;
}

/* ════ 响应式 ════ */
@media (max-width: 1440px) {
  /* 100vw 含纵向滚动条宽度，会撑出横向滚动条；跟随容器即可 */
  .nz-ft-inner { width: 100%; padding: 0 20px; }
}
@media (max-width: 900px) {
  .nz-footer { height: 780px; }
  .nz-ft-inner { gap: 40px; }
  .nz-ft-sub { font-size: 19px; letter-spacing: -0.76px; max-width: 86vw; }
  .nz-ft-credit { top: auto; bottom: 18px; left: 16px; }
  .nz-ft-social { top: auto; bottom: 14px; right: 10px; gap: 6px; }
}
@media (max-width: 640px) {
  .nz-footer { height: 700px; }
  /* 手机上封顶 96px：8.2vw 在 390px 宽时只有 32px，得靠 vw 段放大 */
  .nz-ft-head { font-size: min(96px, 17vw); }
  .nz-ft-sub { font-size: 16px; line-height: 1.5; letter-spacing: -0.5px; }
  .nz-ft-btn { padding: 12px 22px; font-size: 16px; }
  /* 手机上一个标签可能换行，底部条目分两行放 */
  .nz-ft-credit { bottom: 48px; }
}

@media (prefers-reduced-motion: reduce) {
  .nz-ft-head { transform: none; }
  .nz-plant { transition: none; }
  .nz-sway { animation: none; }
  .nz-plant.bloomed .nz-head { animation: none; }
  .nz-ft-btn { transition-duration: 1ms; }
}
</style>
