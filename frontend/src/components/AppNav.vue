<script setup>
import { ref } from 'vue'
import { onClickOutside } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'
import AppBrand from './AppBrand.vue'
import { ChevronDown, LogOut, UserRound, Compass, CalendarRange, Images } from 'lucide-vue-next'

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const open = ref(false)
const menu = ref(null)
onClickOutside(menu, () => (open.value = false))

const initial = () => (auth.displayName || '旅').slice(0, 1).toUpperCase()

function to(name) {
  open.value = false
  router.push({ name })
}
// 相册住在个人中心里:先挂一次「跳过去」的意图,再保证人在 /me
function toGallery() {
  open.value = false
  ui.galleryJump = true
  if (route.name !== 'profile') router.push({ name: 'profile' })
}
function signOut() {
  open.value = false
  auth.signOut()
}
</script>

<template>
  <header class="appnav">
    <div class="bar">
      <router-link to="/" class="brand">
        <AppBrand :size="22" />
        <span class="display word">行迹</span>
      </router-link>

      <nav class="links">
        <button class="lk" :class="{ on: $route.name === 'explore' }" @click="to('explore')"><Compass :size="15" /> 探索</button>
        <button class="lk" :class="{ on: $route.name === 'trips' }" @click="to('trips')"><CalendarRange :size="15" /> 我的行程</button>
        <button class="lk" :class="{ on: $route.name === 'profile' }" @click="toGallery"><Images :size="15" /> 相册</button>
      </nav>

      <div class="me" ref="menu">
        <button class="me-btn" :class="{ on: open }" @click="open = !open" aria-haspopup="menu">
          <span class="av">
            <img v-if="auth.user?.avatar" :src="auth.user.avatar" :alt="auth.displayName" />
            <template v-else>{{ initial() }}</template>
          </span>
          <span class="nm">{{ auth.displayName }}</span>
          <ChevronDown :size="15" class="chev" />
        </button>

        <transition name="menu">
          <div v-if="open" class="dropdown" role="menu">
            <div class="dd-head">
              <div class="dd-av">{{ initial() }}</div>
              <div>
                <div class="dd-nm">{{ auth.displayName }}</div>
                <div class="dd-ph mono">{{ auth.user?.phone || '未绑定手机号' }}</div>
              </div>
            </div>
            <button class="dd-item" role="menuitem" @click="to('profile')">
              <UserRound :size="16" /> 个人中心
            </button>
            <button class="dd-item danger" role="menuitem" @click="signOut">
              <LogOut :size="16" /> 退出登录
            </button>
          </div>
        </transition>
      </div>
    </div>
  </header>
</template>

<style scoped>
.appnav {
  position: sticky;
  top: 0;
  z-index: 40;
  background: color-mix(in srgb, var(--paper) 86%, transparent);
  backdrop-filter: saturate(1.2) blur(8px);
  border-bottom: 1px solid var(--hair-2);
}
.bar {
  height: 64px;
  display: flex;
  align-items: center;
  gap: clamp(20px, 3vw, 44px);
  padding-inline: clamp(20px, 3.2vw, 44px);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--ink);
}
.word { font-size: 20px; font-weight: 600; }
.links { display: inline-flex; gap: 6px; }
.lk {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: none;
  cursor: pointer;
  font-family: var(--f-body);
  font-size: 14px;
  color: var(--ink-2);
  padding: 8px 14px;
  border-radius: 999px;
  transition: background var(--t-base) var(--ease), color var(--t-base) var(--ease);
}
.lk:hover { background: var(--paper-sink); color: var(--blue); }
.lk.soon { color: var(--ink-4); cursor: default; }
.lk.soon:hover { background: none; color: var(--ink-4); }
.lk.on { background: var(--blue-wash); color: var(--blue); font-weight: 600; }

.me { position: relative; margin-left: auto; }
.me-btn {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--hair);
  background: var(--paper);
  border-radius: 999px;
  padding: 5px 12px 5px 5px;
  cursor: pointer;
  font-family: var(--f-body);
  color: var(--ink);
  transition: border-color var(--t-base) var(--ease), box-shadow var(--t-base) var(--ease);
}
.me-btn:hover, .me-btn.on { border-color: var(--blue-line); box-shadow: var(--shadow-1); }
.av {
  width: 30px; height: 30px; border-radius: 50%;
  background: var(--blue); color: #fff;
  display: grid; place-items: center; font-size: 13px; font-weight: 700;
  overflow: hidden;
}
.av img { width: 100%; height: 100%; object-fit: cover; }
.nm { font-size: 14px; max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chev { color: var(--ink-3); transition: transform var(--t-base) var(--ease); }
.me-btn.on .chev { transform: rotate(180deg); }

.dropdown {
  position: absolute;
  right: 0;
  top: calc(100% + 10px);
  width: 240px;
  background: var(--paper);
  border: 1px solid var(--hair-2);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-2);
  padding: 8px;
  z-index: 50;
}
.dd-head {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 10px 12px;
  border-bottom: 1px solid var(--hair-3);
  margin-bottom: 6px;
}
.dd-av {
  width: 38px; height: 38px; border-radius: 50%;
  background: var(--blue-wash); color: var(--blue);
  display: grid; place-items: center; font-weight: 700;
}
.dd-nm { font-size: 15px; color: var(--ink); }
.dd-ph { font-family: var(--f-mono); font-size: 11.5px; color: var(--ink-3); }
.dd-item {
  display: flex; align-items: center; gap: 10px; width: 100%;
  border: 0; background: none; cursor: pointer; text-align: left;
  font-family: var(--f-body); font-size: 14px; color: var(--ink-2);
  padding: 10px 10px; border-radius: var(--r-md);
  transition: background var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.dd-item:hover { background: var(--paper-raise); color: var(--ink); }
.dd-item.danger:hover { background: var(--seal-wash); color: var(--seal); }

.menu-enter-active, .menu-leave-active {
  transition: opacity var(--t-base) var(--ease), transform var(--t-base) var(--ease);
}
.menu-enter-from, .menu-leave-to { opacity: 0; transform: translateY(-8px) scale(0.98); }

@media (max-width: 720px) {
  .links { display: none; }
}
</style>
