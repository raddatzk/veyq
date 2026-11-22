// @vitest-environment happy-dom
// A node header's and a level row's plans and core's favourite, cover and bookmark rights against
// contracts/platform/plans/v1-vectors.json (ADR 0154 decision 2, ADR 0155 decision 7.2's third slice): every case through
// the web's adapters (`plans.ts`, `favorites/rights.ts`, `cover/rights.ts`, `bookmark/rights.ts`) over the platform's Kotlin
// (`@veyq/core-js/navigation`), and the real registry (`src/test/setup.ts` registers the applications, as `main.ts` does),
// whose kinds' header orders must be the file's. The core records the vectors (its PlanVectorsTest's write mode, ADR 0155
// § Consequences) and answers the same cases. A header that holds only so many buttons (`maxButtons`) is an app's; the web
// fits its own, so those cases run through the module itself.
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { headerPlan as coreHeaderPlan } from '@veyq/core-js/navigation'
import { registry } from '../applications/registry'
import { headerPlan, rowPlan } from './plans'
import type { NodeHeaderAction } from './nodeHeader'
import { favoriteRights } from '../favorites/rights'
import { coverRights } from './cover/rights'
import { bookmarkRights } from '../bookmark/rights'
import type { Access } from '../access'
import type { ScopeKey } from '../../features/spaces/epochs'

const held = vi.hoisted(() => ({ node: null as null | { rename: boolean; move: boolean; delete: boolean }, writable: true }))
vi.mock('../access', async (original) => {
  const real = await original<typeof import('../access')>()
  return {
    ...real,
    nodeRights: (a: Access, at: Parameters<typeof real.nodeRights>[1]) => (held.node ? { ...real.NO_NODE_RIGHTS, ...held.node } : real.nodeRights(a, at)),
    applicationWritable: () => held.writable,
  }
})

const FILE = resolve(__dirname, '../../../../../contracts/platform/plans/v1-vectors.json')
const vectors = JSON.parse(readFileSync(FILE, 'utf8')) as any

const orderOf = (kind: string): string[] => {
  const d = registry.kind(kind)
  return d.supported ? [...(d.header?.order ?? [])] : []
}

/** The actions a case's rights and own entries offer, as the web's pages and core's actions hand them to the plan. */
const offered = (c: any): NodeHeaderAction[] =>
  [...Object.keys(c.rights).filter((r) => c.rights[r]), ...c.own].map((id) => ({ id, label: id, icon: 'pencil', onClick: () => {}, shown: true }))
const ids = (l: readonly { id: string }[]) => l.map((a) => a.id)

const member = (keys: boolean, readonly: boolean): Access =>
  ({ mode: 'member', scopeId: 's', epochs: keys ? ({} as unknown as ScopeKey) : null, readonly, publicShares: true, publicSharesContribute: true, publicSharesDropBox: true, applicationsOff: new Set() }) as Access
const guest = (): Access => ({ mode: 'guest', role: 'READ_WRITE' }) as unknown as Access

function favorite(c: any): boolean {
  return favoriteRights(c.member ? member(true, false) : guest(), c.node ? { id: 'n', kind: c.known ? 'FOLDER' : 'NOT_A_KIND' } : null).favorite
}
function cover(c: any): boolean {
  held.writable = c.applicationOn
  try {
    const at = { kind: c.node ? (c.takesCover ? 'MEMORY' : 'FILE') : null, inIdentityFolder: c.inIdentityFolder }
    return coverRights(c.member ? member(c.keys, c.readonly) : guest(), at).setCover
  } finally {
    held.writable = true
  }
}
function bookmark(c: any) {
  held.node = { rename: c.rename, move: c.move, delete: c.delete }
  try {
    const r = bookmarkRights(c.member ? member(c.keys, false) : guest(), { kind: c.node ? 'SHARE_BOOKMARK' : null, inIdentityFolder: false, shareable: false })
    return { open: r.open, rename: r.rename, move: r.move, delete: r.delete }
  } finally {
    held.node = null
  }
}

describe('the plans\' vectors (contracts/platform/plans/v1-vectors.json)', () => {
  it('the registry\'s header orders are the file\'s', () => {
    expect([...registry.kinds()].sort().map((kind) => ({ kind, header: orderOf(kind) }))).toEqual(vectors.kinds)
  })

  it('every header', () => {
    for (const [i, c] of vectors.header.entries()) {
      let answer: { buttons: string[]; menu: string[]; last: string[] }
      if (c.maxButtons === undefined) {
        const p = headerPlan(offered(c), c.order)
        answer = { buttons: ids(p.buttons), menu: ids(p.menu), last: ids(p.last) }
      } else {
        const p = coreHeaderPlan(c.rights, c.order, c.own, c.maxButtons)
        answer = { buttons: [...p.buttons], menu: [...p.menu], last: [...p.last] }
      }
      expect(answer, `header ${i}: ${c.rule}`).toEqual(c.expected)
    }
  })

  it('every row', () => {
    for (const [i, c] of vectors.row.entries()) {
      const p = rowPlan(offered(c))
      expect({ menu: ids(p.menu), last: ids(p.last) }, `row ${i}: ${c.rule}`).toEqual(c.expected)
    }
  })

  it('core\'s favourite, cover and bookmark rights', () => {
    for (const [i, c] of vectors.rights.favorite.entries()) expect(favorite(c), `favorite ${i}`).toBe(c.expected)
    for (const [i, c] of vectors.rights.cover.entries()) expect(cover(c), `cover ${i}`).toBe(c.expected)
    for (const [i, c] of vectors.rights.bookmark.entries()) expect(bookmark(c), `bookmark ${i}`).toEqual(c.expected)
  })
})
