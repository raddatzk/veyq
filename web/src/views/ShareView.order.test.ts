// @vitest-environment happy-dom
// uc.memory.gallery § "One order for every tile — including one that is still uploading", at the **share
// viewer**. The viewer carries its own copy of the grid layout, so the rule has to be proven here too: a
// contributor's in-flight upload sits at its capture-time place among the listed media, and when it settles it
// hands that exact slot to the real tile.
//
// Why its own file rather than a case in ShareView.test.ts: that file's subject is the accountless terms gate,
// and it stubs BOTH heavy media composables inert on purpose. Lifting the `useOptimisticTiles` stub there would
// drag object URLs and real previews into a test about a gate. Here the composable is REAL and the terms fetch
// is made to fail (which fails open by design), so what is under test is the grid and nothing else.
//
// The share is resolved as a VIEWER, although in-flight uploads belong to a contributor: joining as one needs
// a network round-trip (the display-name gate seals a name under the root K_mem and posts it) that this bite
// has no interest in, and the grid does not branch on role — `layoutItems` and `sortedItems` read neither
// `role` nor `isUploader`/`isContributor`; only OptimisticTile's `:deletable` does. The optimistic tiles come
// from the upload queue keyed by shareId, which is role-blind, so the merge under test is the same one.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const SHARE_ID = 's1'
const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
/** The listed media's id — a canonical UUID, since its metadata tier is sealed bound to it (ADR 0072). */
const MEDIA_0 = '5c09e000-0000-4000-8000-0000000000de'
/** Two capture moments, far apart, so nothing below depends on a tie-break it did not mean to test. */
const OLD = Date.UTC(2019, 2, 3)
const NEW = Date.UTC(2024, 8, 9)

vi.mock('exifr', () => ({ default: { parse: vi.fn(async () => undefined) } }))
vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: SHARE_ID }, hash: '#k=AAAA' }) }))
vi.mock('../features/memory/useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), decrypt: vi.fn().mockResolvedValue(undefined), revokeAll: vi.fn() }),
}))

const resolveShareMock = vi.fn()
vi.mock('../features/memory/share', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memory/share')>()
  return {
    ...actual,
    resolveShare: (...args: unknown[]) => resolveShareMock(...args),
    fetchShareTerms: vi.fn().mockRejectedValue(new Error('no terms')), // fails open → no gate, straight to content
  }
})

// The upload queue the viewer subscribes to: a reactive batches array this test drives directly.
const hoisted = vi.hoisted(() => ({ ref: { batches: null as unknown as unknown[] } }))
vi.mock('../features/memory/uploadQueue', async (orig) => {
  const actual = await orig<typeof import('../features/memory/uploadQueue')>()
  const { reactive, computed } = await import('vue')
  const batches = reactive<unknown[]>([])
  hoisted.ref.batches = batches
  return { ...actual, useUploadQueue: () => ({ batches: computed(() => batches), activeFileCount: computed(() => 0) }), retryFile: vi.fn() }
})

import ShareView from './ShareView.vue'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'

/** A shared media whose sealed metadata tier carries `capturedAt` — the key the grid sorts on — sealed bound to
 *  (scope, media) exactly as the media layer does. */
function sharedItem(mediaId: string, capturedAt: number) {
  const memoryKey = v1.randomBytes(32)
  const bytes = new TextEncoder().encode(JSON.stringify({ v: 1, capturedAt }))
  return {
    memoryKey,
    scopeId: SCOPE_ID,
    item: {
      mediaId,
      original: { blobId: `b-${mediaId}`, chunkCount: 1, byteSize: 1, wrappedBlobKey: 'x', ownerId: mediaId, role: 'ORIGINAL' as const },
      derivatives: [],
      wrappedMeta: b64std(v1.seal({ purpose: 'media-meta', scopeId: SCOPE_ID, mediaId }, memoryKey, bytes)),
    },
  }
}

function uploadFile(id: string, status: string, opts: { mediaId?: string; lastModified?: number } = {}) {
  return {
    id, name: `${id}.jpg`, sizeBytes: 3, mediaType: 'image/jpeg', status,
    progress: status === 'done' ? 1 : 0.4, attempts: 1, controller: new AbortController(),
    file: new File([new Uint8Array([1, 2, 3])], `${id}.jpg`, { type: 'image/jpeg', lastModified: opts.lastModified }),
    mediaId: opts.mediaId,
  }
}

function pushBatch(files: unknown[]) {
  hoisted.ref.batches.push({
    id: 'b-0', label: `${files.length}`, target: { key: SHARE_ID }, // the grouping key a share batch carries
    files, seq: 0, finishedAt: null, acknowledged: false,
  })
}

/** Mark a queued file settled onto a real media, the way the queue does on `done`. */
function settle(fileIndex: number, mediaId: string) {
  const batch = hoisted.ref.batches[0] as { files: { status: string; mediaId?: string }[] }
  batch.files[fileIndex].status = 'done'
  batch.files[fileIndex].mediaId = mediaId
}

function gridOrder(wrapper: ReturnType<typeof mount>): string[] {
  const grid = wrapper.find('.media-grid').element
  return Array.from(grid.children).map(
    (el) => el.getAttribute('data-media-id') ?? el.querySelector('img')?.getAttribute('alt') ?? '?',
  )
}

beforeEach(() => {
  localStorage.clear()
  hoisted.ref.batches.length = 0
  resolveShareMock.mockReset()
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})

describe('ShareView — one order for every grid tile', () => {
  it('a settling upload hands its grid slot to the real tile — the position does not move', async () => {
    resolveShareMock.mockResolvedValue({
      memoryName: 'My Memory', noteByLocale: {}, role: ShareRole.Viewer, scopeId: SCOPE_ID,
      access: { kind: 'read', memoryKey: v1.randomBytes(32) }, items: [sharedItem(MEDIA_0, OLD)],
    })
    const wrapper = mount(ShareView, { global: { plugins: [realI18n()], stubs: { MediaLightbox: true } } })
    await flushPromises()
    await flushPromises()

    pushBatch([uploadFile('u1', 'uploading', { lastModified: OLD }), uploadFile('u2', 'uploading', { lastModified: NEW })])
    await flushPromises()
    await flushPromises()

    // newest first (the viewer has no toggle); u1 ties with the media it will become and leads it
    expect(gridOrder(wrapper)).toEqual(['u2.jpg', 'u1.jpg', MEDIA_0])
    const slotBefore = gridOrder(wrapper).indexOf('u1.jpg')

    settle(0, MEDIA_0)
    await flushPromises()

    const after = gridOrder(wrapper)
    expect(after).not.toContain('u1.jpg') // handed off, not duplicated
    expect(after.indexOf(MEDIA_0)).toBe(slotBefore)
  })
})
