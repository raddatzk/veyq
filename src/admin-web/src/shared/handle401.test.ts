// @vitest-environment happy-dom
// The end of a session the server no longer honours (inbox #449). A `401` that survives the one rotation
// (`app/sessionRefresh.ts`) means the session is over: the console must say so and land on the sign-in, in one
// step. It used to push `login` with the dead token still in memory, and the REAL router below — its guard
// included — sent `login` straight back to `console`: the owner saw nothing at all. So these cases mount the app's
// own router, not a stand-in, and the sign-in view as it renders.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import router from '../router'
import LoginView from '../views/LoginView.vue'
import { i18n } from '../i18n'
import { handle401 } from './handle401'
import { serverState } from './state/serverState'
import { serverRole } from '../app/serverRole'
import { clearTokens, session, setTokens } from '../app/session'
import { httpError } from '../test/httpError'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  Button: true,
  InputText: true,
  Password: true,
}

function mountLogin() {
  return mount(LoginView, { global: { plugins: [router, i18n], stubs } })
}

describe('a 401 the rotation could not cure (inbox #449)', () => {
  beforeEach(async () => {
    i18n.global.locale.value = 'en'
    serverState.setupCompleted = true
    setTokens('expired-access', 'dead-refresh', 'family-1')
    serverRole.verdict = 'owner'
    await router.push({ name: 'console' })
    expect(router.currentRoute.value.name).toBe('console')
  })

  it('lands on the sign-in — the router no longer bounces it back to the console', async () => {
    handle401(router)(httpError(401))
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(session.accessToken).toBeNull()
    expect(session.refreshToken).toBeNull()
    expect(serverRole.verdict).toBe('unknown') // the next sign-in re-probes instead of inheriting
  })

  it('and the sign-in says why, and that the action was not applied — once', async () => {
    handle401(router)(httpError(401))
    await flushPromises()

    const shown = mountLogin()
    expect(shown.find('.msg[data-severity="warn"]').text()).toBe(
      'Your session has ended. Sign in again — what you just did was not applied.',
    )
    shown.unmount()

    const again = mountLogin()
    expect(again.find('.msg[data-severity="warn"]').exists()).toBe(false)
  })

  it('leaves everything alone for any other failure', async () => {
    handle401(router)(httpError(403))
    handle401(router)(new Error('network'))
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('console')
    expect(session.accessToken).toBe('expired-access')
    clearTokens()
  })
})
