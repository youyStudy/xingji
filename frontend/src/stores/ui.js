import { defineStore } from 'pinia'

/**
 * 界面态 store —— 目前只装「登录/注册弹窗」。
 * 约定：登录与注册不再各占一个路由，而是首页（及全站）上的一个浮层。
 *   open  控制显隐；mode 决定表单是登录还是注册；next 是登录后要回跳的目标（可空）。
 * 这个 store 不依赖 router，也不依赖 auth，避免循环引用；
 * 谁想打开弹窗（顶栏按钮 / 401 回调 / 路由守卫）直接调用这里的 action。
 */
export const useUiStore = defineStore('ui', {
  state: () => ({
    authOpen: false,
    authMode: 'login', // 'login' | 'register'
    authNext: '', // 成功后的回跳路径；'' 表示就地关闭、留在当前页
    // 「跳到相册」意图:导航按钮置位,由 ProfileView 消费后清零。
    // 用标志而不是 URL hash,是因为已经在 /me 时再点一次也要能滚过去(同路由 push 不产生导航)。
    galleryJump: false
  }),

  actions: {
    openAuth(mode = 'login', next = '') {
      this.authMode = mode
      this.authNext = next
      this.authOpen = true
    },
    openLogin(next = '') {
      this.openAuth('login', next)
    },
    openRegister(next = '') {
      this.openAuth('register', next)
    },
    switchMode(mode) {
      if (mode === 'login' || mode === 'register') this.authMode = mode
    },
    closeAuth() {
      this.authOpen = false
      this.authNext = ''
    }
  }
})
