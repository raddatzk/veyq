// cr-dev-auto-unlock — the "backdoor cannot ship" guard. Run AFTER `vite build`: fail if the built app bundle
// contains the DEV-only seed-credentials endpoint or the dev-auto-unlock marker. Both MUST be absent — the endpoint
// is an `apply:'serve'` Vite middleware (never built) and the client consumer is `import.meta.env.DEV`-gated
// (tree-shaken from prod). This proves it rather than trusting inspection, because it guards a security backdoor.
//
// Bite (snapshot-and-restore, never `git checkout`): remove the `if (import.meta.env.DEV)` gate in
// src/app/devAutoUnlock.ts, `pnpm run build`, and this goes RED; restore, and it goes green.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const DIST = fileURLToPath(new URL('../dist', import.meta.url))
// Keep these literals in sync with vite.config.ts (the endpoint path) and src/app/devAutoUnlock.ts (the marker).
const SENTINELS = ['__dev/seed-credentials', 'VEYQ_DEV_AUTOUNLOCK']
// Only text bundle outputs can carry a sentinel; skip fonts/images so a binary read can't false-positive or throw.
const TEXT = /\.(js|mjs|cjs|css|html|json|map|svg|txt)$/

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else if (TEXT.test(name)) out.push(p)
  }
  return out
}

let files
try {
  files = walk(DIST)
} catch {
  console.error(`no-dev-backdoor: dist/ not found at ${DIST} — run \`vite build\` first.`)
  process.exit(1)
}

const hits = []
for (const file of files) {
  const text = readFileSync(file, 'utf8')
  for (const s of SENTINELS) if (text.includes(s)) hits.push(`${file}: contains "${s}"`)
}

if (hits.length) {
  console.error('DEV BACKDOOR LEAK — the prod build contains dev-only sentinel(s):')
  for (const h of hits) console.error('  - ' + h)
  console.error('The dev auto-unlock must be tree-shaken out of any build. Check the import.meta.env.DEV gate.')
  process.exit(1)
}

console.log(`no-dev-backdoor: dist/ (${files.length} text files) is clean of ${SENTINELS.map((s) => `"${s}"`).join(' + ')}.`)
