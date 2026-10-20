// Run AFTER `vite build`: fail unless the PDF viewer's pdf.js is a LAZY chunk with a same-origin worker, its runtime data is
// emitted, and nothing that would widen the page's policy shipped. The twin of `assert-media-worker-in-dist.mjs` and
// `../packages/crypto/scripts/assert-argon-worker-in-dist.mjs` (inbox #412).
//
// Why a build can lose any of this and still exit 0: pdf.js is reached through `import('./pdfjsRuntime')` (pdfEngine.ts).
// A static import of it anywhere puts ~0.5 MB of parser into the entry bundle of every page, silently; a worker URL spelled
// any other way than the `?url` import in pdfjsRuntime.ts is inlined as a `data:` URL, which `worker-src 'self'` refuses, and
// every PDF then fails to open ("the file cannot be read") while all unit tests - which mock the engine - stay green.
//
// It asserts, each named in its failure:
//  (i)   exactly one `assets/pdf.worker.min-*.js` exists and it is pdf.js' worker (carries its `GetDocRequest` message);
//  (ii)  exactly one `assets/pdfjsRuntime-*.js` exists, is pdf.js' main library, and references that worker by file name;
//  (iii) the runtime is reached by a dynamic `import(...)` and by no static import, and the entry chunks (everything
//        index.html loads) hold none of pdf.js - neither its library nor its worker;
//  (iv)  neither emitted file builds code from strings: no `new Function(` and no `eval(` (the page has no 'unsafe-eval');
//  (v)   the same-origin runtime data exists (`assets/pdfjs-<version>/{cmaps,standard_fonts,iccs}/`), and NO `.wasm` file
//        and no `wasm` directory shipped: the WASM decoders need 'wasm-unsafe-eval', which the policy does not grant, and
//        `useWasm: false` (pdfOptions.ts) is what keeps pdf.js from asking.
//
// What it does NOT prove: that a browser starts the worker or that the deployed policy admits it at run time - only a real
// page with the real header shows that (docs/apps/depot.md names the proof).
//
// Bite (snapshot-and-restore, never `git checkout`): in pdfEngine.ts replace the dynamic import with a static
// `import { getDocument } from './pdfjsRuntime'`, build, and (iii) goes RED; restore and it is green.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist', import.meta.url))
const ASSETS = join(DIST, 'assets')
const WORKER = /^pdf\.worker\.min-[^/]+\.js$/
const RUNTIME = /^pdfjsRuntime-[^/]+\.js$/
const DATA_DIRS = ['cmaps', 'standard_fonts', 'iccs']

let names
try {
  names = readdirSync(ASSETS)
} catch {
  console.error(`verify:pdf-worker: ${ASSETS} not found - run \`vite build\` first`)
  process.exit(1)
}
const text = (name) => readFileSync(join(ASSETS, name), 'utf8')
const failures = []

const workers = names.filter((n) => WORKER.test(n))
const runtimes = names.filter((n) => RUNTIME.test(n))
if (workers.length !== 1) failures.push(`(i) expected exactly one assets/pdf.worker.min-*.js, found ${workers.length}: ${workers.join(', ') || 'none'}`)
else if (!text(workers[0]).includes('GetDocRequest')) failures.push(`(i) ${workers[0]} does not carry pdf.js' GetDocRequest message - it is not the worker`)

if (runtimes.length !== 1) {
  const inlined = names.filter((n) => n.endsWith('.js') && !WORKER.test(n) && text(n).includes('GlobalWorkerOptions.workerSrc'))
  failures.push(
    `(ii) expected exactly one assets/pdfjsRuntime-*.js, found ${runtimes.length}: ${runtimes.join(', ') || 'none'}` +
      (inlined.length ? ` - pdf.js was merged into ${inlined.join(', ')} (a static import of pdfjsRuntime.ts?)` : ''),
  )
} else {
  const runtime = text(runtimes[0])
  if (!runtime.includes('GlobalWorkerOptions.workerSrc')) failures.push(`(ii) ${runtimes[0]} is not pdf.js' library`)
  if (workers.length === 1 && !runtime.includes(workers[0])) failures.push(`(ii) ${runtimes[0]} does not reference ${workers[0]} - pdf.js cannot start its worker`)
}

if (runtimes.length === 1) {
  const runtime = runtimes[0]
  const html = readFileSync(join(DIST, 'index.html'), 'utf8')
  if (html.includes(runtime) || (workers[0] && html.includes(workers[0]))) failures.push('(iii) index.html loads pdf.js directly (script or modulepreload)')
  const jsNames = names.filter((n) => n.endsWith('.js') && n !== runtime && n !== workers[0])
  const dynamic = new RegExp('import\\(\\s*[`"\']\\./' + runtime.replace(/[.]/g, '\\.') + '[`"\']')
  const staticRef = new RegExp('(?:from|import)\\s*[`"\']\\./' + runtime.replace(/[.]/g, '\\.') + '[`"\']')
  const referrers = jsNames.filter((n) => text(n).includes(runtime))
  if (referrers.length === 0) failures.push(`(iii) no chunk references ${runtime} - the viewer cannot load pdf.js`)
  for (const n of referrers) {
    const src = text(n)
    if (staticRef.test(src)) failures.push(`(iii) ${n} imports ${runtime} STATICALLY - pdf.js would load with it`)
    else if (!dynamic.test(src)) failures.push(`(iii) ${n} references ${runtime} but not as a dynamic import(...)`)
  }
  // pdf.js' own text must live in the two lazy files only.
  for (const n of jsNames) {
    if (WORKER.test(n) || RUNTIME.test(n)) continue
    const src = text(n)
    if (src.includes('GetDocRequest') || src.includes('GlobalWorkerOptions.workerSrc')) failures.push(`(iii) ${n} contains pdf.js code outside the lazy chunk`)
  }
}

for (const n of [...workers, ...runtimes]) {
  const src = text(n)
  if (/new\s+Function\s*\(/.test(src)) failures.push(`(iv) ${n} contains \`new Function(\``)
  if (/(^|[^\w$.])eval\s*\(/.test(src)) failures.push(`(iv) ${n} contains \`eval(\``)
}

const dataRoots = names.filter((n) => /^pdfjs-\d/.test(n))
if (dataRoots.length !== 1) failures.push(`(v) expected exactly one assets/pdfjs-<version>/ directory, found ${dataRoots.length}`)
else {
  for (const d of DATA_DIRS) {
    const dir = join(ASSETS, dataRoots[0], d)
    if (!existsSync(dir) || readdirSync(dir).length === 0) failures.push(`(v) assets/${dataRoots[0]}/${d}/ is missing or empty`)
  }
}
const wasm = []
const walk = (dir) => {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e)
    if (statSync(p).isDirectory()) {
      if (e === 'wasm') wasm.push(p)
      walk(p)
    } else if (e.endsWith('.wasm')) wasm.push(p)
  }
}
walk(DIST)
if (wasm.length) failures.push(`(v) WebAssembly shipped (${wasm.map((p) => p.slice(DIST.length + 1)).join(', ')}) - the policy has no 'wasm-unsafe-eval'`)

if (failures.length) {
  console.error('PDF.JS IS NOT BUILT AS INTENDED - PDFs would not open, or pdf.js would weigh on every page:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log(`verify:pdf-worker: ${runtimes[0]} is lazy (dynamic import only), ${workers[0]} is its same-origin worker, no eval/new Function, no WASM, runtime data in ${dataRoots[0]}/.`)
