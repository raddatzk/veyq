// Group invite name-key (uc.spaces.consent-group-add, cr-0096, ADR 0027). Replaces cr-0094's one-off owner
// name-seal. The group name is sealed under a name-key `K_name` derived from the name's content-epoch key
// (`K_name = HKDF(E_e, "veyq/v1/group-name-key")`). When a GroupOwner adds a member who turned on "ask before
// adding me to groups", the owner's device wraps **K_name** (not a name copy) to the invitee's identity — so the
// invitee opens the **same canonical `encryptedName`** the members read and the owner cannot privately show a
// divergent name. A distinct `group-name-key` AAD context (not `scope-grant`) gives domain separation — a
// name-key wrap can never be reinterpreted as a scope-key grant. Mirrors {@link ./grant#wrapScopeKeyToMember},
// but wraps the derived name-key, not the scope key.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { identityPrivate } from '../../app/vault'
import { openName } from '../memory/tree'

/** The AAD/KDF context binding the wrap of the derived name-key `K_name` (ADR 0017/0027). Distinct from `scope-grant`. */
export const GROUP_NAME_KEY = 'group-name-key'

/** HKDF `info` for deriving the group name-key from an epoch key. There is no conformance vector for
 *  `group-name-key`; the derivation is pinned by the forward KAT in `inviteName.test.ts` instead. */
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
 * their own identity secret under {@link GROUP_NAME_KEY}). The server stores it verbatim and never sees the key.
 */
export function wrapNameKeyToInvitee(nameKey: Uint8Array, inviteeIdentityPublicKeyB64: string): string {
  const pub = v1.deserializeIdentityPublic(fromB64std(inviteeIdentityPublicKeyB64))
  const hybridPub: v1.HybridPublic = { x: pub.kemX, m: pub.kemM }
  return b64std(v1.asymWrap(hybridPub, nameKey, GROUP_NAME_KEY, { nonce: v1.randomBytes(12) }))
}

/**
 * Open a pending invitation's name on-device: unwrap `sealedNameKey` with the caller's **own** vaulted identity →
 * `K_name`, then decrypt the scope's **canonical** `encryptedName` under it → the group name; null if the vault is
 * locked or the ciphertext won't open (wrong recipient / corrupt / renamed since the invite). The invitee's
 * Accept/Decline view uses this to show the **group-consistent** name — not an owner-typed claim.
 */
export function openInviteeName(sealedNameKeyB64: string, encryptedNameB64: string): string | null {
  const priv = identityPrivate()
  if (!priv) return null
  let nameKey: Uint8Array | null = null
  try {
    nameKey = v1.asymUnwrap(v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(sealedNameKeyB64), GROUP_NAME_KEY)
    return openName(nameKey, encryptedNameB64)
  } catch {
    return null
  } finally {
    nameKey?.fill(0)
    priv.kemX.fill(0)
    priv.mlkemSeed.fill(0)
    priv.sign.fill(0)
  }
}
