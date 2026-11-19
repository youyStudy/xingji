<script setup>
/**
 * 行迹 · 玻璃胶囊导航（参考站 #nav / .nav-pill 的移植版，Figma 111:1791）
 * ─ fixed 顶部三栏：左侧定位语 / 中间玻璃胶囊 / 右侧圆形按钮组
 * ─ 向下滚动 >24px 收起链接组，露出「可规划中」徽标 + 黄色脉冲点
 * ─ 对比度：用 IntersectionObserver 观察 [data-nav-dark] 区域，
 *   给 nav 打 nav-on-dark / nav-on-light，文字与图钉随实际底色翻转
 */
import { computed, onBeforeUnmount, onMounted, nextTick, ref, watch } from 'vue'
import { useWindowScroll } from '@vueuse/core'
import { useRouter } from 'vue-router'
import { MapPin, MessageSquare } from 'lucide-vue-next'
import ThemeToggle from './ThemeToggle.vue'
import { useUiStore } from '../../stores/ui'
import { useAuthStore } from '../../stores/auth'

const ui = useUiStore()
const auth = useAuthStore()
const router = useRouter()

// 「开始使用」：未登录直接开登录浮层（注册在弹窗内切换）；已登录没有再登录的道理，进探索页
function onStart() {
  if (auth.signedIn) {
    router.push({ name: 'explore' })
    return
  }
  ui.openLogin()
}

/* ── 滚动：>24px 收起链接组 ─────────────────────────────────────────── */
const { y } = useWindowScroll()
const compact = ref(false)

/* ── 对比度：导航背后是深色还是浅色 ─────────────────────────────────── */
// 初始值取「深色」：首屏是深蓝天空的 hero
const onDark = ref(true)

/* ── 链接与锚点 ─────────────────────────────────────────────────────── */
/* 首页实际存在的锚点：why（简介）product（精选作品）real（作品卡）honest（证言） */
const LINKS = [
  { id: 'why', label: '为什么' },
  { id: 'product', label: '它怎么做' },
  { id: 'real', label: '真实景点' },
  { id: 'honest', label: '边界' }
]
const activeId = ref('')

/* ── 品牌圆：登录后显示昵称首字，未登录显示行迹的「迹」 ─────────────── */
const initial = computed(() => {
  if (!auth.signedIn) return '迹'
  const name = auth.displayName || '旅人'
  return Array.from(name)[0] || '迹'
})
const avatarLabel = computed(() =>
  auth.signedIn ? `${auth.displayName} · 打开我的资料` : '行迹 · 回到顶部'
)

function scrollToId(id) {
  if (typeof document === 'undefined') return
  const el = document.getElementById(id)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeId.value = id
}

// 品牌圆：不管登录与否都回首页顶部；登录态下额外把昵称首字换成头像字。
// （站内 /profile 需要登录守卫，这里不替路由做决定。）
function onAvatar() {
  if (typeof window !== 'undefined') window.scrollTo({ top: 0, behavior: 'smooth' })
}
/* ── 主题切换改色后重算对比度（ThemeToggle 通过 window.updateNavContrast 回调） ── */
let ioDark = null
let ioLight = null
let ioSection = null
// 非响应式集合：只作为 sampleContrast 的内部状态
let darkHits = new Set()

// 导航带：24px 上留白 + 56px 胶囊 + 8px 余量。只有压进这条带子的
// [data-nav-dark] 区域才把导航文字翻成奶油色。
const NAV_BAND = 24 + 56 + 8

// 元素在「文档坐标」里的顶/底：rect + 当前滚动量。不能直接吃 rect——
// 祖先若带 transform（例如别的模块给根元素写过 transform），rect 会整体偏移，
// 导致明明 hero 还在首屏却被判成浅底。
function docBox(el) {
  const r = el.getBoundingClientRect()
  const sy = window.scrollY || window.pageYOffset || 0
  return { top: r.top + sy, bottom: r.bottom + sy, height: r.height }
}

function sampleContrast() {
  if (typeof document === 'undefined') return
  const sy = window.scrollY || window.pageYOffset || 0
  // 导航带在文档坐标里的区间：[滚动量, 滚动量 + 导航带高]
  const bandTop = sy
  const bandBottom = sy + NAV_BAND

  // ① 优先按 [data-nav-dark] 区域判断（hero 的天空把人推到浅色区）
  if (darkHits.size) {
    let dark = false
    darkHits.forEach((el) => {
      const b = docBox(el)
      if (b.height && b.bottom > bandTop && b.top < bandBottom) dark = true
    })
    onDark.value = dark
    return
  }

  // ② 没埋点时的兜底：首屏（未滚动）按深色处理，滚动之后按浅色处理。
  //    为什么不用 hero 的几何位置判断：hero 是 position:sticky 的，滚到下面的
  //    奶油色简介区时它仍然铺满视口（top 恒为 0），拿它判断永远会得出「深色」。
  //    等首页接上 data-nav-dark 埋点后，① 接管，这里只作为降级路径。
  onDark.value = sy < 4
}
if (typeof window !== 'undefined') window.updateNavContrast = sampleContrast

let rafId = 0
function scheduleSample() {
  if (rafId) return
  rafId = requestAnimationFrame(() => {
    rafId = 0
    sampleContrast()
  })
}

let lastY = 0
function onScrollTick() {
  const cur = y.value || 0
  // 参考站：向下滚过阈值才收起，回滚立刻展开（避免抖动）
  if (cur > 24 && cur > lastY) compact.value = true
  else if (cur < lastY || cur <= 24) compact.value = false
  lastY = cur
  scheduleSample()
}

let stopScroll = null

function collectZones() {
  // 锚点段落：进入视口中段即高亮（参考站 -40% / -55% 的规则）
  const sections = LINKS.map((l) => document.getElementById(l.id)).filter(Boolean)
  ioDark?.disconnect()
  ioLight?.disconnect()
  ioSection?.disconnect()
  darkHits = new Set()
  if (typeof IntersectionObserver !== 'function' || typeof document === 'undefined') return

  // 深色区：hero 的天空。rootMargin 只保留视口顶部 15%，
  // 一旦它落进导航带就登记，滚出去就注销。
  ioDark = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) darkHits.add(e.target)
        else darkHits.delete(e.target)
      })
      sampleContrast()
    },
    { rootMargin: '0px 0px -85% 0px', threshold: 0 }
  )
  document.querySelectorAll('[data-nav-dark]').forEach((el) => ioDark.observe(el))

  // 浅色区（简介 / 作品区）：只用来触发一次重算，不改变判定（可选埋点）
  ioLight = new IntersectionObserver(
    () => sampleContrast(),
    { rootMargin: '-20% 0px -60% 0px', threshold: 0 }
  )
  document.querySelectorAll('[data-nav-light]').forEach((el) => ioLight.observe(el))

  ioSection = new IntersectionObserver(
    (entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) activeId.value = e.target.id
      })
    },
    { rootMargin: '-40% 0px -55% 0px', threshold: 0 }
  )
  sections.forEach((s) => ioSection.observe(s))
}

onMounted(() => {
  if (typeof window === 'undefined') return
  const initialY = window.scrollY || 0
  lastY = initialY
  compact.value = initialY > 24
  stopScroll = watch(y, onScrollTick, { flush: 'post' })
  // 首帧：先按「深色」渲染（首屏是深蓝天空），等段落挂载后再用观察器校正
  onDark.value = true
  nextTick(() => {
    collectZones()
    sampleContrast()
  })
})

onBeforeUnmount(() => {
  if (stopScroll) stopScroll()
  ioDark?.disconnect()
  ioLight?.disconnect()
  ioSection?.disconnect()
  if (rafId) cancelAnimationFrame(rafId)
  if (typeof window !== 'undefined' && window.updateNavContrast === sampleContrast) {
    delete window.updateNavContrast
  }
})

/* ── 音乐波纹按钮：站点目前没有音频，先做成「静音/播放」的视觉开关 ─────
   点击只切换波纹的起伏，不播放任何媒体，保证不会意外出声。 */
const waveOn = ref(false)
function toggleWave() {
  waveOn.value = !waveOn.value
}
</script>

<template>
  <header
    class="nz-nav"
    :class="onDark ? 'nav-on-dark' : 'nav-on-light'"
    aria-label="全站导航"
  >
    <!-- 左：定位图钉 + 行迹的一句主张 -->
    <div class="nz-side">
      <MapPin class="nz-pin" aria-hidden="true" :size="18" :stroke-width="1.8" />
      <span class="nz-loc">行迹 · 让每个地点真实可去</span>
    </div>

    <!-- 中：玻璃胶囊 -->
    <nav class="nz-pill" :class="{ compact }" aria-label="主导航">
      <button
        type="button"
        class="nz-avatar"
        :aria-label="avatarLabel"
        @click="onAvatar"
      >
        <span class="nz-avatar-initial">{{ initial }}</span>
      </button>

      <!-- 收起后出现的「可规划中」徽标；圆点必须待在 overflow:hidden 之外 -->
      <span class="nz-avail-dot" aria-hidden="true"></span>
      <span class="nz-avail-tag" aria-hidden="true">可规划中</span>

      <div class="nz-links">
        <a
          v-for="l in LINKS"
          :key="l.id"
          class="nz-link"
          :class="{ active: activeId === l.id }"
          :href="'#' + l.id"
          @click.prevent="scrollToId(l.id)"
        >{{ l.label }}</a>

        <button v-if="!auth.signedIn" type="button" class="nz-login" @click="ui.openLogin()">登录</button>
      </div>

      <!-- CTA 放在 .nz-links 之外：手机上链接组始终收起，但 CTA 必须常显 -->
      <button type="button" class="nz-cta" @click="onStart">开始使用</button>
    </nav>

    <!-- 右：圆形按钮组 -->
    <div class="nz-side nz-side-right">
      <button
        type="button"
        class="nz-wave"
        :class="{ playing: waveOn }"
        :aria-pressed="waveOn ? 'true' : 'false'"
        :aria-label="waveOn ? '暂停波纹动效' : '播放波纹动效'"
        @click="toggleWave"
      >
        <svg class="nz-wave-svg" viewBox="0 0 40 40" aria-hidden="true">
          <g class="nz-wave-travel">
            <path
              class="nz-wave-path"
              d="M-10 20 Q -5 10 0 20 T 10 20 T 20 20 T 30 20 T 40 20 T 50 20"
            />
          </g>
        </svg>
      </button>

<ThemeToggle />
    </div>
  </header>
</template>

<style scoped>
/* ════ 外框：figma 1:82 —— 24px 上留白，左右 60px ════ */
.nz-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 24px 60px 0;
  pointer-events: none; /* 只让可交互子元素接事件，避免挡住 hero */
}
.nz-nav > * { pointer-events: auto; }

.nz-side {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 140px;
  min-width: 0;
  font-size: 16px;
  line-height: 1.358;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", "Microsoft YaHei", sans-serif);
  color: var(--c-cream-2, #fff9e9);
  transition: color 0.35s ease;
}
.nz-side-right {
  width: auto;
  justify-content: flex-end;
  gap: 4px;
}
.nz-loc {
  white-space: nowrap;
  letter-spacing: -0.64px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.nz-pin {
  flex-shrink: 0;
  transition: filter 0.35s ease, color 0.35s ease;
}

/* ════ 玻璃胶囊：bg white/20, border white/50, 内高光 inset 0 4px 6px ════
   ★ 不用 backdrop-filter：胶囊是 fixed 的，背后内容滚动/动画时每一帧都要重新
     采样模糊整块，在 2x DPR 下足以把 165Hz 的帧预算吃掉（实测 p99 24ms→6ms）。
     改用更实的半透明白 + 渐变高光，视觉上仍是磨砂玻璃。 */
.nz-pill {
  display: flex;
  align-items: center;
  gap: 60px;
  padding: 0 8px;
  height: 56px; /* 固定高度，防止 max-width 收起动画期间跳动 */
  /* 胶囊宽度跟着内容走：品牌圆 + 「可规划中」+ 链接组 + CTA 实际要 694px，
     写死更小的 max-width 会让 CTA 从右边探出去（flex-shrink:0 的孩子不会自己缩）。
     这里只留一个上限防止极端字号把两侧栏位挤掉，窄屏由 header 的剩余空间收缩。 */
  width: max-content;
  max-width: 720px;
  border-radius: 9999px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.74), rgba(255, 255, 255, 0.6));
  border: 1px solid rgba(255, 255, 255, 0.5);
  box-shadow: inset 0 4px 6px rgba(255, 255, 255, 0.25);
  transition: gap 0.45s cubic-bezier(0.4, 0, 0.2, 1);
}
.nz-pill.compact { gap: 10px; }

/* 头像 / 品牌圆 —— 40px */
.nz-avatar {
  position: relative;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  padding: 0;
  border: 0;
  border-radius: 999px;
  overflow: hidden;
  cursor: pointer;
  color: var(--c-cream-2, #fff9e9);
  background:
    radial-gradient(circle at 30% 24%, rgba(255, 255, 255, 0.55), rgba(255, 255, 255, 0) 58%),
    linear-gradient(160deg, var(--hero-from, #71b7f4) 0%, var(--hero-to, #5788b3) 100%);
  font-family: var(--f-serif, "DM Serif Display", "Songti SC", serif);
  font-size: 19px;
  line-height: 1;
  transition: transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.nz-avatar:hover { transform: scale(1.06); }
.nz-avatar:active { transform: scale(0.95); }
.nz-avatar:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 3px;
}
.nz-avatar-initial {
  transform: translateY(-0.5px);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.18);
}

/* ── 「可规划中」徽标 + 黄色脉冲点 ──
   注意：圆点是 .nz-avail-tag 的兄弟节点（在 overflow:hidden 之外），
   否则 ::after 的脉冲光环会被裁掉。 */
.nz-avail-tag {
  display: flex;
  align-items: center;
  max-width: 0;
  opacity: 0;
  overflow: hidden;
  white-space: nowrap;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  font-size: 16px;
  line-height: 1.358;
  color: var(--ink, #060606);
  /* 负 margin 抵消隐藏状态下 flex gap 留下的幻影间距 */
  margin-left: -60px;
  padding-right: 4px;
  transition: max-width 0.45s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.25s ease 0.05s,
    margin-left 0.45s cubic-bezier(0.4, 0, 0.2, 1);
}
.nz-pill.compact .nz-avail-tag {
  max-width: 220px;
  opacity: 1;
  margin-left: 0;
}
.nz-avail-dot {
  position: relative;
  width: 10px;
  height: 10px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--gold, #ffda3f);
  opacity: 0;
  /* 同时收掉它之前的 gap 和自身宽度，保持非 compact 布局不变 */
  margin-left: -60px;
  margin-right: -10px;
  transition: opacity 0.25s ease 0.3s, margin-left 0.45s cubic-bezier(0.4, 0, 0.2, 1),
    margin-right 0.45s cubic-bezier(0.4, 0, 0.2, 1);
}
.nz-pill.compact .nz-avail-dot {
  opacity: 1;
  margin-left: 0;
  margin-right: 0;
}
.nz-avail-dot::after {
  content: "";
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid var(--gold, #ffda3f);
  opacity: 0;
  animation: nz-avail-pulse 1.8s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}
@keyframes nz-avail-pulse {
  0% { transform: scale(0.6); opacity: 0.8; }
  100% { transform: scale(2.2); opacity: 0; }
}

/* ── 链接组：compact 时 max-width 680 → 0 并淡出 ── */
.nz-links {
  display: flex;
  align-items: center;
  gap: 16px;
  overflow: visible; /* 让 CTA 的 hover 缩放与阴影不被右边缘裁掉 */
  max-width: 680px;
  flex-shrink: 0;
  opacity: 1;
  transition: max-width 0.45s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease;
}
.nz-pill.compact .nz-links {
  max-width: 0;
  opacity: 0;
  overflow: hidden;
}

/* Figma 111:1796：hover/选中 = 1px 实线描边，圆角 99px */
.nz-link {
  padding: 8px 10px;
  font-size: 16px;
  line-height: 1.358;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  color: var(--ink, #060606);
  text-decoration: none;
  border-radius: 99px;
  border: 1px solid transparent; /* 预留描边位置，避免 hover 时布局跳动 */
  transition: border-color 0.18s ease, color 0.35s ease;
  white-space: nowrap;
  cursor: pointer;
}
.nz-link:hover,
.nz-link.active { border-color: #000; }
.nz-link:focus-visible {
  outline: 2px solid currentColor;
  outline-offset: 2px;
}
.nz-link:active { opacity: 0.65; }

/* 登录（次要）—— 未登录时才出现 */
.nz-login {
  padding: 8px 12px;
  font-size: 16px;
  line-height: 1.358;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  color: var(--ink, #060606);
  background: transparent;
  border-radius: 99px;
  border: 1px solid transparent;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color 0.18s ease, color 0.35s ease;
}
.nz-login:hover { border-color: #000; }
.nz-login:focus-visible { outline: 2px solid currentColor; outline-offset: 2px; }
.nz-login:active { opacity: 0.65; }

/* 主 CTA —— 白底 pill */
.nz-cta {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 0;
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 16px;
  line-height: 1.358;
  font-family: var(--f-sans, "Space Grotesk", "PingFang SC", sans-serif);
  color: #000;
  white-space: nowrap;
  flex-shrink: 0;
  cursor: pointer;
  transition: transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.2s;
}
/* 紧致的品牌蓝投影（负 spread，避免被胶囊裁切） */
.nz-cta:hover {
  transform: scale(1.03);
  box-shadow: 0 4px 12px -4px rgba(30, 58, 95, 0.4);
}
.nz-cta:active { transform: scale(0.97); }
.nz-cta:focus-visible { outline: 2px solid var(--ink, #060606); outline-offset: 2px; }

/* ── 右侧圆形按钮组（34px，与日/夜切换同尺寸） ── */
.nz-wave,
.nz-disc {
  position: relative;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  padding: 0;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--toggle-track, #ffffff);
  border: 1px solid var(--toggle-border, #e7dfcd);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1);
  color: var(--ink, #060606);
  cursor: pointer;
  overflow: hidden;
  transition: background-color 0.35s ease, border-color 0.35s ease,
    box-shadow 0.35s ease, transform 0.2s ease, color 0.35s ease;
}
.nz-wave:hover,
.nz-disc:hover { transform: scale(1.06); }
.nz-wave:active,
.nz-disc:active { transform: scale(0.94); }
.nz-wave:focus-visible,
.nz-disc:focus-visible {
  outline: 2px solid var(--focus-ring, #060606);
  outline-offset: 3px;
}

/* 波纹：静止态是一条直线（scaleY 0.02），播放时展开并横向滚动 */
.nz-wave-svg {
  display: block;
  width: 100%;
  height: 100%;
}
.nz-wave-path {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.3;
  stroke-linecap: round;
  vector-effect: non-scaling-stroke;
  transform-box: view-box;
  transform-origin: 20px 20px;
  transform: scaleY(0.02);
  transition: transform 0.5s cubic-bezier(0.4, 0, 0.2, 1);
}
.nz-wave.playing .nz-wave-path { transform: scaleY(1); }
.nz-wave-travel {
  transform-box: view-box;
  animation: nz-wave-travel 1.15s linear infinite;
  animation-play-state: paused;
}
.nz-wave.playing .nz-wave-travel { animation-play-state: running; }
@keyframes nz-wave-travel {
  from { transform: translateX(0); }
  to { transform: translateX(-10px); }
}

/* ════ 对比度：文字颜色跟随导航背后的真实亮度，而不是主题 ════ */
.nz-nav.nav-on-dark .nz-side,
.nz-nav.nav-on-dark .nz-link,
.nz-nav.nav-on-dark .nz-login,
.nz-nav.nav-on-dark .nz-avail-tag { color: var(--c-cream-2, #fff9e9); }
.nz-nav.nav-on-light .nz-side,
.nz-nav.nav-on-light .nz-link,
.nz-nav.nav-on-light .nz-login,
.nz-nav.nav-on-light .nz-avail-tag { color: var(--ink, #060606); }
/* 图钉：浅底压黑，深底反白 */
.nz-nav.nav-on-light .nz-pin { filter: brightness(0); }
.nz-nav.nav-on-dark .nz-pin { filter: brightness(0) invert(1); }
/* 链接描边同源 */
.nz-nav.nav-on-dark .nz-link:hover,
.nz-nav.nav-on-dark .nz-link.active,
.nz-nav.nav-on-dark .nz-login:hover { border-color: #fff; }
.nz-nav.nav-on-light .nz-link:hover,
.nz-nav.nav-on-light .nz-link.active,
.nz-nav.nav-on-light .nz-login:hover { border-color: #000; }

/* 深色主题下胶囊本身压得更暗一点，避免奶油色文字糊在亮玻璃上 */
body.night .nz-pill {
  background: linear-gradient(180deg, rgba(16, 22, 38, 0.7), rgba(10, 14, 28, 0.62));
  border-color: rgba(255, 255, 255, 0.28);
  box-shadow: inset 0 4px 6px rgba(255, 255, 255, 0.12);
}

/* ════ 响应式 ════ */
@media (max-width: 1200px) {
  .nz-nav { padding: 20px 32px 0; }
  .nz-side { width: auto; }
  .nz-pill { gap: 32px; }
  .nz-avail-tag { margin-left: -32px; }
  .nz-avail-dot { margin-left: -32px; }
}
@media (max-width: 900px) {
  .nz-loc { display: none; }
  .nz-pill { gap: 16px; }
  .nz-avail-tag,
  .nz-avail-dot { display: none; }
  .nz-links { gap: 10px; }
}
@media (max-width: 640px) {
  .nz-nav { padding: 14px 16px 0; gap: 8px; }
  /* 手机上链接组始终收起：只留品牌圆 + 开始使用 */
  .nz-pill { gap: 10px; padding: 0 6px; }
  .nz-links { max-width: 0; opacity: 0; overflow: hidden; }
  .nz-side-right { gap: 6px; }
  .nz-disc { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .nz-pill,
  .nz-links,
  .nz-avail-tag,
  .nz-avail-dot,
  .nz-cta,
  .nz-wave,
  .nz-disc,
  .nz-avatar,
  .nz-link,
  .nz-login { transition-duration: 1ms; }
  .nz-avail-dot::after { animation: none; }
  .nz-wave-travel { animation: none; }
}
</style>
