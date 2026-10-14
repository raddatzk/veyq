// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('./exportSubtree', async (orig) => ({ ...(await orig<typeof import('./exportSubtree')>()), prepareExport: vi.fn() }))
vi.mock('../../shared/saveStream', async (orig) => ({ ...(await orig<typeof import('../../shared/saveStream')>()), openSaveTarget: vi.fn() }))

import ExportSubtreeDialog from './ExportSubtreeDialog.vue'
import { prepareExport, type PreparedExport } from './exportSubtree'
import { ExportError } from './manifest'
import { SaveCancelled, SaveTooLarge, openSaveTarget } from '../../shared/saveStream'
import { realI18n, t } from '../../test/i18n'
import { guestAccessOf, memberAccessOf, treeNode } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from '../node/tree'

const node = treeNode({ id: 'n1', kind: 'FOLDER', name: 'Trip: 2025' })
const tree = { rootId: null } as unknown as TreeSource<TreeNode>
const sink = { write: vi.fn(), close: vi.fn(), abort: vi.fn() }
const prepared = (over: Partial<PreparedExport> = {}): PreparedExport => ({
  nodes: 4, payloads: 3, bytes: 2_500_000, skipped: [{ name: 'Box', kind: 'FILES_DROPBOX', reason: 'unsupported_kind' }],
  write: vi.fn(async () => {}), ...over,
})

async function mountDialog(access: Access = memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) })) {
  const w = mount(ExportSubtreeDialog, {
    props: { node, access, tree, displayName: 'Trip: 2025' },
    global: { plugins: [realI18n(), PrimeVue] },
    attachTo: document.body,
  })
  await flushPromises()
  return w
}
const body = () => document.body
const save = () => body().querySelector<HTMLButtonElement>('[data-testid="export-save"]')!

beforeEach(() => {
  document.body.innerHTML = ''
  vi.resetAllMocks()
  vi.mocked(openSaveTarget).mockResolvedValue(sink)
})

describe('ExportSubtreeDialog: Download as a ZIP', () => {
  it('asks nothing: it walks the tree at once, with the session\'s access and tree, and says what is in it and what is left out', async () => {
    vi.mocked(prepareExport).mockResolvedValue(prepared())
    const access = guestAccessOf('READ')
    await mountDialog(access)
    expect(vi.mocked(prepareExport).mock.calls[0][0]).toMatchObject({ rootId: 'n1', access, tree })
    expect(vi.mocked(prepareExport).mock.calls[0][0]).not.toHaveProperty('options')
    expect(body().querySelector('[data-testid="export-summary"]')?.textContent).toContain('4')
    expect(body().querySelector('[data-testid="export-skipped"]')?.textContent).toContain('Box')
    // no warning, no location choice (ADR 0082 decision 4)
    expect(body().querySelector('input[type="checkbox"]')).toBeNull()
    expect(body().querySelector('[role="alert"]')).toBeNull()
  })

  it('"Save as ZIP…" opens the save target FIRST with the safe name and the known size, then writes into it and closes', async () => {
    const p = prepared()
    vi.mocked(prepareExport).mockResolvedValue(p)
    const w = await mountDialog()
    expect(save().textContent).toContain(t('export.save'))
    save().click()
    await flushPromises()
    expect(openSaveTarget).toHaveBeenCalledWith('Trip_ 2025.zip', 2_500_000)
    expect(p.write).toHaveBeenCalledWith(sink, expect.any(Function))
    expect(w.emitted('close')).toBeTruthy()
  })

  it('a browser that cannot save this much says so; a cancelled picker is silent and writes nothing', async () => {
    vi.mocked(prepareExport).mockResolvedValue(prepared())
    const w = await mountDialog()
    vi.mocked(openSaveTarget).mockRejectedValueOnce(new SaveCancelled())
    save().click()
    await flushPromises()
    expect(body().querySelector('[role="alert"]')).toBeNull()
    vi.mocked(openSaveTarget).mockRejectedValueOnce(new SaveTooLarge(10))
    save().click()
    await flushPromises()
    expect(body().querySelector('[role="alert"]')?.textContent).toContain(t('export.tooLarge'))
    expect(w.emitted('close')).toBeUndefined()
  })

  it('a walk that fails says so and offers no save; nothing under the node says there is nothing to download', async () => {
    vi.mocked(prepareExport).mockRejectedValue(new Error('boom'))
    const first = await mountDialog()
    expect(body().querySelector('[role="alert"]')?.textContent).toContain(t('export.failed'))
    expect(body().querySelector('[data-testid="export-save"]')).toBeNull()
    first.unmount()
    vi.mocked(prepareExport).mockRejectedValue(new ExportError('nothing_to_import', 'x'))
    await mountDialog()
    expect(body().querySelector('[role="alert"]')?.textContent).toContain(t('export.nothing'))
  })
})
