// @vitest-environment happy-dom
// The Memories body of the public share page with the REAL `openSharedMemory` (the shell tests stub it): the body is
// what turns the shell's head into a rendering, and it is the one that reports a share it cannot open. Mounted bare —
// the password/terms gates and the registry dispatch are the shell's (views/ShareView*.test.ts).
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
// The media are Memories' own guest read (`share-media`), built in `../api`.
vi.mock('../api', () => ({ publicShareMediaApi: vi.fn() }))
vi.mock('../useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))

import MemoryShareBody from './MemoryShareBody.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { MEMORY_KIND } from '../kinds'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { realI18n } from '../../../test/i18n'
import { publicShareMediaApi } from '../api'
import { fakeApi, stubApi } from '../../../test/apiMock'
import type { ShareMediaApi } from '../../../api/generated/share-media'

const K_SHARE = v1.randomBytes(32)

function head(share: Partial<PublicShare>, role: ShareRole = ShareRole.Read): PublicShareHead {
  return {
    shareId: 's1', share: { role, scopeId: 'scope-1', rootKind: MEMORY_KIND, nodes: [], ...share } as PublicShare, kShare: K_SHARE,
    scopeId: 'scope-1', name: 'Rom 2026', noteByLocale: {}, role, rootKind: MEMORY_KIND,
  }
}

function mountBody(h: PublicShareHead) {
  return mount(MemoryShareBody, {
    props: { shareId: 's1', fragmentKey: 'AAAA', pwToken: undefined, head: h, reload: async () => {} },
    global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
  })
}

const ROOT_NODES = [{ id: 'root', parentId: null, kind: MEMORY_KIND, epoch: 0 }] as PublicShare['nodes']
const ROW = { nodeId: 'root', mediaId: 'm-1', blobId: 'b-1', chunkCount: 1, byteSize: 10, wrappedBlobKey: 'aa==', wrappedMeta: null }
const openWrap = () => b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, v1.randomBytes(32)))

describe('MemoryShareBody opens the Memory from the shell\'s head', () => {
  it('renders the read-only Memory when the wrap opens under the fragment key', async () => {
    stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [] } })
    const w = mountBody(head({ wrap: openWrap(), nodes: ROOT_NODES }))
    await flushPromises()
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
  })

  it('lists the media from Memories\' own read, asked with the guest\'s token', async () => {
    const { listShareMedia } = stubApi(publicShareMediaApi, { listShareMedia: { media: [ROW], identities: [] } })
    const w = mount(MemoryShareBody, {
      props: { shareId: 's1', fragmentKey: 'AAAA', pwToken: 'tok', head: head({ wrap: openWrap(), nodes: ROOT_NODES }), reload: async () => {} },
      global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
    })
    await flushPromises()
    expect(listShareMedia).toHaveBeenCalledWith({ shareId: 's1', xSharePassword: 'tok' })
    expect(w.find('.gallery-count').text()).toContain('1')
    expect(w.find('.veyq-empty').exists()).toBe(false)
  })

  it('shows no count and no empty state while the media read is in flight', async () => {
    fakeApi(publicShareMediaApi, { listShareMedia: vi.fn<ShareMediaApi['listShareMedia']>(() => new Promise(() => {})) })
    const w = mountBody(head({ wrap: openWrap(), nodes: ROOT_NODES }))
    await flushPromises()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
    expect(w.find('.gallery-count').exists()).toBe(false)
    expect(w.find('.veyq-empty').exists()).toBe(false) // an empty Memory and one still loading are not the same
  })

  it('reports the share unavailable when the media read is refused (the link went away after its resolve)', async () => {
    fakeApi(publicShareMediaApi, { listShareMedia: vi.fn<ShareMediaApi['listShareMedia']>().mockRejectedValue(new Error('404')) })
    const w = mountBody(head({ wrap: openWrap(), nodes: ROOT_NODES }))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })

  it('reports an unusable share instead of rendering one: a viewer response without the wrap it needs', async () => {
    const w = mountBody(head({ wrap: undefined }))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })

  it('…and a wrap sealed under another key', async () => {
    const wrap = b64std(v1.seal({ purpose: 'share-key' }, v1.randomBytes(32), v1.randomBytes(32)))
    const w = mountBody(head({ wrap }))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })

  it('…and an uploader response without the drop key it needs', async () => {
    const w = mountBody(head({ nodeId: 'drop-node', sealedDropPublicKey: undefined }, ShareRole.WriteOnly))
    await flushPromises()
    expect(w.emitted('unavailable')).toHaveLength(1)
  })
})
