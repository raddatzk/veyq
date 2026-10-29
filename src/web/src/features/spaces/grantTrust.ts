// The recipient's half of ADR 0028 Decision 6: a grant is opened only if its granter's signature verifies. A seal to
// a public key needs only that key, so without this check the server could hand an account a scope key of its own
// choosing — its personal scope included — and the account's next write would go under it. The one unwrap path
// (`scopes.ts`) and the switcher's icon read call {@link verifyOwnGrant} before the key inside is ever used.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { anchorChainKey, type ChainKeyAnchor, type TenantStandings } from '../account/identityPins'
import { cachedTenantView } from '../account/tenantTrust'
import { epochFloor } from '../account/trustMarks'
import { grantSignatureVerifies, type GrantScope } from './grant'

/** Why a grant was not opened. The key inside is never used; the scope does not open (it waits for a current grant).
 *  `epoch_unlinked` is decided where the grant is opened and held (`scopes.ts` § `requireLinkToHeld`, ADR 0111 decision
 *  23): its epoch chain does not reach the key this device held. */
export type GrantRefusal =
  | 'unsigned' | 'not_self_signed' | 'bad_signature' | 'granter_key_unanchored' | 'granter_key_retired' | 'epoch_unlinked'

export class GrantRefused extends Error {
  constructor(readonly scopeId: string, readonly reason: GrantRefusal) {
    super(`grant_refused: ${reason}`)
    this.name = 'GrantRefused'
  }
}

/** The caller as a grant's recipient: their account id and the identity public bundle their vault holds. */
export type Me = { userId: string; pub: v1.Identity['pub']; pubB64: string }

// The account id is the one fact the vault does not hold. Cached against the public bundle it was read for, so a
// different identity (another account, or this one after a key rotation) reads it again rather than inheriting it.
let cachedId: { pubB64: string; userId: string } | null = null

/** The caller as a recipient, derived from the vault's private bundle — never from a directory's copy of it. */
export async function me(priv: v1.Identity['priv']): Promise<Me> {
  const pub = v1.identityPublicFromPrivate(priv)
  const pubB64 = b64std(v1.serializeIdentityPublic(pub))
  if (cachedId?.pubB64 !== pubB64) {
    const userId = (await withRefresh(() => accountApi().getAccountKeyMaterial())).userId
    cachedId = { pubB64, userId }
  }
  return { userId: cachedId.userId, pub, pubB64 }
}

// Pin writes are version-guarded read-modify-writes; several grants verified at once (the switcher opens every
// group) would race each other's re-base. One at a time, the way the rotation's re-grant loop already pins.
let pinQueue: Promise<unknown> = Promise.resolve()
function anchorSerially(userId: string, keyB64: string, tenant?: TenantStandings): Promise<ChainKeyAnchor> {
  const next = pinQueue.then(() => anchorChainKey(userId, keyB64, { tenant }))
  pinQueue = next.catch(() => undefined)
  return next
}

/** A grant row as a read serves it (`ScopeSummary`, `MyTenant`). */
export type GrantRow = {
  granterUserId?: string | null
  grantSignature?: string | null
  granterIdentityPublicKey?: string | null
}

/**
 * Verify the caller's own grant before it is opened (ADR 0028 Decision 6); throws {@link GrantRefused} otherwise.
 * - **Self-granted** (the caller's own birth, rotation or re-key): the signature must verify under the caller's own
 *   signing key, which comes from the vault — never from the directory.
 * - **PERSONAL**: only a self-granted grant opens. The kind is signed, so a group's genuine grant served in the
 *   personal scope's row does not verify as `PERSONAL`, and a foreign-signed one is refused outright.
 * - **Shared**: the signature must verify under the granter's key the grant row carries (its per-grant snapshot), and
 *   the granter's key chain must reach that key from the head this account pinned (ADR 0028 Decision 2,
 *   `anchorChainKey`): the pinned key matches with no fetch; a first-seen chain is pinned and a chain that advanced
 *   moves the pin (only after the signature verified, so a forged grant pins nothing); anything else is refused as
 *   `granter_key_unanchored` — with no accept, the key returns only through the chain.
 * - **A retired key of that chain** (an earlier step's) signed the grant before its granter secured their account; a new
 *   epoch from it is what a thief holding the old identity would forge, since the honest granter signs every new epoch
 *   with the new key. So it opens only at an epoch at or below this device's floor for the scope — an epoch this device
 *   already held — and is refused above it as `granter_key_retired`.
 * - **In its tenant** (ADR 0111 decisions 12 and 15): a GROUP's grant is judged against the granter's standing in the
 *   group's tenant (`tenantId`) — a granter without a join record who is no owner there, or whose join record does not
 *   verify, is `granter_key_unanchored`, and a joined granter's key must sit on their chain at or after the step the
 *   record names. Not by any role in the group: who is in one is the server's word, so a device's first grant of a
 *   group opens from any member the tenant anchors (ADR 0111 § What stays open, inbox #750, `groupFirstGrant.test.ts`).
 *   The TENANT scope's own grant is judged by the pin alone: its standings are read with the very key it
 *   carries, and a tenant whose directory this device cannot read (another tenant than the active one) falls back to
 *   the pin as well. What ties a new epoch to the key this device held is not the granter but the epoch chain, checked
 *   where the grant is opened and held (`scopes.ts` § `requireLinkToHeld`, ADR 0111 decision 23).
 */
export async function verifyOwnGrant(
  scope: GrantScope, wrappedGrant: string, row: GrantRow, self: Me, tenantId?: string,
): Promise<void> {
  const refuse = (reason: GrantRefusal) => new GrantRefused(scope.scopeId, reason)
  if (!row.granterUserId || !row.grantSignature) throw refuse('unsigned')
  const to = { userId: self.userId, identityPublicKeyB64: self.pubB64 }
  if (row.granterUserId === self.userId) {
    if (!grantSignatureVerifies(self.pub.sign, scope, to, wrappedGrant, row.grantSignature)) throw refuse('bad_signature')
    return
  }
  if (scope.kind === 'PERSONAL') throw refuse('not_self_signed')
  if (!row.granterIdentityPublicKey) throw refuse('unsigned')
  let granterSign: Uint8Array
  try {
    granterSign = v1.deserializeIdentityPublic(fromB64std(row.granterIdentityPublicKey)).sign
  } catch {
    throw refuse('bad_signature')
  }
  if (!grantSignatureVerifies(granterSign, scope, to, wrappedGrant, row.grantSignature)) throw refuse('bad_signature')
  const tenant = tenantId && scope.kind === 'GROUP' ? await cachedTenantView(tenantId).catch(() => undefined) : undefined
  const granter = await anchorSerially(row.granterUserId, row.granterIdentityPublicKey, tenant)
  if (granter.anchor === 'anomaly') throw refuse('granter_key_unanchored')
  if (granter.retired && scope.epoch > (await epochFloor(scope.scopeId))) throw refuse('granter_key_retired')
}
