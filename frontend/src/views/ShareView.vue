<script setup>
/**
 * 公开分享页（免登录）。凭 URL 里的 token 调 /api/public/share/{token} 拿行程详情，
 * 只读展示：不挂 explore store、不带任何编辑/地图能力，谁拿到链接都能看。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { viewShare } from '../api/xingji'
import AppBrand from '../components/AppBrand.vue'
import {
  Loader2, MapPin, Utensils, Car, BedDouble, Mountain,
  Clock, Coins, Calendar, ShieldAlert
} from 'lucide-vue-next'

const route = useRoute()
const detail = ref(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    detail.value = await viewShare(route.params.token)
    if (!detail.value) error.value = '分享内容为空'
  } catch (e) {
    error.value = e.message || '分享链接无效或已撤销'
  } finally {
    loading.value = false
  }
})

const it = computed(() => detail.value?.itinerary || null)
// /public/share 的节点是 { node, lng, lat, navUrl } 包装结构，模板按扁平节点渲染
const days = computed(() =>
  (detail.value?.days || []).map((d) => ({
    ...d,
    nodes: (d.nodes || []).map((x) => x.node || x)
  }))
)

const TYPE_ICON = { 景点: MapPin, 餐饮: Utensils, 交通: Car, 住宿: BedDouble }
function nodeIcon(t) { return TYPE_ICON[t] || Mountain }
function fmtTime(n) {
  const a = String(n.startTime || '').slice(0, 5)
  const b = String(n.endTime || '').slice(0, 5)
  return a && b ? `${a}–${b}` : a || b || ''
}
function fmtDate(d) {
  if (!d) return ''
  const s = String(d).slice(0, 10)
  const [y, m, dd] = s.split('-')
  return m && dd ? `${Number(m)}月${Number(dd)}日` : s
}
</script>

<template>
  <div class="share-page">
    <header class="sp-bar">
      <AppBrand :size="20" />
      <router-link class="sp-cta" to="/">去规划我的行程 →</router-link>
    </header>

    <main class="sp-main">
      <!-- 载入中 -->
      <div v-if="loading" class="sp-state">
        <Loader2 :size="22" class="spin" />
        <span>正在打性行程…</span>
      </div>

      <!-- 失效 / 错误 -->
      <div v-else-if="error" class="sp-state err">
        <div class="sp-err-ic"><ShieldAlert :size="22" /></div>
        <h4 class="serif">打不开这份分享</h4>
        <p>{{ error }}</p>
        <router-link class="sp-cta big" to="/">回行迹首页</router-link>
      </div>

      <!-- 行程内容（只读） -->
      <article v-else class="sp-card">
        <div class="sp-chip">好友分享的行程</div>
        <h1 class="serif sp-title">{{ it?.title || '一段旅程' }}</h1>
        <div class="sp-meta">
          <span v-if="it?.days"><Calendar :size="13" /> {{ it.days }} 天</span>
          <span v-if="it?.startDate"><MapPin :size="13" /> {{ fmtDate(it.startDate) }} 出发</span>
          <span v-if="it?.budget"><Coins :size="13" /> 预算 ¥{{ it.budget }}</span>
        </div>

        <section v-for="(d, di) in days" :key="d.day?.id || di" class="sp-day">
          <div class="spd-head">
            <span class="spd-no">Day {{ di + 1 }}</span>
            <span v-if="d.day?.date" class="spd-date">{{ fmtDate(d.day.date) }}</span>
            <span v-if="d.day?.theme" class="spd-theme">{{ d.day.theme }}</span>
          </div>
          <div class="spd-nodes">
            <div v-for="n in d.nodes" :key="n.id" class="snode" :class="{ abn: n.isAbnormal === 1 }">
              <div class="snode-tile"><component :is="nodeIcon(n.nodeType)" :size="15" /></div>
              <div class="snode-main">
                <div class="snode-top">
                  <span class="snode-name">{{ n.placeName }}</span>
                  <span class="snode-tag">{{ n.nodeType }}</span>
                  <span v-if="n.isAbnormal === 1" class="snode-abn">地点待核验</span>
                </div>
                <div class="snode-sub">
                  <span v-if="fmtTime(n)"><Clock :size="11" /> {{ fmtTime(n) }}</span>
                  <span v-if="n.cost"><Coins :size="11" /> ¥{{ n.cost }}</span>
                </div>
              </div>
            </div>
            <p v-if="!d.nodes?.length" class="spd-free">这一天没有安排，随意走走</p>
          </div>
        </section>

        <footer class="sp-foot">
          <AppBrand :size="14" />
          <span>这份行程由 AI 规划 · 想要你自己的，<router-link to="/">来行迹</router-link></span>
        </footer>
      </article>
    </main>
  </div>
</template>

<style scoped>
.share-page { min-height: 100vh; background: var(--paper); display: flex; flex-direction: column; }

.sp-bar {
  flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 16px clamp(16px, 5vw, 40px); border-bottom: 1px solid var(--hair-2);
}
.sp-cta { font-size: 13px; color: var(--blue); text-decoration: none; }
.sp-cta:hover { text-decoration: underline; }
.sp-cta.big { font-size: 14px; margin-top: 10px; }

.sp-main { flex: 1; display: grid; place-items: start center; padding: clamp(20px, 6vh, 56px) 16px 48px; }

.sp-state { display: flex; flex-direction: column; align-items: center; gap: 10px; color: var(--ink-3); font-size: 14px; padding: 60px 0; }
.sp-state.err .sp-err-ic { width: 52px; height: 52px; border-radius: 50%; background: var(--seal-wash); color: var(--seal); display: grid; place-items: center; }
.sp-state h4 { margin: 4px 0 0; font-size: 20px; color: var(--ink); }
.sp-state p { margin: 0; font-size: 13px; }

.sp-card {
  width: min(680px, 100%);
  background: var(--paper-raise); border: 1px solid var(--hair-2); border-radius: var(--r-lg);
  box-shadow: var(--shadow-2); padding: clamp(22px, 4vw, 36px);
}
.sp-chip { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--blue); background: var(--blue-wash); border-radius: 999px; padding: 4px 11px; }
.sp-title { margin: 14px 0 10px; font-size: clamp(24px, 4vw, 32px); color: var(--ink); line-height: 1.25; }
.sp-meta { display: flex; flex-wrap: wrap; gap: 16px; font-size: 12.5px; color: var(--ink-3); margin-bottom: 22px; }
.sp-meta span { display: inline-flex; align-items: center; gap: 5px; }

.sp-day { background: var(--paper); border: 1px solid var(--hair-2); border-radius: var(--r-lg); padding: 16px 18px; margin-bottom: 14px; }
.spd-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.spd-no { font-family: var(--f-mono); font-size: 13px; font-weight: 700; color: var(--blue); }
.spd-date { font-size: 12px; color: var(--ink-3); }
.spd-theme { font-size: 12.5px; color: var(--ink-2); margin-left: auto; font-style: italic; }

.spd-nodes { display: flex; flex-direction: column; gap: 8px; }
.snode { display: flex; gap: 12px; padding: 10px; border-radius: var(--r-md); background: var(--paper-raise); }
.snode.abn { background: var(--seal-wash); }
.snode-tile { flex: none; width: 36px; height: 36px; border-radius: var(--r-md); background: var(--blue-wash); color: var(--blue); display: grid; place-items: center; }
.snode.abn .snode-tile { background: #fff; color: var(--seal); }
.snode-main { min-width: 0; flex: 1; }
.snode-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.snode-name { font-size: 14px; color: var(--ink); font-weight: 600; }
.snode-tag { font-size: 11px; color: var(--ink-3); border: 1px solid var(--hair-2); border-radius: 5px; padding: 1px 6px; }
.snode-abn { font-size: 11px; color: var(--seal); }
.snode-sub { display: flex; gap: 14px; margin-top: 4px; font-size: 12px; color: var(--ink-3); }
.snode-sub span { display: inline-flex; align-items: center; gap: 4px; }
.spd-free { margin: 0; font-size: 12.5px; color: var(--ink-4); font-style: italic; }

.sp-foot { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 20px; font-size: 12px; color: var(--ink-4); }
.sp-foot a { color: var(--blue); }

.spin { animation: xjspin 0.9s linear infinite; color: var(--blue); }
@keyframes xjspin { to { transform: rotate(360deg); } }
</style>
