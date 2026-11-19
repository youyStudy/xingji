<script setup>
import { ref, nextTick, watch, computed } from 'vue'
import { useExploreStore } from '../stores/explore'
import { useAuthStore } from '../stores/auth'
import AppBrand from './AppBrand.vue'
import {
  PenLine, PanelLeftClose, PanelLeftOpen, Send, Loader2,
  Sparkles, MapPin, Navigation, Calendar, Coins, Wand2, X,
  ArrowDownLeft, ArrowUpRight, ChevronDown, MessageSquareOff,
  Users, Bus, BedDouble, Star, Ban, SlidersHorizontal, Camera, Plus
} from 'lucide-vue-next'

// collapsible=true（默认，探索页）：会话是一条可收起的抽屉边栏。
// collapsible=false（我的行程中栏）：作为固定栏铺满父容器宽度，隐藏收起把手/按钮。
const props = defineProps({ collapsible: { type: Boolean, default: true } })

const store = useExploreStore()
const auth = useAuthStore()

const draft = ref('')
// 与后端 ChatServiceImpl.MAX_INPUT_CHARS 保持一致:正常说需求用不到这么多,
// 这里只是防止粘贴整篇小说把上下文撑爆。超了直接拦在本地,不用等一个来回。
const MAX_INPUT = 500
const overLimit = computed(() => draft.value.length > MAX_INPUT)
const scroller = ref(null)
const taRef = ref(null)
const PREFS = ['人文', '历史', '夜景', '美食', '自然', '亲子', '购物', '博物馆', '网红', '小众']
// 节奏三档:与后端行程提示词的数量规则一一对应(悠闲≤2/适中≤3/特种兵≤4 个主景点)
const PACES = ['特种兵', '适中', '悠闲']
// 住宿方式:决定行程结构。住一处=全程同一区域;换着住=按行程分段(如市区2晚+远郊1晚)
const LODGING_MODES = [
  { value: '住一处', label: '一直住一处', hint: '全程住同一片区域，行李不用搬' },
  { value: '换着住', label: '中间换着住', hint: '按行程分段住，比如去远郊那晚住当地' }
]
const PACE_HINT = {
  特种兵: '每天 3-4 个主景点，行程紧凑',
  适中: '每天最多 3 个主景点，劳逸结合',
  悠闲: '每天最多 2 个主景点，留足自由时间'
}

const EXAMPLES = [
  '帮我规划 3 天重庆行程，预算 2000，喜欢人文和夜景',
  '想去西安玩两天，重点看历史古迹',
  '给女朋友安排一个厦门 3 天浪漫游，预算 3000'
]

function scrollBottom() {
  nextTick(() => {
    if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  })
}
watch(
  () => [
    store.messages.length,
    store.chatBusy,
    store.showParams,
    store.chatError,
    store.genError,
    store.sessionTitle
  ],
  scrollBottom
)

/* 输入框随内容长高：先归零再按 scrollHeight 设，最多 120px（超出内部滚动） */
function resizeTa() {
  const el = taRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 120)}px`
}
watch(draft, () => nextTick(resizeTa))

function submit() {
  const t = draft.value.trim()
  if (!t) return
  // 超长时不发,草稿留在输入框里让用户自己删(不静默截断吞掉他的话)
  if (overLimit.value) return
  // 上一句还在等回复：保留草稿不丢字，等回复完再发
  if (store.chatBusy) return
  draft.value = ''
  nextTick(resizeTa)
  store.send(t)
}
function onKey(e) {
  // 中文输入法选词/上屏的 Enter 不是"发送"（keyCode 229 兼容旧内核）
  if (e.isComposing || e.keyCode === 229) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}
/**
 * 把卡片上的景点加进"必须去"清单。
 * mustVisit 在表单里是逗号分隔的文本，这里做去重后追加。
 */
function addToMustVisit(name) {
  if (!name) return
  const cur = splitMustVisit(store.params.mustVisit)
  if (cur.includes(name)) return
  cur.push(name)
  store.params.mustVisit = cur.join('，')
}
function splitMustVisit(v) {
  return String(v || '').split(/[,，、;；]+/).map((s) => s.trim()).filter(Boolean)
}
function useExample(t) {
  draft.value = t
  nextTick(() => submit())
}
function togglePref(p) {
  const i = store.params.preferences.indexOf(p)
  if (i >= 0) store.params.preferences.splice(i, 1)
  else store.params.preferences.push(p)
}
// 到达/返程锚点子区:默认收起,避免给主表单增视觉重量;填了任一锚点后按钮上出 badge 提示
const anchorsOpen = ref(false)
const anchorFilled = (a) => Boolean(a && (a.date || a.time || a.location))
// 新流程里锚点是最先收集的信息:画像卡亮出时若已有到达/返程,自动展开子区,让用户看得见、改得了
watch(() => store.showParams, (v) => {
  if (v && (anchorFilled(store.params.arrival) || anchorFilled(store.params.departure))) anchorsOpen.value = true
})
// 清空结构由 store 统一维护，避免这里手写对象漏字段
function clearAnchor(which) {
  store.clearAnchor(which)
}
const anchorFilledCount = computed(() => {
  let n = 0
  if (anchorFilled(store.params.arrival)) n++
  if (anchorFilled(store.params.departure)) n++
  return n
})
// 服务端算好的缺失项 → 人话；只展示，不在前端推导还缺什么
const MISS_LABELS = {
  city: '目的地',
  origin: '出发地',
  days: '天数',
  startDate: '出发日期',
  'arrival.date': '到达日期',
  'arrival.time': '到达时间',
  'departure.date': '返程日期',
  'departure.time': '返程时间',
  lodgingMode: '住宿方式',
  lodging: '住宿区域'
}
const missingLabels = computed(() =>
  (store.missing || []).map((k) => MISS_LABELS[k] || k).filter(Boolean)
)
/* 画像卡三态：已确认 / 待确定 / 已排除 */
const confirmedChips = computed(() => {
  const p = store.params || {}
  const out = []
  if (p.city) out.push(`目的地 ${p.city}`)
  if (p.days) out.push(`${p.days} 天`)
  if (p.startDate) out.push(`出发 ${String(p.startDate).slice(0, 10)}`)
  if (p.budget) out.push(`预算 ${p.budget}`)
  if (p.groupSize) out.push(`${p.groupSize} 人`)
  if (p.preferences && p.preferences.length) out.push(p.preferences.join(' / '))
  if (p.mustVisit) out.push(`必去 ${p.mustVisit}`)
  return out
})
const excludedChips = computed(() =>
  (store.constraints || [])
    .filter((c) => c && c.active !== false && (c.type === 'EXCLUSION' || c.type === 'SAFETY'))
    .map((c) => c.content)
    .filter(Boolean)
)
const showTyping = computed(() => store.chatBusy && store.messages[store.messages.length - 1]?.role === 'user')

/* ---------- 出发/结束/天数 三方联动 ----------
 * 规则:改"结束"→按区间重算天数;改"天数"或"出发"→按天数推"结束"。
 * 写回同值不会触发下一轮 watch,不会循环 */
const isoAdd = (iso, n) => {
  if (!iso) return iso
  const d = new Date(iso + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
const isoDiff = (a, b) => {
  if (!a || !b) return null
  const diff = (new Date(b + 'T00:00:00Z') - new Date(a + 'T00:00:00Z')) / 86400000
  return Number.isFinite(diff) ? Math.round(diff) : null
}
watch(() => store.params.endDate, (v) => {
  if (!v || !store.params.startDate) return
  const d = isoDiff(store.params.startDate, v)
  if (d != null && d >= 0) store.params.days = d + 1
})
watch(() => store.params.days, (v) => {
  if (!v || v < 1 || !store.params.startDate) return
  const next = isoAdd(store.params.startDate, v - 1)
  if (next && next !== store.params.endDate) store.params.endDate = next
})
watch(() => store.params.startDate, (v) => {
  if (!v || !store.params.days) return
  const next = isoAdd(v, store.params.days - 1)
  if (next && next !== store.params.endDate) store.params.endDate = next
})
</script>

<template>
  <aside class="chat" :class="{ collapsed: props.collapsible && store.collapsed, fill: !props.collapsible }">
    <!-- 收起态：一条窄轨（仅在可收起时出现） -->
    <button v-if="props.collapsible && store.collapsed" class="rail" @click="store.toggleCollapse()" title="展开对话" aria-label="展开对话">
      <PanelLeftOpen :size="18" />
      <span class="rail-txt">AI 会话</span>
      <span class="rail-dot" v-if="store.messages.length">{{ store.messages.length }}</span>
    </button>

    <!-- 展开态 -->
    <template v-else>
      <header class="ch-head">
        <div class="ch-title">
          <AppBrand :size="18" />
          <span class="ch-name">{{ store.sessionTitle || '新对话' }}</span>
        </div>
        <div class="ch-acts">
          <!-- 画像：随时手动打开/收起(发消息只在还没有行程时才自动弹卡)。
               会话的删除入口在「我的行程」列表(...菜单):删行程会连带删它的会话 -->
          <button
            class="ch-btn"
            :class="{ on: store.showParams }"
            @click="store.showParams = !store.showParams"
            title="旅行画像"
            aria-label="旅行画像"
          ><SlidersHorizontal :size="16" /></button>
          <button class="ch-btn" @click="store.newSession()" title="新对话" aria-label="新对话"><PenLine :size="16" /></button>
          <button v-if="props.collapsible" class="ch-btn" @click="store.toggleCollapse()" title="收起边栏" aria-label="收起边栏"><PanelLeftClose :size="16" /></button>
        </div>
      </header>

      <div class="ch-body" ref="scroller">
        <!-- 空态 A:已选中一条早期生成的行程,但没有关联会话(它是在会话关联功能上线之前建的) -->
        <div v-if="!store.messages.length && store.tripSessionMissing && !props.collapsible" class="empty orphan">
          <div class="empty-ic orphan-ic"><MessageSquareOff :size="20" /></div>
          <h4 class="serif">这条行程暂无对话历史</h4>
          <p class="empty-tip">它是在"行程关联会话"功能上线之前生成的，所以没有可回放的对话。你可以继续在这里发问，新的对话会挂在这条行程下。</p>
        </div>

        <!-- 空态 B:全新会话(探索页,或行程页点了「新行程」) -->
        <div v-else-if="!store.messages.length" class="empty">
          <div class="empty-ic"><Sparkles :size="20" /></div>
          <h4 class="serif">想去哪儿走走？</h4>
          <p class="empty-tip">说一句你的出行想法，我会帮你把旅行画像补齐；确认无误后再点「生成行程」，不会自动开跑。</p>
          <div class="chips">
            <button v-for="(ex, i) in EXAMPLES" :key="i" class="chip" @click="useExample(ex)">{{ ex }}</button>
          </div>
        </div>

        <!-- 气泡 -->
        <div v-for="(m, i) in store.messages" :key="m.id || i" class="row" :class="m.role">
          <div v-if="m.role === 'assistant'" class="av"><AppBrand :size="15" /></div>
          <div class="bubble" :class="{ 'has-spots': m.spots && m.spots.length }">
            <span class="txt">{{ m.content }}</span>
            <!-- 景点图文卡片：数据来自高德真实 POI（名字/照片/评分/门票），不是模型编的 -->
            <div v-if="m.spots && m.spots.length" class="spot-cards">
              <article v-for="(s, si) in m.spots" :key="s.amapPoiId || si" class="sc">
                <div class="sc-img">
                  <img v-if="s.photo" :src="s.photo" :alt="s.name" loading="lazy" referrerpolicy="no-referrer" />
                  <div v-else class="sc-noimg"><Camera :size="16" /></div>
                </div>
                <div class="sc-body">
                  <div class="sc-name">{{ s.name }}</div>
                  <div class="sc-meta">
                    <span v-if="s.rating" class="sc-rating"><Star :size="11" /> {{ s.rating }}</span>
                    <span v-if="s.cost">{{ s.cost }} 元</span>
                    <span v-else-if="s.openTime" class="sc-time">{{ s.openTime }}</span>
                  </div>
                </div>
                <button
                  class="sc-add"
                  :title="'加进必去清单'"
                  @click="addToMustVisit(s.name)"
                ><Plus :size="12" /></button>
              </article>
            </div>
          </div>
        </div>

        <!-- 打字指示 -->
        <div v-if="showTyping" class="row assistant">
          <div class="av"><AppBrand :size="15" /></div>
          <div class="bubble typing"><i></i><i></i><i></i></div>
        </div>

        <!-- 旅行画像：只由用户主动打开（或生成报错时打开），对话中不自动弹。
             画像在后台默默累积，不占对话区的视觉空间——用户需要核对时点右上角按钮即可。 -->
        <transition name="pm">
          <div v-if="store.showParams" class="params">
            <div class="pm-head">
              <Wand2 :size="15" /> <span class="pm-t">旅行画像</span>
              <!-- 有行程时说明这次生成是"新开一条",避免被误读成当前右侧那条被替换/已生成 -->
              <span class="pm-tip">{{ store.hasItinerary ? '生成会新建一条行程' : '确认后手动生成' }}</span>
              <button class="pm-x" @click="store.showParams = false" aria-label="关闭"><X :size="14" /></button>
            </div>
            <!-- 画像卡三态：不是必填表单，只是当前对话确认到哪一步 -->
            <div class="pc">
              <div class="pc-row">
                <span class="pc-k">已确认</span>
                <span class="pc-v" :class="{ empty: !confirmedChips.length }">{{ confirmedChips.length ? confirmedChips.join(' · ') : '还没有' }}</span>
              </div>
              <div class="pc-row">
                <span class="pc-k">待确定</span>
                <span class="pc-v" :class="{ empty: !missingLabels.length }">{{ missingLabels.length ? missingLabels.join(' · ') : '无' }}</span>
              </div>
              <div class="pc-row" v-if="excludedChips.length">
                <span class="pc-k">已排除</span>
                <span class="pc-v">{{ excludedChips.join(' · ') }}</span>
              </div>
            </div>

            <div class="pg-sec">基本</div>
            <label class="pf">
              <Navigation :size="14" />
              <input v-model="store.params.origin" placeholder="出发地，如 重庆" />
            </label>
            <label class="pf">
              <MapPin :size="14" />
              <input v-model="store.params.city" placeholder="目的地城市，如 成都" />
            </label>
            <!-- 出发/结束与天数联动:填区间自动算天数,改天数自动推结束 -->
            <div class="pf-2 pf-2c">
              <label class="pf">
                <Calendar :size="14" />
                <input
                  type="date"
                  v-model="store.params.startDate"
                  :class="{ dimmed: store.params.arrival?.date }"
                  :title="store.params.arrival?.date ? '已按到达锚点日期对齐' : ''"
                />
              </label>
              <label class="pf">
                <span class="pf-lab">结束</span>
                <input type="date" v-model="store.params.endDate" :min="store.params.startDate || undefined" />
              </label>
            </div>
            <div class="pf-2 pf-2b">
              <label class="pf">
                <span class="pf-lab">天数</span>
                <input type="number" min="1" max="15" placeholder="几天" v-model.number="store.params.days" />
              </label>
              <label class="pf">
                <Coins :size="14" />
                <input type="number" min="0" step="100" placeholder="预算" v-model.number="store.params.budget" />
              </label>
            </div>

            <div class="pg-sec">同行与节奏</div>
            <div class="pf-2 pf-2b">
              <label class="pf">
                <Users :size="14" />
                <input type="number" min="1" max="20" placeholder="人数" v-model.number="store.params.groupSize" />
              </label>
              <label class="pf">
                <span class="pf-lab">关系</span>
                <select class="pf-sel" v-model="store.params.companionType">
                  <option value="">未说明</option>
                  <option>独行</option>
                  <option>情侣</option>
                  <option>朋友</option>
                  <option>家庭</option>
                  <option>家庭·有老人小孩</option>
                </select>
              </label>
            </div>
            <div class="pace" role="group" aria-label="节奏偏好">
              <button
                v-for="pz in PACES"
                :key="pz"
                class="pace-btn"
                :class="{ on: store.params.pace === pz }"
                @click="store.params.pace = pz"
              >{{ pz }}</button>
            </div>
            <div class="pace-hint">{{ PACE_HINT[store.params.pace] || '未选择，生成时默认适中' }}</div>

            <div class="pg-sec">偏好</div>
            <div class="pf-prefs">
              <button v-for="p in PREFS" :key="p" class="pref" :class="{ on: store.params.preferences.includes(p) }" @click="togglePref(p)">{{ p }}</button>
            </div>

            <div class="pg-sec">交通与住宿</div>
            <label class="pf">
              <Bus :size="14" />
              <select class="pf-sel" v-model="store.params.transport">
                <option value="">市内交通不限</option>
                <option>公共交通优先</option>
                <option>打车</option>
                <option>自驾</option>
              </select>
            </label>
            <!-- 住宿方式：优先于"住哪儿"——它决定行程结构（全程一处 vs 分段换住） -->
            <div class="lg-mode" role="group" aria-label="住宿方式">
              <button
                v-for="m in LODGING_MODES"
                :key="m.value"
                type="button"
                class="lg-btn"
                :class="{ on: store.params.lodgingMode === m.value }"
                :title="m.hint"
                @click="store.params.lodgingMode = store.params.lodgingMode === m.value ? '' : m.value"
              >{{ m.label }}</button>
            </div>
            <label class="pf">
              <BedDouble :size="14" />
              <input
                v-model="store.params.lodging"
                :placeholder="store.params.lodgingMode === '换着住' ? '住宿区域，如 春熙路、都江堰（可用、分隔）' : '住宿区域，如 春熙路'"
              />
            </label>
            <div class="lg-tip">只填区域名就行，具体住处自己去高德订</div>

            <div class="pg-sec">硬约束 <span class="pg-opt">(选填)</span></div>
            <label class="pf">
              <Star :size="14" />
              <input v-model="store.params.mustVisit" placeholder="必须去，逗号分隔，如 大熊猫基地、宽窄巷子" />
            </label>
            <label class="pf">
              <Ban :size="14" />
              <input v-model="store.params.avoid" placeholder="回避项，如 不吃辣、不能早起、不去网红店" />
            </label>

            <!-- 到达 / 返程 硬锚点子区（用户在画像卡手填,首末日的景点密度与住宿晚数按这两个时刻反推） -->
            <div class="an-wrap">
              <button class="an-toggle" :class="{ open: anchorsOpen }" @click="anchorsOpen = !anchorsOpen" type="button">
                <ChevronDown :size="13" class="an-chev" />
                <span>{{ anchorsOpen ? '收起' : '补充' }}到达 / 返程时间</span>
                <span class="an-opt">(可选)</span>
                <span v-if="anchorFilledCount" class="an-badge">{{ anchorFilledCount }}</span>
              </button>
              <transition name="an">
                <div v-if="anchorsOpen" class="an-body">
                  <div class="an-row">
                    <div class="an-h">
                      <ArrowDownLeft :size="13" />
                      <span>到达</span>
                      <button
                        v-if="anchorFilled(store.params.arrival)"
                        class="an-x"
                        @click="clearAnchor('arrival')"
                        title="清除到达信息"
                        aria-label="清除到达信息"
                        type="button"
                      ><X :size="12" /></button>
                    </div>
                    <div class="an-line">
                      <input
                        type="date"
                        v-model="store.params.arrival.date"
                        class="an-dt"
                        :class="{ unsure: store.params.arrival.confidence != null && store.params.arrival.confidence < 0.7 }"
                        :title="store.params.arrival.confidence != null && store.params.arrival.confidence < 0.7 ? 'AI 从对话中识别，建议确认' : '只填时刻也可以，日期可以稍后补'"
                        aria-label="到达日期"
                      />
                      <input
                        type="time"
                        v-model="store.params.arrival.time"
                        class="an-dt an-time"
                        :class="{ unsure: store.params.arrival.confidence != null && store.params.arrival.confidence < 0.7 }"
                        :title="store.params.arrival.date ? '' : '只说了时刻也没关系，日期留空等确认'"
                        aria-label="到达时间"
                      />
                      <select v-model="store.params.arrival.type" class="an-sel" aria-label="到达交通方式">
                        <option value="train">高铁</option>
                        <option value="flight">飞机</option>
                        <option value="bus">大巴</option>
                        <option value="car">自驾</option>
                        <option value="other">其他</option>
                      </select>
                    </div>
                    <input type="text" v-model="store.params.arrival.location" class="an-loc" placeholder="站点/机场，如 成都东站" />
                  </div>
                  <div class="an-row">
                    <div class="an-h">
                      <ArrowUpRight :size="13" />
                      <span>返程</span>
                      <button
                        v-if="anchorFilled(store.params.departure)"
                        class="an-x"
                        @click="clearAnchor('departure')"
                        title="清除返程信息"
                        aria-label="清除返程信息"
                        type="button"
                      ><X :size="12" /></button>
                    </div>
                    <div class="an-line">
                      <input
                        type="date"
                        v-model="store.params.departure.date"
                        class="an-dt"
                        :class="{ unsure: store.params.departure.confidence != null && store.params.departure.confidence < 0.7 }"
                        :title="store.params.departure.confidence != null && store.params.departure.confidence < 0.7 ? 'AI 从对话中识别，建议确认' : '只填时刻也可以，日期可以稍后补'"
                        aria-label="返程日期"
                      />
                      <input
                        type="time"
                        v-model="store.params.departure.time"
                        class="an-dt an-time"
                        :class="{ unsure: store.params.departure.confidence != null && store.params.departure.confidence < 0.7 }"
                        :title="store.params.departure.date ? '' : '只说了时刻也没关系，日期留空等确认'"
                        aria-label="返程时间"
                      />
                      <select v-model="store.params.departure.type" class="an-sel" aria-label="返程交通方式">
                        <option value="train">高铁</option>
                        <option value="flight">飞机</option>
                        <option value="bus">大巴</option>
                        <option value="car">自驾</option>
                        <option value="other">其他</option>
                      </select>
                    </div>
                    <input type="text" v-model="store.params.departure.location" class="an-loc" placeholder="站点/机场" />
                  </div>
                  <div class="an-hint">只填时刻也能先记下，日期稍后补；日期与时刻都齐了，生成时才会启用首/末日边界（首日景点 ≥ 到达+90min，末日景点 ≤ 返程-150min）</div>
                </div>
              </transition>
            </div>

            <div class="pm-err" v-if="store.genError">{{ store.genError }}</div>
            <p v-if="!store.genBusy && store.lastAction === 'generate'" class="gen-hint">你已确认生成，点下面的按钮开始（不会自动执行）。</p>
            <p v-else-if="!store.genBusy && store.lastAction === 'ready'" class="gen-hint">信息已经够了，确认无误后可以生成行程。</p>
            <button
              class="btn gen"
              :class="{ hot: store.lastAction === 'generate' }"
              :disabled="store.genBusy || !store.canGenerate"
              @click="store.generate()"
            >
              <Loader2 v-if="store.genBusy" :size="15" class="spin" />
              <Sparkles v-else :size="15" />
              {{ store.genBusy ? 'AI 正在规划…' : (store.lastAction === 'generate' ? '开始生成行程' : '生成行程（约 30-60 秒）') }}
            </button>
            <p v-if="!store.genBusy && store.genBlockText" class="gen-hint">{{ store.genBlockText }}</p>
          </div>
        </transition>
        <!-- 两种"没收到正常回复"要分开看：
             notice —— 我们主动拒绝/引导（如问题超范围、输入过长），属正常对话，走中性灰；
             error  —— 系统真出故障（断网/超时），才用红色提醒重试。 -->
        <div
          v-if="store.chatError || (store.genError && !store.showParams)"
          class="gen-err-inline"
          :class="{ error: store.chatErrorKind === 'failure' }"
          role="status"
        >
          {{ store.chatError || store.genError }}
        </div>
      </div>

      <!-- 输入区 -->
      <footer class="ch-foot">
        <div class="composer">
          <textarea
            ref="taRef"
            v-model="draft"
            rows="1"
            placeholder="写下你想去的城市、天数、预算…"
            @input="resizeTa"
            @keydown="onKey"
          ></textarea>
          <button class="send" :disabled="!draft.trim() || store.chatBusy || overLimit" @click="submit" aria-label="发送">
            <Loader2 v-if="store.chatBusy" :size="16" class="spin" />
            <Send v-else :size="16" />
          </button>
        </div>
        <div class="foot-hint">
          <span v-if="overLimit" class="over-limit">超了 {{ draft.length - MAX_INPUT }} 字，排行程说重点就行</span>
          <span v-else>Enter 发送 · Shift+Enter 换行</span>
        </div>
      </footer>
    </template>
  </aside>
</template>

<style scoped>
.gen-hint { margin: 6px 2px 0; font-size: 12px; color: var(--ink-4); text-align: center; }
.btn.gen:disabled { opacity: 0.55; cursor: not-allowed; }
.chat {
  position: relative; /* 历史浮层等绝对定位的基准 */
  display: flex; flex-direction: column;
  height: 100%; width: 360px; flex: none;
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-1);
  transition: width var(--t-morph) var(--ease);
  overflow: hidden;
}
.chat.collapsed { width: 60px; }
.chat.fill { width: auto; flex: 1 1 0; min-width: 0; }
.rail {
  flex: none; height: 100%; width: 100%;
  border: 0; background: var(--paper-raise); cursor: pointer;
  display: flex; flex-direction: column; align-items: center; gap: 14px;
  padding: 18px 0; color: var(--ink-3);
  transition: background var(--t-base) var(--ease), color var(--t-base) var(--ease);
}
.rail:hover { background: var(--blue-wash); color: var(--blue); }
.rail-txt { writing-mode: vertical-rl; letter-spacing: 0.28em; font-size: 12.5px; }
.rail-dot { font-size: 11px; background: var(--blue); color: #fff; border-radius: 999px; padding: 1px 7px; font-family: var(--f-mono); }

/* 头部 */
.ch-head {
  flex: none; height: 58px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 12px 0 16px; border-bottom: 1px solid var(--hair-3);
}
.ch-title { display: flex; align-items: center; gap: 9px; min-width: 0; }
.ch-name { font-size: 14.5px; color: var(--ink); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ch-acts { display: flex; gap: 4px; }
.ch-btn { width: 32px; height: 32px; border-radius: var(--r-md); border: 0; background: none; color: var(--ink-3); cursor: pointer; display: grid; place-items: center; transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease); }
.ch-btn:hover { background: var(--paper-sink); color: var(--blue); }
.ch-btn.on { background: var(--blue-wash); color: var(--blue); }

/* 主体 */
.ch-body { flex: 1; overflow-y: auto; padding: 18px 16px; display: flex; flex-direction: column; gap: 14px; }

.empty { text-align: center; padding: 24px 8px 8px; }
.empty-ic { width: 44px; height: 44px; border-radius: 50%; background: var(--blue-wash); color: var(--blue); display: grid; place-items: center; margin: 0 auto 12px; }
.empty h4 { margin: 0 0 6px; font-size: 19px; color: var(--ink); }
.empty-tip { margin: 0 0 16px; font-size: 13px; line-height: 1.7; color: var(--ink-3); }
.chips { display: flex; flex-direction: column; gap: 8px; }
.chip { text-align: left; border: 1px solid var(--hair-2); background: var(--paper); border-radius: var(--r-md); padding: 10px 12px; font-size: 13px; color: var(--ink-2); cursor: pointer; font-family: var(--f-body); transition: all var(--t-fast) var(--ease); }
.chip:hover { border-color: var(--blue-line); background: var(--blue-wash); color: var(--blue); transform: translateY(-1px); }

/* 孤儿行程(有行程无会话):用中性灰调,不与主 CTA 蓝争注意力 */
.empty.orphan { padding-top: 32px; }
.empty.orphan .orphan-ic { background: var(--paper-sink); color: var(--ink-3); }
.empty.orphan h4 { color: var(--ink-2); font-size: 16px; }
.empty.orphan .empty-tip { font-size: 12.5px; color: var(--ink-3); margin-bottom: 0; }

/* 气泡 */
.row { display: flex; gap: 8px; max-width: 100%; }
.row.user { justify-content: flex-end; }
.av { flex: none; width: 26px; height: 26px; border-radius: 50%; background: var(--blue-wash); color: var(--blue); display: grid; place-items: center; margin-top: 2px; }
.bubble { padding: 10px 13px; border-radius: 14px; font-size: 13.5px; line-height: 1.65; max-width: 78%; word-break: break-word; }
.bubble .txt { white-space: pre-wrap; }
.row.user .bubble { background: var(--blue); color: #fff; border-bottom-right-radius: 5px; }
.row.assistant .bubble { background: var(--paper-raise); color: var(--ink); border-bottom-left-radius: 5px; }
.bubble.typing { display: flex; gap: 4px; align-items: center; padding: 13px 15px; }
.bubble.typing i { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-4); animation: blink 1.2s infinite both; }
.bubble.typing i:nth-child(2) { animation-delay: 0.2s; }
.bubble.typing i:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.3; } 40% { opacity: 1; } }

/* 旅行画像卡 */
.params { border: 1px solid var(--blue-line); background: var(--blue-wash); border-radius: var(--r-lg); padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.pm-head { display: flex; align-items: center; gap: 7px; font-size: 13.5px; font-weight: 600; color: var(--blue); }
.pm-head .pm-t { flex: 1; }
.pm-tip { flex: none; font-size: 11px; font-weight: 400; color: var(--ink-4); }
.pm-x { border: 0; background: none; cursor: pointer; color: var(--ink-3); display: grid; place-items: center; }
/* 服务端算出的缺失项：低干扰提示，不抢主表单 */
.pm-miss { display: flex; align-items: baseline; gap: 7px; background: var(--paper); border: 1px dashed var(--blue-line); border-radius: var(--r-md); padding: 7px 10px; }
.pc { margin: 2px 0 10px; border: 1px solid var(--blue-line); border-radius: var(--r-md); padding: 8px 10px; display: grid; gap: 5px; background: var(--paper); }
.pc-row { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.5; }
.pc-k { flex: none; color: var(--ink-3); }
.pc-v { color: var(--ink); word-break: break-word; }
.pc-v.empty { color: var(--ink-4); }
.btn.gen.hot { background: var(--blue); color: #fff; }
.pm-miss-k { flex: none; font-size: 11px; letter-spacing: 0.08em; color: var(--ink-4); }
.pm-miss-v { font-size: 12px; line-height: 1.55; color: var(--ink-2); }
/* 分组小标题 */
.pg-sec { font-size: 11px; letter-spacing: 0.12em; color: var(--ink-3); font-weight: 600; margin-top: 2px; display: flex; align-items: baseline; gap: 5px; }
.pg-opt { font-weight: 400; letter-spacing: normal; color: var(--ink-4); }
.pf { display: flex; align-items: center; gap: 8px; background: var(--paper); border: 1px solid var(--hair-2); border-radius: var(--r-md); padding: 0 10px; color: var(--ink-3); }
.pf input { flex: 1; border: 0; background: none; outline: none; font-family: var(--f-body); font-size: 13px; color: var(--ink); padding: 9px 0; min-width: 0; }
.pf select.pf-sel { flex: 1; border: 0; background: none; outline: none; font-family: var(--f-body); font-size: 13px; color: var(--ink); padding: 8px 0; min-width: 0; appearance: auto; cursor: pointer; }
.pf-lab { font-size: 12.5px; color: var(--ink-3); white-space: nowrap; }
.pf-2 { display: grid; grid-template-columns: 1.4fr 0.8fr 1fr; gap: 8px; }
.pf-2b { grid-template-columns: 1fr 1.4fr; }
.pf-2c { grid-template-columns: 1.35fr 1fr; }
/* 节奏三档分段控件 */
.pace { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.pace-btn {
  border: 1px solid var(--hair-2); background: var(--paper); border-radius: var(--r-md);
  font-family: var(--f-body); font-size: 12.5px; color: var(--ink-2); padding: 7px 0; cursor: pointer;
  transition: all var(--t-fast) var(--ease);
}
.pace-btn:hover { border-color: var(--blue-line); color: var(--blue); }
.pace-btn.on { background: var(--blue); color: #fff; border-color: var(--blue); }
/* 住宿方式二选一：和节奏按钮同一套交互语言 */
.lg-mode { display: flex; gap: 6px; }
.lg-btn {
  flex: 1; padding: 7px 10px; font-size: 12.5px; font-family: var(--f-body);
  border: 1px solid var(--hair-2); background: var(--paper); color: var(--ink-2);
  border-radius: var(--r-md); cursor: pointer;
  transition: all var(--t-fast) var(--ease);
}
.lg-btn:hover { border-color: var(--blue-line); color: var(--blue); }
.lg-btn.on { background: var(--blue-wash); border-color: var(--blue-line); color: var(--blue); font-weight: 600; }
.lg-tip { font-size: 11.5px; color: var(--ink-4); padding: 0 2px; }

/* ===== 景点图文卡片 ===== */
.bubble.has-spots { max-width: 92%; }
.spot-cards {
  display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px; margin-top: 10px;
}
.sc {
  position: relative; display: flex; flex-direction: column;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-md); overflow: hidden;
  transition: border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.sc:hover { border-color: var(--blue-line); transform: translateY(-1px); }
.sc-img { aspect-ratio: 16 / 10; background: var(--paper-sink); overflow: hidden; }
.sc-img img { width: 100%; height: 100%; object-fit: cover; display: block; }
.sc-noimg { width: 100%; height: 100%; display: grid; place-items: center; color: var(--ink-4); }
.sc-body { padding: 7px 9px 9px; min-width: 0; }
.sc-name {
  font-size: 12.5px; font-weight: 600; color: var(--ink); line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.sc-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 4px; font-size: 11px; color: var(--ink-3); }
.sc-rating { display: inline-flex; align-items: center; gap: 2px; color: var(--blue); }
.sc-time { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sc-add {
  position: absolute; top: 6px; right: 6px;
  width: 24px; height: 24px; border-radius: 50%;
  border: 0; background: rgba(255, 255, 255, 0.92); color: var(--blue);
  display: grid; place-items: center; cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.15);
  opacity: 0; transition: opacity var(--t-fast) var(--ease), background var(--t-fast) var(--ease);
}
.sc:hover .sc-add, .sc-add:focus-visible { opacity: 1; }
.sc-add:hover { background: var(--blue); color: #fff; }


.pace-hint { font-size: 11px; color: var(--ink-4); padding: 0 2px; }
.pf-prefs { display: flex; flex-wrap: wrap; gap: 6px; }
.pref { border: 1px solid var(--hair-2); background: var(--paper); border-radius: 999px; padding: 4px 11px; font-size: 12px; color: var(--ink-2); cursor: pointer; font-family: var(--f-body); transition: all var(--t-fast) var(--ease); }
.pref.on { background: var(--blue); color: #fff; border-color: var(--blue); }
.pm-err { font-size: 12px; color: var(--seal); }
.btn.gen { justify-content: center; margin-top: 2px; }

/* 默认(notice)：中性色，不当作错误。红只留给真正的故障，红线只在「地点没核验上」这类地方出现 */
.over-limit { color: var(--seal); }
.gen-err-inline { font-size: 12px; color: var(--ink-3); text-align: center; line-height: 1.6; padding: 0 4px; }
.gen-err-inline.error { color: var(--seal); }

/* 输入区 */
.ch-foot { flex: none; padding: 12px 16px 10px; border-top: 1px solid var(--hair-3); }
.composer { display: flex; align-items: flex-end; gap: 8px; background: var(--paper-raise); border: 1px solid var(--hair-2); border-radius: var(--r-lg); padding: 8px 8px 8px 14px; transition: border-color var(--t-base) var(--ease), box-shadow var(--t-base) var(--ease); }
.composer:focus-within { border-color: var(--blue-line); box-shadow: 0 0 0 3px var(--blue-wash); }
.composer textarea { flex: 1; border: 0; background: none; outline: none; resize: none; font-family: var(--f-body); font-size: 13.5px; line-height: 1.5; color: var(--ink); max-height: 120px; padding: 5px 0; scrollbar-width: none; }
/* 文字超长时内部仍可滚动,但不显示原生滚动条(Windows 经典滚动条的上下箭头很突兀) */
.composer textarea::-webkit-scrollbar { width: 0; display: none; }
.send { flex: none; width: 34px; height: 34px; border-radius: 50%; border: 0; background: var(--blue); color: #fff; cursor: pointer; display: grid; place-items: center; transition: transform var(--t-fast) var(--ease), opacity var(--t-fast) var(--ease); }
.send:hover:not(:disabled) { transform: scale(1.08); }
.send:active:not(:disabled) { transform: scale(0.94); }
.send:disabled { opacity: 0.4; cursor: default; }
.foot-hint { font-size: 11px; color: var(--ink-4); text-align: center; margin-top: 7px; }

.pm-enter-active, .pm-leave-active { transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); }
.pm-enter-from, .pm-leave-to { opacity: 0; transform: translateY(8px); }

/* 到达/返程 硬锚点子区 */
.an-wrap { display: flex; flex-direction: column; gap: 8px; }
.an-toggle {
  display: inline-flex; align-items: center; gap: 6px;
  align-self: flex-start;
  border: 0; background: none; cursor: pointer;
  color: var(--ink-3); font-family: var(--f-body); font-size: 12.5px;
  padding: 4px 2px;
  transition: color var(--t-fast) var(--ease);
}
.an-toggle:hover { color: var(--blue); }
.an-chev { transition: transform var(--t-morph) var(--ease); }
.an-toggle.open .an-chev { transform: rotate(180deg); }
.an-opt { color: var(--ink-4); font-size: 11.5px; }
.an-badge {
  font-family: var(--f-mono); font-size: 11px; line-height: 1;
  background: var(--blue); color: #fff;
  border-radius: 999px; padding: 3px 6px;
}
.an-body { display: flex; flex-direction: column; gap: 8px; }
.an-row {
  display: flex; flex-direction: column; gap: 6px;
  background: var(--paper); border: 1px solid var(--hair-2);
  border-radius: var(--r-md); padding: 8px 10px;
}
.an-h { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ink-2); font-weight: 500; }
.an-x {
  margin-left: auto;
  width: 18px; height: 18px; border-radius: 50%;
  border: 0; background: none; cursor: pointer;
  color: var(--ink-3); display: grid; place-items: center;
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.an-x:hover { background: var(--paper-sink); color: var(--seal); }
.an-line { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.85fr) auto; gap: 6px; align-items: center; }
.an-dt, .an-sel, .an-loc {
  font-family: var(--f-body); font-size: 12.5px;
  padding: 6px 8px;
  border: 1px solid var(--hair-2); border-radius: var(--r-sm);
  background: var(--paper-raise); color: var(--ink);
  outline: none; min-width: 0;
  transition: border-color var(--t-fast) var(--ease), box-shadow var(--t-fast) var(--ease);
}
.an-time { padding-left: 5px; padding-right: 4px; }
.an-dt:focus, .an-sel:focus, .an-loc:focus {
  border-color: var(--blue-line);
  box-shadow: 0 0 0 2px var(--blue-wash);
}
/* AI 从对话抽取的低置信锚点:暖橙提示"建议确认",不阻塞生成 */
.an-dt.unsure {
  border-color: #d99a3d;
  background: #fdf6ea;
}
.an-hint { font-size: 11.5px; line-height: 1.55; color: var(--ink-3); padding: 0 2px; }

/* 到达锚点存在时,startDate 视觉降饱和,提示"以 arrival 为准"(不 disable,仍可读) */
.pf input.dimmed { opacity: 0.5; }

/* 锚点子区展开/收起动画:与 .pm 主卡同款语言,只是方向自上而下 */
.an-enter-active, .an-leave-active { transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease); }
.an-enter-from, .an-leave-to { opacity: 0; transform: translateY(-4px); }

.spin { animation: xjspin 0.9s linear infinite; }
@keyframes xjspin { to { transform: rotate(360deg); } }
</style>
