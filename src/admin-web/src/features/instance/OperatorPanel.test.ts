// @vitest-environment happy-dom
// uc.admin.operator-details (admin-web) — Server → Operator. Each case is one observable:
//   - nothing warns, every value empty: each empty public field shows as not stated, beside what a Server Owner may owe
//     (ADR 0093 § 14: no operating mode, nothing required);
//   - a save sends every value, an empty field as null; a CODE_SENT answer offers the code entry, and the code confirms
//     (main flow 4); instance mail that is not ready is said (A2).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./operator', async () => {
  const actual = await vi.importActual<typeof import('./operator')>('./operator')
  return { ...actual, getOperator: vi.fn(), setOperator: vi.fn(), clearOperator: vi.fn(), confirmOperatorNotification: vi.fn() }
})

import OperatorPanel from './OperatorPanel.vue'
import { confirmOperatorNotification, getOperator, setOperator, type InstanceOperator } from './operator'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const selectStub = {
  props: ['modelValue', 'options'],
  emits: ['update:modelValue'],
  template:
    '<select class="select" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
}

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-severity="tone"><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqTextarea: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // Each option carries its value and shows its label, as the layer's select does, so a panel that sent the label
  // would send it here too.
  VeyqSelect: selectStub,
  // The country field, for the cases that only need a country set; the case about the country itself drives the real one.
  VeyqCombobox: selectStub,
  VeyqButton: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}

function operator(over: Partial<InstanceOperator> = {}): InstanceOperator {
  return {
    contactEmail: undefined, contactLanguages: [], statementEmail: undefined, notificationEmail: undefined,
    notificationConfirmation: 'NOT_SET', notificationConfirmedAt: undefined, instanceMailReady: true, providerName: undefined,
    providerAddress: undefined, providerEmail: undefined, providerRepresentative: undefined, supervisoryAuthority: undefined,
    recipients: [], recipientSuggestions: [], ...over,
  }
}


async function mountPanel({ realCombobox = false } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const { VeyqCombobox: _stubbed, ...withoutCombobox } = stubs
  // The real combobox portals its list to document.body, so it is mounted there and its options are read from it.
  const wrapper = realCombobox
    ? mount(OperatorPanel, { attachTo: document.body, global: { plugins: [router, i18n], stubs: withoutCombobox } })
    : mount(OperatorPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

describe('OperatorPanel (uc.admin.operator-details, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('warns about nothing: every empty public field shows as not stated, beside what a Server Owner may owe', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator())
    const w = await mountPanel()
    expect(w.find('.operator-save').exists()).toBe(true)
    expect(w.find('[class*="operator-warn"]').exists()).toBe(false)
    expect(w.find('.msg[data-severity="warn"]').exists()).toBe(false)
    for (const field of ['.operator-contact', '.operator-provider-name', '.operator-provider-address', '.operator-provider-email', '.operator-supervisory-authority', '.operator-dpo']) {
      expect(w.find(field).attributes('placeholder')).toBe('Not stated')
    }
    expect(w.find('.operator-no-recipient').text()).toContain('not stated by this operator')
    const duties = w.find('[data-test="operator-duties"]')
    expect(duties.text()).toContain('household exemption')
    expect(duties.text()).toContain('your own assessment, not veyq’s')
    expect(w.find('input[type="checkbox"]').exists()).toBe(false) // information, not a choice: nothing to tick
  })

  it('a value set shows itself, and the recipients’ gap goes with the first row', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator({
      providerName: 'Example e. V.', recipients: [{ name: 'Example Hosting GmbH', kind: 'HOSTING', country: 'DE' }],
    }))
    const w = await mountPanel()
    expect((w.find('.operator-provider-name').element as HTMLInputElement).value).toBe('Example e. V.')
    expect(w.find('.operator-no-recipient').exists()).toBe(false)
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
      providerRepresentative: null, supervisoryAuthority: null, recipients: [], dataProtectionOfficer: null,
    })
  })

  it('a suggested recipient becomes a row only when added, and is saved only as that row; the officer is saved', async () => {
    const suggested = operator({ recipientSuggestions: [{ kind: 'MAIL_RELAY', host: 'smtp.example.org' }] })
    vi.mocked(getOperator).mockResolvedValue(suggested)
    vi.mocked(setOperator).mockResolvedValue(suggested)
    const w = await mountPanel()
    expect(w.findAll('[data-testid="operator-recipient"]')).toHaveLength(0)
    expect(w.find('.operator-suggestion').attributes('data-kind')).toBe('MAIL_RELAY')

    // Saved without adopting it: nothing is published unconfirmed.
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0].recipients).toEqual([])

    await w.find('.operator-suggestion__add').trigger('click')
    expect(w.find('.operator-suggestion').exists()).toBe(false) // a row names its host now
    const row = w.find('[data-testid="operator-recipient"]')
    expect((row.find('.operator-recipient__name').element as HTMLInputElement).value).toBe('smtp.example.org')
    expect((row.find('.operator-recipient__kind').element as HTMLSelectElement).value).toBe('MAIL_RELAY') // the suggestion's kind
    await row.find('.operator-recipient__name').setValue('Mail AG')
    await row.find('.operator-recipient__country').setValue('DE')
    await w.find('.operator-add-recipient').trigger('click') // an empty row is not sent
    await w.find('.operator-dpo').setValue(' DSB GmbH ')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0]).toMatchObject({
      recipients: [{ name: 'Mail AG', kind: 'MAIL_RELAY', description: null, country: 'DE' }],
      dataProtectionOfficer: 'DSB GmbH',
    })
  })

  it('a country is picked by its name in the console’s language and sent as its code, never as the name', async () => {
    i18n.global.locale.value = 'de'
    vi.mocked(getOperator).mockResolvedValue(operator())
    vi.mocked(setOperator).mockImplementation(async (u) => operator({ recipients: u.recipients ?? [] }))
    const w = await mountPanel({ realCombobox: true })
    await w.find('.operator-add-recipient').trigger('click')
    const row = w.find('[data-testid="operator-recipient"]')
    const country = row.find('.operator-recipient__country .veyq-combobox__input')
    const shown = () => [...document.querySelectorAll('.veyq-combobox__list .veyq-select__option')]
    // the whole list, opened from the field
    await row.find('.operator-recipient__country .veyq-combobox__trigger').trigger('click')
    await flushPromises()
    const names = shown().map((o) => o.textContent?.trim())
    expect(names).toContain('Deutschland')
    expect(names).not.toContain('Germany') // the German console names it in German
    // typing narrows it to the names that match
    ;(country.element as HTMLInputElement).value = 'deutsch'
    await country.trigger('input')
    await flushPromises()
    expect(shown().map((o) => o.textContent?.trim())).toEqual(['Deutschland'])
    await row.find('.operator-recipient__name').setValue('Example Hosting GmbH')
    await row.find('.operator-recipient__kind').setValue('HOSTING')
    // pick the option the reader sees as "Deutschland": whatever value it carries is what goes out
    ;(shown().find((o) => o.textContent?.trim() === 'Deutschland') as HTMLElement).click()
    await flushPromises()
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0].recipients)
      .toEqual([{ name: 'Example Hosting GmbH', kind: 'HOSTING', description: null, country: 'DE' }])
    // the saved code reads back as the same choice
    expect((row.find('.operator-recipient__country .veyq-combobox__input').element as HTMLInputElement).value).toBe('Deutschland')
    w.unmount()
  })

  it('what a recipient does is a kind from the list; only Other asks for words, and only Other sends them', async () => {
    vi.mocked(getOperator).mockResolvedValue(operator())
    // The server's answer is what was sent, so the panel keeps the rows it shows across a save.
    vi.mocked(setOperator).mockImplementation(async (u) => operator({ recipients: u.recipients ?? [] }))
    const w = await mountPanel()
    await w.find('.operator-add-recipient').trigger('click')
    const row = () => w.find('[data-testid="operator-recipient"]')
    expect(row().findAll('.operator-recipient__kind option').map((o) => o.text()))
      .toEqual(['Hosts the server', 'Stores the encrypted files', 'Relays the mail', 'Keeps the backups', 'Other'])
    await row().find('.operator-recipient__name').setValue('SMS AG')
    expect(row().find('.operator-recipient__description').exists()).toBe(false)
    await row().find('.operator-recipient__kind').setValue('OTHER')
    await row().find('.operator-recipient__description').setValue(' sends sign-in texts ')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0].recipients)
      .toEqual([{ name: 'SMS AG', kind: 'OTHER', description: 'sends sign-in texts', country: null }])

    // The kind changed: the field goes, and the words typed for Other are not sent with the new kind.
    await row().find('.operator-recipient__kind').setValue('BACKUP')
    expect(row().find('.operator-recipient__description').exists()).toBe(false)
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0].recipients)
      .toEqual([{ name: 'SMS AG', kind: 'BACKUP', description: null, country: null }])

    // A row with no kind chosen says nothing a reader could use and is not sent.
    await w.find('.operator-add-recipient').trigger('click')
    await w.findAll('[data-testid="operator-recipient"]')[1].find('.operator-recipient__name').setValue('No Kind AG')
    await w.find('.operator-save').trigger('click')
    await flushPromises()
    expect(vi.mocked(setOperator).mock.lastCall![0].recipients?.map((r) => r.name)).toEqual(['SMS AG'])
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
