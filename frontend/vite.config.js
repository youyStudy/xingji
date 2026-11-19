import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 行迹前端：全新 Vue3 + Vite 工程。
// 后端 Spring Boot 在 8080，前端 5174（避开旧的 xingji-web:5173）。
// /api 与 /uploads 一律代理到后端，业务代码只写相对路径，部署换 Nginx 反代不用改。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    host: '127.0.0.1',
    // 【踩坑记录】Windows 下编辑器/工具写文件时会产生临时目录，
    // Vite watcher 尝试 watch 这些临时文件会 EBUSY 崩溃，必须忽略。
    watch: {
      ignored: ['**/*.bak', '**/.*.tmpdir/**', '**/*.tmp', '**/shots/**']
    },
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
