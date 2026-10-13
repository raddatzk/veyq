// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('./exportSubtree', async (orig) => ({ ...(await orig<typeof import('./exportSubtree')>()), prepareExport: vi.fn() }))
vi.mock('../../shared/saveStream', async (orig) => ({ ...(await orig<typeof import('../../shared/saveStream')>()), openSaveTarget: vi.fn() }))

import ExportSubtreeDialog from './ExportSubtreeDialog.vue'
import { prepareExport, type PreparedExport } from './exportSubtree'
import { SaveCancelled, SaveTooLarge, openSaveTarget } from '../../shared/saveStream'
import { realI18n, t } from '../../test/i18n'
import { memberAccessOf, treeNode } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'

const node = treeNode({ id: 'n1', kind: 'FOLDER', name: 'Trip' })
const sink = { write: vi.fn(), close: vi.fn(), abort: vi.fn() }
const prepared = (over: Partial<PreparedExport> = {}): PreparedExport => ({
  nodes: 4, payloads: 3, bytes: 2_500_000, skipped: [{ name: 'Box', kind: 'FILES_DROPBOX', reason: 'unsupported_kind' }],
  write: vi.fn(async () => {}), ...over,
})

async function mountDialog() {
  const w = mount(ExportSubtreeDialog, {
    props: { node, access: memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) }), displayName: 'Trip' },
    global: { plugins: [realI18n(), PrimeVue] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}
const body = () => document.body

beforeEach(() => {
  document.body.innerHTML = ''
  vi.resetAllMocks()
  vi.mocked(openSaveTarget).mockResolvedValue(sink)
})

describe('ExportSubtreeDialog', () => {
  it('warns first that the file is plain, offers the location choice, and walks the tree only on Continue', async () => {
    await mountDialog()
    expect(body().querySelector('[data-testid="export-warning"]')?.textContent).toContain(t('export.warning'))
    expect(prepareExport).not.toHaveBeenCalled()
    expect(body().querySelector('[data-testid="export-location"]')).not.toBeNull()
  })

  it('passes the location choice to the walk and then shows what is in the export and what is left out', async () => {
    vi.mocked(prepareExport).mockResolvedValue(prepared())
    await mountDialog()
    body().querySelector<HTMLInputElement>('[data-testid="export-location"] input')!.click()
    await flushPromises()
    body().querySelector<HTMLButtonElement>('[data-testid="export-prepare"]')!.click()
    await flushPromises()
    expect(vi.mocked(prepareExport).mock.calls[0][0]).toMatchObject({ rootId: 'n1', options: { includeLocation: false } })
    expect(body().querySelector('[data-testid="export-summary"]')?.textContent).toContain('4')
    expect(body().querySelector('[data-testid="export-skipped"]')?.textContent).toContain('Box')
  })

  it('Save opens the save target FIRST with the known size, then writes into it and closes', async () => {
    const p = prepared()
    vi.mocked(prepareExport).mockResolvedValue(p)
    const w = await mountDialog()
    body().querySelector<HTMLButtonElement>('[data-testid="export-prepare"]')!.click()
    await flushPromises()
    body().querySelector<HTMLButtonElement>('[data-testid="export-save"]')!.click()
    await flushPromises()
    expect(openSaveTarget).toHaveBeenCalledWith('Trip.zip', 2_500_000)
    expect(p.write).toHaveBeenCalledWith(sink, expect.any(Function))
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a browser that cannot save this much says so; a cancelled picker is silent and writes nothing', async () => {
    vi.mocked(prepareExport).mockResolvedValue(prepared())
    const w = await mountDialog()
    body().querySelector<HTMLButtonElement>('[data-testid="export-prepare"]')!.click()
    await flushPromises()
    vi.mocked(openSaveTarget).mockRejectedValueOnce(new SaveCancelled())
    body().querySelector<HTMLButtonElement>('[data-testid="export-save"]')!.click()
    await flushPromises()
    expect(body().querySelector('[role="alert"]')).toBeNull()
    vi.mocked(openSaveTarget).mockRejectedValueOnce(new SaveTooLarge(10))
    body().querySelector<HTMLButtonElement>('[data-testid="export-save"]')!.click()
    await flushPromises()
    expect(body().querySelector('[role="alert"]')?.textContent).toContain(t('export.tooLarge'))
    expect(w.emitted('close')).toBeUndefined()
  })

  it('a walk that fails says the export did not finish and returns to the choice', async () => {
    vi.mocked(prepareExport).mockRejectedValue(new Error('boom'))
    await mountDialog()
    body().querySelector<HTMLButtonElement>('[data-testid="export-prepare"]')!.click()
    await flushPromises()
    expect([...body().querySelectorAll('[role="alert"]')].map((e) => e.textContent).join(' ')).toContain(t('export.failed'))
    expect(body().querySelector('[data-testid="export-prepare"]')).not.toBeNull()
  })
})
