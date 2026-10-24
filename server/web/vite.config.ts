import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

export default defineConfig({
  plugins: [preact()],
  server: {
    proxy: { '/api': { target: 'http://localhost:8080', ws: true, changeOrigin: false } },
  },
  build: { target: 'es2022' },
})
