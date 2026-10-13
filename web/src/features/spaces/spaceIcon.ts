// Space icon primitives (uc.spaces.set-space-icon, ADR 0027). A space icon is a chosen image, baked to a fixed
// square, re-encoded, PADDED to a constant plaintext length, then sealed under a derived icon-key
// `K_icon = HKDF(E_e, "veyq/v1/space-icon-key")`. The constant length is the point: every `sealedIcon` is
// byte-identical in size, so the operator cannot tell a logo from a photo — and the tenant overview handing one
// length per tenant discloses nothing. Mirrors `inviteName.deriveGroupNameKey` (derive) + `tree.sealName` (seal),
// with a DISTINCT context/info string so an icon wrap can never be reinterpreted as a name-key or a scope-grant.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** Where an icon is sealed: its space and the epoch `K_icon` derives from (ADR 0072). */
export type IconAddress = { scopeId: string; iconEpoch: number }
const iconContext = (a: IconAddress) => ({ purpose: 'space-icon', scopeId: a.scopeId, iconEpoch: a.iconEpoch }) as const
/** HKDF `info` for deriving `K_icon` from an epoch key. Pinned by the `space_icon_key` conformance vector. */
const ICON_KEY_INFO = v1.utf8('veyq/v1/space-icon-key')

/**
 * The constant plaintext length every icon is padded to before sealing. 16 KiB gives a 256×256 re-encode ample
 * quality headroom while the sealed base64 sits at ~33% of MariaDB's `TEXT` cap: sealed = 1(type)+12(nonce)+P+
 * 16(tag) = 16413 bytes → base64 = 21884 chars. **This number is load-bearing in three places at once** — this
 * constant, the contract's `@Size(max = 21884)` on `SetScopeIconRequest.sealedIcon`, and the V40 `TEXT` column —
 * and the `spaceIcon.test.ts` cap test pins them together. Changing it means changing all three.
 */
export const ICON_PADDED_LEN = 16384
/** The u32be length header prepended before the encoded bytes; the remainder of the pad is zero-filled. */
const HEADER_LEN = 4
/** The largest encoded image the pad can carry — the caller steps encode quality down until it fits. */
export const ICON_ENCODED_BUDGET = ICON_PADDED_LEN - HEADER_LEN

/**
 * Derive the icon-key `K_icon` from the key of the epoch the icon was sealed under (ADR 0027):
 * `K_icon = HKDF-SHA256(E_e, salt="", "veyq/v1/space-icon-key", 32)`. One-way — handing out `K_icon` leaks the
 * icon and nothing else (not `E_e`, not the content key). The caller owns (and should zero) the returned bytes.
 */
export function deriveSpaceIconKey(epochKey: Uint8Array): Uint8Array {
  return v1.hkdfSha256(epochKey, new Uint8Array(0), ICON_KEY_INFO, 32)
}

/**
 * Pad encoded image bytes to the constant plaintext: `u32be(len) || bytes || zero-fill` to {@link ICON_PADDED_LEN}.
 * Throws `icon_too_large` if the bytes exceed {@link ICON_ENCODED_BUDGET} — the caller must step quality down first.
 */
export function pad(bytes: Uint8Array): Uint8Array {
  return padTo(bytes, ICON_PADDED_LEN)
}

/** The same `u32be(len) || bytes || zero-fill` pad at another constant length (the member avatar's 8 KiB, ADR 0084).
 *  Throws `icon_too_large` when the bytes do not fit `paddedLen - 4`. */
export function padTo(bytes: Uint8Array, paddedLen: number): Uint8Array {
  if (bytes.length > paddedLen - HEADER_LEN) throw new Error('icon_too_large')
  const out = new Uint8Array(paddedLen)
  new DataView(out.buffer).setUint32(0, bytes.length, false)
  out.set(bytes, HEADER_LEN)
  return out
}

/** The u32be length header's width: a pad at length `n` carries `n - PAD_HEADER_LEN` encoded bytes. */
export const PAD_HEADER_LEN = HEADER_LEN

/**
 * Recover the encoded bytes from a padded plaintext (the counterpart to {@link pad}). Throws `icon_malformed` on a
 * length header that overflows the pad — {@link openIcon} turns any failure into the initial (never a throw).
 */
export function unpad(padded: Uint8Array): Uint8Array {
  if (padded.length < HEADER_LEN) throw new Error('icon_malformed')
  const len = new DataView(padded.buffer, padded.byteOffset, padded.byteLength).getUint32(0, false)
  if (len > padded.length - HEADER_LEN) throw new Error('icon_malformed')
  return padded.slice(HEADER_LEN, HEADER_LEN + len)
}

/**
 * Seal encoded icon bytes under `K_icon` → opaque base64 (the {@link sealName} shape: `0x02 || nonce(12) || aead`,
 * fresh nonce, `space-icon` purpose bound to (scope, icon epoch)). Pads to the constant **intrinsically** — you
 * cannot seal a variable length — so every `sealedIcon` is byte-identical in size.
 */
export function sealIcon(iconKey: Uint8Array, address: IconAddress, encoded: Uint8Array): string {
  return b64std(v1.seal(iconContext(address), iconKey, pad(encoded)))
}

/**
 * Open a `sealedIcon` under `K_icon` → the encoded image bytes (unwrap + unpad), or **null** if it won't open /
 * unpad (wrong key, corrupt, malformed base64) — the caller renders the initial and never throws
 * (uc.spaces.set-space-icon E2, the same posture a dangling Memory cover takes).
 */
export function openIcon(iconKey: Uint8Array, address: IconAddress, sealedIcon: string): Uint8Array | null {
  try {
    return unpad(v1.open(iconContext(address), iconKey, fromB64std(sealedIcon)))
  } catch {
    return null
  }
}
