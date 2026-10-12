// @vitest-environment happy-dom
// Core's cover dialog (inbox #411): the ONE dialog every node that takes a cover opens. It crops a source with the shared 16:9
// cropper and emits a `CoverSaveIntent`; this pins every source and intent (upload, keep, the application's own `media`, remove),
// the crop it restores from a stored cover, what it refuses (a non-image, an oversize file) and its a11y (a labelled modal dialog,
// focus inside it, Escape closes). The real PrimeVue Dialog is mounted in the body: the semantics are the library's, the wiring ours.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Dialog from 'primevue/dialog'
import CoverDialog from './CoverDialog.vue'
import type { CoverCrop, CoverSaveIntent } from '../coverModel'
import { realI18n, t } from '../../../../test/i18n'

type Props = InstanceType<typeof CoverDialog>['$props']

let wrappers: VueWrapper[] = []
async function mountDialog(p: Partial<Props> = {}) {
  const w = mount(CoverDialog, {
    props: { cover: null, existingSrc: null, source: null, hint: null, ...p },
    attachTo: document.body,
    global: { plugins: [realI18n(), PrimeVue] },
  })
  wrappers.push(w)
  await vi.waitFor(() => expect(dialog(), 'the dialog renders').not.toBeNull())
  return w
}
const body = () => document.body
const dialog = () => body().querySelector<HTMLElement>('[role="dialog"]')
const button = (label: string) => [...body().querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim() === label)
const cropperImg = () => body().querySelector<HTMLImageElement>('.memory-cover-cropper img')

/** Pick a file through the dialog's hidden input, as the browser's file dialog would hand it over. */
async function pickFile(file: File) {
  const input = body().querySelector<HTMLInputElement>('input[type="file"]')!
  Object.defineProperty(input, 'files', { configurable: true, value: [file] })
  input.dispatchEvent(new Event('change'))
  await new Promise((r) => setTimeout(r))
}
/** Stub the cropper image's decoded natural size and fire its load so the crop math runs (happy-dom decodes nothing). */
async function loadCropper(natW: number, natH: number) {
  const img = cropperImg()!
  Object.defineProperty(img, 'naturalWidth', { configurable: true, value: natW })
  Object.defineProperty(img, 'naturalHeight', { configurable: true, value: natH })
  img.dispatchEvent(new Event('load'))
  await new Promise((r) => setTimeout(r))
}
const png = () => new File([new Uint8Array([1, 2, 3])], 'cover.png', { type: 'image/png' })
const near = (a: number, b: number) => Math.abs(a - b) < 1e-9
const saved = (w: VueWrapper) => w.emitted('save')?.[0]?.[0] as CoverSaveIntent | undefined

beforeEach(() => {
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:picked'), revokeObjectURL: vi.fn() })
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  wrappers.forEach((w) => w.unmount())
  wrappers = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('a node with no cover yet', () => {
  it('offers choose + cancel + save (disabled), no cropper and no Remove', async () => {
    await mountDialog()
    await new Promise((r) => setTimeout(r))
    expect(button(t('node.cover.choose'))).toBeTruthy()
    expect(button(t('common.cancel'))).toBeTruthy()
    expect(button(t('node.cover.save'))!.disabled, 'nothing to save yet').toBe(true)
    expect(button(t('node.cover.remove')), 'nothing to remove').toBeUndefined()
    expect(cropperImg()).toBeNull()
  })

  it('a picked image is cropped; Save emits an upload intent with the file and the centred 16:9 crop', async () => {
    const w = await mountDialog()
    await pickFile(png())
    expect(cropperImg()!.getAttribute('src')).toBe('blob:picked')
    await loadCropper(800, 450) // a 16:9 image: the centred crop is the full frame
    button(t('node.cover.save'))!.click()
    const intent = saved(w)!
    expect(intent.kind).toBe('upload')
    if (intent.kind !== 'upload') throw new Error('expected upload')
    expect(intent.file.name).toBe('cover.png')
    expect(near(intent.crop.width, 1) && near(intent.crop.height, 1)).toBe(true)
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a non-16:9 image is framed by the crop - a centred 16:9 window of it, not the whole frame', async () => {
    const w = await mountDialog()
    await pickFile(png())
    await loadCropper(1000, 1000) // square: a 16:9 window is the full width, 9/16 of the height
    button(t('node.cover.save'))!.click()
    const intent = saved(w)
    if (intent?.kind !== 'upload') throw new Error('expected upload')
    expect(near(intent.crop.width, 1)).toBe(true)
    expect(near(intent.crop.height, 9 / 16)).toBe(true)
    expect(near(intent.crop.y, (1 - 9 / 16) / 2)).toBe(true)
  })

  it.each([
    ['a PDF', new File([new Uint8Array([1])], 'plan.pdf', { type: 'application/pdf' }), 'node.cover.notImage'],
    ['an empty image', new File([], 'empty.png', { type: 'image/png' }), 'node.cover.notImage'],
  ])('%s is rejected with a message in an alert; no cropper, nothing to save', async (_what, file, key) => {
    const w = await mountDialog()
    await pickFile(file)
    expect(body().querySelector('[role="alert"]')?.textContent).toBe(t(key))
    expect(cropperImg()).toBeNull()
    expect(button(t('node.cover.save'))!.disabled).toBe(true)
    expect(w.emitted('save')).toBeUndefined()
  })

  it('a file over the limit is rejected as too large', async () => {
    await mountDialog()
    const huge = png()
    Object.defineProperty(huge, 'size', { value: 41 * 1024 * 1024 })
    await pickFile(huge)
    expect(body().querySelector('[role="alert"]')?.textContent).toBe(t('node.cover.tooLarge', { max: '40 MB' }))
  })

  it('a good file after a refused one clears the message', async () => {
    await mountDialog()
    await pickFile(new File([new Uint8Array([1])], 'a.pdf', { type: 'application/pdf' }))
    expect(body().querySelector('[role="alert"]')).not.toBeNull()
    await pickFile(png())
    expect(body().querySelector('[role="alert"]')).toBeNull()
    expect(cropperImg()).not.toBeNull()
  })
})

describe('a node with a cover', () => {
  const crop: CoverCrop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 } // an achievable 16:9 window of a 3200x1800 image

  it('re-crops the current cover: its crop is restored and Save emits keep with it (no new image)', async () => {
    const w = await mountDialog({ cover: { crop }, existingSrc: 'blob:existing' })
    await new Promise((r) => setTimeout(r))
    expect(cropperImg()!.getAttribute('src')).toBe('blob:existing')
    await loadCropper(3200, 1800)
    button(t('node.cover.save'))!.click()
    const intent = saved(w)
    if (intent?.kind !== 'keep') throw new Error('expected keep')
    expect(near(intent.crop.x, crop.x) && near(intent.crop.y, crop.y) && near(intent.crop.width, crop.width)).toBe(true)
  })

  it('Remove emits the remove intent and closes', async () => {
    const w = await mountDialog({ cover: { crop }, existingSrc: 'blob:existing' })
    await new Promise((r) => setTimeout(r))
    button(t('node.cover.remove'))!.click()
    expect(saved(w)).toEqual({ kind: 'remove' })
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a cover whose image has not loaded can still be removed, not re-cropped', async () => {
    const w = await mountDialog({ cover: { crop }, existingSrc: null })
    await new Promise((r) => setTimeout(r))
    expect(cropperImg()).toBeNull()
    expect(button(t('node.cover.save'))!.disabled).toBe(true)
    button(t('node.cover.remove'))!.click()
    expect(saved(w)).toEqual({ kind: 'remove' })
  })

  it('choosing a new image replaces the re-crop: Save emits upload, with a fresh centred crop', async () => {
    const w = await mountDialog({ cover: { crop }, existingSrc: 'blob:existing' })
    await pickFile(png())
    expect(cropperImg()!.getAttribute('src')).toBe('blob:picked')
    await loadCropper(800, 450)
    button(t('node.cover.save'))!.click()
    const intent = saved(w)
    if (intent?.kind !== 'upload') throw new Error('expected upload')
    expect(near(intent.crop.x, 0) && near(intent.crop.width, 1)).toBe(true) // not the old crop
  })
})

describe('the application\'s own source (a photo chosen in the gallery)', () => {
  it('is cropped at once and saved as a media intent carrying the id it was given', async () => {
    const w = await mountDialog({ source: { id: 'm7', src: 'blob:photo', crop: null }, hint: 'Or use a gallery photo.' })
    await new Promise((r) => setTimeout(r))
    expect(cropperImg()!.getAttribute('src')).toBe('blob:photo')
    expect(body().textContent).toContain('Or use a gallery photo.')
    await loadCropper(3200, 1800)
    button(t('node.cover.save'))!.click()
    const intent = saved(w)
    if (intent?.kind !== 'media') throw new Error('expected media')
    expect(intent.mediaId).toBe('m7')
    expect(near(intent.crop.width, 1)).toBe(true)
  })

  it('restores the stored crop when that photo already is the cover', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 }
    const w = await mountDialog({ cover: { crop }, existingSrc: 'blob:existing', source: { id: 'm7', src: 'blob:photo', crop } })
    await loadCropper(3200, 1800)
    button(t('node.cover.save'))!.click()
    const intent = saved(w)
    if (intent?.kind !== 'media') throw new Error('expected media')
    expect(near(intent.crop.x, crop.x) && near(intent.crop.width, crop.width)).toBe(true)
  })

  it('takes precedence over the current cover: the cropper shows the photo, not the old image', async () => {
    await mountDialog({ cover: { crop: { x: 0, y: 0, width: 1, height: 1 } }, existingSrc: 'blob:existing', source: { id: 'm7', src: 'blob:photo', crop: null } })
    await new Promise((r) => setTimeout(r))
    expect(cropperImg()!.getAttribute('src')).toBe('blob:photo')
  })

  it('without a source there is no hint line of the application\'s', async () => {
    await mountDialog()
    await new Promise((r) => setTimeout(r))
    expect(body().textContent).toContain(t('node.cover.copy'))
    expect(body().textContent).not.toContain('gallery')
  })
})

describe('cancel and a11y', () => {
  it('Cancel closes and saves nothing', async () => {
    const w = await mountDialog({ cover: { crop: { x: 0, y: 0, width: 1, height: 1 } }, existingSrc: 'blob:existing' })
    await loadCropper(800, 450)
    button(t('common.cancel'))!.click()
    expect(w.emitted('close')).toBeTruthy()
    expect(w.emitted('save')).toBeUndefined()
  })

  it('is a modal dialog labelled by its title', async () => {
    await mountDialog()
    await new Promise((r) => setTimeout(r))
    const d = dialog()!
    expect(d.getAttribute('aria-modal')).toBe('true')
    const label = d.getAttribute('aria-labelledby')
    expect(label, 'labelled').toBeTruthy()
    expect(document.getElementById(label!)?.textContent).toBe(t('node.cover.dialogTitle'))
  })

  it('puts focus inside the dialog on open', async () => {
    await mountDialog()
    await vi.waitFor(() => expect(dialog()!.contains(document.activeElement) && document.activeElement !== document.body).toBe(true))
  })

  it('closes without saving on what the dialog reports as its close (Escape, the X, the mask)', async () => {
    const w = await mountDialog({ cover: { crop: { x: 0, y: 0, width: 1, height: 1 } }, existingSrc: 'blob:existing' })
    const library = w.findComponent(Dialog)
    // Escape is PrimeVue's own document listener (bound when its enter transition runs, which happy-dom does not drive); what
    // is ours is that the dialog leaves it ON and that its close event is the dialog's close.
    expect(library.props('closeOnEscape'), 'Escape closes').not.toBe(false)
    expect(library.props('closable'), 'the X closes').not.toBe(false)
    expect(library.props('modal')).toBe(true)
    library.vm.$emit('update:visible', false)
    expect(w.emitted('close')).toBeTruthy()
    expect(w.emitted('save')).toBeUndefined()
  })

  it('the file input is not a tab stop or announced (the visible button opens it)', async () => {
    await mountDialog()
    const input = body().querySelector<HTMLInputElement>('input[type="file"]')!
    expect(input.tabIndex).toBe(-1)
    expect(input.getAttribute('aria-hidden')).toBe('true')
    expect(input.accept).toBe('image/*')
  })
})
