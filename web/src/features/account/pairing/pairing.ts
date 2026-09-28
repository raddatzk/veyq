// uc.account.onboard-device (web, ADR 0059) — the client half of the pairing ceremony: what the displayed code
// carries, what the answering device puts on the wire, the number both screens compare, and the one envelope the
// grant travels in. Operator-blind throughout: the server relays a routing handle it can only recognise by hash,
// one public bundle it cannot attribute, and one ciphertext it cannot open.
//
// **The relay is the one party that sees both halves of each binding step (ADR 0059 §11).** So the displayed code
// carries a 32-byte secret the relay never sees, and both devices derive from it the handle (the only value the
// server receives) and a channel key. Both values the relay carries — the answer and the sealed grant — travel
// with a MAC under that key, and every reader below verifies it before it hands anything back. The codes of §5
// and the channel key defend against different parties: the codes stop someone who is not the relay (a
// photographed QR, a phisher's QR), the channel key stops the relay. Neither replaces the other, and an attacker
// who both reads the QR and runs the relay holds the channel key — nothing here claims otherwise.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { TrustMarks } from '../trustMarks'

/** The scanned code's marker. It exists ONLY so one scanner can dispatch between this and the Emergency Kit's
 *  `veyqkit:` — it is deliberately not a URL scheme, and ADR 0059 §4 is the reason: a code that could name an
 *  endpoint would redirect a trusted device's seal to a server the attacker runs, with no password and no
 *  session needed. */
const PREFIX = 'veyqpair:'

/**
 * The *sign in with a QR code* direction's marker (D1). Its payload is byte-identical to the one above — marker
 * plus secret — and that is exactly why it needs its own marker rather than sharing one.
 *
 * Without the distinction a mis-scan is silent AND destructive: the sign-in card's scan entry would read a
 * sign-in code as a pairing one, mount the join card, and post an answer into the slot. That slot is write-once on
 * the server, so the real ceremony is burned before anyone sees a wrong screen. The two scanners therefore refuse
 * each other's marker, and each refusal is a scan the camera keeps looking past.
 *
 * The marker is the ONLY direction signal that exists anywhere, and it is deliberately client-side: the server
 * stores the handle's SHA-256 and nothing else, so it still cannot tell the two ceremonies apart.
 */
const SIGNIN_PREFIX = 'veyqsignin:'

/** Six digits. Long enough that one online guess against a ten-minute channel is not a threat, short enough to
 *  read off one screen and type into another without a second look. */
const CODE_DIGITS = 6

/** Rejection ceiling for the digit draw: 250 = 25 x 10, so every byte below it maps to a digit uniformly.
 *  Taking `% 10` over the full byte range instead would make 0-5 likelier than 6-9 — a bias small enough to
 *  never show up in a test that only checks the shape. */
const CODE_REJECT_AT = 250

/** Seal context of the grant. Purpose only (ADR 0072 Decision 2): the KEK is derived per single-use ephemeral key,
 *  so the key is the binding; the ciphertext is opened within the channel's ten minutes and nothing survives it,
 *  so a drift between two client versions fails LOUDLY at `openAs` rather than silently. */
const PAIRING_GRANT = { purpose: 'pairing-grant' } as const

const SECRET_BYTES = 32
const decoder = new TextDecoder()

/**
 * What the trusted device seals and the onboarded device opens (ADR 0059 §2, §9).
 *
 * `email` is carried because the onboarded device never types one and **no authenticated read hands the
 * caller's own address back** — without it that device cannot key its `DEVICE_KEY` record, its session vault,
 * or write moment 1's local wrap lookup, all of which are keyed by email. It is no leak: this envelope is
 * sealed to a key only the granted device holds, and that device is being handed the Master Key.
 *
 * `marks` are the linking device's trust-state marks (ADR 0028 Decision 7): the new device starts from them, so the
 * server cannot hand it an older pin store, register or scope epoch than the device that vouched for it had held.
 * They are read only out of an envelope whose MAC verified — {@link openGrant} checks it before it opens anything.
 * Absent from an envelope sealed by a client that predates them, which inherits nothing.
 */
export type PairingGrant = {
  email: string
  masterKey: Uint8Array
  refreshToken: string
  label: string
  marks?: TrustMarks
}

/**
 * Why a value that crossed the relay was refused. Each is its own name, so a caller — and a test — can tell a
 * value that does not parse from one that parses and does not verify:
 *   - `answer_unreadable` / `envelope_unreadable`: not the shape this client writes (not ours, whoever sent it);
 *   - `answer_refused`: the MAC does not verify — the relay substituted the key, or (signing in with a QR code)
 *     the transcribed code is not the one displayed. The two are indistinguishable on purpose; either way nothing
 *     may be sealed to that key;
 *   - `envelope_refused`: the MAC does not verify — a grant someone other than the trusted device sealed to this
 *     device's public key, which anyone can do.
 */
export type PairingRefusal = 'answer_unreadable' | 'answer_refused' | 'envelope_unreadable' | 'envelope_refused'

export class PairingRefused extends Error {
  constructor(readonly reason: PairingRefusal) {
    super(`pairing_refused: ${reason}`)
    this.name = 'PairingRefused'
  }
}

/** The channel as both devices derive it from the displayed code's secret: the handle the server routes by, and
 *  the key it never receives. */
export type PairingChannelKeys = { handle: string; key: Uint8Array }

/**
 * Mint the secret the displayed code carries (ADR 0059 §11): 32 random bytes the server never receives.
 *
 * Standard base64 because the scanner validates against the contract's `[A-Za-z0-9+/]*={0,2}`. That alphabet is
 * why the loop is here: a secret whose encoding starts with `/` makes the payload read `veyqpair://…`, which is
 * URL-shaped — precisely the reading ADR 0059 §4 exists to keep impossible, and a scanner or OS that linkifies
 * it would act on that reading. Re-minting is the cheap fix. (`veyqkit:` never met this because it encodes
 * base64**url**.) Both markers take their secret from here and no other draw, which is what carries the loop over
 * to `veyqsignin:`.
 */
export function mintPairingSecret(): string {
  let secret = b64std(v1.randomBytes(SECRET_BYTES))
  while (secret.startsWith('/')) secret = b64std(v1.randomBytes(SECRET_BYTES))
  return secret
}

/** Derive the channel from the displayed code's secret. The handle goes on the wire in standard base64 as it
 *  always has; the key stays on the two devices. */
export function channelKeys(secret: string): PairingChannelKeys {
  const s = fromB64std(secret)
  return { handle: b64std(v1.pairingHandle(s)), key: v1.pairingChannelKey(s) }
}

/**
 * Read a secret out of a scanned string under one specific marker, or `null`. Anchored at both ends on purpose:
 * the payload IS the whole string, so nothing can ride along after the secret, and a code carrying a host, a
 * path or a query never parses as one of ours (ADR 0059 §4). A payload that is not exactly 32 bytes is not one
 * this client minted.
 *
 * Both directions share this reader so the two markers cannot drift into two different notions of "a payload of
 * ours" — but each anchors on its OWN prefix, so neither ever accepts the other's code. That refusal is
 * load-bearing, not tidiness: see [SIGNIN_PREFIX].
 */
function parseMarked(prefix: string, text: string): string | null {
  const m = new RegExp(`^${prefix}([A-Za-z0-9+/]+={0,2})$`).exec(text.trim())
  if (!m) return null
  try {
    return fromB64std(m[1]).length === SECRET_BYTES ? m[1] : null
  } catch {
    return null
  }
}

/** The displayed code's whole payload in *add a device*: the marker and the secret, nothing else (§4, §11). */
export const pairingPayload = (secret: string): string => PREFIX + secret

/** Read an *add a device* secret out of a scanned string — which is also how the sign-in card's one scan entry
 *  dispatches between this and an Emergency Kit (D6). */
export const parsePairingPayload = (text: string): string | null => parseMarked(PREFIX, text)

/**
 * The displayed code's whole payload in *sign in with a QR code* (D1): the second marker and the secret, and
 * still no key — a serialized transport bundle is 1260 bytes, which is a QR version 28 nobody scans off a
 * laptop screen and which sits past the decoder bound `web/CLAUDE.md`'s jsQR exception rests on.
 *
 * Nothing is lost by relaying the key instead: the relayed key is MACed under the channel key this payload's
 * secret derives, so the relay cannot swap it, and the transcribed code bound into the same MAC is what
 * authenticates it against a phisher who authored the QR (ADR 0059 §5).
 */
export const signInPayload = (secret: string): string => SIGNIN_PREFIX + secret

/** Inverse of [signInPayload], and the predicate the trusted device's scanner accepts on. */
export const parseSignInPayload = (text: string): string | null => parseMarked(SIGNIN_PREFIX, text)

/**
 * The pairing code the trusted device displays and the account holder types into the new one (ADR 0059 §5).
 *
 * **Freshly drawn, and deliberately not derived from any key.** §5's whole point is that anything derivable
 * from the scanned QR is already the attacker's — they authored it — so `fingerprint`/`checkNumber` are the one
 * thing this direction may NOT reuse. For the same reason this direction shows no comparison number on either
 * screen: comparing is theatre where the attacker wrote one of the two screens.
 *
 * Six digits against exactly ONE attempt inside the channel's ten minutes (D4): there is no second guess to
 * grind, because the answer slot is write-once and no status can carry a refusal back — and the relay, which
 * could grind it offline, sees only a MAC under a key it does not hold (§11).
 *
 * **What it must never become:** the code must not be mixed into the envelope's encryption (ADR 0059 §6). That
 * would hand the holder of the ephemeral private key — the attacker, in the case this defends — a six-digit
 * offline search. The code gates the SEAL on the trusted device and gates nothing on the server.
 */
export function mintPairingCode(): string {
  let code = ''
  while (code.length < CODE_DIGITS) {
    for (const b of v1.randomBytes(CODE_DIGITS)) {
      if (b >= CODE_REJECT_AT) continue
      code += String(b % 10)
      if (code.length === CODE_DIGITS) break
    }
  }
  return code
}

/**
 * The onboarded device's single-use transport key: a full identity bundle rather than a bare hybrid pair, so
 * the wire serialization AND the digest input are the ones already pinned (`serializeIdentityPublic`,
 * `fingerprint`). The Ed25519 half is unused by the wrap and exists for exactly that reason — a bare hybrid
 * pair would save 36 bytes and owe two new pinned forms. (That unused half is also what the relay would vary to
 * grind the check number; the answer's MAC is why that no longer buys it anything.)
 */
export const mintTransportKey = (): v1.Identity => v1.generateIdentity()

/** `lp(first) || lp(second)`, consumed EXACTLY — trailing bytes are not a tolerable extra. */
function readPair(bytes: Uint8Array, reason: PairingRefusal): [Uint8Array, Uint8Array] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let o = 0
  const next = (): Uint8Array => {
    if (o + 4 > bytes.length) throw new PairingRefused(reason)
    const len = view.getUint32(o, false)
    o += 4
    if (o + len > bytes.length) throw new PairingRefused(reason)
    const field = bytes.slice(o, o + len)
    o += len
    return field
  }
  const first = next()
  const second = next()
  if (o !== bytes.length) throw new PairingRefused(reason)
  return [first, second]
}

const decodeB64 = (value: string, reason: PairingRefusal): Uint8Array => {
  try {
    return fromB64std(value)
  } catch {
    throw new PairingRefused(reason)
  }
}

/**
 * The answer as it goes on the wire, in BOTH directions:
 *
 * ```
 * answer = b64std( lp(serializeIdentityPublic(pub)) || lp(mac) )
 * ```
 *
 * One shape and one length for both, so the relay cannot tell the ceremonies apart. What keeps the two
 * directions' answers from passing for each other is the MAC's label (`pairing-answer` vs
 * `pairing-sign-in-answer`), not the shape — so a sign-in answer read as an add-a-device one is refused on its
 * MAC, and the other way round.
 */
const encodeAnswer = (serializedPub: Uint8Array, mac: Uint8Array): string =>
  b64std(v1.concat(v1.lp(serializedPub), v1.lp(mac)))

/** Read an answer's key and MAC. The key must re-serialize to exactly the bytes the MAC covers, so the key a grant
 *  is sealed to is byte for byte the key that was authenticated. */
function readAnswer(answer: string): { pub: v1.Identity['pub']; serializedPub: Uint8Array; mac: Uint8Array } {
  const [serializedPub, mac] = readPair(decodeB64(answer, 'answer_unreadable'), 'answer_unreadable')
  let pub: v1.Identity['pub']
  try {
    pub = v1.deserializeIdentityPublic(serializedPub)
  } catch {
    throw new PairingRefused('answer_unreadable')
  }
  const again = v1.serializeIdentityPublic(pub)
  if (again.length !== serializedPub.length || again.some((b, i) => b !== serializedPub[i])) {
    throw new PairingRefused('answer_unreadable')
  }
  return { pub, serializedPub, mac }
}

/** The *add a device* answer: the new device's transport key, MACed under the channel key (ADR 0059 §11). */
export function transportAnswer(key: Uint8Array, pub: v1.Identity['pub']): string {
  const serializedPub = v1.serializeIdentityPublic(pub)
  return encodeAnswer(serializedPub, v1.pairingMac(key, { purpose: 'pairing-answer', transportKey: serializedPub }))
}

/**
 * The trusted device's gate in *add a device*: the key to seal to, or [PairingRefused]. A refused MAC means the
 * relay (or anyone else without the QR's secret) answered — refused BEFORE a check number exists, so the relay's
 * grind towards the honest number has nothing left to buy.
 */
export function readTransportAnswer(key: Uint8Array, answer: string): v1.Identity['pub'] {
  const { pub, serializedPub, mac } = readAnswer(answer)
  if (!v1.pairingMacVerifies(key, { purpose: 'pairing-answer', transportKey: serializedPub }, mac)) {
    throw new PairingRefused('answer_refused')
  }
  return pub
}

/**
 * The *sign in with a QR code* answer: the key AND its proof of the code, as the channel's ONE relayed value (D2).
 * `PairingChannel` relays exactly `answer` and `envelope`, and `sealGrant` takes no code and never may
 * (ADR 0059 §6) — so the two travel together or not at all. The proof is a MAC under the channel key, so the
 * relay, which reads it, cannot grind the code out of it: it does not hold the key the grind would need.
 */
export function signInAnswer(key: Uint8Array, pub: v1.Identity['pub'], code: string): string {
  const serializedPub = v1.serializeIdentityPublic(pub)
  return encodeAnswer(
    serializedPub,
    v1.pairingMac(key, { purpose: 'pairing-sign-in-answer', transportKey: serializedPub, code }),
  )
}

/**
 * The trusted device's whole gate in *sign in with a QR code* (ADR 0059 §6, §11): the key to seal to, or
 * [PairingRefused]. `code` is the one this device drew and displayed. A wrong transcription and a substituted key
 * are refused alike, as `answer_refused` — there is exactly one attempt, and either way nothing may be sealed.
 */
export function readSignInAnswer(key: Uint8Array, code: string, answer: string): v1.Identity['pub'] {
  const { pub, serializedPub, mac } = readAnswer(answer)
  if (!v1.pairingMacVerifies(key, { purpose: 'pairing-sign-in-answer', transportKey: serializedPub, code }, mac)) {
    throw new PairingRefused('answer_refused')
  }
  return pub
}

/**
 * The short number both screens show (ADR 0059 §5): the first group of the answering key's safety-number
 * digest — the vector-pinned derivation of ADR 0028 plus a display rule, so nothing new is pinned here.
 *
 * Five digits are enough for what this compare defends: the photographed-QR race, where someone who is not the
 * relay answers the channel the intended device could not. They never see the intended device's number, so
 * there is nothing to grind towards. **The relay did see it** — it carries the honest answer — and five digits
 * are about 10⁵ hashes of grinding the unused Ed25519 half; that is why the answer's MAC is checked before this
 * number is ever shown (ADR 0059 §11), not a reason to lengthen the number.
 */
export const checkNumber = (pub: v1.Identity['pub']): string => v1.fingerprint(pub).split(' ')[0]

/**
 * Seal the grant to the answering device's transport key (ADR 0059 §3), and MAC the sealed bytes under the
 * channel key (§11). Versioned utf8 JSON, mirroring how the identity pins and the device register seal theirs.
 *
 * ```
 * envelope = b64std( lp(sealTo(pairing-grant, pub, json)) || lp(mac) )
 * ```
 */
export function sealGrant(key: Uint8Array, pub: v1.Identity['pub'], grant: PairingGrant): string {
  const plaintext = v1.utf8(
    JSON.stringify({
      v: 1,
      email: grant.email,
      masterKey: b64std(grant.masterKey),
      refreshToken: grant.refreshToken,
      label: grant.label,
      ...(grant.marks ? { marks: grant.marks } : {}),
    }),
  )
  const sealed = v1.sealTo(PAIRING_GRANT, { x: pub.kemX, m: pub.kemM }, plaintext)
  return b64std(v1.concat(v1.lp(sealed), v1.lp(v1.pairingMac(key, { purpose: 'pairing-envelope', envelope: sealed }))))
}

/**
 * Open the sealed grant: verify its MAC under the channel key FIRST, then open it with the transport private
 * bundle. Anyone can seal to this device's public key — the relay read it off the answer — so a seal that opens
 * proves nothing about who made it; only the MAC does (ADR 0059 §11). Throws [PairingRefused] for an envelope that
 * is not ours by shape or by MAC, `OpenRefused` on a tag mismatch, and `pairing_grant_unreadable` for a body or
 * version this client does not write — each of which is the same thing to the caller: this envelope is not ours.
 */
export function openGrant(key: Uint8Array, priv: v1.Identity['priv'], envelope: string): PairingGrant {
  const [sealed, mac] = readPair(decodeB64(envelope, 'envelope_unreadable'), 'envelope_unreadable')
  if (!v1.pairingMacVerifies(key, { purpose: 'pairing-envelope', envelope: sealed }, mac)) {
    throw new PairingRefused('envelope_refused')
  }
  const plaintext = v1.openAs(PAIRING_GRANT, v1.hybridSecret(priv), v1.hybridPublic(priv), sealed)
  const o = JSON.parse(decoder.decode(plaintext)) as Partial<Record<keyof PairingGrant | 'v', unknown>>
  if (o.v !== 1 || typeof o.email !== 'string' || typeof o.masterKey !== 'string'
    || typeof o.refreshToken !== 'string' || typeof o.label !== 'string') {
    throw new Error('pairing_grant_unreadable')
  }
  const marks = o.marks === undefined ? undefined : readMarksField(o.marks)
  return {
    email: o.email,
    masterKey: fromB64std(o.masterKey),
    refreshToken: o.refreshToken,
    label: o.label,
    ...(marks ? { marks } : {}),
  }
}

const isMark = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0

/** The envelope's marks, exactly in the shape this client writes — anything else is not our envelope, and is
 *  refused like any other unreadable body rather than half-read. */
function readMarksField(raw: unknown): TrustMarks {
  const m = raw as Partial<Record<keyof TrustMarks, unknown>> | null
  if (!m || typeof m !== 'object' || !isMark(m.pins) || !isMark(m.register) || !m.epochs || typeof m.epochs !== 'object') {
    throw new Error('pairing_grant_unreadable')
  }
  const epochs: Record<string, number> = {}
  for (const [scopeId, e] of Object.entries(m.epochs as Record<string, unknown>)) {
    if (!isMark(e)) throw new Error('pairing_grant_unreadable')
    epochs[scopeId] = e
  }
  return { pins: m.pins, register: m.register, epochs }
}
