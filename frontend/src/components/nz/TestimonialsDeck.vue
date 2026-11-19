<script setup>
/*
 * TestimonialsDeck —— 证言（参考站 .testimonials + 卡片扇出）
 * 出处：inline-head.css:679-701（section）、756-780（草浪）、792-800（land）、
 *       811-845（eyebrow / 标题）、847-889（--spread 扇出公式）、891-957（卡片内部）、
 *       landing.js:676-732（spread 驱动：SCRUB=460、smoothstep）、
 *       954-957（.t-scrub 占位 620px）。
 * 背景是自绘 SVG 山丘/树林（不使用参考站 willow/grass 图片）。
 * 内容为「设计原则摘录」，不虚构用户量或名人证言。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { Scroll, useScrollFrame } from '../../composables/useScroll'
import { useTilt } from '../../composables/useTilt'

const sectionEl = ref(null)
const deckEl = ref(null)
const cardsEl = deckEl
const spacerEl = ref(null)
const flowTop = ref(null)

/* 三张玻璃卡跟随指针轻微 3D 倾斜。
   ★ 关键：倾斜作用在内层 .nz-t-face 上——外层 .nz-t-card 的 transform 是
     用 --pos×--spread 做扇出位移的（CSS 变量驱动），GSAP 一写 transform 会冲掉扇出。
     所以把玻璃视觉挪到 face，face 没有别的 transform，GSAP 可放心接管。 */
useTilt(deckEl, { item: '.nz-t-face', maxDeg: 8, perspective: 1000 })

/* 3 张卡：设计原则摘录（诚实写法，不冒充实名证言） */
const CARDS = [
  {
    pos: -1,
    mark: '先核验，再给出',
    quote:
      '一条路线最先要回答的不是"好不好玩"，而是"能不能去"。行迹会先把它拆成一个个具体地点，再逐个拿到真实地图里确认：名字对得上、地址是真的、当天去得成。对不上的那一个会被标红，而不是被悄悄塞进日程。只有确认过的地方，才会留在你的行程里。',
    name: '设计原则 01',
    role: '关于核验'
  },
  {
    pos: 0,
    mark: '行程是一棵树，不是一张表',
    quote:
      '想法先长成一颗可编辑的行程树：城市、天数、每天的去处与动线。你可以只改其中一枝——换掉一天的午饭、把某个下午挪到明天——其余部分不必推倒重来。改过的地方会被重新核验一次，改坏的会立刻告诉你，而不是等到出发那天才发现。',
    name: '设计原则 02',
    role: '关于结构'
  },
  {
    pos: 1,
    mark: '留出余量，才算能走',
    quote:
      '把等位、堵车、爬坡、天黑的时刻都算进行程里，剩下的时间才真的属于你。可执行的行程不该是排得最满的那一份。行迹会在每天留出缓冲，也会在地点之间标出步行与乘车的真实时长，让"来得及"这三个字不是一句客气话。',
    name: '设计原则 03',
    role: '关于节奏'
  }
]

const SCRUB = 460 // 扇开所需滚动距离（landing.js:684）
const ease = (t) => {
  t = Math.max(0, Math.min(1, t))
  return t * t * (3 - 2 * t)
}

const opened = ref(false)
const spread = ref(0)
function setSpread(p) {
  // 值没变就不写 CSS 变量：--spread 一变，三张玻璃卡的 transform 全部失效重算
  if (p === spread.value) return
  spread.value = p
  if (cardsEl.value) cardsEl.value.style.setProperty('--spread', p.toFixed(4))
}

let desktopMq = null
function updateSpread() {
  const section = sectionEl.value
  const cards = cardsEl.value
  if (!section || !cards) return
  // 只在桌面扇出（landing.js:707）
  if (desktopMq == null) desktopMq = window.matchMedia('(min-width: 1101px)')
  if (!desktopMq.matches) {
    if (spread.value !== 0) {
      spread.value = 0
      cards.style.removeProperty('--spread')
    }
    return
  }
  const rect = Scroll.rect(section)
  if (!rect.height) return
  // 离屏不写样式；回到视口附近时按流位置一次补齐（扇出只增不减）
  if (rect.bottom < -200 || rect.top > Scroll.vh() + 200) return
  const sy = Scroll.y()
  // 区块的"流位置"固定不变（sticky 只影响绘制位置），直接一次量好即可，
  // 不再依赖 rect.top > 0（吸顶后 rect.top 恒为 0，旧写法永远不更新）
  if (flowTop.value == null) {
    const prevPos = section.style.position
    const prevTop = section.style.top
    section.style.position = 'static'
    section.style.top = 'auto'
    flowTop.value = section.getBoundingClientRect().top + sy
    section.style.position = prevPos
    section.style.top = prevTop
    Scroll.kick()
    return
  }
  // 参考站：只在区块吸顶之后才开始扇开（past = 滚动位置 − 区块流位置），
  // 全开需 SCRUB=460px，开到头就保持
  const past = sy - flowTop.value
  setSpread(Math.max(spread.value, ease(past / SCRUB)))
}

let reduce = false
onMounted(() => {
  reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduce) setSpread(1)
  // 尺寸变化后 flowTop 失效
  const inv = () => {
    flowTop.value = null
    Scroll.kick()
  }
  window.addEventListener('resize', inv)
  window.addEventListener('load', inv)
  onBeforeUnmount(() => {
    window.removeEventListener('resize', inv)
    window.removeEventListener('load', inv)
  })
  updateSpread()
  // 三张卡逐张入场：sticky 吸顶后 IntersectionObserver 的比值不可靠，
  // 这里直接用「组件挂载 + 首次进入视口附近」触发，双保险
  const openDeck = () => {
    if (cardsEl.value) cardsEl.value.classList.add('is-in')
  }
  // 无条件在挂载后放行（入场动画靠 transition-delay 错峰，不依赖滚动位置判断）
  requestAnimationFrame(() => requestAnimationFrame(openDeck))
  setTimeout(openDeck, 300)
  // 移动端轮播定位到第一张真实卡
  requestAnimationFrame(() => {
    const track = deckEl.value
    if (track && track.children.length) {
      const first = track.children[0]
      track.scrollLeft = first.offsetLeft - (track.clientWidth - first.offsetWidth) / 2
    }
  })
})

useScrollFrame(updateSpread)
</script>

<template>
  <section ref="sectionEl" class="nz-testimonials" id="honest">
    <!-- 自绘「山丘 / 树林」背景（3–4 层波 + 渐变），替代参考站的柳树画 -->
    <div class="nz-t-willow" aria-hidden="true">
      <svg class="nz-scene" viewBox="0 0 1440 1010" preserveAspectRatio="xMidYMax slice">
        <defs>
          <linearGradient id="nzSky" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="var(--surface-raised, #eee8d2)" />
            <stop offset="65%" stop-color="var(--scene-mid, #dfe6cf)" />
            <stop offset="100%" stop-color="var(--scene-far, #c8d5b4)" />
          </linearGradient>
          <linearGradient id="nzForest" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="var(--scene-forest-0, #9cb47a)" />
            <stop offset="100%" stop-color="var(--scene-forest-1, #6f8c53)" />
          </linearGradient>
          <linearGradient id="nzMeadow" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="var(--scene-meadow-0, #87a25f)" />
            <stop offset="100%" stop-color="var(--scene-meadow-1, #5f7a3f)" />
          </linearGradient>
        </defs>
        <rect width="1440" height="1010" fill="url(#nzSky)" />
        <!-- 远山 -->
        <path
          d="M0 470 C 150 400 300 452 460 418 C 620 384 760 430 920 404 C 1080 378 1260 424 1440 392 L1440 1010 L0 1010 Z"
          fill="var(--scene-hill, #b9c9a4)"
          opacity=".85"
        />
        <!-- 树林层：一排起伏树冠 -->
        <path
          d="M0 540 C 60 470 120 540 180 492 C 240 444 300 540 360 500 C 420 460 480 540 540 496
             C 600 452 660 540 720 500 C 780 460 840 540 900 496 C 960 452 1020 540 1080 500
             C 1140 460 1200 540 1260 496 C 1320 452 1380 540 1440 500 L1440 1010 L0 1010 Z"
          fill="url(#nzForest)"
        />
        <!-- 草甸 -->
        <path
          d="M0 720 C 160 672 300 736 460 712 C 620 688 760 742 920 716 C 1080 690 1240 742 1440 706 L1440 1010 L0 1010 Z"
          fill="url(#nzMeadow)"
        />
        <!-- 草纹 -->
        <g stroke="var(--scene-grass, #4e6a35)" stroke-width="2" opacity=".35">
          <path d="M60 800 q6 -26 2 -50 M96 812 q8 -30 3 -56 M180 790 q6 -22 2 -44 M300 820 q7 -28 3 -52" />
          <path d="M620 806 q6 -24 2 -48 M700 826 q8 -30 3 -56 M980 800 q6 -22 2 -46 M1180 818 q7 -28 3 -54" />
        </g>
      </svg>
    </div>

    <!-- 顶部波浪草边（.t-wavy 等价）：mask 的 data-URI 波浪 -->
    <div class="nz-t-wavy" aria-hidden="true"></div>
    <!-- 底部土地带（.t-land 等价：bottom -96px） -->
    <div class="nz-t-land" aria-hidden="true"></div>

    <div class="nz-t-inner">
      <p class="nz-t-eyebrow">设计原则摘录</p>
      <h2 class="nz-t-heading">它按什么做取舍</h2>

      <div ref="deckEl" class="nz-t-cards" :class="{ 'is-reduced': reduce }">
        <figure
          v-for="(c, i) in CARDS"
          :key="i"
          class="nz-t-card"
          :style="{ '--pos': c.pos }"
        >
          <div class="nz-t-face">
            <span class="nz-t-quoteicon" aria-hidden="true">
              <svg viewBox="0 0 82 82">
                <path d="M8 50 C8 26 20 12 40 6 L44 16 C30 22 24 30 23 38 C31 38 37 45 37 55 C37 65 30 72 20 72 C13 72 8 63 8 50 Z" fill="currentColor" />
                <path d="M50 50 C50 26 62 12 82 6 L86 16 C72 22 66 30 65 38 C73 38 79 45 79 55 C79 65 72 72 62 72 C55 72 50 63 50 50 Z" transform="translate(-6 0)" fill="currentColor" />
              </svg>
            </span>
            <p class="nz-t-quote">{{ c.quote }}</p>
            <hr class="nz-t-divider" />
            <div class="nz-t-author">
              <div class="nz-t-authorrow">
                <span class="nz-t-name">{{ c.name }}</span>
              </div>
              <span class="nz-t-role">{{ c.role }}</span>
            </div>
          </div>
        </figure>
      </div>
    </div>
  </section>

  <!-- scrub 占位（.t-scrub：620px），提供扇出所需滚动距离（head.css:954-957） -->
  <div ref="spacerEl" class="nz-t-scrub" aria-hidden="true"></div>
</template>

<style scoped>
/* .testimonials（head.css:680-701） */
.nz-testimonials {
  /* sticky 吸顶后标题会被固定栏裁掉：内容整体下移一个导航带高度。
     导航带 = 24px 上留白 + 56px 胶囊 = 80px，再留 16px 呼吸 → 96px。
     实测（1600×1000）：不加这条时吸顶期间标题 top=9、eyebrow top=-7，
     标题压在胶囊底下、eyebrow 被视口上沿切掉，整整 620px 的吸顶段都看不到标题。 */
  --nav-off: 96px;
  position: sticky;
  top: 0;
  z-index: 11;
  /* 参考站用它把入场延后 755px（作品区底 + 755）。但作品区是吸底的，这段间隔
     期间屏幕上一个东西都不动 → 连续滚 3/4 屏画面冻住，手感就是"卡"。
     留 220px 足够看到吸底 + 压暗，然后立刻接上"被证言区盖上来"的动作。 */
  margin-top: 220px;
  height: max(1010px, 100vh);
  background: var(--surface-raised, #eee8d2);
  overflow-x: clip;
  overflow-y: visible;
}

.nz-t-willow {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}
.nz-scene {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
}

/* 草浪（.t-wavy 等价）：mask 波浪，随主题取 --surface-raised（site.css:502-513 思路） */
.nz-t-wavy {
  position: absolute;
  top: -6.6vw;
  left: 0;
  width: 100%;
  height: 13.75vw;
  z-index: 1;
  pointer-events: none;
  background: var(--scene-wavy, #eee8d2);
  -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1440 198' preserveAspectRatio='none'%3E%3Cpath fill='%23fff' d='M0 12 L60 36 C120 22 180 64 260 48 C340 32 400 66 480 52 C560 38 620 8 700 26 C780 44 840 70 920 56 C1000 42 1060 10 1140 24 C1220 38 1280 66 1360 50 C1400 42 1420 20 1440 14 L1440 198 L0 198 Z'/%3E%3C/svg%3E")
    0 0 / 100% 100% no-repeat;
  mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1440 198' preserveAspectRatio='none'%3E%3Cpath fill='%23fff' d='M0 12 L60 36 C120 22 180 64 260 48 C340 32 400 66 480 52 C560 38 620 8 700 26 C780 44 840 70 920 56 C1000 42 1060 10 1140 24 C1220 38 1280 66 1360 50 C1400 42 1420 20 1440 14 L1440 198 L0 198 Z'/%3E%3C/svg%3E")
    0 0 / 100% 100% no-repeat;
}

/* 土地带（head.css:792-800：bottom -96px） */
.nz-t-land {
  position: absolute;
  bottom: -96px;
  left: 0;
  width: 100%;
  height: 180px;
  z-index: 2;
  pointer-events: none;
  background: linear-gradient(180deg, var(--scene-land-0, rgba(94, 120, 66, 0)) 0%, var(--scene-land-1, #4f6637) 55%, var(--scene-land-2, #3d5229) 100%);
}

.nz-t-inner {
  position: relative;
  width: 100%;
  height: 100%;
  margin: 0 auto;
  z-index: 3;
}

/* eyebrow（head.css:812-828）：Inter 14px / -0.04em */
.nz-t-eyebrow {
  position: absolute;
  top: calc(-15.31px + 8px + var(--nav-off));
  left: 0;
  right: 0;
  text-align: center;
  margin: 0;
  font-family: var(--f-sans, 'Inter', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 14px;
  line-height: 1.3576;
  letter-spacing: -0.04em;
  color: var(--text-eyebrow, rgba(0, 0, 0, 0.5));
  white-space: nowrap;
}

/* 标题（head.css:831-845）：40px / -0.04em */
.nz-t-heading {
  position: absolute;
  top: calc(0.96px + 8px + var(--nav-off));
  left: 0;
  right: 0;
  text-align: center;
  margin: 0;
  font-family: var(--f-sans, 'Inter', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 40px;
  line-height: 1.3576;
  letter-spacing: -0.04em;
  color: var(--text-title, rgba(0, 0, 0, 0.9));
  white-space: nowrap;
}

/* 卡片栈（head.css:850-860）：top calc(50vh-255px)，宽 430 高 510，--spread 0 */
.nz-t-cards {
  position: absolute;
  top: calc(50vh - 250px + 24px);
  left: 50%;
  /* 参考站 #t-cards：430×510，居中；--spread 0→1 让三张卡从叠放扇成左/中/右 */
  width: 430px;
  height: 510px;
  transform: translateX(-50%);
  --spread: 0;
}

/* 玻璃卡（head.css:861-889）
   ★ 拆两层：.nz-t-card 只负责「扇出位移 + 定位」，transform 用 --pos×--spread（CSS 变量驱动）。
     .nz-t-face 是内层玻璃视觉，自身没有 CSS transform —— 指针 3D 倾斜（useTilt）只写在这一层，
     这样 GSAP 接管 face 的 transform 时，绝不会冲掉外层的扇出。 */
.nz-t-card {
  position: absolute;
  top: 0;
  left: 0;
  width: 430px;
  min-height: 510px;
  box-sizing: border-box;
  margin: 0;
  will-change: transform;
  /* ★ 三张卡全部叠在中心（left:0），只靠 --pos × --spread × 462px 左右扇开：
     spread=0 时三张完全重合（看起来只有一张）→ 滚动后摊成左/中/右三张。 */
  left: 0;
  transform: translateX(calc(var(--pos) * var(--spread) * 462px));
}
.nz-t-face {
  width: 100%;
  min-height: 510px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 20px;
  padding: 34px;
  border-radius: 34px;
  /* 玻璃配方（参考站 .t-card）：日间浅灰玻璃；夜里由 body.night 重指同一组变量
     ★ 不用 backdrop-filter：卡片背后是会动/会被滚动的山景，模糊每帧重算，
     2x DPR 下是整页滚动帧时间的主要来源。
     ★ 没有模糊就必须更不透明：三张卡在扇开前完全重合，0.8 级别的透明度会让
     后面两张卡的文字透上来（看起来像渲染错误）。0.95 只留 5% 透光，仍有玻璃感。 */
  --glass-from: rgba(252, 253, 250, 0.95);
  --glass-to: rgba(196, 199, 194, 0.92);
  --glass-border: #999;
  --glass-ink: #000;
  background: linear-gradient(180deg, var(--glass-from) 0%, var(--glass-to) 100%);
  border: 1px solid var(--glass-border);
  box-shadow: 4px 4px 20px 0 rgba(0, 0, 0, 0.3);
}
/* 顺序入场：3 张分别错开 120ms（只跑一次，由 JS 加 .is-in 触发） */

/* 首帧状态：三张卡都从"半透明 + 下移"开始，靠 animation 自动错峰入场，
   不依赖任何 JS 类，避免 sticky 场景下判定失败导致整组不可见 */
.nz-t-card {
  animation: nz-card-in 0.72s cubic-bezier(0.22, 1, 0.36, 1) both;
}
/* 入场动画只作用于透明度与内层位移，交给子层，避免覆盖扇开的 translateX */
@keyframes nz-card-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}
.nz-t-card:nth-child(1) { animation-delay: 0.05s; }
.nz-t-card:nth-child(2) { animation-delay: 0.17s; }
.nz-t-card:nth-child(3) { animation-delay: 0.29s; }
@keyframes nz-card-in {
  from { opacity: 0; }
  to   { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .nz-t-card { animation: none; }
}
.nz-t-card.is-reduced {
  transform: none;
}
body.night .nz-t-card,
body.night .nz-t-card :deep(p),
body.night .nz-t-card :deep(h3),
body.night .nz-t-card :deep(span) {
  color: var(--text-heading, #fff9f1);
}
.nz-testimonials {
  --scene-hill: #b9c9a4;
  --scene-grass: #4e6a35;
  --scene-land-0: rgba(94, 120, 66, 0);
  --scene-land-1: #4f6637;
  --scene-land-2: #3d5229;
  --scene-wavy: #eee8d2;
  --scene-land: #b9c9a4;
  --scene-mid: #dfe6cf;
  --scene-far: #c8d5b4;
  --scene-forest-0: #9cb47a;
  --scene-forest-1: #6f8c53;
  --scene-meadow-0: #87a25f;
  --scene-meadow-1: #5f7a3f;
}
/* 夜：山丘插画降为深蓝绿，玻璃卡才读得出「夜色里的玻璃」 */
body.night .nz-testimonials {
  --scene-hill: #1a2c3d;
  --scene-grass: #24405a;
  --scene-land-0: rgba(20, 32, 46, 0);
  --scene-land-1: #13202e;
  --scene-land-2: #0b141d;
  --scene-wavy: #111c36;
  --scene-land: #14202e;
  --scene-mid: #2a3b4d;
  --scene-far: #1d2b3a;
  --scene-forest-0: #22384a;
  --scene-forest-1: #16273a;
  --scene-meadow-0: #1b2d3b;
  --scene-meadow-1: #0f1c28;
}
body.night .nz-t-card {
  --glass-from: rgba(24, 36, 62, 0.94);
  --glass-to: rgba(10, 18, 34, 0.92);
  --glass-border: rgba(255, 249, 241, 0.28);
  --glass-ink: var(--text-heading, #fff9f1);
  color: var(--text-heading, #fff9f1);
}

.nz-t-quoteicon {
  display: block;
  width: 82px;
  height: 82px;
  flex-shrink: 0;
}
.nz-t-quoteicon svg {
  width: 100%;
  height: 100%;
  display: block;
}

.nz-t-mark {
  margin: 0;
  font-family: var(--f-serif, 'DM Serif Display', 'Songti SC', serif);
  font-weight: 400;
  font-size: 26px;
  line-height: 1.25;
  color: #000;
}

/* 引文（head.css:898-907）：14px / 1.45 */
.nz-t-quote {
  margin: 0;
  width: 100%;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 14px;
  line-height: 1.45;
  color: #000;
  word-break: break-word;
}

/* 分隔线（head.css:909-916）：pin 到卡片底部 */
.nz-t-divider {
  width: 100%;
  height: 0;
  margin: 0;
  margin-top: auto;
  border: 0;
  border-top: 1px solid rgba(0, 0, 0, 0.5);
}

/* 作者行（head.css:918-952） */
.nz-t-author {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
}
.nz-t-authorrow {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.nz-t-avatar {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #cfd6c4;
  color: #33402a;
  font-family: var(--f-mono, 'JetBrains Mono', monospace);
  font-size: 15px;
}
.nz-t-name {
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 700;
  font-size: 22px;
  line-height: normal;
  color: #000;
  white-space: nowrap;
}
.nz-t-role {
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-weight: 400;
  font-size: 16px;
  line-height: normal;
  color: #000;
}

/* scrub 占位（head.css:957） */
.nz-t-scrub {
  height: 620px;
  position: relative;
  background: var(--surface-raised, #eee8d2);
}

/* ════ 响应式（head.css:976-1123）：≤1100 变一列 + 横向 snap 轮播 ════ */
@media (max-width: 1100px) {
  .nz-testimonials {
    position: relative;
    top: auto;
    height: auto;
    min-height: 100vh;
    min-height: 100lvh;
    display: flex;
    flex-direction: column;
    justify-content: center;
    padding: 80px 24px calc(21vw + 24px);
  }
  .nz-t-land {
    bottom: -16px;
  }
  .nz-t-inner {
    width: 100%;
    max-width: 620px;
    height: auto;
  }
  .nz-t-eyebrow {
    position: static;
    text-align: center;
    margin-bottom: 6px;
  }
  .nz-t-heading {
    position: static;
    font-size: clamp(34px, 6vw, 48px);
    letter-spacing: -1.6px;
    line-height: 1.15;
    white-space: normal;
    margin-bottom: 36px;
    text-align: center;
  }
  .nz-t-cards {
    position: static;
    transform: none;
    left: auto;
    width: auto;
    height: auto;
    display: flex;
    flex-direction: row;
    align-items: stretch;
    gap: 16px;
    overflow-x: auto;
    scroll-snap-type: x proximity;
    touch-action: pan-x pan-y;
    overscroll-behavior-x: contain;
    margin: 0 -24px;
    padding: 8px 24px 28px;
    scrollbar-width: none;
  }
  .nz-t-cards::-webkit-scrollbar {
    display: none;
  }
  .nz-t-card {
    position: static;
    transform: none !important;
    left: auto;
    width: min(430px, 84vw);
    flex: 0 0 min(430px, 84vw);
    min-height: 0;
    margin: 0;
    scroll-snap-align: center;
    will-change: auto;
  }
  .nz-t-scrub {
    height: 32vh;
  }
}

@media (max-width: 640px) {
  .nz-testimonials {
    padding-top: 84px;
  }
  .nz-t-card {
    padding: 28px;
    border-radius: 28px;
  }
  .nz-t-name {
    font-size: 22px;
  }
  .nz-t-role {
    font-size: 17px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nz-t-card {
    transition: none;
  }
}
</style>
