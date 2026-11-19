<script setup>
import { ref, computed } from 'vue'
import { useExploreStore } from '../stores/explore'
import NodeEditForm from './NodeEditForm.vue'
import DayEditForm from './DayEditForm.vue'
import ConfirmModal from './ConfirmModal.vue'
import {
  MapPin, Utensils, Car, BedDouble, Mountain, Clock, Coins, ChevronDown,
  Pencil, Trash2, Plus, RefreshCw, Loader2, Sparkles, ShieldAlert, CloudSun,
  Navigation, Route as RouteIcon
} from 'lucide-vue-next'

/**
 * 「每日规划」面板（我的行程页右栏）—— 每天一个可展开/收起的分组，组内竖向时间线。
 * 展示数据走 props.days（与 explore.itinerary 同源）；所有编辑动作直接调 explore store：
 * 节点增改删、天信息(主题/日期)编辑、删天/加天、按天 AI 重排、方案说明(advice)展示，
 * 与探索页行程面板同一套 store action，两处行为完全一致。
 */
const props = defineProps({ days: { type: Array, default: () => [] } })
const store = useExploreStore()

const TYPE_ICON = { 景点: MapPin, 餐饮: Utensils, 交通: Car, 住宿: BedDouble }
const icon = (t) => TYPE_ICON[t] || Mountain

// 分组展开/收起：collapsed[key]=true 表示该天收起，默认全展开
const collapsed = ref({})
const dayKey = (d, di) => String(d.day?.id ?? di)
const isOpen = (k) => !collapsed.value[k]
function toggle(k) {
  collapsed.value = { ...collapsed.value, [k]: !collapsed.value[k] }
}

/* ---------- 节点编辑/新增（同一时刻只开一个表单） ---------- */
const editing = ref(null) // { id:null|nodeId, dayId, placeId?, placeName, nodeType, seq, startTime, endTime, cost }
function startNodeAdd(d) {
  const seq = ((d.nodes || []).length ? Math.max(...d.nodes.map((n) => n.seq || 0)) : 0) + 1
  editing.value = { id: null, dayId: d.day?.id, placeId: null, placeName: '', nodeType: '景点', seq, startTime: '', endTime: '', cost: '' }
}
function startNodeEdit(n) {
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
  askConfirm({
    title: '删除后，这条安排将不可恢复',
    description: `将把「${n.placeName}」从这一天的行程中移除。`,
    confirmText: '删除该安排',
    run: async () => {
      if (editing.value?.id === n.id) editing.value = null
      await store.removeNode(n.id)
    }
  })
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

/* ---------- 按天 AI 重排（一句反馈，只重排这一天） ---------- */
const regen = ref(null) // { dayId, feedback }
function startRegen(d) {
  regen.value = { dayId: d.day?.id, feedback: '' }
}
async function submitRegen() {
  if (!regen.value) return
  const { dayId, feedback } = regen.value
  regen.value = null
  await store.regenDay(dayId, feedback)
}
function askRemoveDay(d) {
  askConfirm({
    title: '删除后，这一天将不可恢复',
    description: `Day ${d.day?.dayIndex || ''} 的全部安排都会被移除。`,
    confirmText: '删除该天',
    run: async () => {
      editing.value = null
      dayEditing.value = null
      await store.removeDay(d.day?.id)
    }
  })
}

/* ---------- 统一确认弹窗（与 TripsView 同一套 ConfirmModal）---------- */
const confirmBox = ref(null)
const confirmBusy = ref(false)
function askConfirm(box) {
  confirmBox.value = box
}
async function onConfirm() {
  const box = confirmBox.value
  if (!box || confirmBusy.value) return
  confirmBusy.value = true
  try {
    await box.run()
    confirmBox.value = null
  } catch (e) {
    store.editError = e.message || '操作失败，请重试'
  } finally {
    confirmBusy.value = false
  }
}

/* ---------- 方案说明（与探索页同源） ---------- */
const ADVICE_BLOCKS = [
  { key: 'budgetPlan', label: '预算拆分', icon: Coins },
  { key: 'reminders', label: '预约与风险提醒', icon: ShieldAlert },
  { key: 'backups', label: '备选方案', icon: CloudSun }
]
const advice = computed(() => store.adviceObj)

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

/* ---------- 相邻站点路线（与探索页行程面板同源） ---------- */
const routeMsg = ref('')
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
  routeMsg.value = ''
  const n = await store.calcRoutes()
  if (typeof n === 'number') routeMsg.value = n ? `已算出 ${n} 段路线` : '相邻站点还没有可用坐标'
}
function openNav(url) {
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<template>
  <div class="arrange">
    <section
      v-for="(d, di) in days"
      :key="dayKey(d, di)"
      class="dgroup"
      :class="{ open: isOpen(dayKey(d, di)), regenning: store.regenDayId === d.day?.id }"
    >
      <!-- 分组头：左侧点击展开/收起，右侧工具（编辑/重排/删除）悬停浮现 -->
      <div class="dg-headrow">
        <button class="dg-head" @click="toggle(dayKey(d, di))" :aria-expanded="isOpen(dayKey(d, di))">
          <ChevronDown :size="16" class="dg-chev" :class="{ closed: !isOpen(dayKey(d, di)) }" />
          <span class="dg-badge mono">DAY {{ d.day?.dayIndex || di + 1 }}</span>
          <span class="dg-date mono" v-if="d.day?.date">{{ fmtDate(d.day.date) }}</span>
          <span class="dg-theme serif" v-if="d.day?.theme">{{ d.day.theme }}</span>
          <span class="dg-count mono">{{ (d.nodes || []).length }} 站</span>
        </button>
        <div class="dg-tools">
          <button class="dg-tool edit" :disabled="store.editBusy" title="编辑主题/日期" @click="startDayEdit(d)">
            <Pencil :size="12" />
          </button>
          <button class="dg-tool regen" :disabled="store.regenDayId != null" title="AI 重排这一天" @click="startRegen(d)">
            <Loader2 v-if="store.regenDayId === d.day?.id" :size="12" class="spin" />
            <RefreshCw v-else :size="12" />
          </button>
          <button class="dg-tool del" :disabled="store.editBusy" title="删除这天" @click="askRemoveDay(d)">
            <Trash2 :size="12" />
          </button>
        </div>
      </div>

      <div
        class="dg-body"
        :style="{ maxHeight: isOpen(dayKey(d, di)) ? '2000px' : '0px', opacity: isOpen(dayKey(d, di)) ? 1 : 0 }"
      >
        <div class="dg-inner">
          <!-- 编辑这天信息：主题/日期 -->
          <DayEditForm
            v-if="dayEditing && dayEditing.dayId === d.day?.id"
            :form="dayEditing"
            :busy="store.editBusy"
            :error="store.editError"
            @save="saveDayForm"
            @cancel="dayEditing = null"
          />

          <!-- 按天重排：一句反馈 -->
          <div v-if="regen && regen.dayId === d.day?.id" class="regen-form">
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

          <ol class="tl">
            <template v-for="n in d.nodes" :key="n.id">
              <!-- 上一站到这里的真实距离/耗时；没算过就不占位 -->
              <li v-if="legFor(n)" class="tl-leg">
                <span class="tlleg-line" />
                <span class="mono">{{ fmtKm(legFor(n).distance) }} · {{ fmtMin(legFor(n).duration) }}</span>
                <button v-if="legFor(n).navUrl" class="tlleg-nav" title="按这一段的路线导航" @click="openNav(legFor(n).navUrl)">
                  <Navigation :size="11" /> 导航
                </button>
              </li>
              <!-- 编辑既有节点：表单原位替换 -->
              <li v-if="editing && editing.id === n.id" class="tl-form">
                <NodeEditForm
                  :form="editing"
                  :busy="store.editBusy"
                  :error="store.editError"
                  @save="saveNodeForm"
                  @cancel="editing = null"
                />
              </li>
              <li v-else class="tl-item" :class="{ abn: n.isAbnormal === 1 }">
                <span class="tl-dot"><component :is="icon(n.nodeType)" :size="14" /></span>
                <div class="tl-body">
                  <div class="tl-top">
                    <span class="tl-name">{{ n.placeName }}</span>
                    <span class="tl-tag">{{ n.nodeType }}</span>
                  </div>
                  <div class="tl-meta">
                    <span v-if="fmtTime(n)"><Clock :size="12" /> {{ fmtTime(n) }}</span>
                    <span v-if="n.cost"><Coins :size="12" /> ¥{{ n.cost }}</span>
                    <span v-if="n.isAbnormal === 1" class="abn-flag">地点待核验</span>
                  </div>
                </div>
                <div class="tl-acts">
                  <button class="ta-btn" :disabled="store.editBusy" title="编辑" @click="startNodeEdit(n)"><Pencil :size="12" /></button>
                  <button class="ta-btn danger" :disabled="store.editBusy" title="删除" @click="askRemoveNode(n)"><Trash2 :size="12" /></button>
                </div>
              </li>
            </template>

            <!-- 新增节点：表单落在这天末尾 -->
            <li v-if="editing && !editing.id && editing.dayId === d.day?.id" class="tl-form">
              <NodeEditForm
                :form="editing"
                :busy="store.editBusy"
                :error="store.editError"
                @save="saveNodeForm"
                @cancel="editing = null"
              />
            </li>
          </ol>

          <button
            v-if="!(editing && editing.dayId === d.day?.id)"
            class="add-node"
            :disabled="store.editBusy"
            @click="startNodeAdd(d)"
          ><Plus :size="12" /> 添加安排</button>
        </div>
      </div>
    </section>

    <button class="add-day" :disabled="store.editBusy" @click="store.appendDay()">
      <Loader2 v-if="store.editBusy" :size="13" class="spin" />
      <Plus v-else :size="13" />
      再加一天
    </button>

    <!-- 路线：算相邻站点的真实距离，结果落在上面每两站之间 -->
    <div class="route-row">
      <div class="route-modes" role="group" aria-label="出行方式">
        <button :class="{ on: store.routeMode === 'car' }" :disabled="store.routeBusy" @click="store.routeMode = 'car'">驾车</button>
        <button :class="{ on: store.routeMode === 'walk' }" :disabled="store.routeBusy" @click="store.routeMode = 'walk'">步行</button>
      </div>
      <button class="route-btn" :disabled="store.routeBusy" @click="calcRoutes">
        <Loader2 v-if="store.routeBusy" :size="13" class="spin" />
        <RouteIcon v-else :size="13" />
        {{ store.routeBusy ? '正在算路线…' : store.routeSegments.length ? '重算路线' : '计算路线' }}
      </button>
    </div>
    <p class="route-msg" v-if="store.routeError">{{ store.routeError }}</p>
    <p class="route-msg" v-else-if="routeMsg">{{ routeMsg }}</p>

    <!-- AI 方案说明：预算拆分 / 预约与风险提醒 / 备选方案 -->
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

    <div class="v-err" v-if="store.editError && !editing && !dayEditing">{{ store.editError }}</div>

    <!-- 统一确认弹窗:删除安排/删除这天 -->
    <ConfirmModal
      :open="!!confirmBox"
      :title="confirmBox?.title"
      :description="confirmBox?.description || ''"
      :confirm-text="confirmBox?.confirmText"
      :busy="confirmBusy"
      danger
      @confirm="onConfirm"
      @cancel="confirmBox = null"
    />
  </div>
</template>

<style scoped>
/* ===== 每日规划：开放式分组 =====
   不套圆角卡：每天一行分组头 + 组间发丝线,展开内容贴左对齐时间线;
   全灰阶,唯一彩色 = 「地点待核验」的印泥红;AI 重排进行中 = 左缘一道墨色竖条。 */
.arrange { display: flex; flex-direction: column; }
.mono { font-family: var(--f-mono); }

/* 分组：行式分区,发丝线分隔 */
.dgroup { border-bottom: 1px solid var(--hair-3); padding: 2px 0 0; transition: background var(--t-fast) var(--ease); }
.dgroup:last-of-type { border-bottom: 0; }
.dgroup.regenning { box-shadow: inset 2px 0 0 var(--ink); }

/* 头部：整行可点,悬停沉灰;左侧图标+mono 标签,主题衬线为主文案 */
.dg-headrow { display: flex; align-items: stretch; }
.dg-head { flex: 1; min-width: 0; display: flex; align-items: center; gap: 9px; padding: 11px 6px 11px 2px; border: 0; background: none; cursor: pointer; font-family: var(--f-body); text-align: left; transition: color var(--t-fast) var(--ease); }
.dg-chev { flex: none; color: var(--ink-4); transition: transform var(--t-morph) var(--ease), color var(--t-fast) var(--ease); }
.dg-chev.closed { transform: rotate(-90deg); }
.dgroup.open .dg-chev { color: var(--ink-2); }
.dg-badge { font-size: 10.5px; font-weight: 600; letter-spacing: 0.1em; color: var(--ink-3); line-height: 1; flex: none; white-space: nowrap; }
.dg-date { font-size: 11px; color: var(--ink-4); line-height: 1; flex: none; white-space: nowrap; }
.dg-theme { font-size: 13.5px; color: var(--ink); font-style: italic; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.dg-count { margin-left: auto; font-size: 11px; color: var(--ink-4); flex: none; white-space: nowrap; }
.dg-tools { flex: none; display: flex; align-items: center; gap: 2px; padding: 0 2px; opacity: 0; transition: opacity var(--t-fast) var(--ease); }
.dgroup:hover .dg-tools, .dgroup.regenning .dg-tools, .dg-tools:focus-within { opacity: 1; }
.dg-tool {
  width: 24px; height: 24px; border-radius: var(--r-sm); border: 0; background: none;
  color: var(--ink-4); cursor: pointer; display: grid; place-items: center;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.dg-tool:hover { background: var(--paper-raise); color: var(--ink); transform: scale(1.08); }
.dg-tool:active { transform: scale(0.92); }
.dg-tool.del:hover { background: var(--seal-wash); color: var(--seal); }
.dg-tool:disabled { opacity: 0.35; cursor: default; }

/* 展开/收起：max-height/opacity 由内联 style 驱动，这里只做裁剪与过渡 */
.dg-body { overflow: hidden; transition: max-height var(--t-morph) var(--ease), opacity var(--t-morph) var(--ease); }
.dg-inner { min-height: 0; display: flex; flex-direction: column; gap: 6px; padding: 2px 0 14px; }

/* 时间线：细发丝线 + 灰底小圆图标,无描边无投影 */
.tl { position: relative; list-style: none; margin: 0; padding: 0 8px 2px 4px; }
.tl::before { content: ''; position: absolute; left: 15px; top: 14px; bottom: 16px; width: 1px; background: var(--hair-2); }
.tl-item { position: relative; display: flex; gap: 10px; padding: 6px 0; border-radius: 6px; transition: background var(--t-fast) var(--ease); }
.tl-dot { position: relative; z-index: 1; flex: none; width: 23px; height: 23px; border-radius: 50%; background: var(--paper-raise); color: var(--ink-3); display: grid; place-items: center; transition: transform var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.tl-item:hover .tl-dot { transform: scale(1.08); background: var(--paper-sink); color: var(--ink); }
.tl-item.abn .tl-dot { background: var(--seal-wash); color: var(--seal); }
.tl-body { min-width: 0; flex: 1; padding-top: 1px; }
.tl-top { display: flex; align-items: center; gap: 8px; }
.tl-name { font-size: 13.5px; color: var(--ink); font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.tl-tag { flex: none; font-size: 10px; color: var(--ink-4); border: 1px solid var(--hair-2); border-radius: 4px; padding: 0.5px 5px; }
.tl-meta { display: flex; gap: 10px; margin-top: 3px; font-size: 11.5px; color: var(--ink-4); flex-wrap: wrap; }
.tl-meta span { display: inline-flex; align-items: center; gap: 4px; }
.abn-flag { color: var(--seal) !important; }
/* 节点操作：悬停浮现,灰底无投影 */
.tl-acts { position: absolute; right: 0; top: 6px; display: flex; gap: 2px; opacity: 0; transition: opacity var(--t-fast) var(--ease); }
.tl-item:hover .tl-acts, .tl-acts:focus-within { opacity: 1; }
.ta-btn { width: 24px; height: 24px; border-radius: var(--r-sm); border: 0; background: var(--paper-raise); color: var(--ink-4); cursor: pointer; display: grid; place-items: center; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.ta-btn:hover { background: var(--paper-sink); color: var(--ink); transform: scale(1.08); }
.ta-btn:active { transform: scale(0.92); }
.ta-btn.danger:hover { background: var(--seal-wash); color: var(--seal); }
.ta-btn:disabled { opacity: 0.4; cursor: default; }
/* 表单行（节点/天编辑）不参与时间线圆点布局 */
.tl-form { list-style: none; padding: 2px 0 4px; }

/* 相邻两站之间：距离/耗时贴时间线内缩进，保持竖线不断 */
.tl-leg { list-style: none; display: flex; align-items: center; gap: 8px; padding: 0 0 0 33px; margin-top: -3px; min-height: 18px; font-size: 11px; color: var(--ink-4); }
.tlleg-line { flex: none; width: 10px; height: 1px; background: var(--hair); }
.tlleg-nav { display: inline-flex; align-items: center; gap: 3px; border: 0; background: none; padding: 1px 6px; border-radius: 999px; font-size: 11px; color: var(--blue); cursor: pointer; }
.tlleg-nav:hover { background: var(--blue-wash); }

/* 路线计算：默认跟「添加安排」同一套安静虚线语言 */
.route-row { display: flex; align-items: center; justify-content: center; gap: 8px; margin-top: 10px; }
.route-modes { display: inline-flex; border: 1px solid var(--hair-2); border-radius: 999px; padding: 2px; }
.route-modes button { border: 0; background: none; padding: 3px 9px; border-radius: 999px; font-size: 11.5px; color: var(--ink-4); cursor: pointer; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.route-modes button.on { background: var(--ink); color: var(--paper); }
.route-modes button:disabled { opacity: .6; cursor: default; }
.route-btn { display: inline-flex; align-items: center; gap: 5px; border: 1px dashed var(--hair-2); border-radius: 8px; background: none; color: var(--ink-4); font-family: var(--f-body); font-size: 12px; padding: 6px 12px; cursor: pointer; transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), background var(--t-fast) var(--ease); }
.route-btn:hover:not(:disabled) { border-color: var(--hair); color: var(--ink); background: var(--paper-raise); }
.route-btn:disabled { opacity: .55; cursor: default; }
.route-msg { margin: 6px 0 0; font-size: 11.5px; color: var(--ink-4); text-align: center; font-family: var(--f-mono); }

/* 添加安排 / 再加一天：安静虚线,悬停沉灰 */
.tl .add-node { margin: 2px 0 2px 33px; }
.add-node {
  display: flex; align-items: center; justify-content: center; gap: 5px;
  border: 1px dashed var(--hair-2); border-radius: 8px; background: none;
  color: var(--ink-4); font-family: var(--f-body); font-size: 12px;
  padding: 6px 0; cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), background var(--t-fast) var(--ease);
}
.add-node:hover:not(:disabled) { border-color: var(--hair); color: var(--ink); background: var(--paper-raise); }
.add-node:disabled { opacity: 0.5; cursor: default; }
.add-day {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px dashed var(--hair-2); border-radius: var(--r-md); background: none;
  color: var(--ink-3); font-family: var(--f-body); font-size: 12.5px;
  padding: 9px 0; margin-top: 12px; cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease), background var(--t-fast) var(--ease);
}
.add-day:hover:not(:disabled) { border-color: var(--hair); color: var(--ink); background: var(--paper-raise); }
.add-day:disabled { opacity: 0.5; cursor: default; }

/* 按天重排：反馈输入 + 进行中提示 */
.regen-form { display: flex; flex-direction: column; gap: 7px; margin: 0; padding: 9px; background: var(--paper-raise); border: 1px dashed var(--hair); border-radius: var(--r-md); }
.regen-form input {
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink);
  padding: 7px 9px; border: 1px solid var(--hair-2); border-radius: var(--r-sm);
  background: var(--paper); outline: none;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.regen-form input:focus { border-color: var(--ink-3); box-shadow: 0 0 0 2px rgba(21, 23, 27, 0.07); }
.regen-acts { display: flex; justify-content: flex-end; gap: 6px; }
.rg-btn { border-radius: var(--r-sm); cursor: pointer; font-family: var(--f-body); font-size: 12px; padding: 5px 12px; transition: all var(--t-fast) var(--ease); }
.rg-btn.ghost { border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2); }
.rg-btn.ghost:hover { border-color: var(--hair); color: var(--ink); background: var(--paper-raise); }
.rg-btn.solid { border: 1px solid var(--ink); background: var(--ink); color: #fff; }
.rg-btn.solid:hover:not(:disabled) { transform: translateY(-1px); box-shadow: var(--shadow-2); }
.rg-btn.solid:active:not(:disabled) { transform: scale(0.97); box-shadow: none; }
.rg-btn:disabled { opacity: 0.55; cursor: default; }
.regen-busy { display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--ink-3); }

/* AI 方案说明：开放式折叠区,顶部一道发丝线 */
.advice { border: 0; border-top: 1px solid var(--hair-3); margin-top: 8px; background: none; border-radius: 0; overflow: visible; }
.advice summary {
  list-style: none; cursor: pointer;
  display: flex; align-items: center; gap: 7px;
  padding: 12px 2px; font-size: 13px; font-weight: 600; color: var(--ink-2);
  border-radius: 6px;
  transition: color var(--t-fast) var(--ease);
}
.advice summary::-webkit-details-marker { display: none; }
.advice summary svg { color: var(--ink-4); transition: color var(--t-fast) var(--ease); }
.advice summary:hover { color: var(--ink); }
.advice summary:hover svg { color: var(--ink-2); }
.advice summary .adv-tip { margin-left: auto; font-size: 10.5px; font-weight: 400; color: var(--ink-4); }
.advice[open] summary { color: var(--ink); border-bottom: 1px solid var(--hair-3); border-radius: 0; }
.adv-body { padding: 12px 2px 4px; display: flex; flex-direction: column; gap: 12px; }
.adv-h { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: var(--ink); margin-bottom: 5px; }
.adv-h svg { color: var(--ink-4); }
.adv-p { margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--ink-2); white-space: pre-wrap; }

/* 编辑表单（NodeEditForm / DayEditForm 共享组件）仅在本面板内的灰阶覆写 */
.arrange :deep(.nf), .arrange :deep(.df) { border: 1px dashed var(--hair); background: var(--paper-raise); }
.arrange :deep(.nf-f), .arrange :deep(.df-f) { background: var(--paper); }
.arrange :deep(.nf-f:focus), .arrange :deep(.df-f:focus) { border-color: var(--ink-3); box-shadow: 0 0 0 2px rgba(21, 23, 27, 0.07); }
.arrange :deep(.nf-btn.ghost), .arrange :deep(.df-btn.ghost) { background: var(--paper); }
.arrange :deep(.nf-btn.ghost:hover), .arrange :deep(.df-btn.ghost:hover) { border-color: var(--hair); color: var(--ink); }
.arrange :deep(.nf-btn.solid), .arrange :deep(.df-btn.solid) { border-color: var(--ink); background: var(--ink); }

.v-err { font-size: 12px; color: var(--seal); text-align: center; margin-top: 8px; }
.spin { animation: xjspin 0.9s linear infinite; color: var(--ink-3); }
@keyframes xjspin { to { transform: rotate(360deg); } }
</style>
