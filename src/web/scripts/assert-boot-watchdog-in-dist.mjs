// Run AFTER `vite build`: fail unless the start watchdog (ADR 0136 decision 2, `src/boot/watchdog.ts`) shipped as ONE
// file that imports nothing, and the built page loads it the way the decision needs.
//
// Why the build can break it and still exit 0: the watchdog answers a start whose chunks hang or never arrive, so it
// must not depend on any of them. One `import` in its source — a shared constant, a helper — and Rollup emits it as an
// `import … from './assets/…'` of a shared chunk: the watchdog then loads only when that chunk does, and a hang takes
// it down with the app. Nothing else would show it: the unit tests evaluate the source module, not the built file. And
// index.html names it `vite-ignore`d; were that attribute lost, Vite would fold it into the app's own entry, where it
// runs only after every chunk of the app arrived.
//
// It asserts four things, each named in its failure:
//  (i)   `dist/boot-watchdog.js` exists;
//  (ii)  it contains `veyq:boot-reload-at`, the watchdog's own storage key — the file IS the watchdog;
//  (iii) it has no `import` (a statement or an `import()`) and no `export … from`;
//  (iv)  `dist/index.html` loads it as an `async` module script from `/boot-watchdog.js`.
//
// What it does NOT prove: that a browser runs it or that the served CSP admits it (lane L1's start spec,
// `tests/e2e/tests/features/account/start-never-blank.spec.ts`).
//
// Bite (snapshot-and-restore, never `git checkout`): add `import { START_EVENT } from '../app/startSignals'` to
// watchdog.ts and use it, run `pnpm run build`, and this goes RED on (iii); restore, and it goes green.
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist/', import.meta.url))
const FILE = `${DIST}boot-watchdog.js`
const SENTINEL = 'veyq:boot-reload-at'

if (!existsSync(`${DIST}index.html`)) {
  console.error(`verify:boot-watchdog: ${DIST}index.html not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
if (!existsSync(FILE)) {
  failures.push('(i) dist/boot-watchdog.js was not emitted')
} else {
  const code = readFileSync(FILE, 'utf8')
  if (!code.includes(SENTINEL)) failures.push(`(ii) dist/boot-watchdog.js does not contain "${SENTINEL}" — it is not the watchdog`)
  const imports = code.match(/\bimport\b[^;]{0,80}/g) ?? []
  const reexports = code.match(/\bexport\b[^;]*\bfrom\b[^;]{0,80}/g) ?? []
  for (const found of [...imports, ...reexports]) failures.push(`(iii) dist/boot-watchdog.js imports: ${found}`)
}
const tag = readFileSync(`${DIST}index.html`, 'utf8').match(/<script\b[^>]*\bsrc="\/boot-watchdog\.js"[^>]*>/)?.[0]
if (!tag) failures.push('(iv) dist/index.html has no <script src="/boot-watchdog.js">')
else if (!/\btype="module"/.test(tag) || !/\basync\b/.test(tag)) failures.push(`(iv) dist/index.html loads the watchdog without type="module" async: ${tag}`)

if (failures.length) {
  console.error('START WATCHDOG BROKEN IN THE BUILD — a hung start would show a blank page again:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}

console.log('verify:boot-watchdog: dist/boot-watchdog.js is one file with no import, loaded async by index.html.')
