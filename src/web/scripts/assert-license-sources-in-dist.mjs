// MPL-2.0 §3.2(a): a copy in executable form tells its recipient where the source is. Run AFTER `vite build`: fail if
// dist/third-party-licenses.txt is missing, if any package heading in it lacks the `Source:` line vite.config.ts's
// licenseSourcesPlugin adds, or if ExifReader - the bundle's MPL-2.0 package, the reason the line exists - is not
// listed with its source. A Vite upgrade that renames the file, reorders the plugin or changes the heading shape
// turns this red instead of shipping a notice without sources.
//
// Bite (snapshot-and-restore, never `git checkout`): drop `licenseSourcesPlugin()` from vite.config.ts's plugin list,
// `npm run build`, and this goes RED; restore, and it goes green.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist/', import.meta.url))
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
  if (source !== `https://www.npmjs.com/package/${name}/v/${version}`) failures.push(`${name} ${version}: Source is "${source}"`)
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
