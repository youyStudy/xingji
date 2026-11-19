<script setup>
import { onMounted, onBeforeUnmount, ref, nextTick, watch, computed } from 'vue'
import AppNav from '../components/AppNav.vue'
import ChatSidebar from '../components/ChatSidebar.vue'
import TripArrangement from '../components/TripArrangement.vue'
import ConfirmModal from '../components/ConfirmModal.vue'
import { useTripsStore } from '../stores/trips'
import { useExploreStore } from '../stores/explore'
import { createShare, deleteSession } from '../api/xingji'
import {
  Plus, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen,
  Loader2, Calendar, MapPin, Coins, Compass, ShieldCheck, FileText, Sparkles, Trash2,
  MoreHorizontal, Pencil, Pin, CheckSquare, Share2, Check, MessageSquare
} from 'lucide-vue-next'

// 三栏结构：
//   左 = 行程列表（历史，像大模型侧栏，可收起）—— trips store
//   中 = 这次行程的会话（回放它当初怎么聊出来的，并能接着聊→重排）—— 复用 explore store + ChatSidebar
//   右 = 每天的具体安排 —— 绑 explore.itinerary
const trips = useTripsStore()
const ex = useExploreStore()

const STATUS = {
  0: { t: '草稿', c: 'draft' },
  1: { t: '已生成', c: 'done' },
  2: { t: '已校验', c: 'ok' }
}
function fmtDate(d) {
  if (!d) return ''
  const s = String(d).slice(0, 10)
  const [y, m, dd] = s.split('-')
  return m && dd ? `${y}.${m}.${dd}` : s
}

// 选中某条行程：更新列表高亮 + 让中栏/右栏载入这条行程（会话 + 安排）
function pick(id) {
  if (!id) return
  trips.selectedId = id
  ex.loadTrip(id)
}

/* ---------- 左列表：对话与行程统一按时间分组(置顶/今天/昨天/更早) ---------- */
// 行程列表项带 sessionId：没被任何行程挂上的会话 = 进行中的对话,生成成功即自动变成行程条
function tsOf(x) {
  return new Date(x.updateTime || x.createTime || 0).getTime()
}
// 右上角时间戳:今天显示时刻,昨天显示"昨天",更早显示 月.日
function fmtStamp(t) {
  if (!t) return ''
  const d = new Date(t)
  const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = (day(new Date()) - day(d)) / 86400000
  if (diff <= 0) return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (diff === 1) return '昨天'
  return `${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
}
const listGroups = computed(() => {
  // 多选模式只出现行程(批量删除语义不覆盖对话)
  const linked = new Set((trips.list || []).map((t) => t.sessionId).filter(Boolean))
  const chats = selectMode.value ? [] : (ex.sessions || [])
    .filter((s) => !linked.has(s.id))
    .map((s) => ({ kind: 'chat', id: s.id, key: `c-${s.id}`, title: s.title || '未命名对话', pinned: 0, ts: tsOf(s), raw: s }))
  const tripsArr = (trips.list || []).map((t) => ({
    kind: 'trip', id: t.id, key: `t-${t.id}`, title: t.title || '未命名行程',
    pinned: t.pinned ? 1 : 0, ts: tsOf(t), raw: t
  }))
  const all = [...chats, ...tripsArr]
  const pinned = all.filter((x) => x.pinned).sort((a, b) => b.ts - a.ts)
  const rest = all.filter((x) => !x.pinned).sort((a, b) => b.ts - a.ts)
  const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const t0 = day(new Date())
  const t1 = t0 - 86400000
  const groups = []
  if (pinned.length) groups.push({ label: '置顶', items: pinned })
  const today = rest.filter((x) => x.ts >= t0)
  const yest = rest.filter((x) => x.ts >= t1 && x.ts < t0)
  const older = rest.filter((x) => x.ts < t1)
  if (today.length) groups.push({ label: '今天', items: today })
  if (yest.length) groups.push({ label: '昨天', items: yest })
  if (older.length) groups.push({ label: '更早', items: older })
  return groups
})
// 第二行灰色预览:行程 = 天数·出发日·预算;对话 = 还没生成行程
function itemPreview(it) {
  if (it.kind === 'chat') return '对话进行中 · 还没生成行程'
  const t = it.raw
  const seg = []
  if (t.days) seg.push(`${t.days} 天`)
  if (t.startDate) seg.push(`${fmtDate(t.startDate)} 出发`)
  if (t.budget) seg.push(`¥${t.budget}`)
  return seg.length ? seg.join(' · ') : '已生成'
}
function rowOn(it) {
  if (it.kind === 'chat') return !trips.selectedId && ex.sessionId === it.id
  return selectMode.value ? checked.value.has(it.id) : it.id === trips.selectedId
}
function rowClick(it) {
  if (selectMode.value) {
    if (it.kind === 'trip') toggleCheck(it.id)
    return
  }
  if (it.kind === 'chat') openChat(it.raw)
  else pick(it.id)
}
// 点开一条进行中的对话：先复位画像/锚点(避免串进上一条的参数),再载入它的消息
async function openChat(s) {
  if (!s?.id) return
  ex.newSession()
  trips.selectedId = null
  await ex.openSession(s.id)
}

/* ---------- 统一确认弹窗（替代 window.confirm）---------- */
// confirmBox 描述当前要确认的操作;run 真正执行,弹窗等 run 完成才关,失败留在弹窗上
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
    toast(e.message || '操作失败，请重试')
  } finally {
    confirmBusy.value = false
  }
}

// 删除一条行程：确认弹窗 → 软删除 → 列表刷新；删的是当前选中的就清空中/右栏回到新会话态
function askDelete(t) {
  if (!t?.id) return
  askConfirm({
    title: '删除后，该行程将不可恢复',
    description: `将删除行程「${t.title || '未命名行程'}」，它的每日安排会一并移除。`,    confirmText: '删除该行程',
    run: async () => {
      const wasSelected = trips.selectedId === t.id
      await trips.remove(t.id)
      if (wasSelected) ex.newSession()
      // 明确告诉用户删掉了哪条,避免"以为删了 A 其实删了 B"
      toast(`已删除「${t.title || '未命名行程'}」`)
    }
  })
}

/* ---------- 「...」气泡菜单：行程(重命名/置顶/多选/分享/删除)、对话(删除) ---------- */
const menuFor = ref(null) // 当前打开菜单的条目 key：'t-<行程id>' / 'c-<会话id>'
const menuPos = ref({ top: 0, left: 0 })
const menuTrip = computed(() => trips.list.find((t) => menuFor.value === `t-${t.id}`) || null)
const menuChat = computed(() => (ex.sessions || []).find((s) => menuFor.value === `c-${s.id}`) || null)
function openMenuAt(key, e) {
  // 按按钮的屏幕位置定位菜单(fixed,右对齐),列表容器 overflow 裁不到它
  const r = e.currentTarget.getBoundingClientRect()
  menuPos.value = { top: r.bottom + 6, left: Math.max(8, r.right - 158) }
  menuFor.value = key
}
const openMenu = (t, e) => openMenuAt(`t-${t.id}`, e)
const openMenuChat = (s, e) => openMenuAt(`c-${s.id}`, e)
function closeMenu() {
  menuFor.value = null
}
function onDocDown(e) {
  if (menuFor.value == null) return
  if (!e.target.closest('.tmenu') && !e.target.closest('.t-more')) closeMenu()
}

// 删除一条进行中的对话(还没生成行程,直接删会话;删的是当前打开的就回到新会话态)
function askDeleteChat(s) {
  closeMenu()
  if (!s?.id) return
  askConfirm({
    title: '删除后，该对话将不可恢复',
    description: `对话「${s.title || '未命名对话'}」的聊天记录将一并删除。`,
    confirmText: '删除该对话',
    run: async () => {
      await deleteSession(s.id)
      if (ex.sessionId === s.id) ex.newSession()
      await ex.refreshSessions()
      toast(`已删除对话「${s.title || '未命名对话'}」`)
    }
  })
}

/* ---------- 重命名（行内编辑） ---------- */
const renamingId = ref(null)
const renameDraft = ref('')
const renameInput = ref(null)
function startRename(t) {
  renamingId.value = t.id
  renameDraft.value = t.title || ''
  nextTick(() => renameInput.value?.focus())
}
async function saveRename() {
  const id = renamingId.value
  const title = (renameDraft.value || '').trim()
  renamingId.value = null
  if (!id || !title) return
  if (title === trips.list.find((x) => x.id === id)?.title) return
  try {
    await trips.rename(id, title)
  } catch (e) {
    toast(e.message || '重命名失败')
  }
}

/* ---------- 多选模式（批量删除） ---------- */
const selectMode = ref(false)
const checked = ref(new Set())
function toggleCheck(id) {
  const s = new Set(checked.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  checked.value = s
}
function deleteChecked() {
  const ids = [...checked.value]
  if (!ids.length) return
  const wasSelected = ids.includes(trips.selectedId)
  askConfirm({
    title: `删除后，所选 ${ids.length} 条行程将不可恢复`,
    description: '它们的每日安排会一并移除。',
    confirmText: `删除 ${ids.length} 条`,
    run: async () => {
      const r = await trips.removeMany(ids)
      exitMulti()
      if (wasSelected) ex.newSession()
      toast(r.failed ? `已删除 ${r.total - r.failed} 条，${r.failed} 条失败` : `已删除 ${r.total} 条`)
    }
  })
}
function exitMulti() {
  selectMode.value = false
  checked.value = new Set()
}

/* ---------- 轻量 toast（分享复制等反馈） ---------- */
const toastMsg = ref('')
let toastTimer = null
function toast(msg) {
  toastMsg.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toastMsg.value = ''), 2500)
}

/* ---------- 菜单动作 ---------- */
function menuRename(t) {
  closeMenu()
  startRename(t)
}
async function menuPin(t) {
  closeMenu()
  try {
    await trips.togglePin(t.id)
  } catch (e) {
    toast(e.message || '操作失败')
  }
}
function menuMulti() {
  closeMenu()
  exitMulti()
  selectMode.value = true
}
async function menuShare(t) {
  closeMenu()
  try {
    const token = await createShare(t.id)
    const url = `${location.origin}/s/${token}`
    try {
      await navigator.clipboard.writeText(url)
    } catch (_) {
      /* 剪贴板被拒也不报错,链接可稍后在探索页重新生成 */
    }
    toast('分享链接已复制')
  } catch (e) {
    toast(e.message || '生成分享链接失败')
  }
}
function menuDelete(t) {
  closeMenu()
  askDelete(t)
}

function goNew() {
  // 就地新会话:清 explore store(消息/行程/参数/锚点/画像/错误复位) + 取消左侧高亮
  // 中栏 ChatSidebar 会自动回到"想去哪儿走走"空态引导,用户直接开始聊;聊出来的行程生成成功后由 watch(genBusy) 回写选中态
  ex.newSession()
  // newSession 只复位画像/锚点,基础字段在这里重置(不整体替换 params,避免抹掉画像字段的键)
  ex.params.city = ''
  ex.params.startDate = null
  ex.params.endDate = null
  ex.params.days = null
  ex.params.budget = null
  ex.params.preferences = []
  trips.selectedId = null
}

onMounted(async () => {
  await trips.loadList()
  if (!trips.selectedId && trips.list.length) pick(trips.list[0].id)
  // 左列表的「进行中的对话」分区数据;发消息/生成后 store 内部会自动刷新
  ex.refreshSessions().catch(() => {})
})

// 「接着聊→重排」成功后：高亮新到那条、并刷新左侧列表（生成会落一条新行程）
watch(
  () => ex.genBusy,
  async (busy, prev) => {
    const nid = ex.itinerary?.itinerary?.id
    if (prev && !busy && nid) {
      trips.selectedId = nid
      await trips.loadList()
    }
  }
)

// 会话栏 / 规划栏之间可左右拖动的分割线：拖动改中栏宽度，右栏自适应剩余。
// 默认比例：左 10% / 中 70% / 右 20%（左栏固定不可拖，只有这根线能拖）。
// 中栏与右栏都在 .ts-split 里（约占整宽 90%），故中栏取该区的 0.78 ≈ 整宽 70%，右栏自然 ≈ 20%。
const splitEl = ref(null)
const chatW = ref(440)
const GUT = 10       // 把手宽度
const MIN_CHAT = 280
const MIN_MAIN = 120
const CHAT_RATIO = 0.78
let dragging = false
let startX = 0
let startW = 0
let userAdjusted = false

// 「每日规划」整块面板是否收起（类比任务监控右上角整块收起）：
// 收起后会话铺满、右侧只留一个细把手再点开。
const planCollapsed = ref(false)

function clampW(next) {
  const total = splitEl.value ? splitEl.value.getBoundingClientRect().width : 0
  const maxChat = Math.max(MIN_CHAT, total - GUT - MIN_MAIN)
  return Math.min(Math.max(next, MIN_CHAT), maxChat)
}
// 按容器比例回到默认（中 80% / 右 10%）
function applyDefault() {
  const el = splitEl.value
  if (!el) return
  const total = el.getBoundingClientRect().width
  if (!total) return
  chatW.value = clampW(Math.round(total * CHAT_RATIO))
}
function onMove(e) {
  if (!dragging) return
  userAdjusted = true
  chatW.value = clampW(startW + (e.clientX - startX))
}
function onUp() {
  dragging = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
}
function onDown(e) {
  e.preventDefault()
  dragging = true
  startX = e.clientX
  startW = chatW.value
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
}
function resetSplit() {
  userAdjusted = false
  applyDefault()
}
function togglePlan(next) {
  planCollapsed.value = next
  if (!next && !userAdjusted) nextTick(applyDefault)
}
// 未手动拖过时，随窗口变化保持默认比例（堆叠态 <860 交给 CSS，忽略）
function onResize() {
  if (userAdjusted) return
  if (window.innerWidth <= 860) return
  applyDefault()
}
onMounted(async () => {
  await nextTick()
  applyDefault()
  window.addEventListener('resize', onResize)
  document.addEventListener('mousedown', onDocDown) // 气泡菜单:点外部关闭
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  document.removeEventListener('mousedown', onDocDown)
  onUp()
})
</script>

<template>
  <div class="trips">
    <AppNav />

    <div class="ts-body">
      <!-- 一个整体工作区：外缘一圈发丝线，内部各栏之间也用发丝线分隔，不叠浮卡 -->
      <div class="ts-frame">
        <!-- 左：行程列表（历史会话栏） -->
        <aside class="ts-side" :class="{ collapsed: trips.collapsed }">
          <button v-if="trips.collapsed" class="rail" @click="trips.toggleCollapse()" title="展开行程列表" aria-label="展开行程列表">
            <PanelLeftOpen :size="18" />
            <span class="rail-txt">我的行程</span>
            <span class="rail-dot" v-if="trips.list.length">{{ trips.list.length }}</span>
          </button>

          <template v-else>
            <div class="side-head">
              <span class="sh-title">我的行程</span>
              <button class="sh-btn" @click="trips.toggleCollapse()" title="收起列表" aria-label="收起列表"><PanelLeftClose :size="16" /></button>
            </div>

            <!-- 多选模式：顶栏换成 已选/删除/完成 -->
            <div v-if="selectMode" class="ms-bar">
              <span class="ms-n mono">已选 {{ checked.size }} 项</span>
              <button class="ms-act" @click="exitMulti">完成</button>
              <button class="ms-act del" :disabled="!checked.size" @click="deleteChecked">
                <Trash2 :size="13" /> 删除
              </button>
            </div>
            <button v-else class="new-btn" @click="goNew">
              <Plus :size="15" /> 新行程
            </button>

          <div class="side-list">
            <div v-if="trips.listLoading" class="sl-loading"><Loader2 :size="18" class="spin" /> 正在载入…</div>
            <div v-else-if="trips.listError" class="sl-err">{{ trips.listError }}</div>
            <div v-else-if="!trips.list.length && !ex.sessions.length" class="sl-empty">
              <Sparkles :size="18" />
              <p>还没有行程</p>
              <span>去探索说一句需求，就能生成一条</span>
            </div>

            <!-- 对话与行程统一按 置顶/今天/昨天/更早 分组；双行条目:头像圆标+标题+右上时间+灰色预览 -->
            <template v-for="g in listGroups" :key="g.label">
              <div class="sl-sec mono">{{ g.label }}</div>
              <div
                v-for="it in g.items"
                :key="it.key"
                class="t-item"
                :class="{ on: rowOn(it), chat: it.kind === 'chat', checking: selectMode }"
                role="button"
                tabindex="0"
                @click="rowClick(it)"
                @keydown.enter="rowClick(it)"
              >
                <span v-if="selectMode" class="ti-check" :class="{ on: checked.has(it.id) }"><Check :size="12" /></span>
                <span v-else class="t-ava">
                  <MessageSquare v-if="it.kind === 'chat'" :size="14" />
                  <FileText v-else :size="14" />
                </span>
                <div class="t-main">
                  <div class="ti-row1">
                    <input
                      v-if="renamingId === it.id"
                      ref="renameInput"
                      v-model="renameDraft"
                      class="ti-rename"
                      placeholder="标题"
                      @click.stop
                      @keydown.enter.stop="saveRename"
                      @keydown.esc.stop="renamingId = null"
                      @blur="saveRename"
                    />
                    <template v-else>
                      <span class="ti-title">{{ it.title }}</span>
                      <Pin v-if="it.pinned" :size="12" class="ti-pin" />
                      <span class="ti-time mono">{{ fmtStamp(it.ts) }}</span>
                      <button
                        class="ti-more t-more"
                        title="更多操作"
                        aria-label="更多操作"
                        @click.stop="it.kind === 'chat' ? openMenuChat(it.raw, $event) : openMenu(it.raw, $event)"
                      >
                        <MoreHorizontal :size="14" />
                      </button>
                    </template>
                  </div>
                  <div class="ti-preview">{{ itemPreview(it) }}</div>
                </div>
              </div>
            </template>
          </div>
          </template>
        </aside>

        <!-- 会话 + 每日规划：两者之间一道可左右拖动的分割线 -->
        <div class="ts-split" ref="splitEl">
          <!-- 中：会话（复用探索页会话组件，可继续聊并按新需求重排） -->
          <div class="ts-chat" :class="{ 'fill-now': planCollapsed }" :style="{ width: chatW + 'px' }">
            <ChatSidebar :collapsible="false" />
          </div>

          <!-- 面板收起后：右缘留一个细把手，点一下重新展开 -->
          <button
            v-if="planCollapsed"
            class="ts-open-tab"
            @click="togglePlan(false)"
            title="展开每日规划"
            aria-label="展开每日规划"
          >
            <PanelRightOpen :size="15" />
            <span class="ot-txt">每日规划</span>
          </button>

          <template v-else>
            <!-- 分割线（按住左右拖，双击复位） -->
            <div
              class="ts-splitter"
              role="separator"
              aria-orientation="vertical"
              title="拖动调整宽度 · 双击复位"
              @pointerdown="onDown"
              @dblclick="resetSplit"
            >
              <span class="sp-grip"><i></i><i></i><i></i></span>
            </div>

            <!-- 右：每日规划面板（类比任务监控——独立面板面、顶部标题+整块收起、每天可展开分组） -->
            <main class="ts-main panel">
              <div class="pn-bar">
                <span class="pn-title">每日规划</span>
                <span class="pn-sub mono" v-if="ex.itinerary">{{ (ex.itinerary.days || []).length }} 天</span>
                <button class="pn-btn" @click="togglePlan(true)" title="收起面板" aria-label="收起面板">
                  <PanelRightClose :size="16" />
                </button>
              </div>

              <div class="pn-scroll">
                <div v-if="ex.tripLoading" class="m-state">
                  <Loader2 :size="24" class="spin" />
                  <p>正在展开这次行程…</p>
                </div>

                <div v-else-if="ex.tripError" class="m-state">
                  <div class="ms-ic err"><ShieldCheck :size="22" /></div>
                  <p class="ms-t">{{ ex.tripError }}</p>
                  <button class="btn" @click="pick(trips.selectedId)">重试</button>
                </div>

                <div v-else-if="!ex.itinerary" class="m-state">
                  <div class="ms-ic"><Compass :size="22" /></div>
                  <p class="ms-t">选择一条行程查看安排</p>
                  <span class="ms-s">或点左侧「新行程」直接开始新的对话规划</span>
                </div>

                <template v-else>
                  <header class="m-head">
                    <div class="mh-chip mono">行程安排</div>
                    <h2 class="serif mh-title">{{ ex.itinerary.itinerary?.title || '我的行程' }}</h2>
                    <div class="mh-meta">
                      <span v-if="ex.itinerary.itinerary?.days"><MapPin :size="14" /> {{ ex.itinerary.itinerary.days }} 天</span>
                      <span v-if="ex.itinerary.itinerary?.startDate"><Calendar :size="14" /> {{ fmtDate(ex.itinerary.itinerary.startDate) }}</span>
                      <span v-if="ex.itinerary.itinerary?.budget"><Coins :size="14" /> 预算 ¥{{ ex.itinerary.itinerary.budget }}</span>
                      <span class="mh-st" :class="STATUS[ex.itinerary.itinerary?.status]?.c">{{ STATUS[ex.itinerary.itinerary?.status]?.t || '' }}</span>
                    </div>
                  </header>
                  <TripArrangement :days="ex.itinerary.days || []" />
                </template>
              </div>
            </main>
          </template>
        </div>
      </div>
    </div>

    <!-- 「...」气泡菜单：Teleport 到 body,不被列表容器裁剪;形变展开(fixed 定位对齐按钮右缘) -->
    <Teleport to="body">
      <transition name="tmenu">
        <div v-if="menuTrip || menuChat" class="tmenu" :style="{ top: menuPos.top + 'px', left: menuPos.left + 'px' }" role="menu">
          <template v-if="menuTrip">
            <button class="tm-item" role="menuitem" @click="menuRename(menuTrip)"><Pencil :size="13" /> 重命名</button>
            <button class="tm-item" role="menuitem" @click="menuPin(menuTrip)"><Pin :size="13" /> {{ menuTrip.pinned ? '取消置顶' : '置顶' }}</button>
            <button class="tm-item" role="menuitem" @click="menuMulti"><CheckSquare :size="13" /> 多选</button>
            <button class="tm-item" role="menuitem" @click="menuShare(menuTrip)"><Share2 :size="13" /> 分享</button>
            <button class="tm-item danger" role="menuitem" @click="menuDelete(menuTrip)"><Trash2 :size="13" /> 删除</button>
          </template>
          <template v-else-if="menuChat">
            <button class="tm-item danger" role="menuitem" @click="askDeleteChat(menuChat)"><Trash2 :size="13" /> 删除对话</button>
          </template>
        </div>
      </transition>

      <!-- 轻量 toast:分享复制/批量删除结果等反馈 -->
      <transition name="toast">
        <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>
      </transition>
    </Teleport>

    <!-- 统一确认弹窗:删除行程/批量删除/删除对话 -->
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
/* ===== 行程页视觉语言：一张白纸工作区 =====
   灰阶三档面(白 / raise / sink) + 发丝线分区,不用阴影叠卡;
   全页唯一的实心墨色焦点 = 「新行程」与输入区的发送钮;
   蓝色只作为"已生成"状态信息色出现,红只出现在异常/删除。 */
.trips { display: flex; flex-direction: column; height: 100vh; background: var(--paper); }
.ts-body { flex: 1; min-height: 0; display: flex; padding: 16px; }

/* 整体工作区盒子：外缘一圈发丝线，内部栏间也用发丝线，光洁无浮卡堆叠 */
.ts-frame {
  flex: 1; min-width: 0; min-height: 0; display: flex;
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1);
  overflow: hidden;
}

/* ===== 左：行程列表 ===== */
.ts-side { position: relative; flex: none; width: 236px; height: 100%; display: flex; flex-direction: column; border-right: 1px solid var(--hair-3); background: var(--paper); overflow: hidden; transition: width var(--t-morph) var(--ease); }
.ts-side.collapsed { width: 60px; }

.rail { flex: none; height: 100%; width: 100%; border: 0; background: var(--paper-raise); cursor: pointer; display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 18px 0; color: var(--ink-3); transition: background var(--t-base) var(--ease), color var(--t-base) var(--ease); }
.rail:hover { background: var(--paper-sink); color: var(--ink); }
.rail-txt { writing-mode: vertical-rl; letter-spacing: 0.28em; font-size: 12.5px; }
.rail-dot { font-size: 11px; background: var(--ink); color: #fff; border-radius: 999px; padding: 1px 7px; font-family: var(--f-mono); }

.side-head { flex: none; height: 50px; display: flex; align-items: center; justify-content: space-between; padding: 0 10px 0 16px; border-bottom: 1px solid var(--hair-3); }
.sh-title { font-size: 13px; font-weight: 600; letter-spacing: 0.03em; color: var(--ink); }
.sh-btn { width: 30px; height: 30px; border-radius: var(--r-sm); border: 0; background: none; color: var(--ink-4); cursor: pointer; display: grid; place-items: center; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.sh-btn:hover { background: var(--paper-sink); color: var(--ink); }

/* 新行程 = 全页唯一常驻的实心墨色焦点钮 */
.new-btn {
  flex: none; margin: 12px 12px 4px;
  display: inline-flex; align-items: center; justify-content: center; gap: 7px;
  border: 0; background: var(--ink); color: #fff;
  border-radius: var(--r-md); padding: 9px;
  font-family: var(--f-body); font-size: 13px; font-weight: 600; letter-spacing: 0.02em;
  cursor: pointer;
  transition: transform var(--t-fast) var(--ease), background var(--t-fast) var(--ease), box-shadow var(--t-base) var(--ease);
}
.new-btn:hover { background: #000; transform: translateY(-1px); box-shadow: var(--shadow-2); }
.new-btn:active { transform: scale(0.97); box-shadow: none; }

.side-list { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 10px 14px; display: flex; flex-direction: column; gap: 2px; }
/* 分组小标：mono 灰，像 IDE 分区标签(置顶/今天/昨天/更早) */
.sl-sec { flex: none; font-size: 10px; letter-spacing: 0.2em; color: var(--ink-4); padding: 10px 10px 4px; }
.sl-loading, .sl-err { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--ink-3); padding: 14px 8px; }
.sl-err { color: var(--seal); }
.sl-empty { text-align: center; color: var(--ink-3); padding: 30px 12px; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.sl-empty svg { color: var(--ink-4); }
.sl-empty p { margin: 4px 0 0; font-size: 13.5px; color: var(--ink-2); }
.sl-empty span { font-size: 12px; }

/* 列表项(对话/行程同构):头像圆标 + 双行文本,悬停浅灰,选中沉灰+左缘墨条 */
.t-item { text-align: left; border: 0; background: none; border-radius: 10px; padding: 8px 10px; cursor: pointer; font-family: var(--f-body); display: flex; align-items: flex-start; gap: 10px; transition: background var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease); }
.t-item:hover { background: var(--paper-raise); }
.t-item.on { background: var(--paper-sink); box-shadow: inset 2px 0 0 var(--ink); }
.t-ava { flex: none; width: 30px; height: 30px; border-radius: 50%; background: var(--paper-raise); color: var(--ink-3); display: grid; place-items: center; margin-top: 1px; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.t-item:hover .t-ava { color: var(--ink-2); }
.t-item.on .t-ava { background: var(--ink); color: #fff; }
.t-item:active .t-ava { transform: scale(0.92); }
.t-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.ti-row1 { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ti-title { font-size: 13px; color: var(--ink); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; flex: 1; }
/* 对话条比行程轻一档:标题不加粗 */
.t-item.chat .ti-title { font-weight: 500; }
/* 置顶图钉:小号信息色(蓝),只在置顶时出现 */
.ti-pin { flex: none; color: var(--blue-mid); }
/* 右上角时间戳:悬停时淡出让位给「...」(两者各占固定位,不跳版) */
.ti-time { flex: none; font-size: 10.5px; color: var(--ink-4); transition: opacity var(--t-fast) var(--ease); }
.t-item:hover .ti-time { opacity: 0; }
/* 「...」更多操作:悬停列表项时浮现 */
.ti-more {
  flex: none; width: 22px; height: 22px; border-radius: var(--r-sm); border: 0; background: none;
  color: var(--ink-4); cursor: pointer; display: grid; place-items: center;
  opacity: 0; transition: opacity var(--t-fast) var(--ease), background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.t-item:hover .ti-more, .ti-more:focus-visible { opacity: 1; }
.ti-more:hover { background: var(--paper); color: var(--ink); transform: scale(1.08); }
.ti-more:active { transform: scale(0.92); }
/* 第二行灰色预览 */
.ti-preview { font-size: 11.5px; color: var(--ink-4); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* 多选模式:不出现「...」 */
.t-item.checking .ti-more { display: none; }
/* 重命名行内输入 */
.ti-rename {
  flex: 1; min-width: 0; font-family: var(--f-body); font-size: 13px; font-weight: 600; color: var(--ink);
  border: 1px solid var(--hair); border-radius: var(--r-sm); background: #fff;
  padding: 4px 8px; outline: none;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.ti-rename:focus { border-color: var(--ink-3); box-shadow: 0 0 0 2px rgba(21, 23, 27, 0.08); }
/* 多选勾选圈 */
.ti-check {
  flex: none; width: 20px; height: 20px; border-radius: 50%;
  border: 1.5px solid var(--hair); background: var(--paper); color: transparent;
  display: grid; place-items: center;
  transition: all var(--t-fast) var(--ease);
}
.ti-check.on { background: var(--ink); border-color: var(--ink); color: #fff; transform: scale(1.05); }

/* 多选模式顶条(替换"新行程"按钮位) */
.ms-bar { flex: none; margin: 12px 12px 4px; display: flex; align-items: center; gap: 6px; padding: 6px 6px 6px 10px; border: 1px solid var(--hair-2); background: var(--paper-raise); border-radius: var(--r-md); animation: msIn var(--t-base) var(--ease) both; }
@keyframes msIn { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: none; } }
.ms-n { flex: 1; font-size: 12px; color: var(--ink); font-weight: 600; }
.ms-act {
  display: inline-flex; align-items: center; gap: 5px;
  border: 0; background: none; color: var(--ink-2);
  border-radius: var(--r-sm); padding: 5px 10px; font-family: var(--f-body); font-size: 12px; cursor: pointer;
  transition: all var(--t-fast) var(--ease);
}
.ms-act:hover:not(:disabled) { background: var(--paper); color: var(--ink); }
.ms-act.del { color: var(--seal); }
.ms-act.del:hover:not(:disabled) { background: var(--seal-wash); color: var(--seal); }
.ms-act:disabled { opacity: 0.45; cursor: default; }
/* 状态徽章(右栏标题区专用)：信息色,低饱和小 pill */
.mh-st.draft { background: var(--paper-sink); color: var(--ink-3); }
.mh-st.done { background: var(--blue-wash); color: var(--blue); }
.mh-st.ok { background: #e7f4ec; color: #2f7d53; }

/* ===== 中+右：可拖分栏 ===== */
.ts-split { flex: 1 1 0; min-width: 0; display: flex; height: 100%; }

/* 中：会话（宽度由 chatW 内联控制）；在整框里就是白底面板,去掉卡片壳 */
.ts-chat { display: flex; flex: none; min-width: 280px; height: 100%; }
.ts-chat :deep(.chat) { background: transparent; border: 0; border-radius: 0; box-shadow: none; }
.ts-chat.fill-now { flex: 1 1 0; width: auto !important; }

/* 分割线：1px 发丝线,悬停/拖动时沉为墨色 */
.ts-splitter { flex: none; width: 10px; height: 100%; cursor: col-resize; display: flex; align-items: center; justify-content: center; position: relative; touch-action: none; }
.ts-splitter::before { content: ''; position: absolute; top: 8px; bottom: 8px; left: 50%; width: 1px; transform: translateX(-50%); background: var(--hair-2); transition: background var(--t-fast) var(--ease), width var(--t-fast) var(--ease); }
.ts-splitter:hover::before, .ts-splitter:active::before { background: var(--ink); width: 2px; }
.sp-grip { position: relative; z-index: 1; display: flex; flex-direction: column; gap: 3px; padding: 6px 3px; border-radius: 6px; opacity: 0; transition: opacity var(--t-fast) var(--ease); }
.ts-splitter:hover .sp-grip, .ts-splitter:active .sp-grip { opacity: 1; }
.sp-grip i { width: 3px; height: 3px; border-radius: 50%; background: var(--paper); box-shadow: 0 0 0 1px var(--hair); }
.ts-splitter:active .sp-grip i { background: var(--ink); }

/* 右：每日规划面板 —— 整框里的右栏,白底+发丝线,不套卡 */
.ts-main.panel { flex: 1 1 0; min-width: 120px; height: 100%; display: flex; flex-direction: column; overflow: hidden; background: var(--paper); }
.pn-bar { flex: none; height: 50px; display: flex; align-items: center; gap: 10px; padding: 0 10px 0 18px; border-bottom: 1px solid var(--hair-3); }
.pn-title { font-size: 14px; font-weight: 700; color: var(--ink); }
.pn-sub { font-size: 11.5px; color: var(--ink-4); }
.pn-btn { margin-left: auto; width: 30px; height: 30px; border-radius: var(--r-sm); border: 0; background: none; color: var(--ink-4); cursor: pointer; display: grid; place-items: center; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.pn-btn:hover { background: var(--paper-sink); color: var(--ink); }
.pn-btn:active { transform: scale(0.9); }
.pn-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 18px 24px; }

/* 面板收起后：右缘细把手（竖排文字 + 展开图标），点开重新展开 */
.ts-open-tab { flex: none; width: 42px; height: 100%; margin-left: auto; border: 0; border-left: 1px solid var(--hair-3); border-radius: 0; background: var(--paper-raise); color: var(--ink-3); cursor: pointer; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; transition: background var(--t-base) var(--ease), color var(--t-base) var(--ease); }
.ts-open-tab:hover { background: var(--paper-sink); color: var(--ink); }
.ot-txt { writing-mode: vertical-rl; letter-spacing: 0.24em; font-size: 12px; }

/* 内容头：小号 mono 灰标签 + 衬线标题 + 一行 meta */
.m-head { margin-bottom: 18px; }
.mh-chip { display: block; font-size: 10px; letter-spacing: 0.26em; color: var(--ink-4); margin-bottom: 8px; }
.mh-title { margin: 0 0 10px; font-size: 21px; color: var(--ink); line-height: 1.25; }
.mh-meta { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--ink-3); flex-wrap: wrap; }
.mh-meta span { display: inline-flex; align-items: center; gap: 5px; }
.mh-st { font-size: 10px; padding: 2px 8px; border-radius: 999px; }

/* 空态/加载/错误 */
.m-state { height: 100%; display: grid; place-content: center; justify-items: center; text-align: center; gap: 8px; color: var(--ink-3); }
.ms-ic { width: 52px; height: 52px; border-radius: 50%; background: var(--paper-raise); color: var(--ink-3); display: grid; place-items: center; margin-bottom: 6px; }
.ms-ic.err { background: var(--seal-wash); color: var(--seal); }
.ms-t { margin: 0; font-size: 14px; color: var(--ink-2); }
.ms-s { font-size: 12.5px; }
.m-state .btn { --btn-bg: var(--ink); --btn-fg: #fff; margin-top: 12px; padding: 10px 24px; font-size: 13px; border-radius: var(--r-md); }
.m-state .btn:hover { box-shadow: var(--shadow-2); }

.spin { animation: xjspin 0.9s linear infinite; color: var(--ink-3); }
@keyframes xjspin { to { transform: rotate(360deg); } }

/* ===== 「...」气泡菜单：白底发丝线浮层(浮层允许投影),条目悬停沉灰 ===== */
.tmenu {
  position: fixed; z-index: 1200; min-width: 150px;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-md); box-shadow: var(--shadow-2);
  padding: 5px;
  transform-origin: top right;
}
.tm-item {
  width: 100%; display: flex; align-items: center; gap: 9px;
  border: 0; background: none; cursor: pointer; text-align: left;
  font-family: var(--f-body); font-size: 13px; color: var(--ink-2);
  border-radius: var(--r-sm); padding: 8px 10px;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.tm-item svg { color: var(--ink-4); transition: color var(--t-fast) var(--ease); }
.tm-item:hover { background: var(--paper-raise); color: var(--ink); }
.tm-item:hover svg { color: var(--ink); }
.tm-item.danger { color: var(--seal); }
.tm-item.danger svg { color: var(--seal); }
.tm-item.danger:hover { background: var(--seal-wash); color: var(--seal); }
/* 菜单形变过渡:从锚点角轻微缩放淡入(Popover Morph) */
.tmenu-enter-active { transition: opacity var(--t-base) var(--ease), transform var(--t-morph) var(--ease); }
.tmenu-leave-active { transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.tmenu-enter-from, .tmenu-leave-to { opacity: 0; transform: scale(0.92) translateY(-4px); }

/* toast:墨色胶囊浮出 */
.toast {
  position: fixed; left: 50%; bottom: 34px; transform: translateX(-50%); z-index: 1300;
  background: var(--ink); color: var(--paper);
  font-family: var(--f-body); font-size: 13px;
  border-radius: 999px; padding: 9px 20px;
  box-shadow: var(--shadow-2);
}
.toast-enter-active { transition: opacity var(--t-base) var(--ease), transform var(--t-morph) var(--ease); }
.toast-leave-active { transition: opacity var(--t-fast) var(--ease), transform var(--t-fast) var(--ease); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(-50%) translateY(12px) scale(0.95); }

/* ===== 中栏会话(共享组件 ChatSidebar)仅在行程页内的灰阶覆写 ===== */
/* 头部按钮：悬停沉灰变墨,不用蓝 */
.ts-chat :deep(.ch-btn:hover) { background: var(--paper-sink); color: var(--ink); }
.ts-chat :deep(.ch-btn.on) { background: var(--paper-sink); color: var(--ink); }
/* 空态/示例 chip：灰圈灰字,悬停沉灰 */
.ts-chat :deep(.empty-ic) { background: var(--paper-raise); color: var(--ink-3); }
.ts-chat :deep(.chip:hover) { border-color: var(--hair); background: var(--paper-raise); color: var(--ink); transform: translateY(-1px); }
/* 气泡：AI = 白底素文本(只留头像),用户 = 沉灰底 */
.ts-chat :deep(.av) { background: var(--paper-sink); color: var(--ink-3); }
.ts-chat :deep(.row.user .bubble) { background: var(--paper-sink); color: var(--ink); border-radius: 12px; border-bottom-right-radius: 4px; }
.ts-chat :deep(.row.assistant .bubble) { background: transparent; color: var(--ink); padding: 3px 6px 3px 0; border-radius: 0; }
/* 旅行画像卡：白底发丝线,不再整块淡蓝 */
.ts-chat :deep(.params) { border: 1px solid var(--hair-2); background: var(--paper); }
.ts-chat :deep(.pm-head) { color: var(--ink); }
.ts-chat :deep(.pf) { background: var(--paper-raise); }
.ts-chat :deep(.pace-btn:hover) { border-color: var(--hair); color: var(--ink); }
.ts-chat :deep(.pace-btn.on) { background: var(--ink); border-color: var(--ink); color: #fff; }
.ts-chat :deep(.pref.on) { background: var(--ink); border-color: var(--ink); }
.ts-chat :deep(.an-toggle:hover) { color: var(--ink); }
.ts-chat :deep(.an-badge) { background: var(--ink); }
.ts-chat :deep(.an-row) { background: var(--paper-raise); }
.ts-chat :deep(.an-dt), .ts-chat :deep(.an-sel), .ts-chat :deep(.an-loc) { background: var(--paper); }
.ts-chat :deep(.an-dt:focus), .ts-chat :deep(.an-sel:focus), .ts-chat :deep(.an-loc:focus) { border-color: var(--ink-3); box-shadow: 0 0 0 2px rgba(21, 23, 27, 0.07); }
/* 输入区：内衬灰框,focus 环墨色;发送钮 = 墨色圆钮(页面第二焦点,与「新行程」同源) */
.ts-chat :deep(.composer) { background: var(--paper-raise); }
.ts-chat :deep(.composer:focus-within) { border-color: var(--ink-3); box-shadow: 0 0 0 3px rgba(21, 23, 27, 0.06); }
.ts-chat :deep(.send) { background: var(--ink); }
/* 生成行程按钮：全局蓝底胶囊在此页覆写为墨色 */
.ts-chat :deep(.btn.gen) { --btn-bg: var(--ink); --btn-fg: #fff; border-radius: var(--r-md); padding: 12px 20px; font-size: 13.5px; }
.ts-chat :deep(.btn.gen:hover) { box-shadow: var(--shadow-2); }

/* ===== 响应式：窄屏堆叠 ===== */
@media (max-width: 860px) {
  .ts-body { overflow-y: auto; }
  /* 窄屏堆叠：关闭左右分栏、隐藏分割线，中栏宽度不再受 chatW 内联约束 */
  .ts-split { flex-direction: column; height: auto; }
  .ts-splitter { display: none; }
  .ts-chat { width: auto !important; min-width: 0; min-height: 420px; }
  .ts-main { min-width: 0; min-height: 420px; }
}
@media (max-width: 720px) {
  .ts-frame { flex-direction: column; height: auto; }
  .ts-side { width: 100% !important; height: auto; max-height: 40vh; border-right: 0; border-bottom: 1px solid var(--hair-3); }
  .ts-main { padding: 0; }
  .mh-title { font-size: 19px; }
}
</style>
