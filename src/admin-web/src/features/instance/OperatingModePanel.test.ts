// @vitest-environment happy-dom
// uc.admin.operator-details (admin-web) — Server → Operating mode. Each case is one observable:
//   - the notice is on screen before anything is set, in the console's language (main flow 1);
//   - the switches the chosen mode's preset would change are listed before it is set, from the server's preset;
//   - *private* is not sent without the tick, and is sent with it (main flow 2); an unchosen mode says so.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./operator', async () => {
  const actual = await vi.importActual<typeof import('./operator')>('./operator')
  return { ...actual, getOperatingMode: vi.fn(), setOperatingMode: vi.fn() }
})

import OperatingModePanel from './OperatingModePanel.vue'
import { getOperatingMode, setOperatingMode, type InstanceOperatingMode } from './operator'
import { OPERATING_MODE_NOTICE } from '../../i18n/operatingModeNotice'
import { i18n } from '../../i18n'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  Button: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
  SelectButton: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template: '<div class="select"><button v-for="o in options" :key="o.value" type="button" :class="\'opt-\' + o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
  Checkbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" class="tick" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

const guestRecord = {
  id: 'core.public-shares.guest-write-record',
  title: { en: 'Guest-write record', de: 'Gast-Schreibprotokoll' },
  onInPrivate: false,
  onInOrganisation: true,
  off: false,
}

function modeRead(over: Partial<InstanceOperatingMode> = {}): InstanceOperatingMode {
  return { mode: 'ORGANISATION', chosen: true, preset: [guestRecord], ...over }
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(OperatingModePanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('OperatingModePanel (uc.admin.operator-details, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('shows the notice before anything is set, in the console’s language', async () => {
    vi.mocked(getOperatingMode).mockResolvedValue(modeRead())
    i18n.global.locale.value = 'de'
    const w = await mountPanel()
    expect(w.find('[data-test="mode-notice"]').text()).toContain(OPERATING_MODE_NOTICE.de.exemption)
    expect(setOperatingMode).not.toHaveBeenCalled()
  })

  it('lists the switches the chosen mode would change before it is set, and nothing for the mode in effect', async () => {
    vi.mocked(getOperatingMode).mockResolvedValue(modeRead())
    const w = await mountPanel()
    expect(w.find('.mode-changes').text()).toContain('changes no switch')
    await w.find('.opt-PRIVATE').trigger('click')
    expect(w.find('.mode-changes li').attributes('data-id')).toBe('core.public-shares.guest-write-record')
    expect(w.find('.mode-changes li').text()).toBe('Guest-write record: off')
  })

  it('does not send private without the tick, and sends it with the tick', async () => {
    vi.mocked(getOperatingMode).mockResolvedValue(modeRead())
    vi.mocked(setOperatingMode).mockResolvedValue(modeRead({ mode: 'PRIVATE', preset: [{ ...guestRecord, off: true }] }))
    const w = await mountPanel()
    await w.find('.opt-PRIVATE').trigger('click')
    expect(w.find('.mode-set').attributes('disabled')).toBeDefined()
    await w.find('.mode-set').trigger('click')
    expect(setOperatingMode).not.toHaveBeenCalled()

    await w.find('.tick').setValue(true)
    await w.find('.mode-set').trigger('click')
    await flushPromises()
    expect(setOperatingMode).toHaveBeenCalledWith('PRIVATE', true)
    expect(w.find('.mode-current').attributes('data-mode')).toBe('PRIVATE')
  })

  it('sets organisation with no tick asked', async () => {
    vi.mocked(getOperatingMode).mockResolvedValue(modeRead({ mode: 'PRIVATE' }))
    vi.mocked(setOperatingMode).mockResolvedValue(modeRead())
    const w = await mountPanel()
    await w.find('.opt-ORGANISATION').trigger('click')
    expect(w.find('.tick').exists()).toBe(false)
    await w.find('.mode-set').trigger('click')
    await flushPromises()
    expect(setOperatingMode).toHaveBeenCalledWith('ORGANISATION', false)
  })

  it('says when no mode was chosen yet, and that the instance runs as organisation', async () => {
    vi.mocked(getOperatingMode).mockResolvedValue(modeRead({ chosen: false }))
    const w = await mountPanel()
    expect(w.find('.mode-current').text()).toContain('runs as club, organisation or company')
  })
})
