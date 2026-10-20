// Run AFTER a consumer's `vite build`, as `node ../packages/crypto/scripts/assert-argon-worker-in-dist.mjs dist`:
// fail unless the build emitted the Argon2 worker (`src/argon.worker.ts`) as a chunk of its own and the app
// references it. Both `web/` and `admin-web/` call it from their `check`; it lives here, once, because the property
// belongs to the module that makes the `new Worker(new URL(…))` call (`src/argonWorker.ts`), not to either app.
//
// Why a build can lose the worker and still exit 0: Vite only recognises the worker when `new Worker(new URL('…',
// import.meta.url))` is spelled as ONE expression. Split it into `const url = new URL(…)` + `new Worker(url, …)`
// and the build stays green, emits no worker chunk, and inlines the raw TypeScript as a `data:video/mp2t` URL —
// which no browser can run as a worker and `worker-src 'self'` refuses anyway. The front door then falls back to
// the page thread on every derivation (one production-params Argon2id blocks it for ~0.8 s).
//
// It asserts three things, each named in its failure:
//  (i)   exactly one `assets/argon.worker-*.js` exists;
//  (ii)  it contains `unknown_op`, the dispatcher's sentinel — the chunk IS argon.worker.ts, not a file by that name;
//  (iii) some other `assets/*.js` references it by its emitted file name — the app can reach it.
//
// What it does NOT prove: that a browser evaluates the chunk (Vite emits an IIFE, loaded as a module worker), or
// that the deployed CSP admits it at run time. `argonWorker.ts`'s one-time console warning is what makes either
// failure visible in a running page.
//
// Bite (snapshot-and-restore, never `git checkout`): split the constructor in src/argonWorker.ts as above, run the
// consumer's `npm run build`, and this goes RED on (i); restore, and it goes green.
import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const distArg = process.argv[2]
if (!distArg) {
  console.error('usage: assert-argon-worker-in-dist.mjs <distDir>')
  process.exit(2)
}
const assets = join(resolve(distArg), 'assets')
// The sentinel the dispatcher throws for an op it does not know — present in the worker chunk and nowhere else.
const SENTINEL = 'unknown_op'
const WORKER_CHUNK = /^argon\.worker-[^/]+\.js$/

let names
try {
  names = readdirSync(assets)
} catch {
  console.error(`verify:argon-worker: ${assets} not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
const workers = names.filter((n) => WORKER_CHUNK.test(n))
if (workers.length !== 1) {
  failures.push(`(i) expected exactly one assets/argon.worker-*.js, found ${workers.length}: ${workers.join(', ') || 'none'}`)
} else {
  const [chunk] = workers
  if (!readFileSync(join(assets, chunk), 'utf8').includes(SENTINEL)) {
    failures.push(`(ii) ${chunk} does not contain "${SENTINEL}" — it is not the Argon2 dispatcher`)
  }
  const referrers = names.filter((n) => n !== chunk && n.endsWith('.js') && readFileSync(join(assets, n), 'utf8').includes(chunk))
  if (referrers.length === 0) failures.push(`(iii) no other assets/*.js references ${chunk} — the app cannot reach it`)
}

if (failures.length) {
  console.error('ARGON2 WORKER NOT IN THE BUILD — every derivation would block the page thread:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(`verify:argon-worker: ${workers[0]} emitted, carries the dispatcher, and is referenced by the app.`)
