// @vitest-environment happy-dom
// A share guest's move (inbox #358) on core's page of a public link: the member's level view, core's move dialog and the
// guest's door (`placeShareNodeAsGuest` after the page's naming flow) — real crypto for the subtree, the real router and
// PrimeVue, the clients guarded (`test/guestMode.ts`: an authenticated one throws and is recorded), every step a DOM
// click. The registry is a fresh one (FOLDER is core's) with one kind "an application" registers, its section exposing
// a move per node (as Files' rows do). Pinned: a READ_WRITE guest moves within its subtree only, through its link, the
// request carrying the re-wrapped key and the version it was built on; a READ guest, and the link's root, offer none.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
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

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const LETTERS = '6e5a0000-0000-4000-8000-0000000000e1'
const NESTED = '6e5a0000-0000-4000-8000-0000000000e2'
const NOTE = '6e5a0000-0000-4000-8000-0000000000e3'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), letters: v1.randomBytes(32), nested: v1.randomBytes(32), note: v1.randomBytes(32) }
const HASH = `#k=${b64url(K_SHARE)}`
const NOTE_KIND = 'NOTE'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string, placementVersion = 0): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion }
  return { id, parentId, kind, epoch: 0, placementVersion, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
// Familie/ ─ Briefe/ ─ Feldpost/ (version 2)    Notizen (NOTE, a node a contributor added)
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe', FOLDER_KIND),
        child(NESTED, LETTERS, keys.letters, keys.nested, 'Feldpost', FOLDER_KIND, 2),
        child(NOTE, ROOT, keys.root, keys.note, 'Notizen', NOTE_KIND),
      ],
    } as PublicShare,
  }
}

/** An application's section: a move button per node, shown where its capability says (here: a READ_WRITE guest). */
const MoveSection = defineComponent({
  props: ['folder', 'nodes', 'epochs', 'access', 'tree', 'viewMode'],
  emits: ['openNode', 'create', 'rename', 'move'],
  setup: (p, { emit }) => () => h('section', { class: 'note-section' },
    p.access.role === ShareRole.ReadWrite
      ? (p.nodes as { id: string; name: string }[]).map((n) => h('button', { class: 'note-move', 'data-id': n.id, onClick: () => emit('move', n) }, n.name))
      : []),
})

let router: Router
async function mountAt(role: ShareRole, node?: string, o: { requestGuest?: () => Promise<GuestIdentity | null>; reload?: () => Promise<void> } = {}) {
  await router.push(`/s/${SHARE_ID}${node ? `?node=${node}` : ''}${HASH}`)
  const h0 = head(role)
  const access = guestAccessFor(h0, { requestGuest: o.requestGuest, reload: o.reload, fragmentKey: b64url(K_SHARE) })
  const w = mount(ShareTreePage, {
    attachTo: document.body,
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: o.reload ?? (async () => {}), access },
    global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
  await flushPromises()
  return w
}
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
const moveCardButton = () => document.body.querySelector(`.veyq-folder-overview-actions button[aria-label="${t('node.move.action')}"]`)
const rows = () => [...document.body.querySelectorAll('.move-destination')].map((l) => [
  l.querySelector('.move-destination-name')!.textContent, l.querySelector('input')!.disabled,
])
async function pickAndMove(name: string) {
  const label = [...document.body.querySelectorAll('.move-destination')].find((l) => l.querySelector('.move-destination-name')!.textContent === name)!
  await click(label.querySelector('input')!)
  await click([...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === t('node.move.submit'))!)
}
const guest: GuestIdentity = { identityId: 'g-1', contributorToken: 'contributor-token' } as GuestIdentity

beforeEach(async () => {
  authenticatedCalls.length = 0
  for (const f of Object.values(toast)) f.mockClear()
  const r = createRegistry()
  r.registerKind({ kind: NOTE_KIND, icon: 'pi pi-file', shareable: false, section: { order: 10, component: MoveSection }, placement: { root: true, parents: [FOLDER_KIND] } })
  holder.reg = r
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

describe('a READ_WRITE guest moves inside its share', () => {
  it('a folder from its card: only the subtree\'s folders, through its link, the key re-wrapped under the destination', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    const requestGuest = vi.fn(async () => guest)
    const reload = vi.fn(async () => {})
    await mountAt(ShareRole.ReadWrite, NESTED, { requestGuest, reload })
    await click(moveCardButton()!)
    // the share root and its folders — never the scope root, never a guest's identity folder, never itself
    expect(rows()).toEqual([['Familie', false], ['Briefe', true], ['Feldpost', true]])
    await pickAndMove('Familie')
    expect(requestGuest, 'named through the page\'s one flow').toHaveBeenCalledTimes(1)
    expect(updateShareNode).toHaveBeenCalledTimes(1)
    const call = updateShareNode.mock.calls[0][0]
    expect(call).toMatchObject({ shareId: SHARE_ID, nodeId: NESTED, xContributorToken: 'contributor-token' })
    const req = call.updateShareNodeRequest
    expect(req).toMatchObject({ parentId: ROOT, placementVersion: 2 })
    expect('epoch' in req && req.epoch !== undefined, 'a guest states no epoch').toBe(false)
    const at3 = { purpose: 'content-key', scopeId: SCOPE_ID, nodeId: NESTED, epoch: 0, placementVersion: 3 } as const
    expect(Array.from(v1.open(at3, keys.root, fromB64std(req.wrappedContentKey)))).toEqual(Array.from(keys.nested))
    expect(reload, 're-resolved after the move').toHaveBeenCalled()
    expect(toast.showUndo).toHaveBeenCalledWith(expect.objectContaining({ summary: t('node.move.moved', { name: 'Feldpost', to: 'Familie' }) }))
    expect(authenticatedCalls).toEqual([])
  })

  it('a node a section lists (a contributor\'s note) into a folder', async () => {
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    await mountAt(ShareRole.ReadWrite, undefined, { requestGuest: async () => guest })
    await click(document.body.querySelector(`.note-move[data-id="${NOTE}"]`)!)
    expect(rows()).toEqual([['Familie', true], ['Briefe', false], ['Feldpost', false]])
    await pickAndMove('Briefe')
    expect(updateShareNode.mock.calls[0][0].updateShareNodeRequest).toMatchObject({ parentId: LETTERS, placementVersion: 0 })
    expect(authenticatedCalls).toEqual([])
  })

  it('a refusal the server answers is explained by its name (illegal_scope)', async () => {
    const body = JSON.stringify({ code: 'illegal_scope' })
    const updateShareNode = stubApi(publicSharesApi, { updateShareNode: undefined }).updateShareNode
    updateShareNode.mockRejectedValueOnce({ response: new Response(body, { status: 422, headers: { 'content-type': 'application/json' } }) })
    await mountAt(ShareRole.ReadWrite, NESTED, { requestGuest: async () => guest })
    await click(moveCardButton()!)
    await pickAndMove('Familie')
    expect(document.body.querySelector('.p-message-error')!.textContent!.trim()).toBe(t('node.move.refused.illegal_scope'))
  })

  // (A guest's identity folder offers none either — `access.test.ts`' matrix; its entry opens only through a drop box's
  // key, which this subtree has none of.)
  it('the link\'s root offers no move: its parent lies above the link', async () => {
    await mountAt(ShareRole.ReadWrite)
    expect(document.body.querySelector('.veyq-folder-overview-card h2')!.textContent).toBe('Familie')
    expect(moveCardButton()).toBeNull()
  })
})

describe('a guest without the right moves nothing', () => {
  it('READ: no move on any folder card, no section move, no dialog', async () => {
    await mountAt(ShareRole.Read, NESTED)
    expect(moveCardButton()).toBeNull()
    expect(document.body.querySelector('.note-move')).toBeNull()
    expect(authenticatedCalls).toEqual([])
  })

  it('WRITE_ONLY (never this page\'s, by the shell): still no move anywhere', async () => {
    await mountAt(ShareRole.WriteOnly, NESTED)
    expect(moveCardButton()).toBeNull()
  })
})
