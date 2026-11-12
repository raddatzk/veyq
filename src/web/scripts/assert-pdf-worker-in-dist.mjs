// Run AFTER `vite build`: fail unless the PDF renderer's pdf.js (core's `src/shared/pdf/`) is a LAZY chunk with a same-origin worker, its runtime data is
// emitted, and nothing that would widen the page's policy shipped. The twin of `assert-media-worker-in-dist.mjs` and
// `../packages/crypto/scripts/assert-argon-worker-in-dist.mjs` (inbox #412).
//
// Why a build can lose any of this and still exit 0: pdf.js is reached through `import('./pdfjsRuntime')` (`src/shared/pdf/pdfEngine.ts`).
// A static import of it anywhere puts ~0.5 MB of parser into the entry bundle of every page, silently; a worker URL spelled
// any other way than the `?url` import in `src/shared/pdf/pdfjsRuntime.ts` is inlined as a `data:` URL, which `worker-src 'self'` refuses, and
// every PDF then fails to open ("the file cannot be read") while all unit tests - which mock the engine - stay green.
//
// It asserts, each named in its failure:
//  (i)   exactly one `assets/pdf.worker.min-*.js` exists and it is pdf.js' worker (carries its `GetDocRequest` message);
//  (ii)  exactly one `assets/pdfjsRuntime-*.js` exists, is pdf.js' main library, and references that worker by file name;
//  (iii) the runtime is reached by a dynamic `import(...)` and by no static import, and the entry chunks (everything
//        index.html loads) hold none of pdf.js - neither its library nor its worker;
//  (iv)  neither emitted file, nor either image decoder (below), builds code from strings: no `new Function(` and no
//        `eval(` (the page has no 'unsafe-eval'). Bite: append `eval(0)` to an emitted decoder - RED.
//  (v)   the same-origin runtime data exists (`assets/pdfjs-<version>/{cmaps,standard_fonts,iccs}/`), and its `decoders/`
//        holds EXACTLY pdf.js' two plain-JavaScript image decoders (`jbig2_nowasm_fallback.js` for JBIG2 and CCITT,
//        `openjpeg_nowasm_fallback.js` for JPEG 2000; ADR 0128), each byte for byte `pdfjs-dist/wasm/`'s file - so what
//        the worker imports is what the lockfile pinned, and nothing else of that directory (`quickjs-eval.js`, the
//        `.wasm`s) rides along. The two names are listed HERE on purpose, not read from the build's list: a third name
//        added there must turn this red. And none of pdf.js' WebAssembly shipped: no `wasm` directory, and the one
//        `.wasm` in dist is the WebP encoder's (`assets/webp_encoder-<hash>.wasm`, byte for byte
//        `webp-encoder/wasm/webp_encoder.wasm`, ADR 0127). The policy would compile pdf.js' WebAssembly
//        ('wasm-unsafe-eval'); it stays off because nobody turned it on (inbox #837), and `useWasm: false`
//        (pdfOptions.ts) is what keeps pdf.js from asking. Bites, each RED: copy `pdfjs-dist/wasm/jbig2.wasm` into
//        `decoders/` (a `.wasm`, and a third file); copy `quickjs-eval.js` there (a third file); flip one byte of an
//        emitted decoder (not the package's bytes).
//
// What it does NOT prove: that a browser starts the worker, that pdf.js finds the decoders (that is `wasmUrl` in
// pdfOptions.ts, which `pdfEngine.real.test.ts` decodes through), or that the deployed policy admits either at run time -
// only a real page with the real header shows that (cc.security.csp § pdf.js records the pass).
//
// Bite (snapshot-and-restore, never `git checkout`): in `src/shared/pdf/pdfEngine.ts` replace the dynamic import with a static
// `import { getDocument } from './pdfjsRuntime'`, build, and (iii) goes RED; restore and it is green.
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist', import.meta.url))
const ASSETS = join(DIST, 'assets')
const WORKER = /^pdf\.worker\.min-[^/]+\.js$/
// The WebP encoder (ADR 0127): the one WebAssembly the client ships, emitted by Vite under its own name and a hash.
const ENCODER = /^webp_encoder-[\w-]+\.wasm$/
const ENCODER_SOURCE = fileURLToPath(new URL('../webp-encoder/wasm/webp_encoder.wasm', import.meta.url))
const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')
const RUNTIME = /^pdfjsRuntime-[^/]+\.js$/
const DATA_DIRS = ['cmaps', 'standard_fonts', 'iccs']
// pdf.js' plain-JavaScript image decoders (ADR 0128) and the package directory they must equal byte for byte.
const DECODERS = ['jbig2_nowasm_fallback.js', 'openjpeg_nowasm_fallback.js']
const PDFJS_WASM_DIR = join(dirname(createRequire(import.meta.url).resolve('pdfjs-dist/package.json')), 'wasm')
// A file name as a literal inside a RegExp: every metacharacter escaped, the backslash first among them.
const escapeRegExp = (text) => text.replace(/[\\^$.*+?()[\]{}|]/g, '\\$&')

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
  const dynamic = new RegExp('import\\(\\s*[`"\']\\./' + escapeRegExp(runtime) + '[`"\']')
  const staticRef = new RegExp('(?:from|import)\\s*[`"\']\\./' + escapeRegExp(runtime) + '[`"\']')
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

const dataRoots = names.filter((n) => /^pdfjs-\d/.test(n))
const decoderDir = dataRoots.length === 1 ? join(dataRoots[0], 'decoders') : null
const decoders = decoderDir && existsSync(join(ASSETS, decoderDir)) ? readdirSync(join(ASSETS, decoderDir)).map((n) => join(decoderDir, n)) : []

for (const n of [...workers, ...runtimes, ...decoders]) {
  const src = text(n)
  if (/new\s+Function\s*\(/.test(src)) failures.push(`(iv) ${n} contains \`new Function(\``)
  if (/(^|[^\w$.])eval\s*\(/.test(src)) failures.push(`(iv) ${n} contains \`eval(\``)
}

if (dataRoots.length !== 1) failures.push(`(v) expected exactly one assets/pdfjs-<version>/ directory, found ${dataRoots.length}`)
else {
  for (const d of DATA_DIRS) {
    const dir = join(ASSETS, dataRoots[0], d)
    if (!existsSync(dir) || readdirSync(dir).length === 0) failures.push(`(v) assets/${dataRoots[0]}/${d}/ is missing or empty`)
  }
  const shipped = decoders.map((p) => p.slice(decoderDir.length + 1)).sort()
  if (shipped.join() !== [...DECODERS].sort().join()) {
    failures.push(`(v) assets/${decoderDir}/ must hold exactly ${DECODERS.join(' and ')}, it holds ${shipped.join(', ') || 'nothing'}`)
  }
  for (const name of DECODERS.filter((n) => shipped.includes(n))) {
    if (sha256(join(ASSETS, decoderDir, name)) !== sha256(join(PDFJS_WASM_DIR, name))) failures.push(`(v) assets/${decoderDir}/${name} is not byte for byte pdfjs-dist's wasm/${name}`)
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
const encoderSha = sha256(ENCODER_SOURCE)
const isEncoder = (p) => p.startsWith(ASSETS + '/') && ENCODER.test(p.slice(ASSETS.length + 1)) && sha256(p) === encoderSha
const encoders = wasm.filter(isEncoder)
const foreign = wasm.filter((p) => !isEncoder(p))
if (foreign.length) failures.push(`(v) WebAssembly other than the WebP encoder shipped (${foreign.map((p) => p.slice(DIST.length + 1)).join(', ')}) - pdf.js' decoders stay off until they are decided`)
if (encoders.length !== 1) failures.push(`(v) expected the WebP encoder once as assets/webp_encoder-<hash>.wasm with the committed bytes, found ${encoders.length}`)

if (failures.length) {
  console.error('PDF.JS IS NOT BUILT AS INTENDED - PDFs would not open, or pdf.js would weigh on every page:')
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log(
  `verify:pdf-worker: ${runtimes[0]} is lazy (dynamic import only), ${workers[0]} is its same-origin worker, no eval/new Function, no WASM but the WebP encoder's, runtime data and pdfjs-dist's two image decoders in ${dataRoots[0]}/.`,
)
