// @vitest-environment happy-dom
// A drop's move (inbox #421, drag and drop): the host's `moveTo` door runs the move dialogs' own run (`moveRun.ts`) with the
// place already chosen, over the REAL registry (Memories and Depot), core's real `NodeDialogs` and the app's real
// confirmation dialog; the session's door is a recorder (`nodeMover` stubbed: that its writes are the placement writes is
// `move.wire.test.ts`'s). What this pins: one node moves as `MoveNodeDialog` moves it and several as `MoveNodesDialog` does
// - the same check before sending, the same writes, the same words -, a refusal is said in an error toast in the dialog's
// words, a selection's left-out or refused nodes open its dialog on the very summary it would have shown, and the page
// follows what was on screen.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))
const door = vi.hoisted(() => ({ mover: null as null | import('./move').NodeMover }))
vi.mock('./move', async (orig) => ({ ...(await orig<typeof import('./move')>()), nodeMover: () => door.mover }))

import { useNodeDialogs, type NodeDialogs } from './nodeDialogs'
import NodeDialogsView from './components/NodeDialogs.vue'
import { MoveDeclined, type MoveOutcome, type NodeMover } from './move'
import { FOLDER_KIND } from './kind'
import type { TreeNode } from './tree'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { mountConfirmDialog } from '../../test/confirmDialog'

const n = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Familie (root) ─ Reisen/ ─ Rom/, bericht.pdf        (the level on screen)
//                ─ Archiv/ ─ Bericht.PDF               (a file whose name folds like the one in Reisen)
//                ─ Einsendungen (drop box) ─ Anna/     (a guest's identity folder)
const NODES = [
  n('reisen', FOLDER_KIND, null, 'Reisen'), n('rom', FOLDER_KIND, 'reisen', 'Rom'), n('file', FILE_KIND, 'reisen', 'bericht.pdf'),
  n('archiv', FOLDER_KIND, null, 'Archiv'), n('taken', FILE_KIND, 'archiv', 'Bericht.PDF'),
  n('box', DROPBOX_KIND, null, 'Einsendungen'), n('anna', FOLDER_KIND, 'box', 'Anna', { shareIdentityId: 'id-anna' }),
]
const byId = (id: string) => NODES.find((x) => x.id === id)!

type Recorder = NodeMover & { move: ReturnType<typeof vi.fn>; moveMany: ReturnType<typeof vi.fn> }
function recorder(refuse: Record<string, unknown> = {}): Recorder {
  return {
    move: vi.fn(async (nodeId: string) => {
      if (refuse[nodeId]) throw refuse[nodeId]
    }),
    moveMany: vi.fn(async (moves: readonly { nodeId: string; to: string | null }[]): Promise<MoveOutcome[]> => moves.map((m) => ({ nodeId: m.nodeId, error: refuse[m.nodeId] ?? null }))),
    refresh: vi.fn(async () => {}),
  }
}
const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })

/** A host page as the member's: core's dialogs over the tree, the level of Reisen on screen, recording what it follows. */
function mountHost(mover: Recorder) {
  door.mover = mover
  const stayOn = vi.fn()
  let dialogs: NodeDialogs | null = null
  const Host = defineComponent({
    setup() {
      dialogs = useNodeDialogs({ access: () => memberAccessOf(), tree: () => memberTreeOf(() => NODES), onScreen: () => 'reisen', rootLabel: () => 'Familie', stayOn })
      return () => h(NodeDialogsView, { dialogs: dialogs! })
    },
  })
  mount(Host, { attachTo: document.body, global: { plugins: [realI18n()] } })
  return { doors: dialogs!.doors, state: dialogs!.state, open: dialogs!.open, stayOn }
}
const summaryLines = () => [...document.body.querySelectorAll('.move-summary-lines li')].map((li) => li.textContent?.trim())
const summaryMoved = () => document.body.querySelector('.move-summary .veyq-form-copy')?.textContent?.trim() ?? ''

beforeEach(() => {
  for (const f of Object.values(toast)) f.mockClear()
})

describe('moveTo: one node, as MoveNodeDialog moves it', () => {
  it('is the single placement write; the toast says where it went with an Undo that moves it back; the page follows', async () => {
    const mover = recorder()
    const { doors, stayOn } = mountHost(mover)
    doors.moveTo([byId('file')], 'rom')
    await flushPromises()
    expect(mover.move.mock.calls).toEqual([['file', 'rom']])
    expect(mover.moveMany).not.toHaveBeenCalled()
    expect(toast.showUndo).toHaveBeenCalledOnce()
    const undo = toast.showUndo.mock.calls[0][0] as { summary: string; onUndo: () => void }
    expect(undo.summary).toBe(t('node.move.moved', { name: 'bericht.pdf', to: 'Rom' }))
    expect(stayOn).toHaveBeenCalledWith('reisen')
    undo.onUndo()
    await flushPromises()
    expect(mover.move.mock.calls[1]).toEqual(['file', 'reisen'])
    expect(document.body.querySelector('.move-dialog'), 'no dialog: the place was chosen by the drop').toBeNull()
  })

  it('to the scope root, the message names the space', async () => {
    const mover = recorder()
    mountHost(mover).doors.moveTo([byId('rom')], null)
    await flushPromises()
    expect(mover.move.mock.calls).toEqual([['rom', null]])
    expect((toast.showUndo.mock.calls[0][0] as { summary: string }).summary).toBe(t('node.move.moved', { name: 'Rom', to: 'Familie' }))
  })

  it('a name a sibling there already takes is refused before anything is sent, in the dialog\'s words', async () => {
    const mover = recorder()
    mountHost(mover).doors.moveTo([byId('file')], 'archiv')
    await flushPromises()
    expect(mover.move).not.toHaveBeenCalled()
    expect(toast.error.mock.calls).toEqual([[t('node.move.nameTakenThere', { kind: t('depot.aFile'), name: 'Bericht.PDF' })]])
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it('a refusal of the write is said by its code; a guest who declines to name itself is told nothing', async () => {
    let mover = recorder({ rom: coded(422, 'illegal_structure') })
    mountHost(mover).doors.moveTo([byId('rom')], 'archiv')
    await flushPromises()
    expect(toast.error.mock.calls).toEqual([[t('node.move.refused.illegal_structure')]])
    toast.error.mockClear()
    mover = recorder({ rom: new MoveDeclined() })
    mountHost(mover).doors.moveTo([byId('rom')], 'archiv')
    await flushPromises()
    expect(toast.error).not.toHaveBeenCalled()
    expect(toast.showUndo).not.toHaveBeenCalled()
  })

  it("a drop-box guest's folder asks first, as the dialog does; declined, nothing is sent", async () => {
    const confirm = mountConfirmDialog()
    const mover = recorder()
    mountHost(mover).doors.moveTo([byId('anna')], 'archiv')
    await flushPromises()
    expect(confirm.text()).toContain(t('node.move.identityTitle'))
    confirm.click(t('common.cancel'))
    await flushPromises()
    expect(mover.move).not.toHaveBeenCalled()
  })

  it('while its write runs, the page\'s live refresh pauses as for an open dialog, and a second drop is not taken', async () => {
    const mover = recorder()
    let finish = () => {}
    mover.move.mockImplementationOnce(() => new Promise<void>((resolve) => (finish = resolve)))
    const { doors, open } = mountHost(mover)
    expect(open.value).toBe(false)
    doors.moveTo([byId('rom')], 'archiv')
    await flushPromises()
    expect(open.value).toBe(true)
    doors.moveTo([byId('file')], 'rom')
    await flushPromises()
    expect(mover.move.mock.calls).toEqual([['rom', 'archiv']])
    finish()
    await flushPromises()
    expect(open.value).toBe(false)
  })
})

describe('moveTo: several, as MoveNodesDialog moves them', () => {
  it('N single writes in one run; what moved is said in one toast; the page follows', async () => {
    const mover = recorder()
    const { doors, stayOn } = mountHost(mover)
    doors.moveTo([byId('rom'), byId('file')], null)
    await flushPromises()
    expect(mover.moveMany.mock.calls).toEqual([[[{ nodeId: 'rom', to: null }, { nodeId: 'file', to: null }]]])
    expect(mover.move).not.toHaveBeenCalled()
    expect((toast.showUndo.mock.calls[0][0] as { summary: string }).summary).toBe(t('node.move.many.moved', { count: 2, to: 'Familie' }))
    expect(stayOn).toHaveBeenCalledWith('reisen')
    expect(document.body.querySelector('.move-dialog'), 'everything moved: no dialog').toBeNull()
  })

  it('one left out before sending and one refused: the rest moved, and the selection\'s dialog opens on its summary', async () => {
    const mover = recorder({ rom: coded(409, 'slug_taken') })
    const { doors, state } = mountHost(mover)
    const box = n('box2', FOLDER_KIND, 'reisen', 'Box')
    NODES.push(box)
    try {
      doors.moveTo([byId('rom'), byId('file'), box], 'archiv')
      await flushPromises()
    } finally {
      NODES.pop()
    }
    // bericht.pdf folds onto Bericht.PDF there: left out, never sent
    expect(mover.moveMany.mock.calls).toEqual([[[{ nodeId: 'rom', to: 'archiv' }, { nodeId: 'box2', to: 'archiv' }]]])
    expect(state.movingMany.value?.summary).toBeDefined()
    expect(summaryMoved()).toBe(t('node.move.many.moved', { count: 1, to: 'Archiv' }))
    expect(summaryLines()).toEqual([
      t('node.move.many.reason', { name: 'bericht.pdf', reason: t('node.move.nameTakenThere', { kind: t('depot.aFile'), name: 'Bericht.PDF' }) }),
      t('node.move.many.reason', { name: 'Rom', reason: t('node.move.refused.slug_taken') }),
    ])
    expect(document.body.querySelector('.move-browser'), 'the summary, not the browser').toBeNull()
  })

  it('nothing would go: said in one error toast, nothing sent', async () => {
    const mover = recorder()
    mountHost(mover).doors.moveTo([byId('file')].concat(n('file2', FILE_KIND, 'reisen', 'BERICHT.pdf')), 'archiv')
    await flushPromises()
    expect(mover.moveMany).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledOnce()
    expect(toast.error.mock.calls[0][0]).toContain(t('node.move.nameTakenThere', { kind: t('depot.aFile'), name: 'Bericht.PDF' }))
  })
})
