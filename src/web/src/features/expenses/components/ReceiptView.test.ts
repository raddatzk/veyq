// @vitest-environment happy-dom
// An opened receipt (uc.expenses.attach-receipts step 3, ADR 0108 decision 7), each rule beside its control. The BYTES
// decide: a JPEG is an <img> on an object URL typed as the sniffed JPEG even when its name says PDF; a PDF goes to core's
// page renderer (`shared/pdf`, the engine mocked: happy-dom draws no canvas); a text that claims to be a PDF is never
// rendered, only offered for download, and pdf.js never sees it; a protected PDF is offered for download, never prompted.
// A blocked receipt reads nothing, and a read the server refuses as blocked (`451`) says so. What it made is undone on
// unmount.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../../shared/pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../../../shared/pdf/pdfEngine')>()), openPdf: vi.fn() }))

import ReceiptView from './ReceiptView.vue'
import { openPdf, PdfOpenError, type PdfDocument } from '../../../shared/pdf/pdfEngine'
import { realI18n, t } from '../../../test/i18n'
import type { ReceiptSource } from '../receipts'

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 1, 2])
const PDF = new TextEncoder().encode('%PDF-1.7\n1 0 obj\n')
const TEXT = new TextEncoder().encode('%PD not really: a note about a bill')

const source = (bytes: Uint8Array | (() => Promise<Uint8Array>), over: Partial<ReceiptSource> = {}): ReceiptSource => ({
  id: 'r1', name: 'bill.pdf', size: 10, blocked: false,
  read: vi.fn(typeof bytes === 'function' ? bytes : async () => bytes),
  download: vi.fn(async () => {}),
  ...over,
})
const doc = (): PdfDocument => ({ numPages: 1, pageSize: async () => ({ width: 595, height: 842 }), render: () => ({ done: Promise.resolve(true), cancel: () => {} }), destroy: vi.fn(async () => {}) })
const mountView = (s: ReceiptSource) => mount(ReceiptView, { props: { source: s }, global: { plugins: [realI18n()], stubs: { PdfPages: { template: '<div class="pdf-pages-stub" />' } } } })

let made: Blob[]
beforeEach(() => {
  vi.mocked(openPdf).mockReset()
  made = []
  vi.spyOn(URL, 'createObjectURL').mockImplementation((b) => {
    made.push(b as Blob)
    return `blob:receipt-${made.length}`
  })
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})
afterEach(() => vi.restoreAllMocks())

describe('ReceiptView', () => {
  it('draws a JPEG as an image typed by its bytes, whatever its name claims, and revokes it on unmount', async () => {
    const w = mountView(source(JPEG))
    await flushPromises()
    expect(w.find('img').attributes('src')).toBe('blob:receipt-1')
    expect(made[0]!.type).toBe('image/jpeg')
    expect(openPdf).not.toHaveBeenCalled()
    w.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:receipt-1')
  })

  it('hands a PDF to core\'s page renderer, never prompting for a password, and frees it on unmount', async () => {
    const d = doc()
    vi.mocked(openPdf).mockResolvedValue(d)
    const w = mountView(source(PDF))
    await flushPromises()
    expect(w.find('.pdf-pages-stub').exists()).toBe(true)
    expect(vi.mocked(openPdf).mock.calls[0]![0]).toEqual(PDF)
    expect(await vi.mocked(openPdf).mock.calls[0]![2]!(false)).toBeNull() // the prompt declines at once
    w.unmount()
    expect(d.destroy).toHaveBeenCalled()
  })

  it('a protected or unreadable PDF is offered for download', async () => {
    vi.mocked(openPdf).mockRejectedValue(new PdfOpenError('password'))
    const s = source(PDF)
    const w = mountView(s)
    await flushPromises()
    expect(w.text()).toContain(t('expenses.receipts.notShown'))
    await w.findAll('button').find((b) => b.text() === t('expenses.receipts.download'))!.trigger('click')
    expect(w.emitted('download')).toHaveLength(1)
  })

  it('a text that claims to be a PDF is never rendered: no image, no pdf.js, only the download', async () => {
    const w = mountView(source(TEXT))
    await flushPromises()
    expect(w.find('img').exists()).toBe(false)
    expect(w.find('.pdf-pages-stub').exists()).toBe(false)
    expect(openPdf).not.toHaveBeenCalled()
    expect(made).toEqual([])
    expect(w.text()).toContain(t('expenses.receipts.notShown'))
  })

  it('a blocked receipt reads nothing and says it is blocked (control: an unblocked one is read)', async () => {
    const blocked = source(JPEG, { blocked: true })
    const w = mountView(blocked)
    await flushPromises()
    expect(blocked.read).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('node.blockedByOperator'))
    const open = source(JPEG)
    mountView(open)
    await flushPromises()
    expect(open.read).toHaveBeenCalled()
  })

  it('a read the server refuses as blocked says so; another failure offers a retry', async () => {
    const refused = Object.assign(new Error('Response returned an error code'), { response: { status: 451 } })
    const w = mountView(source(async () => Promise.reject(refused)))
    await flushPromises()
    expect(w.text()).toContain(t('node.blockedByOperator'))
    const member = mountView(source(async () => Promise.reject(new Error('blocked_by_operator'))))
    await flushPromises()
    expect(member.text()).toContain(t('node.blockedByOperator'))
    const failed = mountView(source(async () => Promise.reject(new Error('network'))))
    await flushPromises()
    expect(failed.text()).toContain(t('expenses.receipts.openFailed'))
    expect(failed.findAll('button').some((b) => b.text() === t('expenses.receipts.retry'))).toBe(true)
  })
})
