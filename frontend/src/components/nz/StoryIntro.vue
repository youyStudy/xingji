<script setup>
/*
 * StoryIntro —— 简介区（参考站 .projects + .sky-band + .intro-copy）
 * 出处：inline-head.css:387-412（.projects/.sky-band）、446-462（.intro-copy）、
 *       landing.js:64-161（按渲染行拆行 + 逐行入场阈值）。
 * 奶油云带是自绘 SVG（不使用参考站 sky-band.svg）。
 */
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Scroll, useScrollFrame } from '../../composables/useScroll'

const sectionEl = ref(null)
const copyEl = ref(null)

const para1 = '先有一句模糊的念头，再有一条能照着走的日程。行迹把「想去」这件事拆开：城市与景点来自真实地图，每天挂上地点、时间与花费，你随时能改。'
const para2 = '每一个地点都要先过一道核验——真实存在、能导航、能去——才配留在你的行程里。'

const lines = ref([]) // 渲染行文字
let lastShown = null

/* 把段落按「渲染行」拆开：同一 offsetTop 的词属于同一行（landing.js:74-102） */
function splitIntoLines(rootEl) {
  const paragraphs = Array.from(rootEl.querySelectorAll('[data-split]'))
  const result = []
  for (const p of paragraphs) {
    const text = p.dataset.text || p.textContent
    if (p.dataset.text === undefined) p.dataset.text = text
    const tokens = text.split(/(\s+)/).filter((t) => t.length)
    p.innerHTML = ''
    const spans = tokens.map((t) => {
      const s = document.createElement('span')
      s.textContent = t
      p.appendChild(s)
      return s
    })
    const groups = []
    let cur = null
    let curTop = null
    for (const s of spans) {
      const top = s.offsetTop
      if (curTop === null || top !== curTop) {
        cur = []
        groups.push(cur)
        curTop = top
      }
      cur.push(s.textContent)
    }
    p.innerHTML = ''
    for (const words of groups) {
      result.push(words.join('').replace(/^\s+|\s+$/g, ''))
    }
  }
  return result
}

/* 先渲染成可测的段落，量出行数再换成 span 行结构 */
function build() {
  const rootEl = copyEl.value
  if (!rootEl) return
  const built = splitIntoLines(rootEl)
  lines.value = built
  lastShown = null
}

function apply() {
  const el = copyEl.value
  if (!el) return
  const vh = Scroll.vh()
  const top = Scroll.rect(el).top
  const start = vh * 0.88 // progress 0：文案顶部在视口下方
  const end = vh * 0.32 // progress 1：文案顶部升到视口上三分之一
  let progress = (start - top) / (start - end)
  progress = progress < 0 ? 0 : progress > 1 ? 1 : progress

  // 只有"已显示行数"变化时才碰 DOM（landing.js:128-132）
  const shown = Math.floor(progress * (lines.value.length + 1))
  if (shown === lastShown) return
  lastShown = shown

  const n = lines.value.length
  const nodeList = el.querySelectorAll('.nz-tw-line')
  nodeList.forEach((node, i) => {
    const threshold = (i + 1) / (n + 1) // 均匀阈值，第 0 行初始隐藏
    node.classList.toggle('is-in', progress >= threshold)
  })
}

/* 共用单 rAF 调度器：读缓存 rect，再写 class */
useScrollFrame(apply)

let rebuildTimer = null
function rebuild() {
  const el = copyEl.value
  if (!el) return
  // 还原成两段纯文本，重新测行
  el.innerHTML = '<p class="nz-intro-p" data-split></p><p class="nz-intro-p nz-bold" data-split></p>'
  const ps = el.querySelectorAll('[data-split]')
  ps[0].textContent = para1
  ps[1].textContent = para2
  const built = splitIntoLines(el)
  lines.value = built
  // 生成带 class 的行节点
  el.innerHTML = ''
  for (const text of built) {
    const s = document.createElement('span')
    s.className = 'nz-tw-line'
    s.textContent = text
    el.appendChild(s)
  }
  lastShown = null
  apply()
}

let reduce = false
onMounted(() => {
  reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  rebuild()
  if (reduce) {
    copyEl.value.querySelectorAll('.nz-tw-line').forEach((n) => n.classList.add('is-in'))
  }
  window.addEventListener('resize', onResize, { passive: true })
  // 字体到位后换行点会变，必须重测（landing.js:157-160）
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(() => {
      rebuild()
      if (reduce) copyEl.value.querySelectorAll('.nz-tw-line').forEach((n) => n.classList.add('is-in'))
    })
  }
  nextTick(apply)
})

function onResize() {
  if (rebuildTimer) clearTimeout(rebuildTimer)
  rebuildTimer = setTimeout(() => {
    rebuild()
    if (reduce) copyEl.value.querySelectorAll('.nz-tw-line').forEach((n) => n.classList.add('is-in'))
  }, 200)
}

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (rebuildTimer) clearTimeout(rebuildTimer)
})
</script>

<template>
  <section ref="sectionEl" class="nz-projects" id="why">
    <!-- 奶油云带（.sky-band，inline-head.css:398-412）：
         自绘波浪 SVG，宽 100%、比例 1440/471，top: calc(200px - 32.71vw) -->
    <div class="nz-sky-band" aria-hidden="true">
      <svg viewBox="0 0 1440 471" preserveAspectRatio="none">
        <path
          d="M0 471 L0 268 C 96 246 176 292 268 262 C 360 232 424 178 520 196 C 616 214 690 250 792 232
             C 894 214 950 168 1052 186 C 1154 204 1216 254 1316 238 C 1380 228 1418 214 1440 206 L1440 471 Z"
          fill="var(--sky-band-fill, #fff9f1)"
        />
        <path
          d="M0 286 C 120 258 210 300 320 276 C 430 252 486 210 596 226 C 706 242 770 286 880 268
             C 990 250 1050 208 1160 222 C 1270 236 1350 280 1440 262"
          fill="none"
          stroke="rgba(255,255,255,.55)"
          stroke-width="2"
        />
      </svg>
    </div>

    <!-- 简介正文（.intro-copy，inline-head.css:447-462：
         宽 982.9px / 32px / 字距 -1.28px / 行高 1.358 / 居中 / 第二段 700） -->
    <div ref="copyEl" class="nz-intro-copy" aria-label="行迹简介"></div>
  </section>
</template>

<style scoped>
/* .projects（inline-head.css:388-393）：盖住吸顶 hero */
.nz-projects {
  /* 云带填充跟随主题：日=奶油，夜=夜底，避免夜里出现一块暖白"墙" */
  --sky-band-fill: var(--bg-base, #fff9f1);
  position: relative;
  z-index: 10;
  background: var(--bg-base, #fff9f1);
  /* 参考站 #story 实测高 821px：317 上留白 + 正文 + 下留白 */
  padding: 317px 0 200px;
  min-height: 821px;
  box-sizing: border-box;
  display: flex;
  align-items: flex-start;
}

/* 云带（inline-head.css:398-412）：1440/471 比例，锚定在 200px 处 */
.nz-sky-band {
  position: absolute;
  left: 0;
  width: 100%;
  aspect-ratio: 1440 / 471;
  top: calc(200px - 32.71vw);
  pointer-events: none;
}
.nz-sky-band svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* 简介（inline-head.css:447-462） */
.nz-projects { scroll-margin-top: 90px; }
.nz-intro-copy {
  position: relative;
  width: 982.9px;
  margin-inline: auto;
  max-width: calc(100% - 96px);
  margin: 0 auto;
  text-align: center;
  font-family: var(--f-sans, 'Space Grotesk', 'PingFang SC', sans-serif);
  font-size: 32px;
  letter-spacing: -1.28px;
  line-height: 1.358;
  color: var(--text-heading, #000);
}

/* 逐行入场（landing.js:135-139 + 任务书）：初始 .25 透明度、下移 8px */
.nz-intro-copy :deep(.nz-tw-line) {
  display: inline;
  opacity: 0.25;
  transform: translateY(8px);
  transition: opacity 0.5s ease, transform 0.5s ease;
}
.nz-intro-copy :deep(.nz-tw-line.is-in) {
  opacity: 1;
  transform: translateY(0);
}
/* 行与行之间要能断行：每行一个 inline-block 撑成自己的行盒 */
.nz-intro-copy :deep(.nz-tw-line) {
  display: inline-block;
  width: 100%;
}
/* 第二段加粗（.intro-copy .bold：font-weight 700 + margin-top 1.358em） */
.nz-intro-copy :deep(.nz-tw-line.nz-line-bold) {
  font-weight: 700;
}
.nz-intro-copy :deep(.nz-interlude) {
  display: block;
  height: 1.358em;
}

/* 响应式（inline-head.css:1025-1030） */
@media (max-width: 1100px) {
  .nz-projects {
    padding: 150px 24px 120px;
  }
  .nz-sky-band {
    top: -120px;
    left: -40px;
    width: calc(100% + 40px);
  }
  .nz-intro-copy {
    width: 100%;
    max-width: 640px;
    font-size: clamp(19px, 4.6vw, 28px);
    letter-spacing: -0.5px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nz-intro-copy :deep(.nz-tw-line) {
    opacity: 1;
    transform: none;
    transition: none;
  }
}
</style>
