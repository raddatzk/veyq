// en.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (sealTo, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { scopesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, isHttpStatus, HttpStatus } from '../../shared/httpError'
import { identityPrivate } from '../../app/vault'
import { slugify } from '../../shared/naming'
import { scopeKeyOf, zeroScopeKey, type ScopeKey } from './epochs'
import { grantContext, recipientIdentityDigest, sealGrant, type GrantScope, type SignedGrant } from './grant'
import * as invite from '@veyq/crypto/invite'
import { me, verifyOwnGrant, GrantRefused, type Me } from './grantTrust'
import { anchorIdentity, IdentityUnanchoredError, type ServedIdentity, type TenantStandings } from '../account/identityPins'
import { loadTenantView, tenantViewOfScope, type TenantOpener } from '../account/tenantTrust'
import { epochFloor, epochKeyCommitment, heldEpochKey, holdEpochKey } from '../account/trustMarks'
import { groupNameContext, wrapNameKeyToInvitee, sealGroupName, openGroupNameWith } from './inviteName'
import { iconContext, sealIcon, openIcon } from './spaceIcon'
import { openValue, sealValue, wrapValueKeyOf } from './valueKey'
import { rememberGroupName, groupNameSeen } from './groupNamesSeen'
import { stageScopeLevelRekey, isRestageable, REKEY_RESTAGES } from './scopeRekey'
import { rekeyScope } from '../../core/node/rekey/rekeyScope'
import { ScopeKind, type CompromiseRotation, type ScopeSummary, type ScopeSummaryRoleEnum, type MyTenant, type ScopeMember } from '../../api/generated/scopes'
import type { OpenInvite, InviteGrantEntry, InviteeNameKey } from '../../api/generated/scopes'
import type { RotationPlanScope } from '../../api/generated/account'

/** The seal context of a scope grant (ADR 0072), re-exported — its home is {@link ./grant}, shared with the
 *  grant-delivery worker (cr-0076) + rotation re-grants (cr-0089). */
export { grantContext } from './grant'

/**
 * Turn off every link of a scope (ADR 0075 point 5) — a scope owner's switch-off: every enabled share, whoever made
 * it, goes off and is marked, so each comes back only by {@link reissueShare}. Resolves how many it turned off. Each is
 * a close (ADR 0114 decision 1): the server marks what lies below every viewable one due, and this device starts the
 * scope's re-key in the background ({@link rekeyClosedScopes}).
 */
export async function revokeScopeShares(scopeId: string): Promise<number> {
  const { revoked } = await withRefresh(() => scopesApi().revokeScopeShares({ scopeId }))
  if (revoked > 0) void rekeyClosedScopes([scopeId])
  return revoked
}

/**
 * After a close the server ran in bulk — every link of a scope switched off, or a removed member's links in the scopes
 * of their tenant — this device re-keys what it marked (ADR 0114 decision 9): [scopeIds], or without them every scope of
 * the active tenant this member holds that is now re-keying, one after another, in the background — never awaited by
 * the caller, so the dialog does not wait. The whole scope's re-key (`rekeyScope`): a bulk close marks the subtree of
 * every link it closed, and only the due nodes are re-keyed. Any failure — a scope this device cannot open, an
 * owner-only node a member leaves — is left to the members' workers, which resume any scope with a due node. Never
 * rejects.
 */
export function rekeyClosedScopes(scopeIds?: readonly string[]): Promise<void> {
  return (async () => {
    const ids = scopeIds ?? (await listMyScopes()).filter((s) => s.rekeying === true).map((s) => s.id)
    for (const id of ids) await rekeyScope(id).catch(() => undefined)
  })().catch(() => undefined)
}

/** The caller's scopes in the active tenant (each with their own grant ciphertext). */
export async function listMyScopes(): Promise<ScopeSummary[]> {
  return withRefresh(() => scopesApi().listMyScopes())
}

/** A scope's succession statements (ADR 0115 decision 5), decoded — so a member checks that a newer epoch's key succeeds
 *  the one it held. They open nothing: a scope has one key at a time. */
export async function listScopeSuccessions(scopeId: string): Promise<v1.SuccessionLink[]> {
  const served = await withRefresh(() => scopesApi().listScopeEpochs({ scopeId }))
  return served.map((s) => ({
    epoch: s.epoch, successionPublicKey: fromB64std(s.successionPublicKey), successionSignature: fromB64std(s.successionSignature),
  }))
}

/** Soft-delete a group (uc.spaces.delete-group, cr-0090) — enters the grace period (owner-gated). */
export async function deleteGroup(scopeId: string): Promise<void> {
  await withRefresh(() => scopesApi().deleteGroup({ scopeId }))
}

/** Restore a soft-deleted group in-window (cr-0090) — clears the pending-deletion marker (owner-gated). */
export async function restoreGroup(scopeId: string): Promise<void> {
  await withRefresh(() => scopesApi().restoreGroup({ scopeId }))
}

/** A scope's pending-deletion state (cr-0090), and — on an owner's row only — whether a compromise rotation queued
 *  on it needs an owner (uc.spaces.complete-queued-rotation) and whether its re-key is unfinished (ADR 0109, derived by
 *  the server). Null when the caller holds no such scope. No name decryption. */
export async function scopeLifecycle(scopeId: string): Promise<{
  deletedAt: Date | null; purgeAfter: Date | null; compromiseRotation: CompromiseRotation | null; rekeying: boolean
} | null> {
  const s = (await listMyScopes()).find((x) => x.id === scopeId)
  return s
    ? {
        deletedAt: s.deletedAt ?? null, purgeAfter: s.purgeAfter ?? null, compromiseRotation: s.compromiseRotation ?? null,
        rekeying: s.rekeying === true,
      }
    : null
}

/**
 * An owner queues the scope's dead-lettered compromise rotation again (uc.spaces.complete-queued-rotation E8): every
 * rotated account whose rotation failed is excluded again, this caller is not — so this device's own worker can
 * claim it on its next pass. Answers the row's new value (null once nothing needs an owner).
 */
export async function requeueScopeRotation(scopeId: string): Promise<CompromiseRotation | null> {
  const r = await withRefresh(() => scopesApi().requeueScopeRotation({ scopeId }))
  return r.compromiseRotation ?? null
}

/** Every tenant the caller belongs to — the sidebar space switcher (Host-independent). */
export async function listMyTenants(): Promise<MyTenant[]> {
  return withRefresh(() => scopesApi().listMyTenants())
}

/**
 * Ensure this member has a PERSONAL scope in the active tenant — auto-birth, operator-blind: generate the
 * scope key, seal it to our **own** identity public key, and POST only the ciphertext grant. No-op if one
 * already exists. Requires an unlocked vault (the identity bundle). Returns whether a scope was created.
 */
export async function ensurePersonalScope(): Promise<boolean> {
  if ((await listMyScopes()).some((s) => s.kind === ScopeKind.Personal)) return false
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot birth a personal scope')
  const scopeKey = v1.randomBytes(32)
  // The client mints the scope's id: the grant is sealed bound to it, at the birth epoch 0 (ADR 0072).
  const scopeId = crypto.randomUUID()
  try {
    // Self-signed, kind PERSONAL (ADR 0028 Decision 6): the only personal grant this client will ever open.
    const self = await me(priv)
    const grant = sealGrant(scopeKey, { scopeId, kind: ScopeKind.Personal, epoch: 0 }, recipientOf(self), priv.sign)
    await withRefresh(() =>
      scopesApi().createPersonalScope({
        createPersonalScopeRequest: { scopeId, ...grant, recipientIdentityDigest: recipientIdentityDigest(self.pubB64) },
      }),
    )
    return true
  } finally {
    scopeKey.fill(0)
    zero(priv)
  }
}

/**
 * Create a named GROUP scope in the active tenant (en.spaces.group-scope, cr-0032) — operator-blind: generate a
 * random `K_SC`, **seal the name under a fresh value key `K_v` of its own** at name-epoch 0 and wrap `K_v` under
 * `K_SC` (ADR 0115), seal `K_SC` to our **own** identity (the self-grant), and POST only the ciphertexts. The server
 * mints the scope (at name-epoch 0) + our OWNER self-membership and never sees a key or the name. Requires an unlocked
 * vault. Returns the new scope id (so the caller can switch into `#@<id>`).
 */
export async function createGroup(name: string): Promise<string> {
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot create a group')
  const scopeKey = v1.randomBytes(32)
  // The client mints the scope's id: the grant and the name are both sealed bound to it (ADR 0072).
  const scopeId = crypto.randomUUID()
  try {
    const sealedName = sealValue({ epoch: 0, key: scopeKey }, groupNameContext(scopeId, 0), (nameKey) =>
      fromB64std(sealGroupName(nameKey, scopeId, 0, name)))
    const self = await me(priv)
    const grant = sealGrant(scopeKey, { scopeId, kind: ScopeKind.Group, epoch: 0 }, recipientOf(self), priv.sign)
    const created = await withRefresh(() =>
      scopesApi().createGroup({
        createGroupRequest: {
          scopeId, encryptedName: sealedName.sealed, wrappedNameValueKey: sealedName.wrapped, ...grant,
          recipientIdentityDigest: recipientIdentityDigest(self.pubB64),
        },
      }),
    )
    return created.scopeId
  } finally {
    scopeKey.fill(0)
    zero(priv)
  }
}

/** The group's display name from its scope key: open the name's value key from its wrap under the one key the caller
 *  holds, then the canonical `encryptedName` under it at the epoch it was written at (cr-0096/ADR 0027, ADR 0115) — a
 *  rotation re-wraps the key and leaves the name, so a wrap under any other key, or one a rotator could not open and
 *  staged as one nobody opens, does not open. Members read the same blob a pending invitee is handed the key for.
 *  Every name opened here is also remembered in memory for this page ({@link rememberGroupName}): the one label the
 *  security panel has for a group, whose plan serves no name (inbox #280). */
function openGroupName(
  scopeId: string, epochs: ScopeKey, nameEpoch: number, encryptedName: string, wrappedNameValueKey: string | null | undefined,
): string {
  const name = openValue(epochs, groupNameContext(scopeId, nameEpoch), wrappedNameValueKey, (nameKey) =>
    openGroupNameWith(nameKey, scopeId, nameEpoch, encryptedName))
  rememberGroupName(scopeId, name)
  return name
}

/** [open]'s name, or the unnamed group's `''` when it does not open. */
function unnamedUnless(open: () => string): string {
  try {
    return open()
  } catch {
    return ''
  }
}

/** A space's icon from its scope key (uc.spaces.set-space-icon): open the icon's value key from its wrap under the one
 *  key the caller holds — a rotation re-wraps it and leaves the icon (ADR 0115) — then open + unpad the sealed icon at the
 *  epoch it was written at. Null when either does not open → the caller renders the initial, never a throw. */
function openSpaceIconAt(
  scopeId: string, epochs: ScopeKey, iconEpoch: number, sealedIcon: string, wrappedIconValueKey: string | null | undefined,
): Uint8Array | null {
  const address = { scopeId, iconEpoch }
  try {
    return openValue(epochs, iconContext(address), wrappedIconValueKey, (iconKey) => openIcon(iconKey, address, sealedIcon))
  } catch {
    return null
  }
}

/** A group the member holds — the switcher/settings row: opaque id + tenant + the **decrypted** (client-side)
 *  name + its decrypted `icon` bytes (null → the initial) + the caller's own `role` in it (OWNER/MEMBER, cr-0087 —
 *  lets the settings hub list groups I own). */
export type GroupSpace = {
  scopeId: string; tenantId: string; name: string; icon: Uint8Array | null; role: string; deletedAt: Date | null; purgeAfter: Date | null
  /** Owner rows only: a compromise rotation queued here that needs an owner (`stalled`/`failed`), else null. */
  compromiseRotation: CompromiseRotation | null
  /** The caller's grant is behind the group's current epoch (ADR 0115 decision 8): the group has a new key this
   *  device does not hold yet, so nothing of it opens — its name is the one this page remembers, or empty, and its icon
   *  the initial — until a member online re-grants it. */
  awaitingKey: boolean
}

/**
 * The member's groups in the active tenant with their names decrypted on-device (en.spaces.group-scope). Each
 * group's name is sealed under **its own** `K_SC`, so we open every group's grant to decrypt its name — the
 * server only ever returned the ciphertext name + ciphertext grant. A group whose grant won't open (E2, a foreign
 * grant) is dropped from the switcher rather than crashing it; one whose name won't open is listed unnamed. A group whose grant is BEHIND its current epoch
 * stays listed, marked {@link GroupSpace.awaitingKey}: a rotation replaced its key and re-wrapped its name's and icon's value keys under
 * the new one, which this device does not hold yet (ADR 0115 decision 8) — it reads nothing of the group until then.
 */
export async function listMyGroups(): Promise<GroupSpace[]> {
  const priv = identityPrivate()
  if (!priv) return []
  try {
    const groups = (await listMyScopes()).filter((s) => s.kind === ScopeKind.Group)
    const self = await me(priv)
    const rows = await Promise.all(
      groups.map(async (g): Promise<GroupSpace | null> => {
        const row = {
          scopeId: g.id, tenantId: g.tenantId, role: g.role, deletedAt: g.deletedAt ?? null,
          purgeAfter: g.purgeAfter ?? null, compromiseRotation: g.compromiseRotation ?? null,
        }
        // Behind: the key that would open the name is one this device does not hold (ADR 0115 decision 8).
        if (g.grantedEpoch < g.currentEpoch) return { ...row, name: groupNameSeen(g.id) ?? '', icon: null, awaitingKey: true }
        let epochs: ScopeKey | null = null
        try {
          epochs = scopeKeyOf(await openOwnGrant(g, priv, self), g.grantedEpoch)
          // A name that does not open under the key the grant opened — one whose value key's wrap a rotator could not open
          // and staged as one nobody opens (ADR 0115 decision 2) — is the unnamed group, never a reason to drop it from the
          // switcher.
          const name = g.encryptedName
            ? unnamedUnless(() => openGroupName(g.id, epochs!, g.nameEpoch, g.encryptedName!, g.wrappedNameValueKey))
            : ''
          const icon = g.sealedIcon ? openSpaceIconAt(g.id, epochs, g.iconEpoch, g.sealedIcon, g.wrappedIconValueKey) : null
          return { ...row, name, icon, awaitingKey: false }
        } catch (e) {
          if (e instanceof AwaitingCurrentKey) return { ...row, name: groupNameSeen(g.id) ?? '', icon: null, awaitingKey: true }
          return null
        } finally {
          if (epochs) zeroScopeKey(epochs)
        }
      }),
    )
    return rows.filter((r): r is GroupSpace => r !== null)
  } finally {
    zero(priv)
  }
}

/** An opened scope: its opaque identity + the in-memory **scope key** (owned transiently by the caller; `key` is the
 *  same bytes as `epochs.key`, the one key of the epoch the grant carries, ADR 0115 decision 1) + the decrypted display
 *  `name` for a named scope (a GROUP; undefined else). */
export type OpenedScope = {
  scopeId: string; tenantId: string; kind: ScopeKind; key: Uint8Array; epochs: ScopeKey; nameEpoch: number; name?: string; deletedAt: Date | null; purgeAfter: Date | null
  /** The caller's own role in the scope, as its row carries it (`OWNER` / `MEMBER`). */
  role?: ScopeSummaryRoleEnum
  /** A GROUP's name's value key, wrapped under the scope key (ADR 0115) — what a pending invitee is handed the key from
   *  ({@link wrapGroupNameKeyForInvitee}). Absent for another kind and for a row that serves no name (the rotation plan's). */
  wrappedNameValueKey?: string | null
}

/** The caller as a grant's recipient — the account id and the identity bundle the vault holds. */
const recipientOf = (self: Me) => ({ userId: self.userId, identityPublicKeyB64: self.pubB64 })

/**
 * Open our **own** grant on a scope row with the vaulted identity → the in-memory epoch key. Caller owns it. The
 * grant is bound to (this row's scope, its granted epoch) (ADR 0072): a grant the server moved here from another
 * scope's row — or from another epoch — is refused, never opened as this scope's key. And it is opened only once
 * its granter's signature verifies (ADR 0028 Decision 6, {@link verifyOwnGrant}): the key inside a refused grant is
 * never used — {@link GrantRefused} is thrown before the unwrap, and the scope does not open.
 *
 * Never below this device's epoch floor for the scope (ADR 0024 Decision 6): an older grant the server serves again is
 * refused ({@link AwaitingCurrentKey}) on every path that opens one — a scope, the switcher's names and icons, a slug —
 * so no path decrypts under an epoch this device already knows to be superseded.
 *
 * A grant at or above the epoch this device holds must succeed the key it holds (ADR 0115 decision 5,
 * {@link requireSuccessionFromHeld}), else it is refused as `epoch_unlinked` before the key is used. A grant that opened
 * is then held: this device's epoch floor for the scope rises to its epoch (ADR 0024 Decision 6) — the epoch is authentic
 * because the grant is bound to it and signed — and its key becomes the one a later epoch's statements must start from.
 */
async function openOwnGrant(
  scope: OwnGrantRow, priv: { kemSeed: Uint8Array; sign: Uint8Array }, self: Me,
): Promise<Uint8Array> {
  if (scope.grantedEpoch < (await epochFloor(scope.id))) throw new AwaitingCurrentKey(scope.id)
  await verifyOwnGrant({ scopeId: scope.id, kind: scope.kind, epoch: scope.grantedEpoch }, scope.wrappedGrant, scope, self, scope.tenantId)
  const key = v1.openAs(grantContext(scope.id, scope.grantedEpoch), v1.hybridSecret(priv), fromB64std(scope.wrappedGrant))
  try {
    await requireSuccessionFromHeld(scope, key)
  } catch (e) {
    key.fill(0)
    throw e
  }
  await holdEpochKey(scope.id, scope.grantedEpoch, key)
  return key
}

/**
 * A new epoch counts only if it succeeds the key this device held (ADR 0115 decision 5, ADR 0111 decision 23, inbox
 * #650). The floor refuses a lower epoch NUMBER; this refuses an unproven KEY: an operator can bump a scope's epoch and
 * serve a grant at the new one around a key of its own choosing — sealed to this account's public key, signed by a
 * granter it fabricated and the pin meets for the first time — but it cannot sign the succession from `E_held`'s
 * succession key, because it never held `E_held`. So the served statements must lead, one per epoch, from the succession
 * public key this device kept for the key it held to exactly the one the grant's key derives (at the same epoch: be that
 * key). Throws {@link GrantRefused} `epoch_unlinked` otherwise — the scope then waits for a current grant, the state
 * every refused grant reads as, and a rotation declares it unreadable. Nothing in a statement opens anything.
 *
 * Passes with nothing held (a device's first grant of the scope: the invite's anchor vouched for a newcomer's, ADR 0111
 * decision 19) and for a grant below the held epoch, which is no new epoch: the floor is its check. A member pending
 * across several epochs checks every statement in between; an account's securing self-grants the very key it held.
 */
async function requireSuccessionFromHeld(scope: OwnGrantRow, key: Uint8Array): Promise<void> {
  const held = await heldEpochKey(scope.id)
  if (!held || scope.grantedEpoch < held.epoch) return
  const unlinked = () => new GrantRefused(scope.id, 'epoch_unlinked')
  if (scope.grantedEpoch === held.epoch) {
    if (epochKeyCommitment(key) !== held.commitment) throw unlinked()
    return
  }
  let statements: v1.SuccessionLink[]
  try {
    statements = await listScopeSuccessions(scope.id)
  } catch (e) {
    // No statements for a scope this account is not yet a member of (a pending proposal: 404) or holds no grant in (403):
    // nothing can prove the key. Any other failure says nothing about it.
    if (isHttpStatus(e, HttpStatus.notFound) || isHttpStatus(e, HttpStatus.forbidden)) throw unlinked()
    throw e
  }
  const target = v1.successionKeyPair(key)
  target.privateKey.fill(0)
  const proven = v1.verifySuccessionPath({
    scopeId: scope.id, heldEpoch: held.epoch, heldPublicKey: fromB64std(held.commitment), statements,
    targetEpoch: scope.grantedEpoch, targetPublicKey: target.publicKey,
  })
  if (!proven) throw unlinked()
}

/** Our grant is older than the scope's current epoch (ADR 0024 Decision 6): a rotation skipped us, or its re-grant
 *  has not reached us yet. The scope reads as awaiting the current key — nothing is written into it, and since the
 *  rotation replaced the key and moved everything to the new one, nothing of it opens either (ADR 0115
 *  decision 8). The same
 *  state, with the same presentation, answers a grant below this device's own floor for the scope: an older epoch
 *  than one this device has held, whatever `currentEpoch` the server pairs it with. */
class AwaitingCurrentKey extends Error {
  constructor(readonly scopeId: string) {
    super(`awaiting_current_key: ${scopeId}`)
    this.name = 'AwaitingCurrentKey'
  }
}

/** `open` with a refused grant — or one a rotation left behind — read as no open scope: it waits for the current key,
 *  not an error (ADR 0028 Decision 6, ADR 0024 Decision 6). A new member never waits here: every membership is born
 *  with a grant (ADR 0111). */
async function orUnopened<T>(open: () => Promise<T | null>): Promise<T | null> {
  try {
    return await open()
  } catch (e) {
    if (e instanceof GrantRefused || e instanceof AwaitingCurrentKey) return null
    throw e
  }
}

/** Open a scope summary → its one key + (for a GROUP) its decrypted name, sealed under `K_name` derived from that key
 *  (cr-0096/ADR 0027, ADR 0115 decision 1).
 *
 *  **No write under a known-stale epoch** (ADR 0024 Decision 6, layer 1): a grant older than the scope's current
 *  epoch holds a key every member removed since also holds, so content sealed under it would be readable by them.
 *  Such a scope is not opened ({@link AwaitingCurrentKey}); the grant-delivery reconcile re-grants it. Only a
 *  rotation opens it anyway (`staleGrant: 'open'`): it states the epoch it creates, which the server refuses unless
 *  it is the scope's next, so a lagging rotator gets `stale_epoch` — the answer its callers already handle.
 *
 *  **The floor is the half an active operator cannot argue with** (layer 2): a grant below the highest epoch this
 *  device has held for the scope is refused the same way, and a rotation is no exception — one built on it would
 *  state an epoch this device knows already exists. Reads are refused with it (owner decision, 2026-09-28): one
 *  closed state for both halves, never a read-only open. */
async function toOpenedScope(
  scope: OwnGrantRow, priv: { kemSeed: Uint8Array; sign: Uint8Array }, self: Me,
  staleGrant: 'refuse' | 'open' = 'refuse',
): Promise<OpenedScope> {
  if (staleGrant === 'refuse' && scope.grantedEpoch < scope.currentEpoch) throw new AwaitingCurrentKey(scope.id)
  const grantKey = await openOwnGrant(scope, priv, self)
  const epochs = scopeKeyOf(grantKey, scope.grantedEpoch)
  let name: string | undefined
  if (scope.kind === ScopeKind.Group && scope.encryptedName) {
    try {
      name = openGroupName(scope.id, epochs, scope.nameEpoch ?? 0, scope.encryptedName, scope.wrappedNameValueKey)
    } catch {
      name = undefined // a name that won't open (E2) is non-fatal — the scope still opens, just unlabelled
    }
  }
  return {
    scopeId: scope.id, tenantId: scope.tenantId, kind: scope.kind, key: epochs.key, epochs, nameEpoch: scope.nameEpoch ?? 0, name,
    deletedAt: scope.deletedAt ?? null, purgeAfter: scope.purgeAfter ?? null, role: scope.role,
    wrappedNameValueKey: scope.wrappedNameValueKey ?? null,
  }
}

/** What opening the caller's own grant reads of a scope row: a {@link ScopeSummary} of the active tenant's list, or a
 *  row of the account rotation's plan (uc.account.rotate-keys), which serves the same grant fields for every
 *  tenant but no name, no deletion dates and no role. */
type OwnGrantRow = Pick<
  ScopeSummary,
  'id' | 'kind' | 'tenantId' | 'currentEpoch' | 'grantedEpoch' | 'wrappedGrant' | 'granterUserId' | 'grantSignature' | 'granterIdentityPublicKey'
> & Partial<Pick<ScopeSummary, 'encryptedName' | 'wrappedNameValueKey' | 'nameEpoch' | 'deletedAt' | 'purgeAfter' | 'role'>>

const rowOfPlan = (row: RotationPlanScope): OwnGrantRow => ({
  id: row.scopeId, kind: row.kind, tenantId: row.tenantId, currentEpoch: row.currentEpoch, grantedEpoch: row.grantedEpoch,
  wrappedGrant: row.wrappedGrant, granterUserId: row.granterUserId, grantSignature: row.grantSignature,
  granterIdentityPublicKey: row.granterIdentityPublicKey,
})

/**
 * The current epoch key of a scope the account rotation's plan lists (ADR 0111 decision 6), opened with the
 * vault's identity exactly as {@link openScopeById} opens a row of the active tenant — the grant verified
 * ({@link verifyOwnGrant}), never below this device's epoch floor, and refused when it lags the scope's current epoch —
 * but from the plan, which serves every tenant's rows (Host-independent). The caller owns and zeroes the key.
 * Throws a refusal {@link isUnreadableGrant} recognises when this device cannot open the grant: the rotation then
 * declares the scope unreadable rather than leaving it out.
 */
export async function openPlanGrantKey(row: RotationPlanScope): Promise<Uint8Array> {
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot open a grant')
  try {
    const scope = rowOfPlan(row)
    if (scope.grantedEpoch < scope.currentEpoch) throw new AwaitingCurrentKey(scope.id)
    return await openOwnGrant(scope, priv, await me(priv))
  } finally {
    zero(priv)
  }
}

/** Whether `e` is this device's refusal of the caller's own grant — a signature, a granter's key or the epoch floor
 *  (ADR 0028 Decision 6, ADR 0024 Decision 6) — rather than a failure that says nothing about the grant. */
export function isUnreadableGrant(e: unknown): boolean {
  return e instanceof GrantRefused || e instanceof AwaitingCurrentKey
}

/**
 * A {@link RotationOpener} over a row of the account rotation's plan — what finishing a secured account commits each
 * scope's next epoch from (ADR 0111 decision 8): the self-grant the publish wrote, opened with the vault's
 * (published) identity whatever tenant the scope lives in. The first open uses `row`; a rebuild after `409 stale_epoch`
 * re-reads the plan through `reread`, and when the scope's epoch moved past `row`'s it throws {@link EpochMovedOn}:
 * someone else's commit met the scope, which is what the rebuild must not answer with yet another epoch.
 */
export function planOpener(row: RotationPlanScope, reread: () => Promise<RotationPlanScope | undefined>): RotationOpener {
  let first = true
  return async (scopeId) => {
    const now = first ? row : await reread()
    first = false
    if (!now || now.scopeId !== scopeId) return null
    if (now.currentEpoch !== row.currentEpoch) throw new EpochMovedOn(scopeId)
    const priv = identityPrivate()
    if (!priv) return null
    try {
      return await toOpenedScope(rowOfPlan(now), priv, await me(priv), 'open')
    } finally {
      zero(priv)
    }
  }
}

/** A scope's epoch moved on between reading it and committing its next one — another member's commit. */
export class EpochMovedOn extends Error {
  constructor(readonly scopeId: string) {
    super(`epoch_moved_on: ${scopeId}`)
    this.name = 'EpochMovedOn'
  }
}

/**
 * Open the caller's scope of the given `kind` → its identity + in-memory scope key (open our **own** grant with the
 * vaulted identity), or null if there is no such scope / the vault is locked.
 * This is **open-only** — it never mints a scope. The caller owns the returned keys transiently.
 */
export async function openScope(kind: ScopeKind): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.kind === kind)
    return scope ? await orUnopened(async () => toOpenedScope(scope, priv, await me(priv))) : null
  } finally {
    zero(priv)
  }
}

/**
 * Open a specific scope by its opaque id → its identity + in-memory scope key, or null if the caller holds no
 * such scope / the vault is locked. Used to enter a group from the `#@<id>` fragment (en.spaces.group-scope).
 */
export async function openScopeById(scopeId: string): Promise<OpenedScope | null> {
  return openById(scopeId, 'refuse')
}

/**
 * Open a scope of a {@link listMyScopes} answer the caller already holds → its identity + in-memory scope key, exactly as
 * {@link openScopeById} opens it (the grant verified, a stale grant refused), without listing the scopes again: the search
 * opens every space of one list in a row (ADR 0125 decision 3). Null when the vault is locked or the grant does not open.
 */
export async function openListedScope(scope: ScopeSummary): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    return await orUnopened(async () => toOpenedScope(scope, priv, await me(priv)))
  } finally {
    zero(priv)
  }
}

/** {@link openScopeById} with the stale-grant rule chosen — `'open'` only for {@link commitRotation}. */
async function openById(scopeId: string, staleGrant: 'refuse' | 'open'): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.id === scopeId)
    return scope ? await orUnopened(async () => toOpenedScope(scope, priv, await me(priv), staleGrant)) : null
  } finally {
    zero(priv)
  }
}

/** A group's readable URL slug — `slugify` of its decrypted name (cr-0120), or null when it has no openable name.
 *  Decrypts under the scope key and zeroes it per call (the name stays operator-blind). */
async function groupSlug(
  g: ScopeSummary,
  priv: { kemSeed: Uint8Array; sign: Uint8Array },
  self: Me,
): Promise<string | null> {
  if (!g.encryptedName) return null
  let epochs: ScopeKey | null = null
  try {
    epochs = scopeKeyOf(await openOwnGrant(g, priv, self), g.grantedEpoch)
    return slugify(openGroupName(g.id, epochs, g.nameEpoch, g.encryptedName, g.wrappedNameValueKey))
  } catch {
    return null
  } finally {
    if (epochs) zeroScopeKey(epochs)
  }
}

/**
 * Open a group from its URL fragment token (en.spaces.group-scope, cr-0120). The token is either the group's
 * readable **slug** (`slugify(name)` — the canonical form for a uniquely-named group) or its opaque **scopeId**
 * (a legacy `#@<id>` link, or the fallback form a name-collision emits). Resolved against the caller's own scope
 * list in one fetch: an exact id match wins with no name decryption; otherwise the group whose decrypted name
 * slugifies to the token — but only when **exactly one** does (groups carry no server-enforced slug uniqueness,
 * unlike content nodes, so an ambiguous slug resolves to nothing → the caller falls back to Personal). Null when
 * the vault is locked or nothing matches.
 */
export async function openGroupByToken(token: string): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const groups = (await listMyScopes()).filter((s) => s.kind === ScopeKind.Group)
    const self = await me(priv)
    let scope = groups.find((s) => s.id === token)
    if (!scope) {
      const slugs = await Promise.all(groups.map((g) => groupSlug(g, priv, self)))
      const matches = groups.filter((_, i) => slugs[i] === token)
      if (matches.length === 1) scope = matches[0]
    }
    const found = scope
    return found ? await orUnopened(() => toOpenedScope(found, priv, self)) : null
  } finally {
    zero(priv)
  }
}

/** Open the member's personal scope → just its key (thin wrapper over {@link openScope}; used by e2e harnesses). */
export async function openPersonalScope(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Personal))?.key ?? null
}

/**
 * Open a group's name's value key `K_v` (from its wrap under the scope key, ADR 0115) and seal it to a pending invitee's
 * identity (uc.spaces.consent-group-add, cr-0096/ADR 0027) → the `sealedNameKey` to post with the consent proposal. The
 * invitee unwraps it to open the **same canonical `encryptedName`** the members read — so a modified owner cannot
 * privately show a divergent name. Returns null if the caller can't open the scope (vault locked / no grant) or the
 * name's key (a wrap that does not open: a rename mints a fresh one). Requires an unlocked vault; the transient scope
 * key + `K_v` are zeroed before returning.
 */
export async function wrapGroupNameKeyForInvitee(
  scopeId: string,
  inviteeUserId: string,
  invitee: ServedIdentity,
): Promise<string | null> {
  // The anchor gate (ADR 0028 Decision 2): this is the invite's first wrap to the invitee's *foreign*
  // identity — pinned on first sight, a key the chain reaches from the pinned head proceeds, an anomaly is never sealed
  // to: the caller catches IdentityUnanchoredError and says so, with no accept.
  const tenant = await tenantViewOfScope(scopeId)
  if ((await anchorIdentity(inviteeUserId, invitee, { tenant })) === 'anomaly') throw new IdentityUnanchoredError(inviteeUserId)
  const inviteeIdentityPublicKeyB64 = invitee.identityPublicKey
  const opened = await openScopeById(scopeId)
  if (!opened) return null
  try {
    const { nameEpoch } = opened
    return openValue(opened.epochs, groupNameContext(scopeId, nameEpoch), opened.wrappedNameValueKey, (nameKey) =>
      wrapNameKeyToInvitee(nameKey, scopeId, nameEpoch, inviteeIdentityPublicKeyB64))
  } catch {
    return null
  } finally {
    zeroScopeKey(opened.epochs)
  }
}

/**
 * The new name's `K_v` sealed to each pending consent invitee of [scopeId] (`inviteeNameKeys`, ADR 0115): a rename mints
 * a fresh key, so every invitee that held the old one is handed the new one, or the server refuses the rename (`409
 * rename_incomplete`). Each goes only to a key the invitee's chain reaches from this device's pin — the anchor check the
 * consent proposal's wrap makes ({@link wrapGroupNameKeyForInvitee}, ADR 0028 Decision 2); an invitee held as an anomaly
 * gets its name key cleared (both nulls) — an omission, allowed and visible (ADR 0111 decision 9): the proposal stands,
 * shown without the group's name. Sequential so the pin store's version-guarded writes don't race each other.
 */
async function inviteeNameKeysFor(scopeId: string, nameEpoch: number, nameKey: Uint8Array): Promise<InviteeNameKey[]> {
  const pending = (await withRefresh(() => scopesApi().listScopeMembers({ scopeId }))).filter((m) => m.pendingConsent === true)
  if (pending.length === 0) return []
  const tenant = await tenantViewOfScope(scopeId)
  const out: InviteeNameKey[] = []
  for (const m of pending) {
    if ((await anchorIdentity(m.userId, m, { tenant })) === 'anomaly') {
      out.push({ userId: m.userId, sealedNameKey: null, recipientIdentityDigest: null })
    } else {
      out.push({
        userId: m.userId, sealedNameKey: wrapNameKeyToInvitee(nameKey, scopeId, nameEpoch, m.identityPublicKey),
        recipientIdentityDigest: recipientIdentityDigest(m.identityPublicKey),
      })
    }
  }
  return out
}

/**
 * Rename a group (uc.spaces.rename-group, cr-0097/ADR 0027) — operator-blind: seal the new name under a **fresh** value
 * key `K_v` (ADR 0115), wrap `K_v` under the caller's scope key at its epoch, seal it to each pending invitee
 * ({@link inviteeNameKeysFor}) and POST `{ encryptedName, nameEpoch, wrappedNameValueKey, inviteeNameKeys }`. A fresh
 * `K_v` makes the rename forward-secret: a member removed at an earlier epoch never held it. The server accepts it only
 * when `nameEpoch == currentEpoch`; a stale caller (a rotation moved the epoch under them) gets `409 stale_epoch`, and an
 * invitee list a proposal made meanwhile outdated `409 rename_incomplete` or `recipient_key_not_current`. We refetch +
 * retry **once** — grant-delivery may have re-granted us to the current epoch between the two attempts, and the members
 * are read again. Requires an unlocked vault + OWNER on the group (enforced server-side). Throws on a persistent failure.
 */
export async function renameGroup(scopeId: string, newName: string): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open group to rename')
    const nameEpoch = opened.epochs.epoch // seal under the epoch we hold; the server gates it == current
    const nameKey = v1.generateValueKey()
    try {
      const encryptedName = sealGroupName(nameKey, scopeId, nameEpoch, newName)
      const wrappedNameValueKey = wrapValueKeyOf(opened.epochs, groupNameContext(scopeId, nameEpoch), nameKey)
      const inviteeNameKeys = await inviteeNameKeysFor(scopeId, nameEpoch, nameKey)
      await withRefresh(() => scopesApi().renameGroup({
        scopeId, renameGroupRequest: { encryptedName, nameEpoch, wrappedNameValueKey, inviteeNameKeys },
      }))
      return
    } catch (e) {
      // a concurrent rotation, or a proposal made or a key moved meanwhile — refetch the epoch and the members, retry once
      if (attempt === 0 && (await isRenameRebuildable(e))) continue
      throw e
    } finally {
      nameKey.fill(0)
      zeroScopeKey(opened.epochs)
    }
  }
}

/** Whether a refused rename is built again from a fresh read: a stale epoch, or an invitee list that no longer covers the
 *  group's pending proposals or their keys. */
async function isRenameRebuildable(e: unknown): Promise<boolean> {
  return (await isStaleEpoch(e))
    || (await isCodedError(e, HttpStatus.conflict, 'rename_incomplete'))
    || isCodedError(e, HttpStatus.conflict, 'recipient_key_not_current')
}

/** Whether a failed call is a `409 stale_epoch`: the epoch the call was built on is not the scope's — a rotation
 *  advanced it under a rename or icon set, or a rotation was itself built on a superseded epoch. */
export function isStaleEpoch(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_epoch')
}

/** How often {@link commitRotation} rebuilds after a rebuildable refusal: once (ADR 0024 Decision 4). A bound, not a
 *  retry policy: a server that refuses every attempt gets two, never a loop. */
export const ROTATION_REBUILDS = 1

/** Whether a failed rotation is worth a rebuild: built on a superseded epoch, or on a member list or a list of open
 *  invites a concurrent join or invite changed (`409 rotation_incomplete`, ADR 0111 decisions 2 and 9). */
async function isRebuildable(e: unknown): Promise<boolean> {
  return (await isStaleEpoch(e)) || (await isCodedError(e, HttpStatus.conflict, 'rotation_incomplete'))
    // the scope-level re-wrap's listing or stage, read with a grant that lags the scope (ADR 0115 decision 2)
    || isCodedError(e, HttpStatus.forbidden, 'grant_behind')
}

/**
 * Set or replace a space's icon (uc.spaces.set-space-icon) — operator-blind: bake+encode is the caller's, here we
 * seal the encoded bytes under a fresh value key `K_v`, wrap `K_v` under the caller's scope key at its epoch (ADR 0115)
 * and POST `{ sealedIcon, iconEpoch, wrappedIconValueKey }`. The server accepts it only when `iconEpoch == currentEpoch`; a stale caller
 * (a concurrent rotation) gets `409 stale_epoch`, so we refetch + retry **once** — the same shape as
 * {@link renameGroup}. The padding to a constant length is intrinsic to {@link sealIcon}, so every stored icon is
 * byte-identical in size. Requires an unlocked vault + OWNER on the scope (enforced server-side).
 */
export async function setSpaceIcon(scopeId: string, encoded: Uint8Array): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open space to set its icon')
    const iconEpoch = opened.epochs.epoch // seal under the epoch we hold; the server gates it == current
    try {
      const address = { scopeId, iconEpoch }
      const icon = sealValue(opened.epochs, iconContext(address), (iconKey) => fromB64std(sealIcon(iconKey, address, encoded)))
      await withRefresh(() => scopesApi().setScopeIcon({
        scopeId, setScopeIconRequest: { sealedIcon: icon.sealed, iconEpoch, wrappedIconValueKey: icon.wrapped },
      }))
      return
    } catch (e) {
      if (attempt === 0 && (await isStaleEpoch(e))) continue // a concurrent rotation — refetch the epoch + retry once
      throw e
    } finally {
      zeroScopeKey(opened.epochs)
    }
  }
}

/** Remove a space's icon (uc.spaces.set-space-icon A5) — every member falls back to the initial. No epoch (a
 *  removal carries no ciphertext to be stale); idempotent. Owner-gated server-side. */
export async function clearSpaceIcon(scopeId: string): Promise<void> {
  await withRefresh(() => scopesApi().clearScopeIcon({ scopeId }))
}

/**
 * Open a tenant's icon for the switcher (uc.spaces.set-space-icon, en.spaces.tenant-scope) — unwrap the caller's
 * **own** grant on that tenant transiently, open the icon's value key from its wrap, and open + unpad the `sealedIcon` →
 * the encoded image bytes, or **null** (no icon set, or it won't open → the caller renders the initial). Zeroes the
 * scope key + the identity copy in `finally` (the {@link listMyGroups} posture).
 *
 * A rotation re-wraps the icon's value key under its new key (ADR 0115), so the wrap opens with the grant key. A grant
 * behind the tenant's current epoch holds a key no wrap is under any more: the icon returns null → the initial (never a
 * throw).
 */
export async function openMyTenantIcon(tenant: MyTenant): Promise<Uint8Array | null> {
  if (!tenant.sealedIcon || !tenant.wrappedGrant || tenant.grantedEpoch == null) return null
  const priv = identityPrivate()
  if (!priv) return null
  let scopeKey: Uint8Array | null = null
  try {
    // The tenant scope's id IS the tenant id; the grant is bound to it at the epoch the row states (ADR 0072), and
    // it opens only once its granter's signature verifies (ADR 0028 Decision 6) — a refusal renders the initial.
    await verifyOwnGrant(
      { scopeId: tenant.tenantId, kind: ScopeKind.Tenant, epoch: tenant.grantedEpoch }, tenant.wrappedGrant, tenant, await me(priv),
    )
    scopeKey = v1.openAs(
      grantContext(tenant.tenantId, tenant.grantedEpoch), v1.hybridSecret(priv), fromB64std(tenant.wrappedGrant),
    )
    return openSpaceIconAt(
      tenant.tenantId, { epoch: tenant.grantedEpoch, key: scopeKey }, tenant.iconEpoch, tenant.sealedIcon, tenant.wrappedIconValueKey,
    )
  } catch {
    return null
  } finally {
    scopeKey?.fill(0)
    zero(priv)
  }
}

/**
 * Open a single group's icon for its settings-panel header (uc.spaces.set-space-icon) — unwrap the caller's own
 * grant on that group transiently, open the icon's value key from its wrap, and open + unpad the `sealedIcon` → the encoded image
 * bytes, or **null** (no icon / unopenable → the initial). Zeroes the scope key + the identity copy in `finally`. The
 * batched sidebar/hub read goes through {@link listMyGroups} (which opens every group's icon in one pass); this is
 * the single-scope read the owner's panel re-runs after a set/replace/remove.
 */
export async function openGroupIcon(scopeId: string): Promise<Uint8Array | null> {
  const priv = identityPrivate()
  if (!priv) return null
  let epochs: ScopeKey | null = null
  try {
    const g = (await listMyScopes()).find((s) => s.id === scopeId && s.kind === ScopeKind.Group)
    if (!g || !g.sealedIcon) return null
    epochs = scopeKeyOf(await openOwnGrant(g, priv, await me(priv)), g.grantedEpoch)
    return openSpaceIconAt(g.id, epochs, g.iconEpoch, g.sealedIcon, g.wrappedIconValueKey)
  } catch {
    return null
  } finally {
    if (epochs) zeroScopeKey(epochs)
    zero(priv)
  }
}

/**
 * Remove a member from a group — the removal and its rotation in **one request** (ADR 0024 Decision 4): a
 * {@link commitRotation} that names the member it removes, so the server deletes the membership and commits the new
 * epoch in one transaction. A failed call leaves the member in place; a closed tab cannot leave a removal without its
 * rotation. New content thereafter seals under `E_{n+1}`, which the removed member never holds. Returns the new
 * epoch index. Requires an unlocked vault (opens the caller's own grant to derive `E_n`).
 *
 * **Excluded by the remover's own choice.** `pickedFrom` is the directory the owner picked the member from, read
 * **before** the removal; every row carrying `removedUserId` is dropped from it — a duplicate included — so a server
 * that re-lists the removed member cannot get the new epoch wrapped to their genuine key — nor in a rebuild, which reads
 * the directory again and drops them from it the same way. Never pass a list read after the removal: that is the
 * server's word on who remains, not the owner's.
 *
 * `revokeLinks` (ADR 0075) also turns off every public link of the group in the same transaction — any of them the
 * removed member could have copied; each link's owner then renews the ones still needed.
 */
export async function rotateGroupForRemoval(
  scopeId: string, pickedFrom: ScopeMember[], removedUserId: string, revokeLinks = false,
): Promise<number> {
  return commitRotation(scopeId, pickedFrom, { removes: removedUserId, revokeLinks })
}

/** One re-grant a rotation carries: the new epoch key wrapped to a member's identity, signed by the rotator, naming the
 *  key it is wrapped to (ADR 0111 decision 10: the server refuses one to a key that is not the member's current one). */
export type RotationGrant = { userId: string; recipientIdentityDigest: string } & SignedGrant

/** Who performs a rotation: the account, the identity bundle its own re-grant is sealed to, and the key that signs
 *  every re-grant — always the vault's identity (an account rotation swaps the vault before it commits any epoch,
 *  uc.account.rotate-keys). */
type Rotator = { userId: string; identityPublicKeyB64: string; signPrivateKey: Uint8Array }

/** Opens the scope a rotation is built on — the caller's own grant, at whatever epoch it lags (`E_n`). */
export type RotationOpener = (scopeId: string) => Promise<OpenedScope | null>

/** How {@link commitRotation} runs beyond the plain rotation. */
export type CommitRotationOptions = {
  /** A removal-rotation: the member with this id is dropped from the grants and removed in the same commit. */
  removes?: string
  /** A member the caller leaves out by its own choice without removing them here — a tenant owner's re-key after the
   *  removal cascade took them out (ADR 0025 § 2): dropped from every member list the rotation builds from, the one
   *  handed in and every re-read, and never named in the request. */
  excluding?: string
  /** Turn off every public link of the scope in the same commit (ADR 0075). */
  revokeLinks?: boolean
  /** Where the caller's own grant is opened from: the active tenant's scope list by default; the account rotation's
   *  finish passes {@link planOpener}, which reads every tenant's rows (ADR 0111 decision 8). */
  open?: RotationOpener
  /** Where the scope's TENANT is opened from, to read who is anchored there (ADR 0111 decision 15): the active tenant's
   *  list by default; the finish passes the plan's tenant row. */
  openTenant?: TenantOpener
}

/**
 * Carry a tenant's open member invites into a new epoch (ADR 0111 decision 2): an invite is a placeholder member, so it
 * is re-granted like one — wrapped to its invite key, with the invite's id as the grant's recipient — once the owner's
 * signature over its key verifies under the signer's served key and that key is anchored ({@link anchorIdentity}: first
 * use pins it, a key the signer's chain reaches from the pinned head passes, an anomaly does not). Any other is
 * **declared pending**: it keeps the grant it holds, and its newcomer joins
 * behind and is brought current by the grant delivery. The server refuses a rotation that names neither for an open one.
 */
async function carryOpenInvites(
  scopeId: string, newKey: Uint8Array, next: GrantScope, signPrivateKey: Uint8Array, tenant: TenantStandings,
): Promise<{ inviteGrants: InviteGrantEntry[]; pendingInviteIds: string[] }> {
  const inviteGrants: InviteGrantEntry[] = []
  const pendingInviteIds: string[] = []
  // Sequential, as the member re-grants: the pin store's version-guarded writes must not race each other.
  for (const open of await withRefresh(() => scopesApi().listOpenInvites({ scopeId }))) {
    if (await inviteVouched(scopeId, open, tenant)) {
      const to = { userId: open.inviteId, identityPublicKeyB64: open.invitePublicKey }
      inviteGrants.push({ inviteId: open.inviteId, ...sealGrant(newKey, next, to, signPrivateKey), recipientIdentityDigest: recipientIdentityDigest(open.invitePublicKey) })
    } else {
      pendingInviteIds.push(open.inviteId)
    }
  }
  return { inviteGrants, pendingInviteIds }
}

/** Whether an open invite's key is the one its owner signed, under a signer key this device anchors (the signer's
 *  served key and `signerKeyChainHead`, {@link anchorIdentity}). False — never a throw — for anything malformed: every
 *  byte here came from the server. */
async function inviteVouched(tenantId: string, open: OpenInvite, tenant: TenantStandings): Promise<boolean> {
  try {
    const signer = v1.deserializeIdentityPublic(fromB64std(open.signerIdentityPublicKey))
    const signed = invite.inviteKeySignatureVerifies(
      signer.sign,
      { tenantId, inviteId: open.inviteId, invitePublicKey: fromB64std(open.invitePublicKey), expiresAt: Math.floor(open.expiresAt.getTime() / 1000) },
      fromB64std(open.inviteKeySignature),
    )
    // Only a signature that verified may pin its signer's key (as for a grant's granter, ADR 0028 Decision 6).
    if (!signed) return false
    const served = { identityPublicKey: open.signerIdentityPublicKey, keyChainHead: open.signerKeyChainHead }
    return (await anchorIdentity(open.signerUserId, served, { tenant })) !== 'anomaly'
  } catch {
    return false
  }
}

/**
 * Commit a scope-key rotation (ADR 0115) — the one path every rotation takes. Open the caller's own grant (`E_n`), mint a
 * fresh `E_{n+1}` that REPLACES it (decision 1: nothing seals `E_n` under it), re-grant the members — and, for a TENANT,
 * carry every open member invite ({@link carryOpenInvites}) —, re-wrap every scope-level value key (and re-seal the scope-root entries) under the new key and stage
 * it ({@link stageScopeLevelRekey}, decision 2), sign the succession from `E_n` (decision 5) and commit **stating the
 * epoch it creates**, `n+1`. `E_n` is our grant, not the scope's epoch, so the server takes the commit only as the
 * scope's current epoch + 1: a rotation that lost a race or rode a lagging grant is `409 stale_epoch` (or, at the stage,
 * `403 grant_behind`) with nothing stored. On that answer — or on `409 rotation_incomplete`, a member who joined or an
 * invite minted meanwhile — the whole attempt runs **once** more ({@link ROTATION_REBUILDS}) from a re-opened grant,
 * over the scope's members **read again** (`listScopeMembers`) and its open invites read again: never the list the
 * caller was handed, which is what the server just found incomplete (inbox #661). One rebuild, never a loop, so a server
 * that answers every attempt with a conflict cannot keep this device minting keys.
 *
 * A row written while the rotation staged answers the stage or the commit `409 rekey_parts_stale` (or a row that appeared
 * since, `rekey_parts_missing`): the rows are listed and staged again under the same key and re-key id and the same
 * commit sent again, at most {@link REKEY_RESTAGES} times — the same grants to the same members, so no new recipient
 * meets the key. After the commit nothing at scope level is under the old key, and every node of the scope is due for
 * its own re-key, which the caller runs (`core/node/rekey/rekeyScope.ts`).
 *
 * Every member whose key is anchored ({@link anchorIdentity}: `firstUse`, `match` or `advanced`) is re-granted — a
 * pending consent proposal too, which holds a grant from its proposal on (ADR 0111 decision 4) —
 * and the rotator itself unconditionally, to the identity it holds; every other member
 * (an `anomaly`) is named in `pendingUserIds`, so the request covers every membership but the removed one (ADR 0111
 * decision 9, `409 rotation_incomplete`).
 *
 * The key is minted inside the attempt, never hoisted: the refused attempt's wraps already left the device, and one
 * may be addressed to a member removed in the meantime — reusing that key would give the server a grant it could
 * hand them. Returns the new epoch; a second stale answer (our grant still lags) is thrown, see {@link isStaleEpoch}.
 *
 * With `removes` it is a removal-rotation ({@link rotateGroupForRemoval}): no row with that id is carried, and the
 * request names them so the server deletes their membership in the same commit. `excluding` drops its id from every
 * list the same way, without naming it. Both hold for the re-read list too: however the server lists the member a caller
 * leaves out, a rebuild never wraps the new epoch to them. With `open`, the caller's grant comes
 * from there instead of the active tenant's list — the account rotation's finish, whose scopes may live in any tenant.
 *
 * Every member the caller is handed is judged — however many there are (ADR 0111 decision 8: the finish of
 * a secured account grants whoever is a member then, one who joined after the publish included).
 */
export async function commitRotation(
  scopeId: string, members: ScopeMember[], opts: CommitRotationOptions = {},
): Promise<number> {
  const { removes, excluding, revokeLinks = false, open = (id: string) => openById(id, 'open'), openTenant } = opts
  const leftOut = (m: ScopeMember) => m.userId === removes || m.userId === excluding
  for (let attempt = 0; ; attempt++) {
    // The first attempt builds from the list the caller read; a rebuild reads the directory again, because the answer
    // that sent it here may be a membership the caller's list lacks. The caller's exclusions hold for both.
    const listed = attempt === 0 ? members : await withRefresh(() => scopesApi().listScopeMembers({ scopeId }))
    const carry = listed.filter((m) => !leftOut(m))
    // Our grant → the one key we hold (E_n). Opened even when it lags the scope: the server refuses the epoch this
    // rotation states unless it is the next one, which is the stale answer handled below.
    const opened = await open(scopeId)
    if (!opened) throw new Error('cannot open scope to rotate')
    const newKey = v1.randomBytes(32)
    const who = await vaultRotator()
    try {
      // The epoch this rotation creates binds the succession, every grant and every re-wrapped key (ADR 0072) — the one
      // the server checks.
      const epoch = opened.epochs.epoch + 1
      const next: GrantScope = { scopeId, kind: opened.kind, epoch }
      const grants: RotationGrant[] = []
      const pendingUserIds: string[] = []
      const grantTo = (userId: string, identityPublicKeyB64: string) => {
        grants.push({
          userId, ...sealGrant(newKey, next, { userId, identityPublicKeyB64 }, who.signPrivateKey),
          recipientIdentityDigest: recipientIdentityDigest(identityPublicKeyB64),
        })
      }
      // Who is anchored is judged in the scope's tenant (ADR 0111 decisions 12 and 15): a member there without a join
      // record who is no owner — a membership the server made — is declared pending like any anomaly. A PERSONAL scope
      // has no other member to judge, so its tenant is not read.
      const tenant = opened.kind === ScopeKind.Personal ? undefined : await loadTenantView(opened.tenantId, openTenant)
      // Sequential so the pin store's version-guarded writes don't race each other.
      for (const m of carry) {
        // Our own re-grant targets the identity we hold, never the directory's copy of it.
        if (m.userId === who.userId) grantTo(m.userId, who.identityPublicKeyB64)
        // Never to a key the chain does not reach from the pinned head (ADR 0028 Decision 2): that member
        // is DECLARED pending (decision 10) — never skipped silently, the server refuses a rotation that names neither —
        // and any member whose client anchors their key brings them current through the grant delivery. The rotation
        // itself still proceeds: forward secrecy against the removed member cannot hinge on them.
        else if ((await anchorIdentity(m.userId, m, { tenant })) === 'anomaly') pendingUserIds.push(m.userId)
        else grantTo(m.userId, m.identityPublicKey)
      }
      // A tenant's open member invites are placeholder members (ADR 0111 decision 2): each is re-granted or declared.
      const invites = opened.kind === ScopeKind.Tenant && tenant
        ? await carryOpenInvites(scopeId, newKey, next, who.signPrivateKey, tenant)
        : { inviteGrants: [], pendingInviteIds: [] }
      // The succession statement (ADR 0115 decision 5): the new key's succession public key, signed by the old key's.
      // It opens nothing — no stored value makes either key readable from the other.
      const succession = v1.successionKeyPair(newKey)
      succession.privateKey.fill(0)
      const successionSignature = v1.signSuccession(opened.epochs.key, { scopeId, epoch, successionPublicKey: succession.publicKey })
      const rekeyId = crypto.randomUUID()
      const rotateScopeRequest = {
        epoch, successionPublicKey: b64std(succession.publicKey), successionSignature: b64std(successionSignature), rekeyId,
        grants, pendingUserIds, ...invites, removesUserId: removes, revokeLinks: revokeLinks || undefined,
      }
      const run = { scopeId, rekeyId, oldKey: opened.epochs, newKey: { epoch, key: newKey } }
      let rotated: { epoch: number }
      for (let restage = 0; ; restage++) {
        try {
          await stageScopeLevelRekey(run)
          rotated = await withRefresh(() => scopesApi().rotateScope({ scopeId, rotateScopeRequest }))
          break
        } catch (e) {
          // a row written while this rotation staged: list it again and stage again, under the same key and id
          if (restage < REKEY_RESTAGES && (await isRestageable(e))) continue
          throw e
        }
      }
      // This device made the epoch, so it now holds it (ADR 0024 Decision 6) — the epoch it stated, which the
      // server took as the next one; a server that later serves the older grant meets the floor, and a later epoch's
      // succession statements must start from this key (ADR 0115 decision 5).
      await holdEpochKey(scopeId, epoch, newKey)
      return rotated.epoch
    } catch (e) {
      // superseded or incomplete — re-read, re-open and rebuild, from a fresh key, a bounded number of times
      if (attempt < ROTATION_REBUILDS && (await isRebuildable(e))) continue
      throw e
    } finally {
      newKey.fill(0)
      zeroScopeKey(opened.epochs)
      who.signPrivateKey.fill(0)
    }
  }
}

/** The vault's identity as a {@link Rotator} — a copy of its signing key the caller zeroes. */
async function vaultRotator(): Promise<Rotator> {
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot rotate')
  try {
    const self = await me(priv)
    return { userId: self.userId, identityPublicKeyB64: self.pubB64, signPrivateKey: priv.sign.slice() }
  } finally {
    zero(priv)
  }
}

/** Best-effort wipe of the transient identity-private copy the vault handed us. */
function zero(priv: { kemSeed: Uint8Array; sign: Uint8Array }): void {
  priv.kemSeed.fill(0)
  priv.sign.fill(0)
}
