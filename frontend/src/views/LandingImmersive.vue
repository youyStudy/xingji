<!--
  行迹 · 首页（沉浸式）
  视觉语言移植自参考站（zainabkabira.com）的「天空 / 大地」插画式滚动叙事：
    NavBar（玻璃胶囊导航 + 日夜切换）→ SkyHero（吸顶天空 hero + 行程进度盘）
    → StoryIntro（简介，奶油云带爬升）→ FeaturedWork（错位项目卡 + 跑马灯 + 纸飞机）
    → TestimonialsDeck（山景玻璃卡扇出）→ GardenFooter（山丘页脚 + 可点击生长的花园）
  设计 token 见 src/styles/theme.css，滚动引擎见 src/composables/useLenis.js（只在首页挂载）。
  素材为自绘 SVG，加上 src/assets/tiles/ 下随项目打包的真实旅行照（取自本项目相册），
  不引外网站点图片，也不使用参考站的任何版权资产。
-->
<script setup>
import NavBar from '../components/nz/NavBar.vue'
import SkyHero from '../components/nz/SkyHero.vue'
import StoryIntro from '../components/nz/StoryIntro.vue'
import FeaturedWork from '../components/nz/FeaturedWork.vue'
import TestimonialsDeck from '../components/nz/TestimonialsDeck.vue'
import GardenFooter from '../components/nz/GardenFooter.vue'
import { useLenis } from '../composables/useLenis'

// 平滑滚动只服务首页的滚动叙事；功能页（探索/我的行程/个人中心）是多栏固定布局，
// 整页 Lenis 会按 wheel 并拦掉面板内部的滚动，这里按路由挂载即可。
useLenis()
</script>

<template>
  <div class="nz-page route-view">
    <NavBar />
    <main class="nz-main">
      <SkyHero />
      <StoryIntro />
      <FeaturedWork />
      <TestimonialsDeck />
    </main>
    <GardenFooter />
  </div>
</template>

<style scoped>
.nz-page { position: relative; background: var(--bg-base, #fff9f1); }
/* ★ sticky 生效的关键：祖先不能有 overflow（哪怕是 clip）也不能被撑成 flex 子项尺寸。
   .nz-page 只做背景，main 不参与任何 overflow 裁剪，精选作品才能「吸底」等证言区压上来。 */
.nz-main { display: block; }
</style>
