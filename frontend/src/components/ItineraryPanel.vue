<script setup>
import { computed, ref, nextTick } from 'vue'
import { useExploreStore } from '../stores/explore'
import NodeEditForm from './NodeEditForm.vue'
import DayEditForm from './DayEditForm.vue'
import {
  Sparkles, MapPin, Utensils, Car, BedDouble, Mountain,
  Clock, Coins, Loader2, ShieldCheck, Calendar,
  ChevronsLeft, ChevronsRight, Pencil, Trash2, Plus, Share2, Check, X,
  RefreshCw, ShieldAlert, CloudSun, Navigation, Route as RouteIcon
} from 'lucide-vue-next'

const store = useExploreStore()

const it = computed(() => store.itinerary?.itinerary || null)
const days = computed(() => store.itinerary?.days || [])
const totalNodes = computed(() => days.value.reduce((s, d) => s + (d.nodes?.length || 0), 0))

const TYPE_ICON = { 景点: MapPin, 餐饮: Utensils, 交通: Car, 住宿: BedDouble }
function nodeIcon(t) { return TYPE_ICON[t] || Mountain }
function fmtTime(n) {
  const a = String(n.startTime || '').slice(0, 5)
  const b = String(n.endTime || '').slice(0, 5)
  return a && b ? `${a}–${b}` : a || b || ''
}
/**
 * 打开高德导航。链接由后端用真实坐标拼好（uri.amap.com，GCJ-02），
 * 直接新窗口打开：装了高德 App 会唤起 App，没装则打开高德网页版。
 * 没定位的节点不给按钮——宁可没有导航，也不导到一个猜出来的坐标。
 */
function openNav(url) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}
function fmtDate(d) {
  if (!d) return ''
  const s = String(d).slice(0, 10)
  const [y, m, dd] = s.split('-')
  return m && dd ? `${Number(m)}月${Number(dd)}日` : s
}

/* ---------- 相邻站点路线（距离/耗时内联在两个节点之间） ---------- */
// 以「到达节点」为锚：segment.toNodeId 对得上当前节点，才把这一段的距离画在它上方
function legFor(n) {
  return store.routeSegments.find((s) => s.toNodeId === n.id) || null
}
function fmtKm(m) {
  if (m == null) return '—'
  return m >= 1000 ? (m / 1000).toFixed(1) + ' 公里' : m + ' 米'
}
function fmtMin(s) {
  if (s == null) return '—'
  const min = Math.round(s / 60)
  return min >= 60 ? `${Math.floor(min / 60)} 小时 ${min % 60} 分` : `${min} 分钟`
}
async function calcRoutes() {
  const n = await store.calcRoutes()
  if (typeof n === 'number') toast(n ? `已算出 ${n} 段路线` : '相邻站点还没有可用坐标')
}

/* ---------- 标题行内编辑 ---------- */
const titleEditing = ref(false)
const titleDraft = ref('')
const titleInput = ref(null)
function startTitleEdit() {
  titleDraft.value = it.value?.title || ''
  titleEditing.value = true
  nextTick(() => titleInput.value?.focus())
}
async function saveTitle() {
  const t = (titleDraft.value || '').trim()
  titleEditing.value = false
  if (!t || t === it.value?.title) return
  await store.saveItinerary({ title: t })
}

/* ---------- 节点编辑/新增（同一时刻只开一个表单） ---------- */
const editing = ref(null) // { id:null|nodeId, dayId, placeId?, placeName, nodeType, seq, startTime, endTime, cost }
function startNodeAdd(d) {
  const seq = ((d.nodes || []).length ? Math.max(...d.nodes.map((n) => n.seq || 0)) : 0) + 1
  editing.value = { id: null, dayId: d.day?.id, placeId: null, placeName: '', nodeType: '景点', seq, startTime: '', endTime: '', cost: '' }
}
function startNodeEdit(n) {
  // 时间后端序列化为 "08:30:00"，表单只要 HH:mm
  editing.value = {
    id: n.id,
    dayId: n.dayId,
    placeId: n.placeId ?? null,
    placeName: n.placeName || '',
    nodeType: n.nodeType || '景点',
    seq: n.seq ?? 1,
    startTime: n.startTime ? String(n.startTime).slice(0, 5) : '',
    endTime: n.endTime ? String(n.endTime).slice(0, 5) : '',
    cost: n.cost != null ? Number(n.cost) : ''
  }
}
async function saveNodeForm() {
  if (!editing.value) return
  const ok = await store.saveNode(editing.value)
  if (ok) editing.value = null
}
function askRemoveNode(n) {
  if (!window.confirm(`删除「${n.placeName}」这条安排？`)) return
  if (editing.value?.id === n.id) editing.value = null
  store.removeNode(n.id)
}
function askRemoveDay(d) {
  if (!window.confirm(`删除 Day ${days.value.indexOf(d) + 1} 的全部安排？`)) return
  editing.value = null
  store.removeDay(d.day?.id)
}
/* ---------- 轻量 toast（删除成功等反馈） ---------- */
const toastMsg = ref('')
let toastTimer = null
function toast(msg) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toastMsg.value = ''), 2500)
}

function askRemoveItinerary() {
  if (!window.confirm('删除整条行程？删除后不可恢复。')) return
  editing.value = null
  dayEditing.value = null
  regen.value = null
  store.removeItinerary().then(() => {
    // removeItinerary 成功会清掉 itinerary、失败会把原因写进 editError
    if (!store.itinerary && !store.editError) toast('行程已删除')
  })
}

/* ---------- 分享 ---------- */
const shareUrl = computed(() => (store.shareToken ? `${location.origin}/s/${store.shareToken}` : ''))
function copyShare() {
  if (!shareUrl.value) return
  navigator.clipboard?.writeText(shareUrl.value)
  store.shareCopied = true
  setTimeout(() => (store.shareCopied = false), 2500)
}

/* ---------- 方案说明（advice：预算拆分 / 预约与风险提醒 / 备选方案） ---------- */
const ADVICE_BLOCKS = [
  { key: 'budgetPlan', label: '预算拆分', icon: Coins },
  { key: 'reminders', label: '预约与风险提醒', icon: ShieldAlert },
  { key: 'backups', label: '备选方案', icon: CloudSun }
]
const advice = computed(() => store.adviceObj)

/* ---------- 按天 AI 重排（反馈一句话，只重排这一天） ---------- */
const regen = ref(null) // { dayId, feedback }：反馈输入框打开时非空
function startRegen(d) {
  regen.value = { dayId: d.day?.id, feedback: '' }
}
async function submitRegen() {
  if (!regen.value) return
  const { dayId, feedback } = regen.value
  regen.value = null
  await store.regenDay(dayId, feedback)
}

/* ---------- 天信息编辑（主题/日期） ---------- */
const dayEditing = ref(null) // { dayId, theme, date }
function startDayEdit(d) {
  dayEditing.value = {
    dayId: d.day?.id,
    theme: d.day?.theme || '',
    date: d.day?.date ? String(d.day.date).slice(0, 10) : ''
  }
}
async function saveDayForm() {
  if (!dayEditing.value) return
  const { dayId, theme, date } = dayEditing.value
  const ok = await store.saveDay(dayId, { theme: (theme || '').trim(), date: date || null })
  if (ok) dayEditing.value = null
}
</script>

<template>
  <div class="itin-dock" :class="{ collapsed: store.itinCollapsed }">
    <section class="itin">
      <!-- 生成中遮罩 -->
      <div v-if="store.genBusy" class="gen-busy">
        <div class="gb-card">
          <Loader2 :size="22" class="spin" />
          <div class="gb-t">AI 正在规划行程</div>
          <div class="gb-s">按你的画像逐日挑选真实地点、排顺路的线…通常 30-60 秒</div>
        </div>
      </div>

      <!-- 空态 -->
      <div v-else-if="!days.length" class="itin-empty">
        <template v-if="store.genError">
          <div class="ie-ic err"><ShieldCheck :size="22" /></div>
          <h4 class="serif">生成没成功</h4>
          <p class="ie-tip">{{ store.genError }}</p>
          <button class="btn retry" @click="store.generate()">
            <Sparkles :size="15" /> 重试生成
          </button>
        </template>
        <template v-else>
          <div class="ie-ic"><Sparkles :size="22" /></div>
          <h4 class="serif">行程会出现在这里</h4>
          <p class="ie-tip">在左侧对话里说出你的出行需求，我会把每天的安排整理成卡片，并同步到右侧地图。</p>
        </template>
      </div>

      <!-- 有行程 -->
      <template v-else>
        <header class="it-head">
          <div class="it-top">
            <div class="it-chip"><Sparkles :size="13" /> AI 推荐行程</div>
            <div class="it-acts">
              <button
                class="ia-btn"
                :disabled="store.shareBusy"
                :title="store.shareToken ? '生成新的分享链接' : '生成分享链接'"
                @click="store.share()"
              >
                <Loader2 v-if="store.shareBusy" :size="13" class="spin" />
                <Share2 v-else :size="13" />
              </button>
              <button class="ia-btn danger" :disabled="store.editBusy" title="删除行程" @click="askRemoveItinerary">
                <Trash2 :size="13" />
              </button>
            </div>
          </div>

          <!-- 标题：行内编辑 -->
          <div v-if="titleEditing" class="it-title-edit">
            <input
              ref="titleInput"
              v-model="titleDraft"
              class="it-title-input"
              placeholder="行程标题"
              @keydown.enter="saveTitle"
              @keydown.esc="titleEditing = false"
            />
            <button class="ia-btn" title="保存" @click="saveTitle"><Check :size="13" /></button>
            <button class="ia-btn" title="取消" @click="titleEditing = false"><X :size="13" /></button>
          </div>
          <div v-else class="it-title-row">
            <h3 class="serif it-title">{{ it?.title || '我的行程' }}</h3>
            <button class="ia-btn title-pencil" title="改标题" @click="startTitleEdit"><Pencil :size="12" /></button>
          </div>

          <div class="it-meta">
            <span v-if="it?.days"><MapPin :size="13" /> {{ store.params.city || '目的地' }}</span>
            <span v-if="it?.days"><Calendar :size="13" /> {{ it.days }} 天</span>
            <span v-if="it?.budget"><Coins :size="13" /> ¥{{ it.budget }}</span>
          </div>
        </header>

        <div class="it-scroll">
          <article
            v-for="(d, di) in days"
            :key="d.day?.id || di"
            class="day-card"
            :class="{ active: di === store.activeDay, regenning: store.regenDayId === d.day?.id }"
            @click="store.setActiveDay(di)"
          >
            <div class="dc-head">
              <span class="dc-no">Day {{ di + 1 }}</span>
              <span class="dc-date" v-if="d.day?.date">{{ fmtDate(d.day.date) }}</span>
              <span class="dc-theme" v-if="d.day?.theme">{{ d.day.theme }}</span>
              <!-- 今日路线：当天相邻两点的分段导航（后端按真实坐标拼好） -->
              <button
                v-if="d.routeLinks && d.routeLinks.length"
                class="dc-tool nav"
                :title="`在高德地图中按今天的路线走（${d.routeLinks.length} 段）`"
                @click.stop="openNav(d.routeLinks[0].url)"
              ><Navigation :size="12" /></button>
              <button
                class="dc-tool edit"
                :disabled="store.editBusy"
                title="编辑主题/日期"
                @click.stop="startDayEdit(d)"
              ><Pencil :size="12" /></button>
              <button
                class="dc-tool regen"
                :disabled="store.regenDayId != null"
                title="AI 重排这一天"
                @click.stop="startRegen(d)"
              >
                <Loader2 v-if="store.regenDayId === d.day?.id" :size="12" class="spin" />
                <RefreshCw v-else :size="12" />
              </button>
              <button
                class="dc-tool del"
                :disabled="store.editBusy"
                title="删除这天"
                @click.stop="askRemoveDay(d)"
              ><Trash2 :size="12" /></button>
            </div>

            <!-- 编辑这天信息：主题/日期 -->
            <DayEditForm
              v-if="dayEditing && dayEditing.dayId === d.day?.id"
              :form="dayEditing"
              :busy="store.editBusy"
              :error="store.editError"
              @save="saveDayForm"
              @cancel="dayEditing = null"
            />

            <!-- 按天重排：一句反馈 → 只重排这一天 -->
            <div v-if="regen && regen.dayId === d.day?.id" class="regen-form" @click.stop>
              <input
                v-model="regen.feedback"
                placeholder="要怎么调？如：太赶了少排点，晚上加个夜市"
                @keydown.enter="submitRegen"
              />
              <div class="regen-acts">
                <button class="rg-btn ghost" @click="regen = null">取消</button>
                <button class="rg-btn solid" :disabled="store.regenDayId != null" @click="submitRegen">AI 重排</button>
              </div>
            </div>
            <div v-if="store.regenDayId === d.day?.id" class="regen-busy">
              <Loader2 :size="12" class="spin" /> AI 正在重排这一天…约 20-40 秒
            </div>
            <div class="dc-nodes">
              <template v-for="n in d.nodes" :key="n.id">
                <!-- 上一站到这里的真实距离/耗时：没算过就不占位，不编造 -->
                <div v-if="legFor(n)" class="leg" @click.stop>
                  <span class="leg-rail" />
                  <span class="leg-meta">{{ fmtKm(legFor(n).distance) }} · {{ fmtMin(legFor(n).duration) }}</span>
                  <button
                    v-if="legFor(n).navUrl"
                    class="leg-nav"
                    title="按这一段的路线导航"
                    @click="openNav(legFor(n).navUrl)"
                  ><Navigation :size="11" /> 导航</button>
                </div>
                <!-- 编辑既有节点：表单原位替换 -->
                <NodeEditForm
                  v-if="editing && editing.id === n.id"
                  :form="editing"
                  :busy="store.editBusy"
                  :error="store.editError"
                  @save="saveNodeForm"
                  @cancel="editing = null"
                />
                <div v-else class="poi" :class="{ abn: n.isAbnormal === 1 }">
                  <div class="poi-tile"><component :is="nodeIcon(n.nodeType)" :size="16" /></div>
                  <div class="poi-main">
                    <div class="poi-top">
                      <span class="poi-name">{{ n.placeName }}</span>
                      <span class="poi-tag">{{ n.nodeType }}</span>
                    </div>
                    <div class="poi-sub">
                      <span v-if="fmtTime(n)"><Clock :size="12" /> {{ fmtTime(n) }}</span>
                      <span v-if="n.cost"><Coins :size="12" /> ¥{{ n.cost }}</span>
                      <!-- 有真实坐标才显示导航；没坐标（AI 建议项/未核验）说明原因，不画假点 -->
                      <span v-if="n.located && n.navUrl" class="loc-ok"><MapPin :size="12" /> 已定位</span>
                      <span v-else-if="n.locationNote" class="loc-na">{{ n.locationNote }}</span>
                      <span v-if="n.isAbnormal === 1" class="abn-flag">地点待核验</span>
                    </div>
                  </div>
                  <div class="poi-acts">
                    <button
                      v-if="n.located && n.navUrl"
                      class="pa-btn nav"
                      title="在高德地图中导航到这里"
                      @click.stop="openNav(n.navUrl)"
                    ><Navigation :size="12" /></button>
                    <button class="pa-btn" :disabled="store.editBusy" title="编辑" @click.stop="startNodeEdit(n)"><Pencil :size="12" /></button>
                    <button class="pa-btn danger" :disabled="store.editBusy" title="删除" @click.stop="askRemoveNode(n)"><Trash2 :size="12" /></button>
                  </div>
                </div>
              </template>

              <!-- 新增节点：表单落在这天末尾 -->
              <NodeEditForm
                v-if="editing && !editing.id && editing.dayId === d.day?.id"
                :form="editing"
                :busy="store.editBusy"
                :error="store.editError"
                @save="saveNodeForm"
                @cancel="editing = null"
              />
              <button
                v-if="!(editing && editing.dayId === d.day?.id)"
                class="add-node"
                :disabled="store.editBusy"
                @click.stop="startNodeAdd(d)"
              ><Plus :size="12" /> 添加安排</button>
            </div>
          </article>

          <button class="add-day" :disabled="store.editBusy" @click="store.appendDay()">
            <Loader2 v-if="store.editBusy" :size="13" class="spin" />
            <Plus v-else :size="13" />
            再加一天
          </button>

          <!-- AI 方案说明：预算拆分 / 预约与风险提醒 / 备选方案（生成时随行程一起产出） -->
          <details v-if="advice" class="advice">
            <summary>
              <Sparkles :size="13" />
              <span>方案说明</span>
              <span class="adv-tip">预算 · 预约提醒 · 备选</span>
            </summary>
            <div class="adv-body">
              <div v-for="b in ADVICE_BLOCKS" :key="b.key" v-show="advice[b.key]" class="adv-block">
                <div class="adv-h"><component :is="b.icon" :size="12" /> {{ b.label }}</div>
                <p class="adv-p">{{ advice[b.key] }}</p>
              </div>
            </div>
          </details>

          <div class="it-foot">
            <!-- 分享链接：生成后出现，可复制/撤销 -->
            <transition name="pm">
              <div v-if="store.shareToken" class="share-row">
                <input class="share-url" readonly :value="shareUrl" @focus="($e) => $e.target.select()" />
                <button class="btn btn-ghost share-copy" @click="copyShare">{{ store.shareCopied ? '已复制' : '复制' }}</button>
                <button class="btn btn-ghost" :disabled="store.shareBusy" @click="store.unshare()">撤销</button>
              </div>
            </transition>
            <div class="v-err" v-if="store.editError">{{ store.editError }}</div>
            <!-- 路线：算相邻站点的真实距离；结果直接落在上面每两个节点之间 -->
            <div class="route-row">
              <div class="route-modes" role="group" aria-label="出行方式">
                <button :class="{ on: store.routeMode === 'car' }" :disabled="store.routeBusy" @click="store.routeMode = 'car'">驾车</button>
                <button :class="{ on: store.routeMode === 'walk' }" :disabled="store.routeBusy" @click="store.routeMode = 'walk'">步行</button>
              </div>
              <button class="btn btn-ghost route-btn" :disabled="store.routeBusy" @click="calcRoutes">
                <Loader2 v-if="store.routeBusy" :size="14" class="spin" />
                <RouteIcon v-else :size="14" />
                {{ store.routeBusy ? '正在算路线…' : store.routeSegments.length ? '重算路线' : '计算路线' }}
              </button>
            </div>
            <p class="v-msg route-err" v-if="store.routeError">{{ store.routeError }}</p>
            <button class="btn btn-ghost validate" :disabled="store.validateBusy" @click="store.validate()">
              <Loader2 v-if="store.validateBusy" :size="15" class="spin" />
              <ShieldCheck v-else :size="15" />
              {{ store.validateBusy ? '正在核验地点…' : '校验地点真实性' }}
            </button>
            <p class="v-msg" v-if="store.validateMsg">{{ store.validateMsg }}</p>
          </div>
        </div>
      </template>
    </section>

    <!-- 边缘把手：骑在行程右边界上，收起 / 拉出行程 -->
    <button
      class="seam-btn"
      @click="store.toggleItinCollapse()"
      :title="store.itinCollapsed ? '展开行程' : '收起行程，只看地图'"
      :aria-label="store.itinCollapsed ? '展开行程' : '收起行程'"
    >
      <ChevronsRight v-if="store.itinCollapsed" :size="18" />
      <ChevronsLeft v-else :size="18" />
    </button>

    <!-- 轻量 toast:删除成功等反馈 -->
    <Teleport to="body">
      <transition name="itoast">
        <div v-if="toastMsg" class="itoast">{{ toastMsg }}</div>
      </transition>
    </Teleport>
  </div>
</template>

<style scoped>
.itin-dock { position: relative; flex: none; height: 100%; display: flex; }
.itin { position: relative; height: 100%; width: clamp(280px, 24vw, 340px); flex: none; display: flex; flex-direction: column; background: var(--paper-raise); border: 1px solid var(--hair-2); border-radius: var(--r-lg); box-shadow: var(--shadow-1); overflow: hidden; transition: width var(--t-morph) var(--ease), opacity var(--t-base) var(--ease); }
.itin-dock.collapsed .itin { width: 0; border-left-width: 0; border-right-width: 0; box-shadow: none; opacity: 0; }

/* 边缘把手：骑在行程右边界上，收起 / 拉出行程 */
.seam-btn {
  position: absolute; top: 50%; right: -15px; transform: translateY(-50%); z-index: 6;
  width: 30px; height: 46px; border-radius: 12px;
  border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2);
  box-shadow: var(--shadow-1); cursor: pointer; display: grid; place-items: center;
  transition: color var(--t-fast) var(--ease), border-color var(--t-fast) var(--ease), background var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.seam-btn:hover { color: var(--blue); border-color: var(--blue-line); background: var(--blue-wash); }
.seam-btn:active { transform: translateY(-50%) scale(0.9); }

.it-head { flex: none; padding: 20px 22px 14px; border-bottom: 1px solid var(--hair-3); }
.it-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.it-chip { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--blue); background: var(--blue-wash); border-radius: 999px; padding: 4px 11px; }
.it-acts { display: flex; gap: 4px; }
/* 头部小图标按钮：默认隐身，随面板悬停或自身悬停浮现 */
.ia-btn {
  width: 26px; height: 26px; border-radius: var(--r-sm); border: 0; background: none;
  color: var(--ink-4); cursor: pointer; display: grid; place-items: center; flex: none;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.ia-btn:hover { background: var(--paper-sink); color: var(--blue); }
.ia-btn.danger:hover { background: var(--seal-wash); color: var(--seal); }
.ia-btn:disabled { opacity: 0.5; cursor: default; }

.it-title-row { display: flex; align-items: flex-start; gap: 6px; margin: 0 0 8px; }
.it-title-row .it-title { margin: 0; }
.title-pencil { opacity: 0; transition: opacity var(--t-fast) var(--ease); }
.it-title-row:hover .title-pencil { opacity: 1; }
.it-title-edit { display: flex; align-items: center; gap: 4px; margin: 0 0 8px; }
.it-title-input {
  flex: 1; min-width: 0; font-family: var(--f-body); font-size: 18px; color: var(--ink);
  border: 1px solid var(--blue-line); border-radius: var(--r-md); background: var(--paper);
  padding: 5px 10px; outline: none; box-shadow: 0 0 0 3px var(--blue-wash);
}
.it-title { margin: 0 0 8px; font-size: 23px; color: var(--ink); line-height: 1.25; }
.it-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 12.5px; color: var(--ink-3); }
.it-meta span { display: inline-flex; align-items: center; gap: 5px; }

.it-scroll { flex: 1; overflow-y: auto; padding: 16px 18px 24px; display: flex; flex-direction: column; gap: 14px; }

.day-card { background: var(--paper); border: 1px solid var(--hair-2); border-radius: var(--r-lg); padding: 14px 16px; cursor: pointer; transition: border-color var(--t-base) var(--ease), box-shadow var(--t-base) var(--ease), transform var(--t-fast) var(--ease); }
.day-card:hover { box-shadow: var(--shadow-1); }
.day-card.active { border-color: var(--blue-line); box-shadow: 0 0 0 3px var(--blue-wash); }
.dc-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.dc-no { font-family: var(--f-mono); font-size: 13px; font-weight: 700; color: var(--blue); }
.dc-date { font-size: 12px; color: var(--ink-3); }
.dc-theme { font-size: 12.5px; color: var(--ink-2); margin-left: auto; font-style: italic; }
/* 天卡片工具（AI 重排 / 删除）：与主题标签同排，悬停卡片时浮现 */
.dc-tool {
  flex: none; width: 22px; height: 22px; border-radius: var(--r-sm); border: 0; background: none;
  color: var(--ink-4); cursor: pointer; display: grid; place-items: center; align-self: center;
  opacity: 0; transition: opacity var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.day-card:hover .dc-tool, .day-card.regenning .dc-tool { opacity: 1; }
.dc-tool.del:hover { background: var(--seal-wash); color: var(--seal); }
.dc-tool.regen:hover, .dc-tool.edit:hover { background: var(--blue-wash); color: var(--blue); }
.dc-tool:disabled { opacity: 0.4; cursor: default; }

/* 按天重排：一句反馈的输入小卡 + 重排进行中提示 */
.regen-form {
  display: flex; flex-direction: column; gap: 7px;
  margin-bottom: 12px; padding: 10px;
  background: var(--paper); border: 1px dashed var(--blue-line); border-radius: var(--r-md);
}
.regen-form input {
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink);
  padding: 7px 9px; border: 1px solid var(--hair-2); border-radius: var(--r-sm);
  background: var(--paper-raise); outline: none;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.regen-form input:focus { border-color: var(--blue-line); box-shadow: 0 0 0 2px var(--blue-wash); }
.regen-acts { display: flex; justify-content: flex-end; gap: 6px; }
.rg-btn { border-radius: var(--r-sm); cursor: pointer; font-family: var(--f-body); font-size: 12px; padding: 5px 12px; transition: all var(--t-fast) var(--ease); }
.rg-btn.ghost { border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2); }
.rg-btn.ghost:hover { border-color: var(--blue-line); color: var(--blue); }
.rg-btn.solid { border: 1px solid var(--blue); background: var(--blue); color: #fff; }
.rg-btn.solid:hover:not(:disabled) { filter: brightness(1.08); }
.rg-btn:disabled { opacity: 0.55; cursor: default; }
.regen-busy { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--blue); margin-bottom: 12px; }

/* AI 方案说明：原生 details 折叠，预算拆分/预约提醒/备选三块 */
.advice { border: 1px solid var(--hair-2); border-radius: var(--r-lg); background: var(--paper); overflow: hidden; }
.advice summary {
  list-style: none; cursor: pointer;
  display: flex; align-items: center; gap: 7px;
  padding: 11px 14px; font-size: 13px; font-weight: 600; color: var(--ink-2);
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.advice summary::-webkit-details-marker { display: none; }
.advice summary:hover { background: var(--paper-sink); color: var(--blue); }
.advice summary .adv-tip { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--ink-4); }
.advice[open] summary { border-bottom: 1px solid var(--hair-3); color: var(--blue); }
.adv-body { padding: 12px 14px; display: flex; flex-direction: column; gap: 12px; }
.adv-h { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 600; color: var(--blue); margin-bottom: 5px; }
.adv-p { margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--ink-2); white-space: pre-wrap; }

.dc-nodes { display: flex; flex-direction: column; gap: 8px; }

/* 相邻两站之间：一条竖线 + 距离/耗时，右端可直达该段导航 */
.leg { display: flex; align-items: center; gap: 8px; padding: 0 10px 0 20px; margin-block: -4px; min-height: 20px; }
.leg-rail { flex: none; width: 1px; height: 20px; background: linear-gradient(var(--hair), var(--hair-2)); }
.leg-meta { font-size: 11.5px; color: var(--ink-3); font-family: var(--f-mono); }
.leg-nav { display: inline-flex; align-items: center; gap: 3px; border: 0; background: none; padding: 2px 6px; border-radius: 999px; font-size: 11px; color: var(--blue); cursor: pointer; }
.leg-nav:hover { background: var(--blue-wash); }
.poi { display: flex; gap: 12px; padding: 10px; border-radius: var(--r-md); background: var(--paper-raise); transition: background var(--t-fast) var(--ease); }
.poi:hover { background: var(--paper-sink); }
.poi.abn { background: var(--seal-wash); }
.poi-tile { flex: none; width: 40px; height: 40px; border-radius: var(--r-md); background: var(--blue-wash); color: var(--blue); display: grid; place-items: center; }
.poi.abn .poi-tile { background: #fff; color: var(--seal); }
.poi-main { min-width: 0; flex: 1; }
.poi-top { display: flex; align-items: center; gap: 8px; }
.poi-name { font-size: 14px; color: var(--ink); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.poi-tag { flex: none; font-size: 11px; color: var(--ink-3); border: 1px solid var(--hair-2); border-radius: 5px; padding: 1px 6px; }
.poi-sub { display: flex; flex-wrap: wrap; gap: 2px 14px; margin-top: 4px; font-size: 12px; color: var(--ink-3); }
.poi-sub span { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
.abn-flag { color: var(--seal) !important; }
/* 节点操作：悬停浮现的编辑/删除 */
.poi-acts { flex: none; display: flex; gap: 2px; align-items: center; opacity: 0; transition: opacity var(--t-fast) var(--ease); }
.poi:hover .poi-acts, .poi-acts:focus-within { opacity: 1; }
.pa-btn {
  width: 24px; height: 24px; border-radius: var(--r-sm); border: 0; background: none;
  color: var(--ink-4); cursor: pointer; display: grid; place-items: center;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.pa-btn:hover { background: var(--paper); color: var(--blue); }
.pa-btn.danger:hover { background: var(--paper); color: var(--seal); }
/* 导航按钮：主色强调，它是这一行里用户最想点的动作 */
.pa-btn.nav { color: var(--blue); }
.pa-btn.nav:hover { background: var(--blue); color: #fff; }
.dc-tool.nav:hover { background: var(--blue-wash); color: var(--blue); }
/* 已定位 / 未定位的提示：都用中性色，不抢红色（红只留给"地点待核验"） */
.loc-ok { color: var(--blue); display: inline-flex; align-items: center; gap: 2px; }
.loc-na { color: var(--ink-4); font-size: 11.5px; }
.pa-btn:disabled { opacity: 0.4; cursor: default; }

/* 添加安排 / 再加一天：虚线幽灵按钮 */
.add-node {
  display: flex; align-items: center; justify-content: center; gap: 5px;
  border: 1px dashed var(--hair-2); border-radius: var(--r-md); background: none;
  color: var(--ink-4); font-family: var(--f-body); font-size: 12px;
  padding: 7px 0; cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), background var(--t-fast) var(--ease);
}
.add-node:hover:not(:disabled) { border-color: var(--blue-line); color: var(--blue); background: var(--blue-wash); }
.add-node:disabled { opacity: 0.5; cursor: default; }
.add-day {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px dashed var(--hair-2); border-radius: var(--r-lg); background: none;
  color: var(--ink-3); font-family: var(--f-body); font-size: 13px;
  padding: 10px 0; cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), background var(--t-fast) var(--ease);
}
.add-day:hover:not(:disabled) { border-color: var(--blue-line); color: var(--blue); background: var(--blue-wash); }
.add-day:disabled { opacity: 0.5; cursor: default; }

/* 分享链接行 */
.share-row { display: flex; align-items: center; gap: 6px; width: 100%; }
.share-url {
  flex: 1; min-width: 0; font-family: var(--f-mono); font-size: 11px; color: var(--ink-2);
  border: 1px solid var(--hair-2); border-radius: var(--r-md); background: var(--paper);
  padding: 7px 10px; outline: none;
}
.share-copy { flex: none; }
.v-err { font-size: 12px; color: var(--seal); text-align: center; width: 100%; }

.route-row { display: flex; align-items: center; gap: 8px; width: 100%; justify-content: center; }
.route-btn { flex: none; }
.route-modes { display: inline-flex; border: 1px solid var(--hair-2); border-radius: 999px; padding: 2px; background: var(--paper-raise); }
.route-modes button { border: 0; background: none; padding: 4px 9px; border-radius: 999px; font-size: 11.5px; color: var(--ink-3); cursor: pointer; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.route-modes button.on { background: var(--ink); color: var(--paper); }
.route-modes button:disabled { cursor: default; opacity: .6; }
.route-err { color: var(--seal); font-family: var(--f-body); }
.validate { justify-content: center; margin-top: 4px; }
.it-foot { display: flex; flex-direction: column; gap: 8px; align-items: center; margin-top: 6px; }
.v-msg { margin: 0; font-size: 12px; color: var(--ink-3); font-family: var(--f-mono); }

.itin-empty { flex: 1; display: grid; place-content: center; justify-items: center; text-align: center; padding: 30px; }
.ie-ic { width: 52px; height: 52px; border-radius: 50%; background: var(--blue-wash); color: var(--blue); display: grid; place-items: center; margin-bottom: 14px; }
.ie-ic.err { background: var(--seal-wash); color: var(--seal); }
.btn.retry { margin-top: 14px; justify-content: center; }
.itin-empty h4 { margin: 0 0 8px; font-size: 20px; color: var(--ink); }
.ie-tip { margin: 0; font-size: 13px; line-height: 1.7; color: var(--ink-3); max-width: 260px; }

.gen-busy { position: absolute; inset: 0; z-index: 6; display: grid; place-content: center; justify-items: center; background: color-mix(in srgb, var(--paper-raise) 80%, transparent); backdrop-filter: blur(3px); }
.gb-card { display: flex; flex-direction: column; align-items: center; gap: 8px; text-align: center; }
.gb-t { font-size: 15px; color: var(--ink); font-weight: 600; }
.gb-s { font-size: 12.5px; color: var(--ink-3); }
.spin { animation: xjspin 0.9s linear infinite; color: var(--blue); }
@keyframes xjspin { to { transform: rotate(360deg); } }

/* toast:底部居中浮出(删除反馈) */
.itoast {
  position: fixed; left: 50%; bottom: 34px; transform: translateX(-50%); z-index: 1300;
  background: var(--ink); color: var(--paper);
  font-family: var(--f-body); font-size: 13px;
  border-radius: 999px; padding: 9px 20px;
  box-shadow: var(--shadow-2);
}
.itoast-enter-active { transition: opacity var(--t-base) var(--ease), transform var(--t-morph) var(--ease); }
.itoast-leave-active { transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.itoast-enter-from, .itoast-leave-to { opacity: 0; transform: translateX(-50%) translateY(12px) scale(0.95); }
</style>
