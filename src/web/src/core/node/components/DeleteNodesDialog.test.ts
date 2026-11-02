// @vitest-environment happy-dom
// Core's delete question for a level's selection (inbox #421) over the REAL registry (Memories and Depot, filled by
// `src/test/setup.ts`) and the real `VeyqDialog`, every step a DOM click. Core's `deleteNode` / `restoreNode` and the tree
// re-read are recorders. Pinned: ONE question naming how many go and of which kinds (each kind's own noun); each node
// through its door (core's, or the kind's own `remove`); a failure named in one summary while the rest is deleted; one toast
// whose Undo restores exactly what went.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

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
import { MEMORY_KIND } from '../../../features/memories/kinds'
import { FILE_KIND } from '../../../features/depot/kinds'
import { treeNode } from '../../../test/models'
import { realI18n, t } from '../../../test/i18n'

const SCOPE = 'scope-1'
const EPOCHS = { epoch: 0, key: new Uint8Array(32) }
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
    global: { plugins: [realI18n()] },
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

// ONE node - a row's default delete, a selection of one, and a page's own node from its header (the folder on screen, an
// opened node: the member's host, inbox #385 (c)) - is asked that node's own question (`deleteQuestion.ts`, inbox #375,
// #395), with the same Undo: a folder its own, a kind's registered `deleteTitle` / `deleteCopy`, else the neutral words.
describe('DeleteNodesDialog - one node: its kind\'s own question', () => {
  const one = (kind: string) => treeNode({ id: 'n1', kind, name: 'Rom 2026' })
  const title = () => document.body.querySelector('.delete-nodes-dialog .veyq-dialog__title')?.textContent?.trim()
  const copy = () => document.body.querySelector('.delete-nodes-dialog .dialog-copy')?.textContent?.trim()
  const asked = async (kind: string, locale?: 'en' | 'de') => {
    const w = mountDialog([{ node: one(kind) }])
    await flushPromises()
    if (locale) {
      const { i18n } = await import('../../../i18n')
      i18n.global.locale.value = locale
      await w.vm.$nextTick()
    }
    const q = { title: title(), copy: copy() }
    w.unmount()
    return q
  }

  it('titles a folder as a folder and warns about its subtree', async () => {
    const q = await asked(FOLDER_KIND)
    expect(q.title).toContain(t('node.deleteFolderTitle'))
    expect(q.copy).toBe(t('node.deleteFolderCopy', { name: 'Rom 2026' }))
  })

  it('titles a node by its kind\'s deleteTitle and says its deleteCopy - a Memory, a chapter, a drop box, a file', async () => {
    expect((await asked('MEMORY')).title).toContain('Delete Memory')
    expect((await asked('CHAPTER')).title).toContain('Delete chapter')
    expect((await asked('DEPOT_DROPBOX')).title).toContain('Delete drop box')
    expect((await asked('DEPOT_DROPBOX')).copy).toBe(t('depot.dropBox.deleteConfirm', { name: 'Rom 2026' }))
    expect((await asked('MEMORY')).copy).toBe(t('node.deleteCopy', { name: 'Rom 2026' }))
    expect((await asked('FILE')).copy).toBe(t('depot.deleteConfirm', { name: 'Rom 2026' }))
    expect((await asked('FILE')).title).toContain(t('depot.deleteTitle'))
  })

  it('falls back to the neutral "Delete" for a kind nobody registered', async () => {
    const q = await asked('SOME_NEWER_KIND')
    expect(q.title).toContain(t('node.delete'))
    expect(q.title).not.toMatch(/Memory/)
  })

  // inbox #385 (d): every node delete is a move to the space's trash, so the copy says that - never that it cannot be
  // undone - in both languages, with the name in the language's own quotes.
  it('says the node moves to the trash, never that the delete cannot be undone, in both languages', async () => {
    const { i18n } = await import('../../../i18n')
    for (const [locale, trash, quoted, never] of [
      ['en', 'trash', '“Rom 2026”', 'undone'],
      ['de', 'Papierkorb', '„Rom 2026“', 'rückgängig'],
    ] as const) {
      for (const kind of [FOLDER_KIND, 'MEMORY']) {
        const q = await asked(kind, locale)
        expect(q.copy, `${locale} ${kind}`).toContain(trash)
        expect(q.copy).toContain(quoted)
        expect(q.copy).not.toContain(never)
      }
    }
    i18n.global.locale.value = 'en'
  })

  it('deletes it through core\'s door, closes, and offers ONE Undo that restores it', async () => {
    const w = mountDialog([{ node: one('MEMORY') }])
    await flushPromises()
    await click(t('node.delete'))
    expect(calls.deleteNode).toHaveBeenCalledWith(SCOPE, 'n1')
    expect(w.emitted('done')).toHaveLength(1)
    expect(w.emitted('close')).toHaveLength(1)
    expect(toast.showUndo).toHaveBeenCalledOnce()
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(calls.restoreNode.mock.calls.map((c) => (c[3] as TreeNode).id)).toEqual(['n1'])
  })
})
