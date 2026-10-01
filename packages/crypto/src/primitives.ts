// The raw AEAD and wrap primitives of the v1 suite. NOT a public entry point: `package.json` `exports` does not
// list this module, so `web/` and `admin-web/` cannot import it — every seal they write goes through the typed
// `seal`/`open`/`sealTo`/`openAs` in ./seal.ts, which binds the object a ciphertext is stored as (ADR 0072
// Decision 6: the structure is the guard). Only this package's own modules and its conformance test reach here.
import { x25519 } from '@noble/curves/ed25519.js'
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js'
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

export type HybridPublic = { x: Uint8Array; m: Uint8Array }
export type HybridSecret = { x: Uint8Array; m: Uint8Array }

export const aeadSeal = (key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).encrypt(pt)
export const aeadOpen = (key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).decrypt(ct)

function combiner(
  ssM: Uint8Array,
  ssX: Uint8Array,
  ctM: Uint8Array,
  epkX: Uint8Array,
  pkM: Uint8Array,
  pkX: Uint8Array,
): Uint8Array {
  const ikm = concat(lp(ssM), lp(ssX))
  const salt = sha256(concat(lp(ctM), lp(epkX), lp(pkM), lp(pkX)))
  return hkdf(sha256, ikm, salt, utf8('veyq/v1/kem'), 32)
}

export function kemEncap(
  pk: HybridPublic,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array } = {},
): { ct: Uint8Array; ss: Uint8Array } {
  const esk = opts.eskX ?? crypto.getRandomValues(new Uint8Array(32))
  const epkX = x25519.getPublicKey(esk)
  const ssX = x25519.getSharedSecret(esk, pk.x)
  const enc = ml_kem768.encapsulate(pk.m, opts.mlkemMsg)
  const ss = combiner(enc.sharedSecret, ssX, enc.cipherText, epkX, pk.m, pk.x)
  return { ct: concat(epkX, enc.cipherText), ss }
}

export function kemDecap(sk: HybridSecret, pk: HybridPublic, ct: Uint8Array): Uint8Array {
  const epkX = ct.slice(0, 32)
  const ctM = ct.slice(32)
  const ssX = x25519.getSharedSecret(sk.x, epkX)
  const ssM = ml_kem768.decapsulate(ctM, sk.m)
  return combiner(ssM, ssX, ctM, epkX, pk.m, pk.x)
}

// AAD binds the envelope TYPE byte (0x02 sym / 0x01 asym) alongside the suite marker and the context bytes, so a
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

const asymKek = (ss: Uint8Array, context: Uint8Array): Uint8Array =>
  hkdf(sha256, ss, new Uint8Array(0), concat(utf8('veyq/v1/wrap'), context), 32)

/** §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead */
export function asymWrap(
  pk: HybridPublic,
  targetKey: Uint8Array,
  context: Uint8Array,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array; nonce: Uint8Array },
): Uint8Array {
  const { ct, ss } = kemEncap(pk, opts)
  return concat(
    Uint8Array.of(ASYM_TYPE),
    ct,
    opts.nonce,
    aeadSeal(asymKek(ss, context), opts.nonce, targetKey, wrapAad(ASYM_TYPE, context)),
  )
}
export function asymUnwrap(sk: HybridSecret, pk: HybridPublic, blob: Uint8Array, context: Uint8Array): Uint8Array {
  const ct = blob.slice(1, 1 + 1120)
  const nonce = blob.slice(1 + 1120, 1 + 1120 + 12)
  const aead = blob.slice(1 + 1120 + 12)
  const ss = kemDecap(sk, pk, ct)
  return aeadOpen(asymKek(ss, context), nonce, aead, wrapAad(ASYM_TYPE, context))
}
