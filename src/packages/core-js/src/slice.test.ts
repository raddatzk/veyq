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
  files: { control: 'blob/Sniff.kt' },
  media: { control: 'media/ExifReader.kt' },
  export: { control: 'export/ManifestRules.kt' },
  audio: { control: 'audio/Order.kt' },
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
  // the file facts (ADR 0155 decision 7.2.4, § The file facts' slice): sniff.ts' sniffImage, sniffText, claimsText, claimsSvg,
  // sniffSvg, sniffStream, sniffPdf, extensionOf, meta.ts' downloadName and MIME_TO_EXT, archiveName.ts' exports -
  // 5,815 B min / 2,539 B gz, measured on sources.
  { slice: 'the file facts', deletedGz: 2_539 },
  // the media headers (ADR 0155 decision 7.2.5, § The media headers' slice): imageHeader.ts' imageSizeFromHeader,
  // videoHeader.ts' videoSizeFromHeader and videoCaptureTimeFromHeader, exifTags.ts' readExifTags, tagsFrom, CHUNKED_PREFIX
  // and TIFF_PREFIX - 12,097 B min / 4,809 B gz, measured on sources (the ExifReader library it called, 45,226 B gz of a lazy
  // chunk, left with it and is not counted).
  { slice: 'the media headers', deletedGz: 4_809 },
  // the export archive (ADR 0155 decision 7.2.6, § The export archive's slice): zip.ts' writer, reader and zipLength,
  // manifest.ts' check, payload walk and facts - 14,939 B min / 5,608 B gz, measured on sources (crc32.ts, 265 B gz, stays:
  // the digest worker's own primitive; the adapters that replace the two files are 1,292 B gz, so 4,316 B gz net).
  { slice: 'the export archive', deletedGz: 5_608 },
  // the music order and fades (ADR 0155 decision 7.2.7, § The music order and fades' slice): order.ts' PlayOrder, nextRound,
  // shuffled, cryptoRandomInt and PREVIOUS_RESTARTS_AFTER_MS, fades.ts' autoFadeMs, pressFadeMs, fadeOutAtMs, fadeLengthAt,
  // FADE_MS and PRESS_FADE_MS - 1,905 B min / 864 B gz, measured on sources. The last of decision 7.2's list.
  { slice: 'the music order and fades', deletedGz: 864 },
]
/** Each application's entry (ADR 0155 decision 7.3): its code its own module's file, which imports the platform's. What else
 *  it must not carry, and a Kotlin source its map must list. */
const APPLICATIONS: Record<string, { forbidden?: RegExp; control: string }> = {
  /** Its codec needs no X-Wing, so no SHA-3. */
  expenses: { forbidden: /hash-library-sha3|sponges-library-keccak/i, control: 'expenses/Ledger.kt' },
  /** What an opened file is, a folder upload's plan, an SVG's raster and zoom steps. */
  depot: { control: 'depot/FolderUpload.kt' },
}
/** ADR 0155 decision 7.3: an application grows what ships by at most 25 KB gz. Held gross here — what its entry adds over
 *  every platform entry bundled together, its deleted TypeScript not subtracted, so it can only be stricter than the bound
 *  (the web's measurement, recorded in the ADR, counts the TypeScript). What the application's façade needs of the
 *  platform (Expenses' JSON seam, money's formats) is the platform's, held by the running budget below (the owner,
 *  2026-11-22). */
const APPLICATION_GZ = 25 * 1024
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
  return {
    js: js.contents, gz: gzipSync(js.contents, { level: 9 }).length, map, inputs, stdlib: share(/kotlin-kotlin-stdlib/),
    own: share(/kotlin_veyq_mobile_core\.mjs$/), apps: share(/kotlin_veyq_mobile_apps_\w+\.mjs$/),
  }
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

describe('what an application\'s entry ships', () => {
  for (const [entry, { forbidden, control }] of Object.entries(APPLICATIONS)) {
    it(`@veyq/core-js/${entry}: no serialization, coroutines, Ktor or refusal, within 25 KB gz over the platform`, async () => {
      const { js, gz, map, inputs, stdlib, own, apps } = await bundle([entry])
      const banned = (src: string) => FORBIDDEN.test(src) || (forbidden?.test(src) ?? false)
      const modules = Object.entries(inputs).filter(([, i]) => i.bytesInOutput > 0).map(([m]) => m)
      expect(modules.filter(banned)).toEqual([])
      expect(map.sources.filter(banned)).toEqual([])
      expect(map.sources.some((src) => src.endsWith(control))).toBe(true)
      const platform = await bundle(Object.keys(ENTRIES))
      const together = await bundle([...Object.keys(ENTRIES), entry])
      console.log(`@veyq/core-js/${entry}: ${js.length} B minified, ${gz} B gz alone; of the minified: standard library ${stdlib} B, ` +
        `the platform's own ${own} B, the application's ${apps} B, other ${js.length - stdlib - own - apps} B; ` +
        `over every platform entry (${platform.gz} B gz) it adds ${together.gz - platform.gz} B gz (bound ${APPLICATION_GZ})`)
      expect(together.gz - platform.gz).toBeLessThanOrEqual(APPLICATION_GZ)
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
