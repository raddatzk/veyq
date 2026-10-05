// @vitest-environment happy-dom
// The Memories body of the public share page with the REAL `openSharedMemory` (the shell tests stub it): the body is
// what turns the shell's head into a rendering, and it is the one that reports a share it cannot open. Mounted bare —
// the password/terms gates and the registry dispatch are the shell's (views/ShareView*.test.ts).
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))

import MemoryShareBody from './MemoryShareBody.vue'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { NodeKind } from '../../../api/generated/content'
import { ShareRole, type PublicShare } from '../../../api/generated/sharing'
import { realI18n } from '../../../test/i18n'

const K_SHARE = v1.randomBytes(32)

function head(share: Partial<PublicShare>, role: ShareRole = ShareRole.Read): PublicShareHead {
  return {
    shareId: 's1', share: { role, scopeId: 'scope-1', nodes: [], media: [], ...share } as PublicShare, kShare: K_SHARE,
    scopeId: 'scope-1', name: 'Rom 2026', noteByLocale: {}, role, rootKind: role === ShareRole.WriteOnly ? null : NodeKind.Memory,
  }
}

function mountBody(h: PublicShareHead) {
  return mount(MemoryShareBody, {
    props: { shareId: 's1', fragmentKey: 'AAAA', pwToken: undefined, head: h, reload: async () => {} },
    global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
  })
}

describe('MemoryShareBody opens the Memory from the shell\'s head', () => {
  it('renders the read-only Memory when the wrap opens under the fragment key', async () => {
    const wrap = b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, v1.randomBytes(32)))
    const w = mountBody(head({ wrap, nodes: [{ id: 'root', parentId: null, kind: NodeKind.Memory, epoch: 0 }] as PublicShare['nodes'] }))
    await flushPromises()
    expect(w.emitted('unavailable')).toBeUndefined()
    expect(w.find('.gallery-title').text()).toBe('Rom 2026')
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
