import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 生产环境由 OpenResty 托管静态文件并把 /api 反代到后端，
// 因此 base 用相对路径，方便放在 /www/gmsr/ 子目录下。
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
      },
    },
  },
});
