// @vitest-environment happy-dom
// uc.account.recover-access (web) — the view's own behaviour. The crypto is proven in
// features/account/recovery/recovery.test.ts and the whole flow in the live e2e; what only this file can pin is
// what the SCREEN does: which step it shows, that a wrong Secret Key keeps the person on the step instead of
// sending them back to the start, and that the token never lingers in the address bar.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../features/account/recovery/recovery', () => ({
  requestRecovery: vi.fn().mockResolvedValue(undefined),
  releaseRecoveryMaterial: vi.fn(),
  openWithSecretKey: vi.fn(),
  completeRecovery: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../features/account/emergencyKit/emergencyKit', () => ({ parseKit: vi.fn().mockReturnValue(null) }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import RecoverView from './RecoverView.vue'
import {
  requestRecovery,
  releaseRecoveryMaterial,
  openWithSecretKey,
  completeRecovery,
} from '../features/account/recovery/recovery'
import { RecoveryMaterialSuiteIdEnum, type RecoveryMaterial } from '../api/generated/auth'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: {
    en: {
      recovery: {
        title: 'Reset', intro: 'intro', emailLabel: 'Email', requestSubmit: 'Send', requestSubmitting: 'Sending',
        requestSent: 'If an account exists', stepKey: 'Secret Key', stepKeyIntro: 'k', forAccount: 'For {email}.',
        continueLabel: 'Continue', stepPassword: 'New password', newPassword: 'New', confirmPassword: 'Repeat',
        mismatch: 'Passwords differ', submit: 'Reset', submitting: 'Resetting', done: 'Done',
        linkSpent: 'This link no longer works', keyFailed: 'That Secret Key did not open your account',
        proofFailed: 'Refused', deviceNote: 'devices', noEscrow: 'no escrow',
        mailUnavailable: 'This server cannot send email yet', requestFailed: 'The request did not go through',
      },
      unlock: { secretKey: 'Secret Key', secretKeyHint: 'hint' },
      password: { minLength: 'Use at least 10 characters.', strong: 'Strong.', pwnedWarning: 'Seen in {count} breaches.' },
    },
  },
})

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label', 'loading'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Password: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
}

const MATERIAL: RecoveryMaterial = {
  email: 'kevin@example.com',
  suiteId: RecoveryMaterialSuiteIdEnum.V1,
  wrappedKey: 'd3JhcA==',
  kdfParams: 'a2Rm',
  wrappedIdentityPrivateKey: 'cHJpdg==',
}

// Every mount is tracked and torn down: the view registers a window-level `hashchange` listener, so a wrapper
// left mounted keeps reacting in later tests and the assertions start reading a stale instance's state.
const mounted: ReturnType<typeof mount>[] = []

function mountView() {
  const w = mount(RecoverView, { global: { plugins: [i18n], stubs } })
  mounted.push(w)
  return w
}

/** Put a token in the fragment, the way the mailed link does. */
function withToken(token = 'tok-123') {
  window.history.replaceState(null, '', `/recover#${token}`)
}

/** A thrown generated-client error carrying an HTTP response — the shape `httpError.ts` decodes. */
function httpError(status: number): Error {
  const e = new Error(`http ${status}`)
  ;(e as unknown as { response: Response }).response = new Response('', { status })
  return e
}

/** Fill in the address and press the button on step 1. */
async function submitAddress(w: ReturnType<typeof mount>) {
  await w.find('input').setValue('someone@example.com')
  await w.find('form').trigger('submit')
  await flushPromises()
}

describe('RecoverView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // `clearAllMocks` clears calls, not implementations — so a test that makes the request REJECT would otherwise
    // leak that into every later test. Restated per test rather than left to the module factory.
    vi.mocked(requestRecovery).mockResolvedValue(undefined)
    window.history.replaceState(null, '', '/recover')
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
  })

  it('with no token it asks for an address and says nothing about whether it exists', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('Email')
    expect(releaseRecoveryMaterial).not.toHaveBeenCalled()

    await w.find('input').setValue('someone@example.com')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(requestRecovery).toHaveBeenCalledWith('someone@example.com')
    // The same confirmation regardless — the screen must not undo the endpoint's non-enumerability.
    expect(w.text()).toContain('If an account exists')
  })

  it('says so when the server cannot send mail at all, instead of silently redrawing the form', async () => {
    // ADR 0055 §4: the reset is always offered, and an instance with no mail transport refuses it plainly.
    // Before this branch existed the 503 was an unhandled rejection — the form came back blank, which is
    // indistinguishable from never having pressed the button.
    vi.mocked(requestRecovery).mockRejectedValue(httpError(503))
    const w = mountView()
    await flushPromises()
    await submitAddress(w)

    expect(w.text()).toContain('This server cannot send email yet')
    // And NOT the confirmation: a refused request must never read as a link on its way.
    expect(w.text()).not.toContain('If an account exists')
  })

  it('says so when the request fails for any other reason', async () => {
    // The control for the case above: the 503 branch would be satisfied just as well by a screen that prints
    // the mail message for every failure. This pins that an ordinary failure gets its own, and is not swallowed.
    vi.mocked(requestRecovery).mockRejectedValue(new Error('network'))
    const w = mountView()
    await flushPromises()
    await submitAddress(w)

    expect(w.text()).toContain('The request did not go through')
    expect(w.text()).not.toContain('This server cannot send email yet')
    expect(w.text()).not.toContain('If an account exists')
  })

  it('strips the token from the address bar as soon as it is read, keeping the router\'s history state', async () => {
    // vue-router keeps its own position in history.state; a strip that replaces it with null is what the router
    // warns about, and what loses its back/scroll bookkeeping.
    const routerState = { back: '/', current: '/recover', forward: null, position: 3 }
    window.history.replaceState(routerState, '', '/recover#tok-123')
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    mountView()
    await flushPromises()
    // A token left in the URL rides along into any shared screenshot.
    expect(window.location.hash).toBe('')
    expect(window.history.state).toEqual(routerState)
  })

  it('with a token it releases and shows whose account the link is for — without asking again', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    const w = mountView()
    await flushPromises()

    expect(releaseRecoveryMaterial).toHaveBeenCalledWith('tok-123')
    expect(w.text()).toContain('For kevin@example.com.')
    // Exactly one field to fill on this step: the Secret Key. The address is known, so re-typing it is a step
    // that could only go wrong. The shared control's own hidden file picker is excluded by type — it is an
    // affordance of the field, not a second field, and it is what the person clicks the upload icon to reach.
    expect(w.findAll('input').filter((i) => i.attributes('type') !== 'file')).toHaveLength(1)
    expect(w.find('input[name="secret-key"]').exists()).toBe(true)
  })

  it('picks up the token when the link is opened in the SAME tab, after asking for it here', async () => {
    // The arrival that mounting cannot cover: vue-router sees only a fragment change and never re-mounts, so
    // without a hashchange listener this person keeps staring at "a link is on its way" while holding a link
    // that works. Found by looking at the screen, not by any of the tests above.
    const w = mountView()
    await flushPromises()
    await w.find('input').setValue('kevin@example.com')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(w.text()).toContain('If an account exists')

    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    window.location.hash = 'tok-123'
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    // Two flushes: the listener starts an async chain nobody awaits, so the release resolves on one tick and
    // the re-render lands on the next.
    await flushPromises()
    await flushPromises()

    expect(releaseRecoveryMaterial).toHaveBeenCalledWith('tok-123')
    expect(w.text()).toContain('For kevin@example.com.')
    expect(w.text()).not.toContain('If an account exists')
  })

  it('a dead link explains that a completed reset is the likely reason', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockRejectedValue(new Error('404'))
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('This link no longer works')
  })

  it('a wrong Secret Key keeps the person on the step — the token is still good', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockRejectedValue(new Error('aead'))
    const w = mountView()
    await flushPromises()

    await w.find('input').setValue('wrong-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.text()).toContain('That Secret Key did not open your account')
    expect(w.text()).toContain('Secret Key')
    expect(completeRecovery).not.toHaveBeenCalled()
  })

  it('refuses to submit two different passwords, without calling the server', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32), recoveryAuthTag: 'dGFn' })
    const w = mountView()
    await flushPromises()

    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    const pw = w.findAll('input[type="password"]')
    expect(pw).toHaveLength(2)
    await pw[0].setValue('one password')
    await pw[1].setValue('another password')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.text()).toContain('Passwords differ')
    expect(completeRecovery).not.toHaveBeenCalled()
  })

  it('refuses a password under the floor, and says which floor — the server cannot enforce one', async () => {
    // Model B (ADR 0003): the password never leaves this device and the server stores no credential, so a
    // length rule can only ever live here. That makes the client check the whole control rather than a
    // convenience over a server-side one — which is why it is pinned, and pinned at the boundary.
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32), recoveryAuthTag: 'dGFn' })
    const w = mountView()
    await flushPromises()

    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    const pw = w.findAll('input[type="password"]')
    await pw[0].setValue('short')     // 5 < 10
    await pw[1].setValue('short')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.text()).toContain('Use at least 10 characters.')
    expect(completeRecovery).not.toHaveBeenCalled()

    // Exactly at the floor is ACCEPTED — the boundary, not just "long enough". `<` vs `<=` here is the
    // difference between a policy and an off-by-one nobody would notice.
    await pw[0].setValue('0123456789')
    await pw[1].setValue('0123456789')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(completeRecovery).toHaveBeenCalledOnce()
  })

  it('completes with the released material, never with a re-typed address', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32), recoveryAuthTag: 'dGFn' })
    const w = mountView()
    await flushPromises()

    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    const pw = w.findAll('input[type="password"]')
    await pw[0].setValue('a brand new password')
    await pw[1].setValue('a brand new password')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(completeRecovery).toHaveBeenCalledWith(
      'tok-123',
      MATERIAL,
      expect.objectContaining({ recoveryAuthTag: 'dGFn' }),
      'the-secret-key',
      'a brand new password',
    )
    expect(w.text()).toContain('Done')
  })
})
