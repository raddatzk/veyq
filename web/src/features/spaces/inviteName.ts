// Group invite name-key (uc.spaces.consent-group-add, cr-0096, ADR 0027). Replaces cr-0094's one-off owner
// name-seal. The group name is sealed under a name-key `K_name` derived from the name's content-epoch key
// (`K_name = HKDF(E_e, "veyq/v1/group-name-key")`). When a GroupOwner adds a member who turned on "ask before
// adding me to groups", the owner's device wraps **K_name** (not a name copy) to the invitee's identity — so the
// invitee opens the **same canonical `encryptedName`** the members read and the owner cannot privately show a
// divergent name. A distinct `group-name-key` seal purpose (not `scope-grant`), bound to (scope, name epoch), gives
// domain separation — a name-key wrap can never be reinterpreted as a scope-key grant, nor as another group's. Mirrors {@link ./grant#wrapScopeKeyToMember},
// but wraps the derived name-key, not the scope key.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { identityPrivate } from '../../app/vault'
const decoder = new TextDecoder()

/** The seal context of a group's name-key wrap at a name epoch (ADR 0027/0072). Distinct from `scope-grant`. */
const nameKeyContext = (scopeId: string, nameEpoch: number) => ({ purpose: 'group-name-key', scopeId, nameEpoch }) as const
/** The seal context of a group's canonical name at a name epoch (ADR 0072) — its own purpose, not a node name's. */
const groupNameContext = (scopeId: string, nameEpoch: number) => ({ purpose: 'group-name', scopeId, nameEpoch }) as const

/** Seal a group's name under its name-key `K_name` at (scope, name epoch) → the canonical `encryptedName`. */
export function sealGroupName(nameKey: Uint8Array, scopeId: string, nameEpoch: number, name: string): string {
  return b64std(v1.seal(groupNameContext(scopeId, nameEpoch), nameKey, v1.utf8(name)))
}

/** Open a group's canonical `encryptedName` under `K_name` at (scope, name epoch). Throws on a moved or foreign name. */
export function openGroupNameWith(nameKey: Uint8Array, scopeId: string, nameEpoch: number, encryptedName: string): string {
  return decoder.decode(v1.open(groupNameContext(scopeId, nameEpoch), nameKey, fromB64std(encryptedName)))
}

/** HKDF `info` for deriving the group name-key from an epoch key — pinned by the `group_name_key` conformance
 *  vector (and the forward KAT in `inviteName.test.ts`). */
const NAME_KEY_INFO = v1.utf8('veyq/v1/group-name-key')

/**
 * Derive the group name-key `K_name` from the key of the epoch the name was sealed under (ADR 0027):
 * `K_name = HKDF-SHA256(E_e, salt="", "veyq/v1/group-name-key", 32)`. One-way — handing out `K_name` leaks the
 * name and nothing else (not `E_e`, not the content key). The caller owns (and should zero) the returned bytes.
 */
export function deriveGroupNameKey(epochKey: Uint8Array): Uint8Array {
  return v1.hkdfSha256(epochKey, new Uint8Array(0), NAME_KEY_INFO, 32)
}

/**
 * Wrap the derived name-key `K_name` to a member's **foreign** identity public key (serialized, base64) → the
 * opaque base64 ciphertext to post as `sealedNameKey`. Fresh nonce each call; only the invitee opens it (with
 * their own identity secret, at the same scope + name epoch). The server stores it verbatim and never sees the key.
 */
export function wrapNameKeyToInvitee(
  nameKey: Uint8Array, scopeId: string, nameEpoch: number, inviteeIdentityPublicKeyB64: string,
): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(inviteeIdentityPublicKeyB64))
  return b64std(v1.sealTo(nameKeyContext(scopeId, nameEpoch), pub.kem, nameKey))
}

/**
 * Open a pending invitation's name on-device: unwrap `sealedNameKey` with the caller's **own** vaulted identity →
 * `K_name`, then decrypt the scope's **canonical** `encryptedName` under it → the group name; null if the vault is
 * locked or the ciphertext won't open (wrong recipient / corrupt / renamed since the invite). The invitee's
 * Accept/Decline view uses this to show the **group-consistent** name — not an owner-typed claim.
 */
export function openInviteeName(invitation: {
  scopeId: string
  nameEpoch: number
  sealedNameKey: string
  encryptedName: string
}): string | null {
  const priv = identityPrivate()
  if (!priv) return null
  let nameKey: Uint8Array | null = null
  try {
    // Both seals are bound to the invitation's (scope, name epoch) — the row's scope is the one the invitee accepts
    // or declines by, so a name-key or a name moved from another group's invitation does not open here.
    const { scopeId, nameEpoch } = invitation
    nameKey = v1.openAs(nameKeyContext(scopeId, nameEpoch), v1.hybridSecret(priv), fromB64std(invitation.sealedNameKey))
    return openGroupNameWith(nameKey, scopeId, nameEpoch, invitation.encryptedName)
  } catch {
    return null
  } finally {
    nameKey?.fill(0)
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}
