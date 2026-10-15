// @vitest-environment happy-dom
// Core's delete question for a level's selection (inbox #421) over the REAL registry (Memories and Depot, filled by
// `src/test/setup.ts`) and the real PrimeVue Dialog, every step a DOM click. Core's `deleteNode` / `restoreNode` and the tree
// re-read are recorders. Pinned: ONE question naming how many go and of which kinds (each kind's own noun); each node
// through its door (core's, or the kind's own `remove`); a failure named in one summary while the rest is deleted; one toast
// whose Undo restores exactly what went.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

const calls = vi.hoisted(() => ({ deleteNode: vi.fn(), restoreNode: vi.fn(), loadTree: vi.fn() }))
vi.mock('../tree', async (o) => ({ ...(await o<typeof import('../tree')>()), deleteNode: calls.deleteNode, restoreNode: calls.restoreNode }))
vi.mock('../treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: calls.loadTree }
})
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({ useToast: () => toast }))

import DeleteNodesDialog from './DeleteNodesDialog.vue'
import { FOLDER_KIND } from '../kind'
import type { TreeNode } from '../tree'
import type { SelectedForDelete } from '../selection'
import { MEMORY_KIND } from '../../../features/memory/kinds'
import { FILE_KIND } from '../../../features/depot/kinds'
import { treeNode } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'

const SCOPE = 'scope-1'
const EPOCHS = { current: 0, keys: new Map() }
const folder = treeNode({ id: 'f', kind: FOLDER_KIND, name: 'Rom' })
const folder2 = treeNode({ id: 'g', kind: FOLDER_KIND, name: 'Neapel' })
const memory = treeNode({ id: 'm', kind: MEMORY_KIND, name: 'Lissabon' })
const file = treeNode({ id: 'x', kind: FILE_KIND, name: 'a.txt' })
const remove = vi.fn(async () => {})
const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

function mountDialog(items: SelectedForDelete[], afterUndo?: () => void) {
  return mount(DeleteNodesDialog, {
    attachTo: document.body,
    props: { scopeId: SCOPE, epochs: EPOCHS, items, afterUndo },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}
async function click(label: string) {
  const b = [...document.body.querySelectorAll<HTMLButtonElement>('.delete-nodes-dialog button')].find((x) => x.textContent?.trim() === label)
  if (!b) throw new Error(`no button "${label}"`)
  b.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const text = () => document.body.querySelector('.delete-nodes-dialog')?.textContent ?? ''
const summaryLines = () => [...document.body.querySelectorAll('.delete-summary-lines li')].map((li) => li.textContent?.trim())

beforeEach(() => {
  for (const f of [...Object.values(calls), ...Object.values(toast), remove]) f.mockReset()
  calls.deleteNode.mockResolvedValue(undefined)
  calls.restoreNode.mockResolvedValue(undefined)
  calls.loadTree.mockResolvedValue(undefined)
  remove.mockResolvedValue(undefined)
})

describe('DeleteNodesDialog', () => {
  it('asks ONCE, naming how many go and each kind once, in the kinds\' own words', async () => {
    mountDialog([{ node: folder }, { node: memory }, { node: folder2 }, { node: file, remove }])
    await flushPromises()
    expect(text()).toContain(t('node.deleteMany.title', { count: 4 }))
    const kinds = `${t('node.aFolder')}, ${t('memory.aMemory')}, and ${t('depot.aFile')}`
    expect(text()).toContain(t('node.deleteMany.copy', { count: 4, kinds }))
    expect(calls.deleteNode, 'nothing before the answer').not.toHaveBeenCalled()
  })

  it('Cancel deletes nothing', async () => {
    const w = mountDialog([{ node: folder }])
    await flushPromises()
    await click(t('common.cancel'))
    expect(calls.deleteNode).not.toHaveBeenCalled()
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('Delete sends each node through its kind\'s door, re-reads once, closes, and one Undo restores exactly what went', async () => {
    const afterUndo = vi.fn()
    const w = mountDialog([{ node: folder }, { node: memory }, { node: file, remove }], afterUndo)
    await flushPromises()
    await click(t('node.delete'))
    expect(calls.deleteNode.mock.calls).toEqual([[SCOPE, 'f'], [SCOPE, 'm']])
    expect(remove, 'the file through its own door').toHaveBeenCalledWith('x')
    expect(calls.loadTree).toHaveBeenCalledTimes(1)
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toHaveLength(1)
    expect(toast.showUndo).toHaveBeenCalledTimes(1)
    expect(toast.showUndo.mock.calls[0][0].summary).toBe(t('node.deleteMany.moved', { count: 3 }, 3))
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(calls.restoreNode.mock.calls.map((c) => (c as unknown[])[3])).toEqual([folder, memory, file])
    expect(calls.loadTree).toHaveBeenCalledTimes(2)
    expect(afterUndo).toHaveBeenCalledTimes(1)
  })

  it('a failure is named with its reason and the rest is deleted; the Undo restores only what went', async () => {
    calls.deleteNode.mockImplementation(async (_s: string, id: string) => {
      if (id === 'm') throw coded(404, 'node_not_found')
    })
    const w = mountDialog([{ node: folder }, { node: memory }, { node: folder2 }])
    await flushPromises()
    await click(t('node.delete'))
    expect(calls.deleteNode).toHaveBeenCalledTimes(3)
    expect(summaryLines()).toEqual([t('node.deleteMany.reason', { name: 'Lissabon', reason: t('node.deleteMany.refused.gone') })])
    expect(text()).toContain(t('node.deleteMany.moved', { count: 2 }, 2))
    expect(w.emitted('close'), 'the summary stays until closed').toBeUndefined()
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(calls.restoreNode.mock.calls.map((c) => (c as { id: string }[])[3].id)).toEqual(['f', 'g'])
    await click(t('common.close'))
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('every one failing: nothing deleted is said, no undo is offered', async () => {
    calls.deleteNode.mockRejectedValue(new TypeError('Failed to fetch'))
    mountDialog([{ node: folder }, { node: memory }])
    await flushPromises()
    await click(t('node.delete'))
    expect(text()).toContain(t('node.deleteMany.noneDeleted'))
    expect(summaryLines()).toHaveLength(2)
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('an Undo that fails names what stayed in the trash', async () => {
    mountDialog([{ node: folder }, { node: memory }])
    await flushPromises()
    await click(t('node.delete'))
    calls.restoreNode.mockImplementation(async (...a: unknown[]) => {
      if ((a[3] as TreeNode).id === 'm') throw new Error('slug taken')
    })
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(toast.error).toHaveBeenCalledWith(t('node.deleteMany.undoFailed', { names: t('node.deleteMany.quoted', { name: 'Lissabon' }) }))
  })
})
