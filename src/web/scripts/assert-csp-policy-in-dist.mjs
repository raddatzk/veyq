// ADR 0069 — the policy leaves the build as a FILE, never as a <meta>. Run AFTER `vite build`: fail if dist/ has
// no emitted policy, or if dist/index.html carries a CSP <meta>. The backend's app shell refuses to boot on
// either (AppShell in src/backend/…/shared/appshell), so this check exists to move that failure from a self-hoster's
// boot to CI and the image build: a <meta> intersects with the header and blocks a store on its own origin again,
// and a missing file means a shell with no policy at all.
//
// Bite (snapshot-and-restore, never `git checkout`): re-add a `transformIndexHtml` that injects the <meta> in
// vite.config.ts's cspPlugin, `pnpm run build`, and this goes RED; restore, and it goes green.
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist/', import.meta.url))
// Keep in sync with CSP_POLICY_FILE in src/shared/security/csp.ts (and AppShell.POLICY_FILE in the backend).
const POLICY_FILE = 'content-security-policy.txt'
// The same shape the backend's fail-closed check refuses.
const CSP_META = /<meta[^>]*http-equiv\s*=\s*["']?content-security-policy/i

function read(name) {
  try {
    return readFileSync(DIST + name, 'utf8')
  } catch {
    return null
  }
}

const failures = []
const index = read('index.html')
if (index === null) failures.push('dist/index.html not found — run `vite build` first')
else if (CSP_META.test(index)) failures.push('dist/index.html carries a Content-Security-Policy <meta>')
const policy = read(POLICY_FILE)
if (policy === null || policy.trim() === '') failures.push(`dist/${POLICY_FILE} is missing or empty`)
// Every worker the app starts is a same-origin hashed module (Argon2, the media pixel stage, the gallery's listing
// opener, pdf.js): the policy must admit 'self' as a worker source and nothing wider (no blob:, no data:, no host).
// Bite: drop 'self' from `worker-src` in src/shared/security/csp.ts, `pnpm run build`, and this goes RED.
else if (!/(^|;)\s*worker-src 'self'\s*(;|$)/.test(policy)) failures.push(`dist/${POLICY_FILE} does not carry exactly "worker-src 'self'"`)

// Trusted Types, enforced (ADR 0117): the policy demands them and names exactly the two policies the client creates.
// Bite: drop `require-trusted-types-for` from csp.ts, or add a name to `trusted-types`, `pnpm run build` - RED.
if (policy !== null) {
  if (!/(^|;)\s*require-trusted-types-for 'script'\s*(;|$)/.test(policy)) failures.push(`dist/${POLICY_FILE} does not carry "require-trusted-types-for 'script'"`)
  if (!/(^|;)\s*trusted-types veyq default\s*(;|$)/.test(policy)) failures.push(`dist/${POLICY_FILE} does not carry exactly "trusted-types veyq default"`)
}
// The two ways a bundled Vue would put a string past that enforcement, read in the emitted JavaScript itself:
// - its pass-through `vue` policy, which vite.config.ts compiles out (bite: drop the plugin from `plugins`, build - RED);
// - stringified static content (`createStaticVNode`, whose vnode is the one that sets `staticCount`): Vue writes it with
//   `innerHTML` from a string, which the policy refuses. The compiler emits it for a template with a long static run;
//   none is in the build today, and one appearing must be read first (bite: add 25 static <p> to a component - RED).
const scripts = [read('sw.js') ?? '', ...readdirSync(DIST + 'assets').filter((f) => f.endsWith('.js')).map((f) => read('assets/' + f) ?? '')]
if (scripts.some((code) => /createPolicy\(\s*[`'"]vue[`'"]/.test(code))) failures.push("a bundled script still creates Vue's `vue` policy")
if (scripts.some((code) => /\.staticCount=/.test(code))) failures.push('a bundled script creates a static vnode (`createStaticVNode`): a template the compiler stringified')

if (failures.length) {
  console.error('CSP DELIVERY BROKEN — the backend would refuse to boot this dist, or the policy no longer reads as built:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(
  `verify:csp: dist/${POLICY_FILE} present with worker-src 'self' and Trusted Types enforced (veyq, default), no Vue pass-through ` +
    'policy or static vnode in the bundle, dist/index.html carries no CSP <meta>.',
)
