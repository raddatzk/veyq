// Per-tenant member avatar - the profile picture **sealed under K_tenant** (ADR 0084, uc.spaces.set-profile-picture).
// The sibling of `memberName.ts`: AEAD-sealed on-device, bound to (tenant scope, member) under its own purpose
// `member-avatar` (ADR 0072), so a blob sealed for one purpose or one member can never be read as another. The
// plaintext is a 256 px WebP padded to a CONSTANT length with the space icon's `u32be(len) || bytes || zero-fill`, so the
// operator cannot tell a photo from a drawing. Crypto-only (no api/client, no Vue) so the dev bootstrap script can
// seal identically.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { padTo, unpad, PAD_HEADER_LEN, ICON_PADDED_LEN } from '../../spaces/spaceIcon'

/** Whose picture it is: the tenant scope it is sealed in and the member it shows (ADR 0072). */
export type MemberAvatarAddress = { scopeId: string; userId: string }

const context = (a: MemberAvatarAddress) => ({ purpose: 'member-avatar', scopeId: a.scopeId, userId: a.userId }) as const

/**
 * The constant plaintext length every avatar is padded to - **the tenant icon's pad** ({@link ICON_PADDED_LEN}, 16 KiB), so the
 * sealed length is the icon's too. **Load-bearing in two places at once**: this constant and the contract's `maxLength` of
 * `sealedAvatar` ({@link AVATAR_SEALED_B64_LEN}, 21884) - sealed = 1(type)+12(nonce)+P+16(tag) = 16413 bytes -> base64 21884
 * chars. `memberAvatar.test.ts` pins both together with the contract file.
 */
export const AVATAR_PADDED_LEN = ICON_PADDED_LEN
/** The largest encoded image the pad can carry - the caller steps encode quality down until it fits. */
export const AVATAR_ENCODED_BUDGET = AVATAR_PADDED_LEN - PAD_HEADER_LEN
/** The pixel edge a picture re-encodes to - the icon's 256 px (`ICON_EDGE`, pinned equal in the test; not imported here, this
 *  module stays crypto-only). The largest place it is drawn is 128 px (the profile page), so it stays sharp on a 2x screen. */
export const AVATAR_EDGE = 256
/** The exact base64 length of every `sealedAvatar` (= the contract's `maxLength`). */
export const AVATAR_SEALED_B64_LEN = 21884

/** Seal encoded image bytes under `K_tenant` -> opaque base64. Pads to the constant intrinsically: a variable length cannot be sealed. */
export function sealAvatar(kTenant: Uint8Array, address: MemberAvatarAddress, encoded: Uint8Array): string {
  return b64std(v1.seal(context(address), kTenant, padTo(encoded, AVATAR_PADDED_LEN)))
}

/** Open a `sealedAvatar` under `K_tenant` -> the encoded image bytes, or **null** when it won't open or unpad (wrong key,
 *  another member's blob, corrupt, malformed base64, a pad of another length): the caller shows the letter and never throws (use-case E3). */
export function openAvatar(kTenant: Uint8Array, address: MemberAvatarAddress, sealedAvatar: string | null | undefined): Uint8Array | null {
  if (!sealedAvatar) return null
  try {
    const padded = v1.open(context(address), kTenant, fromB64std(sealedAvatar))
    // The pad is a constant: a plaintext of any other length is not one of ours (e.g. the 8 KiB pad of the first 128 px
    // pictures, stored before the 16 KiB pad) and shows the letter rather than a picture of an unknown shape.
    if (padded.length !== AVATAR_PADDED_LEN) return null
    return unpad(padded)
  } catch {
    return null
  }
}
