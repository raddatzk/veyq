// @vitest-environment happy-dom
// The safety number on a profile (uc.spaces.enforce-verified-identities main flow 0-3, A1, E1; ADR 0028 Decision 3;
// cr-secure-the-account decision 9). The pin store's write door (`markVerified`) and its read (`personTrust` — the fold
// against the served key and head, and the chain's newest step) are mocked so each test can say exactly what was read and
// written; the number and the QR codec are REAL (the vector-pinned `safetyNumber`, `safetyCode`), so a scanned code is
// compared the way the app compares it. The fold itself, the chain walk and the store's one-write/merge behaviour are
// identityPins.test.ts' ("personTrust", "pinAtHead").
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../features/account/identityPins', async (orig) => ({
  ...(await orig<typeof import('../../features/account/identityPins')>()),
  personTrust: vi.fn(),
  markVerified: vi.fn(),
  myIdentityPublicKeyB64: vi.fn(),
}))
vi.mock('./directory', async (orig) => ({ ...(await orig<typeof import('./directory')>()), loadServedIdentity: vi.fn() }))

import PersonSafetyNumber from './PersonSafetyNumber.vue'
import MySafetyNumber from './MySafetyNumber.vue'
import { personTrust, markVerified, myIdentityPublicKeyB64, safetyNumber, IdentityMovedError, type Pin, type TrustState } from '../../features/account/identityPins'
import { fingerprintDigestB64, safetyCodePayload } from '../../features/account/safetyCode'
import { TrustStateRefused } from '../../features/account/trustMarks'
import { loadServedIdentity } from './directory'
import { realI18n, t } from '../../test/i18n'

const bundle = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const DIR = bundle() // the key the directory serves for Anna
const OTHER = bundle() // any other key
const HEAD = 'ERERERERERERERERERERERERERERERERERERERERERE=' // the head the directory serves beside DIR

const pinOf = (key: string, state: Pin['state'] = 'pinned'): Pin => ({ fingerprint: fingerprintDigestB64(key), head: HEAD, state, firstSeenAt: 1 })
/** What the profile's read of Anna comes to: the badge, the pin after the read, and the date of a rotation. */
const reads = (state: TrustState, pin: Pin | null, rotatedAt: Date | null = null) =>
  vi.mocked(personTrust).mockResolvedValue({ state, pin, rotatedAt })

// The camera is not what is under test: the scanner is a stub the test makes "find" a string.
const Scanner = defineComponent({ name: 'KitScanner', props: ['hint', 'errorText'], emits: ['found', 'close'], setup: () => () => h('div', { class: 'scanner-stub' }) })
const Plate = defineComponent({ name: 'PairingCode', props: ['payload', 'alt', 'failedText'], setup: (p) => () => h('div', { class: 'plate-stub' }, p.payload) })
const stubs = {
  KitScanner: Scanner,
  PairingCode: Plate,
  VeyqButton: { props: ['label', 'loading', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqMessage: { props: ['tone'], template: '<div class="msg" :data-tone="tone"><slot /></div>' },
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
  vi.mocked(loadServedIdentity).mockResolvedValue({ identityPublicKey: DIR, keyChainHead: HEAD })
  reads('unpinned', null)
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

  it('the read is judged against the key AND the head the directory serves', async () => {
    await mountTheirs()
    expect(personTrust).toHaveBeenCalledWith(ANNA, { identityPublicKey: DIR, keyChainHead: HEAD })
  })

  it('an anomaly: shows the PIN’s number, not the directory’s, with the note — and no review, no accept', async () => {
    reads('anomaly', pinOf(OTHER))
    const w = await mountTheirs()
    expect(digits(w)).toBe(safetyNumber(OTHER))
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('trust.anomaly'))
    expect(w.get('[data-testid="person-safety-anomaly"]').text()).toBe(t('trust.anomalyNote'))
    expect(w.findAll('button').map((b) => b.text())).toEqual([t('safety.scan'), t('safety.compare')])
  })

  it('control: a pin that holds the directory key reads as pinned, with no note', async () => {
    reads('pinned', pinOf(DIR))
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateUnverified'))
    expect(w.find('[data-testid="person-safety-anomaly"]').exists()).toBe(false)
    expect(w.find('[data-testid="person-safety-rotated"]').exists()).toBe(false)
  })

  it('a rotation the chain reached: the new key\'s number, and "New identity, signed by their previous key, on <date>"', async () => {
    const at = new Date('2026-10-20T12:00:00Z')
    reads('pinned', pinOf(DIR), at)
    const w = await mountTheirs()
    expect(digits(w)).toBe(safetyNumber(DIR))
    const date = at.toLocaleDateString('en', { year: 'numeric', month: 'long', day: 'numeric' })
    expect(w.get('[data-testid="person-safety-rotated"]').text()).toBe(t('trust.rotated', { date }))
  })

  it('a verified pin reads as verified', async () => {
    reads('verified', pinOf(DIR, 'verified'))
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateVerified'))
  })

  it('a scanned code of the shown key on an UNPINNED member verifies it, pinning the compared key at the served head (one action)', async () => {
    const w = await mountTheirs()
    reads('verified', pinOf(DIR, 'verified')) // what the store holds after
    await scan(w, safetyCodePayload(DIR))
    expect(markVerified).toHaveBeenCalledTimes(1)
    expect(markVerified).toHaveBeenCalledWith(ANNA, fingerprintDigestB64(DIR), { pinAtHead: HEAD })
    expect(outcome(w)).toBe(t('safety.verified', { name: 'Anna Meyer' }))
    expect(w.get('[data-testid="person-safety-state"]').text()).toBe(t('safety.stateVerified'))
  })

  it('on a PINNED member the match verifies the pin’s key and never asks to pin', async () => {
    reads('pinned', pinOf(DIR))
    const w = await mountTheirs()
    await scan(w, safetyCodePayload(DIR))
    expect(markVerified).toHaveBeenCalledWith(ANNA, fingerprintDigestB64(DIR), {})
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
    expect(markVerified).toHaveBeenCalledWith(ANNA, fingerprintDigestB64(DIR), { pinAtHead: HEAD })
  })

  it('a key that moved under the compare says so and re-reads what the store holds', async () => {
    vi.mocked(markVerified).mockRejectedValue(new IdentityMovedError(ANNA))
    const w = await mountTheirs()
    reads('anomaly', pinOf(OTHER)) // a concurrent pin landed
    await scan(w, safetyCodePayload(DIR))
    expect(outcome(w)).toBe(t('safety.moved', { name: 'Anna Meyer' }))
    expect(digits(w)).toBe(safetyNumber(OTHER)) // the number shown is now the key the store holds
  })

  it('a refused pin store is said with the way back, never read as unpinned', async () => {
    vi.mocked(personTrust).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    const w = await mountTheirs()
    expect(w.text()).toContain(t('verify.trustRolledBack'))
    expect(w.find('[data-testid="person-safety-scan"]').exists()).toBe(false)
  })

  it('a former member (no directory key) shows no safety section content at all', async () => {
    vi.mocked(loadServedIdentity).mockResolvedValue(null)
    const w = await mountTheirs()
    expect(w.text()).toBe('')
    expect(personTrust).not.toHaveBeenCalled()
  })
})
