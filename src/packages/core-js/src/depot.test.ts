import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'depot.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const d = await import('@veyq/core-js/depot')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/depot/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/depot/v1-vectors.json'), 'utf8'))

/** A number as the vectors hold it: null is NaN. */
const num = (x: number | null) => (x === null ? Number.NaN : x)

// Depot's façade against every section of the vectors (ADR 0155 decisions 6 and 7.3): the shipped module answers every case
// the core's DepotVectorsTest answers on the JVM and natively.
describe('view', () => {
  ;(v.view as any[]).forEach((c) => {
    it(c.name, () => {
      const head = Uint8Array.from(Buffer.from(c.head, 'base64'))
      expect({ ...d.depotFileView(head, c.chunkCount, c.total, c.type, c.claimedName) }).toEqual(c.expected)
    })
  })
})

/** A planned folder as the vectors hold it. */
const tree = (f: any): any => ({
  name: f.name,
  folders: [...f.folders].map(tree),
  files: [...f.files].map((x: any) => ({ name: x.name, entry: x.entry })),
})

describe('folderPlan', () => {
  ;(v.folderPlan as any[]).forEach((c) => {
    it(c.name, () => {
      const tokens = new Map<string, string>((c.tokens as string[][]).map(([k, n, t]) => [`${k}\u0000${n}`, t!]))
      const slugFor = (kind: string, name: string) => {
        const t = tokens.get(`${kind}\u0000${name}`)
        if (t === undefined) throw new Error(`asks (${kind}, ${name}), a token the case does not list`)
        return t
      }
      const plan = d.depotFolderPlan(c.rootName, c.entries, c.siblings, slugFor)
      const next = [1, 2, 3].map(() => d.depotFolderRootNext(plan.rootNames))
      expect({ root: tree(plan.root), files: plan.files, bytes: plan.bytes, nextRootNames: next }).toEqual(c.expected)
    })
  })
})

describe('raster', () => {
  ;(v.raster as any[]).forEach((c, i) => {
    it(`#${i} ${c.width} x ${c.height} at ${c.ratio}`, () => {
      expect({ ...d.depotSvgRaster(c.width, c.height, num(c.ratio)) }).toEqual(c.expected)
    })
  })
})

describe('zoom', () => {
  it('the steps', () => expect([...d.depotSvgZoomSteps()]).toEqual(v.zoom.steps))
  ;(v.zoom.cases as any[]).forEach((c) => {
    it(`from ${c.zoom}`, () => expect({ in: d.depotSvgZoomIn(c.zoom), out: d.depotSvgZoomOut(c.zoom) }).toEqual(c.expected))
  })
})

describe('the caps', () => {
  it('a file\'s page reads within the core\'s caps, a canvas holds the core\'s most pixels', () => {
    expect({ ...d.depotFileViewCaps() }).toEqual({ image: 64 * 1024 * 1024, text: 1024 * 1024, pdf: 64 * 1024 * 1024 })
    expect([d.depotSvgMaxPixels(), d.depotSvgMaxEdge()]).toEqual([4096 * 4096, 8192])
  })
})
