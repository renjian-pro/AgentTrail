import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { proxy: { '/agent': 'http://localhost:8080' } },
  build: { outDir: '../src/main/resources/static', emptyOutDir: true },
  test: { environment: 'jsdom' }
})
