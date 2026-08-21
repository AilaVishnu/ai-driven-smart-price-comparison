import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The backend runs on :8080. Proxying /api keeps the browser same-origin in dev,
// which sidesteps CORS entirely and lets the JWT ride along on normal fetches.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
