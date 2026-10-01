// @vitest-environment happy-dom
// uc.account.confirm-email-address (web) — the page a confirmation link opens. Its three arrivals: with this
// device's session (confirms), with none (A4: confirms nothing and keeps nothing), and as another account (A5:
// the server's one generic refusal). Plus what it does with the token: read on mount and on `hashchange`, and
// stripped from the address bar at once.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/account/email/emailConfirmation', () => ({ confirmEmail: vi.fn() }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import ConfirmEmailView from './ConfirmEmailView.vue'
import { confirmEmail } from '../features/account/email/emailConfirmation'
import { session } from '../app/session'
import { realI18n, t } from '../test/i18n'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label'], emits: ['click'], template: '<button @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

// The view registers a window-level `hashchange` listener, so every mount is torn down after its test.
const mounted: ReturnType<typeof mount>[] = []

function mountView() {
  const w = mount(ConfirmEmailView, { global: { plugins: [realI18n()], stubs } })
  mounted.push(w)
  return w
}

function withToken(token = 'tok-123') {
  window.history.replaceState(null, '', `/confirm-email#${token}`)
}

describe('ConfirmEmailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(confirmEmail).mockResolvedValue(undefined)
    session.accessToken = 'access-token'
    window.history.replaceState(null, '', '/confirm-email')
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
    session.accessToken = null
  })

  it('with this device signed in, confirms with the token from the fragment', async () => {
    withToken()
    const w = mountView()
    await flushPromises()
    expect(confirmEmail).toHaveBeenCalledWith('tok-123')
    expect(w.text()).toContain(t('emailConfirm.done'))
    await w.findAll('button').find((b) => b.text() === t('emailConfirm.toSettings'))!.trigger('click')
    expect(push).toHaveBeenCalledWith('/settings/security')
  })

  it('with no session, sends nothing, keeps nothing, and asks to sign in here or open it where signed in (A4)', async () => {
    session.accessToken = null
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    withToken()
    const w = mountView()
    await flushPromises()
    expect(confirmEmail).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('emailConfirm.signInFirst'))
    expect(window.location.hash).toBe('') // gone from the address bar
    expect(setItem).not.toHaveBeenCalled() // and not parked anywhere for later
    setItem.mockRestore()
  })

  it('signed in as another account, renders the server’s one generic refusal (A5)', async () => {
    vi.mocked(confirmEmail).mockRejectedValue(httpError(404, 'email_confirmation_invalid'))
    withToken()
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain(t('emailConfirm.invalid'))
    expect(w.text()).not.toContain(t('emailConfirm.done'))
  })

  it('tells a network failure apart from a refused link', async () => {
    vi.mocked(confirmEmail).mockRejectedValue(new Error('offline'))
    withToken()
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain(t('emailConfirm.failed'))
    expect(w.text()).not.toContain(t('emailConfirm.invalid'))
  })

  it('strips the token from the address bar and keeps the router’s history state', async () => {
    window.history.replaceState({ position: 3 }, '', '/confirm-email#tok-123')
    mountView()
    await flushPromises()
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/confirm-email')
    expect(window.history.state).toEqual({ position: 3 })
  })

  it('with no token, explains the page and calls nothing', async () => {
    const w = mountView()
    await flushPromises()
    expect(confirmEmail).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('emailConfirm.noLink'))
  })

  it('picks up a link clicked in the same tab (only the fragment changes)', async () => {
    const w = mountView()
    await flushPromises()
    window.location.hash = 'tok-456'
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()
    expect(confirmEmail).toHaveBeenCalledWith('tok-456')
    expect(w.text()).toContain(t('emailConfirm.done'))
  })
})
