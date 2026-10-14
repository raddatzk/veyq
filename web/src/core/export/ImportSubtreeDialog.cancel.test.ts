// @vitest-environment happy-dom
// ADR 0082 decision 9: Cancel writes nothing. The real reader and dry run over a real archive, the real dialog, a real click -
// and no request at all: the quota question is stubbed below the wire, so any fetch is a write the cancel let through.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

vi.mock('../node/tree', async (orig) => ({ ...(await orig<typeof import('../node/tree')>()), createContentNode: vi.fn(), deleteNode: vi.fn() }))
vi.mock('../../features/tenant/tenantStorage', () => ({ tenantStorageFits: vi.fn() }))

import ImportSubtreeDialog from './ImportSubtreeDialog.vue'
import { ZipWriter } from './zip'
import { createContentNode } from '../node/tree'
import { realI18n } from '../../test/i18n'
import { memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { activeSpace } from '../../app/activeSpace'
import { tenantStorageFits } from '../../features/tenant/tenantStorage'

async function download(): Promise<File> {
  const parts: Uint8Array[] = []
  const w = new ZipWriter({ write: async (b) => void parts.push(b.slice()), close: async () => {}, abort: async () => {} })
  const manifest = {
    format: 'veyq-subtree', version: 2, exportedAt: 'x', appVersion: 'x', applications: [],
    root: { id: 'n1', kind: 'FOLDER', name: 'Trip', path: 'Trip', children: [{ id: 'n2', kind: 'FOLDER', name: 'Day 1', path: 'Trip/Day 1' }] },
  }
  await w.addDirectory('Trip')
  await w.addDirectory('Trip/Day 1')
  await w.addBytes('.veyq/manifest.json', new TextEncoder().encode(JSON.stringify(manifest)))
  await w.finish()
  return new File(parts as BlobPart[], 'trip.zip')
}

const fetchSpy = vi.fn(async () => new Response('{}'))
beforeEach(() => {
  document.body.innerHTML = ''
  activeSpace.tenantId = 'tenant-1'
  vi.mocked(createContentNode).mockReset()
  vi.mocked(tenantStorageFits).mockResolvedValue(true)
  fetchSpy.mockClear()
  vi.stubGlobal('fetch', fetchSpy)
})
afterEach(() => vi.unstubAllGlobals())

describe('ImportSubtreeDialog: Cancel', () => {
  it('after the dry run of a real download, Cancel closes and nothing was written or requested', async () => {
    const w = mount(ImportSubtreeDialog, {
      props: { input: { kind: 'zip', file: await download() }, access: memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) }), parent: null, targetName: 'Home' },
      global: { plugins: [realI18n(), PrimeVue] },
      attachTo: document.body,
    })
    await flushPromises()
    await vi.waitFor(() => expect(document.querySelector('[data-testid="import-summary"]')?.textContent).toContain('Trip'))
    // control: the import is offered - the cancel below is a choice, not the only way out
    expect(document.querySelector<HTMLButtonElement>('[data-testid="import-submit"]')?.disabled).toBe(false)
    document.querySelector<HTMLButtonElement>('[data-testid="import-cancel"]')!.click()
    await flushPromises()
    expect(w.emitted('close')).toBeTruthy()
    expect(createContentNode).not.toHaveBeenCalled()
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
