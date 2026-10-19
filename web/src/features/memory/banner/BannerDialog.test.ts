// @vitest-environment happy-dom
// uc.memory.banner - the banner dialog: what it takes and refuses before anything is read further, the frame a medium
// starts in (a portrait one fits, and only it may switch), the focal point the previews follow, and the intent it saves -
// awaited, with its progress, its cancel and its failure said in words.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { DOMWrapper, mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
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
/** The dialog is teleported into the body (PrimeVue's portal): it is read there. */
const page = () => new DOMWrapper(document.body)

async function mountDialog(props: Record<string, unknown> = {}) {
  const save = vi.fn<(intent: BannerSaveIntent, opts: BannerSaveOpts) => Promise<void>>(async () => undefined)
  const deriver = { deriveImage: vi.fn(), deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8])) } as unknown as MediaDeriver
  const wrapper = mount(BannerDialog, {
    props: { current: null, currentSrc: null, save, deriver, ...props },
    global: { plugins: [realI18n(), PrimeVue] },
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

/** The picture's natural size, as the browser reports it once it has loaded. */
async function loaded(wrapper: Page, w: number, h: number) {
  const img = wrapper.get('img.banner-dialog-image')
  Object.defineProperty(img.element, 'naturalWidth', { value: w, configurable: true })
  Object.defineProperty(img.element, 'naturalHeight', { value: h, configurable: true })
  await img.trigger('load')
}

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
  it('a picked landscape image fills; a portrait one fits, may switch to fill, and saves the frame chosen', async () => {
    const { wrapper, save, emitted } = await mountDialog()
    await pick(wrapper, new File([new Uint8Array(10)], 'wide.jpg', { type: 'image/jpeg' }))
    await loaded(wrapper, 4000, 2000)
    expect(wrapper.find('[role="group"]').exists(), 'a landscape medium does not switch').toBe(false)
    expect(wrapper.get('[data-testid="banner-preview-wide"] img.banner-frame-media').classes()).not.toContain('is-fit')

    await pick(wrapper, new File([new Uint8Array(10)], 'tall.jpg', { type: 'image/jpeg' }))
    await loaded(wrapper, 1080, 1920)
    expect(wrapper.get('[data-testid="banner-preview-phone"] img.banner-frame-media').classes()).toContain('is-fit')
    await button(wrapper, t('memory.banner.fill')).trigger('click')
    expect(wrapper.find('input:not([type="file"])').exists(), 'no description field: the banner has none').toBe(false)
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'file', type: 'image/jpeg', poster: null, framing: { frame: 'fill', focal: { x: 0.5, y: 0.5 } } }),
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

  it('a video is framed on its first frame; one whose frame this browser cannot decode is refused (Q5)', async () => {
    const { wrapper, deriver, save } = await mountDialog()
    vi.mocked(deriver.deriveVideoPoster).mockRejectedValueOnce(new Error('no decoder'))
    await pick(wrapper, new File([new Uint8Array(10)], 'clip.mov', { type: 'video/quicktime' }))
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.videoUnreadable'))
    expect(wrapper.find('img.banner-dialog-image').exists()).toBe(false)

    await pick(wrapper, new File([new Uint8Array(10)], 'clip.mov', { type: 'video/quicktime' }))
    await loaded(wrapper, 1080, 1920)
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toMatchObject({ kind: 'file', type: 'video/quicktime', poster: new Uint8Array([0xff, 0xd8]), framing: { frame: 'fit' } })
  })

  it('re-opens the stored banner as it was framed; Remove removes; a failure is said and the dialog stays', async () => {
    const { wrapper, save, emitted } = await mountDialog({ current: stored, currentSrc: 'blob:current' })
    expect(wrapper.get('img.banner-dialog-image').attributes('src')).toBe('blob:current')
    save.mockRejectedValueOnce(new BannerStaleError())
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toEqual({ kind: 'keep', framing: { frame: 'fill', focal: { x: 0.3, y: 0.4 } } })
    expect(wrapper.get('[role="alert"]').text()).toBe(t('memory.banner.stale'))
    expect(emitted('close')).toBeUndefined()

    await button(wrapper, t('memory.banner.remove')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[1][0]).toEqual({ kind: 'remove' })
    expect(emitted('close')).toHaveLength(1)
  })

  it('a picked file is the only source; the arrow keys move the point', async () => {
    const { wrapper, save } = await mountDialog()
    expect(wrapper.text(), 'no gallery source is offered').not.toContain('gallery')
    await pick(wrapper, new File([new Uint8Array(10)], 'wide.jpg', { type: 'image/jpeg' }))
    await loaded(wrapper, 3000, 2000)
    await wrapper.get('.banner-focal-handle').trigger('keydown', { key: 'ArrowRight' })
    await wrapper.get('.banner-focal-handle').trigger('keydown', { key: 'ArrowDown', shiftKey: true })
    await button(wrapper, t('memory.banner.save')).trigger('click')
    await flushPromises()
    expect(save.mock.calls[0][0]).toMatchObject({ kind: 'file', framing: { frame: 'fill', focal: { x: 0.52, y: 0.6 } } })
  })

  it('a save in flight shows its progress and Cancel aborts it, saying nothing', async () => {
    let signal!: AbortSignal
    const { wrapper, save, emitted } = await mountDialog({ current: stored, currentSrc: 'blob:current' })
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
