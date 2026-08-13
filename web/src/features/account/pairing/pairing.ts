// uc.account.onboard-device (web, ADR 0059) — the client half of the pairing ceremony: what the displayed code
// carries, what the answering device puts on the wire, the number both screens compare, and the one envelope the
// grant travels in. Operator-blind throughout: the server relays a routing handle it can only recognise by hash,
// one public bundle it cannot attribute, and one ciphertext it cannot open.
import * as v1 from '../../../shared/crypto/v1'
import { b64std, fromB64std } from '../../../shared/base64'

/** The scanned code's marker. It exists ONLY so one scanner can dispatch between this and the Emergency Kit's
 *  `veyqkit:` — it is deliberately not a URL scheme, and ADR 0059 §4 is the reason: a code that could name an
 *  endpoint would redirect a trusted device's seal to a server the attacker runs, with no password and no
 *  session needed. */
const PREFIX = 'veyqpair:'

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

/** The displayed code's whole payload: the marker and the handle, nothing else (ADR 0059 §4). */
export const pairingPayload = (handle: string): string => PREFIX + handle

/**
 * Read a handle out of a scanned string, or `null` when this is not a pairing code — which is also how the one
 * scan entry dispatches (D6). Anchored on purpose: the payload IS the whole string, so nothing can ride along
 * after the handle, and a code carrying a host, a path or a query never parses as one of ours.
 */
export function parsePairingPayload(text: string): string | null {
  const m = new RegExp(`^${PREFIX}([A-Za-z0-9+/]+={0,2})$`).exec(text.trim())
  return m ? m[1] : null
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
