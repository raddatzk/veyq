import { readdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadEnv, type Plugin } from 'vite'
// `vitest/config`'s defineConfig extends Vite's with the `test` block (setupFiles below) while staying a valid
// Vite config for `npm run dev`/`build`.
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { buildCsp, CSP_POLICY_FILE, providerOrigin } from './src/shared/security/csp'
import { DEFAULT_GEOCODER_URL, DEFAULT_TILE_URL } from './src/shared/state/mapDefaults'

// Vite dev-server config. `npm run dev` works with the defaults below; the env vars only matter for special
// setups (the IntelliJ run configs, the media live-smoke). In production the backend's app shell serves the built
// app (ADR 0069), so the dev proxy does not apply there — but the CSP (below) travels with the build, so it does.

// `/api` is proxied to the backend. Override the backend URL with VEYQ_API_TARGET.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// `/blobs/` (presigned media) is proxied to the object store — only used with the s3 store profile. It is the
// backend's default `veyq.blob-store.bucket`, spelled here because this file cannot read the setting.
const storeTarget = process.env.VEYQ_STORE_TARGET ?? 'http://localhost:19000'
// Dev-server port (the IntelliJ run config sets VITE_PORT). Default 5173.
const port = Number(process.env.VITE_PORT) || 5173
// Force one fixed tenant Host regardless of the browser URL (used by the media live-smoke). Usually unset.
const smokeHost = process.env.VEYQ_SMOKE_HOST

// Content-Security-Policy (cr-0133, ADR 0069). Always a response header, never a <meta>: the dev server sends it
// directly, and the build emits it as `dist/content-security-policy.txt`, which the backend's app shell reads at
// boot, extends with the store origin (only known at runtime) and sends on every shell response. A <meta> would
// intersect with that header and block the store on its own origin again, so the build injects none — and the
// backend refuses to boot a shell whose index.html carries one, or whose policy file is missing (the file name is
// `AppShell.POLICY_FILE` there; `npm run verify:csp` checks both properties of dist before an image is built).
// The allowed map-provider origins are derived from the SAME config the app fetches from (VITE_VEYQ_MAP_* or the
// mapDefaults fallback), so a self-hosted tile/geocoder deployment stays covered automatically.
function cspPlugin(env: Record<string, string>, dev: boolean): Plugin {
  const tileOrigin = providerOrigin(env.VITE_VEYQ_MAP_TILE_URL || DEFAULT_TILE_URL)
  const geocoderOrigin = providerOrigin(env.VITE_VEYQ_MAP_GEOCODER_URL || DEFAULT_GEOCODER_URL)
  const csp = buildCsp({ tileOrigin, geocoderOrigin, dev })
  return {
    name: 'veyq-csp',
    config: () => (dev ? { server: { headers: { 'Content-Security-Policy': csp } } } : {}),
    generateBundle() {
      if (dev) return
      this.emitFile({ type: 'asset', fileName: CSP_POLICY_FILE, source: `${csp}\n` })
    },
  }
}

// The runtime data pdf.js reads while it draws (inbox #412): the predefined CMaps (CJK and other non-embedded encodings),
// the standard 14 fonts' substitutes and the CMYK ICC profile. pdf.js fetches them by URL from a DIRECTORY, so they cannot
// be hashed single imports; this plugin copies the three directories verbatim to `assets/pdfjs-<version>/` (under
// `/assets/`, so the backend serves them immutable - the version is in the path, a new release is a new path) and serves
// the same path from node_modules in dev. They are same-origin, so `connect-src 'self'` carries the fetches and the policy
// gains nothing. The WASM decoders (`wasm/`) are deliberately NOT copied: they need 'wasm-unsafe-eval' (csp.ts).
const PDFJS_DATA_DIRS = ['cmaps', 'standard_fonts', 'iccs'] as const
function pdfjsPackage(): { root: string; version: string } {
  const pkg = createRequire(import.meta.url).resolve('pdfjs-dist/package.json')
  return { root: dirname(pkg), version: JSON.parse(readFileSync(pkg, 'utf8')).version as string }
}
function pdfjsAssetsPlugin(): Plugin {
  const { root, version } = pdfjsPackage()
  const prefix = `/assets/pdfjs-${version}/`
  return {
    name: 'veyq-pdfjs-assets',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = (req.url ?? '').split('?')[0]
        const rel = url.startsWith(prefix) ? url.slice(prefix.length) : ''
        const [dir, name, ...rest] = rel.split('/')
        if (!(PDFJS_DATA_DIRS as readonly string[]).includes(dir) || !name || rest.length || name.includes('..')) return next()
        try {
          res.setHeader('Content-Type', 'application/octet-stream')
          res.end(readFileSync(join(root, dir, name)))
        } catch {
          res.statusCode = 404
          res.end()
        }
      })
    },
    generateBundle() {
      for (const dir of PDFJS_DATA_DIRS) {
        for (const name of readdirSync(join(root, dir))) {
          this.emitFile({ type: 'asset', fileName: `assets/pdfjs-${version}/${dir}/${name}`, source: readFileSync(join(root, dir, name)) })
        }
      }
    },
  }
}

// cr-inline-video-playback Slice 2 (ADR 0047): serve the media Service Worker at a stable root `/sw.js` in DEV
// too, so dev == prod (register.ts always registers `/sw.js`). Vite transforms the module on the fly; the
// `Service-Worker-Allowed: /` header lets a script served from `/sw.js` claim the whole origin (needed to
// intercept `/__media/*`). Best-effort dev convenience — the SW path always has the Slice-1 fallback if a dev
// browser can't register it. NOTE: not yet live-verified end-to-end (rides #102 like the rest of the SW slice).
function mediaSwDevPlugin(): Plugin {
  return {
    name: 'veyq-media-sw-dev',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if ((req.url ?? '').split('?')[0] !== '/sw.js') return next()
        server
          .transformRequest('/src/sw/mediaSw.ts')
          .then((result) => {
            if (!result) return next()
            res.setHeader('Content-Type', 'application/javascript')
            res.setHeader('Service-Worker-Allowed', '/')
            res.setHeader('Cache-Control', 'no-cache')
            res.end(result.code)
          })
          .catch(next)
      })
    },
  }
}

// cr-dev-auto-unlock — a DEV-SERVER-ONLY endpoint serving the local dev-seed credentials to the browser, so an
// operator who cannot type the password (a phone, or an agent barred from entering credentials into a field) can
// reach the unlocked dev app hands-off (consumed by src/app/devAutoUnlock.ts behind an `import.meta.env.DEV` gate).
// SECURITY: `apply: 'serve'` + `configureServer` ⇒ this is dev-server code and is **never** part of `vite build`,
// so the endpoint and the creds cannot exist in a prod build. The creds are read from `.run/.env`, which is already
// gitignored plaintext on disk; the dev server binds 127.0.0.1 only. The guard `scripts/assert-no-dev-backdoor-in-dist.mjs`
// fails the build if `__dev/seed-credentials` ever appears in dist/.
function devSeedCredentialsPlugin(): Plugin {
  return {
    name: 'veyq-dev-seed-credentials',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if ((req.url ?? '').split('?')[0] !== '/__dev/seed-credentials') return next()
        try {
          // `.run/.env` lives at the repo root (one level up from web/), written by `npm run bootstrap:user`.
          const raw = readFileSync(fileURLToPath(new URL('../.run/.env', import.meta.url)), 'utf8')
          const val = (key: string) => {
            const line = raw.split('\n').find((l) => l.startsWith(key + '='))
            return line ? line.slice(key.length + 1).trim() : ''
          }
          const email = val('VEYQ_EMAIL')
          const password = val('VEYQ_PASSWORD')
          const secretKey = val('VEYQ_SECRET_KEY')
          if (!email || !password || !secretKey) {
            res.statusCode = 404
            res.end('{}') // never seeded on this machine — the dev-unlock stays a no-op
            return
          }
          res.setHeader('Content-Type', 'application/json')
          res.setHeader('Cache-Control', 'no-store')
          res.end(JSON.stringify({ email, password, secretKey }))
        } catch {
          res.statusCode = 404
          res.end('{}')
        }
      })
    },
  }
}

// `VERSION` holds the major alone (ADR 0057 §1); anything else fails the build rather than compile a client against NaN.
function platformMajor(): number {
  const text = readFileSync(fileURLToPath(new URL('../VERSION', import.meta.url)), 'utf8').trim()
  if (!/^[0-9]+$/.test(text)) throw new Error(`VERSION must hold the major alone, got: ${text}`)
  return Number(text)
}

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(), cspPlugin(env, command === 'serve'), pdfjsAssetsPlugin(), mediaSwDevPlugin(), devSeedCredentialsPlugin()],
    define: {
      __PDFJS_VERSION__: JSON.stringify(pdfjsPackage().version),
      // The platform's one major (ADR 0085 decision 1), read from the repository's VERSION as the backend's build reads
      // it, so the web gate and the server built from the same tree cannot disagree. The Dockerfile copies VERSION in.
      __VEYQ_MAJOR__: JSON.stringify(platformMajor()),
    },
    test: {
      // Provide the PrimeVue toast service to every mounted component (cr-0157) — see src/test/setup.ts.
      setupFiles: ['./src/test/setup.ts'],
    },
    build: {
      rollupOptions: {
        // Two entries: the app (from index.html) and the media Service Worker (cr-inline-video-playback Slice 2).
        // The SW must land at a stable, UNHASHED `/sw.js` so a redeploy is detectable (`updateViaCache:'none'`);
        // the app keeps its hashed assets. `chunk.name === 'media-sw'` → `sw.js` at the dist root; all else keeps
        // the default hashed `assets/` layout.
        input: {
          main: fileURLToPath(new URL('./index.html', import.meta.url)),
          'media-sw': fileURLToPath(new URL('./src/sw/mediaSw.ts', import.meta.url)),
        },
        output: {
          entryFileNames: (chunk) => (chunk.name === 'media-sw' ? 'sw.js' : 'assets/[name]-[hash].js'),
          // pdf.js' worker is an ASSET (`?url`, started by pdf.js itself as a module worker), emitted with a `.js` name so the
          // backend's static handler serves it with a script MIME type under `nosniff` - an `.mjs` is not in every MIME table.
          assetFileNames: (asset) =>
            (asset.names ?? []).some((n) => /^pdf\.worker/.test(n)) ? 'assets/[name]-[hash].js' : 'assets/[name]-[hash][extname]',
        },
      },
    },
    server: {
      host: '127.0.0.1',
      port,
      // Allow browsing the dev server under `*.localhost` — one subdomain per local tenant. Add other suffixes here.
      allowedHosts: ['.localhost'],
      proxy: {
        // The backend picks the tenant from the request Host. `changeOrigin: false` forwards the REAL browser Host,
        // so browsing `workspace-a.localhost` resolves tenant a, `workspace-b.localhost` tenant b. The port is
        // stripped server-side, so the hostname is the identity. (VEYQ_SMOKE_HOST overrides this with a fixed host.)
        '/api': {
          target: apiTarget,
          changeOrigin: false,
          configure: smokeHost
            ? (proxy: any) => proxy.on('proxyReq', (req: any) => req.setHeader('host', smokeHost))
            : undefined,
        },
        // Presigned media → object store, Host left untouched (SigV4 signs the Host).
        '/blobs/': { target: storeTarget, changeOrigin: false },
      },
    },
  }
})
