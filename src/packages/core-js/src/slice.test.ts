import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'
import { build } from 'esbuild'

// The slice check (ADR 0155 decision 4): what an entry ships, bundled and minified as a web build bundles it, carries
// no kotlinx.serialization, coroutines or Ktor and no `NotOnTheWeb` refusal — by the sources its source map lists,
// through the compiled modules' own maps to the Kotlin files — and the platform module stays inside its running budget
// (ADR 0155 decision 7.2). The size is printed beside the budget, the Kotlin standard library's share apart from the core's own.
const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
const MODULE_FILE = 'kotlin_veyq_mobile_core.mjs'
if (!existsSync(resolve(dist, MODULE_FILE))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}

const FORBIDDEN = /kotlinx-serialization|kotlinx-coroutines|kotlinx\.coroutines|kotlinx\/serialization|ktor|NotOnTheWeb\.kt/i

/** Each entry of the platform's module file (`package.json` maps its code to the module, its types to `dist/<entry>.d.mts`)
 *  and a Kotlin source its map must list: the map does reach the Kotlin sources, so an absence of a forbidden one is an
 *  absence. */
const ENTRIES: Record<string, { control: string }> = {
  search: { control: 'search/Query.kt' },
  tree: { control: 'tree/LevelOrder.kt' },
  sharing: { control: 'sharing/ReceivedLink.kt' },
  navigation: { control: 'navigation/NodeHeader.kt' },
}

/**
 * The platform module's running budget (ADR 0155 decision 7.2): every platform façade lands in the one module file, which
 * a bundler cannot split, so the bound is on the module bundled with all its entries — the search alone as the core's
 * split measured it, plus R × the gzip of each moved slice's deleted TypeScript (R = 3.0, the pilot's ratio rounded up).
 * One row per slice, with the work order and the numbers it measured.
 */
const R = 3.0
const SEARCH_ALONE_GZ = 27_943 // ADR 0156 § Consequences: the search's entry alone on the split
/** One row per moved slice: the gzip of the TypeScript it deleted, and — only where the owner granted it — a ratio of its
 *  own in place of R, with the owner's date. The rule stays R for every slice that names none. */
const SLICES: { slice: string; deletedGz: number; allowed?: { ratio: number; owner: string } }[] = [
  // the node tree's rules (ADR 0155 decision 7.2.1, § The node tree's slice): levelList.ts, location.ts' order and numbering,
  // siblingCollision.ts' collidingSibling, move.ts' plan - 3,086 B min / 1,326 B gz, measured on sources. Its own code
  // measured 4,158 B gz (3.14x): accepted at that ratio by the owner, 2026-11-22.
  { slice: 'the node tree\'s rules', deletedGz: 1_326, allowed: { ratio: 3.14, owner: '2026-11-22' } },
  // the share links' rules (ADR 0155 decision 7.2.2, § The share links' slice): bookmarkLink.ts' exports, linkRules.ts'
  // lastExpiryDay, shareInstance.ts' embeddability and olderMajorsInWindow - 2,678 B min / 1,253 B gz, measured on sources.
  { slice: 'the share links\' rules', deletedGz: 1_253 },
  // the rights and the node plans (ADR 0155 decision 7.2.3, § The rights and plans' slice): nodeHeader.ts' placeCoreActions, mergeHeaderActions, isMenuOnly, orderHeaderActions, partitionHeaderActions, levelRow.ts' mergeRowActions, ROW_MENU_LEAD, splitRowActions, favoriteRights, coverRights, bookmarkRights - 2,145 B min / 923 B gz, measured on sources.
  { slice: 'the rights and the node plans', deletedGz: 923 },
]
const BUDGET_GZ = Math.floor(SEARCH_ALONE_GZ + SLICES.reduce((n, s) => n + (s.allowed?.ratio ?? R) * s.deletedGz, 0))

/** [entries] bundled together, minified, as a web build bundles them: each entry's code is the platform's module file. */
async function bundle(entries: string[]) {
  const out = await build({
    bundle: true, minify: true, format: 'esm', platform: 'browser', write: false, metafile: true, sourcemap: 'external',
    outdir: resolve(here, '../.slice'), logLevel: 'silent', external: ['@noble/*'],
    stdin: { contents: entries.map((e) => `export * from '@veyq/core-js/${e}'`).join('\n'), resolveDir: here, sourcefile: 'entries.mjs' },
  })
  const js = out.outputFiles.find((f) => f.path.endsWith('.js'))!
  const map = JSON.parse(out.outputFiles.find((f) => f.path.endsWith('.map'))!.text) as { sources: string[] }
  const inputs = Object.values(out.metafile.outputs).find((o) => o.entryPoint)!.inputs
  const share = (re: RegExp) => Object.entries(inputs).filter(([m]) => re.test(m)).reduce((n, [, i]) => n + i.bytesInOutput, 0)
  return { js: js.contents, gz: gzipSync(js.contents, { level: 9 }).length, map, inputs, stdlib: share(/kotlin-kotlin-stdlib/), own: share(/kotlin_veyq_mobile_core\.mjs$/) }
}

describe('what an entry ships', () => {
  for (const [entry, { control }] of Object.entries(ENTRIES)) {
    it(`@veyq/core-js/${entry}: no serialization, coroutines, Ktor or refusal`, async () => {
      const { js, gz, map, inputs, stdlib, own } = await bundle([entry])
      const modules = Object.entries(inputs).filter(([, i]) => i.bytesInOutput > 0).map(([m]) => m)
      expect(modules.filter((m) => FORBIDDEN.test(m))).toEqual([])
      expect(map.sources.filter((src) => FORBIDDEN.test(src))).toEqual([])
      expect(map.sources.some((src) => src.endsWith(control))).toBe(true)
      console.log(`@veyq/core-js/${entry}: ${js.length} B minified, ${gz} B gz; of the minified: standard library ${stdlib} B, ` +
        `the platform's own ${own} B, other ${js.length - stdlib - own} B`)
    })
  }
})

describe('the platform module\'s running budget', () => {
  it('every entry together stays within the search alone plus R × each slice\'s deleted TypeScript', async () => {
    const whole = await bundle(Object.keys(ENTRIES))
    console.log(`every platform entry together: ${whole.js.length} B minified, ${whole.gz} B gz (budget ${BUDGET_GZ}: ` +
      `${SEARCH_ALONE_GZ} + ${SLICES.map((s) => `${s.allowed?.ratio ?? R} × ${s.deletedGz}${s.allowed ? ` (the owner, ${s.allowed.owner})` : ''}`).join(' + ')})`)
    expect(whole.gz).toBeLessThanOrEqual(BUDGET_GZ)
  })
})
