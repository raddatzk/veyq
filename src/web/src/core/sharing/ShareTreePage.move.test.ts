// @vitest-environment happy-dom
// A share guest's move (inbox #358) on core's page of a public link: the member's level view, core's move dialog and the
// guest's door (`placeShareNodeAsGuest` after the page's naming flow) — real crypto for the subtree, the real router and
// the clients guarded (`test/guestMode.ts`: an authenticated one throws and is recorded), every step a DOM
// click. The registry is a fresh one (FOLDER is core's) with one kind "an application" registers, its row factory exposing
// a move action per node where its right says (as Depot's rows do; inbox #407: a folder's own move is its NodeHeader
// action once it is open, not an action on its list item). Pinned: a READ_WRITE guest moves within its subtree only, through its link, the
// request carrying the re-wrapped key and the revision it was built on; a READ guest, and the link's root, offer none.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig<object>()))
const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => toast }))

import ShareTreePage from './ShareTreePage.vue'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import type { GuestIdentity } from './guestIdentity'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { realI18n, t } from '../../test/i18n'
import { guestAccessFor } from '../../test/models'
import { authenticatedCalls } from '../../test/guestMode'
import { actionIdsOf, clickAction } from '../../test/levelRows'
import type { LevelRowsFactory } from '../node/levelRow'
import { crumbs, goTo, moveHere, moveHereButton, places, whyNotHere } from '../../test/moveBrowser'
import { moveSelection, selectRows, startSelecting } from '../../test/levelSelection'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { testKind } from '../../test/testApplication'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const LETTERS = '6e5a0000-0000-4000-8000-0000000000e1'
const NESTED = '6e5a0000-0000-4000-8000-0000000000e2'
const NOTE = '6e5a0000-0000-4000-8000-0000000000e3'
const SLIP = '6e5a0000-0000-4000-8000-0000000000e4'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), letters: v1.randomBytes(32), nested: v1.randomBytes(32), note: v1.randomBytes(32), slip: v1.randomBytes(32) }
const HASH = `#k=${b64url(K_SHARE)}`
const NOTE_KIND = 'NOTE'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string, placementRevision = 0): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, scopeEpoch: 0, placementRevision }
  return { blockedByOperator: false, id, parentId, kind, epoch: 0, scopeEpoch: 0, placementRevision, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
// Familie/ ─ Briefe/ ─ Feldpost/ (version 2)    Notizen, Zettel (NOTE, nodes a contributor added)
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, scopeEpoch: 0 },
        child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe', FOLDER_KIND),
        child(NESTED, LETTERS, keys.letters, keys.nested, 'Feldpost', FOLDER_KIND, 2),
        child(NOTE, ROOT, keys.root, keys.note, 'Notizen', NOTE_KIND),
        child(SLIP, ROOT, keys.root, keys.slip, 'Zettel', NOTE_KIND, 1),
      ],
    } as PublicShare,
  }
}

/** An application's row factory: a move action per node, shown where its right says (here: a READ_WRITE guest), and
 *  the same answer for a selection of the level (inbox #421) - no delete, as no guest deletes a node. */
const moveRows: LevelRowsFactory = (ctx, host) => {
  const contributes = () => ctx.value.access.mode === 'guest' && ctx.value.access.role === ShareRole.ReadWrite
  return {
    row: (node) => ({
      icon: 'file', title: node.name, meta: 'note', primary: { label: node.name, onClick: () => host.open?.(node) },
      actions: [{ id: 'move', label: 'Move', icon: 'arrows-h', shown: contributes(), onClick: () => host.move?.(node) }],
      selection: { move: contributes(), delete: false },
    }),
  }
}

let router: Router
async function mountAt(role: ShareRole, node?: string, o: { requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void> } = {}) {
  await router.push(`/s/${SHARE_ID}${node ? `?node=${node}` : ''}${HASH}`)
  const h0 = head(role)
  const access = guestAccessFor(h0, { requestGuest: o.requestGuest, reload: o.reload, fragmentKey: b64url(K_SHARE) })
  const w = mount(ShareTreePage, {
    attachTo: document.body,
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: o.reload ?? (async () => {}), access },
    global: { plugins: [router, realI18n()] },
  })
  await flushPromises()
  return w
}
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
/** The header's "Move to…": an entry of its ⋯ menu (ADR 0110 decision 11), opened with a real click; null where it has none. */
async function moveCardEntry(): Promise<HTMLElement | null> {
  const more = document.body.querySelector<HTMLElement>('.veyq-node-header .veyq-node-header-more')
  if (!more) return null
  await click(more)
  return document.body.querySelector<HTMLElement>('.veyq-node-header-action-move .veyq-menu__item-content')
}
const guest: GuestIdentity = { identityId: 'g-1', contributorToken: 'contributor-token' } as GuestIdentity

beforeEach(async () => {
  authenticatedCalls.length = 0
  for (const f of Object.values(toast)) f.mockClear()
  const r = createRegistry()
  testKind(r, { rekeyContent: nothingOfItsOwnToRekey, kind: NOTE_KIND, icon: 'file', list: { rows: moveRows }, placement: { root: true, parents: [FOLDER_KIND] } })
  holder.reg = r
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

describe('a READ_WRITE guest moves inside its share', () => {
  it('a folder from its header (opened first): only the subtree\'s folders, through its link, the key re-wrapped under the destination', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    const requestGuest = vi.fn(async () => guest)
    const reload = vi.fn(async () => {})
    await mountAt(ShareRole.ReadWrite, NESTED, { requestGuest, reload })
    await click((await moveCardEntry())!)
    // it opens where the folder is; the trail runs up to the share root, never the scope root; itself is not to be opened
    expect(crumbs()).toEqual(['Familie', 'Briefe'])
    expect(places()).toEqual([['Feldpost', false, t('node.move.blocked.self')]])
    expect(whyNotHere()).toBe(t('node.move.here.current'))
    await goTo('Familie')
    expect(places(), 'the share root lists its folder, not the note').toEqual([['Briefe', true, '']])
    expect(moveHereButton().disabled).toBe(false)
    await moveHere()
    expect(requestGuest, 'named through the page\'s one flow').toHaveBeenCalledTimes(1)
    expect(updateShareNode).toHaveBeenCalledTimes(1)
    const call = updateShareNode.mock.calls[0][0]
    expect(call).toMatchObject({ shareId: SHARE_ID, nodeId: NESTED, xContributorToken: 'contributor-token' })
    const req = call.updateShareNodeRequest
    expect(req).toMatchObject({ parentId: ROOT, placementRevision: 2 })
    expect('epoch' in req && req.epoch !== undefined, 'a guest states no epoch').toBe(false)
    const at3 = { purpose: 'content-key', scopeId: SCOPE_ID, nodeId: NESTED, epoch: 0, placementRevision: 3 } as const
    expect(Array.from(v1.open(at3, keys.root, fromB64std(req.wrappedContentKey)))).toEqual(Array.from(keys.nested))
    expect(reload, 're-resolved after the move').toHaveBeenCalled()
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.moved', { name: 'Feldpost', to: 'Familie' }) }))
    expect(authenticatedCalls).toEqual([])
  })

  it('a node an application lists (a contributor\'s note) into a folder, from the real "more" menu of its row', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    const w = await mountAt(ShareRole.ReadWrite, undefined, { requestGuest: async () => guest })
    await clickAction(w, 'Notizen', 'move')
    expect(crumbs()).toEqual(['Familie'])
    expect(whyNotHere(), 'where it is now').toBe(t('node.move.here.current'))
    await moveHere('Briefe')
    expect(updateShareNode.mock.calls[0][0].updateShareNodeRequest).toMatchObject({ parentId: LETTERS, placementRevision: 0 })
    expect(authenticatedCalls).toEqual([])
  })

  it('a refusal the server answers is explained by its name (illegal_scope)', async () => {
    const body = JSON.stringify({ code: 'illegal_scope' })
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    updateShareNode.mockRejectedValueOnce({ response: new Response(body, { status: 422, headers: { 'content-type': 'application/json' } }) })
    await mountAt(ShareRole.ReadWrite, NESTED, { requestGuest: async () => guest })
    await click((await moveCardEntry())!)
    await moveHere('Familie')
    expect(document.body.querySelector('.veyq-message--error')!.textContent!.trim()).toBe(t('node.move.refused.illegal_scope'))
  })

  // (A guest's identity folder offers none either — `access.test.ts`' matrix; its entry opens only through a drop box's
  // key, which this subtree has none of.)
  it('the link\'s root offers no move: its parent lies above the link', async () => {
    await mountAt(ShareRole.ReadWrite)
    expect(document.body.querySelector('.veyq-node-header-title')!.textContent).toBe('Familie')
    expect(await moveCardEntry()).toBeNull()
    expect(document.body.querySelector(`.veyq-node-header button[aria-label="${t('node.move.action')}"]`)).toBeNull()
  })
})

// inbox #421: the guest's level has the member's Select; a selection moves through the guest's door, named once, one re-read.
describe('a READ_WRITE guest moves a selection of its level', () => {
  it('Select, tick two notes, Move to…: each placed through its link, named once, re-resolved once; no Delete', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    const requestGuest = vi.fn(async () => guest)
    const reload = vi.fn(async () => {})
    const w = await mountAt(ShareRole.ReadWrite, undefined, { requestGuest, reload })
    await startSelecting()
    expect(document.body.querySelector('.veyq-select-delete'), 'a guest deletes no node').toBeNull()
    await selectRows('Notizen', 'Zettel')
    await moveSelection()
    expect(crumbs()).toEqual(['Familie'])
    await moveHere('Briefe')
    expect(requestGuest, 'named once for both').toHaveBeenCalledTimes(1)
    expect(updateShareNode).toHaveBeenCalledTimes(2)
    expect(updateShareNode.mock.calls.map((c) => c[0].nodeId).sort()).toEqual([NOTE, SLIP].sort())
    for (const [call] of updateShareNode.mock.calls) {
      expect(call).toMatchObject({ shareId: SHARE_ID, xContributorToken: 'contributor-token' })
      expect(call.updateShareNodeRequest).toMatchObject({ parentId: LETTERS, placementRevision: call.nodeId === SLIP ? 1 : 0 })
    }
    expect(reload, 're-resolved once, after the last').toHaveBeenCalledTimes(1)
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.many.moved', { count: 2, to: 'Briefe' }) }))
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })
})

// inbox #421: drag and drop is the member's on a guest's level too - the same door, the same run as the dialog.
describe('a READ_WRITE guest drags and drops', () => {
  const transfer = () => ({ types: [] as string[], dropEffect: 'unset', effectAllowed: 'unset', setData(type: string) { this.types.push(type) }, setDragImage() {} })
  function fire(el: Element, type: string, data: object): boolean {
    const e = new Event(type, { bubbles: true, cancelable: true })
    Object.defineProperty(e, 'dataTransfer', { value: data })
    el.dispatchEvent(e)
    return e.defaultPrevented
  }
  const itemOf = (name: string) =>
    [...document.body.querySelectorAll('li.veyq-level-item')].find((li) => li.querySelector('.veyq-level-item-title')?.textContent === name)!
  const crumbOf = (name: string) =>
    [...document.body.querySelectorAll('li.veyq-breadcrumb__item')].find((li) => li.textContent?.trim() === name)!

  it('a note onto a folder of the level, and a folder onto the link\'s root in the breadcrumb: each placed through its link', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    const reload = vi.fn(async () => {})
    let w = await mountAt(ShareRole.ReadWrite, undefined, { requestGuest: async () => guest, reload })
    let data = transfer()
    fire(itemOf('Notizen'), 'dragstart', data)
    expect(fire(itemOf('Briefe'), 'dragover', data)).toBe(true)
    fire(itemOf('Briefe'), 'drop', data)
    await flushPromises()
    expect(updateShareNode).toHaveBeenCalledTimes(1)
    expect(updateShareNode.mock.calls[0][0]).toMatchObject({ nodeId: NOTE, xContributorToken: 'contributor-token' })
    expect(updateShareNode.mock.calls[0][0].updateShareNodeRequest).toMatchObject({ parentId: LETTERS, placementRevision: 0 })
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.moved', { name: 'Notizen', to: 'Briefe' }) }))
    w.unmount()

    w = await mountAt(ShareRole.ReadWrite, LETTERS, { requestGuest: async () => guest, reload })
    data = transfer()
    fire(itemOf('Feldpost'), 'dragstart', data)
    expect(fire(crumbOf('Briefe'), 'dragover', data), 'the place on screen').toBe(false)
    expect(fire(crumbOf('Familie'), 'dragover', data)).toBe(true)
    fire(crumbOf('Familie'), 'drop', data)
    await flushPromises()
    expect(updateShareNode.mock.calls[1][0]).toMatchObject({ nodeId: NESTED })
    expect(updateShareNode.mock.calls[1][0].updateShareNodeRequest).toMatchObject({ parentId: ROOT, placementRevision: 2 })
    expect(authenticatedCalls).toEqual([])
    w.unmount()
  })

  it('READ: nothing on its level is draggable', async () => {
    const w = await mountAt(ShareRole.Read)
    expect(document.body.querySelectorAll('[draggable="true"]')).toHaveLength(0)
    w.unmount()
  })
})

describe('a guest without the right moves nothing', () => {
  it('READ: no move on a folder\'s header, none on a row, no dialog', async () => {
    const w = await mountAt(ShareRole.Read, NESTED)
    expect(await moveCardEntry()).toBeNull()
    expect(document.body.querySelector(`.veyq-node-header button[aria-label="${t('node.move.action')}"]`)).toBeNull()
    expect(authenticatedCalls).toEqual([])
    w.unmount()
    // (the folder level above holds no note: the root does)
    const root = await mountAt(ShareRole.Read)
    expect(root.findAll('.veyq-level-item-title').map((x) => x.text())).toContain('Notizen')
    expect(await actionIdsOf(root, 'Notizen')).toEqual([])
    expect(authenticatedCalls).toEqual([])
  })

  it('WRITE_ONLY (never this page\'s, by the shell): still no move anywhere', async () => {
    await mountAt(ShareRole.WriteOnly, NESTED)
    expect(await moveCardEntry()).toBeNull()
    expect(document.body.querySelector(`.veyq-node-header button[aria-label="${t('node.move.action')}"]`)).toBeNull()
  })
})
