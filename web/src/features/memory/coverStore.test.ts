// The shared cover store (this CR) — the two properties that rot first, both bitten by injection because
// neither defect is live: the store is new, so there is nothing to redden against on the untouched tree.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Typed by the real function (inbox #287): an untyped `vi.fn()` took a `{ x, y, w, h }` crop `CoverCrop` does not have.
const loadNodeCover = vi.hoisted(() => vi.fn<typeof import('./memoryCover').loadNodeCover>())
vi.mock('./memoryCover', async (orig) => ({ ...(await orig<typeof import('./memoryCover')>()), loadNodeCover }))
vi.mock('../../core/node/tree', async (orig) => ({
  ...(await orig<typeof import('../../core/node/tree')>()),
  nodeContentKey: vi.fn(),
}))
// activeSpace is imported for `scopeId` + `activeEpochKeys` only — keep the real module out of the way of a
// test that is about the cache, not about opening a scope.
const space = vi.hoisted(() => ({ scopeId: 'scope-1' as string | null, epochs: {} as unknown }))
// The REAL module is kept (so `clearActiveSpace` is the real one, calling the real `resetCovers`); only the
// two readers the store uses are overridden, because opening a scope is not what this file is about.
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  activeSpace: space,
  activeEpochKeys: () => space.epochs,
}))

import { covers, ensureCovers, resetCovers } from './coverStore'
import { treeStore, patchNodeMeta } from '../../core/node/treeStore'
import { nodeContentKey } from '../../core/node/tree'

const revoked: string[] = []
let minted = 0

beforeEach(() => {
  revoked.length = 0
  minted = 0
  vi.stubGlobal('URL', {
    createObjectURL: () => `blob:cover-${++minted}`,
    revokeObjectURL: (u: string) => revoked.push(u),
  })
  space.scopeId = 'scope-1'
  space.epochs = {}
  treeStore.nodes = [
    { id: 'n1', name: 'Rom', kind: 'MEMORY', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'k', wrappedMeta: 'meta-1' },
  ] as unknown as typeof treeStore.nodes
  // A key is always openable here; the un-tried rule is its own case.
  vi.mocked(nodeContentKey).mockImplementation(() => new Uint8Array(32).fill(7))
  loadNodeCover.mockReset()
  loadNodeCover.mockImplementation(async () => ({ blob: new Blob([new Uint8Array([1])]), crop: { x: 0, y: 0, width: 1, height: 1 } }))
  resetCovers()
})
afterEach(() => vi.unstubAllGlobals())

/** Let the store's fire-and-forget resolve settle. */
const settle = () => new Promise((r) => setTimeout(r, 0))

describe('coverStore', () => {
  it('resolves a node once and does not re-decrypt it on every pass', async () => {
    ensureCovers(['n1'])
    await settle()
    expect(loadNodeCover).toHaveBeenCalledTimes(1)
    expect(covers.value.get('n1')?.url).toBe('blob:cover-1')

    ensureCovers(['n1'])
    ensureCovers(['n1'])
    await settle()
    expect(loadNodeCover).toHaveBeenCalledTimes(1)
  })

  // The staleness trap the extraction introduces. A per-visit cache hid this: the browse grid unmounted when a
  // Memory opened, so changing the cover in the detail header and going back re-resolved by accident. A
  // module-level store outlives that. Lever: key the entry on `nodeId` alone — the new cover never appears.
  it('re-resolves when the node’s sealed meta changes, and shows the new cover', async () => {
    ensureCovers(['n1'])
    await settle()
    expect(covers.value.get('n1')?.url).toBe('blob:cover-1')

    patchNodeMeta('n1', 'meta-2', 1) // exactly what a cover edit already does — no new signal to forget
    ensureCovers(['n1'])
    await settle()

    expect(loadNodeCover).toHaveBeenCalledTimes(2)
    expect(covers.value.get('n1')?.url).toBe('blob:cover-2')
    expect(revoked).toContain('blob:cover-1') // …and the superseded one is released, not leaked
  })

  it('a node whose cover was REMOVED stops showing one', async () => {
    ensureCovers(['n1'])
    await settle()
    loadNodeCover.mockResolvedValue(null) // the cover is gone (or the chosen media was deleted — E2)
    patchNodeMeta('n1', 'meta-2', 1)
    ensureCovers(['n1'])
    await settle()

    expect(covers.value.has('n1')).toBe(false)
    expect(revoked).toContain('blob:cover-1')
  })

  it('leaves a node un-tried while its K_mem is not openable, so a later pass retries', async () => {
    space.epochs = null // no epoch keys → nothing can be opened yet
    ensureCovers(['n1'])
    await settle()
    expect(loadNodeCover).not.toHaveBeenCalled()

    space.epochs = {}
    ensureCovers(['n1'])
    await settle()
    expect(loadNodeCover).toHaveBeenCalledTimes(1) // remembered as "not yet", never as "has no cover"
  })

  // The reset property. Asserted through `clearActiveSpace`, which is the cheapest of the three call sites that
  // share this line — `enterScope` and `refreshActiveScope` carry the same call, and driving a scope open would
  // mock far more than it proves. Lever: delete `resetCovers()` from `clearActiveSpace` and this reddens.
  it('releases every decrypted URL when the active space is cleared', async () => {
    ensureCovers(['n1'])
    await settle()
    expect(covers.value.size).toBe(1)

    const { clearActiveSpace } = await import('../../app/activeSpace')
    clearActiveSpace()

    expect(covers.value.size).toBe(0)
    expect(revoked).toContain('blob:cover-1')
  })
})
