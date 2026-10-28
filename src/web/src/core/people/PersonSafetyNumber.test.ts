// @vitest-environment happy-dom
// The safety number and chain code on a profile (uc.spaces.enforce-verified-identities main flow 0-3, A1, E1; ADR 0028
// Decision 3; ADR 0111 decisions 12-15). The tenant's read (`personInTenant` — the anchor, the pin, the head, who
// verified whom), its write (`verifyPerson`) and my own head (`myChainHead`) are mocked so each test can say exactly what
// was read and written; the number, the chain code and the QR codec are REAL (the vector-pinned `safetyNumber`,
// `chainCodeDigits`, `safetyCode`), so a scanned code is compared the way the app compares it. The anchor, the counting of
// verifications and their write are tenantTrust.test.ts'.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

vi.mock('../../features/account/identityPins', async (orig) => ({
  ...(await orig<typeof import('../../features/account/identityPins')>()),
  myIdentityPublicKeyB64: vi.fn(),
}))
vi.mock('../../features/account/tenantTrust', () => ({ personInTenant: vi.fn(), verifyPerson: vi.fn(), myChainHead: vi.fn() }))
vi.mock('./directory', async (orig) => ({
  ...(await orig<typeof import('./directory')>()),
  loadServedIdentity: vi.fn(),
  loadTenantPeople: vi.fn(),
}))

import PersonSafetyNumber from './PersonSafetyNumber.vue'
import MySafetyNumber from './MySafetyNumber.vue'
import {
  myIdentityPublicKeyB64, safetyNumber, IdentityMovedError, IdentityUnanchoredError, type Anchor, type Pin,
} from '../../features/account/identityPins'
import { personInTenant, verifyPerson, myChainHead, type PersonInTenant } from '../../features/account/tenantTrust'
import { chainCode, fingerprintDigestB64, safetyCodePayload } from '../../features/account/safetyCode'
import { TrustStateRefused } from '../../features/account/trustMarks'
import { activeSpace } from '../../app/activeSpace'
import { account } from '../../app/account'
import { loadServedIdentity, loadTenantPeople, type Person } from './directory'
import { realI18n, t } from '../../test/i18n'

const bundle = () => b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
const aHead = () => b64std(v1.randomBytes(32))
const TENANT = '7e000000-0000-4000-8000-0000000000f1'
const ME = 'eeeeeeee-0000-4000-8000-000000000009'
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const BEN = 'bbbbbbbb-0000-4000-8000-000000000002'
const DIR = bundle() // the key the directory serves for Anna
const OTHER = bundle() // any other key
const HEAD = aHead() // the head the directory serves beside DIR
const OTHER_HEAD = aHead() // a head Anna's own device shows when a step was withheld from this one

const pinOf = (key: string, o: Partial<Pin> = {}): Pin =>
  ({ fingerprint: fingerprintDigestB64(key), head: HEAD, index: 0, firstSeenAt: 1, replaced: false, ...o })
/** What the tenant's read of Anna comes to: anchored at DIR and HEAD unless the anchor is an anomaly. */
const reads = (anchor: Anchor, o: Partial<PersonInTenant> = {}) =>
  vi.mocked(personInTenant).mockResolvedValue({
    anchor,
    pin: anchor === 'anomaly' ? null : pinOf(DIR),
    head: anchor === 'anomaly' ? null : HEAD,
    fingerprint: anchor === 'anomaly' ? null : fingerprintDigestB64(DIR),
    rotatedAt: null,
    isNew: false,
    verifiedBy: [],
    ...o,
  })
const person = (userId: string, displayName: string): Person => ({ userId, username: displayName.toLowerCase(), realName: displayName, displayName })

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
type W = Awaited<ReturnType<typeof mountTheirs>>
const allDigits = (w: Pick<W, 'findAll'>) => w.findAll('[data-testid="safety-digits"]').map((d) => d.text())
const digits = (w: W) => allDigits(w)[0]
const chainDigits = (w: W) => w.get('[data-testid="person-chain-code"]').get('[data-testid="safety-digits"]').text()
const state = (w: W) => w.get('[data-testid="person-safety-state"]').text()
const outcome = (w: W) => w.get('[data-testid="person-safety-outcome"]').text()
async function scan(w: W, text: string) {
  await w.get('[data-testid="person-safety-scan"]').trigger('click')
  w.getComponent(Scanner).vm.$emit('found', text)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  activeSpace.tenantId = TENANT
  account.userId = ME
  vi.mocked(loadServedIdentity).mockResolvedValue({ identityPublicKey: DIR, keyChainHead: HEAD })
  vi.mocked(loadTenantPeople).mockResolvedValue(new Map([[BEN, person(BEN, 'Ben')]]))
  reads('firstUse')
  vi.mocked(verifyPerson).mockResolvedValue(undefined)
})

describe('my own safety number and chain code (main flow 0)', () => {
  it('shows the vector-pinned number of my vault key, the chain code of my head and a QR of both, and nothing to edit', async () => {
    const mine = bundle()
    const head = aHead()
    vi.mocked(myIdentityPublicKeyB64).mockReturnValue(mine)
    vi.mocked(myChainHead).mockResolvedValue({ userId: ME, head })
    const w = mount(MySafetyNumber, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(allDigits(w)).toEqual([safetyNumber(mine), chainCode(head)])
    expect(w.get('.plate-stub').text()).toBe(safetyCodePayload(mine, head))
    expect(w.get('.plate-stub').text()).toMatch(/^veyqid:2:/)
    expect(w.text()).toContain(t('safety.mineIntro'))
    expect(w.find('button').exists()).toBe(false)
  })

  it('a vault it cannot read is said, not an empty section', async () => {
    vi.mocked(myIdentityPublicKeyB64).mockImplementation(() => {
      throw new Error('locked')
    })
    const w = mount(MySafetyNumber, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.text()).toContain(t('safety.mineUnavailable'))
    expect(w.find('.plate-stub').exists()).toBe(false)
  })

  it('a served chain that does not end at my key is said too — no number and no code that could match a short chain', async () => {
    vi.mocked(myIdentityPublicKeyB64).mockReturnValue(bundle())
    vi.mocked(myChainHead).mockRejectedValue(new Error('the served key chain does not end at this account’s identity'))
    const w = mount(MySafetyNumber, { global: { plugins: [realI18n()], stubs } })
    await flushPromises()
    expect(w.text()).toContain(t('safety.mineUnavailable'))
    expect(w.find('.plate-stub').exists()).toBe(false)
    expect(w.find('[data-testid="safety-digits"]').exists()).toBe(false)
  })
})

describe('another member’s safety number in the active tenant (main flow 1-3, ADR 0111 decision 15)', () => {
  it('anchored and nobody verified them: the anchored key’s number and chain code, "Not verified"', async () => {
    const w = await mountTheirs()
    expect(personInTenant).toHaveBeenCalledWith(TENANT, ANNA)
    expect(digits(w)).toBe(safetyNumber(DIR))
    expect(chainDigits(w)).toBe(chainCode(HEAD))
    expect(state(w)).toBe(t('safety.stateUnverified'))
    expect(w.find('[data-testid="person-safety-new"]').exists()).toBe(false)
    expect(w.find('[data-testid="person-safety-verified-by"]').exists()).toBe(false)
  })

  it('new: first seen under two weeks ago reads "New" beside "Not verified"', async () => {
    reads('match', { isNew: true })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-new"]').text()).toBe(t('safety.stateNew'))
    expect(state(w)).toBe(t('safety.stateUnverified'))
  })

  it('verified by …: "Verified", and the verifiers named by the display rule — this account as "you"', async () => {
    reads('match', { verifiedBy: [ME, BEN] })
    const w = await mountTheirs()
    expect(state(w)).toBe(t('safety.stateVerified'))
    expect(w.get('[data-testid="person-safety-verified-by"]').text())
      .toBe(t('safety.verifiedBy', { names: `${t('safety.verifiedByYou')}, Ben` }))
  })

  it('an anomaly: the directory’s digits for an in-person compare, the note — and no compare, no accept', async () => {
    reads('anomaly')
    const w = await mountTheirs()
    expect(state(w)).toBe(t('trust.anomaly'))
    expect(w.get('[data-testid="person-safety-anomaly"]').text()).toBe(t('trust.anomalyNote'))
    expect(digits(w)).toBe(safetyNumber(DIR))
    expect(w.findAll('button')).toHaveLength(0)
    expect(w.find('[data-testid="person-safety-new"]').exists()).toBe(false)
  })

  it('a rotation the chain reached: "New identity, signed by their previous key, on <date>"', async () => {
    const at = new Date('2026-10-20T12:00:00Z')
    reads('advanced', { rotatedAt: at })
    const w = await mountTheirs()
    const date = at.toLocaleDateString('en', { year: 'numeric', month: 'long', day: 'numeric' })
    expect(w.get('[data-testid="person-safety-rotated"]').text()).toBe(t('trust.rotated', { date }))
  })

  it('a pin the join record replaced says so; a pin that was theirs says nothing (control)', async () => {
    reads('replaced', { pin: pinOf(DIR, { replaced: true }) })
    const w = await mountTheirs()
    expect(w.get('[data-testid="person-safety-replaced"]').text()).toBe(t('safety.replaced'))

    reads('match')
    const w2 = await mountTheirs()
    expect(w2.find('[data-testid="person-safety-replaced"]').exists()).toBe(false)
  })

  it('a scanned code of the shown key AND head verifies them in this tenant (one action)', async () => {
    const w = await mountTheirs()
    reads('match', { verifiedBy: [ME] }) // what the tenant holds after
    await scan(w, safetyCodePayload(DIR, HEAD))
    expect(verifyPerson).toHaveBeenCalledTimes(1)
    expect(verifyPerson).toHaveBeenCalledWith(TENANT, ANNA, { fingerprint: fingerprintDigestB64(DIR), head: HEAD })
    expect(outcome(w)).toBe(t('safety.verified', { name: 'Anna Meyer' }))
    expect(state(w)).toBe(t('safety.stateVerified'))
  })

  it('a code of the same key at ANOTHER head (a withheld step) is a mismatch: said, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, safetyCodePayload(DIR, OTHER_HEAD))
    expect(verifyPerson).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.mismatch', { name: 'Anna Meyer' }))
  })

  it('a code of a DIFFERENT key is a mismatch: said, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, safetyCodePayload(OTHER, HEAD))
    expect(verifyPerson).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.mismatch', { name: 'Anna Meyer' }))
  })

  it('a veyqid:1 code — the other app is outdated — is said as such, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, `veyqid:1:${fingerprintDigestB64(DIR)}`)
    expect(verifyPerson).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.outdated', { name: 'Anna Meyer' }))
  })

  it('a QR that is no safety code (an Emergency Kit) is said as such, and nothing is written', async () => {
    const w = await mountTheirs()
    await scan(w, 'veyqkit:1:something')
    expect(verifyPerson).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.foreign'))
  })

  it('compare digits: both shown large; "They match" verifies, "They don’t match" writes nothing (A1)', async () => {
    const w = await mountTheirs()
    await w.get('[data-testid="person-safety-compare-start"]').trigger('click')
    expect(w.get('[data-testid="person-safety-compare"]').findAll('[data-testid="safety-digits"]').map((d) => d.text()))
      .toEqual([safetyNumber(DIR), chainCode(HEAD)])
    await w.get('[data-testid="person-safety-differ"]').trigger('click')
    await flushPromises()
    expect(verifyPerson).not.toHaveBeenCalled()
    expect(outcome(w)).toBe(t('safety.mismatch', { name: 'Anna Meyer' }))

    await w.get('[data-testid="person-safety-compare-start"]').trigger('click')
    await w.get('[data-testid="person-safety-match"]').trigger('click')
    await flushPromises()
    expect(verifyPerson).toHaveBeenCalledWith(TENANT, ANNA, { fingerprint: fingerprintDigestB64(DIR), head: HEAD })
  })

  it('a key that moved under the compare says so and re-reads what the tenant anchors now', async () => {
    vi.mocked(verifyPerson).mockRejectedValue(new IdentityMovedError(ANNA))
    const w = await mountTheirs()
    const moved = aHead()
    reads('advanced', { head: moved }) // a rotation landed meanwhile
    await scan(w, safetyCodePayload(DIR, HEAD))
    expect(outcome(w)).toBe(t('safety.moved', { name: 'Anna Meyer' }))
    expect(chainDigits(w)).toBe(chainCode(moved)) // the code shown is now the head the tenant anchors
  })

  it('a key the tenant does not anchor at the write says so, and re-reads into the anomaly', async () => {
    vi.mocked(verifyPerson).mockRejectedValue(new IdentityUnanchoredError(ANNA))
    const w = await mountTheirs()
    reads('anomaly')
    await scan(w, safetyCodePayload(DIR, HEAD))
    expect(outcome(w)).toBe(t('safety.unanchored', { name: 'Anna Meyer' }))
    expect(state(w)).toBe(t('trust.anomaly'))
  })

  it('a refused pin store is said with the way back, never read as unpinned', async () => {
    vi.mocked(personInTenant).mockRejectedValue(new TrustStateRefused('trust_state_rolled_back', 'pins'))
    const w = await mountTheirs()
    expect(w.text()).toContain(t('verify.trustRolledBack'))
    expect(w.find('[data-testid="person-safety-scan"]').exists()).toBe(false)
  })

  it('a former member (no directory key) shows no safety section content at all', async () => {
    vi.mocked(loadServedIdentity).mockResolvedValue(null)
    const w = await mountTheirs()
    expect(w.text()).toBe('')
    expect(personInTenant).not.toHaveBeenCalled()
  })
})
