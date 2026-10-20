// The raw AEAD and wrap primitives of the v1 suite. NOT a public entry point: `package.json` `exports` does not
// list this module, so `src/web/` and `src/admin-web/` cannot import it — every seal they write goes through the typed
// `seal`/`open`/`sealTo`/`openAs` in ./seal.ts, which binds the object a ciphertext is stored as (ADR 0072
// Decision 6: the structure is the guard). Only this package's own modules and its conformance test reach here.
// X-Wing (draft-connolly-cfrg-xwing-kem-11, ADR 0017 decision 2). noble names it `ml_kem768_x25519` and keeps
// `XWing` only as a legacy alias of that name, so the suite imports the name noble maintains.
import { ml_kem768_x25519 as xwing } from '@noble/post-quantum/hybrid.js'
import { hkdf } from '@noble/hashes/hkdf.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { gcm } from '@noble/ciphers/aes.js'

const te = new TextEncoder()
// Stryker disable next-line StringLiteral: reported Survived with 0 tests completed, but killed in fact.
// `normalize('')` throws a RangeError, and v1.ts calls utf8 at module load, so every suite that imports v1 fails to
// import and vitest exits 1 — but Stryker's vitest runner counts a file that fails to import as no failing test.
export const utf8 = (s: string): Uint8Array => te.encode(s.normalize('NFC'))

export function concat(...arrs: Uint8Array[]): Uint8Array {
  const total = arrs.reduce((n, a) => n + a.length, 0)
  const out = new Uint8Array(total)
  let o = 0
  for (const a of arrs) {
    out.set(a, o)
    o += a.length
  }
  return out
}

export const u32be = (n: number): Uint8Array => {
  const b = new Uint8Array(4)
  new DataView(b.buffer).setUint32(0, n >>> 0, false)
  return b
}
/** length-prefixed: u32(len) || bytes */
export const lp = (x: Uint8Array): Uint8Array => concat(u32be(x.length), x)

/** An X-Wing encapsulation key: `pk_M ‖ pk_X`, 1216 bytes. */
export type HybridPublic = Uint8Array
/** An X-Wing decapsulation key: the 32-byte seed itself — the draft's private key, expanded on every use. */
export type HybridSecret = Uint8Array

export const aeadSeal = (key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).encrypt(pt)
export const aeadOpen = (key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).decrypt(ct)

/** The encapsulation key of a decapsulation key (X-Wing's `GenerateKeyPairDerand(seed)`). */
export const kemPublic = (sk: HybridSecret): HybridPublic => xwing.getPublicKey(sk)

/** X-Wing encapsulation. `eseed` (64 bytes) makes it deterministic for the vectors; omitted, noble draws it. */
export function kemEncap(pk: HybridPublic, opts: { eseed?: Uint8Array } = {}): { ct: Uint8Array; ss: Uint8Array } {
  const { cipherText, sharedSecret } = xwing.encapsulate(pk, opts.eseed)
  return { ct: cipherText, ss: sharedSecret }
}

/** X-Wing decapsulation: the combiner binds `pk_X`, which the seed re-derives, so no public key is passed. */
export const kemDecap = (sk: HybridSecret, ct: Uint8Array): Uint8Array => xwing.decapsulate(ct, sk)

// AAD binds the envelope TYPE byte (0x02 sym / 0x01 asym / 0x03 field, below) alongside the suite marker and the context bytes, so a
// ciphertext authenticated for one wrap type can never be reinterpreted as the other (defense in depth vs
// confusion). The context bytes are the canonical encoding of a typed seal context (./seal.ts).
const SYM_TYPE = 0x02
const ASYM_TYPE = 0x01
export const wrapAad = (type: number, context: Uint8Array): Uint8Array =>
  concat(Uint8Array.of(type), utf8('v1'), context)

/** §5b symmetric wrap: 0x02 || nonce(12) || aead */
export function symWrap(kek: Uint8Array, targetKey: Uint8Array, context: Uint8Array, nonce: Uint8Array): Uint8Array {
  return concat(Uint8Array.of(SYM_TYPE), nonce, aeadSeal(kek, nonce, targetKey, wrapAad(SYM_TYPE, context)))
}
export function symUnwrap(kek: Uint8Array, blob: Uint8Array, context: Uint8Array): Uint8Array {
  return aeadOpen(kek, blob.slice(1, 13), blob.slice(13), wrapAad(SYM_TYPE, context))
}

// The field envelope (ADR 0093 § 9): a text or icon field seals under a key of its own, derived per write from the key
// it would otherwise have been sealed under, so the key that opens one revision of one field opens nothing else.
//   K_field = HKDF-SHA256(ikm = K_parent, salt, info = utf8("veyq/v1/field/" + purpose), 32)
// The salt is drawn fresh per write and travels inside the envelope, as the nonce does: the server stores the envelope
// as the opaque ciphertext it already was, and a copy the server makes between columns carries the salt with it. Its
// own type byte (0x03) sits in the AAD as 0x01/0x02 do, so a field envelope never opens as a plain seal under its
// K_field, nor a plain seal as a field envelope. The salt is not in the AAD: another salt derives another key.
const FIELD_TYPE = 0x03
export const FIELD_SALT_BYTES = 16
const FIELD_HEAD = 1 + FIELD_SALT_BYTES

export const fieldKey = (parentKey: Uint8Array, salt: Uint8Array, purpose: string): Uint8Array =>
  hkdf(sha256, parentKey, salt, utf8(`veyq/v1/field/${purpose}`), 32)

/** The salt a field envelope was sealed with. */
export const fieldSaltOf = (envelope: Uint8Array): Uint8Array => envelope.slice(1, FIELD_HEAD)

/** The field envelope: 0x03 || salt(16) || nonce(12) || aead */
export function fieldWrap(
  key: Uint8Array,
  plaintext: Uint8Array,
  context: Uint8Array,
  salt: Uint8Array,
  nonce: Uint8Array,
): Uint8Array {
  return concat(Uint8Array.of(FIELD_TYPE), salt, nonce, aeadSeal(key, nonce, plaintext, wrapAad(FIELD_TYPE, context)))
}
export function fieldUnwrap(key: Uint8Array, envelope: Uint8Array, context: Uint8Array): Uint8Array {
  // The AAD carries the type the OPENER expects, not the envelope's own first byte, so that byte is checked here: an
  // envelope relabelled 0x02 must not open as a field.
  if (envelope[0] !== FIELD_TYPE) throw new TypeError()
  return aeadOpen(key, envelope.slice(FIELD_HEAD, FIELD_HEAD + 12), envelope.slice(FIELD_HEAD + 12), wrapAad(FIELD_TYPE, context))
}

const asymKek = (ss: Uint8Array, context: Uint8Array): Uint8Array =>
  hkdf(sha256, ss, new Uint8Array(0), concat(utf8('veyq/v1/wrap'), context), 32)

/** §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead, where ct is X-Wing's `ct_M ‖ ct_X` */
export function asymWrap(
  pk: HybridPublic,
  targetKey: Uint8Array,
  context: Uint8Array,
  opts: { eseed?: Uint8Array; nonce: Uint8Array },
): Uint8Array {
  const { ct, ss } = kemEncap(pk, opts)
  return concat(
    Uint8Array.of(ASYM_TYPE),
    ct,
    opts.nonce,
    aeadSeal(asymKek(ss, context), opts.nonce, targetKey, wrapAad(ASYM_TYPE, context)),
  )
}
export function asymUnwrap(sk: HybridSecret, blob: Uint8Array, context: Uint8Array): Uint8Array {
  const ct = blob.slice(1, 1 + 1120)
  const nonce = blob.slice(1 + 1120, 1 + 1120 + 12)
  const aead = blob.slice(1 + 1120 + 12)
  const ss = kemDecap(sk, ct)
  return aeadOpen(asymKek(ss, context), nonce, aead, wrapAad(ASYM_TYPE, context))
}
