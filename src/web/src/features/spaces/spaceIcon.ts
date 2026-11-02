// Space icon primitives (uc.spaces.set-space-icon, ADR 0027, ADR 0115). A space icon is a chosen image, baked to a fixed
// square, re-encoded, PADDED to a constant plaintext length, then sealed under a value key `K_v` of its own
// (`./valueKey.ts`) — minted fresh at every icon write, its wrap under the scope key — so a rotation re-wraps `K_v` and
// never rewrites the icon. The constant length is the point: every `sealedIcon` is byte-identical in size, so the
// operator cannot tell a logo from a photo — and the tenant overview handing one length per tenant discloses nothing. A
// DISTINCT seal purpose (`space-icon`) keeps an icon from ever being read as a name or a grant.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

/** Where an icon is sealed: its space and the epoch it was written at (ADR 0072), which a rotation leaves. */
export type IconAddress = { scopeId: string; iconEpoch: number }
/** An icon's own seal context — and so the address its value key's wrap is bound to (`value-key`, ADR 0115). */
export const iconContext = (a: IconAddress) => ({ purpose: 'space-icon', scopeId: a.scopeId, iconEpoch: a.iconEpoch }) as const

/**
 * The constant plaintext length every icon is padded to before sealing. 16 KiB gives a 256×256 re-encode ample
 * quality headroom while the sealed base64 sits at ~33% of MariaDB's `TEXT` cap: sealed = 1(type)+12(nonce)+P+
 * 16(tag) = 16413 bytes → base64 = 21884 chars. **This number is load-bearing in three places at once** — this
 * constant, the contract's `@Size(max = 21884)` on `SetScopeIconRequest.sealedIcon`, and the V40 `TEXT` column —
 * and the `spaceIcon.test.ts` cap test pins them together. Changing it means changing all three.
 */
export const ICON_PADDED_LEN = 16384
/** The largest encoded image the pad can carry — the caller steps encode quality down until it fits. */
export const ICON_ENCODED_BUDGET = ICON_PADDED_LEN - v1.PAD_HEADER_LEN

/**
 * Pad encoded image bytes to the constant plaintext: `u32be(len) || bytes || zero-fill` to {@link ICON_PADDED_LEN}, with
 * the one pad `@veyq/crypto` has (`padTo`). Throws `pad_too_large` if the bytes exceed {@link ICON_ENCODED_BUDGET} — the
 * caller must step quality down first.
 */
export function pad(bytes: Uint8Array): Uint8Array {
  return v1.padTo(bytes, ICON_PADDED_LEN)
}

/**
 * Seal encoded icon bytes under the icon's value key `K_v` → opaque base64 (`0x02 || nonce(12) || aead`, fresh nonce,
 * `space-icon` purpose bound to (scope, icon epoch)). Pads to the constant **intrinsically** — you cannot seal a variable
 * length — so every `sealedIcon` is byte-identical in size.
 */
export function sealIcon(iconKey: Uint8Array, address: IconAddress, encoded: Uint8Array): string {
  return b64std(v1.seal(iconContext(address), iconKey, pad(encoded)))
}

/**
 * Open a `sealedIcon` under its `K_v` → the encoded image bytes (unwrap + unpad), or **null** if it won't open / unpad
 * (wrong key, corrupt, malformed base64) — the caller renders the initial and never throws (uc.spaces.set-space-icon E2,
 * the same posture a dangling Memory cover takes).
 */
export function openIcon(iconKey: Uint8Array, address: IconAddress, sealedIcon: string): Uint8Array | null {
  try {
    return v1.unpad(v1.open(iconContext(address), iconKey, fromB64std(sealedIcon)))
  } catch {
    return null
  }
}
