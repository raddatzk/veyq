import { randomBytes } from 'node:crypto'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Plugin } from 'vite'
// `vitest/config`'s defineConfig extends Vite's with the `test` block (setupFiles below) while staying a valid
// Vite config for `pnpm run dev`/`build`.
import { defaultExclude, defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { buildCsp, CSP_POLICY_FILE } from './src/shared/security/csp.ts'
import { DEV_BREACH_ORIGIN, DEV_GEOCODER_ORIGIN, DEV_RATES_ORIGIN, DEV_TILE_ORIGIN } from './src/shared/state/mapDefaults.ts'

// Vite dev-server config. `pnpm run dev` works with the defaults below; the env vars only matter for special
// setups (the IntelliJ run configs, the media live-smoke). In production the backend's app shell serves the built
// app (ADR 0069), so the dev proxy does not apply there — but the CSP (below) travels with the build, so it does.

// `/api` is proxied to the backend. Override the backend URL with VEYQ_API_TARGET.
const apiTarget = process.env.VEYQ_API_TARGET ?? 'http://127.0.0.1:8080'
// `/blobs/` (presigned media) is proxied to the object store — only used with the s3 store profile. It is the
// backend's default `veyq.blob-store.bucket`, spelled here because this file cannot read the setting.
// An address, not `localhost`: Node tries `::1` and 127.0.0.1 side by side and gives each a quarter second, and under an
// upload's load the connect timed out on both — the proxy answered 502, the frame PUT never reached the store and the
// upload stopped (a 700 MB video, 2026-11-01).
const storeTarget = process.env.VEYQ_STORE_TARGET ?? 'http://127.0.0.1:19000'
// Dev-server port. Default 8081, the origin the dev instance is browsed at (`veyq.localhost:8081`) and the one
// `%dev`'s `veyq.bundled-store.public-endpoint` signs presigned URLs for — change one and change the other, or
// uploads fail in the browser. `VITE_PORT` overrides it; the root launcher sets it from `veyq.webPort` together
// with the backend's endpoint (launcher/backend/build.gradle.kts).
const port = Number(process.env.VITE_PORT) || 8081
// Force one fixed tenant Host regardless of the browser URL (used by the media live-smoke). Usually unset.
const smokeHost = process.env.VEYQ_SMOKE_HOST

// Content-Security-Policy (cr-0133, ADR 0069). Always a response header, never a <meta>: the dev server sends it
// directly, and the build emits it as `dist/content-security-policy.txt`, which the backend's app shell reads at
// boot, extends with the store origin (only known at runtime) and sends on every shell response. A <meta> would
// intersect with that header and block the store on its own origin again, so the build injects none — and the
// backend refuses to boot a shell whose index.html carries one, or whose policy file is missing (the file name is
// `AppShell.POLICY_FILE` there; `pnpm run verify:csp` checks both properties of dist before an image is built).
// The built policy names no outside service: the map tiles, the geocoder, the breached-password check and the exchange
// rates are the Server Owner's settings, and the backend appends the origins of those in use per response (ADR 0088
// decision 7). The dev server serves the app itself, so its header allows the reference providers a new instance is
// seeded with; a dev instance pointed at other services edits the four DEV_* origins (mapDefaults.ts).
// The dev server injects every stylesheet as a <style> element, which `style-src` without 'unsafe-inline' refuses, so in
// dev only Vite stamps them with a nonce (`html.cspNonce`) drawn fresh per start and the header names it (inbox #523).
// The build loads its CSS as files and carries no nonce.
function cspPlugin(dev: boolean): Plugin {
  const devStyleNonce = dev ? randomBytes(18).toString('base64') : undefined
  const csp = dev
    ? buildCsp({ tileOrigin: DEV_TILE_ORIGIN, geocoderOrigin: DEV_GEOCODER_ORIGIN, breachOrigin: DEV_BREACH_ORIGIN, ratesOrigin: DEV_RATES_ORIGIN, dev, devStyleNonce })
    : buildCsp({ tileOrigin: null, geocoderOrigin: null, breachOrigin: null, ratesOrigin: null })
  return {
    name: 'veyq-csp',
    config: () =>
      dev ? { html: { cspNonce: devStyleNonce }, server: { headers: { 'Content-Security-Policy': csp } } } : {},
    generateBundle() {
      if (dev) return
      this.emitFile({ type: 'asset', fileName: CSP_POLICY_FILE, source: `${csp}\n` })
    },
  }
}

// Vue's runtime creates a Trusted Types policy named `vue` that passes every string through, and writes `v-html` and an
// `innerHTML` binding through it (runtime-dom's `unsafeToTrustedHTML`): were it allowed, a plain string in a `v-html`
// would pass the browser's enforcement unseen. The built policy does not name it (csp.ts, ADR 0117), and this plugin
// compiles its creation out, so Vue neither makes it nor reports a refused one on every page load; without a policy Vue
// hands the sink the value as it is, which the browser then refuses unless it is a TrustedHTML (`v-safe-html` makes
// those). The line must be found exactly once in exactly one copy of Vue, or the build fails: a Vue release that moved
// it is read again before it ships. `verify:csp` checks that no `vue` policy is left in dist.
const VUE_TRUSTED_TYPES_LINE = 'const tt = typeof window !== "undefined" && window.trustedTypes;'
const VUE_RUNTIME_DOM = /\/@vue\/runtime-dom\/dist\/runtime-dom\.esm-bundler\.js$/
function vueWithoutPassThroughPolicyPlugin(): Plugin {
  let patched = 0
  return {
    name: 'veyq-vue-without-pass-through-policy',
    apply: 'build',
    buildStart() {
      patched = 0
    },
    transform(code, id) {
      if (!VUE_RUNTIME_DOM.test(id)) return
      const found = code.split(VUE_TRUSTED_TYPES_LINE).length - 1
      if (found !== 1) return this.error(`${id}: expected Vue's Trusted Types line once, found it ${found} times - read the new Vue's policy before building`)
      patched++
      return { code: code.replace(VUE_TRUSTED_TYPES_LINE, 'const tt = false; // veyq: no pass-through policy (vite.config.ts)'), map: null }
    },
    buildEnd(error) {
      if (!error && patched !== 1) this.error(`Vue's runtime-dom was patched ${patched} times, expected once (vueWithoutPassThroughPolicyPlugin)`)
    },
  }
}

// Vite's `build.license` file names each bundled package, its version and its licence text, but not where its source is.
// MPL-2.0 §3.2(a) asks exactly that of a copy in executable form - ExifReader is MPL-2.0 and ships minified - so this
// plugin adds, under every package's heading, its published source at that version on npm (the tarball that was bundled,
// file for file) and its repository when it names one. Every package rather than only MPL: the line costs nothing, and a
// later file-level copyleft dependency is covered without anyone remembering this. `verify:license-sources` checks dist.
const LICENSE_FILE = 'third-party-licenses.txt'
function packageOf(moduleId: string, cache: Map<string, { name: string; version: string; repository?: unknown } | null>) {
  for (let dir = dirname(moduleId); dir.includes('/node_modules/'); dir = dirname(dir)) {
    if (cache.has(dir)) return cache.get(dir) ?? undefined
    const file = join(dir, 'package.json')
    const pkg = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : undefined
    // A nested package.json without a name (`{"type":"module"}` in a dist folder) is not the package; Vite skips it too.
    if (pkg?.name && pkg.version) {
      cache.set(dir, pkg)
      return pkg
    }
  }
  return undefined
}
function repositoryUrl(repository: unknown): string | undefined {
  const raw = typeof repository === 'string' ? repository : (repository as { url?: unknown } | undefined)?.url
  if (typeof raw !== 'string' || !raw) return undefined
  if (/^[\w.-]+\/[\w.-]+$/.test(raw) || raw.startsWith('github:')) return `https://github.com/${raw.replace(/^github:/, '')}`
  const url = raw
    .replace(/^git\+/, '')
    .replace(/^git:\/\//, 'https://')
    .replace(/^git@([^:]+):/, 'https://$1/')
    .replace(/\.git$/, '')
  return /^https:\/\//.test(url) ? url : undefined
}
// The WebP encoder (ADR 0127) is libwebp built by us, not an npm package, so Vite's licence file does not list it. This
// plugin adds it under the same heading shape, with libwebp's licence and patent grant as the build copied them from the
// tag, and the tag's source on libwebp's repository as its `Source:` (`verify:license-sources` holds that form).
const WEBP_ENCODER_DIR = fileURLToPath(new URL('./webp-encoder/', import.meta.url))
function libwebpNotice(): string {
  const pin = readFileSync(join(WEBP_ENCODER_DIR, 'libwebp.pin'), 'utf8')
  const tag = /^tag (\S+)$/m.exec(pin)?.[1]
  if (!tag) throw new Error('webp-encoder/libwebp.pin names no tag')
  const read = (name: string) => readFileSync(join(WEBP_ENCODER_DIR, name), 'utf8').trim()
  return [
    `## libwebp - ${tag} (BSD-3-Clause)`,
    `Source: https://github.com/webmproject/libwebp/tree/${tag}\nRepository: https://chromium.googlesource.com/webm/libwebp`,
    read('COPYING'),
    read('PATENTS'),
  ].join('\n\n')
}
function licenseSourcesPlugin(): Plugin {
  return {
    name: 'veyq-license-sources',
    apply: 'build',
    // `order: 'post'` runs after every plain `generateBundle`, Vite's licence plugin among them, which emits the file.
    generateBundle: {
      order: 'post',
      handler(_, bundle) {
        const asset = bundle[LICENSE_FILE]
        if (asset?.type !== 'asset' || typeof asset.source !== 'string') return this.error(`${LICENSE_FILE} was not emitted before this plugin ran`)
        const cache = new Map()
        const repositories = new Map<string, string | undefined>()
        let encoderBundled = false
        for (const chunk of Object.values(bundle)) {
          if (chunk.type !== 'chunk') continue
          for (const id of chunk.moduleIds) {
            if (id.startsWith(WEBP_ENCODER_DIR)) encoderBundled = true
            const pkg = packageOf(id, cache)
            if (pkg) repositories.set(`${pkg.name} - ${pkg.version}`, repositoryUrl(pkg.repository))
          }
        }
        asset.source = asset.source.replace(/^## (\S+) - (\S+)(.*)$/gm, (heading, name: string, version: string) => {
          const repository = repositories.get(`${name} - ${version}`)
          const lines = [`Source: https://www.npmjs.com/package/${name}/v/${version}`, ...(repository ? [`Repository: ${repository}`] : [])]
          return `${heading}\n\n${lines.join('\n')}`
        })
        if (encoderBundled) asset.source = `${asset.source.trimEnd()}\n\n${libwebpNotice()}\n`
      },
    },
  }
}

// The runtime data pdf.js reads while it draws (inbox #412): the predefined CMaps (CJK and other non-embedded encodings),
// the standard 14 fonts' substitutes and the CMYK ICC profile. pdf.js fetches them by URL from a DIRECTORY, so they cannot
// be hashed single imports; this plugin copies the three directories verbatim to `assets/pdfjs-<version>/` (under
// `/assets/`, so the backend serves them immutable - the version is in the path, a new release is a new path) and serves
// the same path from node_modules in dev. They are same-origin, so `connect-src 'self'` carries the fetches and the policy
// gains nothing. The WASM decoders (`wasm/`) are deliberately NOT copied: the policy would compile them since ADR 0127,
// but they are not turned on (inbox #837), and `verify:pdf-worker` refuses any `.wasm` but the WebP encoder's.
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

// ADR 0047: serve the media Service Worker at a stable root `/sw.js` in DEV
// too, so dev == prod (register.ts always registers `/sw.js`). Vite transforms the module on the fly; the
// `Service-Worker-Allowed: /` header lets a script served from `/sw.js` claim the whole origin (needed to
// intercept `/__media/*`). Best-effort dev convenience — the SW path always has the full-blob fallback if a dev
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
      // Resolved here, outside the handler's catch: a root that is not found must fail the dev server, not answer 404.
      const envFile = join(repoRoot(), '.run', '.env')
      server.middlewares.use((req, res, next) => {
        if ((req.url ?? '').split('?')[0] !== '/__dev/seed-credentials') return next()
        try {
          // `.run/.env` lives at the repo root, written by `pnpm run bootstrap:user`.
          const raw = readFileSync(envFile, 'utf8')
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

// The repository root: the nearest directory above this file that holds `VERSION`. Walked up to rather than counted in
// `../`, because how deep the client sits in the checkout is the layout's to decide (ADR 0106), and a count gone stale
// in the dev-credentials endpoint would be swallowed by its catch and answer 404 in silence. Not found, it throws.
function repoRoot(): string {
  const start = dirname(fileURLToPath(import.meta.url))
  let dir = start
  while (!existsSync(join(dir, 'VERSION'))) {
    const parent = dirname(dir)
    if (parent === dir) throw new Error(`no VERSION in ${start} or above it: not inside a veyq checkout`)
    dir = parent
  }
  return dir
}

// `VERSION` holds the major alone (ADR 0057 §1); anything else fails the build rather than compile a client against NaN.
function platformMajor(): number {
  const text = readFileSync(join(repoRoot(), 'VERSION'), 'utf8').trim()
  if (!/^[0-9]+$/.test(text)) throw new Error(`VERSION must hold the major alone, got: ${text}`)
  return Number(text)
}

export default defineConfig(({ command }) => {
  return {
    plugins: [vue(), vueWithoutPassThroughPolicyPlugin(), cspPlugin(command === 'serve'), pdfjsAssetsPlugin(), mediaSwDevPlugin(), devSeedCredentialsPlugin(), licenseSourcesPlugin()],
    define: {
      __PDFJS_VERSION__: JSON.stringify(pdfjsPackage().version),
      // The platform's one major (ADR 0085 decision 1), read from the repository's VERSION as the backend's build reads
      // it, so the web gate and the server built from the same tree cannot disagree. The Dockerfile copies VERSION in.
      __VEYQ_MAJOR__: JSON.stringify(platformMajor()),
    },
    test: {
      // The catalog, the application registry, auto-unmount and the instance's services for every test — see src/test/setup.ts.
      setupFiles: ['./src/test/setup.ts'],
      // The setup hook imports the registry and, for some suites, heavy modules (pdf.js, the sessions panel's whole
      // account tree). On a loaded machine (load 20-40, measured 2026-10-19) that import alone passed vitest's default
      // 10 s and failed `check` with "Hook timed out" while every test in the file was green when run alone. A hook
      // timeout is not an assertion; 30 s keeps a real hang red without failing on load.
      hookTimeout: 30_000,
      // `.stryker-tmp/` is out of the collection: an `inPlace` Stryker run (ADR 0045) keeps its backup of every file it
      // rewrote there, test files included, and vitest's default exclude does not cover it — so the backup's copies ran
      // too, and media.test.ts's copy failed the dry run on a fixture the backup does not hold (Stryker 10, measured
      // 2026-11-07: 1,753 files in that backup).
      exclude: [...defaultExclude, '**/.stryker-tmp/**'],
    },
    build: {
      // The minifier drops the bundled dependencies' license comments, so their MIT/Apache/BSD notices would never
      // reach the browser that receives their code. Vite writes them out instead, at a stable, unhashed path.
      license: { fileName: LICENSE_FILE },
      rollupOptions: {
        // Two entries: the app (from index.html) and the media Service Worker (ADR 0047).
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
      // No CORS of Vite's own: its default answers a `*.localhost` origin's preflight itself and echoes the origin on a
      // proxied answer, which made a dev embed of another tenant's share work where the backend refuses it (ADR 0121
      // decision 3, `ShareCrossOrigin`: a switched-off `core.public-shares.embeddable` showed nothing in dev). The backend
      // answers cross-origin itself, exactly where it should.
      cors: false,
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
