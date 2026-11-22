import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'navigation.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const n = await import('@veyq/core-js/navigation')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/plans/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/plans/v1-vectors.json'), 'utf8'))

// The plans' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers every case the
// core's PlanVectorsTest answers on the JVM and natively.
describe('a header\'s plan', () => {
  ;(vectors.header as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const p = n.headerPlan(c.rights, c.order, c.own, c.maxButtons ?? null)
      expect({ buttons: [...p.buttons], menu: [...p.menu], last: [...p.last] }).toEqual(c.expected)
    })
  })
})

describe('a row\'s menu', () => {
  ;(vectors.row as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      const p = n.rowPlan(c.rights, c.own)
      expect({ menu: [...p.menu], last: [...p.last] }).toEqual(c.expected)
    })
  })
})

describe('core\'s rights', () => {
  it('the heart', () => {
    for (const c of vectors.rights.favorite) expect(n.favoriteRight(c.member, c.node, c.known), JSON.stringify(c)).toBe(c.expected)
  })
  it('the cover', () => {
    for (const c of vectors.rights.cover) {
      expect(n.coverRight(c.member, c.keys, c.readonly, c.node, c.inIdentityFolder, c.applicationOn, c.takesCover), JSON.stringify(c)).toBe(c.expected)
    }
  })
  it('a kept link', () => {
    for (const c of vectors.rights.bookmark) {
      const b = n.bookmarkRights(c.member, c.node, c.keys, c.rename, c.move, c.delete)
      expect({ open: b.open, rename: b.rename, move: b.move, delete: b.delete }, JSON.stringify(c)).toEqual(c.expected)
    }
  })
})

describe('an entry the core does not know', () => {
  it('throws, naming it: a web entry of its own is a defect (ADR 0154 decision 2)', () => {
    const none = { favorite: false, share: false, rename: false, move: false, cover: false, copy: false, download: false, report: false, delete: false, offline: false }
    expect(() => n.headerPlan(none, [], ['select'], null)).toThrow(/select/)
    expect(() => n.rowPlan(none, ['constructor'])).toThrow(/constructor/)
  })
})
