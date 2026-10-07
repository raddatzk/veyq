// @vitest-environment happy-dom
// Core's body of the public page of a FOLDER link (cr-node-key-hierarchy 5b.4b), against a STUBBED registry (a fresh
// `createRegistry()` behind a delegating Proxy, as `views/ShareView.registry.test.ts`): the test registers the kinds and
// the share bodies "an application" would, with stub bodies that render what they were handed. The subtree is real
// crypto (names and keys open through the chain); the API client is stubbed, the router is a reactive route + `push`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, onMounted, reactive } from 'vue'
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
vi.mock('../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
const nav = vi.hoisted(() => ({ route: null as null | { query: Record<string, string>; hash: string }, push: null as null | ((to: unknown) => Promise<void>) }))
vi.mock('vue-router', () => ({ useRoute: () => nav.route, useRouter: () => ({ push: nav.push, resolve: () => ({ href: '/s/stub' }) }) }))

import FolderShareBody from './FolderShareBody.vue'
import ShareNameDialog from './ShareNameDialog.vue'
import { createRegistry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import { identityNameContext, saveGuestSession } from './guestIdentity'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { realI18n, t } from '../../test/i18n'

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

// Kinds an application would register: one with a share body, one whose body reports its node unusable. 'EXPENSE' is
// registered by nobody.
const NOTE_KIND = 'NOTE'
const BROKEN_KIND = 'BROKEN'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}

function head(role: ShareRole = ShareRole.Read, extra: ShareNode[] = []): PublicShareHead {
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
        ...extra,
      ],
    } as PublicShare,
  }
}

/** A body that renders what the folder body handed it. */
const StubBody = defineComponent({
  name: 'StubBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'subtreeRoot'],
  emits: ['unavailable'],
  setup: (p) => () => h('div', { class: 'embedded-stub' }, `${p.subtreeRoot?.nodeId}|${p.subtreeRoot?.name}|${p.shareId}`),
})
/** A body that finds its node unusable as soon as it mounts. */
const BrokenBody = defineComponent({
  name: 'BrokenBody',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'subtreeRoot'],
  emits: ['unavailable'],
  setup: (_p, { emit }) => {
    onMounted(() => emit('unavailable'))
    return () => h('div', { class: 'broken-stub' })
  },
})

// This Breadcrumb stub pins only WHERE a crumb pushes; the real rendered `<a>` (its href, its prevented default action)
// is clicked in `FolderShareBody.breadcrumb.test.ts` — the stub could not see the fragment the browser lost.
const stubs = {
  Breadcrumb: {
    props: ['home', 'model'],
    template: '<nav class="crumbs"><a class="crumb crumb-home" @click="home.command({ originalEvent: $event })">{{ home.label }}</a><a v-for="m in model" :key="m.label" class="crumb" @click="m.command({ originalEvent: $event })">{{ m.label }}</a></nav>',
  },
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
  Button: { props: ['label', 'type', 'disabled'], emits: ['click'], template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="name-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Message: { template: '<p class="message"><slot /></p>' },
}

function mountBody(h0: PublicShareHead, reload: () => Promise<void> = async () => {}) {
  return mount(FolderShareBody, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload },
    global: { plugins: [realI18n()], stubs },
  })
}
const cardNames = (w: ReturnType<typeof mountBody>) => w.findAll('.veyq-node-card:not(.veyq-node-card-create) strong').map((c) => c.text())
const card = (w: ReturnType<typeof mountBody>, id: string) => w.find(`[data-node-id="${id}"]`)
const newFolderCard = (w: ReturnType<typeof mountBody>) => w.find('.veyq-node-card-create')
async function submitName(w: ReturnType<typeof mountBody>, name: string) {
  await w.find('.dialog-stub .name-input').setValue(name)
  await w.find('.dialog-stub form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  const r = createRegistry()
  r.registerKind({ kind: NOTE_KIND, icon: 'pi pi-file', shareable: true })
  r.registerKind({ kind: BROKEN_KIND, icon: 'pi pi-file', shareable: true })
  r.claimShareBody(NOTE_KIND, StubBody)
  r.claimShareBody(BROKEN_KIND, BrokenBody)
  holder.reg = r
  const route = reactive({ query: {} as Record<string, string>, hash: HASH })
  nav.route = route
  nav.push = vi.fn(async (to: unknown) => {
    route.query = (to as { query: Record<string, string> }).query
  })
})

describe('FolderShareBody browses the shared folder', () => {
  it("shows the folder's name, the note once, and its children's names opened through the chain", async () => {
    const w = mountBody(head())
    await flushPromises()
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.veyq-section-header h1').text()).toBe('Familie')
    expect(w.findAll('.markdown-body')).toHaveLength(1)
    expect(w.find('.markdown-body').text()).toContain('Folder note')
    expect(cardNames(w)).toEqual(['Ausgaben', 'Briefe', 'Kaputt', 'Notizen'])
    expect(card(w, LETTERS).find('.pi').classes()).toContain('pi-folder') // the icon is the registry's
    expect(card(w, ALIEN).find('.pi').classes()).toContain('pi-question-circle') // a kind nobody registered
  })

  it('a folder child navigates into it, keeping the #k= fragment; its children show there', async () => {
    const w = mountBody(head())
    await flushPromises()
    await card(w, LETTERS).trigger('click')
    expect(nav.push).toHaveBeenCalledWith({ query: { node: LETTERS }, hash: HASH })
    await flushPromises()
    expect(w.find('.veyq-section-header h1').text()).toBe('Briefe')
    expect(cardNames(w)).toEqual(['Feldpost'])
  })

  it('an empty folder says so', async () => {
    nav.route!.query = { node: NESTED }
    const w = mountBody(head())
    await flushPromises()
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.empty'))
  })

  it('the breadcrumb runs along the parent chain and navigates up, keeping the fragment', async () => {
    nav.route!.query = { node: NESTED }
    const w = mountBody(head())
    await flushPromises()
    expect(w.findAll('.crumb').map((c) => c.text())).toEqual(['Familie', 'Briefe', 'Feldpost'])
    await w.findAll('.crumb')[1].trigger('click')
    expect(nav.push).toHaveBeenLastCalledWith({ query: { node: LETTERS }, hash: HASH })
    await w.find('.crumb-home').trigger('click')
    expect(nav.push).toHaveBeenLastCalledWith({ query: {}, hash: HASH })
    await flushPromises()
    expect(w.find('.veyq-section-header h1').text()).toBe('Familie')
  })

  it('an id the walk did not open falls back to the root', async () => {
    nav.route!.query = { node: '6e5a0000-0000-4000-8000-0000000000ff' }
    const w = mountBody(head())
    await flushPromises()
    expect(w.find('.veyq-section-header h1').text()).toBe('Familie')
  })
})

describe('FolderShareBody embeds what is not a folder', () => {
  it("embeds the body claimed for the child's kind, handing it the node's id, key and name from the chain", async () => {
    const w = mountBody(head())
    await flushPromises()
    await card(w, NOTE).trigger('click')
    expect(nav.push).toHaveBeenCalledWith({ query: { node: NOTE }, hash: HASH })
    await flushPromises()
    expect(w.find('.embedded-stub').text()).toBe(`${NOTE}|Notizen|${SHARE_ID}`)
    const root = w.findComponent(StubBody).props('subtreeRoot') as { key: Uint8Array }
    expect(Array.from(root.key), "the node's key, opened through the chain").toEqual(Array.from(keys.note))
    expect(w.findAll('.crumb').map((c) => c.text())).toEqual(['Familie', 'Notizen'])
    expect(w.findAll('.markdown-body'), 'the note stays once, above the embedded body').toHaveLength(1)
  })

  it('a kind nobody claimed is a local empty state, never the page\'s unavailable state', async () => {
    nav.route!.query = { node: ALIEN }
    const w = mountBody(head())
    await flushPromises()
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.unsupportedKind'))
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it('an embedded body that reports its node unusable becomes a local empty state too', async () => {
    nav.route!.query = { node: BROKEN }
    const w = mountBody(head())
    await flushPromises()
    expect(w.find('.broken-stub').exists()).toBe(false)
    expect(w.find('.veyq-empty').text()).toBe(t('share.folder.gone'))
    expect(w.emitted('unavailable')).toBeUndefined()
  })

  it('a share the fragment key does not open is the page\'s unavailable state', async () => {
    const bad = head()
    bad.share = { ...bad.share, wrap: b64std(v1.seal({ purpose: 'share-key' }, v1.randomBytes(32), keys.root)) }
    const w = mountBody(bad)
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })
})

describe('FolderShareBody: a READ_WRITE guest adds folders', () => {
  it('a READ guest sees no write control; a READ_WRITE guest sees "New folder"', async () => {
    const read = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(newFolderCard(read).exists()).toBe(false)
    expect(read.find('.veyq-section-header span').text()).toBe(t('share.folder.readonly'))
    const write = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    expect(newFolderCard(write).text()).toBe(t('share.folder.newFolder'))
    expect(write.find('.veyq-section-header span').text()).toBe(t('share.folder.contributorBadge'))
  })

  it("asks the guest's name once, under the folder's key, then creates the folder under the CURRENT folder", async () => {
    const api = stubApi(publicSharesApi, {
      createShareIdentity: { identityId: 'x', contributorToken: 'tok-1' },
      createShareNode: { nodeId: 'x' },
    })
    api.createShareIdentity.mockImplementation(async (r) => ({ identityId: r.createShareIdentityRequest.identityId, contributorToken: 'tok-1' }))
    api.createShareNode.mockImplementation(async ({ createShareNodeRequest }) => ({ nodeId: createShareNodeRequest.nodeId }))
    const reload = vi.fn(async () => {})
    nav.route!.query = { node: LETTERS }
    const w = mountBody(head(ShareRole.ReadWrite), reload)
    await flushPromises()

    await newFolderCard(w).trigger('click')
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.folder.guestNameTitle'))
    await submitName(w, 'Oma')
    const named = api.createShareIdentity.mock.calls[0][0].createShareIdentityRequest
    const ctx = identityNameContext(SCOPE_ID, SHARE_ID, named.identityId)
    expect(new TextDecoder().decode(v1.open(ctx, keys.root, fromB64std(named.sealedDisplayName))), "under the share root's key").toBe('Oma')

    expect(w.find('.dialog-stub .header').text(), 'named, the guest goes on to the folder it asked for').toBe(t('share.folder.newFolderTitle'))
    await submitName(w, 'Urlaub 1998')
    expect(api.createShareNode).toHaveBeenCalledOnce()
    const created = api.createShareNode.mock.calls[0][0]
    expect(created.xContributorToken).toBe('tok-1')
    expect(created.createShareNodeRequest).toMatchObject({ parentId: LETTERS, kind: FOLDER_KIND })
    expect(reload).toHaveBeenCalledOnce()
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)

    await newFolderCard(w).trigger('click') // named now: straight to the folder's name
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.folder.newFolderTitle'))
    expect(api.createShareIdentity).toHaveBeenCalledOnce()
  })

  it('a returning guest is not asked: its session on this device is loaded', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    await newFolderCard(w).trigger('click')
    expect(w.find('.dialog-stub .header').text()).toBe(t('share.folder.newFolderTitle'))
  })

  it('a name a sibling already takes is refused before sending, naming the sibling', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const api = stubApi(publicSharesApi, { createShareNode: { nodeId: 'x' } })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    await newFolderCard(w).trigger('click')
    await submitName(w, 'briefe')
    expect(api.createShareNode).not.toHaveBeenCalled()
    expect(w.find('.dialog-stub .message').text()).toBe(t('share.folder.nameTaken', { name: 'Briefe' }))
  })
})
