import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { ADMIN_CSP } from './src/shared/security/csp'

// The Server-Owner admin surface (ADR 0065). Deliberately much smaller than `src/web/`'s config: no map providers,
// no media Service Worker, no dev seed-credential endpoint — this app talks to exactly one origin (its own,
// which nginx reverse-proxies to the backend) and renders no user content.
//
// The dev proxy points at the backend's admin listener directly, mirroring what nginx does in the container: the
// browser sees a single origin either way, which is the whole point of the reverse-proxy (ADR 0065 — the refresh
// cookie stays same-origin regardless of where the admin container is reached from). Never the public port 8080:
// it 404s every Server-Owner operation.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8090'
const port = Number(process.env.VITE_ADMIN_PORT) || 5174

// The CSP lives in src/shared/security/csp.ts, where it is tested; this only delivers it (meta tag + dev header).

export default defineConfig(({ command }) => ({
  plugins: [
    vue(),
    {
      name: 'veyq-admin-csp',
      config: () => (command === 'serve' ? { server: { headers: { 'Content-Security-Policy': ADMIN_CSP } } } : {}),
      transformIndexHtml: (html: string) => ({
        html,
        tags: [
          {
            tag: 'meta',
            attrs: { 'http-equiv': 'Content-Security-Policy', content: ADMIN_CSP },
            injectTo: 'head-prepend' as const,
          },
        ],
      }),
    },
  ],
  test: {
    // Provide the PrimeVue toast service to every mounted component — see src/test/setup.ts.
    setupFiles: ['./src/test/setup.ts'],
  },
  build: {
    // The minifier drops the bundled dependencies' license comments; Vite writes their notices out instead (as src/web/ does).
    license: { fileName: 'third-party-licenses.txt' },
    rollupOptions: {
      input: { main: fileURLToPath(new URL('./index.html', import.meta.url)) },
    },
  },
  server: {
    host: '127.0.0.1',
    port,
    proxy: {
      // The Host is forwarded unchanged (`changeOrigin: false`): the backend resolves the tenant from it, and
      // the Server-Owner endpoints are tenant-independent, so either way the header must not be rewritten.
      '/api': { target: apiTarget, changeOrigin: false },
    },
  },
}))
