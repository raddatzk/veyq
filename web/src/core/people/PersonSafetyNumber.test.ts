// @vitest-environment happy-dom
// The safety number on a profile (uc.spaces.enforce-verified-identities main flow 0-3, A1, E1; ADR 0028 Addendum 1-2).
// The pin store's write door (`markVerified`) and its read (`getPin`) are mocked so each test can say exactly what was
// written; the number, the fold and the QR codec are REAL (the vector-pinned `safetyNumber`, `foldTrust`, `safetyCode`),
// so a scanned code is compared the way the app compares it. The store's own one-write/merge behaviour is
// identityPins.test.ts' "pinIfUnpinned" block.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../features/account/identityPins', async (orig) => ({
  ...(await orig<typeof import('../../features/account/identityPins')>()),
  getPin: vi.fn(),
  markVerified: vi.fn(),
  myIdentityPublicKeyB64: vi.fn(),
}))
vi.mock('./directory', async (orig) => ({ ...(await orig<typeof import('./directory')>()), loadIdentityKey: vi.fn() }))

import PersonSafetyNumber from './PersonSafetyNumber.vue'
import MySafetyNumber from './MySafetyNumber.vue'
import { getPin, markVerified, myIdentityPublicKeyB64, safetyNumber, IdentityMovedError } from '../../features/account/identityPins'
import { safetyCodePayload } from '../../features/account/safetyCode'
import { TrustStateRefused } from '../../features/account/trustMarks'
import { loadIdentityKey } from './directory'
import { realI18n, t } from '../../test/i18n'

const bundle = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const DIR = bundle() // the key the directory serves for Anna
const OTHER = bundle() // any other key

// The camera and the dialogs are not what is under test: the scanner is a stub the test makes "find" a string, the
// review dialog a stub that records the member it was handed.
const Scanner = defineComponent({ name: 'KitScanner', props: ['hint', 'errorText'], emits: ['found', 'close'], setup: () => () => h('div', { class: 'scanner-stub' }) })
const Review = defineComponent({
  name: 'ChangedKeyReviewDialog',
  props: ['member', 'visible'],
  emits: ['update:visible', 'accepted'],
  setup: (p) => () => (p.visible ? h('div', { class: 'review-stub' }, p.member.identityPublicKey) : null),
})
const Plate = defineComponent({ name: 'PairingCode', props: ['payload', 'alt', 'failedText'], setup: (p) => () => h('div', { class: 'plate-stub' }, p.payload) })
const stubs = {
  KitScanner: Scanner,
  ChangedKeyReviewDialog: Review,
  PairingCode: Plate,
  Button: { props: ['label', 'loading', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
  TrustResetLink: true,
}

async function mountTheirs() {
  const w = mount(PersonSafetyNumber, { props: { userId: ANNA, name: 'Anna Meyer', username: 'anna' }, global: { plugins: [realI18n()], stubs } })
  await flushPromises()
  return w
}
const digits = (w: Awaited<ReturnType<typeof mountTheirs>>) => w.get('[data-testid="safety-digits"]').text()
const outcome = (w: Awaited<ReturnType<typeof mountTheirs>>) => w.get('[data-testid="person-safety-outcome"]').text()
async function scan(w: Awaited<ReturnType<typeof mountTheirs>>, text: string) {
  await w.get('[data-testid="person-safety-scan"]').trigger('click')
  w.getComponent(Scanner).vm.$emit('found', text)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(loadIdentityKey).mockResolvedValue(DIR)
  vi.mocked(getPin).mockResolvedValue(null)
  vi.mocked(markVerified).mockResolvedValue(undefined)
})

describe('my own safety number (main flow 0)', () => {
  it('shows the vector-pinned number of my vault key and a QR of its fingerprint, and nothing to edit', () => {
    const mine = bundle()
    vi.mocked(myIdentityPublicKeyB64).mockReturnValue(mine)
    const w = mount(MySafetyNumber, { global: { plugins: [realI18n()], stubs } })
    expect(w.get('[data-testid="safety-digits"]').text()).toBe(safetyNumber(mine))
    expect(w.get('.plate-stub').text()).toBe(safetyCodePayload(mine))
    expect(w.text()).toContain(t('safety.mineIntro'))
    expect(w.find('button').exists()).toBe(false)
  })

  it('a vault it cannot read is said, not an empty section', () => {
    vi.mocked(myIdentityPublicKeyB64).mockImplementation(() => {
      throw new Error('locked')
    })
    const w = mount(MySafetyNumber, { global: { plugins: [realI18n()], stubs } })
    expect(w.text()).toContain(t('safety.mineUnavailable'))
    expect(w.find('.plate-stub').exists()).toBe(false)
  })
})

describe('another member’s safety number (main flow 1-3)', () => {
  it('unpinned: shows the DIRECTORY key’s number as not verified', async () => {
    const w = await mountTheirs()
    expect(digits(w)).toBe(safetyNumber(DIR))
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateUnverified'))
  })

  it('pinned: shows the PIN’s number, not the directory’s, and a changed key points at the review', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: OTHER, state: 'pinned', firstSeenAt: 1 })
    const w = await mountTheirs()
    expect(digits(w)).toBe(safetyNumber(OTHER))
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('verify.badgeChanged'))
    await w.get('[data-testid="person-safety-review"]').trigger('click')
    expect(w.get('.review-stub').text()).toBe(DIR) // the review compares against what the directory serves now
  })

  it('control: a pin that holds the directory key reads as pinned, with no review offered', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: DIR, state: 'pinned', firstSeenAt: 1 })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateUnverified'))
    expect(w.find('[data-testid="person-safety-changed"]').exists()).toBe(false)
  })

  it('a verified pin reads as verified', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: DIR, state: 'verified', firstSeenAt: 1 })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateVerified'))
  })

  it('a scanned code of the shown key on an UNPINNED member verifies it, pinning the compared key (one action)', async () => {
    const w = await mountTheirs()
    vi.mocked(getPin).mockResolvedValue({ key: DIR, state: 'verified', firstSeenAt: 1 }) // what the store holds after
    await scan(w, safetyCodePayload(DIR))
    expect(markVerified).toHaveBeenCalledTimes(1)
    expect(markVerified).toHaveBeenCalledWith(ANNA, DIR, { pinIfUnpinned: true })
    expect(outcome(w)).toBe(t('safety.verified', { name: 'Anna Meyer' }))
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateVerified'))
  })

  it('on a PINNED member the match verifies the pin’s key and never asks to pin', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: DIR, state: 'pinned', firstSeenAt: 1 })
    const w = await mountTheirs()
    await scan(w, safetyCodePayload(DIR))
    expect(markVerified).toHaveBeenCalledWith(ANNA, DIR, { pinIfUnpinned: false })
  })

  it('a code of a DIFFERENT key is a mismatch: said, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, safetyCodePayload(OTHER))
    expect(markVerified).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.mismatch', { name: 'Anna Meyer' }))
  })

  it('a QR that is no safety code (an Emergency Kit) is said as such, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, 'veyqkit:1:something')
    expect(markVerified).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.foreign'))
  })

  it('compare digits: "They match" verifies, "They don’t match" writes nothing (A1)', async () => {
    const w = await mountTheirs()
    await w.get('[data-testid="person-safety-compare-start"]').trigger('click')
    expect(w.get('[data-testid="person-safety-compare"]').get('[data-testid="safety-digits"]').text()).toBe(safetyNumber(DIR))
    await w.get('[data-testid="person-safety-differ"]').trigger('click')
    await flushPromises()
    expect(markVerified).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.mismatch', { name: 'Anna Meyer' }))

    await w.get('[data-testid="person-safety-compare-start"]').trigger('click')
    await w.get('[data-testid="person-safety-match"]').trigger('click')
    await flushPromises()
    expect(markVerified).toHaveBeenCalledWith(ANNA, DIR, { pinIfUnpinned: true })
  })

  it('a key that moved under the compare says so and re-reads what the store holds', async () => {
    vi.mocked(markVerified).mockRejectedValue(new IdentityMovedError(ANNA))
    const w = await mountTheirs()
    vi.mocked(getPin).mockResolvedValue({ key: OTHER, state: 'pinned', firstSeenAt: 1 }) // a concurrent pin landed
    await scan(w, safetyCodePayload(DIR))
    expect(outcome(w)).toBe(t('safety.moved', { name: 'Anna Meyer' }))
    expect(digits(w)).toBe(safetyNumber(OTHER)) // the number shown is now the key the store holds
  })

  it('a refused pin store is said with the way back, never read as unpinned', async () => {
    vi.mocked(getPin).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    const w = await mountTheirs()
    expect(w.text()).toContain(t('verify.trustRolledBack'))
    expect(w.find('[data-testid="person-safety-scan"]').exists()).toBe(false)
  })

  it('a former member (no directory key) shows no safety section content at all', async () => {
    vi.mocked(loadIdentityKey).mockResolvedValue(null)
    const w = await mountTheirs()
    expect(w.text()).toBe('')
    expect(getPin).not.toHaveBeenCalled()
  })
})

describe('a verified contact\'s key changed, on the profile (ADR 0028 Addendum 3)', () => {
  it('reads louder, and after the review\'s accept the profile reads pinned and offers the compare again', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: OTHER, state: 'verified', firstSeenAt: 1 })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.badgeVerifiedChanged'))
    expect(w.get('[data-testid="person-safety-changed"]').text()).toContain(t('safety.changedVerifiedNote', { name: 'Anna Meyer' }))

    // The review accepts: the store now pins the directory key as `pinned` (acceptKeyChange's own suite pins that).
    vi.mocked(getPin).mockResolvedValue({ key: DIR, state: 'pinned', firstSeenAt: 1 })
    await w.get('[data-testid="person-safety-review"]').trigger('click')
    w.getComponent(Review).vm.$emit('accepted')
    await flushPromises()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateUnverified'))
    expect(digits(w)).toBe(safetyNumber(DIR))
    expect(w.find('[data-testid="person-safety-scan"]').exists()).toBe(true)
    expect(w.find('[data-testid="person-safety-compare-start"]').exists()).toBe(true)
    await scan(w, safetyCodePayload(DIR))
    expect(markVerified).toHaveBeenCalledWith(ANNA, DIR, { pinIfUnpinned: false })
  })

  it('control: an ordinary pinned contact\'s change reads as "Key changed"', async () => {
    vi.mocked(getPin).mockResolvedValue({ key: OTHER, state: 'pinned', firstSeenAt: 1 })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('verify.badgeChanged'))
    expect(w.get('[data-testid="person-safety-changed"]').text()).toContain(t('safety.changedNote', { name: 'Anna Meyer' }))
  })
})
