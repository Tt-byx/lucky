import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  // 统一入口部署：构建产物挂在 nginx 的 /admin/ 子路径下，
  // 资源引用必须带该前缀（/admin/assets/xxx.js）。dev 保持根路径不变。
  base: command === 'build' ? '/admin/' : '/',
  server: {
    port: 5175,
    host: '0.0.0.0'
  }
}))
