// A group's name and its pending invitees' name keys (uc.spaces.consent-group-add, cr-0096, ADR 0027, ADR 0115). The
// group name is sealed under a value key `K_v` of its own (`./valueKey.ts`), minted fresh at every write — the group's
// birth and every rename — whose wrap the scope key holds; a rotation re-wraps `K_v` and never rewrites the name. When a
// GroupOwner adds a member who turned on "ask before adding me to groups", the owner's device seals **that `K_v`** (not a
// name copy) to the invitee's identity — so the invitee opens the **same canonical `encryptedName`** the members read and
// the owner cannot privately show a divergent name; a rename mints a fresh `K_v` and seals it to every pending invitee
// again (`inviteeNameKeys`). A distinct `group-name-key` seal purpose (not `scope-grant`), bound to (scope, name epoch),
// gives domain separation — a name-key wrap can never be reinterpreted as a scope-key grant, nor as another group's.
// Mirrors {@link ./grant#wrapScopeKeyToMember}, but seals the name's value key, not the scope key.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { identityPrivate } from '../../app/vault'
const decoder = new TextDecoder()

/** The seal context of a group's name-key wrap at a name epoch (ADR 0027/0072). Distinct from `scope-grant`. */
const nameKeyContext = (scopeId: string, nameEpoch: number) => ({ purpose: 'group-name-key', scopeId, nameEpoch }) as const
/** The seal context of a group's canonical name at a name epoch (ADR 0072) — its own purpose, not a node name's — and so
 *  the address its value key's wrap is bound to (`value-key`, ADR 0115). */
export const groupNameContext = (scopeId: string, nameEpoch: number) => ({ purpose: 'group-name', scopeId, nameEpoch }) as const

/** Seal a group's name under its value key `K_v` at (scope, name epoch) → the canonical `encryptedName`. */
export function sealGroupName(nameKey: Uint8Array, scopeId: string, nameEpoch: number, name: string): string {
  return b64std(v1.seal(groupNameContext(scopeId, nameEpoch), nameKey, v1.utf8(name)))
}

/** Open a group's canonical `encryptedName` under its `K_v` at (scope, name epoch). Throws on a moved or foreign name, and on
 *  one whose `K_v` is not this one. */
export function openGroupNameWith(nameKey: Uint8Array, scopeId: string, nameEpoch: number, encryptedName: string): string {
  return decoder.decode(v1.open(groupNameContext(scopeId, nameEpoch), nameKey, fromB64std(encryptedName)))
}

/**
 * Seal the name's value key `K_v` to a member's **foreign** identity public key (serialized, base64) → the opaque base64
 * ciphertext to post as `sealedNameKey`. Fresh nonce each call; only the invitee opens it (with their own identity secret,
 * at the same scope + name epoch). The server stores it verbatim and never sees the key.
 */
export function wrapNameKeyToInvitee(
  nameKey: Uint8Array, scopeId: string, nameEpoch: number, inviteeIdentityPublicKeyB64: string,
): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(inviteeIdentityPublicKeyB64))
  return b64std(v1.sealTo(nameKeyContext(scopeId, nameEpoch), pub.kem, nameKey))
}

/**
 * Open a pending invitation's name on-device: unwrap `sealedNameKey` with the caller's **own** vaulted identity → the
 * name's `K_v`, then decrypt the scope's **canonical** `encryptedName` under it → the group name; null if the vault is
 * locked, the ciphertext won't open (wrong recipient / corrupt / a name the key is not for) or a rename cleared the name
 * key (the view then shows the proposal unnamed). The invitee's Accept/Decline view uses this to show the
 * **group-consistent** name — not an owner-typed claim.
 */
export function openInviteeName(invitation: {
  scopeId: string
  nameEpoch: number
  /** Null when a rename cleared it (its device could not anchor this account's key, ADR 0111 decision 9). */
  sealedNameKey: string | null
  encryptedName: string
}): string | null {
  if (invitation.sealedNameKey == null) return null
  const sealedNameKey = invitation.sealedNameKey
  const priv = identityPrivate()
  if (!priv) return null
  let nameKey: Uint8Array | null = null
  try {
    // Both seals are bound to the invitation's (scope, name epoch) — the row's scope is the one the invitee accepts
    // or declines by, so a name-key or a name moved from another group's invitation does not open here.
    const { scopeId, nameEpoch } = invitation
    nameKey = v1.openAs(nameKeyContext(scopeId, nameEpoch), v1.hybridSecret(priv), fromB64std(sealedNameKey))
    return openGroupNameWith(nameKey, scopeId, nameEpoch, invitation.encryptedName)
  } catch {
    return null
  } finally {
    nameKey?.fill(0)
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}
