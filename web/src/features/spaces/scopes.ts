// uc.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (sealTo, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { scopesApi, tenantsApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { identityPrivate } from '../../app/vault'
import { slugify } from '../../shared/naming'
import { deriveEpochKeys, keyAt, writeKey, zeroEpochKeys, epochLinkContext, type EpochKeys } from './epochs'
import { admissionVerifies, grantContext, sealGrant, tagAdmission, type GrantScope, type SignedGrant } from './grant'
import { me, verifyOwnGrant, GrantRefused, type Me } from './grantTrust'
import { checkAndPin, IdentityChangedError } from '../account/identityPins'
import { deriveGroupNameKey, wrapNameKeyToInvitee, sealGroupName, openGroupNameWith } from './inviteName'
import { deriveSpaceIconKey, sealIcon, openIcon } from './spaceIcon'
import { ScopeKind, type ScopeSummary, type MyTenant, type ScopeMember } from '../../api/generated/scopes'
import type { PendingMemberInvite } from '../../api/generated/tenants'

/** The seal context of a scope grant (ADR 0072), re-exported — its home is {@link ./grant}, shared with the
 *  grant-delivery worker (cr-0076) + rotation re-grants (cr-0089). */
export { grantContext } from './grant'

/** The caller's scopes in the active tenant (each with their own grant ciphertext). */
export async function listMyScopes(): Promise<ScopeSummary[]> {
  return withRefresh(() => scopesApi().listMyScopes())
}

/** A scope's epoch-chain links (ADR 0024, cr-0089) — so a member peels the chain to read older epochs. */
export async function listScopeEpochs(scopeId: string): Promise<{ epoch: number; wrappedPrev: string }[]> {
  return withRefresh(() => scopesApi().listScopeEpochs({ scopeId }))
}

/** Soft-delete a group (uc.spaces.delete-group, cr-0090) — enters the grace period (owner-gated). */
export async function deleteGroup(scopeId: string): Promise<void> {
  await withRefresh(() => scopesApi().deleteGroup({ scopeId }))
}

/** Restore a soft-deleted group in-window (cr-0090) — clears the pending-deletion marker (owner-gated). */
export async function restoreGroup(scopeId: string): Promise<void> {
  await withRefresh(() => scopesApi().restoreGroup({ scopeId }))
}

/** A scope's pending-deletion state (cr-0090) — null when the caller holds no such scope. No name decryption. */
export async function scopeLifecycle(scopeId: string): Promise<{ deletedAt: Date | null; purgeAfter: Date | null } | null> {
  const s = (await listMyScopes()).find((x) => x.id === scopeId)
  return s ? { deletedAt: s.deletedAt ?? null, purgeAfter: s.purgeAfter ?? null } : null
}

/** Peel the scope's epoch chain from the caller's grant key down to E_0 (ADR 0024). No fetch when unrotated. */
async function epochKeysFor(scope: ScopeSummary, grantKey: Uint8Array): Promise<EpochKeys> {
  const links = scope.grantedEpoch > 0 ? await listScopeEpochs(scope.id) : []
  return deriveEpochKeys(scope.id, grantKey, scope.grantedEpoch, links)
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
    await withRefresh(() => scopesApi().createPersonalScope({ createPersonalScopeRequest: { scopeId, ...grant } }))
    return true
  } finally {
    scopeKey.fill(0)
    zero(priv)
  }
}

/**
 * Create a named GROUP scope in the active tenant (en.spaces.group-scope, cr-0032) — operator-blind: generate a
 * random `K_SC`, **seal the name under its derived name-key `K_name_0`** (from the birth epoch `E_0 = K_SC`,
 * cr-0096/ADR 0027), seal `K_SC` to our **own** identity (the self-grant), and POST only the two ciphertexts. The
 * server mints the scope (at name-epoch 0) + our OWNER self-membership and never sees a key or the name. Requires
 * an unlocked vault. Returns the new scope id (so the caller can switch into `#@<id>`).
 */
export async function createGroup(name: string): Promise<string> {
  const priv = identityPrivate()
  if (!priv) throw new Error('vault is locked — cannot create a group')
  const scopeKey = v1.randomBytes(32)
  const nameKey = deriveGroupNameKey(scopeKey) // K_name_0 = HKDF(E_0), name-epoch 0
  // The client mints the scope's id: the grant and the name are both sealed bound to it (ADR 0072).
  const scopeId = crypto.randomUUID()
  try {
    const encryptedName = sealGroupName(nameKey, scopeId, 0, name)
    const self = await me(priv)
    const grant = sealGrant(scopeKey, { scopeId, kind: ScopeKind.Group, epoch: 0 }, recipientOf(self), priv.sign)
    // The creator self-tags its own membership under E_0 (ADR 0020 move 6), so the first rotation has a tag to carry.
    const admission = tagAdmission(scopeKey, { scopeId, kind: ScopeKind.Group }, 0, self.userId)
    const created = await withRefresh(() =>
      scopesApi().createGroup({ createGroupRequest: { scopeId, encryptedName, ...grant, ...admission } }),
    )
    return created.scopeId
  } finally {
    scopeKey.fill(0)
    nameKey.fill(0)
    zero(priv)
  }
}

/** The group's display name from its epoch keys: derive `K_name` from the name-epoch key, open the canonical
 *  `encryptedName` under it (cr-0096/ADR 0027). Members read the same blob a pending invitee is handed the key for. */
function openGroupName(scopeId: string, epochs: EpochKeys, nameEpoch: number, encryptedName: string): string {
  const nameKey = deriveGroupNameKey(keyAt(epochs, nameEpoch))
  try {
    return openGroupNameWith(nameKey, scopeId, nameEpoch, encryptedName)
  } finally {
    nameKey.fill(0)
  }
}

/** A group's icon from its epoch keys (uc.spaces.set-space-icon): derive `K_icon` from the **icon-epoch** key —
 *  peeled from the chain, because a group's `iconEpoch` may sit below `currentEpoch` (it rotates on member-removal,
 *  ADR 0024, but a rotation never re-seals the icon) — then open + unpad the sealed icon. Null when unopenable →
 *  the caller renders the initial. Unlike {@link openMyTenantIcon}, this must peel the chain (a tenant never rotates). */
function openGroupIconAt(scopeId: string, epochs: EpochKeys, iconEpoch: number, sealedIcon: string): Uint8Array | null {
  const iconKey = deriveSpaceIconKey(keyAt(epochs, iconEpoch))
  try {
    return openIcon(iconKey, { scopeId, iconEpoch }, sealedIcon)
  } catch {
    return null
  } finally {
    iconKey.fill(0)
  }
}

/** A group the member holds — the switcher/settings row: opaque id + tenant + the **decrypted** (client-side)
 *  name + its decrypted `icon` bytes (null → the initial) + the caller's own `role` in it (OWNER/MEMBER, cr-0087 —
 *  lets the settings hub list groups I own). */
export type GroupSpace = { scopeId: string; tenantId: string; name: string; icon: Uint8Array | null; role: string; deletedAt: Date | null; purgeAfter: Date | null }

/**
 * The member's groups in the active tenant with their names decrypted on-device (en.spaces.group-scope). Each
 * group's name is sealed under **its own** `K_SC`, so we open every group's grant to decrypt its name — the
 * server only ever returned the ciphertext name + ciphertext grant. A group whose grant/name won't open (E2, a
 * rotated/foreign grant) is dropped from the switcher rather than crashing it.
 */
export async function listMyGroups(): Promise<GroupSpace[]> {
  const priv = identityPrivate()
  if (!priv) return []
  try {
    const groups = (await listMyScopes()).filter((s) => s.kind === ScopeKind.Group)
    const self = await me(priv)
    const rows = await Promise.all(
      groups.map(async (g): Promise<GroupSpace | null> => {
        let epochs: EpochKeys | null = null
        try {
          // The name is sealed under K_name (from the name-epoch key) — peel the chain so a rotated group still
          // derives that epoch's key and decrypts its name.
          epochs = await epochKeysFor(g, await openOwnGrant(g, priv, self))
          const name = g.encryptedName ? openGroupName(g.id, epochs, g.nameEpoch, g.encryptedName) : ''
          const icon = g.sealedIcon ? openGroupIconAt(g.id, epochs, g.iconEpoch, g.sealedIcon) : null
          return { scopeId: g.id, tenantId: g.tenantId, name, icon, role: g.role, deletedAt: g.deletedAt ?? null, purgeAfter: g.purgeAfter ?? null }
        } catch {
          return null
        } finally {
          if (epochs) zeroEpochKeys(epochs)
        }
      }),
    )
    return rows.filter((r): r is GroupSpace => r !== null)
  } finally {
    zero(priv)
  }
}

/** An opened scope: its opaque identity + the in-memory **epoch keys** (owned transiently by the caller; `key` is
 *  the write key = the current epoch) + the decrypted display `name` for a named scope (a GROUP; undefined else). */
export type OpenedScope = { scopeId: string; tenantId: string; kind: ScopeKind; key: Uint8Array; epochs: EpochKeys; nameEpoch: number; name?: string; deletedAt: Date | null; purgeAfter: Date | null }

/** The caller as a grant's recipient — the account id and the identity bundle the vault holds. */
const recipientOf = (self: Me) => ({ userId: self.userId, identityPublicKeyB64: self.pubB64 })

/**
 * Open our **own** grant on a scope row with the vaulted identity → the in-memory epoch key. Caller owns it. The
 * grant is bound to (this row's scope, its granted epoch) (ADR 0072): a grant the server moved here from another
 * scope's row — or from another epoch — is refused, never opened as this scope's key. And it is opened only once
 * its granter's signature verifies (ADR 0028 Decision 6, {@link verifyOwnGrant}): the key inside a refused grant is
 * never used — {@link GrantRefused} is thrown before the unwrap, and the scope reads as awaiting access.
 */
async function openOwnGrant(
  scope: ScopeSummary, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }, self: Me,
): Promise<Uint8Array> {
  await verifyOwnGrant({ scopeId: scope.id, kind: scope.kind, epoch: scope.grantedEpoch }, scope.wrappedGrant, scope, self)
  return v1.openAs(grantContext(scope.id, scope.grantedEpoch), v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(scope.wrappedGrant))
}

/** `open` with a refused grant read as no grant: the scope is awaiting access, not an error (ADR 0028 Decision 6). */
async function orAwaitingAccess<T>(open: () => Promise<T | null>): Promise<T | null> {
  try {
    return await open()
  } catch (e) {
    if (e instanceof GrantRefused) return null
    throw e
  }
}

/** Open a scope summary → its epoch keys + (for a GROUP) its decrypted name. The name is sealed under `K_name`
 *  derived from its **name-epoch** key (cr-0096/ADR 0027), so it decrypts under that epoch of the peeled chain. */
async function toOpenedScope(
  scope: ScopeSummary, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }, self: Me,
): Promise<OpenedScope> {
  const grantKey = await openOwnGrant(scope, priv, self)
  const epochs = await epochKeysFor(scope, grantKey)
  let name: string | undefined
  if (scope.kind === ScopeKind.Group && scope.encryptedName) {
    try {
      name = openGroupName(scope.id, epochs, scope.nameEpoch, scope.encryptedName)
    } catch {
      name = undefined // a name that won't open (E2) is non-fatal — the scope still opens, just unlabelled
    }
  }
  return {
    scopeId: scope.id, tenantId: scope.tenantId, kind: scope.kind, key: writeKey(epochs), epochs, nameEpoch: scope.nameEpoch, name,
    deletedAt: scope.deletedAt ?? null, purgeAfter: scope.purgeAfter ?? null,
  }
}

/**
 * Open the caller's scope of the given `kind` → its identity + in-memory epoch keys (open our **own** grant
 * with the vaulted identity, then peel the epoch chain), or null if there is no such scope / the vault is locked.
 * This is **open-only** — it never mints a scope. The caller owns the returned keys transiently.
 */
export async function openScope(kind: ScopeKind): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.kind === kind)
    return scope ? await orAwaitingAccess(async () => toOpenedScope(scope, priv, await me(priv))) : null
  } finally {
    zero(priv)
  }
}

/**
 * Open a specific scope by its opaque id → its identity + in-memory epoch keys, or null if the caller holds no
 * such scope / the vault is locked. Used to enter a group from the `#@<id>` fragment (en.spaces.group-scope).
 */
export async function openScopeById(scopeId: string): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.id === scopeId)
    return scope ? await orAwaitingAccess(async () => toOpenedScope(scope, priv, await me(priv))) : null
  } finally {
    zero(priv)
  }
}

/** A group's readable URL slug — `slugify` of its decrypted name (cr-0120), or null when it has no openable name.
 *  Decrypts under the peeled name-epoch chain and zeroes those keys per call (the name stays operator-blind). */
async function groupSlug(
  g: ScopeSummary,
  priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array },
  self: Me,
): Promise<string | null> {
  if (!g.encryptedName) return null
  let epochs: EpochKeys | null = null
  try {
    epochs = await epochKeysFor(g, await openOwnGrant(g, priv, self))
    return slugify(openGroupName(g.id, epochs, g.nameEpoch, g.encryptedName))
  } catch {
    return null
  } finally {
    if (epochs) zeroEpochKeys(epochs)
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
    return found ? await orAwaitingAccess(() => toOpenedScope(found, priv, self)) : null
  } finally {
    zero(priv)
  }
}

/** Open the member's personal scope → just its key (thin wrapper over {@link openScope}; used by e2e harnesses). */
export async function openPersonalScope(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Personal))?.key ?? null
}

/**
 * Derive a group's current name-key `K_name` (from its name-epoch key) and wrap it to a pending invitee's identity
 * (uc.spaces.consent-group-add, cr-0096/ADR 0027) → the `sealedNameKey` to post with the consent proposal. The
 * invitee unwraps it to open the **same canonical `encryptedName`** the members read — so a modified owner cannot
 * privately show a divergent name. Returns null if the caller can't open the scope (vault locked / no grant).
 * Requires an unlocked vault; the transient epoch keys + `K_name` are zeroed before returning.
 */
export async function wrapGroupNameKeyForInvitee(
  scopeId: string,
  inviteeUserId: string,
  inviteeIdentityPublicKeyB64: string,
): Promise<string | null> {
  // TOFU gate (cr-0098, ADR 0028): this is the invite's first wrap to the invitee's *foreign* identity — pin it on
  // first sight, proceed on a match, but on a CHANGED key surface it (this is a human/UI path) rather than sealing
  // K_name to a possibly-substituted key. The caller catches IdentityChangedError → a notice + accept → re-pin.
  if ((await checkAndPin(inviteeUserId, inviteeIdentityPublicKeyB64)) === 'changed') {
    throw new IdentityChangedError(inviteeUserId)
  }
  const opened = await openScopeById(scopeId)
  if (!opened) return null
  const nameKey = deriveGroupNameKey(keyAt(opened.epochs, opened.nameEpoch))
  try {
    return wrapNameKeyToInvitee(nameKey, scopeId, opened.nameEpoch, inviteeIdentityPublicKeyB64)
  } finally {
    nameKey.fill(0)
    zeroEpochKeys(opened.epochs)
  }
}

/**
 * Rename a group (uc.spaces.rename-group, cr-0097/ADR 0027) — operator-blind: re-seal the new name under `K_name`
 * derived from the caller's **write key** (the highest epoch they hold) and POST `{ encryptedName, nameEpoch }`.
 * The server accepts it only when `nameEpoch == currentEpoch`, so a caller who holds the current epoch reseals the
 * name forward-secretly; a stale caller (a rotation moved the epoch under them) gets `409 stale_epoch`. We refetch
 * + retry **once** — grant-delivery may have re-granted us to the current epoch between the two attempts. Requires
 * an unlocked vault + OWNER on the group (enforced server-side). Throws on a persistent failure.
 */
export async function renameGroup(scopeId: string, newName: string): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open group to rename')
    const nameEpoch = opened.epochs.current // seal under the highest epoch we hold; the server gates it == current
    const nameKey = deriveGroupNameKey(writeKey(opened.epochs))
    try {
      const encryptedName = sealGroupName(nameKey, scopeId, nameEpoch, newName)
      await withRefresh(() => scopesApi().renameGroup({ scopeId, renameGroupRequest: { encryptedName, nameEpoch } }))
      return
    } catch (e) {
      if (attempt === 0 && (await isStaleEpoch(e))) continue // a concurrent rotation — refetch the epoch + retry once
      throw e
    } finally {
      nameKey.fill(0)
      zeroEpochKeys(opened.epochs)
    }
  }
}

/** Whether a failed call is a `409 stale_epoch`: the epoch the call was built on is not the scope's — a rotation
 *  advanced it under a rename or icon set, or a rotation was itself built on a superseded epoch. */
export function isStaleEpoch(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_epoch')
}

/**
 * Set or replace a space's icon (uc.spaces.set-space-icon) — operator-blind: bake+encode is the caller's, here we
 * seal the encoded bytes under `K_icon` derived from the caller's **write key** (the highest epoch they hold) and
 * POST `{ sealedIcon, iconEpoch }`. The server accepts it only when `iconEpoch == currentEpoch`; a stale caller
 * (a concurrent rotation) gets `409 stale_epoch`, so we refetch + retry **once** — the same shape as
 * {@link renameGroup}. The padding to a constant length is intrinsic to {@link sealIcon}, so every stored icon is
 * byte-identical in size. Requires an unlocked vault + OWNER on the scope (enforced server-side).
 */
export async function setSpaceIcon(scopeId: string, encoded: Uint8Array): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt++) {
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open space to set its icon')
    const iconEpoch = opened.epochs.current // seal under the highest epoch we hold; the server gates it == current
    const iconKey = deriveSpaceIconKey(writeKey(opened.epochs))
    try {
      const sealedIcon = sealIcon(iconKey, { scopeId, iconEpoch }, encoded)
      await withRefresh(() => scopesApi().setScopeIcon({ scopeId, setScopeIconRequest: { sealedIcon, iconEpoch } }))
      return
    } catch (e) {
      if (attempt === 0 && (await isStaleEpoch(e))) continue // a concurrent rotation — refetch the epoch + retry once
      throw e
    } finally {
      iconKey.fill(0)
      zeroEpochKeys(opened.epochs)
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
 * **own** grant on that tenant transiently, derive `K_icon`, and open + unpad the `sealedIcon` → the encoded image
 * bytes, or **null** (no icon set, grant-less while awaiting-access, or it won't open → the caller renders the
 * initial). Zeroes the scope key + `K_icon` + the identity copy in `finally` (the {@link listMyGroups} posture).
 *
 * A tenant does not routinely re-key, so `iconEpoch == the grant's epoch == 0` (ADR 0025) and `K_icon` derives
 * directly from the grant key — the cross-tenant switcher read carries **no** epoch chain to peel, by design. If a
 * tenant ever re-keys and `iconEpoch < currentEpoch`, {@link openIcon} returns null → the initial (never a throw).
 */
export async function openMyTenantIcon(tenant: MyTenant): Promise<Uint8Array | null> {
  if (!tenant.sealedIcon || !tenant.wrappedGrant || tenant.grantedEpoch == null) return null
  const priv = identityPrivate()
  if (!priv) return null
  let scopeKey: Uint8Array | null = null
  let iconKey: Uint8Array | null = null
  try {
    // The tenant scope's id IS the tenant id; the grant is bound to it at the epoch the row states (ADR 0072), and
    // it opens only once its granter's signature verifies (ADR 0028 Decision 6) — a refusal renders the initial.
    await verifyOwnGrant(
      { scopeId: tenant.tenantId, kind: ScopeKind.Tenant, epoch: tenant.grantedEpoch }, tenant.wrappedGrant, tenant, await me(priv),
    )
    scopeKey = v1.openAs(
      grantContext(tenant.tenantId, tenant.grantedEpoch), v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(tenant.wrappedGrant),
    )
    iconKey = deriveSpaceIconKey(scopeKey)
    return openIcon(iconKey, { scopeId: tenant.tenantId, iconEpoch: tenant.iconEpoch }, tenant.sealedIcon)
  } catch {
    return null
  } finally {
    scopeKey?.fill(0)
    iconKey?.fill(0)
    zero(priv)
  }
}

/**
 * Open a single group's icon for its settings-panel header (uc.spaces.set-space-icon) — unwrap the caller's own
 * grant on that group transiently, **peel the epoch chain** to `iconEpoch` (a group's icon may sit below the
 * current epoch, unlike a tenant), derive `K_icon`, and open + unpad the `sealedIcon` → the encoded image bytes,
 * or **null** (no icon / unopenable → the initial). Zeroes the epoch keys + the identity copy in `finally`. The
 * batched sidebar/hub read goes through {@link listMyGroups} (which opens every group's icon in one pass); this is
 * the single-scope read the owner's panel re-runs after a set/replace/remove.
 */
export async function openGroupIcon(scopeId: string): Promise<Uint8Array | null> {
  const priv = identityPrivate()
  if (!priv) return null
  let epochs: EpochKeys | null = null
  try {
    const g = (await listMyScopes()).find((s) => s.id === scopeId && s.kind === ScopeKind.Group)
    if (!g || !g.sealedIcon) return null
    epochs = await epochKeysFor(g, await openOwnGrant(g, priv, await me(priv)))
    return openGroupIconAt(g.id, epochs, g.iconEpoch, g.sealedIcon)
  } catch {
    return null
  } finally {
    if (epochs) zeroEpochKeys(epochs)
    zero(priv)
  }
}

/**
 * Rotate a group's content key forward after a member was removed (ADR 0024, cr-0089) — the crypto half of the
 * remove: {@link commitRotation} over every **remaining member**. New content thereafter seals under `E_{n+1}`,
 * which the removed member never holds — so they read pre-removal content but never post-removal content, even
 * against a malicious operator. Operator-blind: only the opaque chain-link + per-member grants and tags are posted.
 * Returns the new epoch index.
 *
 * `remaining` is the directory **after** the removal (the removed member excluded); the caller passes it so this
 * carries exactly the surviving set. It is reused across a stale retry: the server drops every grant whose
 * membership is gone or still pending at commit, so a stale list is harmless against an honest server.
 * Requires an unlocked vault (opens the caller's own grant to derive `E_n`).
 */
export async function rotateGroupForRemoval(scopeId: string, remaining: ScopeMember[]): Promise<number> {
  return commitRotation(scopeId, remaining)
}

/** One re-grant a rotation carries: the new epoch key wrapped to a member's identity, signed by the rotator. */
export type RotationGrant = { userId: string } & SignedGrant

/** Who performs a rotation: the account, the identity bundle its own re-grant is sealed to, and the key that signs
 *  every re-grant. The vault's identity by default; an account key rotation passes its NEW identity, which the
 *  vault does not hold yet ({@link commitRotation}'s `rotator`). */
export type Rotator = { userId: string; identityPublicKeyB64: string; signPrivateKey: Uint8Array }

/** One admission the rotation carries: re-tagged under the new epoch, or dropped because its old tag failed. */
type CarriedAdmission = { admissionId: string; admissionTag: string }

/**
 * Commit a scope-key rotation (ADR 0024 §4) — the one path every rotation takes. Open the caller's own grant
 * (`E_n`), mint a fresh `E_{n+1}`, chain-link it (`E_n` sealed under `E_{n+1}`), carry every admission forward,
 * re-grant the members, and commit **stating the epoch it creates**, `n+1`. `E_n` is our grant, not the scope's
 * epoch, so the server takes the commit only as the scope's current epoch + 1: a rotation that lost a race or rode a
 * lagging grant is `409 stale_epoch` with nothing stored, and would otherwise link the new epoch to a key that is not
 * its predecessor. On that answer the whole attempt runs **once** more from a re-opened grant.
 *
 * **Admissions (ADR 0020 move 6).** Every admission among `carry` — and, for a TENANT scope, every open member
 * invite — is verified under `E_n` first. One that verifies is re-tagged under `E_{n+1}` with the same id; one that
 * fails is dropped, and its member is **not** re-granted (it surfaces as not admitted in the directory). Only a
 * member whose admission verified, who is not a pending consent proposal and whose key passes the TOFU gate is
 * re-granted — the rotator itself excepted, which re-grants itself unconditionally (a grant to oneself needs no
 * admission). The server refuses a rotation that leaves an open admission neither re-tagged nor dropped.
 *
 * The key is minted inside the attempt, never hoisted: the refused attempt's wraps already left the device, and one
 * may be addressed to a member removed in the meantime — reusing that key would give the server a grant it could
 * hand them. Returns the new epoch; a second stale answer (our grant still lags) is thrown, see {@link isStaleEpoch}.
 */
export async function commitRotation(scopeId: string, carry: ScopeMember[], rotator?: Rotator): Promise<number> {
  for (let attempt = 0; ; attempt++) {
    const opened = await openScopeById(scopeId) // our grant → the epoch keys we hold (E_n = the write key)
    if (!opened) throw new Error('cannot open scope to rotate')
    const newKey = v1.randomBytes(32)
    const who = rotator ?? (await vaultRotator())
    try {
      // The epoch this rotation creates binds the link, every grant and every tag (ADR 0072) — the one the server checks.
      const epoch = opened.epochs.current + 1
      const oldKey = writeKey(opened.epochs)
      const wrappedPrev = b64std(v1.seal(epochLinkContext(scopeId, epoch), newKey, oldKey))
      const scope = { scopeId, kind: opened.kind }
      const next: GrantScope = { ...scope, epoch }
      const admissions: CarriedAdmission[] = []
      const droppedAdmissions: string[] = []
      /** Verify an admission under E_n and carry it — re-tagged, or dropped. Whether it verified. */
      const carryAdmission = (targetUserId: string, a: ScopeMember | PendingMemberInvite): boolean => {
        if (a.admissionId == null) return false
        const verified = admissionVerifies(oldKey, opened.epochs.current, scope, targetUserId, a)
        if (verified) admissions.push({ admissionId: a.admissionId, admissionTag: tagAdmission(newKey, scope, epoch, targetUserId, a.admissionId).admissionTag })
        else droppedAdmissions.push(a.admissionId)
        return verified
      }
      const grants: RotationGrant[] = []
      // Sequential so the pin store's version-guarded writes don't race each other.
      for (const m of carry) {
        const admitted = carryAdmission(m.userId, m)
        if (m.userId === who.userId) {
          // Our own re-grant targets the identity we hold, never the directory's copy of it.
          grants.push({ userId: m.userId, ...sealGrant(newKey, next, { userId: m.userId, identityPublicKeyB64: who.identityPublicKeyB64 }, who.signPrivateKey) })
          continue
        }
        // Never re-grant a member whose admission did not verify (ADR 0020 move 6), nor a soft consent proposal
        // (cr-0095): it is grant-gated until the invitee accepts. And never to a CHANGED identity key (cr-0098,
        // ADR 0028): that member stays awaiting access, a visible anomaly, until a human accepts the change. The
        // rotation itself still proceeds — forward secrecy against the removed member cannot hinge on them.
        if (!admitted || m.pendingConsent) continue
        if ((await checkAndPin(m.userId, m.identityPublicKey)) === 'changed') continue
        grants.push({ userId: m.userId, ...sealGrant(newKey, next, { userId: m.userId, identityPublicKeyB64: m.identityPublicKey }, who.signPrivateKey) })
      }
      // A tenant's open member invites are admissions too: their tag becomes the membership's at accept.
      if (opened.kind === ScopeKind.Tenant) {
        for (const invite of await withRefresh(() => tenantsApi().listMemberInvites({ tenantId: scopeId }))) carryAdmission('', invite)
      }
      const rotated = await withRefresh(() =>
        scopesApi().rotateScope({ scopeId, rotateScopeRequest: { epoch, wrappedPrev, grants, admissions, droppedAdmissions } }),
      )
      return rotated.epoch
    } catch (e) {
      if (attempt === 0 && (await isStaleEpoch(e))) continue // superseded — re-open + rebuild once, fresh key
      throw e
    } finally {
      newKey.fill(0)
      zeroEpochKeys(opened.epochs)
      if (!rotator) who.signPrivateKey.fill(0)
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

/**
 * Tag an admission to `scopeId` under the caller's **current** epoch key — the owner's key-holder act that a group
 * add and a tenant invite carry (ADR 0020 move 6). `targetUserId` is the added member's account for a group, `null`
 * for a tenant invite (the invitee has none yet). Throws when the scope cannot be opened — the vault is locked, or a
 * session holds no grant — which is exactly the case an admission must not be possible in.
 */
export async function admissionFor(
  scopeId: string, targetUserId: string | null,
): Promise<{ admissionId: string; admissionEpoch: number; admissionTag: string }> {
  const opened = await openScopeById(scopeId)
  if (!opened) throw new Error('cannot open the scope to admit — the vault is locked or no grant is held')
  try {
    return tagAdmission(writeKey(opened.epochs), { scopeId, kind: opened.kind }, opened.epochs.current, targetUserId)
  } finally {
    zeroEpochKeys(opened.epochs)
  }
}

/** Best-effort wipe of the transient identity-private copy the vault handed us. */
function zero(priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): void {
  priv.kemX.fill(0)
  priv.mlkemSeed.fill(0)
  priv.sign.fill(0)
}
