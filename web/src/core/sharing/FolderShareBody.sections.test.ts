// @vitest-environment happy-dom
// Core's folder page of a public share renders the SHARE SECTIONS kinds registered (cr-files-application F2): a kind
// with a `shareSection` has its children rendered by that component, not as cards; the section gets the folder on
// screen, those children with the chain's keys and names, whether the link writes, and `requestGuest` — the body's own
// naming flow. Harness as `FolderShareBody.test.ts`: a stubbed registry (a fresh `createRegistry()` behind a Proxy),
// real crypto for the subtree, a stub section that records its props.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
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
vi.mock('../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
const nav = vi.hoisted(() => ({ route: null as null | { query: Record<string, string>; hash: string } }))
vi.mock('vue-router', () => ({ useRoute: () => nav.route, useRouter: () => ({ push: vi.fn(), resolve: () => ({ href: '/s/stub' }) }) }))

import FolderShareBody from './FolderShareBody.vue'
import ShareNameDialog from './ShareNameDialog.vue'
import { createRegistry, type ShareSectionProps } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import { saveGuestSession, type GuestIdentity } from './guestIdentity'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
import { publicSharesApi } from '../../api/client'
import { stubApi } from '../../test/apiMock'
import { realI18n } from '../../test/i18n'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_3'
const ROOT = '6e5a0000-0000-4000-8000-0000000000e0'
const SUB = '6e5a0000-0000-4000-8000-0000000000e1'
const DOC = '6e5a0000-0000-4000-8000-0000000000e2'
const DEEP = '6e5a0000-0000-4000-8000-0000000000e3'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), sub: v1.randomBytes(32), doc: v1.randomBytes(32), deep: v1.randomBytes(32) }
const DOC_KIND = 'DOC'

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}

function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(SUB, ROOT, keys.root, keys.sub, 'Briefe', FOLDER_KIND),
        child(DOC, ROOT, keys.root, keys.doc, 'Rezept.pdf', DOC_KIND),
        child(DEEP, SUB, keys.sub, keys.deep, 'Tief.txt', DOC_KIND),
      ],
    } as PublicShare,
  }
}

/** The props the section was last handed, for the test to read. */
const seen = vi.hoisted(() => ({ props: null as null | ShareSectionProps }))
const StubSection = defineComponent({
  name: 'StubSection',
  props: ['shareId', 'fragmentKey', 'pwToken', 'head', 'reload', 'folderId', 'nodes', 'keys', 'names', 'canWrite', 'requestGuest'],
  setup: (p) => () => {
    seen.props = p as unknown as ShareSectionProps
    return h('div', { class: 'section-stub' }, (p.nodes as ShareNode[]).map((n) => n.id).join(','))
  },
})

const stubs = {
  Breadcrumb: { props: ['home', 'model'], template: '<nav />' },
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
  Button: { props: ['label', 'type', 'disabled'], emits: ['click'], template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="name-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Message: { template: '<p class="message"><slot /></p>' },
}

function mountBody(h0: PublicShareHead) {
  return mount(FolderShareBody, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: async () => {} },
    global: { plugins: [realI18n()], stubs },
  })
}
const cardNames = (w: ReturnType<typeof mountBody>) => w.findAll('.veyq-node-card:not(.veyq-node-card-create) strong').map((c) => c.text())

beforeEach(() => {
  localStorage.clear()
  seen.props = null
  const r = createRegistry()
  r.registerKind({ kind: DOC_KIND, icon: 'pi pi-file', shareable: false, shareSection: { order: 20, component: StubSection } })
  holder.reg = r
  nav.route = reactive({ query: {} as Record<string, string>, hash: `#k=${b64url(K_SHARE)}` })
})

describe('FolderShareBody renders the share sections of the registered kinds', () => {
  it("hands a section its kind's children at the folder on screen — never as cards — with their keys and names", async () => {
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(cardNames(w), 'the DOC child is the section\'s, not a card').toEqual(['Briefe'])
    expect(w.find('.section-stub').text()).toBe(DOC)
    const p = seen.props!
    expect(p.folderId).toBe(ROOT)
    expect(p.canWrite).toBe(false)
    expect(p.names.get(DOC)).toBe('Rezept.pdf')
    expect(Array.from(p.keys.get(DOC)!), "the child's key from the chain").toEqual(Array.from(keys.doc))
  })

  it('renders the section in a sub-folder with ITS children, and says writable on a READ_WRITE link', async () => {
    nav.route!.query = { node: SUB }
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    expect(w.find('.section-stub').text()).toBe(DEEP)
    expect(seen.props!.folderId).toBe(SUB)
    expect(seen.props!.canWrite).toBe(true)
    expect(cardNames(w)).toEqual([])
  })

  it("a kind without a share section stays a card (the control)", async () => {
    const r = createRegistry()
    r.registerKind({ kind: DOC_KIND, icon: 'pi pi-file', shareable: false })
    holder.reg = r
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(cardNames(w)).toEqual(['Briefe', 'Rezept.pdf'])
    expect(w.find('.section-stub').exists()).toBe(false)
  })
})

describe('FolderShareBody: requestGuest is the body\'s naming flow', () => {
  it('a returning guest gets its session without a dialog', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-0', contributorToken: 'tok-0' })
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    await expect(seen.props!.requestGuest()).resolves.toEqual({ identityId: 'id-0', contributorToken: 'tok-0' })
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)
  })

  it('a new visitor names itself first; the section then gets the identity, and the new-folder dialog does not open', async () => {
    const api = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'x', contributorToken: 'tok-1' } })
    api.createShareIdentity.mockImplementation(async (r) => ({ identityId: r.createShareIdentityRequest.identityId, contributorToken: 'tok-1' }))
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    let got: GuestIdentity | null | undefined
    void seen.props!.requestGuest().then((g) => (got = g))
    await flushPromises()
    await w.find('.dialog-stub .name-input').setValue('Oma')
    await w.find('.dialog-stub form').trigger('submit')
    await flushPromises()
    expect(got?.contributorToken).toBe('tok-1')
    expect(w.findComponent(ShareNameDialog).exists(), 'nothing else opens after the name').toBe(false)
  })

  it('a visitor who closes the name dialog gets null', async () => {
    const w = mountBody(head(ShareRole.ReadWrite))
    await flushPromises()
    let got: GuestIdentity | null | undefined
    void seen.props!.requestGuest().then((g) => (got = g))
    await flushPromises()
    w.findComponent(ShareNameDialog).vm.$emit('close')
    await flushPromises()
    expect(got).toBeNull()
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)
  })
})
