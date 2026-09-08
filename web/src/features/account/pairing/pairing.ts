// uc.account.onboard-device (web, ADR 0059) — the client half of the pairing ceremony: what the displayed code
// carries, what the answering device puts on the wire, the number both screens compare, and the one envelope the
// grant travels in. Operator-blind throughout: the server relays a routing handle it can only recognise by hash,
// one public bundle it cannot attribute, and one ciphertext it cannot open.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** The scanned code's marker. It exists ONLY so one scanner can dispatch between this and the Emergency Kit's
 *  `veyqkit:` — it is deliberately not a URL scheme, and ADR 0059 §4 is the reason: a code that could name an
 *  endpoint would redirect a trusted device's seal to a server the attacker runs, with no password and no
 *  session needed. */
const PREFIX = 'veyqpair:'

/**
 * The *sign in with a QR code* direction's marker (D1). Its payload is byte-identical to the one above — marker
 * plus handle — and that is exactly why it needs its own marker rather than sharing one.
 *
 * Without the distinction a mis-scan is silent AND destructive: the sign-in card's scan entry would read a
 * sign-in code as a pairing one, mount the join card, and post a bare transport key into the answer slot. That
 * slot is write-once on the server, so the real ceremony is burned before anyone sees a wrong screen. The two
 * scanners therefore refuse each other's marker, and each refusal is a scan the camera keeps looking past.
 *
 * The marker is the ONLY direction signal that exists anywhere, and it is deliberately client-side: the server
 * stores the handle's SHA-256 and nothing else, so it still cannot tell the two ceremonies apart.
 */
const SIGNIN_PREFIX = 'veyqsignin:'

/** Domain separation of the pairing-code commitment (D3), in `fingerprint`'s own shape (`v1.ts`). */
const PAIRING_CODE = 'veyq/v1/pairing-code'

/** Six digits. Long enough that one online guess against a ten-minute channel is not a threat, short enough to
 *  read off one screen and type into another without a second look. */
const CODE_DIGITS = 6

/** Rejection ceiling for the digit draw: 250 = 25 x 10, so every byte below it maps to a digit uniformly.
 *  Taking `% 10` over the full byte range instead would make 0-5 likelier than 6-9 — a bias small enough to
 *  never show up in a test that only checks the shape. */
const CODE_REJECT_AT = 250

/** Wrap context of the sealed grant. Its own domain, shared with no sibling: the KEK is derived per single-use
 *  ephemeral key, the ciphertext is opened within the channel's ten minutes and nothing survives it, so a drift
 *  between two client versions fails LOUDLY at `asymUnwrap` rather than silently. */
const PAIRING_GRANT = 'pairing-grant'

const HANDLE_BYTES = 32
const decoder = new TextDecoder()

/**
 * What the trusted device seals and the onboarded device opens (ADR 0059 §2, §9).
 *
 * `email` is carried because the onboarded device never types one and **no authenticated read hands the
 * caller's own address back** — without it that device cannot key its `DEVICE_KEY` record, its session vault,
 * or write moment 1's local wrap lookup, all of which are keyed by email. It is no leak: this envelope is
 * sealed to a key only the granted device holds, and that device is being handed the Master Key.
 */
export type PairingGrant = {
  email: string
  masterKey: Uint8Array
  refreshToken: string
  label: string
}

/**
 * Mint the channel's routing handle — a client-minted secret, deliberately **not** a key (ADR 0059 §3): the
 * server stores only its SHA-256, so it recognises the handle at use and can never reproduce it.
 *
 * Standard base64 because the contract pins `[A-Za-z0-9+/]*={0,2}` on the wire. That alphabet is why the loop
 * is here: a handle whose encoding starts with `/` makes the payload read `veyqpair://…`, which is
 * URL-shaped — precisely the reading ADR 0059 §4 exists to keep impossible, and a scanner or OS that linkifies
 * it would act on that reading. Re-minting is the cheap fix; re-encoding is not available, the wire form is
 * pinned. (`veyqkit:` never met this because it encodes base64**url**.)
 */
export function mintHandle(): string {
  let handle = b64std(v1.randomBytes(HANDLE_BYTES))
  while (handle.startsWith('/')) handle = b64std(v1.randomBytes(HANDLE_BYTES))
  return handle
}

/**
 * Read a handle out of a scanned string under one specific marker, or `null`. Anchored at both ends on purpose:
 * the payload IS the whole string, so nothing can ride along after the handle, and a code carrying a host, a
 * path or a query never parses as one of ours (ADR 0059 §4).
 *
 * Both directions share this reader so the two markers cannot drift into two different notions of "a payload of
 * ours" — but each anchors on its OWN prefix, so neither ever accepts the other's code. That refusal is
 * load-bearing, not tidiness: see [SIGNIN_PREFIX].
 */
function parseMarked(prefix: string, text: string): string | null {
  const m = new RegExp(`^${prefix}([A-Za-z0-9+/]+={0,2})$`).exec(text.trim())
  return m ? m[1] : null
}

/** The displayed code's whole payload in *add a device*: the marker and the handle, nothing else (§4). */
export const pairingPayload = (handle: string): string => PREFIX + handle

/** Read an *add a device* handle out of a scanned string — which is also how the sign-in card's one scan entry
 *  dispatches between this and an Emergency Kit (D6). */
export const parsePairingPayload = (text: string): string | null => parseMarked(PREFIX, text)

/**
 * The displayed code's whole payload in *sign in with a QR code* (D1): the second marker and the handle, and
 * still no key — a serialized transport bundle is 1260 bytes, which is a QR version 28 nobody scans off a
 * laptop screen and which sits past the decoder bound `web/CLAUDE.md`'s jsQR exception rests on.
 *
 * Nothing is lost by relaying the key instead. In THIS direction the scan authenticates nothing — the attacker
 * authors the QR, therefore the key it would carry (ADR 0059 §5) — so the out-of-band channel buys no key
 * authenticity here. The transcribed code is what authenticates the key, and [signInAnswer] is what binds them.
 *
 * The handle must come from [mintHandle] and no other draw: its re-mint loop is what keeps a payload from ever
 * reading `veyqsignin://`, and that carries over to this marker only because the handle is minted the same way.
 */
export const signInPayload = (handle: string): string => SIGNIN_PREFIX + handle

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
 * grind, because the answer slot is write-once and no status can carry a refusal back.
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
 * The new device's proof that it knows the displayed code, bound to the key the grant will be sealed to (D3):
 *
 * ```
 * sha256( utf8('veyq/v1/pairing-code') || lp(serializeIdentityPublic(pub)) || utf8(code) )
 * ```
 *
 * The key is length-prefixed so no differently-split (key, code) pair can concatenate to the same input. Every
 * primitive here is already vector-pinned, so this adds no `contracts/crypto` vector — it is a new arrangement
 * of pinned parts, not a new derivation.
 *
 * **What it must never become:** the code must not be mixed into the envelope's encryption (ADR 0059 §6). That
 * would hand the holder of the ephemeral private key — the attacker, in the case this defends — a six-digit
 * offline search. The code gates the SEAL on this device and gates nothing on the server.
 */
export const codeCommitment = (pub: v1.Identity['pub'], code: string): Uint8Array =>
  v1.sha256Digest(v1.concat(v1.utf8(PAIRING_CODE), v1.lp(v1.serializeIdentityPublic(pub)), v1.utf8(code)))

/**
 * Does this commitment prove knowledge of `code` for `pub`? The trusted device's whole gate (ADR 0059 §6).
 *
 * A plain byte comparison, length first: the value compared is public, and an attacker who can time it is
 * already executing on the trusted device.
 *
 * **The length check guards the LONGER case, and only that one** — measured, by deleting it and watching which
 * test stayed green. Iteration runs over `expected`, which is always a 32-byte digest, so a short commitment
 * already fails on `b === undefined`; what slips through without the check is a commitment with bytes appended
 * past the digest, which `readSignInAnswer` will happily hand over because its field is length-prefixed. The
 * intuition this replaced — *"`every` over a shorter array is vacuously true"* — is about iterating the
 * ATTACKER's array, which this does not do; it was wrong here and the bite is what said so.
 */
export function codeProofHolds(pub: v1.Identity['pub'], code: string, commitment: Uint8Array): boolean {
  const expected = codeCommitment(pub, code)
  return commitment.length === expected.length && expected.every((b, i) => b === commitment[i])
}

/**
 * The onboarded device's single-use transport key: a full identity bundle rather than a bare hybrid pair, so
 * the wire serialization AND the digest input are the ones already pinned (`serializeIdentityPublic`,
 * `fingerprint`). The Ed25519 half is unused by the wrap and exists for exactly that reason — a bare hybrid
 * pair would save 36 bytes and owe two new pinned forms.
 */
export const mintTransportKey = (): v1.Identity => v1.generateIdentity()

/** The answering device's public bundle as it goes on the wire. */
export const transportAnswer = (pub: v1.Identity['pub']): string => b64std(v1.serializeIdentityPublic(pub))

/** Inverse of [transportAnswer], read by the trusted device off the channel. */
export const readTransportAnswer = (answer: string): v1.Identity['pub'] =>
  v1.deserializeIdentityPublic(fromB64std(answer))

/**
 * The *sign in with a QR code* direction's answer: the key AND its proof, as the channel's ONE relayed value
 * (D2). `PairingChannel` relays exactly `answer` and `envelope`, and `sealGrant` takes no code and never may
 * (ADR 0059 §6) — so the two travel together or not at all.
 *
 * ```
 * answer = b64std( lp(serializeIdentityPublic(pub)) || lp(commitment) )
 * ```
 *
 * ┌──────────────────────────────────────────────────────────────────────────────────────────────────────┐
 * │ THIS IS A SEPARATE PAIR FROM `transportAnswer`/`readTransportAnswer`, AND MERGING THEM IS THE DEFECT. │
 * │ `deserializeIdentityPublic` reads three length-prefixed fields and asserts NOTHING about the end of   │
 * │ the buffer — so a sign-in answer, commitment and all, parses SILENTLY as an add-a-device one. Two     │
 * │ functions make the two wire forms non-interchangeable by construction; one function with a flag would │
 * │ put that silence back. [readSignInAnswer] carries the end-of-buffer assertion the inherited reader    │
 * │ lacks, which is the other half of the same reason.                                                    │
 * └──────────────────────────────────────────────────────────────────────────────────────────────────────┘
 */
export const signInAnswer = (pub: v1.Identity['pub'], code: string): string =>
  b64std(v1.concat(v1.lp(v1.serializeIdentityPublic(pub)), v1.lp(codeCommitment(pub, code))))

/** Inverse of [signInAnswer]. Throws unless the value consumes its buffer EXACTLY — trailing bytes are not a
 *  tolerable extra, they are the shape that would otherwise let one direction's answer pass for the other's. */
export function readSignInAnswer(answer: string): { pub: v1.Identity['pub']; commitment: Uint8Array } {
  const bytes = fromB64std(answer)
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let o = 0
  const next = (): Uint8Array => {
    if (o + 4 > bytes.length) throw new Error('sign_in_answer_unreadable')
    const len = view.getUint32(o, false)
    o += 4
    if (o + len > bytes.length) throw new Error('sign_in_answer_unreadable')
    const field = bytes.slice(o, o + len)
    o += len
    return field
  }
  const serializedPub = next()
  const commitment = next()
  if (o !== bytes.length) throw new Error('sign_in_answer_unreadable')
  return { pub: v1.deserializeIdentityPublic(serializedPub), commitment }
}

/**
 * The short number both screens show (ADR 0059 §5): the first group of the answering key's safety-number
 * digest — the vector-pinned derivation of ADR 0028 plus a display rule, so nothing new is pinned here.
 *
 * Five digits are enough for what this compare actually defends: the photographed-QR race, where an attacker
 * answers the channel the intended device could not. The attacker never sees the intended device's number — it
 * never reached the server — so there is nothing to grind towards; the number has to be *distinguishable*, not
 * unforgeable.
 */
export const checkNumber = (pub: v1.Identity['pub']): string => v1.fingerprint(pub).split(' ')[0]

/** Seal the grant to the answering device's transport key (ADR 0059 §3). Versioned utf8 JSON, mirroring how
 *  the identity pins and the device register seal theirs. */
export function sealGrant(pub: v1.Identity['pub'], grant: PairingGrant): string {
  const plaintext = v1.utf8(
    JSON.stringify({
      v: 1,
      email: grant.email,
      masterKey: b64std(grant.masterKey),
      refreshToken: grant.refreshToken,
      label: grant.label,
    }),
  )
  return b64std(
    v1.asymWrap({ x: pub.kemX, m: pub.kemM }, plaintext, PAIRING_GRANT, { nonce: v1.randomBytes(12) }),
  )
}

/** Open the sealed grant with the transport private bundle. Throws on a tag mismatch, an unreadable body or an
 *  unknown version — every one of which is the same thing to the caller: this envelope is not ours. */
export function openGrant(priv: v1.Identity['priv'], envelope: string): PairingGrant {
  const plaintext = v1.asymUnwrap(
    v1.hybridSecret(priv),
    v1.hybridPublic(priv),
    fromB64std(envelope),
    PAIRING_GRANT,
  )
  const o = JSON.parse(decoder.decode(plaintext)) as Partial<Record<keyof PairingGrant | 'v', unknown>>
  if (o.v !== 1 || typeof o.email !== 'string' || typeof o.masterKey !== 'string'
    || typeof o.refreshToken !== 'string' || typeof o.label !== 'string') {
    throw new Error('pairing_grant_unreadable')
  }
  return {
    email: o.email,
    masterKey: fromB64std(o.masterKey),
    refreshToken: o.refreshToken,
    label: o.label,
  }
}
