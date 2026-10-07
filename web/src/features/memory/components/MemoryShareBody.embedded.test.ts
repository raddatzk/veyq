// @vitest-environment happy-dom
// The Memories body EMBEDDED by core's folder body (cr-node-key-hierarchy 5b.4b): `subtreeRoot` names a Memory below a
// shared folder. With the REAL `openSharedMemory`: the body shows that Memory (its name from the chain, its media only),
// not the folder link's note; a READ_WRITE link lets the guest add photos there — every upload naming the Memory as its
// target — and names the guest once, under the SHARE ROOT's key; a READ link only looks. The root-level controls (no
// `subtreeRoot`) are the same body on a Memory link, unchanged. The upload queue's pick window and enqueue are stubbed,
// so the test reads what the body hands `addSharePhoto`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../api', () => ({ publicShareMediaApi: vi.fn() }))
vi.mock('../useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))
vi.mock('../share', async (importOriginal) => ({ ...(await importOriginal<typeof import('../share')>()), addSharePhoto: vi.fn() }))
vi.mock('../uploadQueue', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../uploadQueue')>()),
  collectPickedFiles: vi.fn(),
  enqueueUploads: vi.fn(),
}))

import MemoryShareBody from './MemoryShareBody.vue'
import ShareNameDialog from '../../../core/sharing/ShareNameDialog.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { identityNameContext, saveGuestSession } from '../../../core/sharing/guestIdentity'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import { MEMORY_KIND } from '../kinds'
import { addSharePhoto } from '../share'
import { collectPickedFiles, enqueueUploads } from '../uploadQueue'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { publicSharesApi } from '../../../api/client'
import { publicShareMediaApi } from '../api'
import { stubApi } from '../../../test/apiMock'
import { realI18n } from '../../../test/i18n'

const SCOPE_ID = '44444444-4444-4444-8444-444444444444'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_1' // a 43-char base64url id: seals are bound to it
const FOLDER = '6e5a0000-0000-4000-8000-0000000000c0'
const MEMORY = '6e5a0000-0000-4000-8000-0000000000c1'
const SIBLING = '6e5a0000-0000-4000-8000-0000000000c2'
const K_SHARE = v1.randomBytes(32)
const FOLDER_KEY = v1.randomBytes(32)
const MEMORY_KEY = v1.randomBytes(32)
const SIBLING_KEY = v1.randomBytes(32)

const child = (id: string, key: Uint8Array, name: string) => {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId: FOLDER, kind: MEMORY_KIND, epoch: 0, placementVersion: 0, encryptedName: sealName(FOLDER_KEY, address, name), wrappedContentKey: wrapContentKey(FOLDER_KEY, address, key) }
}

/** A folder link: the folder root, the embedded Memory and a sibling Memory. */
function folderHead(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: { en: 'Folder note' }, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, FOLDER_KEY)),
      nodes: [{ id: FOLDER, parentId: null, kind: FOLDER_KIND, epoch: 0 }, child(MEMORY, MEMORY_KEY, 'Rom 2026'), child(SIBLING, SIBLING_KEY, 'Paris')],
    } as PublicShare,
  }
}
/** The same body at the root of a Memory link (the control). */
function memoryHead(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Rom 2026', noteByLocale: { en: 'Memory note' }, role, rootKind: MEMORY_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: MEMORY_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, MEMORY_KEY)),
      nodes: [{ id: MEMORY, parentId: null, kind: MEMORY_KIND, epoch: 0 }],
    } as PublicShare,
  }
}
const row = (nodeId: string, mediaId: string) => ({ nodeId, mediaId, blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: null })

const dialogStubs = {
  Dialog: { props: ['visible', 'header'], template: '<div v-if="visible" class="dialog-stub"><h1 class="header">{{ header }}</h1><slot /></div>' },
  Button: { props: ['label', 'type', 'disabled'], emits: ['click'], template: '<button :type="type || \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input class="name-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Message: { template: '<p class="message"><slot /></p>' },
}

function mountBody(head: PublicShareHead, embedded: boolean) {
  return mount(MemoryShareBody, {
    props: {
      shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head, reload: async () => {},
      ...(embedded ? { subtreeRoot: { nodeId: MEMORY, key: MEMORY_KEY, name: 'Rom 2026' } } : {}),
    },
    global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true, ...dialogStubs } },
  })
}

/** Pick a file and run the upload the body enqueued: what it handed `addSharePhoto`. */
async function uploadOne(w: ReturnType<typeof mountBody>) {
  await w.find('input[type="file"]').trigger('change')
  const [entry] = vi.mocked(enqueueUploads).mock.calls[0]
  await entry.upload!({ blob: new Blob([]), mediaType: 'image/jpeg' }, {})
  return vi.mocked(addSharePhoto).mock.calls[0][0]
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  stubApi(publicShareMediaApi, { listShareMedia: { media: [row(MEMORY, 'm-own'), row(SIBLING, 'm-sibling')], identities: [] } })
  vi.mocked(addSharePhoto).mockResolvedValue({ blobId: 'b', mediaId: 'm' })
  // the pick window settles at once with one file; the enqueue is recorded
  vi.mocked(collectPickedFiles).mockImplementation((_input, done) => {
    done([new File([new Uint8Array(4)], 'a.jpg', { type: 'image/jpeg' })])
    return () => {}
  })
})

describe('MemoryShareBody embedded below a shared folder', () => {
  it("shows the embedded Memory — its name and its media only — and not the folder link's note", async () => {
    const w = mountBody(folderHead(ShareRole.Read), true)
    await flushPromises()
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
    expect(w.find('.gallery-count').text(), "the sibling Memory's media are not counted").toContain('1')
    expect(w.find('.share-note').exists(), 'the folder body shows the note once').toBe(false)
  })

  it('the control: at the root of a Memory link the note shows', async () => {
    const w = mountBody(memoryHead(ShareRole.Read), false)
    await flushPromises()
    expect(w.find('.share-note').text()).toContain('Memory note')
  })

  it('a READ link only looks: no add-photos control, no name prompt', async () => {
    const w = mountBody(folderHead(ShareRole.Read), true)
    await flushPromises()
    expect(w.find('.share-add-button').exists()).toBe(false)
    expect(w.find('.share-name-gate').exists()).toBe(false)
  })

  it('a READ_WRITE link adds photos here: no pre-screen in front of the Memory, the add control shows', async () => {
    const w = mountBody(folderHead(ShareRole.ReadWrite), true)
    await flushPromises()
    expect(w.find('.share-name-gate').exists(), 'no pre-screen for a node the guest browsed into').toBe(false)
    expect(w.find('.share-add-button').exists()).toBe(true)
  })

  it('every upload names the embedded Memory as its target, sealed under its key', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' }) // named on the folder body before
    const w = mountBody(folderHead(ShareRole.ReadWrite), true)
    await flushPromises()
    await w.find('.share-add-button').trigger('click')
    expect(w.findComponent(ShareNameDialog).exists(), 'a named guest is not asked again').toBe(false)
    const sent = await uploadOne(w)
    expect(sent).toMatchObject({ shareId: SHARE_ID, scopeId: SCOPE_ID, contributorToken: 'tok-1', nodeId: MEMORY })
    expect(Array.from(sent.sealKey), "sealed under the Memory's key").toEqual(Array.from(MEMORY_KEY))
  })

  it('the control: at the root of a Memory link no target is named', async () => {
    saveGuestSession(SHARE_ID, { identityId: 'id-1', contributorToken: 'tok-1' })
    const w = mountBody(memoryHead(ShareRole.ReadWrite), false)
    await flushPromises()
    const sent = await uploadOne(w)
    expect(Object.keys(sent)).not.toContain('nodeId')
    expect(Array.from(sent.sealKey)).toEqual(Array.from(MEMORY_KEY))
  })

  it("an unnamed guest is asked once, at its first photo, and named under the FOLDER's key", async () => {
    const { createShareIdentity } = stubApi(publicSharesApi, { createShareIdentity: { identityId: 'x', contributorToken: 'tok-new' } })
    createShareIdentity.mockImplementation(async (r) => ({ identityId: r.createShareIdentityRequest.identityId, contributorToken: 'tok-new' }))
    const w = mountBody(folderHead(ShareRole.ReadWrite), true)
    await flushPromises()
    await w.find('.share-add-button').trigger('click')
    expect(w.findComponent(ShareNameDialog).exists()).toBe(true)
    await w.find('.name-input').setValue('Oma')
    await w.find('.dialog-stub form').trigger('submit')
    await flushPromises()

    const req = createShareIdentity.mock.calls[0][0].createShareIdentityRequest
    const ctx = identityNameContext(SCOPE_ID, SHARE_ID, req.identityId)
    expect(new TextDecoder().decode(v1.open(ctx, FOLDER_KEY, fromB64std(req.sealedDisplayName))), "under the share root's key").toBe('Oma')
    expect(() => v1.open(ctx, MEMORY_KEY, fromB64std(req.sealedDisplayName)), "not the Memory's").toThrow(v1.OpenRefused)
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)
    expect(JSON.parse(localStorage.getItem(`veyq:share-contributor:${SHARE_ID}`)!), 'one session per link').toMatchObject({ contributorToken: 'tok-new' })

    await w.find('.share-add-button').trigger('click') // named now: the picker, no second prompt
    expect(w.findComponent(ShareNameDialog).exists()).toBe(false)
    expect(createShareIdentity).toHaveBeenCalledOnce()
  })
})
