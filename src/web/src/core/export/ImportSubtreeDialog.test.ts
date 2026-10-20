// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('./importSubtree', async (orig) => ({ ...(await orig<typeof import('./importSubtree')>()), openExport: vi.fn(), importSubtree: vi.fn() }))
vi.mock('../../features/tenant/tenantStorage', () => ({ tenantStorageFits: vi.fn() }))
vi.mock('../node/treeStore', async (orig) => ({ ...(await orig<typeof import('../node/treeStore')>()), loadTree: vi.fn(async () => {}) }))

import ImportSubtreeDialog from './ImportSubtreeDialog.vue'
import { ImportFailed, importSubtree, openExport, type OpenedExport } from './importSubtree'
import { ExportError, type ExportErrorCode, type Manifest } from './manifest'
import type { ImportInput } from './importSource'
import { realI18n, t } from '../../test/i18n'
import { i18n } from '../../i18n'
import { memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { tenantStorageFits } from '../../features/tenant/tenantStorage'
import { activeSpace } from '../../app/activeSpace'

const manifest: Manifest = {
  format: 'veyq-subtree', version: 1, exportedAt: 'x', appVersion: 'x',
  root: { id: 'n1', kind: 'FOLDER', name: 'Trip', content: { type: 'x.y', file: { path: 'data/n1/a', size: 2048, mediaType: 'x/y' } }, children: [{ id: 'n2', kind: 'ODD', name: 'strange' }] },
}
const opened = { source: {}, manifest } as unknown as OpenedExport
const input: ImportInput = { kind: 'zip', file: new File(['x'], 'trip.zip') }

async function mountDialog(uploadAsFile?: () => void) {
  const w = mount(ImportSubtreeDialog, {
    props: { input, access: memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) }), parent: null, targetName: 'Home', uploadAsFile },
    global: { plugins: [realI18n(), PrimeVue] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}
const body = () => document.body
const alerts = () => [...body().querySelectorAll('[data-testid="import-error"]')].map((e) => e.textContent).join(' ')

beforeEach(() => {
  document.body.innerHTML = ''
  vi.resetAllMocks()
  activeSpace.tenantId = 'tenant-1'
  vi.mocked(tenantStorageFits).mockResolvedValue(true)
})

describe('ImportSubtreeDialog', () => {
  const codes: ExportErrorCode[] = [
    'not_an_export', 'newer_version', 'invalid_manifest', 'too_large', 'unsafe_path', 'damaged_archive', 'payload_missing', 'size_mismatch',
    'checksum', 'nothing_to_import',
  ]
  it.each(codes)('a download refused before anything is written (%s) says why, with the place, and offers no import', async (code) => {
    vi.mocked(openExport).mockRejectedValue(new ExportError(code, 'x', 'Trip/a.txt'))
    await mountDialog()
    expect(t(`export.refused.${code}`)).not.toBe(`export.refused.${code}`)
    expect(alerts()).toContain(`${t(`export.refused.${code}`)} (Trip/a.txt)`)
    expect(body().querySelector('[data-testid="import-submit"]')).toBeNull()
    expect(importSubtree).not.toHaveBeenCalled()
  })

  it('a recognised ZIP from an upload also offers "Upload as a file", which hands it back and closes', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    const back = vi.fn()
    const w = await mountDialog(back)
    body().querySelector<HTMLButtonElement>('[data-testid="import-upload-as-file"]')!.click()
    await flushPromises()
    expect(back).toHaveBeenCalledTimes(1)
    expect(w.emitted('close')).toBeTruthy()
    expect(importSubtree).not.toHaveBeenCalled()
  })

  it('an input that did not come from an upload has no "Upload as a file"', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    await mountDialog()
    expect(body().querySelector('[data-testid="import-upload-as-file"]')).toBeNull()
  })

  it('shows what would be created and where, and what this build leaves out, before anything is written', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    await mountDialog()
    expect(body().querySelector('[data-testid="import-summary"]')?.textContent).toContain('Trip')
    expect(body().querySelector('[data-testid="import-summary"]')?.textContent).toContain('Home')
    // what is left out, per application and why (ADR 0082 decision 9) - and the action says it imports the REST
    expect(body().querySelector('[data-testid="import-skipped"] li[data-reason="unknown_application"]')?.textContent?.trim())
      .toBe(i18n.global.t('export.skipReason.unknown_application', { application: 'ODD', n: 1 }, 1))
    expect(body().querySelector('[data-testid="import-submit"]')?.textContent).toContain(t('export.importRest'))
    expect(importSubtree).not.toHaveBeenCalled()
  })

  it('newer content is listed as newer; an archive with nothing importable offers only Cancel', async () => {
    const nothing: Manifest = {
      format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [{ id: 'expenses', contentSchema: 1 }],
      root: { id: 'n1', kind: 'EXPENSES', name: 'Budget', path: 'Budget', content: { type: 'expenses.sheet', schema: 1 }, children: [{ id: 'n2', kind: 'EXPENSES', name: 'May', path: 'Budget/May' }] },
    }
    vi.mocked(openExport).mockResolvedValue({ source: {}, manifest: nothing } as unknown as OpenedExport)
    await mountDialog()
    expect(body().querySelector('[data-testid="import-skipped"]')?.textContent).toContain(i18n.global.t('export.skipReason.unknown_application', { application: 'expenses', n: 2 }, 2))
    expect(body().querySelector('[data-testid="import-summary"]')?.textContent).toContain(t('export.importNothing'))
    expect(body().querySelector('[data-testid="import-submit"]')).toBeNull()
    expect(body().querySelector('[data-testid="import-cancel"]')).not.toBeNull()
  })

  it('Import runs it under the folder with the member\'s keys, and a success closes with the new node', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    vi.mocked(importSubtree).mockResolvedValue({ nodeId: 'new1', nodes: 1, skipped: [] })
    const w = await mountDialog()
    body().querySelector<HTMLButtonElement>('[data-testid="import-submit"]')!.click()
    await flushPromises()
    expect(vi.mocked(importSubtree).mock.calls[0][0]).toMatchObject({ scopeId: 'scope-1', parent: null, opened })
    expect(w.emitted('done')).toEqual([['new1']])
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a stop part-way says how far it got and that the rest was removed; a quota stop says so too', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    vi.mocked(importSubtree).mockRejectedValueOnce(new ImportFailed(2, 5, true, null, new Error('boom')))
    const w = await mountDialog()
    body().querySelector<HTMLButtonElement>('[data-testid="import-submit"]')!.click()
    await flushPromises()
    expect(alerts()).toContain(t('export.importFailed', { done: 2, total: 5 }))
    vi.mocked(importSubtree).mockRejectedValueOnce(new ImportFailed(1, 5, false, 'x', new Error('quota_exceeded')))
    body().querySelector<HTMLButtonElement>('[data-testid="import-submit"]')!.click()
    await flushPromises()
    expect(alerts()).toContain(t('export.importQuota'))
    expect(w.emitted('done')).toBeUndefined()
  })

  describe('the quota preflight (the server\'s yes or no)', () => {
    const submit = () => body().querySelector<HTMLButtonElement>('[data-testid="import-submit"]')!

    it('asks once with the size of the dry run; a yes leaves the import open', async () => {
      vi.mocked(openExport).mockResolvedValue(opened)
      await mountDialog()
      expect(tenantStorageFits).toHaveBeenCalledTimes(1)
      expect(vi.mocked(tenantStorageFits).mock.calls[0]).toEqual(['tenant-1', 2048])
      expect(body().querySelector('[data-testid="import-no-room"]')).toBeNull()
      expect(submit().disabled).toBe(false)
    })

    it('a no says there is not enough room and does not let the import start', async () => {
      vi.mocked(openExport).mockResolvedValue(opened)
      vi.mocked(tenantStorageFits).mockResolvedValue(false)
      await mountDialog()
      expect(body().querySelector('[data-testid="import-no-room"]')?.textContent).toContain(t('export.importNoRoom', { size: '2 kB' }))
      expect(submit().disabled).toBe(true)
      submit().click()
      await flushPromises()
      expect(importSubtree).not.toHaveBeenCalled()
    })

    it('a question that could not be answered never blocks: the upload decides', async () => {
      vi.mocked(openExport).mockResolvedValue(opened)
      vi.mocked(tenantStorageFits).mockResolvedValue(null)
      await mountDialog()
      expect(body().querySelector('[data-testid="import-no-room"]')).toBeNull()
      expect(submit().disabled).toBe(false)
    })
  })
})
