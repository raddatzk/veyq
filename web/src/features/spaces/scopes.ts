// uc.spaces.personal-scope (web) — birth + open a member's per-tenant PERSONAL scope. Operator-blind: the
// scope key is generated on-device and sealed to the member's own identity (asymWrap, the hybrid KEM); only
// the ciphertext grant reaches the server. The active tenant is resolved server-side from the Host.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { scopesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { identityPrivate } from '../../app/vault'
import { sealName, openName } from '../memory/tree'
import { slugify } from '../../shared/naming'
import { deriveEpochKeys, keyAt, writeKey, zeroEpochKeys, EPOCH_LINK, type EpochKeys } from './epochs'
import { SCOPE_GRANT, wrapScopeKeyToMember } from './grant'
import { checkAndPin, IdentityChangedError } from '../account/identityPins'
import { deriveGroupNameKey, wrapNameKeyToInvitee } from './inviteName'
import { deriveSpaceIconKey, sealIcon, openIcon } from './spaceIcon'
import { ScopeKind, type ScopeSummary, type MyTenant, type ScopeMember } from '../../api/generated/scopes'

/** The AAD/KDF context binding a scope-key wrap (ADR 0017/0020), re-exported for back-compat — its home is now
 *  {@link ./grant}, shared with the grant-delivery worker (cr-0076) + rotation re-grants (cr-0089). */
export { SCOPE_GRANT } from './grant'

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
  return deriveEpochKeys(grantKey, scope.grantedEpoch, links)
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
  try {
    const grant = b64std(
      v1.asymWrap(v1.hybridPublic(priv), scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }),
    )
    await withRefresh(() => scopesApi().createPersonalScope({ createPersonalScopeRequest: { wrappedGrant: grant } }))
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
  try {
    const encryptedName = sealName(nameKey, name)
    const grant = b64std(v1.asymWrap(v1.hybridPublic(priv), scopeKey, SCOPE_GRANT, { nonce: v1.randomBytes(12) }))
    const created = await withRefresh(() =>
      scopesApi().createGroup({ createGroupRequest: { encryptedName, wrappedGrant: grant } }),
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
function openGroupName(epochs: EpochKeys, nameEpoch: number, encryptedName: string): string {
  const nameKey = deriveGroupNameKey(keyAt(epochs, nameEpoch))
  try {
    return openName(nameKey, encryptedName)
  } finally {
    nameKey.fill(0)
  }
}

/** A group's icon from its epoch keys (uc.spaces.set-space-icon): derive `K_icon` from the **icon-epoch** key —
 *  peeled from the chain, because a group's `iconEpoch` may sit below `currentEpoch` (it rotates on member-removal,
 *  ADR 0024, but a rotation never re-seals the icon) — then open + unpad the sealed icon. Null when unopenable →
 *  the caller renders the initial. Unlike {@link openMyTenantIcon}, this must peel the chain (a tenant never rotates). */
function openGroupIconAt(epochs: EpochKeys, iconEpoch: number, sealedIcon: string): Uint8Array | null {
  const iconKey = deriveSpaceIconKey(keyAt(epochs, iconEpoch))
  try {
    return openIcon(iconKey, sealedIcon)
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
    const rows = await Promise.all(
      groups.map(async (g): Promise<GroupSpace | null> => {
        let epochs: EpochKeys | null = null
        try {
          // The name is sealed under K_name (from the name-epoch key) — peel the chain so a rotated group still
          // derives that epoch's key and decrypts its name.
          epochs = await epochKeysFor(g, unwrapGrant(g, priv))
          const name = g.encryptedName ? openGroupName(epochs, g.nameEpoch, g.encryptedName) : ''
          const icon = g.sealedIcon ? openGroupIconAt(epochs, g.iconEpoch, g.sealedIcon) : null
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

/** asymUnwrap our **own** grant on a scope row with the vaulted identity → the in-memory epoch key. Caller owns it. */
function unwrapGrant(scope: ScopeSummary, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): Uint8Array {
  return v1.asymUnwrap(v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(scope.wrappedGrant), SCOPE_GRANT)
}

/** Open a scope summary → its epoch keys + (for a GROUP) its decrypted name. The name is sealed under `K_name`
 *  derived from its **name-epoch** key (cr-0096/ADR 0027), so it decrypts under that epoch of the peeled chain. */
async function toOpenedScope(scope: ScopeSummary, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): Promise<OpenedScope> {
  const grantKey = unwrapGrant(scope, priv)
  const epochs = await epochKeysFor(scope, grantKey)
  let name: string | undefined
  if (scope.kind === ScopeKind.Group && scope.encryptedName) {
    try {
      name = openGroupName(epochs, scope.nameEpoch, scope.encryptedName)
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
 * Open the caller's scope of the given `kind` → its identity + in-memory epoch keys (asymUnwrap our **own** grant
 * with the vaulted identity, then peel the epoch chain), or null if there is no such scope / the vault is locked.
 * This is **open-only** — it never mints a scope. The caller owns the returned keys transiently.
 */
export async function openScope(kind: ScopeKind): Promise<OpenedScope | null> {
  const priv = identityPrivate()
  if (!priv) return null
  try {
    const scope = (await listMyScopes()).find((s) => s.kind === kind)
    return scope ? await toOpenedScope(scope, priv) : null
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
    return scope ? await toOpenedScope(scope, priv) : null
  } finally {
    zero(priv)
  }
}

/** A group's readable URL slug — `slugify` of its decrypted name (cr-0120), or null when it has no openable name.
 *  Decrypts under the peeled name-epoch chain and zeroes those keys per call (the name stays operator-blind). */
async function groupSlug(
  g: ScopeSummary,
  priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array },
): Promise<string | null> {
  if (!g.encryptedName) return null
  let epochs: EpochKeys | null = null
  try {
    epochs = await epochKeysFor(g, unwrapGrant(g, priv))
    return slugify(openGroupName(epochs, g.nameEpoch, g.encryptedName))
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
    let scope = groups.find((s) => s.id === token)
    if (!scope) {
      const slugs = await Promise.all(groups.map((g) => groupSlug(g, priv)))
      const matches = groups.filter((_, i) => slugs[i] === token)
      if (matches.length === 1) scope = matches[0]
    }
    return scope ? await toOpenedScope(scope, priv) : null
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
    return wrapNameKeyToInvitee(nameKey, inviteeIdentityPublicKeyB64)
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
      const encryptedName = sealName(nameKey, newName)
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

/** Whether a failed call is a `409 stale_epoch` (a concurrent rotation advanced the epoch under a rename). */
function isStaleEpoch(e: unknown): Promise<boolean> {
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
      const sealedIcon = sealIcon(iconKey, encoded)
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
  if (!tenant.sealedIcon || !tenant.wrappedGrant) return null
  const priv = identityPrivate()
  if (!priv) return null
  let scopeKey: Uint8Array | null = null
  let iconKey: Uint8Array | null = null
  try {
    scopeKey = v1.asymUnwrap(v1.hybridSecret(priv), v1.hybridPublic(priv), fromB64std(tenant.wrappedGrant), SCOPE_GRANT)
    iconKey = deriveSpaceIconKey(scopeKey)
    return openIcon(iconKey, tenant.sealedIcon)
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
    epochs = await epochKeysFor(g, unwrapGrant(g, priv))
    return openGroupIconAt(epochs, g.iconEpoch, g.sealedIcon)
  } catch {
    return null
  } finally {
    if (epochs) zeroEpochKeys(epochs)
    zero(priv)
  }
}

/**
 * Rotate a group's content key forward after a member was removed (ADR 0024, cr-0089) — the crypto half of the
 * remove: mint a fresh epoch key `E_{n+1}`, chain-link it to the current `E_n` (`symWrap(E_{n+1}, E_n)`), and
 * re-grant `E_{n+1}` to **every remaining member** (`asymWrap` to each directory pubkey), then POST the
 * rotation-commit. New content thereafter seals under `E_{n+1}`, which the removed member never holds — so they
 * read pre-removal content but never post-removal content, even against a malicious operator. Operator-blind:
 * only the opaque chain-link + per-member grants are posted. Returns the new epoch index.
 *
 * `remaining` is the directory **after** the removal (the removed member excluded); the caller passes it so this
 * grants exactly the surviving set. Requires an unlocked vault (opens the caller's own grant to derive `E_n`).
 */
export async function rotateGroupForRemoval(scopeId: string, remaining: ScopeMember[]): Promise<number> {
  const opened = await openScopeById(scopeId) // our grant → the current epoch keys (E_n = the write key)
  if (!opened) throw new Error('cannot open scope to rotate')
  const prevKey = writeKey(opened.epochs)
  const newKey = v1.randomBytes(32)
  try {
    const wrappedPrev = b64std(v1.symWrap(newKey, prevKey, EPOCH_LINK, v1.randomBytes(12)))
    // Never re-grant a soft consent proposal (cr-0095): a `pendingConsent` row is grant-gated until the invitee
    // accepts, so it must stay out of the rotation's re-grant set (the server enforces this too; this avoids the
    // useless wrap + keeps the client honest). The directory hands owners the pending rows, so filter here.
    //
    // TOFU gate (cr-0098, ADR 0028): pin each remaining member on first sight, proceed on a match, but never
    // re-grant the fresh epoch to a CHANGED identity key — exclude that member (they stay awaiting-access, a
    // visible anomaly, until a human accepts the change; grant-delivery likewise declines for them). The rotation
    // itself must still proceed — forward secrecy against the *removed* member cannot hinge on a surviving
    // member's changed key. Sequential so the pin store's version-guarded writes don't race each other.
    const grants: { userId: string; wrappedGrant: string }[] = []
    for (const m of remaining) {
      if (m.pendingConsent) continue
      if ((await checkAndPin(m.userId, m.identityPublicKey)) === 'changed') continue
      grants.push({ userId: m.userId, wrappedGrant: wrapScopeKeyToMember(newKey, m.identityPublicKey) })
    }
    const rotated = await withRefresh(() => scopesApi().rotateScope({ scopeId, rotateScopeRequest: { wrappedPrev, grants } }))
    return rotated.epoch
  } finally {
    newKey.fill(0)
    zeroEpochKeys(opened.epochs)
  }
}

/** Best-effort wipe of the transient identity-private copy the vault handed us. */
function zero(priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): void {
  priv.kemX.fill(0)
  priv.mlkemSeed.fill(0)
  priv.sign.fill(0)
}
