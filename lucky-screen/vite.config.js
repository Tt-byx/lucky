import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  // 统一入口部署：构建产物挂在 nginx 的 /screen/ 子路径下，
  // 资源引用必须带该前缀（/screen/assets/xxx.js），否则会被根路径的 location 吃掉变 404。
  // dev（npm run dev）保持根路径不变，本地开发习惯不受影响。
  base: command === 'build' ? '/screen/' : '/',
  server: {
    port: 5173,
    host: '0.0.0.0'
  }
}))
