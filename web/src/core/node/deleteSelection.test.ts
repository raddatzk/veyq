// Deleting a level's selection (inbox #421, `deleteSelection.ts`): each node through the door its single delete uses (core's
// `deleteNode`, or the kind's own `remove`), one after the other, the tree re-read ONCE; a failure stops nothing; the undo
// restores exactly what it is handed through core's trash restore, again with one re-read. The writes themselves are mocked
// here - a delete and a restore on the wire are `tree.wire.test.ts`'s.
import { describe, it, expect, vi, beforeEach } from 'vitest'

const calls = vi.hoisted(() => ({ deleteNode: vi.fn(async () => {}), restoreNode: vi.fn(async () => {}) }))
vi.mock('./tree', async (o) => ({ ...(await o<typeof import('./tree')>()), deleteNode: calls.deleteNode, restoreNode: calls.restoreNode }))

import { deleteRefusalOf, deleteSelection, restoreSelection } from './deleteSelection'
import type { EpochKeys } from '../../features/spaces/epochs'
import { treeNode } from '../../test/models'

const SCOPE = 'scope-1'
const folder = treeNode({ id: 'f', kind: 'FOLDER', name: 'Rom' })
const memory = treeNode({ id: 'm', kind: 'MEMORY', name: 'Lissabon' })
const file = treeNode({ id: 'x', kind: 'FILE', name: 'a.txt' })
const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

beforeEach(() => {
  for (const f of Object.values(calls)) f.mockReset().mockResolvedValue(undefined)
})

describe('deleteSelection', () => {
  it('sends each node through its door, in order, and re-reads the tree once after the last', async () => {
    const order: string[] = []
    calls.deleteNode.mockImplementation(async (...a: unknown[]) => void order.push(`core:${a[1]}`))
    const remove = vi.fn(async (id: string) => void order.push(`kind:${id}`))
    const reload = vi.fn(async () => void order.push('reload'))
    const out = await deleteSelection(SCOPE, [{ node: folder }, { node: file, remove }, { node: memory }], reload)
    expect(order).toEqual(['core:f', 'kind:x', 'core:m', 'reload'])
    expect(calls.deleteNode).toHaveBeenCalledWith(SCOPE, 'f')
    expect(out).toEqual([{ node: folder, error: null }, { node: file, error: null }, { node: memory, error: null }])
  })

  it('a failure is kept with its node and the rest is still deleted; the re-read still happens once', async () => {
    const boom = coded(404, 'node_not_found')
    calls.deleteNode.mockRejectedValueOnce(boom)
    const reload = vi.fn(async () => {})
    const out = await deleteSelection(SCOPE, [{ node: folder }, { node: memory }], reload)
    expect(calls.deleteNode).toHaveBeenCalledTimes(2)
    expect(out).toEqual([{ node: folder, error: boom }, { node: memory, error: null }])
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

describe('restoreSelection (the Undo)', () => {
  it('restores exactly the nodes it is handed, against the tree as it is then, and re-reads once; a failure stops nothing', async () => {
    const epochs: EpochKeys = { current: 0, keys: new Map() }
    const now = [treeNode({ id: 'parent' })]
    const boom = new Error('slug taken')
    calls.restoreNode.mockRejectedValueOnce(boom)
    const reload = vi.fn(async () => {})
    const out = await restoreSelection(SCOPE, epochs, [folder, file], () => now, reload)
    expect(calls.restoreNode.mock.calls).toEqual([[SCOPE, epochs, now, folder], [SCOPE, epochs, now, file]])
    expect(out).toEqual([{ node: folder, error: boom }, { node: file, error: null }])
    expect(reload).toHaveBeenCalledTimes(1)
  })
})

describe('deleteRefusalOf', () => {
  it('names a node that is gone and a space being deleted; anything else failed', async () => {
    expect(await deleteRefusalOf(coded(404, 'node_not_found'))).toBe('gone')
    expect(await deleteRefusalOf(coded(409, 'scope_deleting'))).toBe('scope_deleting')
    expect(await deleteRefusalOf(new TypeError('Failed to fetch'))).toBe('failed')
  })
})
