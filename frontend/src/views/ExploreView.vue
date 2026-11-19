<script setup>
import { onMounted, ref } from 'vue'
import AppNav from '../components/AppNav.vue'
import ChatSidebar from '../components/ChatSidebar.vue'
import ItineraryPanel from '../components/ItineraryPanel.vue'
import MapPanel from '../components/MapPanel.vue'
import { useExploreStore } from '../stores/explore'

const store = useExploreStore()
// 城市热点扇形打开时淡出浮层,让地图满幅、被点城市真正居中
// 搜索联想下拉展开时同样淡出浮层,下拉名字列不被行程面板盖住
const searchOpen = ref(false)

onMounted(() => {
  store.refreshSessions() // 会话快照:删行程后判断"会话是否被连带删除"用
})
</script>

<template>
  <div class="explore">
    <AppNav />
    <main class="work">
      <!-- 地图：满幅背景画布，铺满整个工作区 -->
      <div class="map-wrap">
        <MapPanel
          :itinerary="store.itinerary"
          :active-day="store.activeDay"
          :city="store.params.city"
          @set-day="store.setActiveDay"
          @pick-city="(c) => (store.params.city = c)"
          @search-open="(v) => (searchOpen = v)"
        />
      </div>

      <!-- 浮层：AI 会话 + 依附于它的行程，一起作为图层悬浮在地图之上 -->
      <div class="panel-layer" :class="{ 'itin-collapsed': store.itinCollapsed, fading: searchOpen }">
        <ChatSidebar />
        <ItineraryPanel />
      </div>
    </main>
  </div>
</template>

<style scoped>
.explore { display: flex; flex-direction: column; height: 100vh; background: var(--paper); }

.work { position: relative; flex: 1; min-height: 0; padding: 16px; }

/* 地图：满幅背景层 */
.map-wrap { position: absolute; inset: 16px; z-index: 0; border: 1px solid var(--hair-2); border-radius: var(--r-lg); box-shadow: var(--shadow-1); overflow: hidden; }

/* 浮层：会话 + 行程合成一个盒子，浮在地图之上；上下各留 15% 白 */
.panel-layer { position: absolute; top: 15%; bottom: 15%; left: 16px; z-index: 1; display: flex; align-items: stretch; gap: 0; pointer-events: none; transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); }
.panel-layer > * { pointer-events: auto; }
/* 热点扇形打开:浮层淡出并左移,把地图让给居中的城市与卡片 */
.panel-layer.fading { opacity: 0; transform: translateX(-24px); pointer-events: none; }
.panel-layer.fading > * { pointer-events: none; }

/* 会话与行程贴合成一个盒子：去掉接触处的圆角与重复边框，接缝只留一条分隔线 */
.panel-layer :deep(.chat) { border-radius: var(--r-lg) 0 0 var(--r-lg); border-right: 0; }
.panel-layer :deep(.itin) { border-radius: 0 var(--r-lg) var(--r-lg) 0; }
/* 毛玻璃：让地图隐约透出来，夜模式随 --paper 自动翻转 */
.panel-layer :deep(.chat) { background: color-mix(in srgb, var(--paper) 82%, transparent); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); }
.panel-layer :deep(.itin) { background: color-mix(in srgb, var(--paper-raise) 82%, transparent); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); }
/* 行程收起后，会话成为盒子右端，补回右边框与四角圆角 */
.panel-layer.itin-collapsed :deep(.chat) { border-radius: var(--r-lg); border-right: 1px solid var(--hair-2); }

@media (max-width: 720px) {
  .work { padding: 10px; }
  .map-wrap { inset: 10px; }
  .panel-layer { position: static; top: auto; bottom: auto; left: auto; pointer-events: auto; height: 100%; flex-direction: column; gap: 16px; }
  .panel-layer :deep(.chat) { border-radius: var(--r-lg); border-right: 1px solid var(--hair-2); }
  .panel-layer :deep(.itin) { border-radius: var(--r-lg); }
}
</style>
