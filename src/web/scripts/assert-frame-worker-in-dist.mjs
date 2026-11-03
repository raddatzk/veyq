// Run AFTER `vite build`: fail unless the build emitted core's frame worker (`src/core/blob/frames.worker.ts`, inbox
// #536) as a chunk of its own and the app references it. The twin of `assert-listing-worker-in-dist.mjs`, for the same
// reason: Vite recognises a Worker only when `new Worker(new URL('…', import.meta.url))` is spelled as ONE expression
// (`src/core/blob/frameWorker.ts`); split it and the build stays green, emits no Worker chunk, and every blob frame is
// sealed and opened on the page thread again — an upload's seal and a lightbox's open back as long tasks, silent but
// for one console warning.
//
// It asserts three things, each named in its failure:
//  (i)   exactly one `assets/frames.worker-*.js` exists;
//  (ii)  it contains `frame_worker_unknown_op`, the Worker's own refusal (`frames.worker.ts` UNKNOWN_OP) — the chunk IS
//        the frame worker, not a file by that name;
//  (iii) some other `assets/*.js` references it by its emitted file name — the app can reach it.
//
// What it does NOT prove: that a browser evaluates the chunk, or that the deployed CSP admits it at run time
// (`worker-src 'self'`, which `verify:csp` asserts the emitted policy carries).
//
// Bite (snapshot-and-restore, never `git checkout`): split the constructor in frameWorker.ts into
// `const url = new URL(…)` + `new Worker(url, …)`, run `pnpm run build`, and this goes RED on (i); restore, and it goes green.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = fileURLToPath(new URL('../dist/assets', import.meta.url))
const SENTINEL = 'frame_worker_unknown_op'
const WORKER_CHUNK = /^frames\.worker-[^/]+\.js$/

let names
try {
  names = readdirSync(ASSETS)
} catch {
  console.error(`verify:frame-worker: ${ASSETS} not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
const workers = names.filter((n) => WORKER_CHUNK.test(n))
if (workers.length !== 1) {
  failures.push(`(i) expected exactly one assets/frames.worker-*.js, found ${workers.length}: ${workers.join(', ') || 'none'}`)
} else {
  const [chunk] = workers
  if (!readFileSync(join(ASSETS, chunk), 'utf8').includes(SENTINEL)) {
    failures.push(`(ii) ${chunk} does not contain "${SENTINEL}" — it is not the frame worker`)
  }
  const referrers = names.filter((n) => n !== chunk && n.endsWith('.js') && readFileSync(join(ASSETS, n), 'utf8').includes(chunk))
  if (referrers.length === 0) failures.push(`(iii) no other assets/*.js references ${chunk} — the app cannot reach it`)
}

if (failures.length) {
  console.error('FRAME WORKER NOT IN THE BUILD — every blob frame would be sealed and opened on the page thread:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log(`verify:frame-worker: ${workers[0]} emitted, carries the Worker's sentinel, and is referenced by the app.`)
