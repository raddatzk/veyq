// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('./importSubtree', async (orig) => ({ ...(await orig<typeof import('./importSubtree')>()), openExport: vi.fn(), importSubtree: vi.fn() }))
vi.mock('../node/treeStore', async (orig) => ({ ...(await orig<typeof import('../node/treeStore')>()), loadTree: vi.fn(async () => {}) }))

import ImportSubtreeDialog from './ImportSubtreeDialog.vue'
import { ImportFailed, importSubtree, openExport, type OpenedExport } from './importSubtree'
import { ExportError, type Manifest } from './manifest'
import { realI18n, t } from '../../test/i18n'
import { memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'

const manifest: Manifest = {
  format: 'veyq-subtree', version: 1, exportedAt: 'x', appVersion: 'x',
  root: { id: 'n1', kind: 'FOLDER', name: 'Trip', children: [{ id: 'n2', kind: 'ODD', name: 'strange' }] },
}
const opened = { zip: {}, manifest } as unknown as OpenedExport

async function mountDialog() {
  const w = mount(ImportSubtreeDialog, {
    props: { file: new Blob(['x']), access: memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) }), parent: null, targetName: 'Home' },
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
})

describe('ImportSubtreeDialog', () => {
  it.each([
    ['not_an_export', 'export.notExport'],
    ['newer_version', 'export.newer'],
    ['invalid_manifest', 'export.invalid'],
  ] as const)('a file that cannot be read (%s) says why and offers no import', async (code, key) => {
    vi.mocked(openExport).mockRejectedValue(new ExportError(code, 'x'))
    await mountDialog()
    expect(alerts()).toContain(t(key))
    expect(body().querySelector('[data-testid="import-submit"]')).toBeNull()
  })

  it('shows what would be created and where, and what this build leaves out, before anything is written', async () => {
    vi.mocked(openExport).mockResolvedValue(opened)
    await mountDialog()
    expect(body().querySelector('[data-testid="import-summary"]')?.textContent).toContain('Trip')
    expect(body().querySelector('[data-testid="import-summary"]')?.textContent).toContain('Home')
    expect(body().querySelector('[data-testid="import-skipped"]')?.textContent).toContain('strange')
    expect(importSubtree).not.toHaveBeenCalled()
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
})
