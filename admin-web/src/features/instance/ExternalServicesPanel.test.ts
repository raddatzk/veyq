// @vitest-environment happy-dom
// uc.admin.configure-external-services (admin-web) — the "External services" rubric: each service with its served name,
// its address and state; a save sends what was typed, the tiles' attribution only for the tiles; removing asks first.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { i18n } from '../../i18n'

vi.mock('./externalServices', () => ({ listExternalServices: vi.fn(), setExternalService: vi.fn(), clearExternalService: vi.fn() }))
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import ExternalServicesPanel from './ExternalServicesPanel.vue'
import { clearExternalService, listExternalServices, setExternalService, type InstanceExternalService } from './externalServices'

const stubs = {
  Message: { template: '<div class="msg"><slot /></div>' },
  Button: { props: ['label', 'disabled'], emits: ['click'], template: '<button type="button" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})

const map = (url: string | null): InstanceExternalService => ({
  id: 'memories.map', title: { en: 'Maps', de: 'Karten' }, url, attribution: url ? '© OSM' : null, takesAttribution: true,
})
const places = (url: string | null): InstanceExternalService => ({
  id: 'memories.places', title: { en: 'Place names', de: 'Ortsnamen' }, url, attribution: null, takesAttribution: false,
})

async function mountPanel(list: InstanceExternalService[]) {
  vi.mocked(listExternalServices).mockResolvedValue(list)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const w = mount(ExternalServicesPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return w
}
const row = (w: Awaited<ReturnType<typeof mountPanel>>, id: string) => w.find(`[data-id="${id}"]`)

describe('ExternalServicesPanel (uc.admin.configure-external-services, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('shows each service with its address and state, the attribution field for the tiles only', async () => {
    const w = await mountPanel([map('https://tile.openstreetmap.org/{z}/{x}/{y}.png'), places(null)])
    expect(row(w, 'memories.map').text()).toContain(t('services.configured'))
    expect((row(w, 'memories.map').find('.service__url').element as HTMLInputElement).value).toBe('https://tile.openstreetmap.org/{z}/{x}/{y}.png')
    expect(row(w, 'memories.map').find('.service__attribution').exists()).toBe(true)
    expect(row(w, 'memories.places').text()).toContain(t('services.notConfigured'))
    expect(row(w, 'memories.places').find('.service__attribution').exists()).toBe(false)
  })

  it('saves what was typed, without an attribution for a service that takes none', async () => {
    vi.mocked(setExternalService).mockResolvedValue(places('https://geo.example.com'))
    const w = await mountPanel([places(null)])
    await row(w, 'memories.places').find('.service__url').setValue('  https://geo.example.com ')
    await row(w, 'memories.places').find('.service__save').trigger('click')
    await flushPromises()
    expect(setExternalService).toHaveBeenCalledWith('memories.places', 'https://geo.example.com', null)
    expect(row(w, 'memories.places').text()).toContain(t('services.configured'))
  })

  it('removes an address only after asking, and names a refused address', async () => {
    vi.mocked(clearExternalService).mockResolvedValue(map(null))
    const w = await mountPanel([map('https://tile.openstreetmap.org/{z}/{x}/{y}.png')])
    confirmAsk.mockResolvedValueOnce(false)
    await row(w, 'memories.map').find('.service__remove').trigger('click')
    await flushPromises()
    expect(clearExternalService).not.toHaveBeenCalled()
    confirmAsk.mockResolvedValueOnce(true)
    await row(w, 'memories.map').find('.service__remove').trigger('click')
    await flushPromises()
    expect(clearExternalService).toHaveBeenCalledWith('memories.map')
    expect(row(w, 'memories.map').text()).toContain(t('services.notConfigured'))
  })
})
