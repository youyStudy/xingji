<!--
  精选作品 —— 逐项对齐参考站 #work 的几何与动画（数值均为 2026-10-27 实测）
  · 区块：高 1889px，sticky top = 100vh − 高度（1440×900 下为 −989px，底部吸住）
  · 内部（相对区块顶）：标题 y80 · 跑马灯 y172(720 宽居中) · 卡片两行 y336 / y956
    第 1 行 宽卡 x60 w784 + 窄卡 x870 w510；第 2 行 窄卡 x60 w510 + 宽卡 x596 w784；高 595
  · 纸飞机：SVG 层 viewBox 0 0 1440 2981、向上伸出区块 1092px；3 段真实贝塞尔轨迹，
    段间靠中间桥接路径淡出/淡入；虚线 dasharray 10 6 随飞行距离显影；飞机 88px 随切线旋转
-->
<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Scroll, useScrollFrame } from '../../composables/useScroll'
import { useTilt } from '../../composables/useTilt'
import tileChongqing from '../../assets/tiles/chongqing.jpg'
import tileChengdu from '../../assets/tiles/chengdu.jpg'
import tileHangzhou from '../../assets/tiles/hangzhou.jpg'
import tileDali from '../../assets/tiles/dali.jpg'

const sectionEl = ref(null)
const shadeEl = ref(null)
const flyEl = ref(null)
const trailEl = ref(null)
const spriteEl = ref(null)
const tilesEl = ref(null)

/* 四张行程卡跟随指针做 3D 倾斜；触屏 / prefers-reduced-motion 自动跳过。
   maxDeg 给得很小（6°）——卡片是滚动叙事的一部分，翻转太大会抢戏也掉帧。 */
useTilt(tilesEl, { item: '.nz-tile', maxDeg: 6 })

/* 4 张卡（参考站同款 2×2 错位）。图用本项目相册的真实旅行照，裁到卡片比例后打进产物：
   外链占位图在部分网络取不到，会让首页这几块变成空白格子。 */
const TILES = [
  { slug: 'chongqing', city: '重庆', title: '山城三日', sub: '把攻略变成日程', days: '3 天', img: tileChongqing },
  { slug: 'chengdu', city: '成都', title: '茶馆半日', sub: '把空闲也排进路线', days: '1 天', img: tileChengdu },
  { slug: 'hangzhou', city: '杭州', title: '环湖一日', sub: '绕开人流的走法', days: '1 天', img: tileHangzhou },
  { slug: 'dali', city: '大理', title: '环海东岸', sub: '风大的时候换一条路', days: '3 天', img: tileDali }
]
const MARQUEE = ['重庆', '成都', '杭州', '大理', '西安', '厦门', '稻城亚丁', '青海湖']

/* ── 参考站实测轨迹（几何数据，原样复刻）─────────────────────────────────
   svg 层 viewBox: 0 0 1440 2981，层顶比区块顶高 1092px
   可见部分 = 3 段子路径，段与段之间不画（避免压住简介文字与标题）
   运动路径 = 段1 + 桥 + 段2 + 桥 + 段3（桥上飞机淡出再淡入） */
const SVG_W = 1440
const SVG_H = 2981.484375
const LIFT = 1092 // 层顶相对区块顶的偏移（向上）

const SEG = [
  {
    label: 'seg1',
    d: 'M 209.5 110 C 109.5 200 69.5 325 87.5 410 C 102.5 488 191.5 528 254.5 555'
  },
  {
    label: 'seg2',
    d: 'M 1217 789.3 C 1389.6 845.7 1397.8 1108 1170 1197.2'
  },
  {
    label: 'seg3',
    d: [
      'M 529.6 1212.9',
      'C 349.3 1219.1 -0.3 1374.1 124.1 1763',
      'C 190.4 1890 433 2054.1 685.7 1885.7',
      'C 1044.4 1646.8 1392 1690.1 1392 2017.7',
      'C 1392 2349.6 1083.5 2404.2 825.1 2324.3',
      'C 566.8 2244.4 286.3 2218.5 211.8 2502.7',
      'C 132.7 2804.4 555.6 2948.8 799.1 2792.7',
      'C 1042.6 2636.6 1382.7 2831.7 1245.2 3125.4'
    ].join(' ')
  }
]

/* 段与段之间的桥（参考站由 JS 用切线方向生成二次控制点，这里用等价的三次贝塞尔） */
const BRIDGE_12 = 'C 618.7 711.1 840.4 666 1217 789.3'
const BRIDGE_23 = 'C 931.4 1290.6 785.7 1204.1 529.6 1212.9'

const VISIBLE_D = SEG.map((s) => s.d).join(' ')
const MOTION_D =
  SEG[0].d + ' ' + BRIDGE_12.replace(/^C/, 'C') + ' ' + SEG[1].d.replace(/^M[^C]*/, '') + ' ' + BRIDGE_23 + ' ' + SEG[2].d.replace(/^M[^C]*/, '')

/* ── 纸飞机：读数线在视口 50%，飞机层顶进入视口后起飞，层底越过读数线时飞完 ── */
let motionLen = 0
let prevAngle = null
let contAngle = 0
let segRanges = []
let onPath = null
/* 上一次写入的状态：值没变就不碰样式（滚动到页脚时这段逐帧逻辑应彻底空转） */
let lastP = null
let lastDashLen = -1
const DASH_STEP = 32
let testiEl
let lastShade = ''

function measure() {
  onPath = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  onPath.setAttribute('d', MOTION_D)
  motionLen = onPath.getTotalLength()
  // 逐段算出在"运动路径"上的 [start, end] 与在"可见路径"上的推进量
  let acc = SEG[0].d
  const probe = document.createElementNS('http://www.w3.org/2000/svg', 'path')
  probe.setAttribute('d', acc)
  let prevEnd = probe.getTotalLength()
  let visEnd = prevEnd
  segRanges = [{ mStart: 0, mEnd: prevEnd, visEnd }]
  for (let i = 1; i < SEG.length; i++) {
    const bridge = i === 1 ? BRIDGE_12 : BRIDGE_23
    acc = acc + ' ' + bridge + ' ' + SEG[i].d.replace(/^M[^C]*/, '')
    probe.setAttribute('d', acc)
    const total = probe.getTotalLength()
    visEnd += total - prevEnd - 0
    segRanges.push({ mStart: prevEnd, mEnd: total, visEnd })
    prevEnd = total
  }
}

function updatePlane() {
  const sprite = spriteEl.value
  const trail = trailEl.value
  const fly = flyEl.value
  if (!sprite || !trail || !fly || !motionLen) return

  const rect = Scroll.rect(fly)
  // 轨迹层已经整体滚出视口：不做取点、不写样式
  if (rect.bottom < -200 || rect.top > Scroll.vh() + 200) return
  const line = Scroll.vh() * 0.5
  const span = Math.max(1, rect.height - line)
  let p = (line - rect.top) / span
  p = Math.max(0, Math.min(1, p))
  // 飞机只在起飞区间内才是可见的；端点处收尾一次后不再逐帧算路径点、写样式
  if (p <= 0 || p >= 1) {
    if (lastP !== p) {
      sprite.style.opacity = '0'
      trail.style.strokeDasharray = p <= 0 ? '0 99999' : '99999 0'
      lastP = p
      lastDashLen = p <= 0 ? 0 : motionLen
    }
    return
  }

  const md = p * motionLen
  const pt = onPath.getPointAtLength(md)
  const ahead = onPath.getPointAtLength(Math.min(motionLen, md + 1))
  const angle = (Math.atan2(ahead.y - pt.y, ahead.x - pt.x) * 180) / Math.PI

  // 落在"桥"上时淡出（参考站同做法：段间不显示飞机，避免压文字）
  let opacity = 1
  for (let i = 1; i < segRanges.length; i++) {
    const prev = segRanges[i - 1]
    const cur = segRanges[i]
    if (md > prev.mEnd && md < cur.mStart) {
      const bp = (md - prev.mEnd) / (cur.mStart - prev.mEnd)
      const ease = (t) => {
        t = Math.max(0, Math.min(1, t))
        return t * t * (3 - 2 * t)
      }
      opacity = Math.max(1 - ease(bp / 0.4), ease((bp - 0.6) / 0.4))
      break
    }
  }

  // 累积角度：相邻帧夹角超过 90° 时按最短方向补 360°，避免 ±180° 处翻转
  const target = angle - 31
  if (prevAngle != null) {
    let d = target - prevAngle
    while (d > 180) d -= 360
    while (d < -180) d += 360
    contAngle = prevAngle + d
  } else {
    contAngle = target
  }
  prevAngle = contAngle
  sprite.style.transform = 'translate3d(' + pt.x.toFixed(1) + 'px,' + pt.y.toFixed(1) + 'px,0) rotate(' + contAngle.toFixed(2) + 'deg)'
  sprite.style.opacity = String(opacity)
  // 线头随飞行距离延伸。dasharray 每次变更都要把整条 6000px 路径重新三角化和光栅化，
  // 逐帧写是这段最贵的操作；按路径长度定量步进（32px），看起来仍是连续生长。
  if (lastDashLen < 0 || Math.abs(md - lastDashLen) >= DASH_STEP) {
    trail.style.strokeDasharray = md.toFixed(1) + ' 99999'
    lastDashLen = md
  }
  lastP = p
}

/* ── 吸底 + 被覆盖压暗（参考站 landing.js:514-673）── */
function pinSection() {
  const el = sectionEl.value
  if (!el) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    el.style.position = 'relative'
    el.style.top = 'auto'
    return
  }
  const h = el.offsetHeight
  if (!h) return
  el.style.position = 'sticky'
  el.style.top = Math.min(0, Scroll.vh() - h) + 'px'
}

const smooth = (t) => {
  t = Math.max(0, Math.min(1, t))
  return t * t * (3 - 2 * t)
}

function recede() {
  const shade = shadeEl.value
  if (!shade) return
  if (testiEl === undefined) testiEl = document.querySelector('.nz-testimonials')
  if (!testiEl) return
  const vh = Scroll.vh()
  const top = Scroll.rect(testiEl).top
  const next = (0.45 * smooth((vh - top) / vh)).toFixed(3)
  if (next === lastShade) return
  lastShade = next
  shade.style.opacity = next
}

/* 离屏暂停跑马灯（参考站 anim-idle） */
let io = null
function watchIdle() {
  if (!sectionEl.value || typeof IntersectionObserver !== 'function') return
  io = new IntersectionObserver(
    (entries) => entries.forEach((e) => sectionEl.value.classList.toggle('anim-idle', !e.isIntersecting)),
    { rootMargin: '80px 0px' }
  )
  io.observe(sectionEl.value)
}

function onFrame() {
  updatePlane()
  recede()
}

onMounted(() => {
  measure()
  pinSection()
  watchIdle()
  window.addEventListener('resize', pinSection)
  window.addEventListener('load', pinSection)
  onFrame()
  Scroll.kick()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', pinSection)
  window.removeEventListener('load', pinSection)
  if (io) io.disconnect()
})

useScrollFrame(onFrame)
</script>

<template>
  <section ref="sectionEl" class="nz-featured" id="product">
    <div ref="shadeEl" class="nz-shade" aria-hidden="true"></div>

    <!-- 纸飞机层：向上伸出区块 1092px（参考站实测） -->
    <div ref="flyEl" class="nz-plane-fly" aria-hidden="true">
      <svg class="nz-plane-trail" :viewBox="'0 0 ' + SVG_W + ' ' + SVG_H" fill="none" preserveAspectRatio="none">
        <path class="nz-trail-stroke" ref="trailEl" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="10 6" :d="VISIBLE_D" />
      </svg>
      <svg ref="spriteEl" class="nz-plane-sprite" viewBox="0 0 113 115" aria-hidden="true">
        <path d="M50.8 4.3 L103.5 76.5 C104 77.2 104.2 78.5 103.4 78.7 C103 78.7 102.9 78.5 102.6 78.3 L44.4 31.1 C44.2 30.6 44.2 30 44.3 29.5 L46.2 5.9 C46.8 2.8 48.3 2.6 49.4 3 Z" fill="#fc9073" />
        <path d="M36.4 54.9 L102.7 80.3 L103.7 77.8 L46.2 31.6 L20.7 47.7 L42.8 46.2 Z" fill="#e07a5f" />
        <path d="M20.4 47.8 L39.7 53.9 L46.4 38.5 Z" fill="#212327" />
        <path d="M44.5 48.1 L102.2 79 L99.9 81.3 L27.3 72.8 L25.5 70.9 Z" fill="#f2a48d" />
      </svg>
    </div>

    <h2 class="nz-featured-heading">被这样走过的地方</h2>

    <div class="nz-marquee-wrap" aria-label="已经纳入行程的城市">
      <div class="nz-marquee-inner">
        <span v-for="(c, i) in MARQUEE" :key="'a' + i" class="nz-marquee-item">{{ c }}</span>
        <span v-for="(c, i) in MARQUEE" :key="'b' + i" class="nz-marquee-item" aria-hidden="true">{{ c }}</span>
      </div>
    </div>

    <!-- 4 张卡：第 1 行 宽–窄，第 2 行 窄–宽（参考站 x/y/w 全部对齐） -->
    <div class="nz-tiles" id="real" ref="tilesEl">
      <a
        v-for="(t, i) in TILES"
        :key="t.slug"
        class="nz-tile"
        :class="[i === 0 || i === 3 ? 'nz-tile-wide' : 'nz-tile-narrow', i === 1 ? 'nz-tile-slot-2' : '', i === 2 ? 'nz-tile-slot-3' : '', i === 3 ? 'nz-tile-slot-4' : '']"
        href="#"
        :aria-label="t.city + ' · ' + t.title"
        @click.prevent
      >
        <img class="nz-tile-img" :src="t.img" :alt="t.city + t.title" loading="lazy" decoding="async" />
        <span class="nz-tile-wash" aria-hidden="true"></span>
        <div class="nz-tile-overlay">
          <p class="nz-tile-title">{{ t.city }} · {{ t.title }}</p>
          <p class="nz-tile-sub">{{ t.sub }} · {{ t.days }}</p>
        </div>
      </a>
    </div>
  </section>
</template>

<style scoped>
/* ════ 区块：高 1889px，sticky 底部吸住（top 由 JS 设为 100vh − 高度）════ */
.nz-featured {
  position: sticky;
  top: 0;
  z-index: 10;
  /* 高度跟内容走：写死 1889px（参考站 1440×900 的值）时，1600×1000 下内容只有
     1551px，底部会空出 338px；吸底后这截空白正对屏幕，看起来像"卡住了"。
     sticky 的 top 由 pinSection() 按实际高度算（vh − h），不需要固定高度。 */
  box-sizing: border-box;
  background: var(--bg-base, #fff9f1);
  padding-top: 80px;
  overflow: visible;
}
.nz-shade {
  position: absolute;
  inset: 0;
  background: #000;
  opacity: 0;
  pointer-events: none;
  z-index: 99;
  will-change: opacity;
}

/* ════ 纸飞机层（参考站：向上伸出 1092px，viewBox 1440×2981）════ */
.nz-plane-fly {
  position: absolute;
  top: -1092px;
  left: 0;
  width: 100%;
  height: 2981px;
  pointer-events: none;
  overflow: visible;
  z-index: 3;
}
.nz-plane-trail {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}
.nz-trail-stroke {
  fill: none;
  stroke: var(--trail-stroke, #000);
}
.nz-plane-sprite {
  position: absolute;
  top: 0;
  left: 0;
  width: 88px;
  height: 89px;
  margin: -44px 0 0 -44px; /* 以路径点为圆心 */
  transform-origin: 50% 50%;
  will-change: transform, opacity;
}

/* ════ 标题（y80）：40px / 300 / -1.6px / 居中 ════ */
.nz-featured-heading {
  margin: 0;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-size: 40px;
  font-weight: 300;
  letter-spacing: -1.6px;
  line-height: 1.358;
  color: var(--text-heading, #000);
  text-align: center;
}

/* ════ 跑马灯（y172，宽 720 居中）════ */
.nz-marquee-wrap {
  position: relative;
  overflow: hidden;
  width: 720px;
  max-width: 86vw;
  height: 68px;
  margin: 38px auto 0;
}
.nz-marquee-wrap::before,
.nz-marquee-wrap::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  width: 80px;
  z-index: 2;
  pointer-events: none;
}
.nz-marquee-wrap::before {
  left: 0;
  background: linear-gradient(to right, var(--bg-base, #fff9f1), transparent);
}
.nz-marquee-wrap::after {
  right: 0;
  background: linear-gradient(to left, var(--bg-base, #fff9f1), transparent);
}
.nz-marquee-inner {
  display: flex;
  align-items: center;
  gap: 72px;
  height: 68px;
  width: max-content;
  animation: nz-logo-scroll 24s linear infinite;
}
.nz-marquee-inner:hover {
  animation-play-state: paused;
}
.nz-featured.anim-idle .nz-marquee-inner {
  animation-play-state: paused;
}
@keyframes nz-logo-scroll {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}
.nz-marquee-item {
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-size: 30px;
  line-height: 68px;
  color: var(--text-heading, #000);
  opacity: 0.45;
  white-space: nowrap;
}

/* ════ 4 张卡：绝对定位对齐参考站（第 1 行 y336，第 2 行 y956；卡片高 595）════
   区块 padding-top 80 + 标题 54 + 跑马灯 68 + 间距 → 第 1 行正好落在 y336 */
.nz-tiles {
  position: relative;
  width: 1440px;
  max-width: 100%;
  margin: 0 auto;
  height: 1215px; /* 两块卡片 595×2 + 25 间距 */
  margin-top: 96px; /* 80 + 54 + 38 + 68 + 96 = 336（实测参考站第 1 行 y=336） */
}
.nz-tile {
  position: absolute;
  top: 0;
  height: 595px;
  border-radius: 40px;
  overflow: hidden;
  cursor: pointer;
  display: block;
  box-shadow: inset 0 0 0 0 #323131;
  transition: box-shadow 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94);
}
.nz-tile:hover {
  box-shadow: inset 0 0 0 1px #323131;
}
/* 参考站实测坐标 */
.nz-tile:nth-child(1) { left: 60px; width: 784px; top: 0; }
.nz-tile:nth-child(2) { left: 870px; width: 510px; top: 0; }
.nz-tile:nth-child(3) { left: 60px; width: 510px; top: 620px; }
.nz-tile:nth-child(4) { left: 596px; width: 784px; top: 620px; }

.nz-tile-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.nz-tile-wash {
  position: absolute;
  inset: 0;
  background: var(--bg-base, #fff9f1);
  opacity: 0.08;
  pointer-events: none;
  mix-blend-mode: soft-light;
}
.nz-tile-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  backdrop-filter: blur(7.7px);
  -webkit-backdrop-filter: blur(7.7px);
  background: var(--overlay-scrim, rgba(255, 249, 241, 0.7));
  opacity: 0;
  transition: opacity 0.35s cubic-bezier(0.25, 0.46, 0.45, 0.94);
  pointer-events: none;
}
.nz-tile:hover .nz-tile-overlay {
  opacity: 1;
}
.nz-tile-title {
  margin: 0;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 40px;
  letter-spacing: -1.6px;
  line-height: 1.358;
  text-align: center;
  color: var(--text-contrast, #000);
  text-transform: uppercase;
  width: 100%;
}
.nz-tile-sub {
  margin: 0;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 19px;
  letter-spacing: -0.76px;
  line-height: 1.358;
  text-align: center;
  color: var(--text-contrast, #000);
  width: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .nz-marquee-inner { animation: none; }
  .nz-plane-sprite { display: none; }
}
</style>
