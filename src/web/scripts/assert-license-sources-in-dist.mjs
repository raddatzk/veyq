// MPL-2.0 §3.2(a): a copy in executable form tells its recipient where the source is. Run AFTER `vite build`: fail if
// dist/third-party-licenses.txt is missing, if any package heading in it lacks the `Source:` line vite.config.ts's
// licenseSourcesPlugin adds, or if ExifReader - the bundle's MPL-2.0 package, the reason the line exists - is not
// listed with its source. A Vite upgrade that renames the file, reorders the plugin or changes the heading shape
// turns this red instead of shipping a notice without sources.
//
// Bite (snapshot-and-restore, never `git checkout`): drop `licenseSourcesPlugin()` from vite.config.ts's plugin list,
// `pnpm run build`, and this goes RED; restore, and it goes green.
//
// One heading is not an npm package: libwebp, which the client's WebP encoder is built from (ADR 0127). Its Source is the
// pinned tag on libwebp's repository (`webp-encoder/libwebp.pin`), and its text carries the build's COPYING and PATENTS;
// exactly that form is accepted for it, and it must be there. Bite: drop the `libwebpNotice()` line from the plugin - RED.
//
// Four headings are files of an npm package rather than the package: the notices of pdf.js' image decoders, which the build
// copies from `pdfjs-dist/wasm/` into `assets/pdfjs-<version>/decoders/` (ADR 0128). Whenever that directory shipped, each
// of `wasm/LICENSE_JBIG2`, `LICENSE_PDFJS_JBIG2`, `LICENSE_OPENJPEG` and `LICENSE_PDFJS_OPENJPEG` must be there once, as
// `## pdfjs-dist - <the installed version> - wasm/<file>` with the package at that version as its Source and the file's
// text verbatim. Bite: drop one of the four from the built licence file - RED.
import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist/', import.meta.url))
const ENCODER = fileURLToPath(new URL('../webp-encoder/', import.meta.url))
const LIBWEBP_TAG = /^tag (\S+)$/m.exec(readFileSync(ENCODER + 'libwebp.pin', 'utf8'))?.[1]
// Keep in sync with LICENSE_FILE in vite.config.ts.
const LICENSE_FILE = 'third-party-licenses.txt'

let text
try {
  text = readFileSync(DIST + LICENSE_FILE, 'utf8')
} catch {
  console.error(`dist/${LICENSE_FILE} not found — run \`vite build\` first`)
  process.exit(1)
}

const failures = []
const headings = [...text.matchAll(/^## (\S+) - (\S+).*\n\nSource: (\S+)?/gm)]
const all = [...text.matchAll(/^## .*$/gm)]
if (all.length === 0) failures.push('no package heading at all — has the file format changed?')
if (headings.length !== all.length) {
  const sourced = new Set(headings.map((m) => m.index))
  for (const m of all) if (!sourced.has(m.index)) failures.push(`no Source line under "${m[0]}"`)
}
for (const [, name, version, source] of headings) {
  if (name === 'libwebp') continue // not an npm package: held below
  if (source !== `https://www.npmjs.com/package/${name}/v/${version}`) failures.push(`${name} ${version}: Source is "${source}"`)
}
const libwebp = headings.filter(([, name]) => name === 'libwebp')
if (libwebp.length !== 1) failures.push(`libwebp (the WebP encoder) is listed ${libwebp.length} times, not once`)
else {
  const [, , version, source] = libwebp[0]
  if (!LIBWEBP_TAG || version !== LIBWEBP_TAG) failures.push(`libwebp is listed as ${version}, the pin holds ${LIBWEBP_TAG}`)
  if (source !== `https://github.com/webmproject/libwebp/tree/${LIBWEBP_TAG}`) failures.push(`libwebp: Source is "${source}", not its tag`)
  const entry = text.slice(libwebp[0].index, all.find((m) => m.index > libwebp[0].index)?.index ?? text.length)
  for (const name of ['COPYING', 'PATENTS']) {
    if (!entry.includes(readFileSync(ENCODER + name, 'utf8').trim())) failures.push(`libwebp's entry does not carry its ${name}`)
  }
}
const PDFJS = dirname(createRequire(import.meta.url).resolve('pdfjs-dist/package.json'))
const PDFJS_VERSION = JSON.parse(readFileSync(join(PDFJS, 'package.json'), 'utf8')).version
if (existsSync(join(DIST, 'assets', `pdfjs-${PDFJS_VERSION}`, 'decoders'))) {
  for (const file of ['LICENSE_JBIG2', 'LICENSE_PDFJS_JBIG2', 'LICENSE_OPENJPEG', 'LICENSE_PDFJS_OPENJPEG']) {
    const heading = `## pdfjs-dist - ${PDFJS_VERSION} - wasm/${file}`
    const found = all.filter((m) => m[0] === heading)
    if (found.length !== 1) {
      failures.push(`pdf.js' image decoders shipped, and "${heading}" is listed ${found.length} times, not once`)
      continue
    }
    const entry = text.slice(found[0].index, all.find((m) => m.index > found[0].index)?.index ?? text.length)
    if (!entry.includes(readFileSync(join(PDFJS, 'wasm', file), 'utf8').trim())) failures.push(`"${heading}" does not carry the notice's text`)
  }
}
if (!/^## exifreader - \S+ \(MPL-2\.0\)\n\nSource: \S+\nRepository: https:\/\/github\.com\/mattiasw\/ExifReader$/m.test(text)) {
  failures.push('ExifReader (MPL-2.0) is not listed with its Source and Repository lines')
}

if (failures.length) {
  console.error(`LICENSE SOURCES MISSING in dist/${LICENSE_FILE}:`)
  for (const f of failures) console.error('  - ' + f)
  process.exit(1)
}
console.log(`dist/${LICENSE_FILE}: ${all.length} packages, each with its source`)
