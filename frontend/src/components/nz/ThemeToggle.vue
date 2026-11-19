<script setup>
/**
 * 行迹 · 日 / 夜切换 —— 参考站 .day-toggle 的移植版。
 * 一枚 34px 圆形按钮，内部两个绝对定位的日/月图标上下滑动：
 *   日间：太阳居中，月亮停在按钮上方被裁掉；
 *   夜间：太阳从底部滑出，月亮升到中央。
 * body.night 是唯一开关（颜色全部来自 src/styles/theme.css 的语义 token，
 * 首帧的 class 由 index.html 的前置脚本写入，避免闪白）。
 */
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { Sun, Moon } from 'lucide-vue-next'

const STORAGE_KEY = 'xingji.theme'

/* View Transition 期间把页面钉回原位，避免切换后整页跳到下方区块 */
function saveScroll(y) {
  try {
    document.documentElement.style.scrollBehavior = 'auto'
  } catch (e) {}
  window.scrollTo(0, y)
}
function restoreScroll(y) {
  if (Math.abs((window.scrollY || window.pageYOffset || 0) - y) > 2) {
    window.scrollTo({ top: y, behavior: 'instant' in window ? 'instant' : 'auto' })
  }
}
const night = ref(false)
let fadeTimer = 0

function readNight() {
  return typeof document !== 'undefined' && document.body.classList.contains('night')
}

function apply(nightMode) {
  if (typeof document === 'undefined') return
  document.body.classList.toggle('night', nightMode)
  night.value = nightMode
  try {
    localStorage.setItem(STORAGE_KEY, nightMode ? 'night' : 'day')
  } catch (e) {
    /* 隐私模式下 localStorage 可能不可写，主题仍在本次会话内生效 */
  }
  // 主题切换会改变导航背后的亮度，通知导航重算对比度（存在才调用）
  if (typeof window !== 'undefined' && typeof window.updateNavContrast === 'function') {
    window.updateNavContrast()
  }
}

function toggle() {
  const next = !night.value
  if (typeof document === 'undefined') return
  // 溶解式换肤：View Transitions 会把整页（含渐变与图片）交叉淡入；
  // 不支持时退回 body.theme-fading 的颜色过渡（500ms 后移除）。
  if (typeof document.startViewTransition === 'function') {
    const y = window.scrollY || window.pageYOffset || 0
    saveScroll(y)
    document.startViewTransition(() => apply(next))
    // View Transition 的快照可能把根滚动位置复位：切换后主动还原一次
    requestAnimationFrame(() => restoreScroll(y))
    setTimeout(() => restoreScroll(y), 120)
    night.value = next
  } else {
    document.body.classList.add('theme-fading')
    apply(next)
    clearTimeout(fadeTimer)
    fadeTimer = setTimeout(() => document.body.classList.remove('theme-fading'), 500)
  }
}

// 本组件是「控制者」而不是「所有者」：首帧 class 由 index.html 决定，
// 这里只在挂载后同步一次自己的显示态；若前置脚本缺失则按存储兜底。
onMounted(() => {
  let initial = readNight()
  if (!initial) {
    try {
      const saved = localStorage.getItem(STORAGE_KEY)
      if (saved === 'night') {
        document.body.classList.add('night')
        initial = true
      }
    } catch (e) { /* 忽略 */ }
  }
  night.value = initial
})

onBeforeUnmount(() => clearTimeout(fadeTimer))
</script>

<template>
  <button
    type="button"
    class="nz-day-toggle"
    :aria-pressed="night ? 'true' : 'false'"
    :aria-label="night ? '切换到日间模式' : '切换到夜间模式'"
    :title="night ? '切换到日间模式' : '切换到夜间模式'"
    @click="toggle"
  >
    <Sun class="nz-toggle-ico ico-sun" aria-hidden="true" :stroke-width="1.7" />
    <Moon class="nz-toggle-ico ico-moon" aria-hidden="true" :stroke-width="1.7" />
  </button>
</template>

<style scoped>
/* 34px 圆形 —— 与导航右侧的音乐波纹按钮同尺寸，组成 4px 间距的紧邻按钮组 */
.nz-day-toggle {
  position: relative;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  padding: 0;
  border-radius: 50%;
  background: var(--toggle-track, #ffffff);
  border: 1px solid var(--toggle-border, #e7dfcd);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1);
  cursor: pointer;
  overflow: hidden;
  -webkit-appearance: none;
  appearance: none;
  color: var(--ink, #060606);
  transition: background-color 0.35s ease, border-color 0.35s ease,
    box-shadow 0.35s ease, transform 0.2s ease;
}
.nz-day-toggle:hover { transform: scale(1.06); }
.nz-day-toggle:active { transform: scale(0.94); }
.nz-day-toggle:focus-visible {
  outline: 2px solid var(--focus-ring, #060606);
  outline-offset: 3px;
}

/* 两个图标共用一个停车位：绝对定位在圆心，靠 transform 上下滑动 */
.nz-toggle-ico {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 20px;
  height: 20px;
  transition: transform 0.5s cubic-bezier(0.34, 1.4, 0.5, 1);
  will-change: transform;
  pointer-events: none;
}
/* 日间：太阳居中，月亮停在顶边之外（被溢出的圆裁掉） */
.ico-sun {
  width: 22px;
  height: 22px;
  color: #f5a623;
  transform: translate(-50%, -50%);
}
.ico-moon {
  color: #f2ead6;
  transform: translate(-50%, -220%);
}
/* 夜间：太阳沉出底部，月亮升到中央。
   ⚠ 不要写成 :global(body.night) …——scoped 编译会把 :global() 剥掉、又把
   .ico-sun 改写成带 data-v 的属性选择器，最终落成一条裸的
   `body.night { transform: translate(-50%, 120%) }`，把整页 body 平移走。
   直接写 body.night 前缀，编译器会自动给后缀加上作用域属性。 */
body.night .ico-sun { transform: translate(-50%, 120%); }
body.night .ico-moon { transform: translate(-50%, -50%); }

@media (prefers-reduced-motion: reduce) {
  .nz-day-toggle,
  .nz-toggle-ico { transition-duration: 1ms; }
}
</style>
