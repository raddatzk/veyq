// @vitest-environment happy-dom
// Core's folder page of a public share renders each kind's LEVEL section for a guest, as the member's level does (inbox
// #401: S2 the page became core's level view, S3 retired the guest-only `shareSection`): the section gets the folder on
// screen, that kind's children, the guest's `access` — its role and the page's one naming flow, the shell's
// (`GuestAccess.requestGuest`) — and the guest's `tree` with the chain's keys and names. Harness: a stubbed registry (a
// fresh `createRegistry()` behind a Proxy), real crypto for the subtree, a stub level section that records its props.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { guestAccessFor } from '../../test/models'
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

import ShareTreePage from './ShareTreePage.vue'
import { createRegistry, type LevelSectionProps } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import type { PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'
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
const seen = vi.hoisted(() => ({ props: null as null | LevelSectionProps }))
/** A kind's level section (what a member sees): lists its nodes, records its props. */
const LevelStub = defineComponent({
  name: 'LevelStub',
  props: ['folder', 'nodes', 'epochs', 'access', 'tree'],
  setup: (p) => () => {
    seen.props = p as unknown as LevelSectionProps
    return h('div', { class: 'level-stub' }, (p.nodes as { id: string }[]).map((n) => n.id).join(','))
  },
})

const stubs = {
  Breadcrumb: { props: ['home', 'model'], template: '<nav />' },
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
  Button: { props: ['label', 'type', 'disabled'], emits: ['click'], template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="name-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Message: { template: '<p class="message"><slot /></p>' },
}

function mountBody(h0: PublicShareHead, access = guestAccessFor(h0)) {
  return mount(ShareTreePage, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h0, reload: async () => {}, access },
    global: { plugins: [realI18n()], stubs },
  })
}
const cardNames = (w: ReturnType<typeof mountBody>) => w.findAll('.veyq-folder-card:not(.veyq-node-header) strong').map((c) => c.text())

beforeEach(() => {
  localStorage.clear()
  seen.props = null
  const r = createRegistry()
  r.registerKind({ kind: DOC_KIND, icon: 'pi pi-file', shareable: false, section: { order: 20, component: LevelStub } })
  holder.reg = r
  nav.route = reactive({ query: {} as Record<string, string>, hash: `#k=${b64url(K_SHARE)}` })
})

describe("ShareTreePage renders the registered kinds' level sections for a guest", () => {
  it("hands a section its kind's children at the folder on screen — never as cards — with the guest's access and tree", async () => {
    const w = mountBody(head(ShareRole.Read))
    await flushPromises()
    expect(cardNames(w), "the DOC child is the section's, not a folder card").toEqual(['Briefe'])
    expect(w.find('.level-stub').text()).toBe(DOC)
    const p = seen.props!
    expect(p.folder?.id).toBe(ROOT)
    expect(p.epochs, 'a guest has no epoch keys: its keys come through the tree').toBeNull()
    expect(p.access).toMatchObject({ mode: 'guest', shareId: SHARE_ID, role: ShareRole.Read })
    expect(p.tree.nameOf(DOC)).toBe('Rezept.pdf')
    expect(Array.from(p.tree.keyOf(DOC)!), "the child's key from the chain").toEqual(Array.from(keys.doc))
    expect(p.tree.linkTo(DOC), 'a link keeps the share key').toEqual({ query: { node: DOC }, hash: `#k=${b64url(K_SHARE)}` })
  })

  it('renders the section in a sub-folder with ITS children, handed the link as it is (READ_WRITE)', async () => {
    nav.route!.query = { node: SUB }
    const h0 = head(ShareRole.ReadWrite)
    const w = mountBody(h0)
    await flushPromises()
    expect(w.find('.level-stub').text()).toBe(DEEP)
    expect(seen.props!.folder?.id).toBe(SUB)
    expect(seen.props!.access.mode === 'guest' && seen.props!.access.role).toBe(ShareRole.ReadWrite)
    expect(cardNames(w)).toEqual([])
  })
})

describe("ShareTreePage: a section's requestGuest is the page's one naming flow", () => {
  it("hands the section the shell's flow (GuestAccess.requestGuest) — no dialog of the body's own", async () => {
    const requestGuest = vi.fn(async () => ({ identityId: 'id-0', contributorToken: 'tok-0' }))
    const h0 = head(ShareRole.ReadWrite)
    const w = mountBody(h0, guestAccessFor(h0, { requestGuest }))
    await flushPromises()
    const access = seen.props!.access
    if (access.mode !== 'guest') throw new Error('a guest page handed a member access')
    await expect(access.requestGuest()).resolves.toEqual({ identityId: 'id-0', contributorToken: 'tok-0' })
    expect(requestGuest).toHaveBeenCalledOnce()
    expect(w.find('.dialog-stub').exists(), 'the body opens no name dialog').toBe(false)
  })
})
