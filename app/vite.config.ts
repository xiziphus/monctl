import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  // Dev server proxies the API to the Python backend so `npm run dev` works
  // against real hardware; the production build is served by that same server.
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8765' },
  },
})
