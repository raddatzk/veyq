// @vitest-environment happy-dom
// uc.admin.tenant-switch-capabilities (web) — the owner-gated Capabilities rubric: the served words in the app's
// language, switching off only after a confirmation that shows the entry's `whenOff`, the shell's own set re-read
// after a switch, and a 403 read rendered as the read-only notice.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./tenantCapabilities', async (orig) => ({
  ...(await orig<typeof import('./tenantCapabilities')>()),
  listTenantCapabilitySwitches: vi.fn(),
  setTenantCapabilitySwitch: vi.fn(),
  getTenantCapabilities: vi.fn(),
  setTenantCapabilityRequirement: vi.fn(),
  clearTenantCapabilityRequirement: vi.fn(),
}))
const ask = vi.fn()
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask }) }))

import TenantCapabilitiesPanel from './TenantCapabilitiesPanel.vue'
import {
  clearTenantCapabilityRequirement, getTenantCapabilities, listTenantCapabilitySwitches, setTenantCapabilityRequirement,
  setTenantCapabilitySwitch, type TenantCapabilityRequirement, type TenantCapabilitySwitch,
} from './tenantCapabilities'
import { activeSpace, clearActiveSpace } from '../../app/activeSpace'
import { capabilityState } from '../../shared/state/capabilities'
import { ScopeKind } from '../../api/generated/scopes'
import { realI18n, t } from '../../test/i18n'

const stubs = {
  VeyqButton: { props: ['label', 'loading', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
}

function requirement(name: string, kind: 'flag' | 'days', over: Partial<TenantCapabilityRequirement> = {}): TenantCapabilityRequirement {
  return {
    name, kind, title: { en: `${name} title`, de: 'Titel' }, whenSet: { en: `${name} does this to what exists`, de: 'Wirkung' },
    value: null, serverValue: null, effective: null, ...over,
  } as TenantCapabilityRequirement
}

function entry(id: string, title: string, over: Partial<TenantCapabilitySwitch> = {}): TenantCapabilitySwitch {
  return {
    id, title: { en: title, de: `${title} (de)` }, purpose: { en: `${title} purpose`, de: 'Zweck' },
    whenOff: { en: `${title} off effect`, de: 'Wirkung' }, off: false, serverOff: false, effective: true, requirements: [], ...over,
  }
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(TenantCapabilitiesPanel, { global: { plugins: [router, realI18n()], stubs } })
  await flushPromises()
  return wrapper
}

const row = (w: Awaited<ReturnType<typeof mountPanel>>, id: string) => w.find(`li[data-id="${id}"]`)

describe('TenantCapabilitiesPanel (uc.admin.tenant-switch-capabilities, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    clearActiveSpace()
    activeSpace.kind = ScopeKind.Tenant
    activeSpace.scopeId = 't-1'
    activeSpace.tenantId = 't-1'
  })

  it('renders the served words and each state, the server-off one included', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockResolvedValue([
      entry('core.public-shares', 'Public links', { off: true, effective: false }),
      entry('core.public-shares.contribute', 'Links to add', { effective: false }),
      entry('core.group-space', 'Groups', { serverOff: true, effective: false }),
    ])
    const wrapper = await mountPanel()
    expect(listTenantCapabilitySwitches).toHaveBeenCalledWith('t-1')
    expect(row(wrapper, 'core.public-shares').text()).toContain('Public links purpose')
    expect(row(wrapper, 'core.public-shares').text()).toContain(t('tenantCapabilities.off'))
    expect(row(wrapper, 'core.public-shares.contribute').text()).toContain(t('tenantCapabilities.offThrough', { parent: 'Public links' }))
    expect(row(wrapper, 'core.group-space').text()).toContain(t('tenantCapabilities.serverOff'))
  })

  it('switches off only after a confirmation showing the entry\'s own words, then re-reads the shell\'s set', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockResolvedValue([entry('core.group-space', 'Groups')])
    vi.mocked(setTenantCapabilitySwitch).mockResolvedValue(entry('core.group-space', 'Groups', { off: true, effective: false }))
    vi.mocked(getTenantCapabilities).mockResolvedValue([{ id: 'core.public-shares' }])
    const wrapper = await mountPanel()

    ask.mockResolvedValueOnce(false) // declined: nothing is sent
    await row(wrapper, 'core.group-space').find('button').trigger('click')
    await flushPromises()
    expect(setTenantCapabilitySwitch).not.toHaveBeenCalled()
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ message: 'Groups off effect' }))

    ask.mockResolvedValueOnce(true)
    await row(wrapper, 'core.group-space').find('button').trigger('click')
    await flushPromises()
    expect(setTenantCapabilitySwitch).toHaveBeenCalledWith('t-1', 'core.group-space', true)
    expect(row(wrapper, 'core.group-space').text()).toContain(t('tenantCapabilities.off')) // from the PUT's answer
    expect(capabilityState.tenantIds).toEqual(['core.public-shares']) // the shell's own set followed
  })

  it('switches back on without asking', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockResolvedValue([entry('core.group-space', 'Groups', { off: true, effective: false })])
    vi.mocked(setTenantCapabilitySwitch).mockResolvedValue(entry('core.group-space', 'Groups'))
    vi.mocked(getTenantCapabilities).mockResolvedValue([])
    const wrapper = await mountPanel()
    await row(wrapper, 'core.group-space').find('button').trigger('click')
    await flushPromises()
    expect(ask).not.toHaveBeenCalled()
    expect(setTenantCapabilitySwitch).toHaveBeenCalledWith('t-1', 'core.group-space', false)
  })

  it('renders a non-owner\'s 403 as the read-only notice, not the register', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockRejectedValue(httpError(403, 'not_tenant_owner'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(t('tenantCapabilities.notOwner'))
    expect(wrapper.find('li').exists()).toBe(false)
  })

  it('sets a requirement only after showing what it does to what exists, and clears one without asking', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockResolvedValue([
      entry('core.public-shares', 'Public links', { requirements: [requirement('linkPassword', 'flag'), requirement('linkMaxDays', 'days', { value: 30, effective: 30 })] }),
    ])
    vi.mocked(setTenantCapabilityRequirement).mockResolvedValue(entry('core.public-shares', 'Public links', {
      requirements: [requirement('linkPassword', 'flag', { value: 1, effective: 1 }), requirement('linkMaxDays', 'days', { value: 30, effective: 30 })],
    }))
    vi.mocked(clearTenantCapabilityRequirement).mockResolvedValue(entry('core.public-shares', 'Public links', { requirements: [] }))
    vi.mocked(getTenantCapabilities).mockResolvedValue([{ id: 'core.public-shares', linkPassword: true }])
    const wrapper = await mountPanel()
    const flag = wrapper.find('li[data-requirement="linkPassword"]')
    expect(flag.text()).toContain(t('tenantCapabilities.requirementNone'))

    ask.mockResolvedValueOnce(true)
    await flag.find('button').trigger('click')
    await flushPromises()
    expect(ask).toHaveBeenCalledWith(expect.objectContaining({ message: 'linkPassword does this to what exists' }))
    expect(setTenantCapabilityRequirement).toHaveBeenCalledWith('t-1', 'core.public-shares', 'linkPassword', 1)
    expect(capabilityState.tenantEntries?.['core.public-shares']?.linkPassword).toBe(true) // the shell's values followed

    const days = wrapper.find('li[data-requirement="linkMaxDays"]')
    await days.find('input').setValue('7')
    ask.mockResolvedValueOnce(true)
    await days.find('.requirement__set').trigger('click')
    await flushPromises()
    expect(setTenantCapabilityRequirement).toHaveBeenLastCalledWith('t-1', 'core.public-shares', 'linkMaxDays', 7)

    ask.mockClear()
    await wrapper.find('li[data-requirement="linkMaxDays"] .requirement__clear').trigger('click')
    await flushPromises()
    expect(ask).not.toHaveBeenCalled()
    expect(clearTenantCapabilityRequirement).toHaveBeenCalledWith('t-1', 'core.public-shares', 'linkMaxDays')
  })

  it('says when the server\'s stricter value is the one in effect, and refuses days out of range without calling', async () => {
    vi.mocked(listTenantCapabilitySwitches).mockResolvedValue([
      entry('core.public-shares', 'Public links', { requirements: [requirement('linkMaxDays', 'days', { value: 60, serverValue: 30, effective: 30 })] }),
    ])
    const wrapper = await mountPanel()
    const days = wrapper.find('li[data-requirement="linkMaxDays"]')
    expect(days.text()).toContain(t('tenantCapabilities.requirementByServer', { value: t('tenantCapabilities.requirementDays', { days: 30 }) }))
    await days.find('input').setValue('0')
    await days.find('.requirement__set').trigger('click')
    await flushPromises()
    expect(setTenantCapabilityRequirement).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('tenantCapabilities.requirementInvalidDays'))
  })
})
