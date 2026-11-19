import { defineStore } from 'pinia'
import {
  login as apiLogin,
  register as apiRegister,
  oauthLogin as apiOauth,
  getProfile,
  updateProfile as apiUpdateProfile,
  getToken,
  setToken,
  clearToken,
  setUnauthorizedHandler
} from '../api/xingji'
import router from '../router'
import { useUiStore } from './ui'

/**
 * 会话 store：token 落地在 localStorage（键 xingji.token），user 是后端 UserVO。
 * 约定：JWT 的 subject 是 userId，前端不解析、只透传；401 时统一清态并弹回 /login。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    user: null,
    profileLoading: false,
    busy: false, // 登录/注册进行中
    error: '' // 最近一次表单级错误（后端中文提示）
  }),

  getters: {
    signedIn: (s) => Boolean(s.token),
    displayName: (s) => s.user?.nickname || s.user?.phone || '旅人'
  },

  actions: {
    /** 应用启动时调用一次：注册 401 回调；有 token 就顺手拉资料。 */
    bootstrap() {
      setUnauthorizedHandler(() => this._onUnauthorized())
      if (this.token && !this.user) this.fetchProfile()
    },

    _onUnauthorized() {
      this.token = ''
      this.user = null
      clearToken()
      const cur = router.currentRoute.value
      // 登录已改为全站弹窗：这里直接唤起，并保留受保护页的回跳意图
      const ui = useUiStore()
      ui.openLogin(cur.meta?.requiresAuth ? cur.fullPath : '')
    },

    async fetchProfile() {
      if (!this.token) return
      this.profileLoading = true
      try {
        this.user = await getProfile()
      } catch (e) {
        // 401 已由 api 层清态并回调；这里只兜其它错误
        if (e.kind !== 'unauthorized') this.error = e.message
      } finally {
        this.profileLoading = false
      }
    },

    async signIn({ phone, password }) {
      this.busy = true
      this.error = ''
      try {
        const t = await apiLogin(phone, password)
        this.token = t
        setToken(t)
        await this.fetchProfile()
        return true
      } catch (e) {
        this.error = e.message
        return false
      } finally {
        this.busy = false
      }
    },

    /** 注册成功后用同一手机号密码自动登录，省一次输入。 */
    async signUp({ phone, password, nickname, email }) {
      this.busy = true
      this.error = ''
      try {
        await apiRegister({ phone, password, nickname, email: email || undefined })
        const t = await apiLogin(phone, password)
        this.token = t
        setToken(t)
        await this.fetchProfile()
        return true
      } catch (e) {
        this.error = e.message
        return false
      } finally {
        this.busy = false
      }
    },

    async signInWithOauth(provider) {
      this.busy = true
      this.error = ''
      try {
        const t = await apiOauth(provider)
        this.token = t
        setToken(t)
        await this.fetchProfile()
        return true
      } catch (e) {
        this.error = e.message
        return false
      } finally {
        this.busy = false
      }
    },

    async saveProfile(dto) {
      this.busy = true
      this.error = ''
      try {
        this.user = await apiUpdateProfile(dto)
        return true
      } catch (e) {
        this.error = e.message
        return false
      } finally {
        this.busy = false
      }
    },

    signOut() {
      this.token = ''
      this.user = null
      this.error = ''
      clearToken()
      const ui = useUiStore()
      ui.closeAuth()
      if (router.currentRoute.value.name !== 'landing') router.push({ name: 'landing' })
    }
  }
})
