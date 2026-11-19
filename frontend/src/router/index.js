import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api/xingji'
import { useUiStore } from '../stores/ui'
import { getLenis } from '../composables/useLenis'

// 结构：落地页 + 个人中心。登录/注册不再占路由，是首页/全站上的弹窗（见 AuthModal）。
// 后续「地球探索 / AI 规划 / 我的行程」直接在这里挂新路由。
const routes = [
  {
    path: '/',
    name: 'landing',
    component: () => import('../views/LandingImmersive.vue'),
    meta: { title: '行迹 · 让每一个地点，都真实可去' }
  },
  {
    path: '/me',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { title: '个人中心 · 行迹', requiresAuth: true, neutral: true }
  },
  {
    path: '/explore',
    name: 'explore',
    component: () => import('../views/ExploreView.vue'),
    meta: { title: '探索 · 行迹', requiresAuth: true, neutral: true }
  },
  {
    path: '/trips',
    name: 'trips',
    component: () => import('../views/TripsView.vue'),
    meta: { title: '我的行程 · 行迹', requiresAuth: true, neutral: true }
  },
  {
    // 公开分享页：凭 token 免登录查看被分享的行程（数据来自 /api/public/share）
    path: '/s/:token',
    name: 'share',
    component: () => import('../views/ShareView.vue'),
    meta: { title: '分享的行程 · 行迹' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 相册跳转挂起时交给 ProfileView 自己滚：这里若返回 {top:0} 会把它的 scrollIntoView 顶回去
  scrollBehavior(to, from, savedPosition) {
    if (useUiStore().galleryJump) return false

    const lenis = getLenis()
    // Lenis 未启用（如 prefers-reduced-motion 跳过初始化）→ 走原生行为
    if (!lenis) {
      if (savedPosition) return savedPosition
      if (to.hash) return { el: to.hash, behavior: 'auto' }
      return { top: 0, behavior: 'auto' }
    }

    // Lenis 在跑：用它的补间做平滑跳转，返回 false 阻止 vue-router 默认瞬时滚动
    const opts = { duration: 1.1 }
    if (savedPosition) lenis.scrollTo(savedPosition.top, opts)
    else if (to.hash) lenis.scrollTo(to.hash, opts)
    else lenis.scrollTo(0, opts)
    return false
  }
})

// 守卫：受保护页未登录 → 回到首页并就地唤起登录弹窗（带上回跳目标）
router.beforeEach((to) => {
  const authed = Boolean(getToken())
  if (to.meta.requiresAuth && !authed) {
    useUiStore().openLogin(to.fullPath)
    return { name: 'landing' }
  }
  return true
})

router.afterEach((to) => {
  if (to.meta?.title) document.title = to.meta.title
  document.body.classList.toggle('app-neutral', Boolean(to.meta?.neutral))
})

export default router
