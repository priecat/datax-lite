import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5188,
    proxy: {
      '/api': {
        target: 'http://localhost:8001',
        changeOrigin: true
      }
    }
  },
  build: {
    // 构建产物直接输出到后端 console 模块 static 目录
    outDir: '../console/src/main/resources/static',
    emptyOutDir: true
  }
})
