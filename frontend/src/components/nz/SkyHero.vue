<script setup>
/*
 * SkyHero —— 行迹首页吸顶天空 hero
 * 移植自参考站 .hero（inline-head.css:3-12）+ .hero-content（374-385）+ .recorder（343-368）
 * 及 landing.js:4-36（滚动缩放）、landing.js:811-894（逐字拆分 + 纵向滚词）。
 * 几何/时长全部照抄参考站实测值；颜色走 theme.css 语义 token，并带参考站兜底值。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { gsap } from 'gsap'
import { useScrollFrame } from '../../composables/useScroll'
import { useMagnetic } from '../../composables/useMagnetic'
import NightSky from './NightSky.vue'

const heroEl = ref(null)
const contentEl = ref(null)
const trackEl = ref(null)
const hostEl = ref(null)
const sliderEl = ref(null)
const knobEl = ref(null)
const fillEl = ref(null)
const playWrapEl = ref(null)

// 磁性 CTA:指针靠近播放圆钮时微微吸附,离开弹回。
// 挂在外层 span 上,让按钮自身的 CSS :hover/:active scale 不被 gsap 的 transform 覆盖。
useMagnetic(playWrapEl, { strength: 0.28, verticalStrength: 0.4, radius: 70 })

/* ── 文案（中文，行迹语言）── */
const eyebrowText = '行迹 · 写在出发之前'
const verticalText = '生成 / 核验 / 可执行'
const lineOne = '让一句想法'
/* 滚动词循环：900ms 停留 / 560ms 滚动（landing.js:841-843 实测值） */
const WORDS = ['长出行程', '长出路线', '落成日子', '变成地图']
const HOLD = 900
const DUR = 560
const EASE = 'cubic-bezier(0.22, 1.15, 0.36, 1)'

const recTitle = '重庆 · 三日 · 从想走到能走'

/* 逐字拆分第一行（landing.js:814-827）：空格单独成 span，避免 inline-block 吞空格 */
const lineOneLetters = computed(() =>
  Array.from(lineOne).map((ch) => ({ ch, space: ch === ' ' }))
)

/* ── 纵向滚词 ── */
const currentWord = ref(WORDS[0])
let rollTimer = null
let reduce = false

function makeWordEl(text) {
  const w = document.createElement('span')
  w.className = 'nz-roll-word'
  for (const ch of Array.from(text)) {
    const s = document.createElement('span')
    s.className = 'nz-letter'
    s.textContent = ch
    w.appendChild(s)
  }
  return w
}

function scheduleNext(wordIndex) {
  rollTimer = setTimeout(() => rollTo((wordIndex + 1) % WORDS.length), HOLD)
}

function rollTo(nextIndex) {
  const track = trackEl.value
  const host = hostEl.value
  if (!track || !host) return
  const word = WORDS[nextIndex]

  if (reduce) {
    // 减少动效：纯换字，不做位移
    track.replaceChildren(makeWordEl(word))
    currentWord.value = word
    scheduleNext(nextIndex)
    return
  }

  host.style.overflowClipMargin = '0px' // 滚动期间收紧裁切，避免上一词露出
  track.appendChild(makeWordEl(word)) // 新词直接叠在当前词下方

  const h = track.firstElementChild.getBoundingClientRect().height
  track.style.transition = `transform ${DUR}ms ${EASE}`
  requestAnimationFrame(() => {
    track.style.transform = `translateY(${-h}px)`
  })

  const done = (e) => {
    if (e.propertyName !== 'transform') return
    track.removeEventListener('transitionend', done)
    track.style.transition = 'none'
    track.style.transform = 'none'
    track.removeChild(track.firstElementChild)
    host.style.overflowClipMargin = '' // 还原，hover 抬起才不会被切
    currentWord.value = word
    scheduleNext(nextIndex)
  }
  track.addEventListener('transitionend', done)
}

/* ── 滚动缩放：progress=clamp(y/(0.8*heroH),0,1)、ease=1-(1-p)^3、scale=1-0.12*ease ──
   走共用的单 rAF 调度器（src/composables/useScroll.js · useScrollFrame）。 */
let heroH = 0
let lastScale = -1
let heroIdle = false
function updateHeroScale() {
  const el = heroEl.value
  const content = contentEl.value
  if (!el || !content) return
  if (!heroH) heroH = el.offsetHeight || 1
  const y = window.scrollY || 0
  const p = Math.min(Math.max(y / (heroH * 0.8), 0), 1)
  const eased = 1 - Math.pow(1 - p, 3)
  const scale = 1 - eased * 0.12
  // hero 是 sticky 的，整页滚动时始终在视口内（只是被后面的区块盖住），
  // IntersectionObserver 永远判不到离屏；改用「被盖住」来暂停无限动画。
  const covered = y >= heroH
  if (covered !== heroIdle) {
    heroIdle = covered
    el.classList.toggle('anim-idle', covered)
  }
  // 滚过 hero 后 p 恒为 1：不再逐帧写 transform（每次写都会让整棵 hero 内容失效）
  if (scale === lastScale) return
  lastScale = scale
  // 滚动缩放与断点适配缩放复合：先按 fit 贴合画布，再乘滚动缩小系数
  content.style.transform = `scale(calc(var(--fit, 1) * ${scale.toFixed(4)}))`
}
/* 订阅共用调度器：挂载时加入、卸载时自动退订 */
useScrollFrame(updateHeroScale)

/* ── 行程进度盘：视觉播放（无音频素材；用 WebAudio 生成极轻的点击节拍）── */
const TOTAL = 128 // 秒（2:08），仅用于时间戳演示
const playing = ref(false)
const position = ref(0) // 0..1
const KNOB = 23.45 // 参考站 landing.js:192
let audioCtx = null
let tickTimer = null
let metronome = null
let startedAt = 0
let startPos = 0

const fmt = (s) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`
const currentLabel = computed(() => fmt(position.value * TOTAL))
const totalLabel = computed(() => fmt(TOTAL))

function render() {
  const slider = sliderEl.value
  if (!slider) return
  const w = slider.clientWidth
  const x = position.value * Math.max(0, w - KNOB)
  if (knobEl.value) knobEl.value.style.left = x + 'px'
  if (fillEl.value) fillEl.value.style.width = x + KNOB / 2 + 'px'
}

function lightBeat() {
  // 极轻的点击节拍：短促正弦 + 快速衰减，音量 0.02，避免打扰
  if (!audioCtx) return
  const t = audioCtx.currentTime
  const osc = audioCtx.createOscillator()
  const gain = audioCtx.createGain()
  osc.type = 'sine'
  osc.frequency.setValueAtTime(880, t)
  gain.gain.setValueAtTime(0.0001, t)
  gain.gain.exponentialRampToValueAtTime(0.02, t + 0.008)
  gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.09)
  osc.connect(gain).connect(audioCtx.destination)
  osc.start(t)
  osc.stop(t + 0.1)
}

function tick() {
  const elapsed = (performance.now() - startedAt) / 1000
  position.value = Math.min(1, startPos + elapsed / TOTAL)
  render()
  if (position.value >= 1) setPlaying(false)
}

function setPlaying(state) {
  playing.value = state
  if (state) {
    if (position.value >= 1) position.value = 0
    startPos = position.value
    startedAt = performance.now()
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)()
      if (audioCtx.state === 'suspended') audioCtx.resume()
    } catch (e) {
      audioCtx = null
    }
    if (tickTimer) clearInterval(tickTimer)
    tickTimer = setInterval(tick, 200)
    if (!reduce) metronome = setInterval(lightBeat, 1500)
  } else {
    if (tickTimer) clearInterval(tickTimer)
    if (metronome) clearInterval(metronome)
    tickTimer = null
    metronome = null
    startPos = position.value
  }
}

function togglePlay() {
  setPlaying(!playing.value)
}

/* 整盘点击即播放/暂停，但滑杆自己处理拖拽（landing.js:213-217） */
function onRecorderClick(e) {
  if (e.target.closest('.nz-play-slider')) return
  togglePlay()
}

/* ── 滑杆拖拽（.scrubbing：拖拽时 1:1 跟手，去掉 0.12s 跟随过渡）── */
const scrubbing = ref(false)
function pctFromEvent(e) {
  const rect = sliderEl.value.getBoundingClientRect()
  const x = e.clientX - rect.left - KNOB / 2
  return Math.min(Math.max(x / (rect.width - KNOB), 0), 1)
}
function onSliderDown(e) {
  scrubbing.value = true
  sliderEl.value.setPointerCapture(e.pointerId)
  position.value = pctFromEvent(e)
  render()
  if (playing.value) {
    startedAt = performance.now()
    startPos = position.value
  }
}
function onSliderMove(e) {
  if (!scrubbing.value) return
  position.value = pctFromEvent(e)
  render()
  if (playing.value) {
    startedAt = performance.now()
    startPos = position.value
  }
}
function onSliderUp(e) {
  if (!scrubbing.value) return
  scrubbing.value = false
  try {
    sliderEl.value.releasePointerCapture(e.pointerId)
  } catch (err) {
    /* noop */
  }
}

let resizeHandler = null
/* ── 画布适配缩放（参考站 baseHeroScale 复刻，landing.js:17-21）──
   1440 及以上不动；641–1300 之间按「画布自然宽 1120px、两侧各留 32px」连续缩小，
   这样平板不会出现标题溢出右侧被裁。 */
const CANVAS_W = 1120
/* 断点适配改为 CSS 换字号（上文的 @media 块），不再依赖整体缩放 */
function updateFit() {}

/* ── 天空揭幕开场时间线 ──
   一次编排好的入场序列（非零散微动效）：天空先亮 → 太阳升起 → 眉标/竖排浮现 →
   标题逐字点亮 → 滚词块淡入 → 前景两朵云飘过 → 行程进度盘最后落定。
   ★ 只淡入 / 位移那些「自身没有 CSS transform」的元素；竖排(rotate)、进度盘
     (translate+scale) 只做 autoAlpha，逐字用 clearProps 收尾，绝不错误覆盖它们的定位 transform。
   ★ reduce 时整段不跑，元素保持 CSS 默认可见。 */
let introCtx = null
function runIntro() {
  if (reduce || !heroEl.value) return
  introCtx = gsap.context(() => {
    const q = gsap.utils.selector(heroEl.value)
    const night = document.body.classList.contains('night')
    const tl = gsap.timeline({ defaults: { ease: 'power2.out' } })
    // 天空先亮：云絮 + 噪点淡入
    tl.from(q('.nz-hero-cirrus'), { autoAlpha: 0, duration: 1.0 }, 0)
      .from(q('.nz-hero-noise'), { autoAlpha: 0, duration: 1.4 }, 0)
    // 太阳升起（夜间隐藏，跳过）
    if (!night) tl.from(q('.nz-sun-group'), { autoAlpha: 0, y: 44, duration: 1.3 }, 0.15)
    // 眉标 + 竖排（竖排有 CSS rotate，只淡入）
    tl.from(q('.nz-hero-eyebrow'), { autoAlpha: 0, y: -10, duration: 0.6, clearProps: 'transform' }, 0.75)
      .from(q('.nz-hero-vertical'), { autoAlpha: 0, duration: 0.7 }, 1.0)
    // 标题逐字点亮
    tl.from(q('.nz-line-one .nz-letter'), { autoAlpha: 0, yPercent: 28, duration: 0.5, stagger: 0.05, clearProps: 'transform' }, 1.05)
    // 滚词块淡入（它自身在循环换词，不碰 transform）
    tl.from(q('.nz-line-roll'), { autoAlpha: 0, duration: 0.6 }, 1.5)
    // 前景两朵云飘入
    tl.from(q('.nz-cloud-a'), { autoAlpha: 0, x: 46, duration: 1.2 }, 0.6)
      .from(q('.nz-cloud-b'), { autoAlpha: 0, x: -34, duration: 1.2 }, 0.9)
    // 进度盘落定（只淡入，保住居中 transform）
    tl.from(q('.nz-recorder'), { autoAlpha: 0, duration: 0.8 }, 1.7)
  }, heroEl.value)
}

onMounted(() => {
  reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  updateFit()
  updateHeroScale()
  // hero 高度随断点/视口变化 → 重置缓存
  resizeHandler = () => {
    heroH = 0
    updateFit()
    updateHeroScale()
    render()
  }
  window.addEventListener('resize', resizeHandler, { passive: true })
  render()
  rollTimer = setTimeout(() => rollTo(1), HOLD)
  runIntro()
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(render)
})

onBeforeUnmount(() => {
  if (introCtx) introCtx.revert()
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
  if (rollTimer) clearTimeout(rollTimer)
  if (tickTimer) clearInterval(tickTimer)
  if (metronome) clearInterval(metronome)
  if (audioCtx && audioCtx.close) audioCtx.close().catch(() => {})
})
</script>

<template>
  <section ref="heroEl" class="nz-hero">
    <!-- 云絮层：CSS 径向渐变仿造天空碎云，opacity .6 + screen（inline-head.css:29-44 同参） -->
    <div class="nz-hero-cirrus" aria-hidden="true"></div>
    <!-- 噪点层：data-URI feTurbulence 平铺 2048px，opacity .1（head.css:57-64） -->
    <div class="nz-hero-noise" aria-hidden="true"></div>
    <NightSky />

    <div ref="contentEl" class="nz-hero-content">
      <!-- 太阳（参考站 sun.svg 的设计语汇：柔光 + 斜线排线圆盘 + 中心实心盘；全部自绘）
           位置比例取自参考站 .sun-group：top -0.256087×w，right -0.171192×w -->
      <div class="nz-sun-group" aria-hidden="true">
        <span class="nz-sun-glow"></span>
        <svg class="nz-sun-disc" viewBox="-10 -10 220 220" role="presentation">
          <defs>
            <pattern id="nzSunHatch" width="17" height="17" patternUnits="userSpaceOnUse" patternTransform="rotate(38)">
              <line x1="0" y1="0" x2="0" y2="17" stroke="#ffd83a" stroke-width="5" />
            </pattern>
            <!-- 印章式遮罩：中心实、边缘碎，避免"贴图感" -->
            <radialGradient id="nzSunSoft" cx="50%" cy="50%" r="50%">
              <stop offset="82%" stop-color="#fff" stop-opacity="1" />
              <stop offset="96%" stop-color="#fff" stop-opacity=".55" />
              <stop offset="100%" stop-color="#fff" stop-opacity="0" />
            </radialGradient>
            <mask id="nzSunMask"><circle cx="100" cy="100" r="100" fill="url(#nzSunSoft)" /></mask>
            <radialGradient id="nzSunCore" cx="50%" cy="46%" r="52%">
              <stop offset="0%" stop-color="#fffaf0" stop-opacity=".85" />
              <stop offset="100%" stop-color="#fffaf0" stop-opacity="0" />
            </radialGradient>
          </defs>
          <g mask="url(#nzSunMask)">
            <circle cx="100" cy="100" r="100" fill="url(#nzSunHatch)" />
            <circle cx="100" cy="100" r="72" fill="url(#nzSunCore)" />
          </g>
        </svg>
      </div>

      <!-- 两朵云：白色 40% 半透明（head.css:84-99 的 cloud-a / cloud-b 比例） -->
      <svg class="nz-cloud nz-cloud-a" viewBox="0 0 320 90" aria-hidden="true">
        <path
          d="M20 70 C 10 70 4 62 6 53 C 8 43 20 40 28 42 C 32 24 50 16 66 22 C 76 8 100 6 112 18 C 130 10 152 18 158 34 C 182 30 202 40 206 54 C 210 66 200 72 188 72 Z"
          fill="#fff"
          fill-opacity=".4"
        />
      </svg>
      <svg class="nz-cloud nz-cloud-b" viewBox="0 0 240 70" aria-hidden="true">
        <path
          d="M16 54 C 8 54 3 48 5 41 C 7 33 16 31 22 33 C 26 19 40 13 52 18 C 60 8 78 7 87 16 C 102 10 118 17 122 29 C 140 26 154 34 157 44 C 160 52 152 56 143 56 Z"
          fill="#fff"
          fill-opacity=".4"
        />
      </svg>

      <!-- eyebrow（head.css:102-115：JetBrains Mono 14px / 1.4px 字距 / 大写） -->
      <p class="nz-hero-eyebrow">
        <span class="nz-eyebrow-dot" aria-hidden="true"></span>
        {{ eyebrowText }}
      </p>

      <h1 class="nz-hero-h1">
        <span class="nz-sr-only">{{ lineOne }}{{ currentWord }} —— 行迹</span>
        <!-- 竖排标签（head.css:119-129：rotate(-90deg)，左移 506px） -->
        <span class="nz-hero-vertical" aria-hidden="true">{{ verticalText }}</span>
        <!-- 第一行：逐字 hover -->
        <span class="nz-h-line nz-line-one" aria-hidden="true">
          <span
            v-for="(item, i) in lineOneLetters"
            :key="'l1-' + i"
            class="nz-letter"
            :class="{ 'nz-h-space': item.space }"
          >{{ item.space ? ' ' : item.ch }}</span>
        </span>
        <!-- 第二行：纵向滚词（head.css:316-323 的 .designer 裁切槽） -->
        <span ref="hostEl" class="nz-h-line nz-line-roll" aria-hidden="true">
          <span ref="trackEl" class="nz-roll">{{ currentWord }}</span>
        </span>
      </h1>

      <!-- 行程进度盘（等价于参考站 .recorder，head.css:343-368） -->
      <div
        class="nz-recorder"
        role="region"
        aria-label="行程进度盘 —— 重庆三日"
        @click="onRecorderClick"
      >
        <div class="nz-disk" :class="{ 'is-spinning': playing }" aria-hidden="true">
          <span class="nz-disk-face"></span>
          <span class="nz-disk-cap"></span>
        </div>

        <div class="nz-rec-center">
          <p class="nz-rec-title">{{ recTitle }}</p>

          <div
            ref="sliderEl"
            class="nz-play-slider"
            :class="{ 'is-scrubbing': scrubbing }"
            role="slider"
            tabindex="0"
            :aria-valuenow="Math.round(position * 100)"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-label="'行程进度 ' + currentLabel"
            @pointerdown.prevent="onSliderDown"
            @pointermove.prevent="onSliderMove"
            @pointerup="onSliderUp"
            @pointercancel="onSliderUp"
            @keydown.left.prevent="position = Math.max(0, position - 0.02); render()"
            @keydown.right.prevent="position = Math.min(1, position + 0.02); render()"
          >
            <div class="nz-slider-track"><div ref="fillEl" class="nz-slider-fill"></div></div>
            <div ref="knobEl" class="nz-slider-knob"></div>
          </div>

          <div class="nz-timestamps">
            <span>{{ currentLabel }}</span>
            <span>{{ totalLabel }}</span>
          </div>

          <span ref="playWrapEl" class="nz-magnet">
            <button
              class="nz-play-circle"
              type="button"
              :aria-label="playing ? '暂停' : '播放'"
              :aria-pressed="playing"
              @click.stop="togglePlay"
            >
              <svg class="nz-ring" viewBox="0 0 42 42" aria-hidden="true">
                <circle cx="21" cy="21" r="20.3" fill="none" stroke="#8f8d88" stroke-width="1.4" />
              </svg>
              <svg class="nz-tri" viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="12" cy="12" r="11" fill="#2f2c2a" />
                <path d="M9.6 7.6 L16.6 12 L9.6 16.4 Z" fill="#fff" />
              </svg>
              <span class="nz-pause-bars" aria-hidden="true"><span></span><span></span></span>
            </button>
          </span>
        </div>

        <div class="nz-disk" :class="{ 'is-spinning': playing }" aria-hidden="true">
          <span class="nz-disk-face"></span>
          <span class="nz-disk-cap"></span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
/* ════ HERO（inline-head.css:4-12）════ */
.nz-hero {
  position: sticky;
  top: 0;
  height: 100vh;
  overflow: hidden;
  z-index: 1;
  background: radial-gradient(
    ellipse 120% 150% at 68.65% 23.5%,
    var(--hero-from, #71b7f4) 0%,
    var(--hero-to, #4a86b8) 100%
  );
}

/* 云絮：几团极淡的高光，screen 混合（head.css:41-42） */
.nz-hero-cirrus {
  position: absolute;
  inset: 0 -10%;
  opacity: 0.6;
  mix-blend-mode: screen;
  pointer-events: none;
  background-image:
    radial-gradient(38% 22% at 22% 34%, rgba(255, 255, 255, 0.85) 0%, rgba(255, 255, 255, 0) 70%),
    radial-gradient(30% 18% at 62% 24%, rgba(255, 255, 255, 0.7) 0%, rgba(255, 255, 255, 0) 72%),
    radial-gradient(44% 26% at 78% 46%, rgba(255, 255, 255, 0.6) 0%, rgba(255, 255, 255, 0) 74%),
    radial-gradient(36% 20% at 40% 62%, rgba(255, 255, 255, 0.5) 0%, rgba(255, 255, 255, 0) 76%);
}

/* 噪点：feTurbulence 平铺，opacity .1（head.css:57-64） */
.nz-hero-noise {
  position: absolute;
  inset: 0;
  opacity: 0.1;
  pointer-events: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='256' height='256'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.8' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='256' height='256' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 2048px 2048px;
}

/* 内容层（head.css:375-385）：全幅绝对定位，作为缩放容器 */
.nz-hero-content {
  position: absolute;
  inset: 0;
  transform-origin: center center;
  will-change: transform;
  --sun-w: clamp(300px, 48vw, 725.138px);
}

/* ── 太阳组（head.css:73-81 比例：top -0.256087×w，right -0.171192×w）── */
.nz-sun-group {
  position: absolute;
  /* 参考站原始的太阳盘半径约为 --sun-w 的 0.29（中心落在画布右侧外），
     直接按 --sun-w 当直径会大出 3 倍、变成一张大贴图；这里按太阳真实直径给尺寸 */
  width: calc(var(--sun-w) * 0.62);
  aspect-ratio: 1;
  /* 参考站实测：圆心落在距右边缘约 33% --sun-w、距顶约 1% 处（大半个太阳在画布外） */
  /* 参考站：太阳圆心落在距右缘 ~29% --sun-w、距顶 ~4% --sun-w（约四分之一在画布外） */
  top: calc(var(--sun-w) * -0.31);
  right: calc(var(--sun-w) * 0.02);
  z-index: 3;
  pointer-events: none;
  z-index: 2;
}
.nz-sun-glow {
  position: absolute;
  /* 参考站：白色 45% 大柔光（r≈209/725 相对圆盘），blur 76.7 */
  inset: -6%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 250, 226, 0.55) 0%, rgba(255, 250, 226, 0.3) 46%, rgba(255, 250, 226, 0) 100%);
  filter: blur(18px);
  mix-blend-mode: screen;
  animation: nz-sun-breathe 9s ease-in-out infinite alternate;
}
@keyframes nz-sun-breathe {
  from { transform: scale(0.96); opacity: 0.85; }
  to   { transform: scale(1.05); opacity: 1; }
}
/* hero 滚出视口后停掉柔光的模糊动画（带 blur 的重绘很贵） */
.nz-hero.anim-idle .nz-sun-glow {
  animation-play-state: paused;
}
/* 夜空星点是 NightSky 子组件的，用 :deep 一起暂停 */
.nz-hero.anim-idle :deep(.nz-star) {
  animation-play-state: paused;
}
.nz-sun-disc {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

/* ── 云（head.css:84-99）── */
.nz-cloud {
  position: absolute;
  height: auto;
  pointer-events: none;
  z-index: 1;
}
.nz-cloud-a {
  width: calc(var(--sun-w) * 0.723655);
  right: calc(var(--sun-w) * -0.466696);
  top: calc(var(--sun-w) * 0.264775);
}
.nz-cloud-b {
  width: calc(var(--sun-w) * 0.384752);
  right: calc(var(--sun-w) * 0.406185);
  top: calc(var(--sun-w) * 0.238576);
}

/* ── eyebrow（head.css:102-115）── */
.nz-hero-eyebrow {
  position: absolute;
  left: calc(50% - 459px);
  top: calc(50vh - 240px);
  display: flex;
  align-items: center;
  gap: 8px;
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 14px;
  letter-spacing: 1.4px;
  text-transform: uppercase;
  color: #fff;
  white-space: nowrap;
}
.nz-eyebrow-dot {
  width: 10.49px;
  height: 10.49px;
  border-radius: 50%;
  background: #ffda3f;
  flex-shrink: 0;
}

.nz-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

/* ── 标题（head.css:304-314：DM Serif Display 156px / 字距 3.1px / #fff9e9）── */
.nz-h-line {
  position: absolute;
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-size: 156px;
  font-weight: 400;
  letter-spacing: 3.1px;
  color: var(--cream-text, #fff9e9);
  white-space: nowrap;
  line-height: normal;
}
.nz-hero-h1 {
  margin: 0;
  padding: 0;
  font-weight: 400;
  position: static;
}
.nz-line-one {
  left: calc(50% - 459px);
  top: calc(50vh - 232px);
}
/* 滚词槽：裁切滚动，但留 30px 余量给 hover 抬起（head.css:316-320） */
.nz-line-roll {
  left: calc(50% + 46px);
  top: calc(50vh - 34px);
  overflow: clip;
  overflow-clip-margin: 30px;
  height: 1.2em;
  line-height: 1.2;
}
.nz-roll {
  display: block;
  will-change: transform;
}
.nz-roll-word {
  display: block;
  line-height: 1.2;
}

/* 竖排标签（head.css:119-129） */
.nz-hero-vertical {
  position: absolute;
  left: calc(50% - 506px);
  top: calc(50vh - 66px);
  transform: translate(-50%, -50%) rotate(-90deg);
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 14px;
  letter-spacing: 1.4px;
  color: #fff;
  white-space: nowrap;
}

/* ── 逐字 hover（head.css:326-341）── */
.nz-letter {
  display: inline-block;
  transform-origin: 50% 100%;
  transition: transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1), color 0.25s ease;
}
.nz-h-line:hover .nz-letter {
  will-change: transform;
}
.nz-letter:hover {
  transform: translateY(-0.16em) rotate(-7deg);
  color: #f4d24a;
}

/* ════ 行程进度盘（head.css:343-372）════ */
.nz-recorder {
  position: absolute;
  left: calc(50% - 229px);
  top: calc(50vh + 58px);
  transform: translate(-50%, -50%) scale(1.08);
  display: flex;
  align-items: flex-start;
  gap: 22px;
  padding: 16px;
  border-radius: 9999px;
  background: #dfdcd5;
  border: 1px solid #c9c9c9;
  box-shadow: 0 11px 9.6px 0 rgba(0, 0, 0, 0.25);
  cursor: pointer;
}
/* 拟物内斜面（head.css:359-368） */
.nz-recorder::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  pointer-events: none;
  box-shadow: inset 0 5px 7.3px 2px #f5f1ee, inset -2px -10px 2.3px 0 #b8b7b2;
}

/* 地图卷盘（等价黑胶 89px，conic-gradient 纹理） */
.nz-disk {
  position: relative;
  width: 89.13px;
  height: 89.13px;
  border-radius: 50%;
  flex-shrink: 0;
  background: repeating-conic-gradient(
    from 0deg,
    #1b1b1b 0deg 10deg,
    #2a2a2a 10deg 18deg,
    #141414 18deg 30deg
  );
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.4), 0 2px 6px rgba(0, 0, 0, 0.28);
}
.nz-disk.is-spinning {
  animation: nz-disk-spin 1.8s linear infinite;
}
@keyframes nz-disk-spin {
  to {
    transform: rotate(360deg);
  }
}
.nz-disk-face {
  position: absolute;
  inset: 30%;
  border-radius: 50%;
  background: radial-gradient(circle at 40% 35%, #f0eee9 0%, #cdcac3 100%);
  box-shadow: inset 0 1px 2px rgba(0, 0, 0, 0.25);
}
.nz-disk-cap {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 8px;
  height: 8px;
  margin: -4px 0 0 -4px;
  border-radius: 50%;
  background: #2c8a3e;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.35);
}

.nz-rec-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 171.42px;
  align-self: stretch;
}
.nz-rec-title {
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 11px;
  letter-spacing: -0.12px;
  color: #070707;
  width: 100%;
  margin: 0 0 4px;
  text-align: center;
}

/* 滑杆（head.css:266-338） */
.nz-play-slider {
  position: relative;
  width: 100%;
  height: 23.45px;
  cursor: pointer;
  touch-action: none;
}
.nz-play-slider:not(.is-scrubbing) .nz-slider-knob {
  transition: left 0.12s linear;
}
.nz-play-slider:not(.is-scrubbing) .nz-slider-fill {
  transition: width 0.12s linear;
}
.nz-play-slider.is-scrubbing {
  cursor: grabbing;
}
.nz-slider-track {
  position: absolute;
  left: 0;
  right: 0;
  top: 5.58px;
  height: 12.29px;
  border-radius: 9999px;
  background: #686360;
  box-shadow: 0 3px 1px 0 #ededed;
  overflow: hidden;
}
.nz-slider-track::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  box-shadow: inset 0 -1px 3px 0 rgba(0, 0, 0, 0.2), inset 3px 3px 3.4px 0 rgba(0, 0, 0, 0.5);
}
.nz-slider-fill {
  position: absolute;
  left: 0;
  top: 0.46px;
  height: 11.83px;
  width: 0;
  background: var(--gold, #ffda3f);
}
.nz-slider-knob {
  position: absolute;
  top: 0;
  left: 0;
  width: 23.45px;
  height: 23.45px;
  border-radius: 9999px;
  border: 1px solid #e4e5e5;
  background: radial-gradient(circle at 50% 50%, #f6f4f5 0%, #caccc9 100%);
  box-shadow: 2px 4px 4px 0 rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
}
.nz-slider-knob::after {
  content: '';
  width: 7.32px;
  height: 7.32px;
  border-radius: 9999px;
  background: linear-gradient(150deg, #fff 15.95%, #8d8b8f 70.17%);
  box-shadow: 0 1px 1.9px 0 rgba(0, 0, 0, 0.51);
}
.nz-timestamps {
  display: flex;
  justify-content: space-between;
  width: 100%;
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 10px;
  letter-spacing: -0.1px;
  color: #070707;
}

/* 播放圆钮（head.css:341-372） */
.nz-magnet {
  display: inline-flex;
  will-change: transform;
}
.nz-play-circle {
  position: relative;
  width: 41.32px;
  height: 41.32px;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
  border-radius: 50%;
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.nz-play-circle:hover {
  transform: scale(1.1);
}
.nz-play-circle:active {
  transform: scale(0.92);
}
.nz-play-circle:focus-visible {
  outline: 2px solid #070707;
  outline-offset: 2px;
}
.nz-ring {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.nz-tri {
  position: absolute;
  inset: 8.33%;
  width: 83.34%;
  height: 83.34%;
}
.nz-pause-bars {
  position: absolute;
  inset: 0;
  display: none;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.nz-pause-bars span {
  width: 4px;
  height: 14px;
  background: #fff;
  border-radius: 2px;
}
.nz-play-circle[aria-pressed='true'] .nz-tri {
  display: none;
}
.nz-play-circle[aria-pressed='true'] .nz-pause-bars {
  display: flex;
}

/* ════ 响应式 ════ */
/* 笔记本（≤1536）：标题 136px / 字距 2.7px，整体右移 61px、下移 40px（head.css:429-444） */
@media (max-width: 1536px) {
  .nz-hero-content {
    --sun-w: clamp(300px, 44vw, 680px);
  }
  .nz-h-line {
    font-size: 136px;
    letter-spacing: 2.7px;
  }
  .nz-hero-eyebrow {
    left: calc(50% - 398px);
    top: calc(50vh - 200px);
  }
  .nz-hero-vertical {
    left: calc(50% - 445px);
    top: calc(50vh - 26px);
  }
  .nz-line-one {
    left: calc(50% - 398px);
    top: calc(50vh - 192px);
  }
  .nz-line-roll {
    left: calc(50% + 59px);
    top: calc(50vh - 2px);
  }
  .nz-recorder {
    left: calc(50% - 184px);
    top: calc(50vh + 80px);
    transform: translate(-50%, -50%) scale(1);
  }
}

/* 平板（641-1100）：保持错位构图，太阳组固定 560px，整体上移 56px（head.css:1133-1151）
   备注：参考站在这一档用 JS baseHeroScale() 把整块 911px 宽的文字画布缩到 (vw-96)/911，
   这里用纯 CSS 的 zoom 复刻同一效果（Chrome/Safari 支持 zoom，Firefox 126+ 也支持）。 */
@media (min-width: 641px) and (max-width: 1100px) {
  .nz-hero-content {
    --sun-w: 560px;
  }
  .nz-h-line {
    font-size: 82px;
    letter-spacing: 1.2px;
  }
  /* 这一档收窄画布：第一行左对齐、第二行只右移 20px，两行都留在视口内 */
  .nz-line-one { left: calc(50% - 268px); top: calc(50vh - 128px); }
  .nz-line-roll { left: calc(50% + 20px); top: calc(50vh - 24px); }
  .nz-hero-eyebrow { left: calc(50% - 268px); top: calc(50vh - 162px); }
  .nz-hero-vertical { left: calc(50% - 306px); top: calc(50vh - 4px); }
  .nz-recorder { left: calc(50% - 110px); top: calc(50vh + 120px); transform: translate(-50%, -50%) scale(0.86); }
  /* 整块画布按视口等比缩放（参考站 baseHeroScale 的 CSS 复刻）。
     画布自然宽度约 1120px（标题右缘 50%+46px + 第二行长度），
     scale 作用在 .nz-hero-content 上、transform-origin 取左上，
     否则绝对定位的 50% 基准会被一起缩掉，构图整体漂移。 */
  .nz-hero {
    transform-origin: 0 0;
    scale: min(1, calc((100vw - 64px) / 1120));
    height: calc(100vh / min(1, (100vw - 64px) / 1120));
  }
  .nz-sun-group,
  .nz-cloud {
    transform: translateY(-56px);
  }
}

/* 平板窄屏（641-820）：字号再收一档 */
@media (min-width: 641px) and (max-width: 820px) {
  .nz-h-line {
    font-size: 68px;
    letter-spacing: 1px;
  }
  .nz-line-one { left: calc(50% - 224px); top: calc(50vh - 112px); }
  .nz-line-roll { left: calc(50% + 12px); top: calc(50vh - 24px); }
  .nz-hero-eyebrow { left: calc(50% - 224px); top: calc(50vh - 144px); }
  .nz-hero-vertical { left: calc(50% - 258px); top: calc(50vh - 2px); }
  .nz-recorder { left: calc(50% - 90px); top: calc(50vh + 110px); transform: translate(-50%, -50%) scale(0.78); }
}

/* 窄笔记本（1101-1300）：136px 标题 + 绝对定位在 1200 左右会顶到右边，按同一公式轻缩 */
@media (min-width: 1101px) and (max-width: 1300px) {
  .nz-h-line {
    font-size: 118px;
    letter-spacing: 2.3px;
  }
  .nz-line-one { left: calc(50% - 372px); top: calc(50vh - 172px); }
  .nz-line-roll { left: calc(50% + 42px); top: calc(50vh - 34px); }
  .nz-hero-eyebrow { left: calc(50% - 372px); top: calc(50vh - 208px); }
  .nz-hero-vertical { left: calc(50% - 414px); top: calc(50vh - 20px); }
  .nz-recorder { left: calc(50% - 168px); top: calc(50vh + 92px); transform: translate(-50%, -50%) scale(0.95); }
}

/* 手机（≤640）：flatten 成居中 flex 纵排（head.css:1166-1216 + 1218-1262） */
@media (max-width: 640px) {
  .nz-hero-content {
    display: flex;
    flex-direction: column;
    justify-content: flex-start;
    padding: calc(clamp(258px, 66vw, 340px) * 0.5 + 96px) 24px 124px 64px;
    --sun-w: clamp(258px, 66vw, 340px);
    --col-pad-l: 64px;
  }
  .nz-sun-group,
  .nz-cloud {
    transform: translateY(70px);
  }
  .nz-hero-eyebrow {
    position: relative;
    left: auto;
    top: auto;
    padding-left: 0;
    transform: none;
    font-size: 13px;
    z-index: 2;
    margin-bottom: 22px;
  }
  .nz-hero-h1 {
    position: relative;
    z-index: 2;
    display: block;
  }
  .nz-h-line {
    position: static;
    display: block;
    font-size: clamp(46px, 14.5vw, 120px);
    letter-spacing: 0;
    line-height: 1;
    white-space: nowrap;
  }
  .nz-line-one {
    white-space: normal;
  }
  .nz-line-roll {
    height: 1.2em;
    line-height: 1;
  }
  .nz-roll-word {
    line-height: 1;
  }
  .nz-hero-vertical {
    display: block;
    position: absolute;
    top: 50%;
    left: calc(8px - var(--col-pad-l));
    transform: translateY(-50%);
    writing-mode: vertical-rl;
    z-index: 2;
    font-size: 12px;
    letter-spacing: 2px;
    opacity: 0.75;
  }
  .nz-recorder {
    position: relative;
    left: auto;
    top: auto;
    transform: scale(0.66);
    transform-origin: top left;
    align-self: flex-start;
    width: fit-content;
    margin: clamp(16px, 3vh, 40px) 0 0;
    z-index: 2;
  }
}

@media (max-width: 360px) {
  .nz-recorder {
    transform: scale(0.58);
  }
}

@media (prefers-reduced-motion: reduce) {
  .nz-letter {
    transition: none;
  }
  .nz-disk.is-spinning {
    animation: none;
  }
}

body.night .nz-sun-group {
  opacity: 0;
  transition: opacity 0.45s ease;
}

/* 〈夜〉夜空接管：参考站夜间是深蓝渐变 + 星/月，云絮退场（body.night 由 theme.css 重指 --hero-from/--hero-to） */
body.night .nz-hero-cirrus {
  opacity: 0.12;
  mix-blend-mode: screen;
}
body.night .nz-hero-noise {
  opacity: 0.14;
  mix-blend-mode: overlay;
}


</style>
