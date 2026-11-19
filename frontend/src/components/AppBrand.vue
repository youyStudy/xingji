<script setup>
// 行迹的品牌记号：一串被走出来的轨迹——虚线上的一个点，正停在它核验过的位置上。
// 纯 SVG，不用图片也不用 emoji，缩放锐利、颜色跟随 currentColor。
defineProps({
  size: { type: Number, default: 26 }
})
</script>

<template>
  <svg
    class="brand-mark"
    :width="size"
    :height="size"
    viewBox="0 0 32 32"
    fill="none"
    aria-hidden="true"
  >
    <!-- 来路：一条被折过两次的行进轨迹 -->
    <path
      d="M5 25 C 5 18, 12 20, 15 14 C 17 10, 16 8, 20 6"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-dasharray="0.5 3.4"
      opacity="0.5"
    />
    <!-- 起点 -->
    <circle cx="5" cy="25" r="2.1" fill="currentColor" opacity="0.35" />
    <!-- 当前落点：一枚被“核验”过的实心定位 -->
    <circle cx="22.5" cy="6.5" r="4.4" fill="currentColor" />
    <circle cx="22.5" cy="6.5" r="1.7" fill="var(--paper)" />
  </svg>
</template>

<style scoped>
.brand-mark {
  color: var(--ink);
  display: block;
  transition: color var(--t-base) var(--ease);
}
</style>
