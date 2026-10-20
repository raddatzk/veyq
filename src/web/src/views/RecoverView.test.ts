// @vitest-environment happy-dom
// uc.account.recover-access (web) — the view's own behaviour. The crypto is proven in
// features/account/recovery/recovery.test.ts and the whole flow in the live e2e; what only this file can pin is
// what the SCREEN does: which step it shows, that a wrong Secret Key keeps the person on the step instead of
// sending them back to the start, and that the token never lingers in the address bar.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../features/account/recovery/recovery', () => ({
  requestRecovery: vi.fn(),
  releaseRecoveryMaterial: vi.fn(),
  openWithSecretKey: vi.fn(),
  completeRecovery: vi.fn(),
}))
vi.mock('../shared/pwned', () => ({ pwnedCount: vi.fn() }))
const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

import RecoverView from './RecoverView.vue'
import { KdfProfileRefused } from '@veyq/crypto/v1'
import {
  requestRecovery,
  releaseRecoveryMaterial,
  openWithSecretKey,
  completeRecovery,
} from '../features/account/recovery/recovery'
import { pwnedCount } from '../shared/pwned'
import { RecoveryMaterialSuiteIdEnum, type RecoveryMaterial } from '../api/generated/auth'
import { realI18n, t } from '../test/i18n'

const stubs = {
  Card: { template: '<div><slot name="title" /><slot name="content" /></div>' },
  Button: { props: ['label', 'loading'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Password: { props: ['modelValue'], template: '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  // The breach warning's "Use it anyway": a native checkbox, so a test ticks it with `setValue(true)`.
  Checkbox: {
    props: ['modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
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
  const w = mount(RecoverView, { global: { plugins: [realI18n()], stubs } })
  mounted.push(w)
  return w
}

/** Put a token in the fragment, the way the mailed link does. */
function withToken(token = 'tok-123') {
  window.history.replaceState(null, '', `/recover#${token}`)
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
    vi.mocked(completeRecovery).mockResolvedValue(undefined)
    // The breach check is automatic, so every step-3 submit passes through it: clean unless a case says otherwise.
    vi.mocked(pwnedCount).mockResolvedValue(0)
    window.history.replaceState(null, '', '/recover')
  })

  afterEach(() => {
    mounted.splice(0).forEach((w) => w.unmount())
  })

  it('with no token it asks for an address and says nothing about whether it exists', async () => {
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain(t('recovery.emailLabel'))
    expect(releaseRecoveryMaterial).not.toHaveBeenCalled()

    await w.find('input').setValue('someone@example.com')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(requestRecovery).toHaveBeenCalledWith('someone@example.com')
    // The same confirmation regardless — the screen must not undo the endpoint's non-enumerability.
    expect(w.text()).toContain(t('recovery.requestSent'))
  })

  it('says so when the server cannot send mail at all, instead of silently redrawing the form', async () => {
    // ADR 0055 §4: the reset is always offered, and an instance with no mail transport refuses it plainly.
    // Before this branch existed the 503 was an unhandled rejection — the form came back blank, which is
    // indistinguishable from never having pressed the button.
    vi.mocked(requestRecovery).mockRejectedValue(httpError(503))
    const w = mountView()
    await flushPromises()
    await submitAddress(w)

    expect(w.text()).toContain(t('recovery.mailUnavailable'))
    // And NOT the confirmation: a refused request must never read as a link on its way.
    expect(w.text()).not.toContain(t('recovery.requestSent'))
  })

  it('says so when the request fails for any other reason', async () => {
    // The control for the case above: the 503 branch would be satisfied just as well by a screen that prints
    // the mail message for every failure. This pins that an ordinary failure gets its own, and is not swallowed.
    vi.mocked(requestRecovery).mockRejectedValue(new Error('network'))
    const w = mountView()
    await flushPromises()
    await submitAddress(w)

    expect(w.text()).toContain(t('recovery.requestFailed'))
    expect(w.text()).not.toContain(t('recovery.mailUnavailable'))
    expect(w.text()).not.toContain(t('recovery.requestSent'))
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
    expect(w.text()).toContain(t('recovery.forAccount', { email: 'kevin@example.com' }))
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
    expect(w.text()).toContain(t('recovery.requestSent'))

    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    window.location.hash = 'tok-123'
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    // Two flushes: the listener starts an async chain nobody awaits, so the release resolves on one tick and
    // the re-render lands on the next.
    await flushPromises()
    await flushPromises()

    expect(releaseRecoveryMaterial).toHaveBeenCalledWith('tok-123')
    expect(w.text()).toContain(t('recovery.forAccount', { email: 'kevin@example.com' }))
    expect(w.text()).not.toContain(t('recovery.requestSent'))
  })

  it('a dead link explains that a completed reset is the likely reason', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockRejectedValue(new Error('404'))
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain(t('recovery.linkSpent'))
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

    expect(w.text()).toContain(t('recovery.keyFailed'))
    expect(w.text()).toContain(t('recovery.stepKey'))
    expect(completeRecovery).not.toHaveBeenCalled()
  })

  it('a served key-derivation profile the client refused does not blame the Secret Key', async () => {
    // ADR 0017 rule 5: the server sent a profile this client does not write — the Kit is fine. The test above is
    // this one's control: same step, same submit, a wrong key still says so.
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockRejectedValue(new KdfProfileRefused())
    const w = mountView()
    await flushPromises()

    await w.find('input').setValue('the-right-key')
    await w.find('form').trigger('submit')
    await flushPromises()

    expect(w.text()).toContain(t('common.unreachable'))
    expect(w.text()).not.toContain(t('recovery.keyFailed'))
    expect(completeRecovery).not.toHaveBeenCalled()
  })

  it('refuses to submit two different passwords, without calling the server', async () => {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32) })
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

    expect(w.text()).toContain(t('recovery.mismatch'))
    expect(completeRecovery).not.toHaveBeenCalled()
  })

  it('refuses a password under the floor, and says which floor — the server cannot enforce one', async () => {
    // Model B (ADR 0003): the password never leaves this device and the server stores no credential, so a
    // length rule can only ever live here. That makes the client check the whole control rather than a
    // convenience over a server-side one — which is why it is pinned, and pinned at the boundary.
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32) })
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

    expect(w.text()).toContain(t('password.minLength'))
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
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32) })
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
      expect.objectContaining({ masterKey: expect.any(Uint8Array) }),
      'the-secret-key',
      'a brand new password',
    )
    expect(w.text()).toContain(t('recovery.done'))
  })

  /** Mounts on step 3 — the new password — with the token's material opened. */
  async function onPasswordStep() {
    withToken()
    vi.mocked(releaseRecoveryMaterial).mockResolvedValue(MATERIAL)
    vi.mocked(openWithSecretKey).mockResolvedValue({ masterKey: new Uint8Array(32) })
    const w = mountView()
    await flushPromises()
    await w.find('input[name="secret-key"]').setValue('the-secret-key')
    await w.find('form').trigger('submit')
    await flushPromises()
    return w
  }

  it('checks the finished new password when its field is left — never while typing, once per password', async () => {
    const w = await onPasswordStep()
    expect(w.text()).toContain(t('password.pwnedNote'))
    const pw = w.findAll('input[type="password"]')
    await pw[0].setValue('a brand')
    await pw[0].setValue('a brand new password')
    await flushPromises()
    expect(pwnedCount).not.toHaveBeenCalled()

    await pw[0].trigger('blur')
    await pw[0].trigger('blur')
    await flushPromises()
    expect(pwnedCount).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledWith('a brand new password', 'https://api.pwnedpasswords.com/range/')
  })

  it('a hit blocks the submit until "Use it anyway" — pressing submit again is not the acceptance', async () => {
    // The slip the composable's own test cannot see: a dialog that forgets to await the guard.
    vi.mocked(pwnedCount).mockResolvedValue(3)
    const w = await onPasswordStep()

    const pw = w.findAll('input[type="password"]')
    await pw[0].setValue('a brand new password')
    await pw[1].setValue('a brand new password')
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(completeRecovery).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('password.pwnedWarning', { count: 3 }))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(completeRecovery).not.toHaveBeenCalled()

    await w.find('input[type="checkbox"]').setValue(true)
    await w.find('form').trigger('submit')
    await flushPromises()
    expect(completeRecovery).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })

  it('a check that could not run goes ahead, and says so in one line', async () => {
    vi.mocked(pwnedCount).mockResolvedValue(null)
    const w = await onPasswordStep()

    const pw = w.findAll('input[type="password"]')
    await pw[0].setValue('a brand new password')
    await pw[0].trigger('blur')
    await pw[1].setValue('a brand new password')
    await flushPromises()
    expect(w.text()).toContain(t('password.pwnedUnavailable'))

    await w.find('form').trigger('submit')
    await flushPromises()
    expect(completeRecovery).toHaveBeenCalledOnce()
    expect(pwnedCount).toHaveBeenCalledOnce()
  })
})
