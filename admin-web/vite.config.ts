import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// The Server-Owner admin surface (ADR 0065). Deliberately much smaller than `web/`'s config: no map providers,
// no media Service Worker, no dev seed-credential endpoint — this app talks to exactly one origin (its own,
// which nginx reverse-proxies to the backend) and renders no user content.
//
// The dev proxy points at the backend directly, mirroring what nginx does in the container: the browser sees a
// single origin either way, which is the whole point of the reverse-proxy (ADR 0065 — the refresh cookie stays
// same-origin regardless of where the admin container is reached from).
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
const port = Number(process.env.VITE_ADMIN_PORT) || 5174

// Content-Security-Policy, injected as a <meta http-equiv> so it ships inside the static build with no server
// needed (self-hostable by default), and mirrored onto the dev server as a real header so dev == prod. Strictly
// tighter than web/'s: this app loads NO third-party origin at all — no tiles, no geocoder, no fonts — so
// `default-src 'self'` needs no escape hatches beyond the two the toolchain forces (inline styles from the
// PrimeVue theme, and `blob:` for the Emergency Kit PDF the browser hands to the operator to save).
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  // The pwned-password check (k-anonymity, 5-char prefix — no password or hash leaves the device) is the ONE
  // outbound call this surface makes. It is best-effort by construction: on a localhost-only or firewalled
  // admin container it simply fails and the warning does not appear.
  "connect-src 'self' https://api.pwnedpasswords.com",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ')

export default defineConfig(({ command }) => ({
  plugins: [
    vue(),
    {
      name: 'veyq-admin-csp',
      config: () => (command === 'serve' ? { server: { headers: { 'Content-Security-Policy': CSP } } } : {}),
      transformIndexHtml: (html: string) => ({
        html,
        tags: [
          {
            tag: 'meta',
            attrs: { 'http-equiv': 'Content-Security-Policy', content: CSP },
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
