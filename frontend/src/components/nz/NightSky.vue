<script setup>
/*
 * NightSky —— hero 的夜空层（参考站 .hero-stars / .hero-meteor / .moon-group 的等价物）
 * 只在 body.night 下显示（主题由 NavBar 里的 ThemeToggle 切换，token 在 theme.css）。
 * 参考数值：月亮 glow 白 45% + blur 153.4（index:2880-2885 注释）、
 * 流星 1.1–1.8s linear、间隔 5.2–13s 随机（任务书），噪点/星星用自绘。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

const rootEl = ref(null)
const meteors = ref([])
let timer = null
let reduce = false
const isNight = ref(false)
let seq = 0

/* 星星：固定随机（种子化）生成，避免每次刷新跳动 */
const STARS = (() => {
  const out = []
  let seed = 20260727
  const rnd = () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return seed / 0x7fffffff
  }
  for (let i = 0; i < 90; i++) {
    out.push({
      id: i,
      x: +(rnd() * 100).toFixed(3),
      y: +(rnd() * 68).toFixed(3),
      r: +(0.6 + rnd() * 1.5).toFixed(2),
      o: +(0.25 + rnd() * 0.6).toFixed(2),
      d: +(rnd() * 4).toFixed(2),
      dur: +(2.4 + rnd() * 3.2).toFixed(2)
    })
  }
  return out
})()

function scheduleMeteor() {
  const gap = reduce ? 13000 : 5200 + Math.random() * 7800 // 5.2–13s
  timer = setTimeout(() => {
    if (isNight.value && !reduce && !heroCovered()) spawnMeteor()
    scheduleMeteor()
  }, gap)
}

function spawnMeteor() {
  const id = ++seq
  const top = 4 + Math.random() * 34 // 只落在天空上半部
  const left = 15 + Math.random() * 70
  const dur = 1.1 + Math.random() * 0.7 // 1.1–1.8s
  meteors.value = [...meteors.value, { id, top, left, dur }]
  // 动画结束后移除节点，避免 DOM 堆积
  setTimeout(() => {
    meteors.value = meteors.value.filter((m) => m.id !== id)
  }, dur * 1000 + 120)
}

function syncTheme() {
  isNight.value = document.body.classList.contains('night')
}
let mo = null

/* hero 是 sticky 的，始终在视口内，IntersectionObserver 判不到"被盖住"；
   由 SkyHero 在滚动超过一屏后给 .nz-hero 加 anim-idle，这里读同一个状态 */
function heroCovered() {
  const hero = document.querySelector('.nz-hero')
  return !!hero && hero.classList.contains('anim-idle')
}

onMounted(() => {
  reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  syncTheme()
  // 主题切换是 body class 变化，用 MutationObserver 跟随（比轮询干净）
  mo = new MutationObserver(syncTheme)
  mo.observe(document.body, { attributes: true, attributeFilter: ['class'] })
  scheduleMeteor()
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  if (mo) mo.disconnect()
})
</script>

<template>
  <div ref="rootEl" class="nz-night" :class="{ 'is-on': isNight }" aria-hidden="true">
    <!-- 星点 -->
    <span
      v-for="s in STARS"
      :key="s.id"
      class="nz-star"
      :style="{
        left: s.x + '%',
        top: s.y + '%',
        width: s.r + 'px',
        height: s.r + 'px',
        opacity: s.o,
        animationDelay: s.d + 's',
        animationDuration: s.dur + 's'
      }"
    />
    <!-- 月亮：白色 45% 光晕 + 白色圆盘 -->
    <div class="nz-moon">
      <span class="nz-moon-glow"></span>
      <svg class="nz-moon-disc" viewBox="0 0 100 100" role="presentation">
        <circle cx="50" cy="50" r="49" fill="#f7f4ea" />
        <circle cx="38" cy="42" r="7" fill="#e6e2d4" opacity=".7" />
        <circle cx="60" cy="60" r="5" fill="#e6e2d4" opacity=".55" />
        <circle cx="55" cy="33" r="3.4" fill="#e6e2d4" opacity=".5" />
      </svg>
    </div>
    <!-- 流星（JS 定时器触发，不是无限 CSS 动画） -->
    <span
      v-for="m in meteors"
      :key="m.id"
      class="nz-meteor"
      :style="{ top: m.top + '%', left: m.left + '%', animationDuration: m.dur + 's' }"
    />
  </div>
</template>

<style scoped>
.nz-night {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  opacity: 0;
  transition: opacity 0.45s ease;
}

/* 只在 night 生效 */
.nz-night.is-on {
  opacity: 1;
}

.nz-star {
  position: absolute;
  border-radius: 50%;
  background: #fff;
  animation-name: nz-twinkle;
  animation-iteration-count: infinite;
  animation-timing-function: ease-in-out;
  animation-direction: alternate;
}
@keyframes nz-twinkle {
  from {
    opacity: 0.15;
    transform: scale(0.85);
  }
  to {
    opacity: 0.95;
    transform: scale(1.15);
  }
}

/* 白天整层不可见：90 个星点没必要继续跑 infinite 动画（hero 被盖住时由 SkyHero 统一暂停） */
.nz-night:not(.is-on) .nz-star {
  animation-play-state: paused;
}

/* 月亮：位置参考参考站右上角（--sun-w 体系里月亮与太阳同组），夜间太阳淡出由 theme.css 处理 */
.nz-moon {
  position: absolute;
  right: 6vw;
  top: 9vh;
  width: clamp(88px, 11vw, 168px);
  aspect-ratio: 1;
}
.nz-moon-glow {
  position: absolute;
  inset: -62%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.45) 0%, rgba(255, 255, 255, 0) 70%);
  filter: blur(28px);
}
.nz-moon-disc {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  filter: drop-shadow(0 0 18px rgba(255, 255, 255, 0.55));
}

.nz-meteor {
  position: absolute;
  width: 2px;
  height: 2px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 6px 1px rgba(255, 255, 255, 0.8);
  animation-name: nz-meteor-fall;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}
/* 拖尾：用伪元素拉一条渐隐的线，方向与运动一致（右下） */
.nz-meteor::after {
  content: '';
  position: absolute;
  right: 100%;
  top: 50%;
  width: 120px;
  height: 1px;
  transform-origin: right center;
  transform: rotate(200deg);
  background: linear-gradient(to left, rgba(255, 255, 255, 0.85), rgba(255, 255, 255, 0));
}
@keyframes nz-meteor-fall {
  0% {
    opacity: 0;
    transform: translate3d(0, 0, 0);
  }
  12% {
    opacity: 1;
  }
  80% {
    opacity: 0.9;
  }
  100% {
    opacity: 0;
    transform: translate3d(38vw, 26vh, 0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .nz-star {
    animation: none;
  }
  .nz-meteor {
    display: none;
  }
}
</style>
