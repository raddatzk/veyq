// @vitest-environment happy-dom
// Core's page of a READ or READ_WRITE public link (uc.sharing.share-public-viewer:guest-sees-the-member-view: core's
// member level view and the kinds' member details handed a guest's access and tree, whatever the link's root kind),
// against a STUBBED registry (a fresh `createRegistry()` behind a delegating Proxy, as
// `views/ShareView.registry.test.ts`): the test registers the kinds, their level sections and details "an application"
// would, with stubs that render what they were handed. The subtree is real crypto (names and keys open through the
// chain), the router and the UI layer are real and every navigation is a DOM click on the rendered element, the clients
// are guarded (`test/guestMode.ts`: an authenticated one throws).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

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

import ShareTreePage from './ShareTreePage.vue'
import ShareNameDialog from './ShareNameDialog.vue'
import { createRegistry } from '../applications/registry'
import type { LevelRowsContext, LevelRowsFactory } from '../node/levelRow'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import { saveGuestSession, type GuestIdentity } from './guestIdentity'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { realI18n, t } from '../../test/i18n'
import { chooseNew, hasNewMenu, newMenuLabels } from '../../test/newMenu'
import { guestAccessFor } from '../../test/models'
import { authenticatedCalls } from '../../test/guestMode'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_2'
const ROOT = '6e5a0000-0000-4000-8000-0000000000d0'
const LETTERS = '6e5a0000-0000-4000-8000-0000000000d1'
const NESTED = '6e5a0000-0000-4000-8000-0000000000d2'
const NOTE = '6e5a0000-0000-4000-8000-0000000000d3'
const ALIEN = '6e5a0000-0000-4000-8000-0000000000d4'
const BROKEN = '6e5a0000-0000-4000-8000-0000000000d5'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), letters: v1.randomBytes(32), nested: v1.randomBytes(32), note: v1.randomBytes(32), alien: v1.randomBytes(32), broken: v1.randomBytes(32) }
const HASH = `#k=${b64url(K_SHARE)}`

// Kinds an application would register, with a level section and no detail. 'EXPENSE' is registered by nobody.
const NOTE_KIND = 'NOTE'
const BROKEN_KIND = 'BROKEN'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementRevision: 0 }
  return { blockedByOperator: false, id, parentId, kind, epoch: 0, placementRevision: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}

function head(role: ShareRole = ShareRole.Read): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: { en: 'Folder **note**' }, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe', FOLDER_KIND),
        child(NESTED, LETTERS, keys.letters, keys.nested, 'Feldpost', FOLDER_KIND),
        child(NOTE, ROOT, keys.root, keys.note, 'Notizen', NOTE_KIND),
        child(ALIEN, ROOT, keys.root, keys.alien, 'Ausgaben', 'EXPENSE'),
        child(BROKEN, ROOT, keys.root, keys.broken, 'Kaputt', BROKEN_KIND),
      ],
    } as PublicShare,
  }
}

/** A kind's row factory, as an application's (inbox #407): one row per node, whose primary click opens the node through the
 *  host, labelled by the tree's name. Records the level context it was handed. */
const seenCtx = vi.hoisted(() => ({ ctx: null as null | LevelRowsContext }))
const stubRows: LevelRowsFactory = (ctx, host) => ({
  row: (node) => {
    seenCtx.ctx = ctx.value
    return { icon: 'file', title: node.name, meta: 'stub', primary: { label: node.name, onClick: () => host.open(node) }, actions: [] }
  },
})
/** A WRITE_ONLY drop page an application claimed: never this page's to mount. */
const StubBody = defineComponent({
  name: 'StubBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'access'],
  emits: ['unavailable'],
  setup: () => () => h('div', { class: 'drop-stub' }),
})
/** A kind's detail, as an application's: what it was handed, and an open button per child. */
const StubDetail = defineComponent({
  name: 'StubDetail',
  props: ['node', 'scopeId', 'epochs', 'contentKey', 'children', 'access', 'tree'],
  emits: ['openNode', 'rename', 'delete', 'createChild', 'share', 'shareUpload'],
  setup: (p, { emit }) => () =>
    h('div', { class: 'detail-stub' }, [
      h('span', { class: 'detail-of' }, `${p.node.id}|${p.tree.nameOf(p.node.id)}|${p.scopeId}`),
      ...(() => {
        const c = p.children as { folders: { id: string; name: string }[]; byKind: Map<string, { id: string; name: string }[]> }
        return [...c.folders, ...[...c.byKind.values()].flat()]
      })().map((n) => h('button', { class: 'detail-open', 'data-node-id': n.id, onClick: () => emit('openNode', n) }, n.name)),
    ]),
})

// The name dialog is the layer's `VeyqDialog` (teleported); stubbed to render in place.
const stubs = {
  VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
}

let router: Router
async function at(node?: string) {
  await router.push(`/s/${SHARE_ID}${node ? `?node=${node}` : ''}${HASH}`)
}
async function mountBody(h0: PublicShareHead, o: { reload?: () => Promise<void>; requestGuest?: () => Promise<GuestIdentity | null> } = {}) {
  const access = guestAccessFor(h0, { requestGuest: o.requestGuest })
  const w = mount(ShareTreePage, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: o.reload ?? (async () => {}), access },
    global: { plugins: [router, realI18n()], stubs },
  })
  await flushPromises()
  return w
}
type Body = Awaited<ReturnType<typeof mountBody>>
const heading = (w: Body) => w.find('.veyq-node-header-title').text()
const folderCards = (w: Body) => w.findAll('li.veyq-level-item.is-folder').map((c) => c.find('.veyq-level-item-title').text())
/** The item of a node by its (numbered) title, and its primary button. */
const itemOf = (w: Body, title: string) => w.findAll('li.veyq-level-item').find((c) => c.find('.veyq-level-item-title').text() === title)!
const openButton = (w: Body, title: string) => itemOf(w, title).find('button.veyq-level-item-main')
const createsFolder = async (w: Body) => (await newMenuLabels(w)).includes(t('node.folderCreate'))
/** Click as a user does; the event says whether the element's default action ran. */
async function click(el: Element) {
  const e = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
  el.dispatchEvent(e)
  await flushPromises()
  return e
}
async function submitName(w: Body, name: string) {
  await w.find('.dialog-stub input').setValue(name)
  await w.find('.dialog-stub form').trigger('submit')
  await flushPromises()
}

beforeEach(async () => {
  localStorage.clear()
  authenticatedCalls.length = 0
  seenCtx.ctx = null
  const r = createRegistry()
  const list = { rows: stubRows }
  r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: NOTE_KIND, icon: 'file', list })
  r.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: BROKEN_KIND, icon: 'file', list })
  r.claimShareBody(NOTE_KIND, StubBody) // a WRITE_ONLY drop page: never mounted on this page
  holder.reg = r
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await at()
  await router.isReady()
})

describe("ShareTreePage browses a shared folder with core's level view", () => {
  it("shows the folder's name, the note once and ONE list of its sub-folders and every kind's nodes, opened through the chain", async () => {
    const w = await mountBody(head())
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(heading(w)).toBe('Familie')
    expect(w.findAll('.markdown-body')).toHaveLength(1)
    expect(w.find('.markdown-body').text()).toContain('Folder note')
    expect(w.findAll('ul.veyq-level-list'), 'one list for everything').toHaveLength(1)
    expect(folderCards(w)).toEqual(['Briefe'])
    // Unified list (inbox #407): folders first, then every other node MIXED BY NAME (not grouped by kind as the sections
    // were) - the unregistered kind's explicit unsupported row ("Ausgaben") sorts among the kinds' nodes.
    expect(w.findAll('.veyq-level-item-title').map((b) => b.text())).toEqual(['Briefe', 'Ausgaben', 'Kaputt', 'Notizen'])
    expect(w.find('li.veyq-level-item.is-unsupported .veyq-level-item-title').text(), 'a kind nobody registered: the explicit unsupported row').toBe('Ausgaben')
    expect(openButton(w, 'Notizen').exists(), "the kinds' nodes are rows with an open button").toBe(true)
    // The row factory is handed the guest's access and tree - the member's context, a guest's values.
    const p = seenCtx.ctx!
    expect(p.access.mode).toBe('guest')
    expect(p.epochs, "a guest's keys come through the tree").toBeNull()
    expect(Array.from(p.tree.keyOf(NOTE)!)).toEqual(Array.from(keys.note))
    expect(authenticatedCalls).toEqual([])
  })

  it('a folder item click navigates into it with the real router, keeping the #k= fragment; its level shows there', async () => {
    const w = await mountBody(head())
    await click(openButton(w, 'Briefe').element)
    expect(router.currentRoute.value.query.node).toBe(LETTERS)
    expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
    expect(heading(w)).toBe('Briefe')
    expect(folderCards(w)).toEqual(['Feldpost'])
  })

  it("a row's open goes through the tree's link: ?node= with the fragment kept", async () => {
    const w = await mountBody(head())
    await click(openButton(w, 'Notizen').element)
    expect(router.currentRoute.value.query.node).toBe(NOTE)
    expect(router.currentRoute.value.hash).toBe(HASH)
  })

  it("an empty folder says so once in plain text, as a member's does", async () => {
    await at(NESTED)
    const w = await mountBody(head())
    expect(heading(w)).toBe('Feldpost')
    // A READ guest may create nothing: the plain "nothing here" (a writer's names the New-menu entries).
    expect(w.find('p.veyq-empty').text()).toBe(t('node.levelEmpty'))
    expect(w.findAll('li.veyq-level-item')).toHaveLength(0)
  })

  it('the breadcrumb runs along the parent chain to the share root, each crumb a real link with the fragment', async () => {
    await at(NESTED)
    const w = await mountBody(head())
    const links = w.findAll('a.veyq-breadcrumb__link')
    expect(links.map((a) => a.text())).toEqual(['Familie', 'Briefe', 'Feldpost'])
    expect(links[1].attributes('href')).toBe(`/s/${SHARE_ID}?node=${LETTERS}${HASH}`)
    const e = await click(links[1].element)
    expect(e.defaultPrevented).toBe(true)
    expect(router.currentRoute.value.query.node).toBe(LETTERS)
    expect(router.currentRoute.value.hash).toBe(HASH)
  })

  it('an id the walk did not open falls back to the root', async () => {
    await at('6e5a0000-0000-4000-8000-0000000000ff')
    const w = await mountBody(head())
    expect(heading(w)).toBe('Familie')
  })

  it('a READ guest is offered nothing that writes or is a member\'s: no trash, no share, rename or delete, no create', async () => {
    const w = await mountBody(head())
    expect(w.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`).exists(), "the space's trash").toBe(false)
    // …while the view switch is the guest's too (inbox #370: a share view is the member view)
    expect(w.find('.veyq-level-toolbar .view-mode-toggle').exists()).toBe(true)
    // the folder's header offers the one thing a READ guest may do with it: download it (ADR 0082 decision 5)
    expect(w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('aria-label')), 'share, rename, delete').toEqual([t('export.action')])
    expect(hasNewMenu(w), 'a READ guest has no "New" menu').toBe(false)
    expect(w.find('.share-folder-badge').text()).toBe(t('share.readonly'))
    expect(authenticatedCalls).toEqual([])
  })
})

// inbox #401: a node that is not a folder renders its kind's DETAIL, exactly as the member's host mounts it — handed the
// guest's access and tree — below a folder and at a link's root alike. A claimed drop page is never mounted here.
describe("ShareTreePage renders a node's detail for the guest", () => {
  const BOX_KIND = 'BOX'
  const BOX = '6e5a0000-0000-4000-8000-0000000000d6'
  const INNER = '6e5a0000-0000-4000-8000-0000000000d7'
  const box = { key: v1.randomBytes(32), inner: v1.randomBytes(32) }
  function boxHead() {
    const h0 = head()
    h0.share.nodes.push(child(BOX, ROOT, keys.root, box.key, 'Postfach', BOX_KIND), child(INNER, BOX, box.key, box.inner, 'Anna', FOLDER_KIND))
    return h0
  }
  beforeEach(() => {
    holder.reg!.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: BOX_KIND, icon: 'inbox', detail: StubDetail })
    holder.reg!.claimShareBody(BOX_KIND, StubBody) // its WRITE_ONLY drop page: not this page's
  })

  it("mounts the kind's detail with the node, the link's scope, no epochs, the chain's key, its children, the guest's access and tree", async () => {
    await at(BOX)
    const w = await mountBody(boxHead())
    expect(w.find('.drop-stub').exists(), 'a drop page is never mounted here').toBe(false)
    expect(w.find('.detail-of').text()).toBe(`${BOX}|Postfach|${SCOPE_ID}`)
    const d = w.findComponent(StubDetail)
    expect(d.props('epochs')).toBeNull()
    expect(Array.from(d.props('contentKey') as Uint8Array), "the node's key from the chain").toEqual(Array.from(box.key))
    expect((d.props('access') as { mode: string }).mode).toBe('guest')
    expect(w.findAll('a.veyq-breadcrumb__link').map((a) => a.text())).toEqual(['Familie', 'Postfach'])
    expect(authenticatedCalls).toEqual([])
  })

  it("a detail's open is a real navigation through the tree's link: ?node= with the #k= fragment kept", async () => {
    await at(BOX)
    const w = await mountBody(boxHead())
    await click(w.find(`.detail-open[data-node-id="${INNER}"]`).element)
    expect(router.currentRoute.value.query.node).toBe(INNER)
    expect(router.currentRoute.value.hash).toBe(HASH)
    expect(heading(w)).toBe('Anna')
  })
})

// A link rooted at a node that is not a folder (a Memory, a chapter — inbox #401 S5): the same page, the kind's detail at
// the root, with the guest's breadcrumb, the note once and the role badge.
describe('ShareTreePage on a link rooted at a non-folder node', () => {
  const BOX_KIND = 'BOX'
  const BOX = '6e5a0000-0000-4000-8000-0000000000e6'
  const INNER = '6e5a0000-0000-4000-8000-0000000000e7'
  const box = { key: v1.randomBytes(32), inner: v1.randomBytes(32) }
  function rootedHead(role: ShareRole = ShareRole.Read): PublicShareHead {
    return {
      shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Rom 2026', noteByLocale: { en: 'Box **note**' }, role, rootKind: BOX_KIND,
      share: {
        role, scopeId: SCOPE_ID, rootKind: BOX_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, box.key)),
        nodes: [{ id: BOX, parentId: null, kind: BOX_KIND, epoch: 0 }, child(INNER, BOX, box.key, box.inner, 'Tag 1', BOX_KIND)],
      } as PublicShare,
    }
  }
  beforeEach(() => {
    holder.reg!.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: BOX_KIND, icon: 'inbox', detail: StubDetail })
    holder.reg!.claimShareBody(BOX_KIND, StubBody)
  })

  for (const [role, badge] of [[ShareRole.Read, 'share.readonly'], [ShareRole.ReadWrite, 'share.contributorBadge']] as const) {
    it(`${role}: the root's detail under the link's name, the note once, the role badge, no drop page`, async () => {
      const w = await mountBody(rootedHead(role))
      expect(w.emitted('unavailable')).toBeUndefined()
      expect(w.find('.detail-of').text()).toBe(`${BOX}|Rom 2026|${SCOPE_ID}`)
      expect(Array.from(w.findComponent(StubDetail).props('contentKey') as Uint8Array), "the root's key from the wrap").toEqual(Array.from(box.key))
      expect(w.findAll('.markdown-body')).toHaveLength(1)
      expect(w.find('.markdown-body').text()).toContain('Box note')
      expect(w.find('.share-folder-badge').text()).toBe(t(badge))
      expect(w.find('.drop-stub').exists()).toBe(false)
      expect(w.findAll('a.veyq-breadcrumb__link').map((a) => a.text())).toEqual(['Rom 2026'])
      expect(authenticatedCalls).toEqual([])
    })
  }

  it("a child opens through the tree's link with the #k= fragment kept; the breadcrumb leads back to the root", async () => {
    const w = await mountBody(rootedHead())
    await click(w.find(`.detail-open[data-node-id="${INNER}"]`).element)
    expect(router.currentRoute.value.query.node).toBe(INNER)
    expect(router.currentRoute.value.hash).toBe(HASH)
    expect(w.find('.detail-of').text()).toBe(`${INNER}|Tag 1|${SCOPE_ID}`)
    const links = w.findAll('a.veyq-breadcrumb__link')
    expect(links.map((a) => a.text())).toEqual(['Rom 2026', 'Tag 1'])
    const e = await click(links[0].element)
    expect(e.defaultPrevented).toBe(true)
    expect(router.currentRoute.value.query.node).toBeUndefined()
    expect(router.currentRoute.value.hash).toBe(HASH)
  })
})

describe('ShareTreePage: what it cannot render stays local', () => {
  it("a kind with no detail is a local empty state, never the page's unavailable state — nor its drop page", async () => {
    await at(NOTE)
    const w = await mountBody(head())
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.unsupportedKind'))
    expect(w.find('.drop-stub').exists()).toBe(false)
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it('…and so is a kind nobody registered', async () => {
    await at(ALIEN)
    const w = await mountBody(head())
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.unsupportedKind'))
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it("a share the fragment key does not open is the page's unavailable state", async () => {
    const bad = head()
    bad.share = { ...bad.share, wrap: b64std(v1.seal({ purpose: 'share-key' }, v1.randomBytes(32), keys.root)) }
    const w = await mountBody(bad)
    expect(w.emitted('unavailable')).toHaveLength(1)
  })
})

describe('ShareTreePage: a READ_WRITE guest adds folders', () => {
  it('a READ_WRITE guest gets the level\'s "New" menu with the folder entry and the contributor badge', async () => {
    const w = await mountBody(head(ShareRole.ReadWrite))
    expect(await createsFolder(w)).toBe(true)
    expect(w.find('.share-folder-badge').text()).toBe(t('share.folder.contributorBadge'))
  })

  it("asks the page's naming flow first, then creates the folder under the CURRENT folder as that guest", async () => {
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    api.createShareNode.mockImplementation(async ({ createShareNodeRequest }) => ({ nodeId: createShareNodeRequest.nodeId }))
    const requestGuest = vi.fn(async () => {
      const g = { identityId: 'id-1', contributorToken: 'tok-1', displayName: 'Oma' }
      saveGuestSession(SHARE_ID, g) // the shell's flow saves the session it names
      return g
    })
    const reload = vi.fn(async () => {})
    await at(LETTERS)
    const w = await mountBody(head(ShareRole.ReadWrite), { reload, requestGuest })

    await chooseNew(w, t('node.folderCreate'))
    expect(requestGuest).toHaveBeenCalledOnce()
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.folder.newFolderTitle'))
    await submitName(w, 'Urlaub 1998')
    expect(api.createShareNode).toHaveBeenCalledOnce()
    const created = api.createShareNode.mock.calls[0][0]
    expect(created.xContributorToken).toBe('tok-1')
    expect(created.createShareNodeRequest).toMatchObject({ parentId: LETTERS, kind: FOLDER_KIND })
    expect(reload).toHaveBeenCalledOnce()
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)
    expect(authenticatedCalls).toEqual([])
  })

  // uc.memory.participants, "at creation": "I was there" lives in Memories' member create dialog (the kind's `createDialog`).
  // A guest's create of an application's kind is THIS page's name dialog - the kind's dialog is never mounted here - and the
  // node is born with no meta, so a guest has no "I was there" and records no participant.
  it("a guest's create of an application's kind is the page's name dialog, never the kind's createDialog, and sends no meta", async () => {
    const KindCreateDialog = defineComponent({ name: 'KindCreateDialog', setup: () => () => h('label', { class: 'kind-create-stub' }, t('memory.participants.wasThere')) })
    holder.reg!.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
      kind: 'MEMO', icon: 'images', list: { rows: stubRows }, createDialog: KindCreateDialog,
      createEntry: { label: 'memory.create', icon: 'images', order: 10, allowed: () => true },
    })
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    api.createShareNode.mockImplementation(async ({ createShareNodeRequest }) => ({ nodeId: createShareNodeRequest.nodeId }))
    const w = await mountBody(head(ShareRole.ReadWrite), { requestGuest: async () => ({ identityId: 'id-0', contributorToken: 'tok-0' }) })
    await chooseNew(w, t('memory.create'))
    expect(w.find('.dialog-stub .header').text(), "control: the page's name dialog is open").toBe(t('common.create'))
    expect(w.findComponent(KindCreateDialog).exists(), "the kind's createDialog").toBe(false)
    expect(w.text()).not.toContain(t('memory.participants.wasThere'))
    await submitName(w, 'Rom')
    const req = api.createShareNode.mock.calls[0][0].createShareNodeRequest as unknown as Record<string, unknown>
    expect(req.kind).toBe('MEMO')
    expect('wrappedMeta' in req, 'a meta').toBe(false)
    expect(authenticatedCalls).toEqual([])
  })

  it('a visitor who declines to name itself gets no folder dialog', async () => {
    const w = await mountBody(head(ShareRole.ReadWrite), { requestGuest: async () => null })
    await chooseNew(w, t('node.folderCreate'))
    expect(w.find('.dialog-stub').exists()).toBe(false)
  })

  it('a name a sibling already takes is refused before sending, naming the sibling', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    const w = await mountBody(head(ShareRole.ReadWrite), { requestGuest: async () => ({ identityId: 'id-0', contributorToken: 'tok-0' }) })
    await chooseNew(w, t('node.folderCreate'))
    await submitName(w, 'briefe')
    expect(api.createShareNode).not.toHaveBeenCalled()
    expect(w.find('.dialog-stub').text()).toContain(t('share.folder.nameTaken', { name: 'Briefe' }))
  })
})
