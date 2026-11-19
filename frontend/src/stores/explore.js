import { defineStore } from 'pinia'
import {
  createSession as apiCreateSession,
  listSessions as apiListSessions,
  getSession as apiGetSession,
  sendChat as apiSendChat,
  generateItinerary as apiGenerate,
  validateItinerary as apiValidate,
  getItinerary as apiGetItinerary,
  listItineraries as apiListItineraries,
  updateItinerary as apiUpdateItinerary,
  deleteItinerary as apiDeleteItinerary,
  addItineraryDay as apiAddDay,
  updateItineraryDay as apiUpdateDay,
  deleteItineraryDay as apiDeleteDay,
  addItineraryNode as apiAddNode,
  updateItineraryNode as apiUpdateNode,
  deleteItineraryNode as apiDeleteNode,
  createShare as apiCreateShare,
  revokeShare as apiRevokeShare,
  regenerateItineraryDay as apiRegenDay,
  listRoutes as apiListRoutes,
  refreshRoutes as apiRefreshRoutes
} from '../api/xingji'

/**
 * 探索页工作台 store —— 把「AI 会话（可收缩边栏）+ 依附于会话的行程 + 地图」三份态收在一处。
 * 设计：会话是主界面；用户在会话里说需求，服务端把大模型返回的结构化补丁合并进
 *      chat_session.context 并整份回传，前端只负责展示和让用户手改。
 *      前端不再用正则猜天数/预算/城市——理解归模型，事实、校验、持久化归服务端。
 *      生成一律由用户点画像卡上的「生成行程」手动触发（生成慢，且用户应先确认画像）。
 *      生成的行程挂在当前会话下，右侧地图据此打点连线。
 * 这里不存 token，登录态仍由 auth store / api 层负责。
 */

/** 两个 ISO 日期差几天（b - a），非法返回 null */
function diffDays(a, b) {
  if (!a || !b) return null
  const da = new Date(a + 'T00:00:00Z').getTime()
  const db = new Date(b + 'T00:00:00Z').getTime()
  if (Number.isNaN(da) || Number.isNaN(db)) return null
  return Math.round((db - da) / 86400000)
}

/** 本地日期(不能用 toISOString:UTC 在东八区凌晨会算成昨天) */
const todayISO = () => {
  const d = new Date()
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}

/**
 * 请求代次:切换会话/新建对话时 opSeq++，在途请求据此丢弃迟到的响应，
 * 避免上一条会话的回复/行程落到新会话里。
 * chatReqId / genReqId 只负责"谁有权关掉 busy"，防止旧请求把新请求的 loading 关掉。
 */
let opSeq = 0
let chatReqId = 0
let genReqId = 0

/**
 * 空的交通锚点(手填初始态)。
 * date/time 分开存:用户经常只说得清一半(如"下午5点到、坐高铁"),不能因为缺日期
 * 就把时间和交通方式一起丢掉。datetime 是 date+time 的派生值,generate() 只认它。
 * type 默认 'train' 只是表单便利;服务端会忽略"只有默认交通方式、没有任何日期/时刻/地点"的空锚点。
 */
const emptyAnchor = () => ({
  date: '',
  time: '',
  datetime: '',
  location: '',
  type: 'train',
  source: '',
  confidence: null
})

/**
 * 服务端锚点 → 表单锚点。
 * 服务端主要给 date/time（只说了时刻时 date 为空——这正是要保留的半成品锚点），
 * 旧数据/旧接口可能只给 datetime，这里拆回 date+time 兼容。
 */
function anchorFromServer(a) {
  const base = emptyAnchor()
  if (!a) return base
  let date = a.date ? String(a.date).slice(0, 10) : ''
  let time = a.time ? String(a.time).slice(0, 5) : ''
  let dt = ''
  if (date && time) {
    dt = `${date}T${time}`
  } else if (a.datetime) {
    dt = String(a.datetime).replace(' ', 'T').slice(0, 16)
    if (!date) date = dt.slice(0, 10)
    if (!time && dt.length >= 16) time = dt.slice(11, 16)
  }
  return {
    date,
    time,
    datetime: date && time ? `${date}T${time}` : dt,
    location: a.location || '',
    type: a.type || base.type,
    source: a.source || '',
    confidence: a.confidence != null ? a.confidence : null
  }
}

/**
 * 旅行画像的默认值(除城市/预算外的字段)。
 * 注意:天数/日期/节奏初始都是空——"没填"和"填了 3 天"必须区分开,
 * 默认值一旦当真实值发给 AI,它会把"默认 3 天"当成用户确认过的信息。
 */
const emptyProfile = () => ({
  origin: '',          // 出发地城市(与目的地 city 配对)
  groupSize: null,      // 同行人数
  companionType: '',    // 独行/情侣/朋友/家庭
  pace: '',             // 特种兵/适中/悠闲(空=未选,生成时后端默认适中)
  transport: '',        // 公共交通优先/打车/自驾
  lodging: '',          // 住宿区域名(如"春熙路"),或用户自己说的住处
  lodgingMode: '',      // 住宿方式:"住一处" / "换着住";空=用户还没说
  mustVisit: '',        // 必去地点(逗号分隔,提交时拆数组)
  avoid: ''             // 回避项:不想去/忌口/不能早起等
})

/** 画像表单的整份初始态（新对话 / 切换会话前复位用） */
const emptyParams = () => ({
  city: '',
  startDate: null,
  endDate: null,
  days: null,
  budget: null,
  preferences: [],
  ...emptyProfile(),
  arrival: emptyAnchor(),
  departure: emptyAnchor()
})

/**
 * 老会话没有服务端画像（context 为空）时，用行程自身带的日期/天数/预算兜底，
 * 方便「接着聊 → 重排」。城市/偏好行程里不可靠，不从这里猜。
 */
const itinerarySeed = (it) => {
  const start = it?.startDate ? String(it.startDate).slice(0, 10) : null
  return {
    startDate: start,
    endDate: start && it?.days ? addDays(start, it.days - 1) : null,
    days: it?.days ?? null,
    budget: it?.budget != null ? Number(it.budget) : null
  }
}

/** 服务端画像是否真的带了信息（空对象/只有默认值视为没有） */
const profileHasCore = (p) => Boolean(p && (
  p.city || p.origin || p.days != null || p.startDate || p.endDate || p.budget != null ||
  (p.preferences && p.preferences.length) || p.groupSize != null || p.companionType ||
  p.pace || p.transport || p.lodging ||
  (p.mustVisit && p.mustVisit.length) || (p.avoid && p.avoid.length) ||
  p.arrival || p.departure
))

/** 把"必去/回避"输入框的逗号分隔文本拆成数组;兼容中英文逗号、顿号、分号 */
function splitList(v) {
  return String(v || '')
    .split(/[,，、;；]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/** 'YYYY-MM-DD' + n 天 → 'YYYY-MM-DD'。走 UTC 避免夏令时/时区把日期算漂一天 */
function addDays(iso, n) {
  if (!iso) return iso
  const d = new Date(iso + 'T00:00:00Z')
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}


/**
 * 后端行程详情 → 前端使用的形状。
 *
 * 后端把节点包成了 { node: {id, placeName, ...}, lng, lat, navUrl, located, locationNote }：
 *   - node 里是原来的节点字段（编辑表单仍按它读写）
 *   - 外层是新增的地图能力（坐标、高德导航链接、是否已定位）
 * 模板里如果到处写 n.node.placeName 会很啰嗦，所以这里摊平成
 * { ...node, lng, lat, navUrl, located, locationNote }，
 * 编辑、删除、显示都还按 n.id / n.placeName 用，和以前一致；
 * 新增的导航字段直接挂在同一个对象上。
 */
function normalizeDetail(detail) {
  if (!detail || !Array.isArray(detail.days)) return detail
  return {
    ...detail,
    days: detail.days.map((d) => ({
      ...d,
      nodes: (d.nodes || []).map((nv) => {
        // 兼容两种形状：老后端直接给节点，新后端包了 node 字段
        const raw = nv && nv.node ? nv.node : nv
        if (!raw) return raw
        return {
          ...raw,
          lng: nv?.lng ?? null,
          lat: nv?.lat ?? null,
          navUrl: nv?.navUrl || '',
          located: Boolean(nv?.located),
          locationNote: nv?.locationNote || ''
        }
      })
    }))
  }
}

export const useExploreStore = defineStore('explore', {
  state: () => ({
    // 会话
    sessions: [],
    sessionId: null,
    sessionTitle: '新对话',
    messages: [], // { id?, role, content, createTime? }
    chatBusy: false,
    chatError: '',
    // chatError 的性质:notice=我们主动拒绝/引导(正常对话的一部分), failure=系统故障需重试。
    // 视图据此决定用中性色还是红色,不把所有"没收到正常回复"都当成报错。
    chatErrorKind: 'notice',
    constraints: [], // 服务端结构化约束（PREFERENCE/EXCLUSION/SAFETY）
    conversationState: null,
    lastAction: 'chat', // chat / ready / generate（服务端最终动作）
    // 行程
    itinerary: null, // ItineraryDetailVO
    genBusy: false,
    genError: '',
    validateBusy: false,
    validateMsg: '', // 校验结果摘要文案
    // 手动编辑（节点/天/标题）。后端 update 接口是整体覆盖语义，action 里负责拼全量字段
    editBusy: false,
    editError: '',
    // 按天 AI 重排：regenDayId 表示正在重排哪一天（面板据此显示该天的等待态）
    regenDayId: null,
    // 分享：shareToken 为空表示当前行程尚未生成分享链接
    shareBusy: false,
    shareToken: '',
    shareCopied: false,
    // 路线：相邻两站的真实距离/耗时（后端按高德算好落库，只在用户点「计算路线」时才重算）
    routeSegments: [],
    routeBusy: false,
    routeError: '',
    routeMode: 'car',
    showParams: false, // 旅行画像卡：只由用户主动打开(或生成报错时打开)，对话中不自动弹
    params: emptyParams(), // 到达/返程锚点见 emptyAnchor:date/time 分开存,半成品也保留
    // 服务端算出的缺失项（city/origin/days/startDate/arrival.date/time/departure.date/time），
    // 前端只负责翻译成人话展示，不自己推导"还缺什么"
    missing: [],
    // 选中的行程没有关联会话(通常是早期版本生成、后端当时没落 session_id)
    // trips 页据此把"从零开始聊"和"这条行程的历史缺失"分开提示,避免误以为程序坏了
    tripSessionMissing: false,
    activeDay: 0, // 当前聚焦的天（地图/面板联动）
    // 布局
    collapsed: false, // AI 会话边栏是否收起（抽屉）
    itinCollapsed: false, // 行程面板是否收起（抽屉）
    // 「我的行程」载入既有行程时用（中栏会话 + 右栏安排都复用本 store）
    tripLoading: false,
    tripError: ''
  }),

  getters: {
    hasItinerary: (s) => Boolean(s.itinerary && s.itinerary.days && s.itinerary.days.length),
    /** 对话进度是否够生成：目的地 + 天数 + 出发日期/到达时间（服务端 ready 同一口径） */
    canGenerate: (s) => Boolean(s.params.city && s.params.days && (s.params.startDate || s.params.arrival?.date)),
    /** 不够生成时告诉用户还差什么（按钮置灰的提示） */
    genBlockText: (s) => {
      const miss = []
      if (!s.params.city) miss.push('目的地')
      if (!s.params.days) miss.push('天数')
      if (!s.params.startDate && !s.params.arrival?.date) miss.push('出发日期或到达时间')
      return miss.length ? `还差${miss.join('、')}，补齐后就能生成` : ''
    },
    /**
     * 方案说明(advice)解析成对象:{budgetPlan,reminders,backups}。
     * 放 getter 供两个行程组件(探索页面板/我的行程每日规划)共用;坏文本整体当 budgetPlan 展示,不报错。
     */
    adviceObj: (s) => {
      const raw = s.itinerary?.itinerary?.advice
      if (!raw) return null
      try {
        const a = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (!a || (!a.budgetPlan && !a.reminders && !a.backups)) return null
        return a
      } catch (e) {
        return typeof raw === 'string' ? { budgetPlan: raw, reminders: '', backups: '' } : null
      }
    }
  },

  actions: {
    toggleCollapse() {
      this.collapsed = !this.collapsed
    },

    toggleItinCollapse() {
      this.itinCollapsed = !this.itinCollapsed
    },

    /**
     * 服务端整份画像 → 表单。服务端是唯一事实源：每轮对话、打开会话都整份回填，
     * 用户在画像卡里手动清掉的字段也会同步清掉，不会只增不减。
     * fallback 只给老会话用（context 为空）：拿行程自带的日期/天数/预算兜底，
     * 否则每次打开旧行程画像都是空白，「接着聊→重排」还得重填一遍。
     */
    applyServerProfile(profile, fallback) {
      const has = profileHasCore(profile)
      const p = has ? profile : {}
      const f = has ? {} : (fallback || {})
      this.params = {
        city: p.city || '',
        origin: p.origin || '',
        startDate: p.startDate ? String(p.startDate).slice(0, 10) : (f.startDate || null),
        endDate: p.endDate ? String(p.endDate).slice(0, 10) : (f.endDate || null),
        days: p.days != null ? Number(p.days) : (f.days != null ? Number(f.days) : null),
        budget: p.budget != null ? Number(p.budget) : (f.budget != null ? Number(f.budget) : null),
        preferences: Array.isArray(p.preferences) ? [...p.preferences] : [],
        groupSize: p.groupSize != null ? Number(p.groupSize) : null,
        companionType: p.companionType || '',
        pace: p.pace || '',
        transport: p.transport || '',
        lodging: p.lodging || '',
        lodgingMode: p.lodgingMode || '',
        // 输入框是逗号分隔文本，服务端存的是数组
        mustVisit: Array.isArray(p.mustVisit) ? p.mustVisit.join('，') : '',
        avoid: Array.isArray(p.avoid) ? p.avoid.join('，') : '',
        arrival: anchorFromServer(p.arrival),
        departure: anchorFromServer(p.departure)
      }
      this.constraints = Array.isArray(p.constraints) ? p.constraints : []
      // 有出发日+天数但缺结束日时补上（只影响展示，不改服务端事实）
      if (!this.params.endDate && this.params.startDate && this.params.days) {
        this.params.endDate = addDays(this.params.startDate, this.params.days - 1)
      }
      // 到达/返程都带日期时，以锚点为硬边界反推日期与天数
      this.syncDatesFromAnchors()
    },

    /** 清空某个锚点（保留 type 默认值，其余字段归零） */
    clearAnchor(which) {
      if (which !== 'arrival' && which !== 'departure') return
      this.params[which] = emptyAnchor()
    },

    /** date + time → datetime。生成接口只认 datetime，发送前统一派生一次。 */
    syncAnchorDatetimes() {
      const fix = (a) => {
        const n = { ...emptyAnchor(), ...(a || {}) }
        n.date = n.date ? String(n.date).slice(0, 10) : ''
        n.time = n.time ? String(n.time).slice(0, 5) : ''
        n.datetime = n.date && n.time ? `${n.date}T${n.time}` : ''
        return n
      }
      this.params.arrival = fix(this.params.arrival)
      this.params.departure = fix(this.params.departure)
    },

    /** 载入一条既有行程（「我的行程」中栏+右栏复用本 store）：详情进 itinerary，顺带回放它当初的会话消息 */
    async loadTrip(id) {
      if (!id) return
      this.tripLoading = true
      this.tripError = ''
      // 打开另一条行程 = 旧请求作废，避免迟到回复串进这条会话
      opSeq++
      chatReqId++
      genReqId++
      this.chatBusy = false
      this.genBusy = false
      // 复位与这条行程无关的临时态
      this.activeDay = 0
      this.showParams = false
      this.genError = ''
      this.chatError = ''
      this.validateMsg = ''
      this.validateBusy = false
      this.editError = ''
      this.shareToken = ''
      this.shareCopied = false
      this.routeSegments = []
      this.routeError = ''
      this.routeBusy = false
      try {
        const detail = await apiGetItinerary(id)
        this.itinerary = normalizeDetail(detail)
        this.loadRouteSegments(id)
        const it = detail?.itinerary || {}
        this.sessionTitle = it.title || '我的行程'
        this.sessionId = it.sessionId || null
        this.tripSessionMissing = !this.sessionId
        this.messages = []
        // 老会话 context 可能是空的：先用行程元信息兜底，拉到服务端画像后再整份覆盖
        const seed = itinerarySeed(it)
        this.params = emptyParams()
        this.applyServerProfile(null, seed)
        this.missing = []
        if (this.sessionId) {
          // 会话历史拉取失败不影响查看行程安排，静默即可;
          // 但 404(会话已被删除)时置孤儿标记,中栏显示"这条行程暂无对话历史"而不是空白
          try {
            const vo = await apiGetSession(this.sessionId)
            this.messages = (vo.messages || []).map((m) => ({
              id: m.id,
              role: m.role,
              content: m.content,
              createTime: m.createTime
            }))
            if (vo.session?.title) this.sessionTitle = vo.session.title
            // 服务端画像/缺失项以会话为准；空画像时 applyServerProfile 会保留 seed
            this.applyServerProfile(vo.profile, seed)
            this.conversationState = vo.conversationState || null
            this.missing = Array.isArray(vo.missing) ? vo.missing : []
          } catch (e) {
            if (e.kind === 'unauthorized') return
            this.tripSessionMissing = true
          }
        }
      } catch (e) {
        if (e.kind === 'unauthorized') return
        this.tripError = e.message || '加载行程失败'
      } finally {
        this.tripLoading = false
      }
    },

    async refreshSessions() {
      try {
        this.sessions = await apiListSessions()
        // 后端在首条消息后会自动改会话标题；这里顺手同步到头部，否则头部一直显示"新对话"
        const cur = this.sessions.find((s) => s.id === this.sessionId)
        if (cur?.title) this.sessionTitle = cur.title
      } catch (e) {
        /* 列表失败不阻断主流程 */
      }
    },

    /** 确保有当前会话；没有就新建一个 */
    async ensureSession() {
      if (this.sessionId) return this.sessionId
      const id = await apiCreateSession(this.sessionTitle)
      // 创建期间用户可能已经切到别的会话/新建对话，不能把当前会话顶掉
      if (this.sessionId) return this.sessionId
      this.sessionId = id
      return id
    },

    newSession() {
      // 让所有在途请求作废：旧会话的回复/行程不能落到新对话里
      opSeq++
      chatReqId++
      genReqId++
      this.chatBusy = false
      this.genBusy = false
      this.sessionId = null
      this.sessionTitle = '新对话'
      this.messages = []
      this.constraints = []
      this.conversationState = null
      this.lastAction = 'chat'
      this.itinerary = null
      this.activeDay = 0
      this.showParams = false
      this.genError = ''
      this.chatError = ''
      this.tripSessionMissing = false
      this.editError = ''
      this.shareToken = ''
      this.shareCopied = false
      this.routeSegments = []
      this.routeError = ''
      this.routeBusy = false
      this.validateMsg = ''
      this.validateBusy = false
      // 整份画像归零：新对话不能继承上一条的目的地/天数/预算/日期/偏好
      this.params = emptyParams()
      this.missing = []
    },

    async openSession(id, nav) {
      this.chatError = ''
      try {
        const vo = await apiGetSession(id)
        // 拉取期间用户又切了会话/新建对话：本次结果作废，不能把 sessionId 顶回去
        if (nav != null && nav !== opSeq) return false
        this.sessionId = id
        this.sessionTitle = vo.session?.title || '对话'
        this.messages = (vo.messages || []).map((m) => ({
          id: m.id,
          role: m.role,
          content: m.content,
          createTime: m.createTime
        }))
        this.itinerary = null
        this.routeSegments = []
        this.activeDay = 0
        this.tripSessionMissing = false
        // 服务端画像是这个会话的唯一事实源：打开就整份回填，刷新页面也不丢
        this.applyServerProfile(vo.profile)
        this.missing = Array.isArray(vo.missing) ? vo.missing : []
        return vo
      } catch (e) {
        if (nav != null && nav !== opSeq) return false
        this.chatError = e.message || '打开会话失败'
        return false
      }
    },

    /** 切到某条历史会话：回放消息；若它生成过行程（itinerary.session_id 关联）也一并找回 */
    async switchSession(id) {
      if (!id) return
      if (id === this.sessionId) return
      // 切会话 = 旧请求全部作废，避免回复/行程串到目标会话
      opSeq++
      const nav = opSeq
      chatReqId++
      genReqId++
      this.chatBusy = false
      this.genBusy = false
      this.chatError = ''
      this.genError = ''
      this.collapsed = false
      this.showParams = false
      this.tripSessionMissing = false
      this.validateMsg = ''
      this.validateBusy = false
      this.editError = ''
      this.shareToken = ''
      this.shareCopied = false
      try {
        const vo = await this.openSession(id, nav)
        if (!vo || nav !== opSeq) return
        const list = await apiListItineraries()
        if (nav !== opSeq || this.sessionId !== id) return
        const mine = (list || []).find((t) => t.sessionId === id)
        if (mine) {
          const detail = await apiGetItinerary(mine.id)
          if (nav !== opSeq || this.sessionId !== id) return
          this.itinerary = normalizeDetail(detail)
          this.loadRouteSegments(mine.id)
          this.activeDay = 0
          this.shareToken = ''
          // 老会话没有服务端画像时，才用行程元信息兜底；
          // 有画像时以画像为准，绝不能让行程把用户刚说的新需求覆盖回去
          if (!profileHasCore(vo.profile)) {
            this.applyServerProfile(null, itinerarySeed(detail?.itinerary))
            this.missing = []
          }
        }
      } catch (e) {
        if (nav !== opSeq) return
        this.chatError = e.message || '切换会话失败'
      }
    },

    /**
     * 画像卡当前值 → 整份快照。服务端按"整份覆盖"处理它：
     * 用户在表单里清掉的字段必须原样传空，否则服务端会保留旧值、表单看起来像清不掉。
     * 到达/返程传结构化对象（date/time 分开），只说了时刻的半成品锚点也能保住。
     */
    chatProfile() {
      const p = this.params
      const iso = (v) => (v ? String(v).slice(0, 10) : '')
      const anchor = (a) => ({
        date: a?.date ? String(a.date).slice(0, 10) : '',
        time: a?.time ? String(a.time).slice(0, 5) : '',
        location: (a?.location || '').trim(),
        type: a?.type || ''
      })
      return {
        origin: (p.origin || '').trim(),
        city: (p.city || '').trim(),
        startDate: iso(p.startDate),
        endDate: iso(p.endDate),
        days: p.days ? Number(p.days) : null,
        budget: p.budget !== '' && p.budget != null ? Number(p.budget) : null,
        preferences: [...(p.preferences || [])],
        groupSize: p.groupSize ? Number(p.groupSize) : null,
        companionType: p.companionType || '',
        pace: p.pace || '',
        transport: p.transport || '',
        lodging: (p.lodging || '').trim(),
        lodgingMode: p.lodgingMode || '',
        mustVisit: splitList(p.mustVisit),
        avoid: splitList(p.avoid),
        constraints: this.constraints || [],
        arrival: anchor(p.arrival),
        departure: anchor(p.departure)
      }
    },

    /**
     * 发一条消息：每一句话（包括对到达/返程问题的回答）都走同一个对话接口。
     * 服务端负责把模型返回的结构化补丁合并进画像、校验、落库，再整份回传；
     * 前端只把回传的画像铺到表单、把 missing 翻译出来，不再本地猜字段。
     */
    async send(text) {
      const content = (text || '').trim()
      if (!content || this.chatBusy) return
      // 请求归属:切会话/新建对话会推进 opSeq,迟到的响应据此丢弃,不能落进别人的会话
      const nav = opSeq
      const req = ++chatReqId
      this.chatError = ''
      this.chatErrorKind = 'notice'
      this.chatBusy = true
      // 乐观插入用户气泡
      this.messages.push({ role: 'user', content })
      try {
        // 首句话先作为会话标题占位；后端首轮也会按原话自动命名
        if (this.sessionTitle === '新对话') this.sessionTitle = content.slice(0, 50)
        const sid = await this.ensureSession()
        if (nav !== opSeq || this.sessionId !== sid) return
        const turn = await apiSendChat(sid, content, this.chatProfile())
        if (nav !== opSeq || this.sessionId !== sid) return
        // 服务端是画像的唯一事实源：整份回填，前端不再自己 merge
        this.applyServerProfile(turn.profile)
        this.conversationState = turn.conversationState || null
        this.lastAction = turn.action || 'chat'
        this.missing = Array.isArray(turn.missing) ? turn.missing : []
        // spots：服务端给的真实景点卡片数据（名字/照片/评分/门票）。
        // 有它就把这条消息渲染成"文字 + 卡片"，没有就是普通气泡。
        this.messages.push({
          id: turn.id,
          role: 'assistant',
          content: turn.content,
          createTime: turn.createTime,
          spots: Array.isArray(turn.spots) ? turn.spots : []
        })
        // 【产品原则】对话为主、表单为辅：这里不主动弹画像卡。
        // 以前一旦"目的地+天数齐了"就弹整张表单，会打断用户正在说的话，
        // 而且此时出发地/日期/住宿多半还没问，用户被迫面对一张空表单——又变回了填表。
        // 现在改为：对话里用一行轻提示让用户知道"我记下了什么"(见 ChatSidebar 的记忆条)，
        // 想改再点开；画像卡只在两种时机出现：
        //   ① 用户主动点右上角按钮
        //   ② 点「生成行程」且信息不全时（那是真有错要改）
        this.refreshSessions()
      } catch (e) {
        if (e.kind === 'unauthorized') return
        if (nav !== opSeq) return
        // 区分"我们主动拒绝了"和"系统真的出故障了":
        // 前者(后端业务码 4xx)是正常对话的一部分,话术后端已经写好,直接展示;
        // 后者(网络断/超时)才需要提示用户重试。
        if (e.kind === 'timeout') {
          this.chatError = '这次等太久了，可能有别的任务在排队。再发一次试试？'
          this.chatErrorKind = 'failure'
        } else if (e.kind === 'unavailable') {
          this.chatError = '服务暂时没响应，稍等一会儿再试。'
          this.chatErrorKind = 'failure'
        } else if (e.kind === 'network') {
          this.chatError = '连不上服务器了，检查一下网络再试。'
          this.chatErrorKind = 'failure'
        } else if (e.code && e.code >= 400 && e.code < 500) {
          // 业务性拒绝：message 已经是给用户看的话术，中性展示，别标成故障
          this.chatError = e.message
          this.chatErrorKind = 'notice'
        } else {
          this.chatError = e.message || '发送失败，再试一次？'
          this.chatErrorKind = 'failure'
        }
      } finally {
        // 期间若已切会话,新会话的 busy 状态不能被这次请求清掉
        if (req === chatReqId) this.chatBusy = false
      }
    },

    /**
     * 到达/返程两个锚点齐了 → 用它们反推出发/结束日期与总天数。
     * 锚点是硬边界(首末日排程、住宿晚数后端都按它算),优先级高于口头"玩几天";
     * 也让"只说了到达/返程、没说天数"的会话不再卡在『请至少填写目的地和天数』。
     */
    syncDatesFromAnchors() {
      const dateOf = (a) => {
        if (!a) return ''
        if (a.date) return String(a.date).slice(0, 10)
        return a.datetime ? String(a.datetime).slice(0, 10) : ''
      }
      const start = dateOf(this.params.arrival)
      const end = dateOf(this.params.departure)
      if (!start || !end) return
      const diff = diffDays(start, end)
      if (diff == null || diff < 0) return
      this.params.startDate = start
      this.params.endDate = end
      this.params.days = diff + 1
    },

    /** 用当前 params 生成行程（依附于当前会话） */
    async generate() {
      if (this.genBusy) return
      this.genError = ''
      const p = this.params
      // date+time 派生 datetime；日期或时刻缺一不可，只有半成品锚点时按未启用处理
      this.syncAnchorDatetimes()
      // 锚点齐了先用锚点反推日期/天数(手动在卡里填到达/返程同样生效),再走校验
      this.syncDatesFromAnchors()
      if (!p.city || !p.days) {
        this.genError = '请至少填写目的地和天数'
        this.showParams = true
        return
      }

      // 组装锚点：生成接口只认 datetime，缺日期或时刻就不塞进 payload
      const buildAnchor = (a) => a?.date && a?.time
        ? {
            datetime: `${a.date}T${a.time}`,
            location: (a.location || '').trim() || null,
            type: a.type || null,
            source: a.source || 'manual',   // 手填走 manual;保留字段透传以便未来其他来源(如 OCR/日历导入)复用同一契约
            confidence: a.confidence != null ? a.confidence : 1.0
          }
        : undefined
      const arrival = buildAnchor(p.arrival)
      const departure = buildAnchor(p.departure)

      // 前端预检（与后端 applyAnchorContract 语义等价，失败省一次网络往返）
      if (arrival && departure && departure.datetime < arrival.datetime) {
        this.genError = '返程时间早于到达时间，请调整'
        this.showParams = true
        return
      }

      // arrival 是硬锚：存在时 startDate 强制对齐到 arrival.date（和后端一致，UI 显示也同步）
      let startDate = p.startDate
      if (arrival) {
        startDate = arrival.datetime.slice(0, 10)
        this.params.startDate = startDate
      }
      // 出发日期仍缺:用结束日期反推(结束 - (天数-1)),这仍是用户明确给过的信息
      if (!startDate && p.endDate && p.days) {
        startDate = addDays(String(p.endDate).slice(0, 10), -(Number(p.days) - 1))
        this.params.startDate = startDate
      }
      // 仍然没有:绝不能默认"今天出发"——"用户 26 号到、行程从今天排起"就是这里来的。
      // 必须明确:出发日期或到达时间,二选一
      if (!startDate) {
        this.genError = '请先填写出发日期（或到达时间），才好按天排程'
        this.showParams = true
        return
      }

      // 日期已过就别再花一次 30-60 秒的生成：模型和地图都会按过去的日期排出无效行程
      if (startDate && String(startDate).slice(0, 10) < todayISO()) {
        this.genError = `出发日期（${String(startDate).slice(0, 10)}）已经过去了，请重新选择日期`
        this.showParams = true
        return
      }

      if (departure && startDate && p.days) {
        const tripEnd = addDays(startDate, Number(p.days) - 1)
        const depDate = departure.datetime.slice(0, 10)
        if (depDate < tripEnd) {
          this.genError = `返程日期（${depDate}）早于行程末（${tripEnd}），请调整天数或返程时间`
          this.showParams = true
          return
        }
      }

      // 请求归属:生成耗时最长(可到 120s),期间切会话的概率最高
      const nav = opSeq
      const req = ++genReqId
      this.genBusy = true
      this.showParams = false
      try {
        const sid = await this.ensureSession()
        if (nav !== opSeq || this.sessionId !== sid) return
        const payload = {
          city: p.city,
          startDate,
          days: Number(p.days),
          budget: p.budget ? Number(p.budget) : undefined,
          preferences: p.preferences && p.preferences.length ? p.preferences : undefined,
          // 旅行画像:填了才带,后端拼进 prompt 由系统规则消费
          groupSize: p.groupSize ? Number(p.groupSize) : undefined,
          companionType: p.companionType || undefined,
          pace: p.pace || '适中',
          transport: p.transport || undefined,
          lodging: p.lodging && p.lodging.trim() ? p.lodging.trim() : undefined,
        lodgingMode: p.lodgingMode || undefined,
          mustVisit: splitList(p.mustVisit).length ? splitList(p.mustVisit) : undefined,
          avoid: splitList(p.avoid).length ? splitList(p.avoid) : undefined,
          constraints: this.constraints && this.constraints.length ? this.constraints : undefined,
          // 把当前会话 id 带给后端,写入 itinerary.session_id;否则"我的行程"里选中该行程时,会话拉不出来、看不到历史对话
          sessionId: sid
        }
        if (arrival) payload.arrival = arrival
        if (departure) payload.departure = departure
        const detail = await apiGenerate(payload)
        if (nav !== opSeq || this.sessionId !== sid) return
        this.itinerary = normalizeDetail(detail)
        // 新行程还没有算过路线，先清空再静默读一次（通常为空）
        this.routeSegments = []
        this.routeError = ''
        this.loadRouteSegments(detail?.itinerary?.id)
        this.activeDay = 0
        // 会话标题只由后端（首条消息自动命名）维护；生成行程不改名，避免头部和历史列表对不上
        this.refreshSessions()
        this.shareToken = ''
        this.shareCopied = false
        this.editError = ''
      } catch (e) {
        if (e.kind !== 'unauthorized' && nav === opSeq) this.genError = e.message || '生成失败，请重试'
      } finally {
        if (req === genReqId) this.genBusy = false
      }
    },

    /** 校验当前行程：高德核验地点真实性 + 约束检查；随后重读详情刷新异常标记 */
    async validate() {
      const id = this.itinerary?.itinerary?.id
      if (!id || this.validateBusy) return
      this.validateBusy = true
      this.validateMsg = ''
      try {
        const r = await apiValidate(id)
        const parts = [`共 ${r.total ?? 0} 个地点`, `确认 ${r.matched ?? 0}`]
        if (r.abnormal) parts.push(`待核验 ${r.abnormal}`)
        this.validateMsg = parts.join(' · ')
        // 重读整棵树，拿到回填的 placeId / isAbnormal
        const detail = await apiGetItinerary(id)
        if (detail) this.itinerary = normalizeDetail(detail)
      } catch (e) {
        if (e.kind !== 'unauthorized') this.validateMsg = e.message || '校验失败'
      } finally {
        this.validateBusy = false
      }
    },

    /** 静默读取已算好的路线段（只查库，不调高德）；没有就算，不打扰用户 */
    async loadRouteSegments(id) {
      if (!id) { this.routeSegments = []; return }
      try {
        const rows = await apiListRoutes(id)
        // 读取期间切了行程：过期结果丢弃
        if (this.itinerary?.itinerary?.id !== id) return
        this.routeSegments = Array.isArray(rows) ? rows : []
      } catch (e) {
        if (e.kind !== 'unauthorized') this.routeSegments = []
      }
    },

    /** 按当前出行方式重算整条行程的相邻路段（含多次高德请求，只在用户点击时触发） */
    async calcRoutes() {
      const id = this.itinerary?.itinerary?.id
      if (!id || this.routeBusy) return
      this.routeBusy = true
      this.routeError = ''
      try {
        const rows = await apiRefreshRoutes(id, this.routeMode)
        if (this.itinerary?.itinerary?.id !== id) return
        this.routeSegments = Array.isArray(rows) ? rows : []
        return this.routeSegments.length
      } catch (e) {
        if (e.kind !== 'unauthorized') this.routeError = e.message || '路线计算失败'
        return false
      } finally {
        this.routeBusy = false
      }
    },

    setActiveDay(i) {
      if (i >= 0 && i < (this.itinerary?.days?.length || 0)) this.activeDay = i
    },

    /* ===== 手动编辑（后端 update 是整体覆盖，这里统一拼全量字段再提交） ===== */

    /** 编辑后重读整棵树，让面板/地图与库内保持一致；天数变少时收拢 activeDay */
    async reloadDetail() {
      const id = this.itinerary?.itinerary?.id
      if (!id) return
      const detail = await apiGetItinerary(id)
      if (!detail) return
      this.itinerary = normalizeDetail(detail)
      // 节点增删/改名后旧分段可能悬空：重读一次，面板只展示仍能对上节点的段
      this.loadRouteSegments(id)
      const n = detail.days?.length || 0
      if (this.activeDay >= n) this.activeDay = Math.max(0, n - 1)
    },

    /** 改行程主体（标题/日期/天数/预算），patch 里只给要改的字段，其余沿用现值补全 */
    async saveItinerary(patch) {
      const it = this.itinerary?.itinerary
      if (!it || this.editBusy) return false
      this.editBusy = true
      this.editError = ''
      try {
        await apiUpdateItinerary(it.id, {
          title: (patch.title != null ? patch.title : it.title) || '',
          startDate: patch.startDate != null ? patch.startDate : (it.startDate ? String(it.startDate).slice(0, 10) : null),
          days: patch.days != null ? patch.days : (it.days ?? null),
          budget: patch.budget != null ? patch.budget : (it.budget ?? null)
        })
        await this.reloadDetail()
        return true
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '保存失败'
        return false
      } finally {
        this.editBusy = false
      }
    },

    /** 删除整条行程（软删除；二次确认由 UI 层负责）。
     *  后端会连带删掉"没有其他存活行程"的来源会话,删完刷新会话列表,
     *  当前会话若被连带删了就回到新对话态,避免后续发消息打到已删除的会话上 */
    async removeItinerary() {
      const id = this.itinerary?.itinerary?.id
      if (!id || this.editBusy) return
      this.editBusy = true
      this.editError = ''
      try {
        await apiDeleteItinerary(id)
        this.itinerary = null
        this.routeSegments = []
        this.routeError = ''
        this.activeDay = 0
        this.shareToken = ''
        this.validateMsg = ''
        await this.refreshSessions()
        if (this.sessionId && !this.sessions.some((s) => s.id === this.sessionId)) {
          this.newSession()
        }
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '删除失败'
      } finally {
        this.editBusy = false
      }
    },

    /** 末尾追加一天：序号接排、日期顺延一天，主题留空 */
    async appendDay() {
      const it = this.itinerary?.itinerary
      if (!it || this.editBusy) return
      this.editBusy = true
      this.editError = ''
      try {
        const days = this.itinerary.days || []
        const last = days.length ? days[days.length - 1] : null
        const lastIndex = last?.day?.dayIndex || days.length
        const lastDate = last?.day?.date || it.startDate
        const nextDate = lastDate ? addDays(String(lastDate).slice(0, 10), 1) : null
        await apiAddDay(it.id, { dayIndex: lastIndex + 1, theme: '', date: nextDate })
        await this.reloadDetail()
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '添加失败'
      } finally {
        this.editBusy = false
      }
    },

    /** 删除某天（连带其节点；二次确认由 UI 层负责） */
    async removeDay(dayId) {
      if (!dayId || this.editBusy) return
      this.editBusy = true
      this.editError = ''
      try {
        await apiDeleteDay(dayId)
        await this.reloadDetail()
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '删除失败'
      } finally {
        this.editBusy = false
      }
    },

    /** 改某天（主题/日期）。后端整体覆盖，dayIndex 沿用现值补全 */
    async saveDay(dayId, patch) {
      const d = (this.itinerary?.days || []).find((x) => x.day?.id === dayId)
      if (!d || this.editBusy) return false
      this.editBusy = true
      this.editError = ''
      try {
        await apiUpdateDay(dayId, {
          dayIndex: patch.dayIndex != null ? patch.dayIndex : (d.day?.dayIndex ?? 1),
          theme: patch.theme != null ? patch.theme : (d.day?.theme || ''),
          date: patch.date != null ? patch.date : (d.day?.date ? String(d.day.date).slice(0, 10) : null)
        })
        await this.reloadDetail()
        return true
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '保存失败'
        return false
      } finally {
        this.editBusy = false
      }
    },

    /**
     * 保存节点（新增或编辑）。form: { id?, dayId, placeId?, placeName, nodeType, seq?, startTime?, endTime?, cost? }
     * placeId/seq 编辑时沿用原值回传，避免后端整体覆盖把它们清掉。
     */
    async saveNode(form) {
      if (this.editBusy) return false
      const payload = {
        placeId: form.placeId ?? null,
        placeName: (form.placeName || '').trim(),
        nodeType: form.nodeType || '景点',
        seq: form.seq ?? 1,
        startTime: form.startTime || null,
        endTime: form.endTime || null,
        cost: form.cost !== '' && form.cost != null ? Number(form.cost) : null
      }
      if (!payload.placeName) {
        this.editError = '地点名不能为空'
        return false
      }
      if (payload.startTime && payload.endTime && payload.endTime < payload.startTime) {
        this.editError = '结束时间早于开始时间'
        return false
      }
      this.editBusy = true
      this.editError = ''
      try {
        if (form.id) await apiUpdateNode(form.id, payload)
        else await apiAddNode(form.dayId, payload)
        await this.reloadDetail()
        return true
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '保存失败'
        return false
      } finally {
        this.editBusy = false
      }
    },

    /** 删除某个节点 */
    async removeNode(nodeId) {
      if (!nodeId || this.editBusy) return
      this.editBusy = true
      this.editError = ''
      try {
        await apiDeleteNode(nodeId)
        await this.reloadDetail()
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '删除失败'
      } finally {
        this.editBusy = false
      }
    },

    /**
     * 按天 AI 重排：只重排某一天，其余天不动（代替整份重写）。
     * feedback 是一句自然语言反馈（"太赶了，少排点，晚上加个夜市"），可空。
     * 画像不落库，这里从 params 现拼；城市必须有，否则不知道去哪拉候选景点。
     */
    async regenDay(dayId, feedback) {
      if (!dayId || this.regenDayId) return
      if (!this.params.city) {
        this.editError = '先在旅行画像里填目的地城市，AI 才能重排这一天'
        this.showParams = true
        return
      }
      this.regenDayId = dayId
      this.editError = ''
      try {
        const p = this.params
        const dto = {
          city: p.city,
          feedback: (feedback || '').trim() || null,
          pace: p.pace || '适中',
          transport: p.transport || null,
          preferences: p.preferences && p.preferences.length ? p.preferences : null,
          mustVisit: splitList(p.mustVisit).length ? splitList(p.mustVisit) : null,
          avoid: splitList(p.avoid).length ? splitList(p.avoid) : null
        }
        const detail = await apiRegenDay(dayId, dto)
        if (detail) {
          this.itinerary = normalizeDetail(detail)
          this.loadRouteSegments(detail?.itinerary?.id)
        }
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '重排失败，请重试'
      } finally {
        this.regenDayId = null
      }
    },

    /* ===== 分享 ===== */

    /** 为当前行程签发分享链接并复制到剪贴板；返回完整 URL */
    async share() {
      const id = this.itinerary?.itinerary?.id
      if (!id || this.shareBusy) return ''
      this.shareBusy = true
      this.editError = ''
      try {
        const token = await apiCreateShare(id)
        this.shareToken = token
        const url = `${location.origin}/s/${token}`
        try {
          await navigator.clipboard.writeText(url)
          this.shareCopied = true
          setTimeout(() => (this.shareCopied = false), 2500)
        } catch (_) {
          /* 剪贴板权限被拒不影响链接展示，用户可手动复制 */
        }
        return url
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '生成分享链接失败'
        return ''
      } finally {
        this.shareBusy = false
      }
    },

    /** 撤销当前行程的全部分享链接（旧链接随即失效） */
    async unshare() {
      const id = this.itinerary?.itinerary?.id
      if (!id || this.shareBusy) return
      this.shareBusy = true
      this.editError = ''
      try {
        await apiRevokeShare(id)
        this.shareToken = ''
        this.shareCopied = false
      } catch (e) {
        if (e.kind !== 'unauthorized') this.editError = e.message || '撤销失败'
      } finally {
        this.shareBusy = false
      }
    }
  }
})
