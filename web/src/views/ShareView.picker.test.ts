// @vitest-environment happy-dom
// uc.memory.upload E3 at the READ_WRITE guest's picker (uc.sharing.share-contributor / uc.sharing.share-uploader,
// step 4 of each). E3 is a property of **the picker**, not of one screen: a visitor adding photos from Safari's
// Photos gallery hits the same late/repeated `input.files` population the curator does, and a picker that reads
// the input once loses whatever the browser had not populated yet — silently, which is the worst way to lose a
// contributed photo.
//
// The three tests below are a set, and each has its own lever (see the CR that introduced them):
//   1. the late file arrives at the queue at all   — reddened by removing the re-read window
//   2. the in-hand files do NOT wait for it        — reddened by gathering first and enqueueing once
//   3. the input is cleared AFTER the window       — reddened by clearing inside the pick, which also reddens 1
// Without 2 and 3 a green 1 cannot say which half of the change is load-bearing.
//
// `MemoryDetail.optimistic.test.ts` covers the same window on the curator's side and is the reference for the
// module-mock shape: `mergeFiles` / `isSupportedMediaFile` stay REAL through `...actual`, only `enqueueUploads`
// is a spy, so what is exercised is the picker's own de-dupe rather than a stub of it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const SHARE_ID = 's1'
const SESSION_KEY = `veyq:share-contributor:${SHARE_ID}`

// The two heavy media composables → inert stubs (no crypto, no object URLs, no timers of their own).
vi.mock('../features/memory/useOptimisticTiles', () => ({
  useOptimisticTiles: () => ({ optimisticTiles: { value: [] }, donorPreviewFor: () => undefined }),
}))
vi.mock('../features/memory/useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({
    thumbs: new Map(),
    reconcile: vi.fn(),
    decrypt: vi.fn().mockResolvedValue(undefined),
    revokeAll: vi.fn(),
  }),
}))

vi.mock('../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { shareId: SHARE_ID }, hash: '#k=AAAA' }) }))

// The shell resolves the platform half and fetches the terms; the Memories body opens the rest. Only those three
// reads are stubbed — the shell, the registry, the body and the helpers it uses are real.
vi.mock('../core/sharing/resolvePublicShare', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../core/sharing/resolvePublicShare')>()
  return { ...actual, resolvePublicShare: vi.fn(), fetchShareTerms: vi.fn() }
})
vi.mock('../features/memory/share', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memory/share')>()
  return { ...actual, openSharedMemory: vi.fn() }
})

vi.mock('../features/memory/uploadQueue', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../features/memory/uploadQueue')>()
  return { ...actual, enqueueUploads: vi.fn(), retryFile: vi.fn(), removeFile: vi.fn() }
})

import { mount, flushPromises } from '@vue/test-utils'
import ShareView from './ShareView.vue'
import { enqueueUploads } from '../features/memory/uploadQueue'
import { resolvePublicShare, fetchShareTerms } from '../core/sharing/resolvePublicShare'
import { openSharedMemory, type ResolvedShare } from '../features/memory/share'
import { headOf, openedOf } from '../test/shareHead'
import { ShareRole } from '../api/generated/sharing'
import { realI18n } from '../test/i18n'

function mountShareView() {
  return mount(ShareView, {
    global: { plugins: [realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
  })
}

/**
 * The hidden file input, driven the way Safari's Photos picker drives it: at pick time the browser hands the
 * element a list and then goes on **filling that same list** for a while. `input.value = ''` does not stop the
 * browser — it swaps a fresh, empty list into the element, and everything that arrives afterwards lands in a
 * list the page can no longer read. That is the exact mechanism by which a clear inside the pick loses files,
 * so the harness models it instead of assuming it away; a harness that re-assigned `files` on every late
 * arrival would let a too-early clear pass, and test 3's lever would prove nothing.
 */
function installPicker(el: HTMLInputElement) {
  let live: File[] = []
  let handedOver: File[] = []
  Object.defineProperty(el, 'files', { get: () => live, configurable: true })
  Object.defineProperty(el, 'value', {
    get: () => (live.length > 0 ? `C:\\fakepath\\${live[0].name}` : ''),
    set: (v: string) => { if (v === '') live = [] },
    configurable: true,
  })
  return {
    /** The visitor picks: the browser hands over a list holding what it has so far. */
    pick(files: File[]) { handedOver = files.slice(); live = handedOver },
    /** Safari finishes populating the list it already handed over. */
    arriveLate(file: File) { handedOver.push(file) },
  }
}

const jpg = (name: string) => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/jpeg' })

/** Every file handed to the upload queue so far, across all batches, in order. */
function enqueuedNames(): string[] {
  return vi.mocked(enqueueUploads).mock.calls.flatMap((call) => call[1].map((s) => s.file.name))
}

/** Serve one resolved share to the shell (head) and the Memories body (the rest), as production splits it. */
function serve(r: ResolvedShare) {
  vi.mocked(resolvePublicShare).mockReset().mockResolvedValue(headOf(r))
  vi.mocked(openSharedMemory).mockReset().mockReturnValue(openedOf(r))
}

describe('ShareView — the contributor picker inherits E3s re-read window', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem(SESSION_KEY, JSON.stringify({ identityId: 'i-1', contributorToken: 'tok' }))
    vi.mocked(enqueueUploads).mockReset()
    // No terms → no gate in front of the content (ShareView fails open on a terms-fetch error).
    vi.mocked(fetchShareTerms).mockReset().mockRejectedValue(new Error('no terms in this test'))
    serve({
      memoryName: 'Rom 2026',
      noteByLocale: {},
      role: ShareRole.ReadWrite,
      // the shared Memory's scope: a contributor's uploads are sealed bound to it, so without one nothing enqueues
      scopeId: '5c09e000-0000-4000-8000-0000000000df',
      access: { kind: 'read', memoryKey: new Uint8Array(32) },
      items: [],
    })
  })
  afterEach(() => { vi.useRealTimers() })

  /** Mount a named-in READ_WRITE share and return its wrapper + a Safari-shaped driver for its file input. */
  async function mountWithPicker() {
    const wrapper = mountShareView()
    await flushPromises()
    const input = wrapper.find('input[type="file"]')
    return { wrapper, input, picker: installPicker(input.element as HTMLInputElement) }
  }

  // 1 — the live defect. A single read of `input.files` drops whatever Safari had not populated yet, and the
  // visitor is told nothing. If this fails with `['a.jpg']` the view mounted and the picker fired; what is
  // missing is only the re-read.
  it('enqueues a file Safari delivers late instead of losing it silently', async () => {
    const { input, picker } = await mountWithPicker()
    vi.useFakeTimers()

    picker.pick([jpg('a.jpg')])
    await input.trigger('change')
    picker.arriveLate(jpg('b.jpg'))
    await vi.advanceTimersByTimeAsync(4000)

    expect(enqueuedNames()).toEqual(['a.jpg', 'b.jpg'])
  })

  // 2 — the control on 1: the window must not become a quiet period everyone pays for. A picker that gathered
  // across the window and enqueued once would satisfy 1 perfectly and reintroduce exactly the regression
  // E3's last sentence records.
  it('hands over the files already in hand before any re-read runs', async () => {
    const { input, picker } = await mountWithPicker()
    vi.useFakeTimers()

    picker.pick([jpg('a.jpg')])
    await input.trigger('change')

    // no timer has advanced — this is the whole point
    expect(vi.mocked(enqueueUploads)).toHaveBeenCalledTimes(1)
    expect(vi.mocked(enqueueUploads).mock.calls[0][1].map((s) => s.file.name)).toEqual(['a.jpg'])
  })

  // 3 — the other control on 1: the clear belongs to the window's teardown, not to the pick. Clearing in the
  // same tick as the read re-creates the loss in 1 while every timer still looks correct.
  it('clears the input after the window closes, not inside the pick', async () => {
    const { input, picker } = await mountWithPicker()
    const el = input.element as HTMLInputElement
    vi.useFakeTimers()

    picker.pick([jpg('a.jpg')])
    await input.trigger('change')
    expect(el.value).not.toBe('') // the window is still reading this input

    await vi.advanceTimersByTimeAsync(4000)
    expect(el.value).toBe('') // torn down with the window, so the same file can be re-picked
  })

  // The window owns its teardown: an unmount mid-window cancels the pending re-reads instead of leaving four
  // timers firing at a component that is gone.
  it('stops re-reading when the view unmounts mid-window', async () => {
    const { wrapper, input, picker } = await mountWithPicker()
    vi.useFakeTimers()

    picker.pick([jpg('a.jpg')])
    await input.trigger('change')
    picker.arriveLate(jpg('b.jpg'))
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(4000)

    expect(enqueuedNames()).toEqual(['a.jpg'])
  })
})
