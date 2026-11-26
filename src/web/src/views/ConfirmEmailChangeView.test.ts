// @vitest-environment happy-dom
// uc.account.change-email (web) — the page the change mail's link opens. ConfirmEmailView's arrivals (this device's
// session, none, a refusal) with this flow's refusals, and what follows a change: the session and this device's
// remembered sign-in now name the new address, and a new Emergency Kit is offered with it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/account/email/emailChange', async (orig) => ({
  ...(await orig<typeof import('../features/account/email/emailChange')>()),
  confirmEmailChange: vi.fn(),
}))
vi.mock('../features/account/email/emailConfirmation', () => ({ getAccountEmail: vi.fn() }))
vi.mock('../features/account/rememberMe/deviceKeyWrap', () => ({ readdressRemembered: vi.fn() }))
vi.mock('../features/account/rememberMe/sessionVault', () => ({ readdressSessionVault: vi.fn() }))
vi.mock('../features/account/coldCredential', async (orig) => ({
  ...(await orig<typeof import('../features/account/coldCredential')>()),
  checkColdCredential: vi.fn(),
}))
vi.mock('../features/account/emergencyKit/kitRecord', async (orig) => ({
  ...(await orig<typeof import('../features/account/emergencyKit/kitRecord')>()),
  writeKitRecord: vi.fn(),
}))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import ConfirmEmailChangeView from './ConfirmEmailChangeView.vue'
import { confirmEmailChange } from '../features/account/email/emailChange'
import { getAccountEmail } from '../features/account/email/emailConfirmation'
import { readdressRemembered } from '../features/account/rememberMe/deviceKeyWrap'
import { readdressSessionVault } from '../features/account/rememberMe/sessionVault'
import { checkColdCredential, ColdCredentialRefused } from '../features/account/coldCredential'
import { account } from '../app/account'
import { session } from '../app/session'
import { realI18n, t } from '../test/i18n'
import { layoutOf, writeKitRecord } from '../features/account/emergencyKit/kitRecord'
import { KIT_LAYOUT } from '../features/account/emergencyKit/emergencyKit'
import * as v1 from '@veyq/crypto/v1'

const OLD = 'me@example.com'
const NEW = 'me.new@example.com'

const stubs = {
  VeyqCard: { template: '<div><slot name="title" /><slot /></div>' },
  VeyqButton: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqPassword: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="kit-password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  SecretKeyField: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  EmergencyKitSave: {
    props: ['email', 'secretKey'],
    emits: ['kept'],
    template: '<div class="kit-save" :data-email="email" :data-key="secretKey"><button class="kit-kept" @click="$emit(\'kept\')" /></div>',
  },
}

const mounted: ReturnType<typeof mount>[] = []

function mountView() {
  const w = mount(ConfirmEmailChangeView, { global: { plugins: [realI18n()], stubs } })
  mounted.push(w)
  return w
}

function withToken(token = 'tok-123') {
  window.history.replaceState(null, '', `/confirm-email-change#${token}`)
}

describe('ConfirmEmailChangeView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(confirmEmailChange).mockResolvedValue(undefined)
    vi.mocked(getAccountEmail).mockResolvedValue({ email: NEW, confirmed: true })
    vi.mocked(readdressRemembered).mockResolvedValue(undefined)
    session.accessToken = 'access-token'
    account.email = OLD
    window.history.replaceState(null, '', '/confirm-email-change')
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
    session.accessToken = null
    account.email = ''
  })

  it('with this device signed in, confirms with the token from the fragment and strips it', async () => {
    withToken()
    const w = mountView()
    await flushPromises()
    expect(confirmEmailChange).toHaveBeenCalledWith('tok-123')
    expect(window.location.hash).toBe('')
    expect(w.find('[data-testid="change-email-done"]').text()).toBe(t('emailChange.done'))
    expect(w.text()).toContain(t('emailChange.doneNote'))
  })

  it('names the account by the new address from now on — this session and this device’s remembered sign-in', async () => {
    withToken()
    mountView()
    await flushPromises()
    expect(account.email).toBe(NEW)
    expect(readdressSessionVault).toHaveBeenCalledWith(OLD, NEW)
    expect(readdressRemembered).toHaveBeenCalledWith(OLD, NEW)
  })

  it('learns the old address from the server when this tab holds none', async () => {
    account.email = ''
    vi.mocked(getAccountEmail).mockResolvedValueOnce({ email: OLD, confirmed: false }).mockResolvedValueOnce({ email: NEW, confirmed: true })
    withToken()
    mountView()
    await flushPromises()
    expect(readdressRemembered).toHaveBeenCalledWith(OLD, NEW)
  })

  /** Mount past a confirmed change and submit the kit check with [key]. */
  async function checkKitWith(key: string) {
    withToken()
    const w = mountView()
    await flushPromises()
    await w.find('input[name="kit-password"]').setValue('the password')
    await w.find('input[name="secret-key"]').setValue(key)
    await w.find('[data-testid="change-email-kit-check"]').trigger('submit')
    await flushPromises()
    return w
  }

  it('prints no kit from a Secret Key that does not pass the cold-credential check', async () => {
    vi.mocked(checkColdCredential).mockRejectedValue(new ColdCredentialRefused('credentialRejected'))
    const w = await checkKitWith('d3Jvbmc=')
    expect(checkColdCredential).toHaveBeenCalledWith('the password', 'd3Jvbmc=', expect.any(Function))
    expect(w.find('.kit-save').exists()).toBe(false)
    expect(w.find('[data-testid="change-email-kit-error"]').text()).toBe(t('emailChange.kitRefused'))

    // Typing a key is never enough on its own: no kit before a check passes.
    await w.find('input[name="secret-key"]').setValue('YW5vdGhlcg==')
    expect(w.find('.kit-save').exists()).toBe(false)
  })

  it('prints the kit with the NEW address and the checked key once the check passes — and drops it if the key changes', async () => {
    vi.mocked(checkColdCredential).mockResolvedValue(undefined)
    const w = await checkKitWith('  c2VjcmV0  ')
    expect(checkColdCredential).toHaveBeenCalledWith('the password', 'c2VjcmV0', expect.any(Function))
    const kit = w.find('.kit-save')
    expect(kit.attributes('data-email')).toBe(NEW)
    expect(kit.attributes('data-key')).toBe('c2VjcmV0')

    await w.find('input[name="secret-key"]').setValue('c2VjcmV0eA==')
    expect(w.find('.kit-save').exists(), 'an unchecked key replaces the checked one: no kit').toBe(false)
  })

  // ADR 0157 decision 4: the address change makes a kit, so it writes the account's kit record — sealed by the check with
  // the Master Key it opened, and PUT only once the kit was saved and kept, never at render.
  it('writes the kit record, sealed with the floor’s Master Key, once the kit was kept — not when it renders', async () => {
    const floorKey = v1.randomBytes(32)
    let handed: Uint8Array | undefined
    vi.mocked(checkColdCredential).mockImplementation(async (_pw, _key, use) => {
      handed = floorKey.slice()
      use?.(handed)
      handed.fill(0) // the helper zeroes what it handed over
    })
    vi.mocked(writeKitRecord).mockResolvedValue(undefined)
    const w = await checkKitWith('c2VjcmV0')
    expect(w.find('.kit-save').exists()).toBe(true)
    expect(writeKitRecord, 'the kit is on screen, not yet kept: nothing written').not.toHaveBeenCalled()

    await w.find('.kit-kept').trigger('click')
    await flushPromises()
    expect(writeKitRecord).toHaveBeenCalledTimes(1)
    expect(layoutOf(floorKey, vi.mocked(writeKitRecord).mock.calls[0][0])).toBe(KIT_LAYOUT)
    expect(KIT_LAYOUT).toBeGreaterThan(1) // control: the layout read above is the record's, not the no-record answer
  })

  it('writes no record when the check refused: there is no kit to keep', async () => {
    vi.mocked(checkColdCredential).mockRejectedValue(new ColdCredentialRefused('credentialRejected'))
    const refused = await checkKitWith('d3Jvbmc=')
    expect(refused.find('.kit-kept').exists()).toBe(false)
    expect(writeKitRecord).not.toHaveBeenCalled()
  })

  it('says a failed check (not a refusal) made no kit either', async () => {
    vi.mocked(checkColdCredential).mockRejectedValue(new Error('offline'))
    const w = await checkKitWith('c2VjcmV0')
    expect(w.find('.kit-save').exists()).toBe(false)
    expect(w.find('[data-testid="change-email-kit-error"]').text()).toBe(t('emailChange.kitFailed'))
  })

  it('with no session, sends nothing, keeps nothing, and asks to sign in', async () => {
    session.accessToken = null
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    withToken()
    const w = mountView()
    await flushPromises()
    expect(confirmEmailChange).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('emailConfirm.signInFirst'))
    expect(window.location.hash).toBe('')
    expect(setItem).not.toHaveBeenCalled()
    setItem.mockRestore()
  })

  it('renders each refusal by its own words, and a refused link changes nothing here', async () => {
    const cases: [Error, string][] = [
      [httpError(404, 'email_change_invalid'), t('emailChange.invalid')],
      [httpError(404, 'tenant_not_found'), t('emailConfirm.notMember')],
      [httpError(503, 'mail_unavailable'), t('emailChange.mailUnavailable')],
      [new Error('offline'), t('emailChange.failed')],
    ]
    for (const [failure, text] of cases) {
      vi.mocked(confirmEmailChange).mockRejectedValueOnce(failure)
      withToken()
      const w = mountView()
      await flushPromises()
      expect(w.text()).toContain(text)
      expect(w.find('[data-testid="change-email-done"]').exists()).toBe(false)
    }
    expect(account.email).toBe(OLD)
    expect(readdressRemembered).not.toHaveBeenCalled()
  })

  it('with no token, explains the page and calls nothing', async () => {
    const w = mountView()
    await flushPromises()
    expect(confirmEmailChange).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('emailChange.noLink'))
  })
})
