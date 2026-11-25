import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'
import { build } from 'esbuild'

// The slice check (ADR 0155 decision 4): what an entry ships, bundled and minified as a web build bundles it, carries
// no `NotOnTheWeb` refusal, and of kotlinx.serialization, coroutines and Ktor exactly what its row declares — by the
// sources its source map lists, through the compiled modules' own maps to the Kotlin files. Sizes are a report, never a
// gate (the owner, 2026-11-25): each entry's size, the platform module's with every entry, and each application's growth
// over the platform are printed beside the ratios ADR 0155 records, the Kotlin standard library's share apart from the core's own.
const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
const MODULE_FILE = 'kotlin_veyq_mobile_core.mjs'
if (!existsSync(resolve(dist, MODULE_FILE))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}

/** A façade reaching a refusal throws on the web: a correctness defect, so this one stays a ban (ADR 0155 decision 4). */
const REFUSAL = /NotOnTheWeb\.kt/
/** The libraries an entry declares when the move that needs them is built (ADR 0155 decision 4, since 2026-11-25). */
const LIBRARIES = {
  serialization: /kotlinx-serialization|kotlinx\/serialization/i,
  coroutines: /kotlinx-coroutines|kotlinx\.coroutines/i,
  ktor: /ktor/i,
} as const
type Library = keyof typeof LIBRARIES

/** Each entry, a Kotlin source its map must list (the map does reach the Kotlin sources, so an absence of a library is an
 *  absence), and the libraries it carries — a move that brings one in widens its row in the same commit, one that drops
 *  one narrows it. The platform's entries' code is the platform's module file (`package.json` maps its code to the module,
 *  its types to `dist/<entry>.d.mts`); each application's, its own module's file, which imports the platform's. */
const ENTRIES: Record<string, { control: string; carries: Library[] }> = {
  search: { control: 'search/Query.kt', carries: [] },
  tree: { control: 'tree/LevelOrder.kt', carries: [] },
  sharing: { control: 'sharing/ReceivedLink.kt', carries: [] },
  navigation: { control: 'navigation/NodeHeader.kt', carries: [] },
  files: { control: 'blob/Sniff.kt', carries: [] },
  media: { control: 'media/ExifReader.kt', carries: [] },
  export: { control: 'export/ManifestRules.kt', carries: [] },
  audio: { control: 'audio/Order.kt', carries: [] },
}
const APPLICATIONS: Record<string, { control: string; carries: Library[] }> = {
  expenses: { control: 'expenses/Ledger.kt', carries: [] },
  /** What an opened file is, a folder upload's plan, an SVG's raster and zoom steps. */
  depot: { control: 'depot/FolderUpload.kt', carries: [] },
  /** The gallery grid's geometry, a Memory's medoid, the participants' merge, the audio frame headers, a WAV's reading. */
  memories: { control: 'memories/MediaGridLayout.kt', carries: [] },
}

/**
 * The platform module's report (ADR 0155 decision 7.2): every platform façade lands in the one module file, which a bundler
 * cannot split, so its size is the module bundled with all its entries. Until 2026-11-25 it was gated by a running bound —
 * the search alone as the core's split measured it plus R × the gzip of each moved slice's deleted TypeScript (R = 3.0, the
 * node tree's 3.14 by the owner, 2026-11-22), 80,094 B gz after the seventh slice; the owner dropped it that day. Each row
 * keeps the TypeScript its slice deleted, so the report prints the platform's growth against it.
 */
const SEARCH_ALONE_GZ = 27_943 // ADR 0156 § Consequences: the search's entry alone on the split
const SLICES: { slice: string; deletedGz: number }[] = [
  // the node tree's rules (ADR 0155 decision 7.2.1, § The node tree's slice): levelList.ts, location.ts' order and numbering,
  // siblingCollision.ts' collidingSibling, move.ts' plan - 3,086 B min / 1,326 B gz, measured on sources.
  { slice: 'the node tree\'s rules', deletedGz: 1_326 },
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
const DELETED_GZ = SLICES.reduce((n, s) => n + s.deletedGz, 0)

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

/** What [bundled] carries: a refusal (a ban) and the libraries an entry declares, by its modules and its map's sources. */
function carried(bundled: Awaited<ReturnType<typeof bundle>>) {
  const modules = Object.entries(bundled.inputs).filter(([, i]) => i.bytesInOutput > 0).map(([m]) => m)
  const names = [...modules, ...bundled.map.sources]
  return {
    refusals: names.filter((n) => REFUSAL.test(n)),
    libraries: (Object.keys(LIBRARIES) as Library[]).filter((l) => names.some((n) => LIBRARIES[l].test(n))),
  }
}

describe('what an entry ships', () => {
  for (const [entry, { control, carries }] of Object.entries(ENTRIES)) {
    it(`@veyq/core-js/${entry}: no refusal, and of serialization, coroutines and Ktor ${carries.join(', ') || 'none'}`, async () => {
      const bundled = await bundle([entry])
      const { js, gz, map, stdlib, own } = bundled
      const { refusals, libraries } = carried(bundled)
      expect(refusals).toEqual([])
      expect(libraries).toEqual(carries)
      expect(map.sources.some((src) => src.endsWith(control))).toBe(true)
      console.log(`@veyq/core-js/${entry}: ${js.length} B minified, ${gz} B gz; of the minified: standard library ${stdlib} B, ` +
        `the platform's own ${own} B, other ${js.length - stdlib - own} B`)
    })
  }
})

describe('what an application\'s entry ships', () => {
  for (const [entry, { control, carries }] of Object.entries(APPLICATIONS)) {
    it(`@veyq/core-js/${entry}: no refusal, and of serialization, coroutines and Ktor ${carries.join(', ') || 'none'}`, async () => {
      const bundled = await bundle([entry])
      const { js, gz, map, stdlib, own, apps } = bundled
      const { refusals, libraries } = carried(bundled)
      expect(refusals).toEqual([])
      expect(libraries).toEqual(carries)
      expect(map.sources.some((src) => src.endsWith(control))).toBe(true)
      const platform = await bundle(Object.keys(ENTRIES))
      const together = await bundle([...Object.keys(ENTRIES), entry])
      console.log(`@veyq/core-js/${entry}: ${js.length} B minified, ${gz} B gz alone; of the minified: standard library ${stdlib} B, ` +
        `the platform's own ${own} B, the application's ${apps} B, other ${js.length - stdlib - own - apps} B; ` +
        `over every platform entry (${platform.gz} B gz) it adds ${together.gz - platform.gz} B gz (reported, no gate)`)
    })
  }
})

describe('the platform module\'s report', () => {
  it('prints every entry together against the search alone and the TypeScript the slices deleted', async () => {
    const whole = await bundle(Object.keys(ENTRIES))
    const grown = whole.gz - SEARCH_ALONE_GZ
    console.log(`every platform entry together: ${whole.js.length} B minified, ${whole.gz} B gz (reported, no gate); ` +
      `over the search alone (${SEARCH_ALONE_GZ}) +${grown} B gz against the slices' ${DELETED_GZ} B gz of deleted TypeScript ` +
      `(${(grown / DELETED_GZ).toFixed(2)}×, application code the platform carries included)`)
    expect(whole.map.sources.some((src) => src.endsWith(ENTRIES.search.control))).toBe(true)
  })
})
