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
//     register that is empty, unreadable or unwritable leaves the list rendering and every revoke working (E4);
//   - the escalation (ADR 0058) cuts the family FIRST and the wrap second, is offered only where the register
//     names a served wrap for that device, and on a half-success says exactly what did and did not happen (E5);
//   - § Devices with a saved key is the retry surface for that half-success, and its bulk action is defined by
//     EXCLUDING this device's own saved key — never by a claim about the others (ADR 0058 §5);
//   - the current device's row also states how THIS device unlocks and offers the FORGET action
//     (uc.account.trust-device § Untrust), which is a different act from the escalation above and stays one.
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
  readRegisterView: vi.fn(),
  reconcileWithSessions: vi.fn(),
  renameDeviceOfFamily: vi.fn(),
}))
vi.mock('../deviceIdentity', () => ({
  deviceId: () => 'this-device',
  guessDeviceLabel: () => 'Firefox · macOS',
}))
vi.mock('../savedKeys', () => ({ listSavedKeyWraps: vi.fn(), removeSavedKey: vi.fn() }))
vi.mock('../localWrap', () => ({ localWrapId: vi.fn() }))
// The device-local half the current row now mounts (DeviceTrustPanel). Mocked to the NO-TIER default so every
// behaviour above keeps asserting exactly what it always did; the cases that care opt in explicitly.
vi.mock('../trustDevice/trustDevice', () => ({
  isTrusted: vi.fn(),
  untrustThisDevice: vi.fn(),
  isTrustSupported: vi.fn(),
}))
vi.mock('../rememberMe/deviceKeyWrap', () => ({ isRemembered: vi.fn(), clearDeviceKey: vi.fn() }))

import SessionsPanel from './SessionsPanel.vue'
import { listSessionFamilies, revokeSessionFamily, revokeOtherSessionFamilies } from './sessions'
import { signOut } from '../login/signOut'
import { readRegisterView, reconcileWithSessions, renameDeviceOfFamily } from '../deviceRegister'
import { listSavedKeyWraps, removeSavedKey } from '../savedKeys'
import { localWrapId } from '../localWrap'
import { isTrusted, untrustThisDevice, isTrustSupported } from '../trustDevice/trustDevice'
import { isRemembered, clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { vaultState } from '../../../app/vault'
import { account } from '../../../app/account'
import { i18n } from '../../../i18n'

const CURRENT = { id: 'aaaaaaaa-1111-4111-8111-111111111111', createdAt: new Date('2026-08-01T09:12:00Z'), lastUsedAt: new Date('2026-08-12T07:45:00Z'), current: true }
const OTHER = { id: 'bbbbbbbb-2222-4222-8222-222222222222', createdAt: new Date('2026-07-03T18:30:00Z'), lastUsedAt: new Date('2026-08-10T21:02:00Z'), current: false }

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  // The forget action confirms in a DIALOG where the session actions use window.confirm — the two acts do not
  // look alike, and this stub keeps that difference visible to the tests below.
  Dialog: { props: ['visible', 'header'], template: '<div class="dialog" v-if="visible">{{ header }}<slot /></div>' },
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

const EMPTY_VIEW = { names: {}, wrapsOfFamily: {}, savedKeyDevices: [] }

/** The escalation button of a row, found by its aria-label rather than by position. */
const escalateButton = (w: VueWrapper, row: number) =>
  w.findAll('.session-row')[row].findAll('button').find((b) => b.attributes('aria-label') === i18n.global.t('sessions.escalate'))

const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

/** A row's plain revoke, by aria-label — the row now carries more than one destructive action. */
const revokeButton = (w: VueWrapper, row: number) =>
  w.findAll('.session-row')[row]
    .findAll('button')
    .find((b) => b.attributes('aria-label') === i18n.global.t(row === 0 ? 'sessions.revokeSelf' : 'sessions.revoke'))

/** The FORGET action of the current device's row — labelled, not positional, so it cannot be confused with a revoke. */
const forgetButton = (w: VueWrapper) =>
  w.findAll('.session-row')[0].findAll('button').find((b) => b.text() === i18n.global.t('unlock.untrust'))

describe('SessionsPanel (uc.account.sessions, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vaultState.unlocked = true
    vi.mocked(listSessionFamilies).mockResolvedValue([CURRENT, OTHER])
    vi.mocked(revokeSessionFamily).mockResolvedValue(undefined)
    vi.mocked(revokeOtherSessionFamilies).mockResolvedValue(undefined)
    vi.mocked(readRegisterView).mockResolvedValue(EMPTY_VIEW)
    vi.mocked(reconcileWithSessions).mockResolvedValue(false)
    vi.mocked(renameDeviceOfFamily).mockResolvedValue(undefined)
    vi.mocked(listSavedKeyWraps).mockResolvedValue([])
    vi.mocked(removeSavedKey).mockResolvedValue(undefined)
    vi.mocked(localWrapId).mockResolvedValue(null)
    account.email = 'me@x.z'
    vi.mocked(isTrustSupported).mockReturnValue(true)
    vi.mocked(isTrusted).mockResolvedValue(false)
    vi.mocked(isRemembered).mockResolvedValue(false)
    vi.mocked(untrustThisDevice).mockResolvedValue(undefined)
    vi.mocked(clearDeviceKey).mockResolvedValue(undefined)
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
    vi.mocked(readRegisterView).mockResolvedValue({ ...EMPTY_VIEW, names: { [OTHER.id]: 'Arbeitslaptop' } })
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
      [], // the served wrap list — an ARRAY because the fetch succeeded; see the null case below
      'this-device',
      'Firefox · macOS',
      CURRENT.id, // from the SERVER's marker — not guessed, and the path a cold boot restore depends on
    )
  })

  it('never touches the register while locked — it is sealed, so there is nothing to read or write', async () => {
    vaultState.unlocked = false
    const wrapper = await mountPanel()
    expect(reconcileWithSessions).not.toHaveBeenCalled()
    expect(readRegisterView).not.toHaveBeenCalled()
    expect(listSavedKeyWraps).not.toHaveBeenCalled()
    // …and the list is unharmed by that: still rendered, still revocable. This is the whole locked contract.
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(rowButtons(wrapper).every((b) => !b.attributes('disabled'))).toBe(true)
  })

  it('renders the list and keeps revoking when the register throws (E4)', async () => {
    vi.mocked(reconcileWithSessions).mockRejectedValue(new Error('register unreachable'))
    vi.mocked(readRegisterView).mockRejectedValue(new Error('register unreachable'))
    const wrapper = await mountPanel()
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.loadFailed'))
    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
  })

  it('renames the DEVICE behind a row, and only offers it where there is a device to rename', async () => {
    vi.mocked(readRegisterView).mockResolvedValue({ ...EMPTY_VIEW, names: { [OTHER.id]: 'Arbeitslaptop' } })
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
    vi.mocked(readRegisterView).mockResolvedValue({ ...EMPTY_VIEW, names: { [OTHER.id]: 'Kevins Laptop' } })
    await rows[1].findAll('button')[0].trigger('click') // Save
    await flushPromises()
    expect(renameDeviceOfFamily).toHaveBeenCalledWith(OTHER.id, 'Kevins Laptop')
    expect(wrapper.text()).toContain('Kevins Laptop')
  })

  it('does not write an empty rename', async () => {
    vi.mocked(readRegisterView).mockResolvedValue({ ...EMPTY_VIEW, names: { [OTHER.id]: 'Arbeitslaptop' } })
    const wrapper = await mountPanel()
    const row = wrapper.findAll('.session-row')[1]
    await row.findAll('button')[0].trigger('click')
    await flushPromises()
    await row.find('input').setValue('   ')
    await row.findAll('button')[0].trigger('click')
    await flushPromises()
    expect(renameDeviceOfFamily).not.toHaveBeenCalled()
  })

  // --- the escalation: sign out AND remove that device's saved key (ADR 0058) ---

  /** A register that names OTHER's device and joins it to a served wrap `w-other`. */
  function withEscalatableOther() {
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-other', method: 'DEVICE' }])
    vi.mocked(readRegisterView).mockResolvedValue({
      names: { [OTHER.id]: 'Arbeitslaptop' },
      wrapsOfFamily: { [OTHER.id]: ['w-other'] },
      savedKeyDevices: [],
    })
  }

  /** A register that names THIS device and joins it to a served wrap — both row actions live at once. */
  function withEscalatableCurrent() {
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-mine', method: 'DEVICE' }])
    vi.mocked(readRegisterView).mockResolvedValue({
      names: { [CURRENT.id]: 'Dieser Rechner' },
      wrapsOfFamily: { [CURRENT.id]: ['w-mine'] },
      savedKeyDevices: [],
    })
  }

  it('cuts the session first and the wrap second — the urgent half is never blocked by the other', async () => {
    withEscalatableOther()
    const order: string[] = []
    vi.mocked(revokeSessionFamily).mockImplementation(async () => void order.push('family'))
    vi.mocked(removeSavedKey).mockImplementation(async () => void order.push('wrap'))

    const wrapper = await mountPanel()
    await escalateButton(wrapper, 1)!.trigger('click')
    await flushPromises()

    expect(order).toEqual(['family', 'wrap'])
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
    expect(removeSavedKey).toHaveBeenCalledWith('w-other')
  })

  it('never touches the wrap when the revoke failed — the reverse half-failure cannot occur', async () => {
    withEscalatableOther()
    vi.mocked(revokeSessionFamily).mockRejectedValue(new Error('offline'))
    const wrapper = await mountPanel()
    await escalateButton(wrapper, 1)!.trigger('click')
    await flushPromises()

    expect(removeSavedKey).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(i18n.global.t('sessions.revokeFailed'))
  })

  it('says exactly what did and did not happen when only the wrap deletion fails (E5)', async () => {
    withEscalatableOther()
    vi.mocked(removeSavedKey).mockRejectedValue(new Error('boom'))
    const wrapper = await mountPanel()
    await escalateButton(wrapper, 1)!.trigger('click')
    await flushPromises()

    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
    // The trap is a message that reads as either total success or total failure. The half-success has to name
    // both halves, because the user's next move (retry below) depends on knowing which one is outstanding.
    expect(wrapper.text()).toContain(i18n.global.t('sessions.escalateHalfFailed'))
  })

  it('offers the escalation only where the register names a served wrap for that device', async () => {
    withEscalatableOther()
    const wrapper = await mountPanel()
    // The row the register can escalate has it…
    expect(escalateButton(wrapper, 1)).toBeDefined()
    // …and the row it cannot does not — while keeping its plain revoke. A missing join is a naming gap (§E4),
    // never a reason to hide the action this screen exists for.
    expect(escalateButton(wrapper, 0)).toBeUndefined()
    expect(rowButtons(wrapper).length).toBeGreaterThanOrEqual(2)
  })

  it('does nothing when the escalation confirmation is declined', async () => {
    withEscalatableOther()
    window.confirm = vi.fn(() => false)
    const wrapper = await mountPanel()
    await escalateButton(wrapper, 1)!.trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).not.toHaveBeenCalled()
    expect(removeSavedKey).not.toHaveBeenCalled()
  })

  // --- § Devices with a saved key, and the exclusion-shaped bulk action (ADR 0058 §5) ---

  it('lists a signed-out device that still holds a saved key, and removes it from there', async () => {
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-lost', method: 'DEVICE_KEY' }])
    vi.mocked(readRegisterView).mockResolvedValue({
      ...EMPTY_VIEW,
      savedKeyDevices: [{ deviceId: 'lost', label: 'Lost laptop', wrapIds: ['w-lost'] }],
    })
    const wrapper = await mountPanel()

    const rows = wrapper.findAll('.saved-key-row')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('Lost laptop')

    await rows[0].find('button').trigger('click')
    await flushPromises()
    expect(removeSavedKey).toHaveBeenCalledWith('w-lost')
  })

  it('removes every saved key EXCEPT this device\'s own — the caller never breaks itself', async () => {
    vi.mocked(localWrapId).mockResolvedValue('w-mine')
    vi.mocked(listSavedKeyWraps).mockResolvedValue([
      { id: 'w-mine', method: 'DEVICE' },
      { id: 'w-a', method: 'DEVICE' },
      { id: 'w-b', method: 'DEVICE_KEY' },
    ])
    const wrapper = await mountPanel()
    await buttonLabelled(wrapper, i18n.global.t('sessions.removeOtherKeys'))!.trigger('click')
    await flushPromises()

    expect(removeSavedKey).toHaveBeenCalledWith('w-a')
    expect(removeSavedKey).toHaveBeenCalledWith('w-b')
    // The whole point of the exclusion shape: this device keeps its own saved key.
    expect(removeSavedKey).not.toHaveBeenCalledWith('w-mine')
    expect(removeSavedKey).toHaveBeenCalledTimes(2)
  })

  it('a caller holding no saved key removes all of them, and is unharmed by that', async () => {
    vi.mocked(localWrapId).mockResolvedValue(null)
    vi.mocked(listSavedKeyWraps).mockResolvedValue([
      { id: 'w-a', method: 'DEVICE' },
      { id: 'w-b', method: 'DEVICE_KEY' },
    ])
    const wrapper = await mountPanel()
    await buttonLabelled(wrapper, i18n.global.t('sessions.removeOtherKeys'))!.trigger('click')
    await flushPromises()

    // Excluding nothing is correct — a device with no saved key has none to spare — and it is not a self-inflicted
    // wound: this device unlocks from the floor either way, and its session is untouched.
    expect(removeSavedKey).toHaveBeenCalledTimes(2)
    expect(signOut).not.toHaveBeenCalled()
    expect(revokeSessionFamily).not.toHaveBeenCalled()
  })

  it('hides the whole section when the only saved key is this device\'s own', async () => {
    vi.mocked(localWrapId).mockResolvedValue('w-mine')
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-mine', method: 'DEVICE' }])
    const wrapper = await mountPanel()
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.savedKeysTitle'))
    // The live control on the same predicate: one foreign saved key and the section IS there.
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-mine', method: 'DEVICE' }, { id: 'w-a', method: 'DEVICE' }])
    const withForeign = await mountPanel()
    expect(withForeign.text()).toContain(i18n.global.t('sessions.savedKeysTitle'))
  })

  it('a locked panel offers neither wrap action, and still lists and revokes', async () => {
    // E3: both joins live in the Master-Key-sealed register, so a locked caller never gets them. The locked
    // ceiling does not rise with this increment — the escalation is absent there by construction.
    vaultState.unlocked = false
    vi.mocked(listSavedKeyWraps).mockResolvedValue([{ id: 'w-a', method: 'DEVICE' }])
    const wrapper = await mountPanel()

    expect(escalateButton(wrapper, 1)).toBeUndefined()
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.savedKeysTitle'))
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    await rowButtons(wrapper)[1].trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
  })

  it('offers nothing wrap-shaped when the served list could not be fetched — and says so by passing null', async () => {
    // `null` is "we do not hold that list", which is NOT "the server serves none". Passing [] here would prune
    // every join row in the register on a transport error.
    vi.mocked(listSavedKeyWraps).mockRejectedValue(new Error('offline'))
    const wrapper = await mountPanel()

    expect(reconcileWithSessions).toHaveBeenCalledWith(
      [CURRENT.id, OTHER.id],
      null,
      'this-device',
      'Firefox · macOS',
      CURRENT.id,
    )
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.savedKeysTitle'))
    // …and the naming half is untouched by it: the list still renders and still revokes.
    expect(wrapper.findAll('.session-row')).toHaveLength(2)
  })
  // --- the merged rubric: how THIS device unlocks, stated in the row that is already about it ---

  it('states this device\'s unlock tier on its own row, and nowhere else', async () => {
    vi.mocked(isTrusted).mockResolvedValue(true)
    const wrapper = await mountPanel()
    const rows = wrapper.findAll('.session-row')
    expect(rows[0].text()).toContain(i18n.global.t('sessions.deviceTrusted'))
    // The control on the same predicate: the status is a fact about THIS device, so no other row may carry it.
    // Without it, a status rendered once per row would satisfy the line above perfectly.
    expect(rows[1].text()).not.toContain(i18n.global.t('sessions.deviceTrusted'))
  })

  it('shows the password-LESS tier when a device somehow holds both, and offers nothing on a plain one', async () => {
    // A device holds at most one convenience tier (login drops the other first). If both are somehow present the
    // stronger, password-less one is what the user actually experiences, so it is what the row must say.
    vi.mocked(isRemembered).mockResolvedValue(true)
    vi.mocked(isTrusted).mockResolvedValue(true)
    const both = await mountPanel()
    expect(both.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceRemembered'))
    expect(both.findAll('.session-row')[0].text()).not.toContain(i18n.global.t('sessions.deviceTrusted'))

    // …and a device on the PASSWORD floor says so and offers nothing to forget — there is nothing here to drop.
    vi.mocked(isRemembered).mockResolvedValue(false)
    vi.mocked(isTrusted).mockResolvedValue(false)
    const plain = await mountPanel()
    expect(plain.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceUntrusted'))
    expect(forgetButton(plain)).toBeUndefined()
  })

  it('forgets this device from its row: both tiers dropped, the session untouched', async () => {
    vi.mocked(isRemembered).mockResolvedValueOnce(true).mockResolvedValue(false) // before -> after
    const wrapper = await mountPanel()
    expect(wrapper.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceRemembered'))

    await forgetButton(wrapper)!.trigger('click') // open the dialog
    await flushPromises()
    await wrapper.find('.dialog').findAll('button').at(-1)!.trigger('click') // confirm
    await flushPromises()

    expect(untrustThisDevice).toHaveBeenCalledWith('me@x.z')
    expect(clearDeviceKey).toHaveBeenCalled()
    expect(wrapper.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceUntrusted'))
    // The line that keeps forgetting from becoming the escalation: forgetting is not a sign-out (ADR 0058).
    expect(revokeSessionFamily).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })

  // BITE 1 — the forget action is offered only where a convenience tier is POSITIVELY known to be here.
  it('offers no forget action when the trust read FAILS — the best-effort default is none', async () => {
    vi.mocked(isRemembered).mockRejectedValue(new Error('idb gone'))
    vi.mocked(isTrusted).mockRejectedValue(new Error('idb gone'))
    const wrapper = await mountPanel()

    expect(wrapper.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceUntrusted'))
    expect(forgetButton(wrapper)).toBeUndefined()
    // The live control on the same predicate: a read that SUCCEEDS with a tier does offer it, so the line above
    // is about the catch path and not about a button that never renders from this mount point at all.
    vi.mocked(isTrusted).mockResolvedValue(true)
    vi.mocked(isRemembered).mockResolvedValue(false)
    expect(forgetButton(await mountPanel())).toBeDefined()
  })

  it('offers no forget action where the browser cannot remember a device', async () => {
    vi.mocked(isTrustSupported).mockReturnValue(false)
    vi.mocked(isTrusted).mockResolvedValue(true)
    const wrapper = await mountPanel()
    expect(wrapper.findAll('.session-row')[0].text()).toContain(i18n.global.t('sessions.deviceUnsupported'))
    expect(forgetButton(wrapper)).toBeUndefined()
  })

  // BITE 2 — the defect the MERGE makes possible: the two panels could not block each other while they were
  // separate. The device-local status read is device-local and unbounded (IndexedDB), so the list must resolve
  // it BESIDE its own load, never inside it. Red against a loader that awaits the trust read.
  it('renders and revokes while the device-trust read HANGS — the list never waits on it', async () => {
    vi.mocked(isRemembered).mockReturnValue(new Promise<boolean>(() => {})) // never settles
    vi.mocked(isTrusted).mockReturnValue(new Promise<boolean>(() => {}))
    const wrapper = await mountPanel()

    expect(wrapper.findAll('.session-row')).toHaveLength(2)
    expect(wrapper.text()).not.toContain(i18n.global.t('sessions.loading'))
    expect(rowButtons(wrapper).every((b) => b.attributes('disabled') === undefined)).toBe(true)

    await revokeButton(wrapper, 1)!.trigger('click')
    await flushPromises()
    expect(revokeSessionFamily).toHaveBeenCalledWith(OTHER.id)
  })

  // BITE 3 — the simplification this merge invites and ADR 0058 forbids: ONE "remove this device" button. The
  // server can never reach a remote device's LOCAL key, only its wrap, so the two acts cannot be one.
  it('keeps the forget action and the escalation two actions, with two labels and two confirms', async () => {
    withEscalatableCurrent()
    vi.mocked(isTrusted).mockResolvedValue(true)
    const wrapper = await mountPanel()

    // Two distinct labels on the same row.
    expect(forgetButton(wrapper)).toBeDefined()
    expect(escalateButton(wrapper, 0)).toBeDefined()
    expect(i18n.global.t('unlock.untrust')).not.toBe(i18n.global.t('sessions.escalate'))

    // Two distinct confirmations: the escalation asks through window.confirm, the forget through its dialog —
    // and neither confirm text is the other's.
    await escalateButton(wrapper, 0)!.trigger('click')
    await flushPromises()
    expect(window.confirm).toHaveBeenCalledWith(i18n.global.t('sessions.escalateConfirm'))
    expect(i18n.global.t('sessions.escalateConfirm')).not.toBe(i18n.global.t('sessions.untrustConfirmBody'))

    // …and the two do different things: the escalation ended a session, the forget never does.
    expect(revokeSessionFamily).toHaveBeenCalledWith(CURRENT.id)
    expect(untrustThisDevice).not.toHaveBeenCalled()

    const fresh = await mountPanel()
    await forgetButton(fresh)!.trigger('click')
    await flushPromises()
    expect(fresh.find('.dialog').text()).toContain(i18n.global.t('sessions.untrustConfirmTitle'))
  })

  it('offers the forget action ONLY on the current device\'s row', async () => {
    vi.mocked(isTrusted).mockResolvedValue(true)
    const wrapper = await mountPanel()
    const otherRow = wrapper.findAll('.session-row')[1]
    // A remote device's local key is unreachable from here by construction; offering "forget" there would be a
    // promise the client cannot keep.
    expect(otherRow.findAll('button').some((b) => b.text() === i18n.global.t('unlock.untrust'))).toBe(false)
  })
})
