// @vitest-environment happy-dom
// Core's move dialog for a level's selection (inbox #421) over the REAL registry (Memories and Depot, filled by
// `src/test/setup.ts`), the real `VeyqDialog` and the app's real confirmation dialog, every step a DOM click. The door
// is a recorder (`NodeMover.moveMany`): that it is N single-node writes and one re-read is `move.test.ts`' and
// `move.wire.test.ts`'s. What this pins: the browser blocks a place ANY selected node cannot take, naming it; the
// pre-check names and leaves out what cannot go and sends the rest; a server refusal part-way is named in one summary while
// the rest moved; one toast whose Undo moves back exactly what moved; a drop-box guest's folders asked about once, no undo.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))

import MoveNodesDialog from './MoveNodesDialog.vue'
import { MoveDeclined, type MoveOutcome, type NodeMover } from './move'
import { FOLDER_KIND } from './kind'
import type { TreeNode } from './tree'
import type { Access, TreeSource } from '../access'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { guestAccessOf, memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { mountConfirmDialog } from '../../test/confirmDialog'
import { authenticatedCalls } from '../../test/guestMode'
import { crumbs, goTo, moveHere, moveHereButton, places, whyNotHere } from '../../test/moveBrowser'

const n = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Familie (root) ─ Reisen/ ─ Rom/, Neapel/, bericht.pdf, X (a kind nobody registered)
//                ─ Archiv/ ─ Bericht.PDF       (a file whose name folds like the one in Reisen)
//                ─ Einsendungen (drop box) ─ Anna/, Ben/ (guests' identity folders)
const NODES = [
  n('reisen', FOLDER_KIND, null, 'Reisen'), n('rom', FOLDER_KIND, 'reisen', 'Rom'), n('neapel', FOLDER_KIND, 'reisen', 'Neapel'),
  n('file', FILE_KIND, 'reisen', 'bericht.pdf'), n('x', 'UNKNOWN_KIND', 'reisen', 'X'),
  n('archiv', FOLDER_KIND, null, 'Archiv'), n('taken', FILE_KIND, 'archiv', 'Bericht.PDF'),
  n('box', DROPBOX_KIND, null, 'Einsendungen'),
  n('anna', FOLDER_KIND, 'box', 'Anna', { shareIdentityId: 'id-anna' }), n('ben', FOLDER_KIND, 'box', 'Ben', { shareIdentityId: 'id-ben' }),
]
const byId = (id: string) => NODES.find((x) => x.id === id)!
const ids = (...xs: string[]) => xs.map(byId)

type Recorder = NodeMover & { moveMany: ReturnType<typeof vi.fn> }
/** A door whose `moveMany` answers every move as moved, unless `refuse` names a node's error. */
function recorder(refuse: Record<string, unknown> = {}): Recorder {
  return {
    move: vi.fn(async () => {}),
    moveMany: vi.fn(async (moves: readonly { nodeId: string; to: string | null }[]): Promise<MoveOutcome[]> => moves.map((m) => ({ nodeId: m.nodeId, error: refuse[m.nodeId] ?? null }))),
    refresh: vi.fn(async () => {}),
  }
}
function mountDialog(
  nodes: TreeNode[], mover: NodeMover, afterUndo?: () => void,
  session: { access: Access; tree: TreeSource<TreeNode> } = { access: memberAccessOf(), tree: memberTreeOf(() => NODES) },
) {
  return mount(MoveNodesDialog, {
    attachTo: document.body,
    props: { nodes, ...session, mover, rootLabel: 'Familie', afterUndo },
    global: { plugins: [realI18n()] },
  })
}
const errorText = () => document.body.querySelector('.veyq-message--error')?.textContent?.trim() ?? ''
const summaryLines = () => [...document.body.querySelectorAll('.move-summary-lines li')].map((li) => li.textContent?.trim())
const summaryMoved = () => document.body.querySelector('.move-summary .veyq-form-copy')?.textContent?.trim() ?? ''
const coded = (status: number, code: string) => ({ response: new Response(JSON.stringify({ code }), { status, headers: { 'content-type': 'application/json' } }) })
const sent = (m: Recorder, call = 0) => (m.moveMany.mock.calls[call][0] as { nodeId: string; to: string | null }[]).map((x) => `${x.nodeId}>${x.to}`)

beforeEach(() => {
  for (const f of Object.values(toast)) f.mockClear()
  authenticatedCalls.length = 0
})

describe('MoveNodesDialog: the browser', () => {
  it('opens at their level, each of them listed and blocked; "Move here" is disabled there: all of them are already here', async () => {
    mountDialog(ids('rom', 'neapel', 'file'), recorder())
    await flushPromises()
    expect(document.body.textContent).toContain(t('node.move.many.title', { count: 3 }))
    expect(crumbs()).toEqual(['Familie', 'Reisen'])
    expect(places()).toEqual([['Neapel', false, t('node.move.blocked.self')], ['Rom', false, t('node.move.blocked.self')]])
    expect(moveHereButton().disabled).toBe(true)
    expect(whyNotHere()).toBe(t('node.move.many.here.current'))
  })

  it('blocks a place ANY selected node cannot take, naming that node', async () => {
    const w = mountDialog(ids('rom', 'x'), recorder())
    await flushPromises()
    for (const step of ['Familie', 'Archiv']) {
      await goTo(step)
      expect(moveHereButton().disabled, step).toBe(true)
      expect(whyNotHere(), step).toBe(t('node.move.many.here.kind', { name: 'X' }))
    }
    // control: without the node no place takes, the same place may be chosen
    w.unmount()
    mountDialog(ids('rom'), recorder())
    await flushPromises()
    await goTo('Familie', 'Archiv')
    expect(moveHereButton().disabled).toBe(false)
  })
})

describe('MoveNodesDialog: the write', () => {
  it('all of them go: one moveMany to the place, the dialog closes, one toast whose Undo moves them back where they were', async () => {
    const mover = recorder()
    const afterUndo = vi.fn()
    const w = mountDialog(ids('rom', 'neapel', 'file'), mover, afterUndo)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    // bericht.pdf is left out (below); here: the two folders and nothing else
    expect(sent(mover)).toEqual(['rom>archiv', 'neapel>archiv'])
    expect(w.emitted('close')).toBeUndefined()
    w.unmount()

    const all = recorder()
    const v = mountDialog(ids('rom', 'neapel'), all, afterUndo)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(sent(all)).toEqual(['rom>archiv', 'neapel>archiv'])
    expect(v.emitted('moved')).toEqual([[{ nodes: ids('rom', 'neapel'), to: 'archiv' }]])
    expect(v.emitted('close')).toHaveLength(1)
    expect(toast.showUndo).toHaveBeenLastCalledWith(expect.objectContaining({ summary: t('node.move.many.moved', { count: 2, to: 'Archiv' }) }))
    toast.showUndo.mock.lastCall![0].onUndo()
    await flushPromises()
    expect(sent(all, 1)).toEqual(['rom>reisen', 'neapel>reisen'])
    expect(afterUndo).toHaveBeenCalledTimes(1)
  })

  it('the pre-check names and leaves out a node whose name a sibling there takes, and sends the rest', async () => {
    const mover = recorder()
    mountDialog(ids('rom', 'file'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(sent(mover)).toEqual(['rom>archiv'])
    expect(summaryMoved()).toBe(t('node.move.many.moved', { count: 1, to: 'Archiv' }))
    expect(summaryLines()).toEqual([
      t('node.move.many.reason', { name: 'bericht.pdf', reason: t('node.move.nameTakenThere', { kind: t('depot.aFile'), name: 'Bericht.PDF' }) }),
    ])
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.many.moved', { count: 1, to: 'Archiv' }) }))
  })

  it('where nothing would go, it says why and sends nothing; the browser stays', async () => {
    const mover = recorder()
    const w = mountDialog(ids('file'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(mover.moveMany).not.toHaveBeenCalled()
    expect(errorText()).toBe(t('node.move.many.reason', { name: 'bericht.pdf', reason: t('node.move.nameTakenThere', { kind: t('depot.aFile'), name: 'Bericht.PDF' }) }))
    expect(w.emitted('close')).toBeUndefined()
    expect(crumbs()).toEqual(['Familie', 'Archiv'])
  })

  it('a server refusal part-way: the summary names the refused node with its reason, the Undo moves back only what moved', async () => {
    const mover = recorder({ neapel: coded(409, 'slug_taken') })
    const w = mountDialog(ids('rom', 'neapel'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(sent(mover)).toEqual(['rom>archiv', 'neapel>archiv'])
    expect(summaryLines()).toEqual([t('node.move.many.reason', { name: 'Neapel', reason: t('node.move.refused.slug_taken') })])
    expect(w.emitted('moved')).toEqual([[{ nodes: ids('rom'), to: 'archiv' }]])
    expect(w.emitted('close'), 'the summary stays until closed').toBeUndefined()
    toast.showUndo.mock.lastCall![0].onUndo()
    await flushPromises()
    expect(sent(mover, 1)).toEqual(['rom>reisen'])
    document.body.querySelector<HTMLButtonElement>('.move-summary button')!.click()
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('every one refused: nothing moved is said, and no undo is offered', async () => {
    const mover = recorder({ rom: coded(429, 'rate_limited'), neapel: new TypeError('Failed to fetch') })
    mountDialog(ids('rom', 'neapel'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(summaryMoved()).toBe(t('node.move.many.noneMoved'))
    expect(summaryLines()).toEqual([
      t('node.move.many.reason', { name: 'Rom', reason: t('node.move.refused.rate_limited') }),
      t('node.move.many.reason', { name: 'Neapel', reason: t('node.move.refused.failed') }),
    ])
    expect(toast.showUndo).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('an Undo that fails names what stayed', async () => {
    const mover = recorder()
    mountDialog(ids('rom', 'neapel'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    mover.moveMany.mockResolvedValueOnce([{ nodeId: 'rom', error: null }, { nodeId: 'neapel', error: new Error('offline') }])
    toast.showUndo.mock.lastCall![0].onUndo()
    await flushPromises()
    expect(toast.error).toHaveBeenCalledWith(t('node.move.many.undoFailed', { names: t('node.move.many.quoted', { name: 'Neapel' }) }))
  })

  it('a guest who declines to name itself: nothing is said, nothing moved', async () => {
    const mover = recorder()
    mover.moveMany.mockRejectedValueOnce(new MoveDeclined())
    const w = mountDialog(ids('rom', 'neapel'), mover)
    await flushPromises()
    await moveHere('Familie', 'Archiv')
    expect(errorText()).toBe('')
    expect(w.emitted('moved')).toBeUndefined()
    expect(toast.showUndo).not.toHaveBeenCalled()
  })
})

describe('MoveNodesDialog: guests\' folders out of a drop box', () => {
  it('asks once, naming them; declined, nothing moves; accepted, they move with no undo', async () => {
    const confirm = mountConfirmDialog()
    const mover = recorder()
    mountDialog(ids('anna', 'ben'), mover)
    await flushPromises()
    expect(crumbs()).toEqual(['Familie', 'Einsendungen'])
    await moveHere('Familie', 'Archiv')
    expect(confirm.text()).toContain(t('node.move.many.identityConfirm', { names: '“Anna” and “Ben”' }))
    confirm.click(t('common.cancel'))
    await flushPromises()
    expect(mover.moveMany).not.toHaveBeenCalled()
    await moveHere()
    confirm.click(t('node.move.identityAccept'))
    await flushPromises()
    expect(sent(mover)).toEqual(['anna>archiv', 'ben>archiv'])
    expect(toast.success).toHaveBeenCalledWith(t('node.move.many.moved', { count: 2, to: 'Archiv' }))
    expect(toast.showUndo).not.toHaveBeenCalled()
  })
})

describe('MoveNodesDialog: a READ_WRITE guest', () => {
  const sub = NODES.filter((x) => ['reisen', 'rom', 'neapel', 'file'].includes(x.id)).map((x) => (x.id === 'reisen' ? { ...x, parentId: null } : x))
  it('browses its subtree only and moves through its door; it calls no authenticated client', async () => {
    const mover = recorder()
    mountDialog([sub[2], sub[3]], mover, undefined, { access: guestAccessOf('READ_WRITE'), tree: { ...memberTreeOf(() => sub), rootId: 'reisen' } as TreeSource<TreeNode> })
    await flushPromises()
    expect(crumbs()).toEqual(['Reisen'])
    await moveHere('Rom')
    expect(sent(mover)).toEqual(['neapel>rom', 'file>rom'])
    expect(authenticatedCalls).toEqual([])
  })
})
