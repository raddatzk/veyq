// @vitest-environment happy-dom
// uc.account.sessions (web, cr-account-sessions-list-and-revoke) — the Sessions rubric of the settings hub.
// The behaviours pinned here are the use-case's, not rendering details:
//   - the list shows every active family with its dates, and marks exactly the one the server flagged (A1);
//   - a revoke of another family ends it and leaves the caller where they are (A2);
//   - "sign out everywhere else" calls the everywhere-else endpoint — never a fan-out of per-family revokes,
//     which would include the caller's own (A3);
//   - revoking your OWN family is treated as a logout and returns to the sign-in screen (E2);
//   - a LOCKED vault changes nothing: the list renders and every revoke stays enabled (E3/A4). That is the trap
//     — a view that disables the action it exists to offer — so it is asserted against an unlocked control;
//   - names come from the sealed device register and are layered on AFTER the list, never awaited by it: a
//     register that is empty, unreadable or unwritable leaves the list rendering and every revoke working (E4).
//
// The register is mocked to a NAMELESS default on purpose. That is the §E4 state, and it is the state the four
// original behaviours above were written against — so they keep asserting exactly what they always did, and the
// naming cases below opt in explicitly rather than silently changing everyone else's fixture.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./sessions', () => ({
  listSessionFamilies: vi.fn(),
  revokeSessionFamily: vi.fn(),
  revokeOtherSessionFamilies: vi.fn(),
}))
vi.mock('../login/signOut', () => ({ signOut: vi.fn() }))
vi.mock('../deviceRegister', () => ({
  deviceNames: vi.fn(),
  reconcileWithSessions: vi.fn(),
  renameDeviceOfFamily: vi.fn(),
}))
vi.mock('../deviceIdentity', () => ({
  deviceId: () => 'this-device',
  guessDeviceLabel: () => 'Firefox · macOS',
}))

import SessionsPanel from './SessionsPanel.vue'
import { listSessionFamilies, revokeSessionFamily, revokeOtherSessionFamilies } from './sessions'
import { signOut } from '../login/signOut'
import { deviceNames, reconcileWithSessions, renameDeviceOfFamily } from '../deviceRegister'
import { vaultState } from '../../../app/vault'
import { i18n } from '../../../i18n'

const CURRENT = { id: 'aaaaaaaa-1111-4111-8111-111111111111', createdAt: new Date('2026-08-01T09:12:00Z'), lastUsedAt: new Date('2026-08-12T07:45:00Z'), current: true }
const OTHER = { id: 'bbbbbbbb-2222-4222-8222-222222222222', createdAt: new Date('2026-07-03T18:30:00Z'), lastUsedAt: new Date('2026-08-10T21:02:00Z'), current: false }

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'ariaLabel', 'title'],
    // `emits` is load-bearing: without it Vue also passes the parent's @click through as a native fallthrough
    // listener, so one trigger fires the handler twice and every call-count assertion below reads double.
    emits: ['click'],
    template: '<button :disabled="loading" :aria-label="ariaLabel" :title="title" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

async function mountPanel(): Promise<VueWrapper> {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const wrapper = mount(SessionsPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return wrapper
}

/** The per-row revoke buttons, in row order. */
const rowButtons = (w: VueWrapper) => w.findAll('.session-row button')

describe('SessionsPanel (uc.account.sessions, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vaultState.unlocked = true
    vi.mocked(listSessionFamilies).mockResolvedValue([CURRENT, OTHER])
    vi.mocked(revokeSessionFamily).mockResolvedValue(undefined)
    vi.mocked(revokeOtherSessionFamilies).mockResolvedValue(undefined)
    vi.mocked(deviceNames).mockResolvedValue({})
    vi.mocked(reconcileWithSessions).mockResolvedValue(false)
    vi.mocked(renameDeviceOfFamily).mockResolvedValue(undefined)
    window.confirm = vi.fn(() => true)
  })

  it('lists every active family with both dates and marks exactly the one the server flagged', async () => {
    const wrapper = await mountPanel()
    const rows = wrapper.findAll('.session-row')
    expect(rows).toHaveLength(2)

    // Exactly one "you are here" badge, and it sits on the family the SERVER marked — not one the client guessed
    // from a locally-remembered id. Without this the sign-out-everywhere-else button below is unanchored.
    const badges = wrapper.findAll('.veyq-badge.success')
    expect(badges).toHaveLength(1)
    expect(rows[0].text()).toContain(i18n.global.t('sessions.thisDevice'))
    expect(rows[1].text()).toContain(i18n.global.t('sessions.otherDevice'))

    // The dates are the whole content of an unnamed row, so their absence is a broken screen, not a cosmetic gap.
    expect(rows[0].text()).toContain('Aug')
    expect(rows[1].text()).toContain('Jul')
    // The id is the only handle telling two same-day logins apart; the head is shown, the whole one is carried.
    expect(rows[1].find('.session-id').text()).toBe('bbbbbbbb')
    expect(rows[1].find('.session-id').attributes('title')).toBe(OTHER.id)
  })

  it('ends another session by its id and leaves the caller signed in here', async () => {
    const wrapper = await mountPanel()
    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()

    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
    // The control that makes the line above mean something: revoking another session is not signing yourself out.
    expect(signOut).not.toHaveBeenCalled()
    expect(revokeOtherSessionFamilies).not.toHaveBeenCalled()
    expect(listSessionFamilies).toHaveBeenCalledTimes(2) // and the list is re-read, so a stale row cannot linger
  })

  it('treats revoking your own family as a logout and returns to the sign-in screen', async () => {
    const wrapper = await mountPanel()
    await rowButtons(wrapper)[0].trigger('click')
    await flushPromises()

    expect(revokeSessionFamily).toHaveBeenCalledWith(CURRENT.id)
    // E2: continuing on this screen with dead tokens is the failure mode; the full sign-out is the behaviour.
    expect(signOut).toHaveBeenCalledTimes(1)
  })

  it('signs out everywhere else through the one endpoint that spares the caller', async () => {
    const wrapper = await mountPanel()
    const others = wrapper.findAll('button').find((b) => b.text() === i18n.global.t('sessions.revokeOthers'))
    await others!.trigger('click')
    await flushPromises()

    expect(revokeOtherSessionFamilies).toHaveBeenCalledTimes(1)
    // The trap: a client-side loop over the list would include the current family and sign the caller out too.
    // Only the server knows which family the request arrived on, so the exclusion must never be built here.
    expect(revokeSessionFamily).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })

  it('offers no sign-out-everywhere-else when this is the only session', async () => {
    vi.mocked(listSessionFamilies).mockResolvedValue([CURRENT])
    const wrapper = await mountPanel()
    expect(wrapper.findAll('button').some((b) => b.text() === i18n.global.t('sessions.revokeOthers'))).toBe(false)
    // The live control on the same predicate: with a second session it IS offered, so the assertion above is
    // about "nothing else to end" and not about a button that never renders.
    vi.mocked(listSessionFamilies).mockResolvedValue([CURRENT, OTHER])
    const withOthers = await mountPanel()
    expect(withOthers.findAll('button').some((b) => b.text() === i18n.global.t('sessions.revokeOthers'))).toBe(true)
  })

  it('renders and revokes with the vault LOCKED — the security action never waits on the cosmetic half', async () => {
    // E3/A4. A session is auth, not unlock: the device that needs to cut off a lost one is exactly the locked
    // one. Names are the only vault-dependent half and are a later slice, so nothing here may gate on the vault.
    vaultState.unlocked = false
    const wrapper = await mountPanel()

    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(rowButtons(wrapper).every((b) => b.attributes('disabled') === undefined)).toBe(true)

    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
  })

  it('says the load failed instead of rendering an empty, reassuring list', async () => {
    // An account whose sessions could not be read must not look like an account with no other sessions — that
    // reads as "nothing to cut off" on the one screen that exists to find a device.
    vi.mocked(listSessionFamilies).mockRejectedValue(new Error('boom'))
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain(i18n.global.t('sessions.loadFailed'))
    expect(wrapper.findAll('.session-row')).toHaveLength(0)
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.empty'))
  })

  it('surfaces a failed revoke instead of silently pretending the session ended', async () => {
    vi.mocked(revokeSessionFamily).mockRejectedValue(new Error('boom'))
    const wrapper = await mountPanel()
    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(i18n.global.t('sessions.revokeFailed'))
  })

  it('does nothing when the confirmation is declined', async () => {
    window.confirm = vi.fn(() => false)
    const wrapper = await mountPanel()
    await rowButtons(wrapper)[0].trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })

  // --- names from the sealed device register (en.account.device-register) ---

  it('labels a row the register can name, and leaves the rest on the anonymous fallback (E4)', async () => {
    vi.mocked(deviceNames).mockResolvedValue({ [OTHER.id]: 'Arbeitslaptop' })
    const wrapper = await mountPanel()
    expect(wrapper.text()).toContain('Arbeitslaptop')
    // The control: the family the register does NOT know still renders, under its fallback heading. Without it,
    // a panel that dropped unnamed rows entirely would satisfy the assertion above perfectly.
    expect(wrapper.text()).toContain(i18n.global.t('sessions.thisDevice'))
    expect(rowButtons(wrapper).length).toBeGreaterThanOrEqual(2)
  })

  it('reconciles the register with the list it is holding, passing the server-set current family', async () => {
    await mountPanel()
    expect(reconcileWithSessions).toHaveBeenCalledWith(
      [CURRENT.id, OTHER.id],
      'this-device',
      'Firefox · macOS',
      CURRENT.id, // from the SERVER's marker — not guessed, and the path a cold boot restore depends on
    )
  })

  it('never touches the register while locked — it is sealed, so there is nothing to read or write', async () => {
    vaultState.unlocked = false
    const wrapper = await mountPanel()
    expect(reconcileWithSessions).not.toHaveBeenCalled()
    expect(deviceNames).not.toHaveBeenCalled()
    // …and the list is unharmed by that: still rendered, still revocable. This is the whole locked contract.
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(rowButtons(wrapper).every((b) => !b.attributes('disabled'))).toBe(true)
  })

  it('renders the list and keeps revoking when the register throws (E4)', async () => {
    vi.mocked(reconcileWithSessions).mockRejectedValue(new Error('register unreachable'))
    vi.mocked(deviceNames).mockRejectedValue(new Error('register unreachable'))
    const wrapper = await mountPanel()
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.loadFailed'))
    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
  })

  it('renames the DEVICE behind a row, and only offers it where there is a device to rename', async () => {
    vi.mocked(deviceNames).mockResolvedValue({ [OTHER.id]: 'Arbeitslaptop' })
    const wrapper = await mountPanel()
    const rows = wrapper.findAll('.session-row')
    // The named row carries rename + revoke; the unnamed one carries revoke alone — a missing name never costs
    // the row its revoke button.
    expect(rows[0].findAll('button')).toHaveLength(1)
    expect(rows[1].findAll('button')).toHaveLength(2)

    await rows[1].findAll('button')[0].trigger('click')
    await flushPromises()
    const input = rows[1].find('input')
    expect((input.element as HTMLInputElement).value).toBe('Arbeitslaptop')
    await input.setValue('Kevins Laptop')
    vi.mocked(deviceNames).mockResolvedValue({ [OTHER.id]: 'Kevins Laptop' })
    await rows[1].findAll('button')[0].trigger('click') // Save
    await flushPromises()
    expect(renameDeviceOfFamily).toHaveBeenCalledWith(OTHER.id, 'Kevins Laptop')
    expect(wrapper.text()).toContain('Kevins Laptop')
  })

  it('does not write an empty rename', async () => {
    vi.mocked(deviceNames).mockResolvedValue({ [OTHER.id]: 'Arbeitslaptop' })
    const wrapper = await mountPanel()
    const row = wrapper.findAll('.session-row')[1]
    await row.findAll('button')[0].trigger('click')
    await flushPromises()
    await row.find('input').setValue('   ')
    await row.findAll('button')[0].trigger('click')
    await flushPromises()
    expect(renameDeviceOfFamily).not.toHaveBeenCalled()
  })
})
