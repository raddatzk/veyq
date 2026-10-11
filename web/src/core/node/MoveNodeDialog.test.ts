// @vitest-environment happy-dom
// Core's move dialog (inbox #358) over the REAL registry (Memories and Files, filled by `src/test/setup.ts`), the real
// PrimeVue Dialog (teleported to the body) and the app's real confirmation dialog, every pick and answer a DOM click.
// The door is a recorder (`NodeMover`): which session moves through what is `move.test.ts`'s; the request body on the
// wire `MoveNodeDialog.wire.test.ts`'s. What this pins: what the picker offers, the sibling pre-check, every server
// refusal explained by its name, the drop-box question, and the undo.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))

import MoveNodeDialog from './MoveNodeDialog.vue'
import type { NodeMover } from './move'
import { FOLDER_KIND } from './kind'
import type { TreeNode } from './tree'
import { PlacementConflictError } from './tree'
import { FILE_KIND, DROPBOX_KIND } from '../../features/files/kinds'
import { memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { mountConfirmDialog } from '../../test/confirmDialog'

const n = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Familie (root) ─ Reisen/ ─ Rom/ ─ bericht.pdf
//                ─ Archiv/ ─ Bericht.PDF       (a file whose name folds like the one moved)
//                ─ Einsendungen (drop box) ─ Anna/ (a guest's identity folder) ─ foto.jpg
const NODES = [
  n('reisen', FOLDER_KIND, null, 'Reisen'), n('rom', FOLDER_KIND, 'reisen', 'Rom'), n('file', FILE_KIND, 'rom', 'bericht.pdf'),
  n('archiv', FOLDER_KIND, null, 'Archiv'), n('taken', FILE_KIND, 'archiv', 'Bericht.PDF'),
  n('box', DROPBOX_KIND, null, 'Einsendungen'), n('anna', FOLDER_KIND, 'box', 'Anna', { shareIdentityId: 'id-anna' }),
  n('foto', FILE_KIND, 'anna', 'foto.jpg'),
]
const byId = (id: string) => NODES.find((x) => x.id === id)!

function recorder(): NodeMover & { move: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> } {
  return { move: vi.fn(async () => {}), refresh: vi.fn(async () => {}) }
}

function mountDialog(node: TreeNode, mover: NodeMover, afterUndo?: (id: string) => void) {
  return mount(MoveNodeDialog, {
    attachTo: document.body,
    props: { node, access: memberAccessOf(), tree: memberTreeOf(() => NODES), mover, rootLabel: 'Familie', afterUndo },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}
type W = ReturnType<typeof mountDialog>
const rowLabels = () => [...document.body.querySelectorAll<HTMLLabelElement>('.move-destination')]
const row = (name: string) => rowLabels().find((l) => l.querySelector('.move-destination-name')?.textContent === name)!
const radio = (name: string) => row(name).querySelector<HTMLInputElement>('input[type=radio]')!
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const submitButton = () => [...document.body.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === t('node.move.submit'))!
const errorText = () => document.body.querySelector('.p-message-error')?.textContent?.trim() ?? ''
async function moveTo(name: string) {
  await click(radio(name))
  await click(submitButton())
}

beforeEach(() => {
  for (const f of Object.values(toast)) f.mockClear()
})

describe('MoveNodeDialog: the destinations', () => {
  it('lists the scope root and every folder; itself, below it, where it is and the drop box are not to be picked', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'Rom' }))
    const listed = rowLabels().map((l) => [l.querySelector('.move-destination-name')!.textContent, l.querySelector('input')!.disabled, l.querySelector('.move-destination-why')?.textContent ?? ''])
    expect(listed).toEqual([
      ['Familie', false, ''],
      ['Archiv', false, ''],
      ['Reisen', true, t('node.move.blocked.current')],
      ['Rom', true, t('node.move.blocked.self')],
    ])
    expect(submitButton().disabled, 'nothing picked yet').toBe(true)
  })

  it('a pick and Move send the node to the picked folder, then close with a toast and its Undo', async () => {
    const mover = recorder()
    const afterUndo = vi.fn()
    const w = mountDialog(byId('rom'), mover, afterUndo)
    await flushPromises()
    await moveTo('Archiv')
    expect(mover.move).toHaveBeenCalledWith('rom', 'archiv')
    expect(w.emitted('moved')).toEqual([[{ node: byId('rom'), from: 'reisen', to: 'archiv' }]])
    expect(w.emitted('close')).toHaveLength(1)
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.moved', { name: 'Rom', to: 'Archiv' }) }))
    // Undo moves it back where it was, through the same door, and tells the host
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(mover.move).toHaveBeenLastCalledWith('rom', 'reisen')
    expect(afterUndo).toHaveBeenCalledWith('rom')
  })

  it('the scope root is a destination for a member', async () => {
    const mover = recorder()
    mountDialog(byId('rom'), mover)
    await flushPromises()
    await moveTo('Familie')
    expect(mover.move).toHaveBeenCalledWith('rom', null)
  })

  it('an Undo that fails says so', async () => {
    const mover = recorder()
    mountDialog(byId('rom'), mover)
    await flushPromises()
    await moveTo('Archiv')
    mover.move.mockRejectedValueOnce(new Error('offline'))
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(toast.error).toHaveBeenCalledWith(t('node.move.undoFailed', { name: 'Rom' }))
  })
})

describe('MoveNodeDialog: refused before sending', () => {
  it('names the sibling at the destination whose name folds to the same (a file\'s folding), and sends nothing', async () => {
    const mover = recorder()
    mountDialog(byId('file'), mover)
    await flushPromises()
    await moveTo('Archiv')
    expect(errorText()).toBe(t('node.move.nameTakenThere', { kind: t('files.aFile'), name: 'Bericht.PDF' }))
    expect(mover.move).not.toHaveBeenCalled()
  })
})

describe('MoveNodeDialog: every server refusal explained by its name', () => {
  const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })
  it.each([
    [409, 'slug_taken'], [422, 'illegal_structure'], [422, 'illegal_scope'], [409, 'stale_epoch'],
    [403, 'share_not_contributable'], [429, 'rate_limited'], [409, 'scope_deleting'],
  ])('%s %s', async (status, code) => {
    const mover = recorder()
    mover.move.mockRejectedValueOnce(coded(status, code))
    const w = mountDialog(byId('rom'), mover)
    await flushPromises()
    await moveTo('Archiv')
    expect(errorText()).toBe(t(`node.move.refused.${code}`))
    expect(w.emitted('close'), 'the dialog stays for another pick').toBeUndefined()
    expect(mover.refresh).not.toHaveBeenCalled()
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('a lost race (stale_placement, or the bounded retry giving up) re-reads the tree and says to try again', async () => {
    for (const e of [coded(409, 'stale_placement'), new PlacementConflictError('rom')]) {
      const mover = recorder()
      mover.move.mockRejectedValueOnce(e)
      const w = mountDialog(byId('rom'), mover)
      await flushPromises()
      await moveTo('Archiv')
      expect(errorText()).toBe(t('node.move.refused.stale_placement'))
      expect(mover.refresh).toHaveBeenCalledTimes(1)
      w.unmount()
    }
  })

  it('a node or link that is gone, and anything unexpected', async () => {
    for (const [e, key] of [[coded(404, 'node_not_found'), 'gone'], [new TypeError('Failed to fetch'), 'failed']] as const) {
      const mover = recorder()
      mover.move.mockRejectedValueOnce(e)
      const w = mountDialog(byId('rom'), mover)
      await flushPromises()
      await moveTo('Archiv')
      expect(errorText()).toBe(t(`node.move.refused.${key}`))
      w.unmount()
    }
  })
})

// ADR 0080 decision 11, inbox #377 (a): a member's move of a guest's folder out of a drop box ends its identity.
describe('MoveNodeDialog: a guest\'s folder out of a drop box', () => {
  it('asks first, saying the folder becomes ordinary and the guest can add nothing; declined, nothing moves', async () => {
    const confirm = mountConfirmDialog()
    const mover = recorder()
    mountDialog(byId('anna'), mover)
    await flushPromises()
    expect(rowLabels().map((l) => l.querySelector('.move-destination-name')!.textContent), 'no drop box, no identity folder offered')
      .toEqual(['Familie', 'Archiv', 'Reisen', 'Rom'])
    await moveTo('Archiv')
    expect(confirm.text()).toContain(t('node.move.identityTitle'))
    expect(confirm.text()).toContain(t('node.move.identityConfirm', { name: 'Anna' }))
    confirm.click(t('common.cancel'))
    await flushPromises()
    expect(mover.move).not.toHaveBeenCalled()
  })

  it('accepted, it moves out, and the toast offers no undo (nothing moves back into a drop box)', async () => {
    const confirm = mountConfirmDialog()
    const mover = recorder()
    mountDialog(byId('anna'), mover)
    await flushPromises()
    await moveTo('Archiv')
    confirm.click(t('node.move.identityAccept'))
    await flushPromises()
    expect(mover.move).toHaveBeenCalledWith('anna', 'archiv')
    expect(toast.success).toHaveBeenCalledWith(t('node.move.moved', { name: 'Anna', to: 'Archiv' }))
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('a file inside a guest\'s folder moves out without the question (the folder keeps its identity)', async () => {
    const mover = recorder()
    mountDialog(byId('foto'), mover)
    await flushPromises()
    await moveTo('Archiv')
    expect(mover.move).toHaveBeenCalledWith('foto', 'archiv')
    expect(toast.showUndo).toHaveBeenCalled()
  })
})

describe('MoveNodeDialog: nothing to pick', () => {
  it('says so when no destination takes the node', async () => {
    mountDialog(n('x', 'UNKNOWN_KIND', null, 'X'), recorder())
    await flushPromises()
    expect(document.body.textContent).toContain(t('node.move.nothing'))
  })

  it('a guest who declines to name itself: nothing is said, nothing moved', async () => {
    const { MoveDeclined } = await import('./move')
    const mover = recorder()
    mover.move.mockRejectedValueOnce(new MoveDeclined())
    const w: W = mountDialog(byId('rom'), mover)
    await flushPromises()
    await moveTo('Archiv')
    expect(errorText()).toBe('')
    expect(w.emitted('moved')).toBeUndefined()
  })
})
