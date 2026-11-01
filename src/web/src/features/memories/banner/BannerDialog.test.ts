// @vitest-environment happy-dom
// uc.memory.banner - the banner dialog: what it takes and refuses before anything is read further, the two previews (the
// whole medium, nothing to frame: ADR 0102, amended 2026-11-01), and the intent it saves - awaited, with its progress, its
// cancel and its failure said in words.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { DOMWrapper, mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { realI18n, t } from '../../../test/i18n'
import BannerDialog from './BannerDialog.vue'
import { BannerStaleError, MAX_BANNER_IMAGE_BYTES } from './bannerWrite'
import type { Banner } from './bannerModel'
import type { MediaDeriver } from '../../../shared/image/mediaDeriver'
import type { BannerSaveIntent, BannerSaveOpts } from './useBannerEditor'

const stored: Banner = {
  medium: 'image', blob: { blobId: 'b', chunkCount: 1, byteSize: 9, wrappedBlobKey: 'k', ownerId: 'n', role: 'BANNER' },
  frame: 'fill', focal: { x: 0.3, y: 0.4 },
}

let mounted: VueWrapper[] = []
/** The dialog is teleported into the body (the dialog's portal): it is read there. */
const page = () => new DOMWrapper(document.body)

async function mountDialog(props: Record<string, unknown> = {}) {
  const save = vi.fn<(intent: BannerSaveIntent, opts: BannerSaveOpts) => Promise<void>>(async () => undefined)
  const deriver = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])) } as unknown as MediaDeriver
  const wrapper = mount(BannerDialog, {
    props: { current: null, currentSrc: null, save, deriver, ...props },
    global: { plugins: [realI18n()] },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  await vi.waitFor(() => expect(document.body.querySelector('[role="dialog"]'), 'the dialog renders').not.toBeNull())
  return { wrapper: page(), emitted: wrapper.emitted.bind(wrapper), save, deriver }
}

type Page = DOMWrapper<Element>

async function pick(wrapper: Page, file: File) {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

const previewSrc = (wrapper: Page, which: 'wide' | 'phone') =>
  wrapper.find(`[data-testid="banner-preview-${which}"] img.banner-frame-media`)

const button = (wrapper: Page, label: string) =>
  wrapper.findAll('button').find((b) => b.text().includes(label) || b.attributes('aria-label') === label)!

beforeEach(async () => {
  vi.clearAllMocks()
  mounted.forEach((w) => w.unmount())
  mounted = []
  document.body.innerHTML = ''
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:picked'), revokeObjectURL: vi.fn() })
})

describe('BannerDialog', () => {
  it('a picked image shows whole in both previews, offers nothing to frame, and saves as it is', async () => {
    const { wrapper, save, emitted } = await mountDialog()
    await pick(wrapper, new File([new Uint8Array(10)], 'wide.jpg', { type: 'image/jpeg' }))
    for (const which of ['wide', 'phone'] as const) {
      expect(previewSrc(wrapper, which).attributes('src')).toBe('blob:picked')
      expect(wrapper.find(`[data-testid="banner-preview-${which}"] .banner-frame-backdrop`).exists()).toBe(true)
    }
    expect(wrapper.find('[role="group"]').exists(), 'no fit / fill switch').toBe(false)
    expect(wrapper.find('.banner-focal-handle').exists(), 'no focal point').toBe(false)
    expect(wrapper.find('input:not([type="file"])').exists(), 'no description field: the banner has none').toBe(false)
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(
      { kind: 'file', file: expect.any(File), type: 'image/jpeg', poster: null },
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(emitted('close')).toHaveLength(1)
  })

  it('refuses an image above 40 MB and a file it cannot read, before anything is read further', async () => {
    const { wrapper, deriver } = await mountDialog()
    const huge = new File([new Uint8Array(1)], 'huge.jpg', { type: 'image/jpeg' })
    Object.defineProperty(huge, 'size', { value: MAX_BANNER_IMAGE_BYTES + 1 })
    await pick(wrapper, huge)
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.tooLarge', { max: '40 MB' }))
    await pick(wrapper, new File([new Uint8Array(1)], 'notes.pdf', { type: 'application/pdf' }))
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.unreadable'))
    expect(deriver.deriveVideoPoster).not.toHaveBeenCalled()
    expect(button(wrapper, t('memory.banner.save')).attributes('disabled')).toBeDefined()
  })

  it('a video is previewed on its first frame; one whose frame this browser cannot decode is refused (Q5)', async () => {
    const { wrapper, deriver, save } = await mountDialog()
    vi.mocked(deriver.deriveVideoPoster).mockRejectedValueOnce(new Error('no decoder'))
    await pick(wrapper, new File([new Uint8Array(10)], 'clip.mov', { type: 'video/quicktime' }))
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.videoUnreadable'))
    expect(previewSrc(wrapper, 'wide').exists()).toBe(false)

    await pick(wrapper, new File([new Uint8Array(10)], 'clip.mov', { type: 'video/quicktime' }))
    expect(previewSrc(wrapper, 'wide').attributes('src')).toBe('blob:picked')
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toEqual({ kind: 'file', file: expect.any(File), type: 'video/quicktime', poster: new Uint8Array([0xff, 0xd8]) })
  })

  it('re-opens the stored banner in the previews, saves only a new file; Remove removes; a failure is said and the dialog stays', async () => {
    const { wrapper, save, emitted } = await mountDialog({ current: stored, currentSrc: 'blob:current' })
    expect(previewSrc(wrapper, 'wide').attributes('src')).toBe('blob:current')
    expect(button(wrapper, t('memory.banner.save')).attributes('disabled'), 'nothing to save until a new file is picked').toBeDefined()
    save.mockRejectedValueOnce(new BannerStaleError())
    await button(wrapper, t('memory.banner.remove')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toEqual({ kind: 'remove' })
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.stale'))
    expect(emitted('close')).toBeUndefined()

    await button(wrapper, t('memory.banner.remove')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[1][0]).toEqual({ kind: 'remove' })
    expect(emitted('close')).toHaveLength(1)
  })

  it('a picked file is the only source', async () => {
    const { wrapper } = await mountDialog()
    expect(wrapper.text(), 'no gallery source is offered').not.toContain('gallery')
    expect(button(wrapper, t('memory.banner.choose'))).toBeTruthy()
  })

  it('a save in flight shows its progress and Cancel aborts it, saying nothing', async () => {
    let signal!: AbortSignal
    const { wrapper, save, emitted } = await mountDialog({ current: stored, currentSrc: 'blob:current' })
    await pick(wrapper, new File([new Uint8Array(10)], 'wide.jpg', { type: 'image/jpeg' }))
    save.mockImplementationOnce(async (_i, opts) => {
      signal = opts.signal!
      opts.onProgress?.(0.42)
      await new Promise<void>((_r, reject) => opts.signal?.addEventListener('abort', () => reject(new DOMException('x', 'AbortError'))))
    })
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(t('memory.banner.uploading', { percent: 42 }))
    await button(wrapper, t('common.cancel')).trigger('click')
    await flushPromises()
    expect(signal.aborted).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(emitted('close')).toBeUndefined()
  })
})
