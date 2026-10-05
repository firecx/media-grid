import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Запросы /api при разработке уходят в систему, запущенную в Docker (nginx на порту 8080):
// один адрес для браузера, поэтому куки входа работает так же, как в боевой сборке.
const backend = process.env.MEDIAGRID_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  build: {
    target: 'es2023',
    sourcemap: false,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
