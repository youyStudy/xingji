import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { reveal } from './composables/useReveal'
import { useAuthStore } from './stores/auth'
import './styles/theme.css'
import './styles/tokens.css'
import './styles/base.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
// 全站可用的滚动揭示指令 v-reveal
app.directive('reveal', reveal)
app.mount('#app')

// 会话恢复：注册 401 回调；本地有 token 就拉一次资料
useAuthStore(pinia).bootstrap()
