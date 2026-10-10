// @vitest-environment happy-dom
// Core's body of the public page of a FOLDER link (cr-node-key-hierarchy 5b.4b; since inbox #401 S2 core's member level
// view handed a guest's access and tree), against a STUBBED registry (a fresh `createRegistry()` behind a delegating
// Proxy, as `views/ShareView.registry.test.ts`): the test registers the kinds, their level sections and the share bodies
// "an application" would, with stubs that render what they were handed. The subtree is real crypto (names and keys open
// through the chain), the router and PrimeVue are real and every navigation is a DOM click on the rendered element, the
// clients are guarded (`test/guestMode.ts`: an authenticated one throws).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, onMounted } from 'vue'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
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

import FolderShareBody from './FolderShareBody.vue'
import ShareNameDialog from './ShareNameDialog.vue'
import { createRegistry, type LevelSectionProps } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import { saveGuestSession, type GuestIdentity } from './guestIdentity'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { realI18n, t } from '../../test/i18n'
import { guestAccessFor } from '../../test/models'
import { authenticatedCalls } from '../../test/guestMode'

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

// Kinds an application would register: one with a share body, one whose body reports its node unusable — both with a
// level section, as every real kind has. 'EXPENSE' is registered by nobody.
const NOTE_KIND = 'NOTE'
const BROKEN_KIND = 'BROKEN'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
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

/** A kind's level section, as an application's: one open button per node, labelled by the tree's name. */
const seenSection = vi.hoisted(() => ({ props: null as null | LevelSectionProps }))
const StubSection = defineComponent({
  name: 'StubSection',
  props: ['folder', 'nodes', 'epochs', 'access', 'tree'],
  emits: ['openNode', 'create', 'rename'],
  setup: (p, { emit }) => () => {
    seenSection.props = p as unknown as LevelSectionProps
    return h('section', { class: 'stub-section' }, (p.nodes as { id: string; name: string }[]).map((n) =>
      h('button', { class: 'stub-open', 'data-node-id': n.id, onClick: () => emit('openNode', n) }, n.name)))
  },
})
/** A body that renders what the folder body handed it. */
const StubBody = defineComponent({
  name: 'StubBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'access', 'subtreeRoot'],
  emits: ['unavailable'],
  setup: (p) => () => h('div', { class: 'embedded-stub' }, `${p.subtreeRoot?.nodeId}|${p.subtreeRoot?.name}|${p.shareId}`),
})
/** A body that finds its node unusable as soon as it mounts. */
const BrokenBody = defineComponent({
  name: 'BrokenBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'access', 'subtreeRoot'],
  emits: ['unavailable'],
  setup: (_p, { emit }) => {
    onMounted(() => emit('unavailable'))
    return () => h('div', { class: 'broken-stub' })
  },
})

// The name dialog is PrimeVue's (teleported); stubbed to render in place.
const stubs = {
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
}

let router: Router
async function at(node?: string) {
  await router.push(`/s/${SHARE_ID}${node ? `?node=${node}` : ''}${HASH}`)
}
async function mountBody(h0: PublicShareHead, o: { reload?: () => Promise<void>; requestGuest?: () => Promise<GuestIdentity | null> } = {}) {
  const access = guestAccessFor(h0, { requestGuest: o.requestGuest })
  const w = mount(FolderShareBody, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: o.reload ?? (async () => {}), access },
    global: { plugins: [router, realI18n(), PrimeVue], stubs, directives: { tooltip: {} } },
  })
  await flushPromises()
  return w
}
type Body = Awaited<ReturnType<typeof mountBody>>
const heading = (w: Body) => w.find('.veyq-folder-overview-card h2').text()
const folderCards = (w: Body) => w.findAll('button.veyq-folder-card').map((c) => c.find('strong').text())
const createFolderCard = (w: Body) => w.find(`.veyq-create-card[aria-label="${t('node.folderCreate')}"]`)
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
  seenSection.props = null
  const r = createRegistry()
  const section = { order: 10, component: StubSection }
  r.registerKind({ kind: NOTE_KIND, icon: 'pi pi-file', shareable: true, section })
  r.registerKind({ kind: BROKEN_KIND, icon: 'pi pi-file', shareable: true, section })
  r.claimShareBody(NOTE_KIND, StubBody)
  r.claimShareBody(BROKEN_KIND, BrokenBody)
  holder.reg = r
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await at()
  await router.isReady()
})

describe("FolderShareBody browses the shared folder with core's level view", () => {
  it("shows the folder's name, the note once, its sub-folders and every kind's section, opened through the chain", async () => {
    const w = await mountBody(head())
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(heading(w)).toBe('Familie')
    expect(w.findAll('.markdown-body')).toHaveLength(1)
    expect(w.find('.markdown-body').text()).toContain('Folder note')
    expect(folderCards(w)).toEqual(['Briefe'])
    expect(w.findAll('.stub-open').map((b) => b.text()).sort(), "the kinds' section gets their nodes").toEqual(['Kaputt', 'Notizen'])
    expect(w.find('.veyq-level-fallback').text(), 'a kind nobody registered: the level fallback').toContain('Ausgaben')
    // The section is handed the guest's access and tree — the member's props, a guest's values.
    const p = seenSection.props!
    expect(p.access.mode).toBe('guest')
    expect(p.epochs, "a guest's keys come through the tree").toBeNull()
    expect(Array.from(p.tree.keyOf(NOTE)!)).toEqual(Array.from(keys.note))
    expect(authenticatedCalls).toEqual([])
  })

  it('a folder card click navigates into it with the real router, keeping the #k= fragment; its level shows there', async () => {
    const w = await mountBody(head())
    const card = w.findAll('button.veyq-folder-card').find((c) => c.text().includes('Briefe'))!
    await click(card.element)
    expect(router.currentRoute.value.query.node).toBe(LETTERS)
    expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
    expect(heading(w)).toBe('Briefe')
    expect(folderCards(w)).toEqual(['Feldpost'])
  })

  it("a section's open goes through the tree's link: ?node= with the fragment kept", async () => {
    const w = await mountBody(head())
    await click(w.find(`.stub-open[data-node-id="${NOTE}"]`).element)
    expect(router.currentRoute.value.query.node).toBe(NOTE)
    expect(router.currentRoute.value.hash).toBe(HASH)
  })

  it('an empty folder says so, as a member\'s does', async () => {
    await at(NESTED)
    const w = await mountBody(head())
    expect(heading(w)).toBe('Feldpost')
    expect(w.text()).toContain(t('node.folderEmpty'))
  })

  it('the breadcrumb runs along the parent chain to the share root, each crumb a real link with the fragment', async () => {
    await at(NESTED)
    const w = await mountBody(head())
    const links = w.findAll('a.p-breadcrumb-item-link')
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
    expect(w.find('.veyq-level-toolbar').exists(), "the space's trash").toBe(false)
    expect(w.find('.veyq-folder-overview-actions').exists(), 'share, rename, delete').toBe(false)
    expect(w.find('.veyq-create-card').exists()).toBe(false)
    expect(w.find('.share-folder-badge').text()).toBe(t('share.folder.readonly'))
    expect(authenticatedCalls).toEqual([])
  })
})

describe('FolderShareBody embeds what is not a folder (until its application mounts its detail, inbox #401 S5)', () => {
  it("embeds the body claimed for the node's kind, handing it the node's id, key and name from the chain and the access", async () => {
    const w = await mountBody(head())
    await click(w.find(`.stub-open[data-node-id="${NOTE}"]`).element)
    expect(w.find('.embedded-stub').text()).toBe(`${NOTE}|Notizen|${SHARE_ID}`)
    const body = w.findComponent(StubBody)
    expect(Array.from((body.props('subtreeRoot') as { key: Uint8Array }).key), "the node's key, opened through the chain").toEqual(Array.from(keys.note))
    expect((body.props('access') as { mode: string }).mode).toBe('guest')
    expect(w.findAll('a.p-breadcrumb-item-link').map((a) => a.text())).toEqual(['Familie', 'Notizen'])
    expect(w.findAll('.markdown-body'), 'the note stays once, above the embedded body').toHaveLength(1)
    expect(w.find('.share-folder-badge').exists(), 'the embedded body says its own role').toBe(false)
  })

  it("a kind nobody claimed is a local empty state, never the page's unavailable state", async () => {
    await at(ALIEN)
    const w = await mountBody(head())
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.unsupportedKind'))
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it('an embedded body that reports its node unusable becomes a local empty state too', async () => {
    await at(BROKEN)
    const w = await mountBody(head())
    expect(w.find('.broken-stub').exists()).toBe(false)
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.gone'))
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it("a share the fragment key does not open is the page's unavailable state", async () => {
    const bad = head()
    bad.share = { ...bad.share, wrap: b64std(v1.seal({ purpose: 'share-key' }, v1.randomBytes(32), keys.root)) }
    const w = await mountBody(bad)
    expect(w.emitted('unavailable')).toHaveLength(1)
  })
})

describe('FolderShareBody: a READ_WRITE guest adds folders', () => {
  it('a READ_WRITE guest gets the level\'s create card and the contributor badge', async () => {
    const w = await mountBody(head(ShareRole.ReadWrite))
    expect(createFolderCard(w).exists()).toBe(true)
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

    await click(createFolderCard(w).element)
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

  it('a visitor who declines to name itself gets no folder dialog', async () => {
    const w = await mountBody(head(ShareRole.ReadWrite), { requestGuest: async () => null })
    await click(createFolderCard(w).element)
    expect(w.find('.dialog-stub').exists()).toBe(false)
  })

  it('a name a sibling already takes is refused before sending, naming the sibling', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    const w = await mountBody(head(ShareRole.ReadWrite), { requestGuest: async () => ({ identityId: 'id-0', contributorToken: 'tok-0' }) })
    await click(createFolderCard(w).element)
    await submitName(w, 'briefe')
    expect(api.createShareNode).not.toHaveBeenCalled()
    expect(w.find('.dialog-stub').text()).toContain(t('share.folder.nameTaken', { name: 'Briefe' }))
  })
})
