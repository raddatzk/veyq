// @vitest-environment happy-dom
// uc.admin.operator-details (admin-web) — Server → Operator. Each case is one observable:
//   - the organisation mode warns while public links are on and no contact is set, and stops once one is (A1);
//   - the identification warning is its own, independent of the contact and of public links (A1);
//   - private warns about nothing, every value empty and public links on — the Impressum warning never shows there;
//   - a save sends every value, an empty field as null; a CODE_SENT answer offers the code entry, and the code confirms
//     (main flow 4); instance mail that is not ready is said (A2).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./operator', async () => {
  const actual = await vi.importActual<typeof import('./operator')>('./operator')
  return { ...actual, getOperator: vi.fn(), setOperator: vi.fn(), clearOperator: vi.fn(), confirmOperatorNotification: vi.fn() }
})
vi.mock('./capabilities', async () => {
  const actual = await vi.importActual<typeof import('./capabilities')>('./capabilities')
  return { ...actual, listInstanceCapabilities: vi.fn() }
})

import OperatorPanel from './OperatorPanel.vue'
import { confirmOperatorNotification, getOperator, setOperator, type InstanceOperator } from './operator'
import { listInstanceCapabilities, type InstanceCapability } from './capabilities'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Textarea: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

function operator(over: Partial<InstanceOperator> = {}): InstanceOperator {
  return {
    operatingMode: 'ORGANISATION', contactEmail: null, contactLanguages: [], statementEmail: null, notificationEmail: null,
    notificationConfirmation: 'NOT_SET', notificationConfirmedAt: null, instanceMailReady: true, providerName: null,
    providerAddress: null, providerEmail: null, providerRepresentative: null, supervisoryAuthority: null, ...over,
  }
}

const identified = { providerName: 'Example e. V.', providerAddress: 'Musterstraße 1', providerEmail: 'info@example.org' }

function publicShares(effective: boolean): InstanceCapability[] {
  const text = { en: 'x', de: 'x' }
  return [{
    id: 'core.public-shares', title: text, purpose: text, whenOff: text, off: !effective, effective,
    tenantSwitchable: true, requirements: [],
  }]
}

async function mountPanel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(OperatorPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('OperatorPanel (uc.admin.operator-details, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(listInstanceCapabilities).mockResolvedValue(publicShares(true))
  })

  it('organisation: warns while public links are on and no contact is set, and stops once one is', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator(identified))
    vi.mocked(setOperator).mockResolvedValue(operator({ ...identified, contactEmail: 'contact@example.org' }))
    const w = await mountPanel()
    expect(w.find('.operator-warn-contact').exists()).toBe(true)
    expect(w.find('.operator-warn-identification').exists()).toBe(false)
    await w.find('.operator-contact').setValue('contact@example.org')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(w.find('.operator-warn-contact').exists()).toBe(false)
  })

  it('organisation: no contact warning while public links are off — the identification warning stands on its own', async () => {
    vi.mocked(listInstanceCapabilities).mockResolvedValue(publicShares(false))
    vi.mocked(getOperator).mockResolvedValue(operator())
    const w = await mountPanel()
    expect(w.find('.operator-warn-contact').exists()).toBe(false)
    expect(w.find('.operator-warn-identification').exists()).toBe(true)
  })

  it('organisation: the identification warning goes once name, address and email are set, whatever the contact', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator({ ...identified, providerEmail: null }))
    vi.mocked(setOperator).mockResolvedValue(operator(identified))
    const w = await mountPanel()
    expect(w.find('.operator-warn-identification').exists()).toBe(true)
    await w.find('.operator-provider-email').setValue('info@example.org')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(w.find('.operator-warn-identification').exists()).toBe(false)
    expect(w.find('.operator-warn-contact').exists()).toBe(true)
  })

  it('private: neither warning shows, every value empty and public links on', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator({ operatingMode: 'PRIVATE' }))
    const w = await mountPanel()
    expect(w.find('.operator-save').exists()).toBe(true)
    expect(w.find('.operator-warn-contact').exists()).toBe(false)
    expect(w.find('.operator-warn-identification').exists()).toBe(false)
  })

  it('a save sends every value, an empty field as null and the languages as tags', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator())
    vi.mocked(setOperator).mockResolvedValue(operator())
    const w = await mountPanel()
    await w.find('.operator-contact').setValue(' contact@example.org ')
    await w.find('.operator-languages').setValue('de, en')
    await w.find('.operator-provider-address').setValue('Musterstraße 1\n12345 Musterstadt')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(setOperator).toHaveBeenCalledWith({
      contactEmail: 'contact@example.org', contactLanguages: ['de', 'en'], statementEmail: null, notificationEmail: null,
      providerName: null, providerAddress: 'Musterstraße 1\n12345 Musterstadt', providerEmail: null,
      providerRepresentative: null, supervisoryAuthority: null,
    })
  })

  it('a code sent to the notification address is entered here and confirms it; a wrong one is refused in words', async () => {
    const sent = operator({ notificationEmail: 'alerts@example.org', notificationConfirmation: 'CODE_SENT' })
    vi.mocked(getOperator).mockResolvedValue(sent)
    vi.mocked(confirmOperatorNotification).mockRejectedValueOnce(httpError(409, 'operator_code_invalid'))
    const w = await mountPanel()
    expect(w.find('.operator-confirmation').attributes('data-state')).toBe('CODE_SENT')
    await w.find('.operator-code__input').setValue('11111111')
    await w.find('.operator-code').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain('not valid any more')

    vi.mocked(confirmOperatorNotification).mockResolvedValueOnce(
      operator({ notificationEmail: 'alerts@example.org', notificationConfirmation: 'CONFIRMED', notificationConfirmedAt: new Date() }),
    )
    await w.find('.operator-code__input').setValue('48213075')
    await w.find('.operator-code').trigger('submit')
    await flushPromises()
    expect(confirmOperatorNotification).toHaveBeenLastCalledWith('48213075')
    expect(w.find('.operator-confirmation').attributes('data-state')).toBe('CONFIRMED')
    expect(w.find('.operator-code').exists()).toBe(false)
  })

  it('says when instance mail is not ready, so no code could be mailed', async () => {
    vi.mocked(getOperator).mockResolvedValue(
      operator({ notificationEmail: 'alerts@example.org', notificationConfirmation: 'UNCONFIRMED', instanceMailReady: false }),
    )
    const w = await mountPanel()
    expect(w.find('.operator-confirmation').text()).toContain('Server → Mail')
  })
})
