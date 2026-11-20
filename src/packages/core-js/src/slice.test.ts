import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'
import { build } from 'esbuild'

// The slice check (ADR 0155 decision 4): what an entry ships, bundled and minified as a web build bundles it, carries
// no kotlinx.serialization, coroutines or Ktor and no `NotOnTheWeb` refusal — by the sources its source map lists,
// through the compiled modules' own maps to the Kotlin files — and stays inside the budget ADR 0155 decision 2 fixed.
// The size is printed beside the budget, the Kotlin standard library's share apart from the façade's own.
const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'veyq-mobile-core.mjs'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}

/** ADR 0155 decision 2: the eager set grows by at most 40 KB gz, the TypeScript the pilot deletes counted. The bundle
 *  alone above it can only miss. */
const BUDGET_GZ = 40 * 1024
const FORBIDDEN = /kotlinx-serialization|kotlinx-coroutines|kotlinx\.coroutines|kotlinx\/serialization|ktor|NotOnTheWeb\.kt/i

const ENTRIES = { search: 'veyq-mobile-core.mjs' }

describe('what an entry ships', () => {
  for (const [entry, file] of Object.entries(ENTRIES)) {
    it(`@veyq/core-js/${entry}: no serialization, coroutines, Ktor or refusal, within the budget`, async () => {
      const out = await build({
        entryPoints: [resolve(dist, file)], bundle: true, minify: true, format: 'esm', platform: 'browser', write: false,
        metafile: true, sourcemap: 'external', outdir: resolve(here, '../.slice'), logLevel: 'silent',
        external: ['@noble/*'],
      })
      const js = out.outputFiles.find((f) => f.path.endsWith('.js'))!
      const map = JSON.parse(out.outputFiles.find((f) => f.path.endsWith('.map'))!.text) as { sources: string[] }
      const inputs = Object.values(out.metafile.outputs).find((o) => o.entryPoint)!.inputs
      const modules = Object.entries(inputs).filter(([, i]) => i.bytesInOutput > 0).map(([m]) => m)
      expect(modules.filter((m) => FORBIDDEN.test(m))).toEqual([])
      expect(map.sources.filter((src) => FORBIDDEN.test(src))).toEqual([])
      // control: the map does reach the Kotlin sources, so an absence above is an absence
      expect(map.sources.some((src) => src.endsWith('search/Query.kt'))).toBe(true)

      const gz = gzipSync(js.contents, { level: 9 }).length
      const share = (re: RegExp) => Object.entries(inputs).filter(([m]) => re.test(m)).reduce((n, [, i]) => n + i.bytesInOutput, 0)
      const stdlib = share(/kotlin-kotlin-stdlib/)
      const own = share(/veyq-mobile-core\.mjs$/)
      console.log(
        `@veyq/core-js/${entry}: ${js.contents.length} B minified, ${gz} B gz (budget ${BUDGET_GZ}); of the minified: ` +
          `standard library ${stdlib} B, the core's own ${own} B, other ${js.contents.length - stdlib - own} B`,
      )
      expect(gz).toBeLessThanOrEqual(BUDGET_GZ)
    })
  }
})
