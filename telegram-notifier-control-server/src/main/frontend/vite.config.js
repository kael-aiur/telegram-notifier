import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [vue()],
  build: {
    emptyOutDir: true,
    outDir: '../resources/static',
  },
  test: {
    environment: 'jsdom',
  },
})
