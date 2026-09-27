// @vitest-environment happy-dom
// uc.account.onboard-device § Main flow — add a new device (web). The ceremony end to end on the trusted side,
// with the real poll loop, the real slide and the real crypto underneath; only the wire is mocked.
//
// Since the scan arm landed it carries BOTH directions, and the scanned one adds the property this whole
// feature turns on: **two gates in series** (ADR 0059 §6, §7 — the proof verified AND the slide completed).
// Either gate alone satisfies "no envelope was posted", so they are driven separately, one bite each — a suite
// that only drove a wrong code could not say which of the two held.
//
// Three properties live here and nowhere else:
//   - **E2 abandons, it does not retry.** Declining the comparison must reach NEITHER `delegate` nor `seal` —
//     and the restart must mint a different handle, because offering "try again" on the same channel is an
//     invitation to grant past the one signal that something else answered.
//   - **The order is delegate → seal**, asserted as a sequence rather than as two independent calls: two
//     `toHaveBeenCalled` lines are satisfied just as well by the wrong order, and the wrong order cannot work
//     (the token being sealed is what `delegate` returns).
//   - **The channel is not read again after the seal** (D7). A collect after the seal hands this device its own
//     grant and leaves the other one waiting forever, with both screens still looking correct.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

vi.mock('./pairingChannel', () => ({
  openPairingChannel: vi.fn(),
  collectPairingChannel: vi.fn(),
  delegatePairingSession: vi.fn(),
  sealPairingChannel: vi.fn(),
}))

const MASTER_KEY = new Uint8Array(32).fill(7)
vi.mock('../../../app/vault', () => ({ masterKey: () => MASTER_KEY }))

// A tripwire, not a dependency: this panel does not import the device register today, and the point of the spy
// is that it fires the day somebody adds it. The onboarded device writes its own entry (write moment 1, from
// its own unlock) — a grant that wrote the entry from this side would work perfectly and quietly re-open the
// merge hazard en.account.device-register's single-writer rule exists to close.
vi.mock('../deviceRegister', () => ({ rememberThisDevice: vi.fn(), rememberThisDeviceWrap: vi.fn() }))

import AddDevicePanel from './AddDevicePanel.vue'
import SlideToConfirm from '../../../shared/ui/SlideToConfirm.vue'
import {
  openPairingChannel,
  collectPairingChannel,
  delegatePairingSession,
  sealPairingChannel,
} from './pairingChannel'
import {
  mintTransportKey,
  transportAnswer,
  checkNumber,
  openGrant,
  parsePairingPayload,
  pairingPayload,
  signInPayload,
  signInAnswer,
  channelKeys,
  mintPairingSecret,
} from './pairing'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { rememberThisDevice, rememberThisDeviceWrap } from '../deviceRegister'
import { account } from '../../../app/account'
import { realI18n, t } from '../../../test/i18n'

const open = vi.mocked(openPairingChannel)
const collect = vi.mocked(collectPairingChannel)
const delegate = vi.mocked(delegatePairingSession)
const seal = vi.mocked(sealPairingChannel)

const REFRESH_TOKEN = 'ZGVsZWdhdGVkLXJlZnJlc2gtdG9rZW4'
const transport = mintTransportKey()

const stubs = {
  Message: { props: ['severity'], template: '<div class="msg"><slot /></div>' },
  InputText: {
    props: ['modelValue', 'placeholder', 'fluid'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['label', 'icon', 'severity', 'loading', 'disabled', 'outlined', 'text'],
    emits: ['click'],
    template: '<button :disabled="loading || disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
  // The QR encoder is loaded lazily from a dynamic import and draws nothing a test can read; what matters here
  // is WHICH payload it is handed, so the stub renders it.
  PairingCode: { props: ['payload'], template: '<div class="qr">{{ payload }}</div>' },
  // Stubbed for its camera, kept for its contract: `accepts` is a prop this file reads, and `found` is emitted
  // the way the decoder would.
  KitScanner: true,
}

const buttonLabelled = (w: VueWrapper, label: string) => w.findAll('button').find((b) => b.text() === label)

const mountPanel = () => mount(AddDevicePanel, { global: { plugins: [realI18n()], stubs } })

/** Advance the real poll loop by one interval and let its promise chain settle. */
const pollOnce = async () => {
  await vi.advanceTimersByTimeAsync(2_000)
  await flushPromises()
}

/** The secret the last shown code carried — read off the screen the way the scanning device reads it. */
let shownSecret = ''

const showCode = async (w: VueWrapper) => {
  await buttonLabelled(w, t('pairing.start'))!.trigger('click')
  await flushPromises()
  await vi.advanceTimersByTimeAsync(0)
  await flushPromises()
  const qr = w.find('.qr')
  if (qr.exists()) shownSecret = parsePairingPayload(qr.text()) ?? ''
}

/** The channel the shown code's secret derives — what the device that scans it derives too. */
const shownChannel = () => channelKeys(shownSecret)

/** The honest new device answers the shown code: its transport key, MACed under the channel key. */
const answerArrives = async (w: VueWrapper) => {
  collect.mockResolvedValue({ answer: transportAnswer(shownChannel().key, transport.pub) })
  await pollOnce()
  await w.vm.$nextTick()
}

const SCANNED_SECRET = mintPairingSecret()
const SCANNED = channelKeys(SCANNED_SECRET)

/** Take the *scan a code* arm and hand the scanner the other device's payload, the way the camera would. Returns
 *  the handle the scanned secret derives — the only thing that reaches the server. */
const scanCode = async (w: VueWrapper) => {
  await buttonLabelled(w, t('pairing.scanStart'))!.trigger('click')
  w.findComponent({ name: 'KitScanner' }).vm.$emit('found', signInPayload(SCANNED_SECRET))
  await flushPromises()
  await w.vm.$nextTick()
  return SCANNED.handle
}

/** The six digits this device drew and is asking the account holder to transcribe. */
const shownCode = (w: VueWrapper) => w.find('[data-testid="pairing-code"]').text()

/** Any other six digits — the mistyped transcription, or an attacker who never saw this screen. */
const someOtherCode = (code: string) => String((Number(code) + 1) % 1_000_000).padStart(6, '0')

/** The other device answers with its key and a proof of `code`. */
const proofArrives = async (w: VueWrapper, code: string, key = mintTransportKey()) => {
  collect.mockResolvedValue({ answer: signInAnswer(SCANNED.key, key.pub, code) })
  await pollOnce()
  await w.vm.$nextTick()
  return key
}

const nameAndSlide = async (w: VueWrapper, label = 'Küchen-Tablet') => {
  await w.find('input').setValue(label)
  const slide = w.findComponent(SlideToConfirm)
  for (let i = 0; i < 10; i++) await slide.trigger('keydown', { key: 'ArrowRight' })
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  account.email = 'someone@example.org'
  open.mockResolvedValue(undefined)
  collect.mockResolvedValue({})
  delegate.mockResolvedValue(REFRESH_TOKEN)
  seal.mockResolvedValue(undefined)
})

afterEach(() => vi.useRealTimers())

describe('AddDevicePanel — the trusted half (uc.account.onboard-device, ADR 0059)', () => {
  it('shows a code that carries the channel’s secret and nothing else — and opens the channel under its handle', async () => {
    const w = mountPanel()

    await showCode(w)

    const secret = parsePairingPayload(w.find('.qr').text())!
    expect(w.find('.qr').text()).toBe(`veyqpair:${secret}`)
    // Only the handle the secret derives reaches the server — never the secret, never the channel key (§11).
    expect(open).toHaveBeenCalledExactlyOnceWith(channelKeys(secret).handle)
    expect(open.mock.calls[0][0]).not.toBe(secret)
  })

  it('shows the same check number the answering device derives, once the answer lands', async () => {
    const w = mountPanel()
    await showCode(w)
    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)

    await answerArrives(w)

    expect(w.find('[data-testid="check-number"]').text()).toBe(checkNumber(transport.pub))
  })

  // D8 row 1, the positive control: the numbers match, the confirmation is carried out, and BOTH calls happen —
  // in the only order that can work, since the token being sealed is what `delegate` hands back.
  it('delegates and then seals when the confirmation is carried out', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await nameAndSlide(w)

    const order = [delegate, seal].map((fn) => fn.mock.invocationCallOrder[0])
    expect(delegate).toHaveBeenCalledExactlyOnceWith(open.mock.calls[0][0])
    expect(seal).toHaveBeenCalledTimes(1)
    expect(order[0]).toBeLessThan(order[1])
    expect(w.find('.msg').text()).toBe(t('pairing.sealed'))
  })

  it('seals the account’s Master Key, the delegated token and the typed name to the answering key', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await nameAndSlide(w, 'Arbeitslaptop')

    const [handle, envelope] = seal.mock.calls[0]
    expect(handle).toBe(open.mock.calls[0][0])
    // Opened with the private half the answering device kept — the only proof that the envelope went to the
    // key whose number was on screen, rather than to some other key the panel happened to hold.
    expect(openGrant(shownChannel().key, transport.priv, envelope)).toEqual({
      email: 'someone@example.org',
      masterKey: MASTER_KEY,
      refreshToken: REFRESH_TOKEN,
      label: 'Arbeitslaptop',
    })
  })

  // D8 row 1, the negative: declining the comparison must not reach the wire at all. Anchored on the two calls
  // themselves — "an error was shown" is produced equally well by a broken form.
  it('grants nothing when the numbers do not match', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    await buttonLabelled(w, t('pairing.mismatch'))!.trigger('click')
    await flushPromises()

    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)
    expect(buttonLabelled(w, t('pairing.start'))).toBeTruthy() // back at the start, not at a retry
  })

  // D8 row 2: E2 abandons the channel rather than retrying it in place. The declined handle must not come back.
  it('mints a NEW handle after a declined comparison, and that ceremony completes normally', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)
    const abandoned = open.mock.calls[0][0]

    await buttonLabelled(w, t('pairing.mismatch'))!.trigger('click')
    await flushPromises()
    // The abandoned channel's answer is not the new one's: MACed under the old channel key, it would be refused.
    collect.mockResolvedValue({})
    await showCode(w)

    const restarted = open.mock.calls[1][0]
    expect(restarted).not.toBe(abandoned)

    // The control on the same predicate: the restart is a working ceremony, not merely a different string.
    await answerArrives(w)
    await nameAndSlide(w)
    expect(delegate).toHaveBeenCalledExactlyOnceWith(restarted)
    expect(seal.mock.calls[0][0]).toBe(restarted)
  })

  // D7 / D8 row 4, at the altitude that matters: the whole screen, after a real seal.
  it('never reads the channel again once it has the answer — the seal is not followed by a collect', async () => {
    const w = mountPanel()
    await showCode(w)
    const beforeTheAnswer = collect.mock.calls.length
    expect(beforeTheAnswer).toBeGreaterThanOrEqual(1) // the control: it was polling before

    await answerArrives(w)
    await nameAndSlide(w)
    expect(seal).toHaveBeenCalledTimes(1)
    const atTheSeal = collect.mock.calls.length

    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()

    expect(collect).toHaveBeenCalledTimes(atTheSeal)
  })

  // D9: every terminal state answers 404 and the client cannot tell them apart, so it says one thing and
  // offers a fresh start rather than a retry of a channel that is gone.
  it('shows one terminal message on a 404 and stops polling', async () => {
    const w = mountPanel()
    await showCode(w)

    collect.mockRejectedValue(httpError(404))
    await pollOnce()
    await w.vm.$nextTick()

    expect(w.find('.msg').text()).toBe(t('pairing.gone'))
    const atTheEnd = collect.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(collect).toHaveBeenCalledTimes(atTheEnd)
  })

  // D8 row 5, this side of it: the name is sealed into the envelope and NOT written here. The device it names
  // writes it itself, on its own first unlock, which is what keeps the register single-writer.
  it('writes no register entry for the device it grants — it only seals the name', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)
    await nameAndSlide(w, 'Arbeitslaptop')

    // The control on the same predicate: the ceremony really completed, so "nothing was written" is not
    // satisfied by a run that never got as far as granting anything.
    expect(seal).toHaveBeenCalledTimes(1)
    expect(openGrant(shownChannel().key, transport.priv, seal.mock.calls[0][1]).label).toBe('Arbeitslaptop')

    expect(rememberThisDevice).not.toHaveBeenCalled()
    expect(rememberThisDeviceWrap).not.toHaveBeenCalled()
  })

  it('cannot be confirmed before the device is named', async () => {
    const w = mountPanel()
    await showCode(w)
    await answerArrives(w)

    const slide = w.findComponent(SlideToConfirm)
    for (let i = 0; i < 10; i++) await slide.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()

    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()

    // The control: the same slide, once a name is there.
    await nameAndSlide(w)
    expect(seal).toHaveBeenCalledTimes(1)
  })
})

describe('AddDevicePanel — the scanned arm (sign in with a QR code, ADR 0059 §6/§7, D9)', () => {
  it('scans only this direction’s marker, and never the code it shows itself', async () => {
    const w = mountPanel()
    await buttonLabelled(w, t('pairing.scanStart'))!.trigger('click')

    const accepts = w.findComponent({ name: 'KitScanner' }).props('accepts') as (s: string) => boolean
    expect(accepts(signInPayload(SCANNED_SECRET))).toBe(true)
    // A `veyqpair:` code is one THIS device showed. Accepting it here would have the panel pair with itself —
    // and on the sign-in card the mirror-image mis-scan burns the write-once answer slot (D1).
    expect(accepts(pairingPayload(SCANNED_SECRET))).toBe(false)
    expect(accepts('veyqkit:whatever')).toBe(false)
    // The negative on the same predicate: a foreign QR is scanned PAST, not reported as a failure.
    expect(accepts('https://example.com/')).toBe(false)
  })

  it('states what is being granted with the code, and shows no comparison number', async () => {
    const w = mountPanel()
    await scanCode(w)

    expect(shownCode(w)).toMatch(/^\d{6}$/)
    // D9's "information early": the consequence is on screen from the moment the code is, long before the
    // gesture that commits to it.
    expect(w.text()).toContain(t('pairing.consequence'))
    // ADR 0059 §5 — comparing is theatre where the attacker authored one of the two screens, so this direction
    // shows no check number at all. The other arm's own number is the control that the element exists.
    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)
    // And nothing is committed yet: the slide is not even on screen.
    expect(w.findComponent(SlideToConfirm).exists()).toBe(false)
  })

  // --- The two gates, separately. Either alone satisfies "no envelope was posted". ---

  it('GATE 1 — a wrong proof does not so much as offer the slide, and reaches neither delegate nor seal', async () => {
    const w = mountPanel()
    await scanCode(w)
    const shown = shownCode(w)

    await proofArrives(w, someOtherCode(shown))

    // Not merely "an error was shown": the gesture is never offered, so there is nothing for the user to
    // complete past the one signal they have (§7).
    expect(w.findComponent(SlideToConfirm).exists()).toBe(false)
    expect(w.find('input').exists()).toBe(false) // not even the name field
    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
    expect(w.text()).toContain(t('pairing.scanRefused'))
    // Terminal, and deliberately so: the answer slot is write-once, so there is no second proof to accept.
    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()
    expect(seal).not.toHaveBeenCalled()
  })

  it('GATE 2 — a VERIFIED proof whose slide is left untouched still seals nothing', async () => {
    const w = mountPanel()
    await scanCode(w)
    const shown = shownCode(w)

    await proofArrives(w, shown)

    // The control that this case is about the slide and not about a refusal: the proof HELD.
    expect(w.text()).toContain(t('pairing.scanProven'))
    expect(w.findComponent(SlideToConfirm).exists()).toBe(true)
    // Named, and still not confirmed — the label gates the gesture, it does not replace it.
    await w.find('input').setValue('Kevin’s laptop')
    await flushPromises()

    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
  })

  it('the completed ceremony delegates and seals exactly once — the live control on both gates', async () => {
    const w = mountPanel()
    const handle = await scanCode(w)
    const shown = shownCode(w)
    const answering = await proofArrives(w, shown)
    const readsBeforeSeal = collect.mock.calls.length
    expect(readsBeforeSeal).toBeGreaterThanOrEqual(1) // the control: it really was polling

    await nameAndSlide(w)

    expect(delegate).toHaveBeenCalledTimes(1)
    expect(seal).toHaveBeenCalledTimes(1)
    expect(delegate.mock.calls[0][0]).toBe(handle)
    expect(seal.mock.calls[0][0]).toBe(handle)

    // Sealed to the key that carried the proof, and to no other. Anchored on a real second key failing to open
    // a real envelope — not on "it threw", which a malformed input produces just as well.
    const envelope = seal.mock.calls[0][1]
    const opened = openGrant(SCANNED.key, answering.priv, envelope)
    expect(opened.masterKey).toEqual(MASTER_KEY)
    expect(opened.refreshToken).toBe(REFRESH_TOKEN)
    expect(opened.label).toBe('Küchen-Tablet')
    expect(() => openGrant(SCANNED.key, mintTransportKey().priv, envelope)).toThrow(v1.OpenRefused)

    // The channel is never read again after the seal: a collect from ANY caller returns the envelope and marks
    // the row COLLECTED, so this device would eat its own grant and the other one would wait forever.
    await vi.advanceTimersByTimeAsync(600_000)
    await flushPromises()
    expect(collect).toHaveBeenCalledTimes(readsBeforeSeal)
    expect(w.text()).toContain(t('pairing.sealed'))
  })

  it('an unreadable answer is the ordinary terminal state, and seals nothing', async () => {
    const w = mountPanel()
    await scanCode(w)
    // A bare key with no proof at all — not a shape this client writes. Nothing can be sealed against it.
    collect.mockResolvedValue({ answer: b64std(v1.serializeIdentityPublic(mintTransportKey().pub)) })
    await pollOnce()
    await w.vm.$nextTick()

    expect(w.text()).toContain(t('pairing.gone'))
    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
  })

  it('starting over after a refusal mints a fresh code, never resuming the spent channel', async () => {
    const w = mountPanel()
    await scanCode(w)
    const first = shownCode(w)
    await proofArrives(w, someOtherCode(first))

    await buttonLabelled(w, t('pairing.again'))!.trigger('click')
    await flushPromises()

    // Back at the scanner, with nothing carried over — a resumed channel is exactly what E2 forbids.
    expect(w.findComponent({ name: 'KitScanner' }).exists()).toBe(true)
    expect(w.find('[data-testid="pairing-code"]').exists()).toBe(false)
    expect(seal).not.toHaveBeenCalled()
  })
})

// ADR 0059 §11 — the relay is the one party that sees both halves of each binding step. Each relay below is a
// test double that knows ONLY what crossed the channel (the handle, the honest answer) and posts in the honest
// device's place. The completed ceremonies above are the live controls.
describe('AddDevicePanel — a relay that substitutes its own key (ADR 0059 §11)', () => {
  it('SIGN IN — a relay that grinds the code off the honest answer and proves it for its own key seals nothing', async () => {
    const w = mountPanel()
    await scanCode(w)
    const shown = shownCode(w)
    const honest = mintTransportKey()
    const honestAnswer = signInAnswer(SCANNED.key, honest.pub, shown)

    // The relay's lever: a candidate list that contains the right code (a list keeps the test fast; the full
    // 10⁶ is the same loop, longer), tried against what crossed it.
    const candidates = Array.from({ length: 2_000 }, (_, i) => String((Number(shown) + 997 * i) % 1_000_000).padStart(6, '0'))
    const relay = mintTransportKey()
    collect.mockResolvedValue({ answer: relayForgesASignInAnswer(honestAnswer, candidates, relay.pub) })
    await pollOnce()
    await w.vm.$nextTick()

    // Refused on the answer, before the name field or the slide exists: there is nothing to confirm past.
    expect(w.text()).toContain(t('pairing.scanRefused'))
    expect(w.findComponent(SlideToConfirm).exists()).toBe(false)
    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
  })

  it('ADD A DEVICE — a relay that grinds its own bundle to the honest check number is refused before any number is shown', async () => {
    const w = mountPanel()
    await showCode(w)
    const honest = mintTransportKey()
    const honestAnswer = transportAnswer(shownChannel().key, honest.pub)

    const relay = mintTransportKey()
    const forged = relayGrindsTheCheckNumber(honestAnswer, relay.pub)
    // The lever is wired: the relay's bundle really does show the honest device's number.
    expect(checkNumber(forged.pub)).toBe(checkNumber(honest.pub))

    collect.mockResolvedValue({ answer: forged.answer })
    await pollOnce()
    await w.vm.$nextTick()

    expect(w.find('[data-testid="check-number"]').exists()).toBe(false)
    expect(w.findComponent(SlideToConfirm).exists()).toBe(false)
    expect(w.text()).toContain(t('pairing.answerRefused'))
    expect(delegate).not.toHaveBeenCalled()
    expect(seal).not.toHaveBeenCalled()
  })
})

const PAIRING_CODE_DOMAIN = v1.utf8('veyq/v1/pairing-code')

/** Split an answer the way the relay can: `lp(first) || lp(second)`. */
function splitAnswer(answer: string): { first: Uint8Array; second: Uint8Array } {
  const bytes = fromB64std(answer)
  const len = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(0, false)
  return { first: bytes.slice(4, 4 + len), second: bytes.slice(8 + len) }
}

/**
 * The sign-in relay: grind every candidate code against the proof that crossed it, with the one function of
 * (key, code) anybody can compute — and post its own key with whatever it can make of that. Where the grind
 * finds nothing, the best it has is the honest proof, replayed beside its own key.
 */
function relayForgesASignInAnswer(honestAnswer: string, candidates: string[], relayPub: v1.Identity['pub']): string {
  const { first: honestKey, second: honestProof } = splitAnswer(honestAnswer)
  const unkeyed = (key: Uint8Array, code: string) =>
    v1.sha256Digest(v1.concat(PAIRING_CODE_DOMAIN, v1.lp(key), v1.utf8(code)))
  const ground = candidates.find((c) => b64std(unkeyed(honestKey, c)) === b64std(honestProof)) ?? null
  const relayKey = v1.serializeIdentityPublic(relayPub)
  const proof = ground === null ? honestProof : unkeyed(relayKey, ground)
  return b64std(v1.concat(v1.lp(relayKey), v1.lp(proof)))
}

/**
 * The add-a-device relay: vary the Ed25519 half of its own bundle — which the wrap never uses — until its
 * check number is the honest device's, read off the honest answer. About 10⁵ hashes (measured 0.5–2 s here, so
 * the grind runs in full rather than from a known-good seed). It keeps the honest answer's MAC beside its own key:
 * that is the best it can do without the channel key.
 */
function relayGrindsTheCheckNumber(honestAnswer: string, relayPub: v1.Identity['pub']): { pub: v1.Identity['pub']; answer: string } {
  const { first: honestKey, second: honestMac } = splitAnswer(honestAnswer)
  const target = checkNumber(v1.deserializeIdentityPublic(honestKey))
  const sign = new Uint8Array(32)
  const view = new DataView(sign.buffer)
  for (let i = 0; ; i++) {
    view.setUint32(0, i, false)
    const pub = { ...relayPub, sign }
    if (checkNumber(pub) === target) {
      const found = { ...relayPub, sign: sign.slice() }
      return { pub: found, answer: b64std(v1.concat(v1.lp(v1.serializeIdentityPublic(found)), v1.lp(honestMac))) }
    }
  }
}
