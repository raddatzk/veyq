// @vitest-environment happy-dom
// uc.account.sessions §E3 (web, cr-locked-sign-out-entry-point) — the locked state's own behaviour, as opposed
// to how the sign-in form reaches it (LoginView.test.ts owns that half).
//
// What only this file can pin is the ORDER and the failure split. The revoke is the half that stops whoever is
// holding the lost device, and it is the half that needs the session this screen is about to give up — so it
// runs first, and it is recorded as a sequence rather than as two independent call assertions, because two
// `toHaveBeenCalled` lines are satisfied just as well by the wrong order. A failed revoke must not cost the
// session needed to retry it. Signing this device out is the one sign-out sequence (uc.account.sign-out), whose
// own test owns what a failed sign-out reports.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./sessions', () => ({ revokeOtherSessionFamilies: vi.fn() }))
vi.mock('../login/signOut', () => ({ signOut: vi.fn() }))
// Confirmation is the app's own dialog (cr-web-own-confirm-dialog), mounted in App.vue and reached from the
// public sign-in card too: the component awaits `ask()`, so the answer is given here.
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import LockedSignOut from './LockedSignOut.vue'
import { revokeOtherSessionFamilies } from './sessions'
import { signOut } from '../login/signOut'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
  VeyqButton: {
    props: ['label', 'icon', 'variant', 'appearance', 'loading', 'disabled'],
    // `emits` is load-bearing: without it Vue passes the parent's @click through as a native fallthrough
    // listener too, so one trigger fires the handler twice and every call-count assertion reads double.
    emits: ['click'],
    template: '<button :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const mountLocked = () => mount(LockedSignOut, { global: { plugins: [realI18n()], stubs } })

const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

const pressAction = async (w: VueWrapper) => {
  await buttonLabelled(w, t('sessions.lockedAction'))!.trigger('click')
  await flushPromises()
}

describe('LockedSignOut (uc.account.sessions §E3, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(revokeOtherSessionFamilies).mockResolvedValue(undefined)
    vi.mocked(signOut).mockResolvedValue(undefined)
    confirmAsk.mockResolvedValue(true)
  })

  it('cuts the other sessions off FIRST and only then signs this device out, through the one sign-out', async () => {
    const order: string[] = []
    vi.mocked(revokeOtherSessionFamilies).mockImplementation(async () => void order.push('revoke'))
    vi.mocked(signOut).mockImplementation(async () => void order.push('sign-out'))

    const wrapper = mountLocked()
    await pressAction(wrapper)

    // The sequence IS the assertion: signing out ends the session the revoke needs, so the reverse order would
    // leave the lost device signed in while this one is the only thing that got cut off.
    expect(order).toEqual(['revoke', 'sign-out'])
    // `everywhere`: the sign-in page it lands on says every device was signed out, not just this one.
    expect(signOut).toHaveBeenCalledWith({ everywhere: true })
  })

  it('never signs this device out when the revoke failed — the session to retry with survives', async () => {
    vi.mocked(revokeOtherSessionFamilies).mockRejectedValue(new Error('offline'))
    const wrapper = mountLocked()
    await pressAction(wrapper)

    expect(signOut).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('sessions.revokeFailed'))
  })

  it('does nothing when the confirmation is declined', async () => {
    confirmAsk.mockResolvedValue(false)
    const wrapper = mountLocked()
    await pressAction(wrapper)

    expect(revokeOtherSessionFamilies).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })

  it('offers the way back to unlocking, and touches no session doing it', async () => {
    const wrapper = mountLocked()
    await buttonLabelled(wrapper, t('sessions.lockedBack'))!.trigger('click')
    await flushPromises()

    expect(wrapper.emitted('unlock')).toHaveLength(1)
    expect(revokeOtherSessionFamilies).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })

  it('says this device keeps nothing, because the toggle that says otherwise is on the form behind it', async () => {
    // Decision 3: the entry forces SESSION scope and enrols no wrap regardless of "stay signed in on this
    // device". That control is visible on the form this screen was reached from, so its copy must be corrected
    // here or it silently lies.
    const wrapper = mountLocked()
    expect(wrapper.text()).toContain(t('sessions.lockedScopeNote'))
  })
})
