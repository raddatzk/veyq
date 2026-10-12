// @vitest-environment happy-dom
// The plain-text half of the file viewer (inbox #412): the text is ordinary, selectable page text (nothing sets
// `user-select: none` on it - checked against the real DOM and the real stylesheets), and two small conveniences sit above
// it: Copy (the clipboard API, with a visible fallback that selects the text instead) and a wrap toggle. What is copied is
// what is SHOWN: a truncated text copies its shown part and says so. The text stays `textContent`, never markup.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('../../../sw/register', () => ({
  registerPlayback: vi.fn(async () => null),
  revokePlayback: vi.fn(),
  handleFromUrl: (url: string) => url.replace('/__media/', ''),
}))
vi.mock('../pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../pdf/pdfEngine')>()), openPdf: vi.fn() }))

import FileViewer from './FileViewer.vue'
import { TEXT_CAP } from '../view'
import type { ViewerTarget } from '../useFileViewer'
import { providerOf, utf8, type FakeProvider } from '../../../test/fileChunks'
import { realI18n, t } from '../../../test/i18n'
import { unselectableBy, userSelectNoneRules } from '../../../test/userSelect'

const mounted: { unmount: () => void }[] = []
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  Object.defineProperty(navigator, 'clipboard', { value: realClipboard, configurable: true })
})
const realClipboard = Object.getOwnPropertyDescriptor(navigator, 'clipboard')?.value
function setClipboard(value: unknown): void {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
}
beforeEach(() => setClipboard(undefined))

const target = (source: FakeProvider, id = 'blob-1'): ViewerTarget => ({ id, source, hints: { type: 'text/plain', name: 'notes.txt' } })
function mountText(text: string, id = 'blob-1') {
  const w = mount(FileViewer, {
    props: { target: target(providerOf(utf8(text)), id), name: 'notes.txt', canDownload: true },
    attachTo: document.body,
    global: { plugins: [realI18n(), PrimeVue] },
  })
  mounted.push(w)
  return w
}
type W = ReturnType<typeof mountText>
const copyButton = (w: W) => w.find('button.file-viewer-text-copy')
const wrapButton = (w: W) => w.find('button.file-viewer-text-wrap')
const note = (w: W) => w.find('.file-viewer-text-note')
const pre = (w: W) => w.find('pre.file-viewer-text')

describe('the text stays selectable page text', () => {
  it('is a <pre> of plain text with no inline or stylesheet rule making it unselectable', async () => {
    const w = mountText('Zeile eins\nZeile zwei')
    await flushPromises()
    const el = pre(w).element
    expect(el.getAttribute('style') ?? '').not.toMatch(/user-select/i)
    expect(unselectableBy(el)).toEqual([])
    expect(unselectableBy(w.find('.file-viewer').element)).toEqual([])
    expect(el.closest('body')).not.toBeNull()
  })

  it('the check bites: it does find the rules that make other things unselectable, and matches them against a real element', () => {
    const rules = userSelectNoneRules()
    expect(rules.length).toBeGreaterThan(0) // the cropper image and the slide-to-confirm handle do set it
    const probe = document.createElement('img')
    probe.className = 'memory-cover-cropper-image'
    const holder = document.createElement('div')
    holder.className = 'memory-cover-cropper'
    holder.append(probe)
    expect(unselectableBy(probe).map((r) => r.file)).toContain('styles/memory.css')
    const fine = document.createElement('pre')
    fine.className = 'file-viewer-text'
    expect(unselectableBy(fine)).toEqual([])
  })

  it('a selection over the text yields exactly the text (the Selection API sees it as ordinary content)', async () => {
    const w = mountText('Zeile eins\nZeile zwei')
    await flushPromises()
    const sel = window.getSelection()!
    sel.selectAllChildren(pre(w).element)
    expect(sel.toString()).toBe('Zeile eins\nZeile zwei')
  })

  it('is still text, not markup', async () => {
    const w = mountText('<b>bold</b> & <script>1</script>')
    await flushPromises()
    expect(pre(w).element.textContent).toBe('<b>bold</b> & <script>1</script>')
    expect(pre(w).element.children).toHaveLength(0)
  })
})

describe('Copy', () => {
  it('writes exactly the shown text to the clipboard and says so', async () => {
    const writeText = vi.fn(async (_text: string) => {})
    setClipboard({ writeText })
    const w = mountText('Hallo\nWelt')
    await flushPromises()
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText).toHaveBeenCalledWith('Hallo\nWelt')
    expect(note(w).text()).toBe(t('files.viewer.text.copied'))
    expect(note(w).attributes('role')).toBe('status')
  })

  it('has an accessible name and is a real button', async () => {
    const w = mountText('x')
    await flushPromises()
    expect(copyButton(w).element.tagName).toBe('BUTTON')
    expect(copyButton(w).attributes('type')).toBe('button')
    expect(copyButton(w).attributes('aria-label')).toBe(t('files.viewer.text.copy'))
    expect(w.find('[role="toolbar"]').attributes('aria-label')).toBe(t('files.viewer.text.toolbar'))
  })

  it('a truncated text copies only the shown part, and the button and the note say so', async () => {
    const writeText = vi.fn(async (_text: string) => {})
    setClipboard({ writeText })
    const w = mountText('zeile\n'.repeat(Math.ceil((TEXT_CAP * 2) / 6)))
    await flushPromises()
    expect(copyButton(w).attributes('aria-label')).toBe(t('files.viewer.text.copyShown'))
    await copyButton(w).trigger('click')
    await flushPromises()
    const copied = writeText.mock.calls[0]![0]
    expect(copied.length).toBe(TEXT_CAP)
    expect(copied).toBe(pre(w).element.textContent)
    expect(note(w).text()).toBe(t('files.viewer.text.copiedShown'))
  })

  it('without a clipboard API it selects the text instead and tells the user to press Ctrl+C', async () => {
    setClipboard(undefined)
    const w = mountText('Hallo Welt')
    await flushPromises()
    window.getSelection()!.removeAllRanges()
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(note(w).text()).toBe(t('files.viewer.text.copyManual'))
    expect(window.getSelection()!.toString()).toBe('Hallo Welt')
  })

  it('a clipboard that refuses (permission, no focus) is the same visible fallback, never a silent failure or an unhandled rejection', async () => {
    setClipboard({ writeText: vi.fn(async () => Promise.reject(new DOMException('denied', 'NotAllowedError'))) })
    const w = mountText('Hallo Welt')
    await flushPromises()
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(note(w).text()).toBe(t('files.viewer.text.copyManual'))
    expect(window.getSelection()!.toString()).toBe('Hallo Welt')
  })

  it('a clipboard object without writeText counts as no clipboard', async () => {
    setClipboard({})
    const w = mountText('Hallo')
    await flushPromises()
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(note(w).text()).toBe(t('files.viewer.text.copyManual'))
  })

  it('the note goes away by itself, and with the file', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    setClipboard({ writeText: vi.fn(async () => {}) })
    const w = mountText('eins', 'blob-1')
    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(note(w).exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(6100)
    expect(note(w).exists()).toBe(false)
    await copyButton(w).trigger('click')
    await flushPromises()
    expect(note(w).exists()).toBe(true)
    await w.setProps({ target: target(providerOf(utf8('zwei')), 'blob-2') })
    await vi.advanceTimersByTimeAsync(0)
    await flushPromises()
    expect(pre(w).text()).toBe('zwei')
    expect(note(w).exists()).toBe(false)
  })

  it('shows no copy or wrap controls for anything but a text', async () => {
    const w = mount(FileViewer, {
      props: { target: target(providerOf(Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 0x49, 0x48, 0x44, 0x52]))), name: 'a.png', canDownload: true },
      attachTo: document.body,
      global: { plugins: [realI18n(), PrimeVue] },
    })
    mounted.push(w)
    await flushPromises()
    expect(w.find('.file-viewer-text-copy, .file-viewer-text-wrap').exists()).toBe(false)
  })
})

describe('the wrap toggle', () => {
  it('lines wrap by default; the toggle is a pressed/unpressed button that switches to scrolling sideways and back', async () => {
    const w = mountText('a very long line '.repeat(20))
    await flushPromises()
    expect(wrapButton(w).attributes('aria-pressed')).toBe('true')
    expect(pre(w).classes()).not.toContain('file-viewer-text-nowrap')
    await wrapButton(w).trigger('click')
    expect(wrapButton(w).attributes('aria-pressed')).toBe('false')
    expect(pre(w).classes()).toContain('file-viewer-text-nowrap')
    await wrapButton(w).trigger('click')
    expect(wrapButton(w).attributes('aria-pressed')).toBe('true')
    expect(pre(w).classes()).not.toContain('file-viewer-text-nowrap')
    expect(wrapButton(w).text()).toBe(t('files.viewer.text.wrap'))
  })

  it('does not touch the text itself', async () => {
    const w = mountText('eins\nzwei')
    await flushPromises()
    await wrapButton(w).trigger('click')
    expect(pre(w).element.textContent).toBe('eins\nzwei')
  })
})
