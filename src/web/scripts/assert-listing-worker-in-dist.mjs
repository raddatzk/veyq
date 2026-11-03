// Run AFTER `vite build`: fail unless the build emitted the listing opener Worker
// (`src/features/memories/listingOpener.worker.ts`, ADR 0105 decision 3) as a chunk of its own and the app references it.
// The twin of `assert-media-worker-in-dist.mjs`, for the same reason: Vite recognises a Worker only when
// `new Worker(new URL('…', import.meta.url))` is spelled as ONE expression (`listingOpener.ts`); split it and the build
// stays green, emits no Worker chunk, and every gallery opens its listing on the page thread again — slower, and silent
// but for one console warning.
//
// It asserts three things, each named in its failure:
//  (i)   exactly one `assets/listingOpener.worker-*.js` exists;
//  (ii)  it contains `memory_not_open_in_listing_opener`, the Worker's own refusal (`listingOpenerCore.ts`
//        MEMORY_NOT_OPEN) — the chunk IS the listing opener, not a file by that name;
//  (iii) some other `assets/*.js` references it by its emitted file name — the app can reach it.
//
// What it does NOT prove: that a browser evaluates the chunk, or that the deployed CSP admits it at run time
// (`worker-src 'self'`, which `verify:csp` asserts the emitted policy carries).
//
// Bite (snapshot-and-restore, never `git checkout`): split the constructor in listingOpener.ts into
// `const url = new URL(…)` + `new Worker(url, …)`, run `pnpm run build`, and this goes RED on (i); restore, and it goes green.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = fileURLToPath(new URL('../dist/assets', import.meta.url))
const SENTINEL = 'memory_not_open_in_listing_opener'
const WORKER_CHUNK = /^listingOpener\.worker-[^/]+\.js$/

let names
try {
  names = readdirSync(ASSETS)
} catch {
  console.error(`verify:listing-worker: ${ASSETS} not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
const workers = names.filter((n) => WORKER_CHUNK.test(n))
if (workers.length !== 1) {
  failures.push(`(i) expected exactly one assets/listingOpener.worker-*.js, found ${workers.length}: ${workers.join(', ') || 'none'}`)
} else {
  const [chunk] = workers
  if (!readFileSync(join(ASSETS, chunk), 'utf8').includes(SENTINEL)) {
    failures.push(`(ii) ${chunk} does not contain "${SENTINEL}" — it is not the listing opener Worker`)
  }
  const referrers = names.filter((n) => n !== chunk && n.endsWith('.js') && readFileSync(join(ASSETS, n), 'utf8').includes(chunk))
  if (referrers.length === 0) failures.push(`(iii) no other assets/*.js references ${chunk} — the app cannot reach it`)
}

if (failures.length) {
  console.error('LISTING OPENER WORKER NOT IN THE BUILD — every gallery would open its listing on the page thread:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(`verify:listing-worker: ${workers[0]} emitted, carries the Worker's sentinel, and is referenced by the app.`)
