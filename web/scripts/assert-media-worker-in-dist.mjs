// Run AFTER `vite build`: fail unless the build emitted the media Worker (`src/shared/image/mediaDerive.worker.ts`)
// as a chunk of its own and the app references it. The twin of `../packages/crypto/scripts/assert-argon-worker-in-dist.mjs`,
// and kept a twin rather than a shared helper on purpose: that one belongs to the crypto package, whose workspace
// exists for crypto alone (root CLAUDE.md), and this Worker is web's.
//
// Why a build can lose the worker and still exit 0: Vite only recognises a worker when `new Worker(new URL('…',
// import.meta.url))` is spelled as ONE expression (`mediaDeriveWorker.ts`). Split it into `const url = new URL(…)` +
// `new Worker(url, …)` and the build stays green, emits no worker chunk, and inlines the raw TypeScript as a `data:`
// URL — which no browser runs as a worker and `worker-src 'self'` refuses anyway. Every image derivation then falls
// back to the page thread, where a backgrounded tab waits on each encode, and the upload looks merely slow.
//
// It asserts three things, each named in its failure:
//  (i)   exactly one `assets/mediaDerive.worker-*.js` exists;
//  (ii)  it contains `create_image_bitmap_unavailable`, the Worker's own infra sentinel — the chunk IS
//        mediaDerive.worker.ts, not a file by that name;
//  (iii) some other `assets/*.js` references it by its emitted file name — the app can reach it.
//
// What it does NOT prove: that a browser evaluates the chunk, or that the deployed CSP admits it at run time.
// `mediaDeriveWorker.ts`'s one-time console warning is what makes either failure visible in a running page.
//
// Bite (snapshot-and-restore, never `git checkout`): split the constructor in mediaDeriveWorker.ts as above, run
// `npm run build`, and this goes RED on (i); restore, and it goes green.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = fileURLToPath(new URL('../dist/assets', import.meta.url))
// Returned by the Worker's feature check and spelled nowhere else in src/ (tests aside).
const SENTINEL = 'create_image_bitmap_unavailable'
const WORKER_CHUNK = /^mediaDerive\.worker-[^/]+\.js$/

let names
try {
  names = readdirSync(ASSETS)
} catch {
  console.error(`verify:media-worker: ${ASSETS} not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
const workers = names.filter((n) => WORKER_CHUNK.test(n))
if (workers.length !== 1) {
  failures.push(`(i) expected exactly one assets/mediaDerive.worker-*.js, found ${workers.length}: ${workers.join(', ') || 'none'}`)
} else {
  const [chunk] = workers
  if (!readFileSync(join(ASSETS, chunk), 'utf8').includes(SENTINEL)) {
    failures.push(`(ii) ${chunk} does not contain "${SENTINEL}" — it is not the media Worker`)
  }
  const referrers = names.filter((n) => n !== chunk && n.endsWith('.js') && readFileSync(join(ASSETS, n), 'utf8').includes(chunk))
  if (referrers.length === 0) failures.push(`(iii) no other assets/*.js references ${chunk} — the app cannot reach it`)
}

if (failures.length) {
  console.error('MEDIA WORKER NOT IN THE BUILD — every image derivation would run on the page thread:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(`verify:media-worker: ${workers[0]} emitted, carries the Worker's sentinel, and is referenced by the app.`)
