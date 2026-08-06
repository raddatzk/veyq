// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeAll } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import MemoryCoverDialog from './MemoryCoverDialog.vue'
import type { CoverSaveIntent, MemoryCover } from '../memoryCover'

// happy-dom has no real object-URL registry; the dialog only needs a stable string + a no-op revoke.
beforeAll(() => {
  vi.stubGlobal('URL', { ...URL, createObjectURL: () => 'blob:upload', revokeObjectURL: () => {} })
})

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { cancel: 'Cancel' },
      memory: {
        zoom: 'Zoom',
        cover: { choose: 'Choose a cover', copy: 'copy', copyNoMedia: 'copy-no-media', upload: 'Upload a photo', remove: 'Remove cover', save: 'Save cover' },
      },
    },
  },
})

const UPLOAD_REF = { blobId: 'b-1', chunkCount: 1, byteSize: 4096, wrappedBlobKey: 'd3JhcA==' }

// Stub the PrimeVue Dialog/Button shells (happy-dom can't drive the overlay) — the crop math + emits are under test.
const stubs = {
  Dialog: { template: '<div class="dlg"><slot /></div>' },
  Button: {
    props: ['type', 'label', 'disabled'],
    template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>`,
  },
}

type Props = { cover?: MemoryCover | null; existingSrc?: string | null; hasMedia?: boolean; mediaSource?: { mediaId: string; src: string } | null }
function mountDialog(p: Props = {}) {
  return mount(MemoryCoverDialog, {
    props: { cover: p.cover ?? null, existingSrc: p.existingSrc ?? null, hasMedia: p.hasMedia ?? false, mediaSource: p.mediaSource ?? null },
    global: { plugins: [i18n], stubs },
  })
}

/** The dialog's action buttons keyed by their label (the stub renders the label as text). */
const btn = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').filter((b) => b.text() === label)[0]

/** Stub the cropper image's decoded natural size and fire its @load so the crop math runs (happy-dom decodes nothing). */
async function loadCropper(w: ReturnType<typeof mount>, natW: number, natH: number) {
  const img = w.find('.memory-cover-cropper img')
  Object.defineProperty(img.element, 'naturalWidth', { configurable: true, value: natW })
  Object.defineProperty(img.element, 'naturalHeight', { configurable: true, value: natH })
  await img.trigger('load')
  await w.vm.$nextTick()
}

const near = (a: number, b: number) => Math.abs(a - b) < 1e-9

describe('MemoryCoverDialog', () => {
  it('has no in-dialog media picker (browsing existing media is the gallery’s job, cr-0073 §gallery)', () => {
    const w = mountDialog({ hasMedia: true })
    expect(w.findAll('.memory-cover-option')).toHaveLength(0)
    expect(w.find('.memory-cover-picker').exists()).toBe(false)
  })

  it('picking a new image file + saving emits an upload intent carrying the file', async () => {
    const w = mountDialog({ hasMedia: false })
    expect(w.find('.memory-cover-cropper').exists()).toBe(false) // no source yet → upload only
    const file = new File([new Uint8Array([1, 2, 3])], 'cover.png', { type: 'image/png' })
    const input = w.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await loadCropper(w, 800, 450) // a 16:9 upload

    await btn(w, 'Save cover').trigger('click')
    const intent = w.emitted('save')?.[0]?.[0] as CoverSaveIntent
    expect(intent.kind).toBe('upload')
    if (intent.kind !== 'upload') throw new Error('expected upload')
    expect(intent.file.name).toBe('cover.png')
    expect(intent.file.type).toBe('image/png')
    expect(near(intent.crop.width, 1)).toBe(true)
    expect(near(intent.crop.height, 1)).toBe(true)
  })

  it('remove emits a remove intent', async () => {
    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'm1' }, crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.5 } }
    const w = mountDialog({ cover })
    await btn(w, 'Remove cover').trigger('click')
    expect((w.emitted('save')?.[0]?.[0] as CoverSaveIntent).kind).toBe('remove')
    expect(w.emitted('close')).toBeTruthy()
  })

  it('re-cropping the current cover from the header restores its crop and emits keep (media OR upload source)', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 } // achievable 16:9 region of a 3200×1800 source
    // a media cover, re-opened from the header: its decrypted thumbnail is the crop source, kept (only the crop moves)
    const w = mountDialog({ cover: { source: { kind: 'media', mediaId: 'm1' }, crop }, existingSrc: 'blob:cover-thumb' })
    expect(w.find('.memory-cover-cropper').exists()).toBe(true)
    expect(w.find('.memory-cover-cropper img').attributes('src')).toBe('blob:cover-thumb')
    await loadCropper(w, 3200, 1800)

    await btn(w, 'Save cover').trigger('click')
    const intent = w.emitted('save')?.[0]?.[0] as CoverSaveIntent
    expect(intent.kind).toBe('keep')
    if (intent.kind !== 'keep') throw new Error('expected keep')
    expect(near(intent.crop.x, crop.x)).toBe(true)
    expect(near(intent.crop.width, crop.width)).toBe(true)
  })

  it('seeded with a Memory photo (lightbox “Set as cover”) crops that photo and emits a media intent', async () => {
    // the lightbox hands in the picked photo's decrypted thumbnail — the cropper is up immediately, no upload step
    const w = mountDialog({ mediaSource: { mediaId: 'm7', src: 'blob:photo-thumb' }, hasMedia: true })
    expect(w.find('.memory-cover-cropper').exists()).toBe(true)
    expect(w.find('.memory-cover-cropper img').attributes('src')).toBe('blob:photo-thumb')
    await loadCropper(w, 3200, 1800) // a 16:9 source → default centre crop == the full frame

    await btn(w, 'Save cover').trigger('click')
    const intent = w.emitted('save')?.[0]?.[0] as CoverSaveIntent
    expect(intent.kind).toBe('media')
    if (intent.kind !== 'media') throw new Error('expected media')
    expect(intent.mediaId).toBe('m7')
    expect(near(intent.crop.width, 1)).toBe(true)
    expect(near(intent.crop.height, 1)).toBe(true)
  })

  it('seeded with the photo that is already the cover restores its stored crop (media intent)', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 }
    const w = mountDialog({
      cover: { source: { kind: 'media', mediaId: 'm7' }, crop },
      mediaSource: { mediaId: 'm7', src: 'blob:photo-thumb' },
      hasMedia: true,
    })
    await loadCropper(w, 3200, 1800)

    await btn(w, 'Save cover').trigger('click')
    const intent = w.emitted('save')?.[0]?.[0] as CoverSaveIntent
    expect(intent.kind).toBe('media')
    if (intent.kind !== 'media') throw new Error('expected media')
    expect(intent.mediaId).toBe('m7')
    expect(near(intent.crop.x, crop.x)).toBe(true)
    expect(near(intent.crop.width, crop.width)).toBe(true)
  })

  it('reopening an upload cover from the header re-crops the decrypted image and emits a keep intent (no re-upload)', async () => {
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.5 }
    const w = mountDialog({ cover: { source: { kind: 'upload', ref: UPLOAD_REF }, crop }, existingSrc: 'blob:existing-cover' })
    // the current uploaded cover is shown from `existingSrc` → the cropper is already up
    expect(w.find('.memory-cover-cropper').exists()).toBe(true)
    expect(w.find('.memory-cover-cropper img').attributes('src')).toBe('blob:existing-cover')
    await loadCropper(w, 3200, 1800)

    await btn(w, 'Save cover').trigger('click')
    const intent = w.emitted('save')?.[0]?.[0] as CoverSaveIntent
    expect(intent.kind).toBe('keep')
    if (intent.kind !== 'keep') throw new Error('expected keep')
    expect(near(intent.crop.x, crop.x)).toBe(true)
    expect(near(intent.crop.width, crop.width)).toBe(true)
  })
})
