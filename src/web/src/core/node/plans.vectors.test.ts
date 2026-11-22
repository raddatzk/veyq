// @vitest-environment happy-dom
// A node header's and a level row's plans and core's favourite, cover and bookmark rights against
// contracts/platform/plans/v1-vectors.json (ADR 0154 decision 2, ADR 0155 decision 7.2's third slice). Phase 2 of
// cr-web-rights-and-plans-on-the-core: the web's TypeScript answers every case it can express (a header with no rename,
// cover, offline or send and every button fitting; a row with no share or offline), so the file is the web's answer before
// anything moves. `VEYQ_WRITE_VECTORS=1` writes the file from the inputs below (this phase's writer; the core's
// `PlanVectorsTest` takes the write over in phase 3).
import { describe, expect, it, vi } from 'vitest'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { registry } from '../applications/registry'
import { mergeHeaderActions, orderHeaderActions, partitionHeaderActions, type CoreHeaderAction, type NodeHeaderAction } from './nodeHeader'
import { mergeRowActions, splitRowActions, type CoreRowAction, type LevelRowAction } from './levelRow'
import { withStandardRowActions } from './standardRowActions'
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
const writing = process.env.VEYQ_WRITE_VECTORS === '1'

type Rights = Record<'favorite' | 'share' | 'rename' | 'move' | 'cover' | 'copy' | 'download' | 'report' | 'delete' | 'offline', boolean>
const RIGHTS = ['favorite', 'share', 'rename', 'move', 'cover', 'copy', 'download', 'report', 'delete', 'offline'] as const
const rights = (...on: (keyof Rights)[]): Rights => Object.fromEntries(RIGHTS.map((r) => [r, on.includes(r)])) as Rights
const ALL = ['favorite', 'share', 'move', 'copy', 'download', 'report', 'delete'] as const

const KINDS = ['DEPOT_DROPBOX', 'CHAPTER', 'EXPENSES_POT', 'FILE', 'FOLDER', 'MEMORY', 'SHARE_BOOKMARK'].sort()
const orderOf = (kind: string): string[] => {
  const d = registry.kind(kind)
  return d.supported ? [...(d.header?.order ?? [])] : []
}

// The cases' inputs (this phase's; the file holds them from phase 3 on).
const HEADER: { rule: string; rights: Rights; order: string; own: string[]; maxButtons?: number }[] = [
  { rule: 'a member\'s folder: core\'s around the standard ones, in the kind\'s order', rights: rights(...ALL), order: 'FOLDER', own: [] },
  { rule: 'a Memory: its page\'s upload, banner and music among them; the banner in the last group', rights: rights(...ALL), order: 'MEMORY', own: ['upload', 'banner', 'music'] },
  { rule: 'a Memory whose banner is drawn already: no banner entry', rights: rights(...ALL), order: 'MEMORY', own: ['upload', 'music'] },
  { rule: 'a chapter: the Memory\'s order', rights: rights(...ALL), order: 'CHAPTER', own: ['upload', 'banner'] },
  { rule: 'a file: its replace beside the share', rights: rights(...ALL), order: 'FILE', own: ['replace'] },
  { rule: 'a drop box', rights: rights(...ALL), order: 'DEPOT_DROPBOX', own: [] },
  { rule: 'a Pot', rights: rights(...ALL), order: 'EXPENSES_POT', own: [] },
  { rule: 'a kept link that reads: its own page first, its name\'s report then its share\'s, the delete last', rights: rights('favorite', 'move', 'report', 'delete'), order: 'SHARE_BOOKMARK', own: ['open-own-page', 'report-shared'] },
  { rule: 'a kept link whose link does not read: its name\'s report alone', rights: rights('favorite', 'move', 'report', 'delete'), order: 'SHARE_BOOKMARK', own: [] },
  { rule: 'a kept link on an instance that takes no report: its share\'s report still', rights: rights('favorite', 'move', 'delete'), order: 'SHARE_BOOKMARK', own: ['open-own-page', 'report-shared'] },
  { rule: 'a READ guest\'s folder: the download and the report', rights: rights('download', 'report'), order: 'FOLDER', own: [] },
  { rule: 'a READ_WRITE guest\'s folder: the move too', rights: rights('move', 'download', 'report'), order: 'FOLDER', own: [] },
  { rule: 'a space pending deletion: what reads, and the share (listing links is a read)', rights: rights('favorite', 'share', 'download', 'report'), order: 'FOLDER', own: [] },
  { rule: 'a space\'s top level: no node, no order', rights: rights('share'), order: '', own: [] },
  { rule: 'nothing granted: nothing offered', rights: rights(), order: 'FOLDER', own: [] },
  { rule: 'an order lists the actions it names first, every other one follows in the merge order', rights: rights(...ALL), order: 'SHARE_BOOKMARK', own: ['music', 'upload'] },
  // the apps' alone until the web draws the plan (phase 5): a menu entry, a bar of a few buttons
  { rule: 'an app\'s folder: rename, cover and "Available offline" in the menu, two buttons fit', rights: rights(...ALL, 'rename', 'cover', 'offline'), order: 'FOLDER', own: [], maxButtons: 2 },
  { rule: 'a phone\'s file: send in the menu, three buttons fit', rights: rights(...ALL, 'rename'), order: 'FILE', own: ['replace', 'send'], maxButtons: 3 },
  { rule: 'no button fits: every one in the menu, in order, the last group apart', rights: rights(...ALL), order: 'MEMORY', own: ['upload', 'banner', 'music'], maxButtons: 0 },
]

const ROW: { rule: string; rights: Rights; own: string[] }[] = [
  { rule: 'a folder\'s row: rename and move first, core\'s after the heart and the cover, report and delete last', rights: rights('favorite', 'rename', 'move', 'cover', 'copy', 'download', 'report', 'delete'), own: [] },
  { rule: 'a file\'s row: no cover', rights: rights('favorite', 'rename', 'move', 'copy', 'download', 'report', 'delete'), own: [] },
  { rule: 'a kept link\'s row that reads: its share\'s report after its name\'s', rights: rights('favorite', 'rename', 'move', 'report', 'delete'), own: ['report-shared'] },
  { rule: 'a READ_WRITE guest\'s row: the move, the download, the report', rights: rights('move', 'download', 'report'), own: [] },
  { rule: 'a READ guest\'s row', rights: rights('download', 'report'), own: [] },
  { rule: 'a space pending deletion', rights: rights('favorite', 'download', 'report'), own: [] },
  { rule: 'nothing granted', rights: rights(), own: [] },
  // the apps' alone (a web row offers no share and no offline store)
  { rule: 'an app\'s row: the share among the standard ones', rights: rights('favorite', 'share', 'rename', 'move', 'cover', 'copy', 'download', 'report', 'delete'), own: [] },
  { rule: 'an app\'s row of a node kept offline', rights: rights('favorite', 'rename', 'move', 'download', 'offline', 'report', 'delete'), own: [] },
]

const bits = (n: number, k: number): boolean[] => Array.from({ length: k }, (_, i) => ((n >> (k - 1 - i)) & 1) === 1)

const vectors: any = existsSync(FILE) && !writing ? JSON.parse(readFileSync(FILE, 'utf8')) : null

// The web's TypeScript, building each action as its pages and `coreNodeActions.ts` build them today.
const noop = () => {}
const act = (id: string, extra: Partial<CoreHeaderAction> = {}): CoreHeaderAction => ({ id, label: id, icon: 'pencil', onClick: noop, shown: true, ...extra })

function webHeader(c: Pick<(typeof HEADER)[number], 'rights' | 'own'>, order: readonly string[]) {
  const r = c.rights
  const core: CoreHeaderAction[] = [
    ...(r.favorite ? [act('favorite', { placement: 'before' })] : []),
    ...(r.copy ? [act('copy', { placement: { after: 'move' }, menuOnly: true })] : []),
    ...(r.download ? [act('download', { placement: 'after' })] : []),
    ...(r.report ? [act('report', { placement: 'after', menuOnly: true })] : []),
    ...(c.own.includes('report-shared') ? [act('report-shared', { placement: 'after', menuOnly: true })] : []),
  ]
  const app: NodeHeaderAction[] = [
    ...(r.share ? [act('share')] : []),
    ...(r.move ? [act('move', { menuOnly: true })] : []),
    ...(r.delete ? [act('delete', { severity: 'danger' })] : []),
    ...c.own.filter((id) => id !== 'report-shared').map((id) => act(id, id === 'banner' ? { menuOnly: true } : {})),
  ]
  const parts = partitionHeaderActions(orderHeaderActions(mergeHeaderActions(core, app).filter((a) => a.shown), order))
  return { buttons: parts.buttons.map((a) => a.id), menu: [] as string[], last: parts.menuOnly.map((a) => a.id) }
}

function webRow(c: (typeof ROW)[number]) {
  const r = c.rights
  const core: CoreRowAction[] = [
    ...(r.favorite ? [act('favorite', { placement: 'before' })] : []),
    ...(r.cover ? [act('cover', { placement: 'after' })] : []),
    ...(r.copy ? [act('copy', { placement: { after: 'move' } })] : []),
    ...(r.download ? [act('download', { placement: 'after' })] : []),
    ...(r.report ? [act('report', { placement: 'after', menuOnly: true })] : []),
    ...(c.own.includes('report-shared') ? [act('report-shared', { placement: 'after', menuOnly: true })] : []),
  ]
  const standard: LevelRowAction[] = [
    ...(r.rename ? [act('rename')] : []),
    ...(r.move ? [act('move')] : []),
    ...(r.delete ? [act('delete', { severity: 'danger' })] : []),
  ]
  const split = splitRowActions(mergeRowActions(core, withStandardRowActions([], standard)))
  return { menu: split.menu.map((a) => a.id), last: split.menuOnly.map((a) => a.id) }
}

const webHeaderCase = (c: Pick<(typeof HEADER)[number], 'rights' | 'own' | 'maxButtons'>) => !c.rights.rename && !c.rights.cover && !c.rights.offline && !c.own.includes('send') && c.maxButtons === undefined
const webRowCase = (c: (typeof ROW)[number]) => !c.rights.share && !c.rights.offline

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

function record() {
  const kinds = KINDS.map((kind) => ({ kind, header: orderOf(kind) }))
  const header = HEADER.map((c) => ({
    rule: c.rule, rights: c.rights, order: c.order ? orderOf(c.order) : [], own: c.own, ...(c.maxButtons !== undefined ? { maxButtons: c.maxButtons } : {}),
    expected: webHeaderCase(c) ? webHeader(c, c.order ? orderOf(c.order) : []) : null,
  }))
  const row = ROW.map((c) => ({ rule: c.rule, rights: c.rights, own: c.own, expected: webRowCase(c) ? webRow(c) : null }))
  const fav = Array.from({ length: 8 }, (_, n) => { const [m, node, known] = bits(n, 3); const c = { member: m, node, known }; return { ...c, expected: favorite(c) } })
  const cov = Array.from({ length: 128 }, (_, n) => {
    const [m, keys, readonly, node, inIdentityFolder, applicationOn, takesCover] = bits(n, 7)
    const c = { member: m, keys, readonly, node, inIdentityFolder, applicationOn, takesCover }
    return { ...c, expected: cover(c) }
  })
  const bm = Array.from({ length: 64 }, (_, n) => {
    const [m, node, keys, rename, move, del] = bits(n, 6)
    const c = { member: m, node, keys, rename, move, delete: del }
    return { ...c, expected: bookmark(c) }
  })
  return {
    format: 'veyq-plans-v1-vectors',
    note: 'Conformance vectors for ADR 0154\'s node plans and core\'s own rights over a node: each kind\'s header order (`kinds`), a node header\'s plan (`header`: the rights its page and core grant, the kind\'s order, the page\'s own entries and, on an app, how many buttons fit (`maxButtons`, absent: all) -> buttons, the menu, the menu\'s last group), a level row\'s menu (`row`: rights and own entries -> the menu and its last group), and core\'s favourite, cover and bookmark rights (`rights`, every combination of their inputs). Entries are `HeaderAction`\'s serial names. Each case names its rule (`rule`). Inputs chosen by hand; every `expected` first recorded from the web\'s TypeScript, where it can express the case, and read against its rule before commit.',
    kinds,
    header,
    row,
    rights: { favorite: fav, cover: cov, bookmark: bm },
  }
}

describe('the plans\' vectors (contracts/platform/plans/v1-vectors.json)', () => {
  it.runIf(writing)('writes the vectors from the web\'s answers', () => {
    writeFileSync(FILE, JSON.stringify(record(), null, 2) + '\n')
  })

  it.skipIf(writing)('the registry\'s header orders are the file\'s', () => {
    expect(KINDS.map((kind) => ({ kind, header: orderOf(kind) }))).toEqual(vectors.kinds)
  })

  it.skipIf(writing)('every header the web can express', () => {
    for (const [i, c] of vectors.header.entries()) if (webHeaderCase(c)) expect(webHeader(c, c.order), `header ${i}: ${c.rule}`).toEqual(c.expected)
  })

  it.skipIf(writing)('every row the web can express', () => {
    for (const [i, c] of vectors.row.entries()) if (webRowCase(c)) expect(webRow(c), `row ${i}: ${c.rule}`).toEqual(c.expected)
  })

  it.skipIf(writing)('core\'s favourite, cover and bookmark rights', () => {
    for (const [i, c] of vectors.rights.favorite.entries()) expect(favorite(c), `favorite ${i}`).toBe(c.expected)
    for (const [i, c] of vectors.rights.cover.entries()) expect(cover(c), `cover ${i}`).toBe(c.expected)
    for (const [i, c] of vectors.rights.bookmark.entries()) expect(bookmark(c), `bookmark ${i}`).toEqual(c.expected)
  })
})
