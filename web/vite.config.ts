import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Запросы /api при разработке уходят в систему, запущенную в Docker (nginx, по умолчанию HTTPS):
// один адрес для браузера, поэтому куки входа работает так же, как в боевой сборке.
// При MEDIAGRID_TLS=off в системе — MEDIAGRID_URL=http://localhost.
const backend = process.env.MEDIAGRID_URL ?? 'https://localhost';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // secure: false — у локальной системы сертификат самоподписанный
    proxy: { '/api': { target: backend, changeOrigin: false, secure: false } },
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
