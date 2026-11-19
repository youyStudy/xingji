/**
 * 后端接口层。统一外壳 Result<T> = { code, msg, data }；code !== 200 视为业务失败。
 * 免登录接口：/api/auth/**、/api/public/**、/api/place/{cities,recommend,recommend/smart,inspiration}
 * 需要登录的接口走 request({auth:true})：自动从 localStorage 取 token 塞进 Authorization 头，
 * 遇到 401（未登录/过期）触发全局 unauthorized 回调，由会话 store 负责清态并弹回登录。
 */

const BASE = '/api'
const TOKEN_KEY = 'xingji.token'

export class ApiError extends Error {
  constructor(message, { code = 0, kind = 'business' } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.kind = kind // business | network | timeout | unavailable | unauthorized
  }
}

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''
export const setToken = (t) => (t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY))
export const clearToken = () => localStorage.removeItem(TOKEN_KEY)

// 401 全局回调，由 auth store 在初始化时注册
let onUnauthorized = null
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

async function request(path, { method = 'GET', body, timeout = 8000, auth = false } = {}) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort('timeout'), timeout)
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth) {
    const t = getToken()
    if (t) headers['Authorization'] = `Bearer ${t}`
  }
  try {
    const res = await fetch(BASE + path, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: ctrl.signal
    })
    // 拦截器直接写回的 401（未登录/过期）
    if (res.status === 401) {
      clearToken()
      if (onUnauthorized) onUnauthorized()
      throw new ApiError('未登录或登录已失效', { code: 401, kind: 'unauthorized' })
    }
    if (!res.ok) {
      if (res.status === 408 || res.status === 504) {
        throw new ApiError('服务响应超时', { code: res.status, kind: 'timeout' })
      }
      if (res.status >= 500) {
        throw new ApiError(`HTTP ${res.status}`, { code: res.status, kind: 'unavailable' })
      }
      throw new ApiError(`HTTP ${res.status}`, { code: res.status, kind: 'network' })
    }
    const envelope = await res.json()
    if (envelope.code !== 200) {
      throw new ApiError(envelope.msg || '请求失败', { code: envelope.code })
    }
    return envelope.data
  } catch (e) {
    if (e instanceof ApiError) throw e
    if (e.name === 'AbortError') throw new ApiError('连接超时', { kind: 'timeout' })
    throw new ApiError('无法连接后端', { kind: 'network' })
  } finally {
    clearTimeout(timer)
  }
}

// ===== 认证（免登录） =====
/** 注册：phone / email? / password / nickname */
export const register = (payload) =>
  request('/auth/register', { method: 'POST', body: payload, timeout: 10000 })

/** 登录：返回裸 token 字符串 */
export const login = async (phone, password) => {
  const data = await request('/auth/login', { method: 'POST', body: { phone, password }, timeout: 10000 })
  return data?.token || ''
}

/** 第三方 mock 登录（provider: qq | wechat）：返回裸 token */
export const oauthLogin = async (provider) => {
  const data = await request(`/auth/oauth/${provider}`, { method: 'POST', timeout: 10000 })
  return data?.token || ''
}

// ===== 个人中心（需登录） =====
/** 我的资料 UserVO（不含密码） */
export const getProfile = () => request('/user/profile', { auth: true })

/** 编辑资料：nickname / email / avatar */
export const updateProfile = (dto) =>
  request('/user/profile', { method: 'PUT', body: dto, auth: true })

/** 改密码：校验原密码 → 更新新密码哈希 */
export const changePassword = (oldPassword, newPassword) =>
  request('/user/password', { method: 'PUT', body: { oldPassword, newPassword }, auth: true })

// ===== 素材上传（需登录；purpose='avatar' 时后端只存文件不入相册） =====
/**
 * 上传一张图片到 /api/media/upload（multipart）。成功返回 TravelMedia：
 * { id, userId, mediaType:'image', filePath:'{uid}/{yyyy-MM}/{uuid}.jpg', fileSize, uploadTime }。
 * purpose='avatar'：头像上传，不入库，返回对象没有 id。
 * 注意：不手动设 Content-Type，交给浏览器补 multipart boundary；401 与 Result 解包逻辑同 request。
 */
export async function uploadMedia(file, purpose) {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort('timeout'), 20000)
  const fd = new FormData()
  fd.append('file', file)
  const headers = {}
  const t = getToken()
  if (t) headers['Authorization'] = `Bearer ${t}`
  try {
    const url = BASE + '/media/upload' + (purpose ? `?purpose=${encodeURIComponent(purpose)}` : '')
    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: fd,
      signal: ctrl.signal
    })
    if (res.status === 401) {
      clearToken()
      if (onUnauthorized) onUnauthorized()
      throw new ApiError('未登录或登录已失效', { code: 401, kind: 'unauthorized' })
    }
    if (!res.ok) throw new ApiError(`HTTP ${res.status}`, { kind: 'network' })
    const envelope = await res.json()
    if (envelope.code !== 200) throw new ApiError(envelope.msg || '上传失败', { code: envelope.code })
    return envelope.data
  } catch (e) {
    if (e instanceof ApiError) throw e
    if (e.name === 'AbortError') throw new ApiError('上传超时', { kind: 'timeout' })
    throw new ApiError('无法连接后端', { kind: 'network' })
  } finally {
    clearTimeout(timer)
  }
}

/** 把入库的相对 filePath 拼成可直接访问的静态 URL（后端把 /uploads/** 映射到磁盘目录）。 */
export const mediaUrl = (filePath) => (filePath ? `/uploads/${filePath}` : '')

// ===== 素材库（个人中心「照片·视频」，需登录） =====
/** 我的未删除素材列表 TravelMedia[]（后端按 create_time DESC） */
export const listMedia = () => request('/media/list', { auth: true })

/** 回收站：已软删除的素材 TravelMedia[]（后端按 update_time DESC） */
export const trashMedia = () => request('/media/trash', { auth: true })

/** 软删除一条素材：置 deleted=1，进回收站，不物理删文件 */
export const deleteMedia = (id) => request(`/media/${id}`, { method: 'DELETE', auth: true })

/** 从回收站恢复一条素材：置 deleted=0 */
export const restoreMedia = (id) => request(`/media/${id}/restore`, { method: 'PUT', auth: true })

/** 清空回收站：物理删除当前用户所有软删素材 */
export const clearTrashMedia = () => request('/media/trash', { method: 'DELETE', auth: true })

// ===== AI 对话（UC02，需登录） =====
/** 新建会话：可选 title（?title=），返回会话 id(Integer) */
export const createSession = (title) =>
  request(`/chat/session${title ? `?title=${encodeURIComponent(title)}` : ''}`, {
    method: 'POST',
    auth: true,
    timeout: 10000
  })

/** 我的会话列表 ChatSession[] */
export const listSessions = () => request('/chat/sessions', { auth: true })

/**
 * 某会话详情 ChatSessionVO =
 * { session, messages[], profile: 服务端画像, missing: 缺失项[] }
 * （含历史消息，时间升序；刷新/切会话都靠它还原画像）
 */
export const getSession = (id) => request(`/chat/session/${id}`, { auth: true })

/**
 * 发一条消息 → ChatTurnVO = { id, content, profile, missing[], action }。
 * profile 传表单的整份快照（不是只带已填项）：服务端按整份覆盖合并，
 * 用户在画像卡里清掉的字段才能同步清掉。锚点回答也走这个接口，不再单独发追问轮。
 */
export const sendChat = (sessionId, content, profile) =>
  request('/chat/message', {
    method: 'POST',
    body: { sessionId, content, profile: profile || undefined },
    auth: true,
    // 免费档模型单轮可能 30~90 秒；45 秒会让前端先报失败、后端却已落库
    timeout: 120000
  })

/** 删除会话（连带清空消息） */
export const deleteSession = (id) => request(`/chat/session/${id}`, { method: 'DELETE', auth: true })

// ===== 行程（UC03/04，需登录） =====
/** AI 生成整棵行程树：入参 {city,startDate,days,budget,preferences[]} → ItineraryDetailVO
 *  超时给到 5 分钟:strong 档是推理模型,2-3 天行程实测要 2 分钟以上;120s 会前端先报失败、
 *  后端却继续跑完落库,造成"页面说失败、我的行程里却多了一条"的错位 */
export const generateItinerary = (req) =>
  request('/itinerary/generate', { method: 'POST', body: req, auth: true, timeout: 300000 })

/** 行程详情整棵树 ItineraryDetailVO = { itinerary, days:[{day,nodes[]}] } */
export const getItinerary = (id) => request(`/itinerary/${id}`, { auth: true })

/** 我的行程列表 Itinerary[]（每项仅 id/title/status/days/startDate/budget） */
export const listItineraries = () => request('/itinerary/list', { auth: true })

/** 校验行程：高德核验地点真实性 + 约束检查 → ValidationResultVO */
export const validateItinerary = (id) =>
  request(`/itinerary/${id}/validate`, { method: 'POST', auth: true, timeout: 30000 })

/** 按天重排：只重排某一天（带画像 + 一句反馈），其余天不动 → 更新后的整棵树。同生成一样走强模型，放宽超时 */
export const regenerateItineraryDay = (dayId, dto) =>
  request(`/itinerary/day/${dayId}/regenerate`, { method: 'POST', body: dto, auth: true, timeout: 120000 })

// ===== 行程编辑/删除（需登录）。注意：后端 update 是整体覆盖，字段必须全量传 =====
/** 改行程主体：{ title, startDate:'YYYY-MM-DD', days, budget } */
export const updateItinerary = (id, dto) =>
  request(`/itinerary/${id}`, { method: 'PUT', body: dto, auth: true })

/** 删除行程（软删除，列表即不可见） */
export const deleteItinerary = (id) => request(`/itinerary/${id}`, { method: 'DELETE', auth: true })

/** 置顶/取消置顶行程（列表按置顶在前排序） */
export const pinItinerary = (id, pinned) =>
  request(`/itinerary/${id}/pin?pinned=${pinned ? 1 : 0}`, { method: 'PUT', auth: true })

/** 末尾加一天：{ dayIndex, theme?, date? } → 新 dayId */
export const addItineraryDay = (id, dto) =>
  request(`/itinerary/${id}/day`, { method: 'POST', body: dto, auth: true })

/** 改某天（整体覆盖：{ dayIndex, theme, date } 全量传，dayIndex 由 store 沿用现值拼好） */
export const updateItineraryDay = (dayId, dto) =>
  request(`/itinerary/day/${dayId}`, { method: 'PUT', body: dto, auth: true })

/** 删除某天（后端连带清掉该天全部节点） */
export const deleteItineraryDay = (dayId) =>
  request(`/itinerary/day/${dayId}`, { method: 'DELETE', auth: true })

/** 某天加一个节点：{ placeName, nodeType, seq, startTime?, endTime?, cost? } → 新 nodeId */
export const addItineraryNode = (dayId, dto) =>
  request(`/itinerary/day/${dayId}/node`, { method: 'POST', body: dto, auth: true })

/** 改某个节点（全量字段：placeId/seq 沿用原值一并回传） */
export const updateItineraryNode = (nodeId, dto) =>
  request(`/itinerary/node/${nodeId}`, { method: 'PUT', body: dto, auth: true })

/** 删除某个节点 */
export const deleteItineraryNode = (nodeId) =>
  request(`/itinerary/node/${nodeId}`, { method: 'DELETE', auth: true })

// ===== 分享 =====
/** 为行程签发分享 token；前端拼 `${location.origin}/s/${token}` 得到访问地址 */
export const createShare = (itineraryId) =>
  request(`/share/${itineraryId}`, { method: 'POST', auth: true })

/** 撤销该行程的全部分享链接（旧链接随即失效） */
export const revokeShare = (itineraryId) =>
  request(`/share/${itineraryId}`, { method: 'DELETE', auth: true })

/** 免登录查看被分享的行程 ItineraryDetailVO；token 无效/撤销 → 业务错误 */
export const viewShare = (token) => request(`/public/share/${token}`, { timeout: 10000 })

// ===== 路线/导航（需登录；结果内联在行程面板，不再有独立页面） =====
/** 读取某行程已算好的路线段 RouteSegmentVO[] */
export const listRoutes = (itineraryId) => request(`/route/${itineraryId}`, { auth: true })
/** 重新计算并落库（mode: car/walk），返回最新 RouteSegmentVO[]。含多次高德请求，放宽超时 */
export const refreshRoutes = (itineraryId, mode = 'car') =>
  request(`/route/${itineraryId}/refresh?mode=${encodeURIComponent(mode)}`, { method: 'POST', auth: true, timeout: 30000 })
