// ADR 0069 — the policy leaves the build as a FILE, never as a <meta>. Run AFTER `vite build`: fail if dist/ has
// no emitted policy, or if dist/index.html carries a CSP <meta>. The backend's app shell refuses to boot on
// either (AppShell in src/backend/…/shared/appshell), so this check exists to move that failure from a self-hoster's
// boot to CI and the image build: a <meta> intersects with the header and blocks a store on its own origin again,
// and a missing file means a shell with no policy at all.
//
// Bite (snapshot-and-restore, never `git checkout`): re-add a `transformIndexHtml` that injects the <meta> in
// vite.config.ts's cspPlugin, `npm run build`, and this goes RED; restore, and it goes green.
import { readFileSync } from 'node:fs'
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
// Bite: drop 'self' from `worker-src` in src/shared/security/csp.ts, `npm run build`, and this goes RED.
else if (!/(^|;)\s*worker-src 'self'\s*(;|$)/.test(policy)) failures.push(`dist/${POLICY_FILE} does not carry exactly "worker-src 'self'"`)

if (failures.length) {
  console.error('CSP DELIVERY BROKEN — the backend would refuse to boot this dist, or the policy no longer reads as built:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(`verify:csp: dist/${POLICY_FILE} present with worker-src 'self', dist/index.html carries no CSP <meta>.`)
