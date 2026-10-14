// @vitest-environment happy-dom
// Core's move dialog (inbox #358) and its folder browser (owner decision 2026-10-14) over the REAL registry (Memories and
// Files, filled by `src/test/setup.ts`), the real PrimeVue Dialog and Breadcrumb (teleported to the body) and the app's
// real confirmation dialog, every step a DOM click on the rendered element. The door is a recorder (`NodeMover`): which
// session moves through what is `move.test.ts`'s; the request body on the wire `move.wire.test.ts`'s and, for a guest,
// `ShareTreePage.move.test.ts`'s. What this pins: where the browser opens, what it lists and lets open, where "Move here"
// may be chosen, the trail, the sibling pre-check, every server refusal explained by its name, the drop-box question, and
// the undo. The clients are guarded (`test/guestMode.ts`), so a guest's mount proves it calls no authenticated one.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))

import MoveNodeDialog from './MoveNodeDialog.vue'
import type { NodeMover } from './move'
import { FOLDER_KIND } from './kind'
import type { TreeNode } from './tree'
import { PlacementConflictError } from './tree'
import type { Access, TreeSource } from '../access'
import { FILE_KIND, DROPBOX_KIND } from '../../features/files/kinds'
import { guestAccessOf, memberAccessOf, memberTreeOf, treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { mountConfirmDialog } from '../../test/confirmDialog'
import { authenticatedCalls } from '../../test/guestMode'
import { crumbs, currentCrumb, goTo, moveHere, moveHereButton, placeButton, places, whyNotHere } from '../../test/moveBrowser'

const n = (id: string, kind: string, parentId: string | null, name: string, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name, parentId, ...over })
// Familie (root) ─ Reisen/ ─ Rom/ ─ bericht.pdf
//                         ─ Neapel/
//                ─ Archiv/ ─ Bericht.PDF       (a file whose name folds like the one moved)
//                ─ Einsendungen (drop box) ─ Anna/ (a guest's identity folder) ─ foto.jpg
const NODES = [
  n('reisen', FOLDER_KIND, null, 'Reisen'), n('rom', FOLDER_KIND, 'reisen', 'Rom'), n('file', FILE_KIND, 'rom', 'bericht.pdf'),
  n('neapel', FOLDER_KIND, 'reisen', 'Neapel'),
  n('archiv', FOLDER_KIND, null, 'Archiv'), n('taken', FILE_KIND, 'archiv', 'Bericht.PDF'),
  n('box', DROPBOX_KIND, null, 'Einsendungen'), n('anna', FOLDER_KIND, 'box', 'Anna', { shareIdentityId: 'id-anna' }),
  n('foto', FILE_KIND, 'anna', 'foto.jpg'),
]
const byId = (id: string) => NODES.find((x) => x.id === id)!

function recorder(): NodeMover & { move: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> } {
  return { move: vi.fn(async () => {}), refresh: vi.fn(async () => {}) }
}

function mountDialog(
  node: TreeNode,
  mover: NodeMover,
  afterUndo?: (id: string) => void,
  session: { access: Access; tree: TreeSource<TreeNode> } = { access: memberAccessOf(), tree: memberTreeOf(() => NODES) },
) {
  return mount(MoveNodeDialog, {
    attachTo: document.body,
    props: { node, ...session, mover, rootLabel: 'Familie', afterUndo },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
}
type W = ReturnType<typeof mountDialog>
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const errorText = () => document.body.querySelector('.p-message-error')?.textContent?.trim() ?? ''
const submitButton = moveHereButton
const moveTo = moveHere

beforeEach(() => {
  for (const f of Object.values(toast)) f.mockClear()
  authenticatedCalls.length = 0
})

describe('MoveNodeDialog: the browser', () => {
  it('opens where the node is: the trail up to the scope root, the places there, the node itself blocked', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    expect(document.body.textContent).toContain(t('node.move.title', { name: 'Rom' }))
    expect(crumbs()).toEqual(['Familie', 'Reisen'])
    expect(currentCrumb()).toBe('Reisen')
    expect(places()).toEqual([['Neapel', true, ''], ['Rom', false, t('node.move.blocked.self')]])
  })

  it('the node itself cannot be opened: a click on it leaves the place as it is', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    await click(placeButton('Rom'))
    expect(currentCrumb()).toBe('Reisen')
    expect(crumbs()).toEqual(['Familie', 'Reisen'])
  })

  it('"Move here" is disabled with the reason where the node is now, and enabled one level down', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    expect(submitButton().disabled).toBe(true)
    expect(whyNotHere()).toBe(t('node.move.here.current'))
    expect(submitButton().getAttribute('aria-describedby')).toBe('move-here-why')
    await goTo('Neapel')
    expect(currentCrumb()).toBe('Neapel')
    expect(submitButton().disabled).toBe(false)
    expect(whyNotHere()).toBe('')
  })

  it('lists only places that take the kind: no file, no drop box; a place with none says so', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    await goTo('Familie')
    expect(places().map((p) => p[0]), 'the drop box "Einsendungen" takes no folder').toEqual(['Archiv', 'Reisen'])
    await goTo('Archiv')
    expect(places(), 'its file "Bericht.PDF" is no place').toEqual([])
    expect(document.body.textContent).toContain(t('node.move.empty', { name: 'Rom' }))
  })

  it('goes into a place and back up by the trail; focus goes to the list of the place opened', async () => {
    mountDialog(byId('rom'), recorder())
    await flushPromises()
    await goTo('Familie', 'Archiv')
    expect(crumbs()).toEqual(['Familie', 'Archiv'])
    expect(document.activeElement?.classList.contains('move-browser-places'), 'focus on the list').toBe(true)
    expect(document.activeElement?.getAttribute('aria-label')).toBe(t('node.move.places', { place: 'Archiv' }))
    await goTo('Familie')
    expect(crumbs()).toEqual(['Familie'])
    await goTo('Reisen', 'Neapel')
    expect(crumbs()).toEqual(['Familie', 'Reisen', 'Neapel'])
  })

  it('Move here sends the node to the place on screen, then closes with a toast and its Undo', async () => {
    const mover = recorder()
    const afterUndo = vi.fn()
    const w = mountDialog(byId('rom'), mover, afterUndo)
    await flushPromises()
    await moveTo('Familie', 'Archiv')
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
    await moveTo('Familie', 'Archiv')
    mover.move.mockRejectedValueOnce(new Error('offline'))
    toast.showUndo.mock.calls[0][0].onUndo()
    await flushPromises()
    expect(toast.error).toHaveBeenCalledWith(t('node.move.undoFailed', { name: 'Rom' }))
  })
})

describe('MoveNodeDialog: a READ_WRITE guest', () => {
  // The guest's view of a link on "Reisen": its served root carries parentId null; nothing above it exists for the guest.
  const sub = NODES.filter((x) => ['reisen', 'rom', 'file', 'neapel'].includes(x.id)).map((x) => (x.id === 'reisen' ? { ...x, parentId: null } : x))
  const session = () => ({ access: guestAccessOf('READ_WRITE'), tree: { ...memberTreeOf(() => sub), rootId: 'reisen' } as TreeSource<TreeNode> })

  it('sees only its subtree: the trail starts at the link\'s root, nothing above it is named; it calls no authenticated client', async () => {
    const mover = recorder()
    mountDialog(sub[1], mover, undefined, session())
    await flushPromises()
    expect(crumbs()).toEqual(['Reisen'])
    expect(places().map((p) => p[0])).toEqual(['Neapel', 'Rom'])
    for (const outside of ['Familie', 'Archiv', 'Einsendungen']) expect(document.body.textContent).not.toContain(outside)
    await moveTo('Neapel')
    expect(mover.move).toHaveBeenCalledWith('rom', 'neapel')
    expect(authenticatedCalls).toEqual([])
  })
})

describe('MoveNodeDialog: refused before sending', () => {
  it('names the sibling at the destination whose name folds to the same (a file\'s folding), and sends nothing', async () => {
    const mover = recorder()
    mountDialog(byId('file'), mover)
    await flushPromises()
    await moveTo('Familie', 'Archiv')
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
    await moveTo('Familie', 'Archiv')
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
      await moveTo('Familie', 'Archiv')
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
      await moveTo('Familie', 'Archiv')
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
    expect(crumbs(), 'it opens in the drop box').toEqual(['Familie', 'Einsendungen'])
    expect(places(), 'no identity folder is offered').toEqual([])
    expect(whyNotHere()).toBe(t('node.move.here.current'))
    await moveTo('Familie', 'Archiv')
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
    await moveTo('Familie', 'Archiv')
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
    await moveTo('Familie', 'Archiv')
    expect(mover.move).toHaveBeenCalledWith('foto', 'archiv')
    expect(toast.showUndo).toHaveBeenCalled()
  })
})

describe('MoveNodeDialog: nowhere to go', () => {
  it('a kind nobody registered: the folders still open, but "Move here" stays disabled with the reason everywhere', async () => {
    mountDialog(n('x', 'UNKNOWN_KIND', 'reisen', 'X'), recorder())
    await flushPromises()
    expect(whyNotHere()).toBe(t('node.move.here.current'))
    for (const step of ['Neapel', 'Familie', 'Archiv']) {
      await goTo(step)
      expect(submitButton().disabled, step).toBe(true)
      expect(whyNotHere(), step).toBe(t('node.move.here.kind'))
    }
  })

  it('a file inside a guest\'s folder: "Move here" is disabled there, and the trail leads out', async () => {
    mountDialog(byId('foto'), recorder())
    await flushPromises()
    expect(crumbs()).toEqual(['Familie', 'Einsendungen', 'Anna'])
    expect(whyNotHere()).toBe(t('node.move.here.identityFolder'))
    await goTo('Einsendungen')
    expect(whyNotHere(), 'a drop box takes no placed file').toBe(t('node.move.here.kind'))
  })

  it('a guest who declines to name itself: nothing is said, nothing moved', async () => {
    const { MoveDeclined } = await import('./move')
    const mover = recorder()
    mover.move.mockRejectedValueOnce(new MoveDeclined())
    const w: W = mountDialog(byId('rom'), mover)
    await flushPromises()
    await moveTo('Familie', 'Archiv')
    expect(errorText()).toBe('')
    expect(w.emitted('moved')).toBeUndefined()
  })
})
