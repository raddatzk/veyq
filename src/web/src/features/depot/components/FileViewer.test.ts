// @vitest-environment happy-dom
// The viewer of an opened file (inbox #412), mounted as the file page does (`FileViewer`, real DOM, real catalog, real
// UI layer): what each kind of file shows, what it never shows (markup as markup, an image the bytes do not prove), what
// is undone when the page goes (object URL, playback handle, the read in flight), and the full-screen toggle. The decrypt
// is `transport.ts`'s; the page's reader is a fake over plain bytes (`test/fileChunks.ts`), and the service worker is the
// same stub Memories' lightbox tests use (there is no worker in the test environment).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
}))

// PDFs are `FileViewer.pdf.test.ts`'s; here the engine is a stub so no test loads pdf.js by accident.
vi.mock('../pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../pdf/pdfEngine')>()), openPdf: vi.fn() }))

import FileViewer from './FileViewer.vue'
import { registerPlayback, revokePlayback } from '../../../sw/register'
import { PLAINTEXT_CHUNK_SIZE } from '../../../sw/range'
import { IMAGE_CAP, TEXT_CAP } from '../view'
import type { ViewerTarget } from '../useFileViewer'
import { MP3_BYTES, MP4_BYTES, PNG_BYTES, providerOf, utf8, type FakeProvider } from '../../../test/fileChunks'
import { realI18n, t } from '../../../test/i18n'
import { formatBytes } from '../../../shared/formatBytes'

let minted: Blob[]
let revoked: string[]
beforeEach(() => {
  minted = []
  revoked = []
  vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => {
    minted.push(b as Blob)
    return `blob:viewer-${minted.length}`
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((u) => void revoked.push(u))
  vi.mocked(registerPlayback).mockReset().mockResolvedValue(null)
  vi.mocked(revokePlayback).mockClear()
})
afterEach(() => vi.restoreAllMocks())

const UNKNOWN_BYTES = Uint8Array.from({ length: 300 }, (_, i) => (i * 7) % 250)
const target = (source: FakeProvider, hints: ViewerTarget['hints'] = {}, id = 'blob-1'): ViewerTarget => ({ id, source, hints })
function mountViewer(tg: ViewerTarget | null, o: { canDownload?: boolean; name?: string } = {}) {
  return mount(FileViewer, {
    props: { target: tg, name: o.name ?? 'Datei.bin', canDownload: o.canDownload ?? true },
    attachTo: document.body,
    global: { plugins: [realI18n()] },
  })
}
type W = ReturnType<typeof mountViewer>
const downloadButton = (w: W) => w.find('button.file-viewer-download')
const fullscreenButton = (w: W) => w.find('button.file-viewer-fullscreen')

describe('nothing to show yet', () => {
  it('renders no viewer while the page has not read the blob', () => {
    const w = mountViewer(null)
    expect(w.find('.file-viewer').exists()).toBe(false)
  })
})

describe('an image', () => {
  it('shows an <img> on an object URL typed with the SNIFFED type, alt text from the file name, and revokes it on unmount', async () => {
    const w = mountViewer(target(providerOf(PNG_BYTES), { type: 'text/plain', name: 'notes.txt' }), { name: 'Urlaub.png' })
    await flushPromises()
    const img = w.find('img.file-viewer-image')
    expect(img.attributes('src')).toBe('blob:viewer-1')
    expect(img.attributes('alt')).toBe('Urlaub.png')
    expect(minted).toHaveLength(1)
    expect(minted[0].type).toBe('image/png') // not the claimed text/plain
    expect(revoked).toEqual([])
    w.unmount()
    expect(revoked).toEqual(['blob:viewer-1'])
  })

  it('revokes the old object URL when the page\'s file is replaced, and shows the new one', async () => {
    const w = mountViewer(target(providerOf(PNG_BYTES), {}, 'blob-1'))
    await flushPromises()
    await w.setProps({ target: target(providerOf(PNG_BYTES), {}, 'blob-2') })
    await flushPromises()
    expect(revoked).toEqual(['blob:viewer-1'])
    expect(w.find('img').attributes('src')).toBe('blob:viewer-2')
  })

  it('revokes the image\'s object URL when the page\'s file is replaced by one that is no image', async () => {
    const w = mountViewer(target(providerOf(PNG_BYTES), {}, 'blob-1'))
    await flushPromises()
    await w.setProps({ target: target(providerOf(UNKNOWN_BYTES), {}, 'blob-2') })
    await flushPromises()
    expect(revoked).toEqual(['blob:viewer-1'])
    expect(w.find('img').exists()).toBe(false)
  })

  it('shows an SVG only as an <img> from a blob URL typed image/svg+xml - never inline', async () => {
    const svg = utf8('<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
    const w = mountViewer(target(providerOf(svg), { type: 'image/svg+xml', name: 'logo.svg' }))
    await flushPromises()
    expect(w.find('img').attributes('src')).toBe('blob:viewer-1')
    expect(minted[0].type).toBe('image/svg+xml')
    // The layer's own icons are inline SVGs (`VeyqIcon`: `<span class="veyq-icon"><svg class="veyq-icon__svg">`, ADR
    // 0107); every other <svg> in the viewer would be the FILE's markup parsed into the page.
    const inline = w.findAll('svg').filter((s) => !(s.classes('veyq-icon__svg') && s.element.parentElement?.classList.contains('veyq-icon')))
    expect(inline, 'the file\'s SVG is never inline markup').toHaveLength(0)
    expect(w.find('script').exists()).toBe(false)
    expect(w.find('object, iframe, embed').exists()).toBe(false)
  })

  it('a decode failure (HEIC off Safari, a damaged file) is a clear message and the download, with the URL revoked', async () => {
    const w = mountViewer(target(providerOf(PNG_BYTES)))
    await flushPromises()
    await w.find('img').trigger('error')
    expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toContain(t('depot.viewer.unsupported.format'))
    expect(revoked).toEqual(['blob:viewer-1'])
    await downloadButton(w).trigger('click')
    expect(w.emitted('download')).toHaveLength(1)
  })
})

describe('a text', () => {
  it('shows an HTML payload as characters: the markup is never parsed', async () => {
    const payload = '<img src=x onerror="document.title=\'pwned\'"><script>document.title="pwned"</script> <b>bold</b>'
    const w = mountViewer(target(providerOf(utf8(payload)), { type: 'text/html', name: 'page.html' }))
    await flushPromises()
    const pre = w.find('pre.file-viewer-text')
    expect(pre.element.textContent).toBe(payload)
    expect(pre.element.children).toHaveLength(0)
    expect(w.find('img, script, b').exists()).toBe(false)
    expect(document.title).not.toBe('pwned')
  })

  it('drops a UTF-8 byte-order mark', async () => {
    const w = mountViewer(target(providerOf(Uint8Array.from([0xef, 0xbb, 0xbf, ...utf8('Überschrift')])), { type: 'text/markdown' }))
    await flushPromises()
    expect(w.find('pre').element.textContent).toBe('Überschrift')
  })

  it('a text over the cap shows its first part and a note that says how much', async () => {
    const w = mountViewer(target(providerOf(utf8('zeile\n'.repeat(Math.ceil((TEXT_CAP * 2) / 6)))), { type: 'text/plain' }))
    await flushPromises()
    expect(w.find('pre').element.textContent!.length).toBe(TEXT_CAP)
    expect(w.find('.file-viewer-hint').text()).toBe(t('depot.viewer.textTruncated', { shown: formatBytes(TEXT_CAP, 'en') }))
  })

  it('a binary that claims to be text is not shown as text', async () => {
    const w = mountViewer(target(providerOf(Uint8Array.from([0x61, 0, 0x62, 0, 1, 2, 3])), { type: 'text/plain', name: 'a.txt' }))
    await flushPromises()
    expect(w.find('pre').exists()).toBe(false)
    expect(w.text()).toContain(t('depot.viewer.unsupported.format'))
  })
})

describe('what is not shown: the download is offered instead', () => {
  it('an image over the cap: not decrypted into memory, the download offered with the limit named', async () => {
    const p = providerOf(PNG_BYTES, { byteSize: IMAGE_CAP + 10 * PLAINTEXT_CHUNK_SIZE, chunkCount: Math.ceil(IMAGE_CAP / PLAINTEXT_CHUNK_SIZE) + 10 })
    const w = mountViewer(target(p, { type: 'image/png' }))
    await flushPromises()
    expect(w.text()).toContain(t('depot.viewer.tooLarge', { limit: formatBytes(IMAGE_CAP, 'en') }))
    expect(p.read).toEqual([0])
    expect(minted).toHaveLength(0)
    expect(downloadButton(w).exists()).toBe(true)
  })

  it('offers no download button where the session has none (the message still stands)', async () => {
    const w = mountViewer(target(providerOf(UNKNOWN_BYTES), {}), { canDownload: false })
    await flushPromises()
    expect(w.text()).toContain(t('depot.viewer.unsupported.format'))
    expect(downloadButton(w).exists()).toBe(false)
  })

  it('a file of no known format', async () => {
    const w = mountViewer(target(providerOf(Uint8Array.from({ length: 300 }, (_, i) => (i * 7) % 250)), { type: 'application/zip', name: 'a.zip' }))
    await flushPromises()
    expect(w.text()).toContain(t('depot.viewer.unsupported.format'))
  })
})

describe('video and audio: through the service worker\'s media route', () => {
  it('a video plays from the SW handle: <video controls playsinline>, the sniffed container, no read past chunk 0', async () => {
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/handle-7')
    const p = providerOf(MP4_BYTES)
    const w = mountViewer(target(p, { type: 'application/octet-stream' }), { name: 'Clip.mp4' })
    await flushPromises()
    const video = w.find('video')
    expect(video.attributes('src')).toBe('/__media/handle-7')
    expect(video.attributes('controls')).toBeDefined()
    expect(video.attributes('playsinline')).toBeDefined()
    expect(registerPlayback).toHaveBeenCalledWith({ chunkCount: p.chunkCount, byteSize: p.byteSize, mediaType: 'video/mp4' }, p)
    expect(p.read).toEqual([0])
    expect(minted).toHaveLength(0) // no whole-file blob
    w.unmount()
    expect(revokePlayback).toHaveBeenCalledWith('handle-7')
  })

  it('an audio file gets an <audio controls>', async () => {
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/handle-8')
    const w = mountViewer(target(providerOf(MP3_BYTES), { name: 'Lied.mp3' }), { name: 'Lied.mp3' })
    await flushPromises()
    expect(w.find('audio').attributes('src')).toBe('/__media/handle-8')
    expect(w.find('audio').attributes('controls')).toBeDefined()
    expect(vi.mocked(registerPlayback).mock.calls[0][0].mediaType).toBe('audio/mpeg')
    expect(w.find('video').exists()).toBe(false)
  })

  it('no usable service worker: a message and the download, never a whole-file decrypt', async () => {
    const p = providerOf(MP4_BYTES)
    const w = mountViewer(target(p, {}))
    await flushPromises()
    expect(w.text()).toContain(t('depot.viewer.unsupported.no-sw'))
    expect(w.find('video').exists()).toBe(false)
    expect(p.read).toEqual([0])
    expect(downloadButton(w).exists()).toBe(true)
  })

  it('a codec the browser cannot play: the element\'s error becomes the message, and the playback is revoked', async () => {
    vi.mocked(registerPlayback).mockResolvedValueOnce('/__media/handle-9')
    const w = mountViewer(target(providerOf(MP4_BYTES), {}))
    await flushPromises()
    await w.find('video').trigger('error')
    expect(w.find('video').exists()).toBe(false)
    expect(w.text()).toContain(t('depot.viewer.unsupported.codec'))
    expect(revokePlayback).toHaveBeenCalledWith('handle-9')
  })

  it('a playback registered after the page moved on is revoked at once', async () => {
    let resolveRegister: (url: string | null) => void = () => {}
    vi.mocked(registerPlayback).mockReturnValueOnce(new Promise((r) => (resolveRegister = r)))
    const w = mountViewer(target(providerOf(MP4_BYTES), {}))
    await flushPromises()
    w.unmount()
    resolveRegister('/__media/late')
    await flushPromises()
    expect(revokePlayback).toHaveBeenCalledWith('late')
  })
})

describe('loading, stopping, failing', () => {
  /** A provider whose chunks after 0 wait on `gate`, so a read stays in flight. */
  function slowImage(chunks: number) {
    let open: () => void = () => {}
    const gate = new Promise<void>((r) => (open = r))
    const bytes = new Uint8Array(chunks * PLAINTEXT_CHUNK_SIZE)
    bytes.set(PNG_BYTES)
    const p = providerOf(bytes)
    const inner = p.getPlaintextChunk
    p.getPlaintextChunk = async (i) => {
      if (i > 0) await gate
      return inner(i)
    }
    return { p, open }
  }

  it('shows a busy state while it reads, then the image', async () => {
    const { p, open } = slowImage(3)
    const w = mountViewer(target(p, { type: 'image/png' }))
    await flushPromises()
    expect(w.find('[role="status"]').text()).toContain(t('depot.viewer.loading'))
    expect(w.find('.file-viewer-stage').attributes('aria-busy')).toBe('true')
    open()
    await flushPromises()
    expect(w.find('img').exists()).toBe(true)
    expect(w.find('[role="status"]').exists()).toBe(false)
  })

  it('unmounting aborts the read: no further chunk is asked for and no object URL is minted', async () => {
    const { p, open } = slowImage(40)
    const w = mountViewer(target(p, { type: 'image/png' }))
    await flushPromises()
    const askedWhileOpen = p.read.length
    expect(askedWhileOpen).toBeLessThanOrEqual(5) // chunk 0 and the pump's window, not the 40 chunks
    w.unmount()
    open()
    await flushPromises()
    expect(p.read.length).toBeLessThanOrEqual(askedWhileOpen + 4) // what was already in flight, nothing newly started
    expect(Math.max(...p.read)).toBeLessThan(10)
    expect(minted).toHaveLength(0)
  })

  it('Stop cancels the read and says so; Try again reads it again', async () => {
    const { p, open } = slowImage(3)
    const w = mountViewer(target(p, { type: 'image/png' }))
    await flushPromises()
    await w.find('[role="status"] button').trigger('click')
    open()
    await flushPromises()
    expect(w.text()).toContain(t('depot.viewer.cancelled'))
    expect(minted).toHaveLength(0)
    const retry = w.findAll('button').find((b) => b.text() === t('depot.viewer.retry'))!
    await retry.trigger('click')
    await flushPromises()
    expect(w.find('img').exists()).toBe(true)
  })

  it('a chunk that does not open is an error in the page\'s own style, with Try again - never an unhandled rejection', async () => {
    let fail = true
    const p = providerOf(PNG_BYTES)
    const inner = p.getPlaintextChunk
    p.getPlaintextChunk = async (i) => {
      if (fail) throw new Error('open_refused')
      return inner(i)
    }
    const w = mountViewer(target(p, {}))
    await flushPromises()
    expect(w.find('.veyq-message--error').text()).toContain(t('depot.viewer.error'))
    fail = false
    await w.findAll('button').find((b) => b.text() === t('depot.viewer.retry'))!.trigger('click')
    await flushPromises()
    expect(w.find('img').exists()).toBe(true)
  })
})

describe('full screen', () => {
  const setFullscreenElement = (el: Element | null) => Object.defineProperty(document, 'fullscreenElement', { value: el, configurable: true })
  afterEach(() => {
    delete (document as unknown as Record<string, unknown>).fullscreenElement
    delete (HTMLElement.prototype as unknown as Record<string, unknown>).requestFullscreen
    delete (document as unknown as Record<string, unknown>).exitFullscreen
  })

  it('is a real, focusable button offered once something is shown, and only then', async () => {
    const w = mountViewer(target(providerOf(UNKNOWN_BYTES), {}))
    await flushPromises()
    expect(fullscreenButton(w).exists()).toBe(false) // nothing to enlarge for a fallback message
    await w.setProps({ target: target(providerOf(PNG_BYTES), {}, 'blob-2') })
    await flushPromises()
    const b = fullscreenButton(w)
    expect(b.element.tagName).toBe('BUTTON')
    expect(b.attributes('aria-label')).toBe(t('depot.viewer.fullscreen'))
    expect(b.attributes('aria-pressed')).toBe('false')
  })

  it('uses the Fullscreen API on the viewer container and follows the browser leaving it (Escape)', async () => {
    const request = vi.fn(async function (this: HTMLElement) { setFullscreenElement(this) })
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { value: request, configurable: true })
    const w = mountViewer(target(providerOf(PNG_BYTES), {}))
    await flushPromises()
    await fullscreenButton(w).trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.contexts[0]).toBe(w.find('.file-viewer').element)
    expect(fullscreenButton(w).attributes('aria-pressed')).toBe('true')
    expect(fullscreenButton(w).attributes('aria-label')).toBe(t('depot.viewer.exitFullscreen'))
    // the browser's own Escape: the element is no longer the fullscreen one
    setFullscreenElement(null)
    document.dispatchEvent(new Event('fullscreenchange'))
    await flushPromises()
    expect(fullscreenButton(w).attributes('aria-pressed')).toBe('false')
  })

  it('exits through the API on the second click', async () => {
    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { value: vi.fn(async function (this: HTMLElement) { setFullscreenElement(this) }), configurable: true })
    const exit = vi.fn(async () => setFullscreenElement(null))
    Object.defineProperty(document, 'exitFullscreen', { value: exit, configurable: true })
    const w = mountViewer(target(providerOf(PNG_BYTES), {}))
    await flushPromises()
    await fullscreenButton(w).trigger('click')
    await flushPromises()
    await fullscreenButton(w).trigger('click')
    await flushPromises()
    expect(exit).toHaveBeenCalledTimes(1)
    expect(fullscreenButton(w).attributes('aria-pressed')).toBe('false')
  })

  it('falls back to a fixed overlay where the API is missing or refused, closed by the button or Escape', async () => {
    const w = mountViewer(target(providerOf(PNG_BYTES), {}))
    await flushPromises()
    expect(typeof HTMLElement.prototype.requestFullscreen).not.toBe('function') // happy-dom has none: the overlay path
    await fullscreenButton(w).trigger('click')
    expect(w.find('.file-viewer').classes()).toContain('file-viewer-full')
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(w.find('.file-viewer').classes()).not.toContain('file-viewer-full')
    await fullscreenButton(w).trigger('click')
    expect(w.find('.file-viewer').classes()).toContain('file-viewer-full')
    await fullscreenButton(w).trigger('click')
    expect(w.find('.file-viewer').classes()).not.toContain('file-viewer-full')

    Object.defineProperty(HTMLElement.prototype, 'requestFullscreen', { value: vi.fn(async () => { throw new Error('refused') }), configurable: true })
    await fullscreenButton(w).trigger('click')
    await flushPromises()
    expect(w.find('.file-viewer').classes()).toContain('file-viewer-full') // refused: the overlay stands in
  })
})
