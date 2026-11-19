<script setup>
import { RouterView } from 'vue-router'
import AuthModal from './components/AuthModal.vue'
</script>

<template>
  <RouterView />
  <!-- 登录/注册是全站浮层，不再各占一个路由；这里挂一次，任何页面都能唤起 -->
  <AuthModal />
</template>
