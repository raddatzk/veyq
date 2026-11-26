// @vitest-environment happy-dom
// Making the new kit (uc.account.renew-emergency-kit steps 3-6, E1, E3; ADR 0157 decision 6): proven before printed — a
// wrong password or Secret Key renders no kit and writes no record; the key that passed is the one printed; the record,
// sealed under the Master Key the proof returned, is PUT only once the kit was saved and kept, never at render; a write
// that fails says the kit is valid.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import type { AccountKitRecordApi } from '../../../api/generated/account'

vi.mock('../../../api/client', () => ({ accountKitRecordApi: vi.fn() }))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../coldCredential', async (orig) => ({ ...(await orig<typeof import('../coldCredential')>()), proveColdCredential: vi.fn() }))

import KitRenewalDialog from './KitRenewalDialog.vue'
import { accountKitRecordApi } from '../../../api/client'
import { fakeApi } from '../../../test/apiMock'
import { ColdCredentialRefused, proveColdCredential } from '../coldCredential'
import { account } from '../../../app/account'
import { realI18n, t } from '../../../test/i18n'
import { TYPED_SECRET_KEY } from '../../../test/secretKey'
import { kitLayoutState, layoutOf, resetKitLayout } from './kitRecord'
import { KIT_LAYOUT } from './emergencyKit'

const MK = v1.randomBytes(32)
const field = (name: string) => ({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: `<input name="${name}" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />`,
})
const stubs = {
  VeyqDialog: { props: ['visible'], template: '<div v-if="visible" class="dialog"><slot /></div>' },
  VeyqButton: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqPassword: field('kit-password'),
  SecretKeyField: field('secret-key'),
  EmergencyKitSave: {
    props: ['email', 'secretKey'],
    emits: ['kept'],
    template: '<div class="kit-save" :data-email="email" :data-key="secretKey"><button class="kit-kept" @click="$emit(\'kept\')" /></div>',
  },
}

let api: ReturnType<typeof serve>
function serve() {
  return fakeApi(accountKitRecordApi, { putKitRecord: vi.fn<AccountKitRecordApi['putKitRecord']>().mockResolvedValue(undefined) })
}

async function proveWith(key = TYPED_SECRET_KEY) {
  const w = mount(KitRenewalDialog, { props: { visible: true }, global: { plugins: [realI18n()], stubs } })
  await w.find('input[name="kit-password"]').setValue('the password')
  await w.find('input[name="secret-key"]').setValue(key)
  await w.find('[data-testid="kit-renew-check"]').trigger('submit')
  await flushPromises()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
  api = serve()
  account.email = 'bea@example.org'
  resetKitLayout()
  kitLayoutState.layout = 1 // the account's last kit is of the older layout: what opened this dialog
})
afterEach(() => {
  account.email = ''
  resetKitLayout()
})

describe('KitRenewalDialog — proven before printed', () => {
  it('a wrong password or Secret Key renders no kit and writes no record (E1)', async () => {
    vi.mocked(proveColdCredential).mockRejectedValue(new ColdCredentialRefused('credentialRejected'))
    const w = await proveWith()
    expect(proveColdCredential).toHaveBeenCalledWith('the password', TYPED_SECRET_KEY)
    expect(w.find('[data-testid="kit-renew-error"]').text()).toBe(t('kit.renew.refused'))
    expect(w.find('.kit-save').exists()).toBe(false)
    expect(api.putKitRecord).not.toHaveBeenCalled()
    expect(kitLayoutState.layout).toBe(1)
  })

  it('a vault behind a secured account is "didn’t match" too, and a failed check says so without a kit', async () => {
    vi.mocked(proveColdCredential).mockRejectedValue(new ColdCredentialRefused('vaultStale'))
    expect((await proveWith()).find('[data-testid="kit-renew-error"]').text()).toBe(t('kit.renew.refused'))
    vi.mocked(proveColdCredential).mockRejectedValue(new Error('offline'))
    const w = await proveWith()
    expect(w.find('[data-testid="kit-renew-error"]').text()).toBe(t('kit.renew.failed'))
    expect(w.find('.kit-save').exists()).toBe(false)
  })

  it('the key that passed is printed for this account; the record is written once the kit is kept, not at render', async () => {
    let handed: Uint8Array | undefined
    vi.mocked(proveColdCredential).mockImplementation(async () => (handed = MK.slice()))
    const w = await proveWith(`  ${TYPED_SECRET_KEY}  `)
    expect(proveColdCredential).toHaveBeenCalledWith('the password', TYPED_SECRET_KEY)
    const kit = w.find('.kit-save')
    expect(kit.attributes('data-key')).toBe(TYPED_SECRET_KEY)
    expect(kit.attributes('data-email')).toBe('bea@example.org')
    expect(handed!.every((b) => b === 0), 'the proven Master Key is dropped once the record is sealed').toBe(true)
    expect(api.putKitRecord, 'the kit is on screen, not yet kept: nothing written').not.toHaveBeenCalled()

    await w.find('.kit-kept').trigger('click')
    await flushPromises()
    expect(api.putKitRecord).toHaveBeenCalledTimes(1)
    const { blob } = api.putKitRecord.mock.calls[0][0].kitRecordUpdate
    expect(layoutOf(MK, blob)).toBe(KIT_LAYOUT)
    expect(KIT_LAYOUT).toBeGreaterThan(1) // control: the answer above is the record's, not the no-record default
    expect(w.find('[data-testid="kit-renew-done"]').text()).toBe(t('kit.renew.done'))
    expect(kitLayoutState.layout, 'the block and the notice go at once').toBe(KIT_LAYOUT)
  })

  it('a record that cannot be written leaves a valid kit and says so (E3); the suggestion stays', async () => {
    vi.mocked(proveColdCredential).mockImplementation(async () => MK.slice())
    api.putKitRecord.mockRejectedValue(new Error('offline'))
    const w = await proveWith()
    await w.find('.kit-kept').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="kit-renew-not-noted"]').text()).toBe(t('kit.renew.notNoted'))
    expect(w.find('[data-testid="kit-renew-done"]').exists()).toBe(false)
    expect(kitLayoutState.layout).toBe(1)
  })

  it('a key changed after the check drops its kit: no kit is kept from an unchecked key', async () => {
    vi.mocked(proveColdCredential).mockImplementation(async () => MK.slice())
    const w = await proveWith()
    expect(w.find('.kit-save').exists()).toBe(true)
    await w.find('input[name="secret-key"]').setValue(`${TYPED_SECRET_KEY}X`)
    expect(w.find('.kit-save').exists()).toBe(false)
    expect(api.putKitRecord).not.toHaveBeenCalled()
  })

  it('closing drops every secret: it opens again on an empty form, with no kit', async () => {
    vi.mocked(proveColdCredential).mockImplementation(async () => MK.slice())
    const w = await proveWith()
    await w.setProps({ visible: false })
    await w.setProps({ visible: true })
    expect((w.find('input[name="kit-password"]').element as HTMLInputElement).value).toBe('')
    expect((w.find('input[name="secret-key"]').element as HTMLInputElement).value).toBe('')
    expect(w.find('.kit-save').exists()).toBe(false)
  })
})
