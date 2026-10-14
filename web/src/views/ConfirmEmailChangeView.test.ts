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
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import ConfirmEmailChangeView from './ConfirmEmailChangeView.vue'
import { confirmEmailChange } from '../features/account/email/emailChange'
import { getAccountEmail } from '../features/account/email/emailConfirmation'
import { readdressRemembered } from '../features/account/rememberMe/deviceKeyWrap'
import { readdressSessionVault } from '../features/account/rememberMe/sessionVault'
import { account } from '../app/account'
import { session } from '../app/session'
import { realI18n, t } from '../test/i18n'

const OLD = 'me@example.com'
const NEW = 'me.new@example.com'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  SecretKeyField: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input name="secret-key" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  EmergencyKitSave: { props: ['email', 'secretKey'], template: '<div class="kit-save" :data-email="email" :data-key="secretKey" />' },
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

  it('offers a new Emergency Kit with the NEW address once a Secret Key is given', async () => {
    withToken()
    const w = mountView()
    await flushPromises()
    expect(w.find('[data-testid="change-email-kit"]').text()).toContain(t('emailChange.kitTitle'))
    expect(w.find('.kit-save').exists()).toBe(false)
    await w.find('input[name="secret-key"]').setValue('  c2VjcmV0  ')
    const kit = w.find('.kit-save')
    expect(kit.attributes('data-email')).toBe(NEW)
    expect(kit.attributes('data-key')).toBe('c2VjcmV0')
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
