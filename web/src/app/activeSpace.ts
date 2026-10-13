// The active space the shell is showing (cr-0030/0075, uc.spaces.switch-spaces). Holds the opaque `scopeId` +
// the in-memory scope content key `K_SC` (the key bytes live OUTSIDE the reactive proxy — never expose key
// material to Vue reactivity). Which scope is active is driven by the URL's leading fragment token (ADR 0019):
// token-less → the shared **tenant** scope, `~me` → **personal**. Entering a scope opens its grant client-side
// (operator-blind: `K_SC` is derived on-device) and resets the per-scope tree + pins.
import { reactive } from 'vue'
import { ensurePersonalScope, openScope, openScopeById, openGroupByToken, type OpenedScope } from '../features/spaces/scopes'
import { getTenantTerms } from '../features/tenant/tenantTerms'
import { zeroEpochKeys, type EpochKeys } from '../features/spaces/epochs'
import { ScopeKind } from '../api/generated/scopes'
import { TENANT_SCOPE } from '../core/node/location'
import { isHttpStatus, HttpStatus } from '../shared/httpError'
import { resetTree, loadTree } from '../core/node/treeStore'
import { registry } from '../core/applications/registry'
import { clearFavorites, favoritesStore, loadFavoritesFor } from '../core/favorites/favoritesStore'
import { resetCovers } from '../core/node/cover/coverStore'
import { resetPeopleDirectory } from '../core/people/directory'
import { startGrantDeliveryPolling, stopGrantDeliveryPolling } from '../features/tasks/grantDeliveryPolling'
import { startScopeRotationPolling, stopScopeRotationPolling } from '../features/tasks/scopeRotationPolling'
import { refreshMyInvitations, clearMyInvitations } from '../features/spaces/invitations'
import { isUnlocked } from './vault'
import { handleTenantSuspended } from './tenantSuspension'
import { TrustStateRefused, refusalMessageKey } from '../features/account/trustMarks'

export type SpaceStatus = 'idle' | 'loading' | 'ready' | 'no-tenant' | 'error'

/** Reactive, non-sensitive view of the active space (the key bytes are held separately). */
export const activeSpace = reactive({
  status: 'idle' as SpaceStatus,
  scopeId: null as string | null,
  tenantId: null as string | null,
  kind: null as ScopeKind | null,
  // The active scope's decrypted display name — a GROUP's name (cr-0032); null for the anonymous tenant/personal.
  name: null as string | null,
  // The write epoch (ADR 0024): a **reactive** mirror of `activeEpochKeys().current`. The key bytes stay outside
  // reactivity, but views need a reactive signal to re-read the keys after a rotation (`refreshActiveScope`) that
  // doesn't flip `status` — a `computed` over `activeEpochKeys()` must depend on THIS so it recomputes on rotate.
  epoch: 0,
  // Pending-deletion state (cr-0090): when a GROUP is soft-deleted it stays enterable but read-only + marked; the
  // shell shows a banner + hides write affordances until `purgeAfter`. Null for an active scope.
  deletedAt: null as Date | null,
  purgeAfter: null as Date | null,
  // The token-less tenant home resolved to Personal because the caller holds no TENANT grant (ADR 0019, E1).
  tenantUnavailable: false,
  // With status 'error': the i18n key naming the trust-state refusal that stopped the scope opening (ADR 0028
  // Decision 7 — a shared grant's granter is checked against the pin store this device refused), else null.
  trustRefusal: null as string | null,
  // The active tenant's terms of service (cr-0114, ADR 0035): the operator's current `termsVersion` and the
  // caller's `acceptedVersion` — both 0 when no terms are set / none accepted. When accepted < version the
  // member accept-gate fires (`termsStale()`, enforced by the router). Refreshed on every scope entry.
  termsVersion: 0,
  acceptedVersion: 0,
})

/** Whether the active tenant has terms the caller hasn't accepted yet — the member accept-gate condition
 *  (uc.account.accept-terms). False when no tenant is active or no terms are set (version 0). Fail-open: a
 *  transient terms-fetch error leaves the versions at 0, so a member is never locked out by an API hiccup. */
export function termsStale(): boolean {
  return activeSpace.tenantId !== null && activeSpace.termsVersion > 0 &&
    activeSpace.acceptedVersion < activeSpace.termsVersion
}

/** Record locally that the caller accepted [version] (called by the /terms view after a successful accept), so
 *  `termsStale()` clears and the guard lets them back into the shell without a re-fetch. */
export function markTermsAccepted(version: number): void {
  if (version > activeSpace.acceptedVersion) activeSpace.acceptedVersion = version
}

// The scope's epoch keys are held outside the reactive proxy on purpose — key material must not flow through Vue
// reactivity. `scopeKey` is the **write key** (E_current); `epochKeys` carries the full peeled chain for reads.
let scopeKey: Uint8Array | null = null
let epochKeys: EpochKeys | null = null

/** The active scope's **write key** `E_current` (seals new content), or null when no space is open. Don't mutate. */
export function activeScopeKey(): Uint8Array | null {
  return scopeKey
}

/** The active scope's write key together with the epoch it is — what a grant sealed with it is bound to (ADR 0072). */
export function activeWriteKey(): { key: Uint8Array; epoch: number } | null {
  return scopeKey && epochKeys ? { key: scopeKey, epoch: epochKeys.current } : null
}

/** The active scope's full **epoch key set** (ADR 0024) — a per-epoch lookup so a read opens a node under its own
 *  epoch key. Null when no space is open. The caller must not mutate or zero it (activeSpace owns its lifetime). */
export function activeEpochKeys(): EpochKeys | null {
  return epochKeys
}

/**
 * Enter the scope selected by the URL's leading fragment `token` (`''` = tenant, `~me` = personal, `@<id>` =
 * group). Opens that scope's grant → holds `scopeId` + `K_SC`, then **resets the tree and every application's per-scope state** so no stale
 * per-scope state bleeds across a switch. Sets `status` to `ready` on success, `no-tenant` when the active tenant
 * can't be resolved from the Host (404), or `error` otherwise. Requires an unlocked vault (the identity opens the
 * grant).
 *
 * **No client birth of the tenant scope** (ADR 0006): the token-less home is *open-only*. When the caller holds
 * no `TENANT` grant (tenant member-invite is unbuilt), it **falls back to Personal** and flags the tenant
 * unavailable (ADR 0019, en.spaces.tenant-scope E1) — never a `POST`. A `@<slug|id>` group is resolved against the
 * caller's own scope list (readable slug, or opaque id for a legacy/collision link — en.spaces.group-scope,
 * cr-0032/0120); an unresolvable one falls back to Personal too. Only Personal auto-births.
 */
export async function enterScope(token: string): Promise<void> {
  activeSpace.status = 'loading'
  activeSpace.trustRefusal = null
  try {
    let opened: OpenedScope | null = null
    if (token === TENANT_SCOPE) {
      opened = await openScope(ScopeKind.Tenant) // open-only — never mint the shared root
      activeSpace.tenantUnavailable = opened === null
    } else {
      activeSpace.tenantUnavailable = false
      if (token.startsWith('@')) opened = await openGroupByToken(token.slice(1)) // `@<slug|id>` → open that group
    }
    if (!opened) {
      // Personal — the explicit `~me`, an unresolved/unopenable `@group`, or the no-grant tenant fallback.
      // Personal is the one birth path (idempotent: a no-op if one already exists).
      await ensurePersonalScope()
      opened = await openScope(ScopeKind.Personal)
    }
    if (!opened) {
      activeSpace.status = 'error'
      return
    }
    if (epochKeys) zeroEpochKeys(epochKeys) // zero every epoch key we held for the scope we're leaving (incl. the write key)
    epochKeys = opened.epochs
    scopeKey = opened.key // the write key = E_current, a reference into `epochKeys` (zeroed together above)
    activeSpace.epoch = opened.epochs.current // reactive signal so key-reading computeds re-run for the new scope
    activeSpace.deletedAt = opened.deletedAt
    activeSpace.purgeAfter = opened.purgeAfter
    activeSpace.scopeId = opened.scopeId
    activeSpace.tenantId = opened.tenantId
    activeSpace.kind = opened.kind
    activeSpace.name = opened.name ?? null
    resetTree() // no stale tree bleeds across scopes — the view reloads for the new scope
    clearFavorites() // no stale pins bleed across scopes (core's: a pin names a node of any kind, ADR 0068)
    resetCovers() // …nor covers: every decrypted cover URL is revoked (core's, inbox #411)
    registry.scopeReset() // …and each application drops what it held for the old scope (Memories: covers)
    activeSpace.status = 'ready'
    // cr-0114: refresh the tenant's terms status so the router accept-gate knows if a (re-)accept is due. The
    // tenant is the same regardless of which scope (tenant/personal/group) is open, and terms are member-readable.
    await refreshTenantTerms(opened.tenantId)
    // cr-invitations-topbar-badge: refresh the pending group-invitation count for the active tenant so the top-bar
    // attention badge reflects it. Not awaited — the badge updates whenever it resolves; scope entry never blocks
    // on it, and a failure just leaves the count at 0 (fail-quiet in refreshMyInvitations).
    void refreshMyInvitations()
    // As a key-holder, keep delivering the scope key to any pending member (cr-0076/cr-0077, first-online-wins):
    // deliver on entry and then poll in the background so "awaiting access" closes without a re-entry (cr-0079).
    // A shared TENANT/GROUP scope may have grant-less members; Personal never does, so it just stops any poll.
    if (opened.kind !== ScopeKind.Personal) startGrantDeliveryPolling(opened.scopeId, opened.kind, activeWriteKey)
    else stopGrantDeliveryPolling()
    // Also drain any queued scope-key rotations (cr-0092): unlike grant-delivery this is scope-agnostic — an owner
    // completes a queued rotation for ANY scope they own (the server offers it to owners only), without opening it. Session-level
    // (restarted on each entry, stopped on logout/re-lock in clearActiveSpace), gated on an unlocked vault.
    startScopeRotationPolling(isUnlocked)
  } catch (err) {
    // A suspended tenant (uc.spaces.scope-access A7): on this domain's own tenant the router takes over with the
    // offline notice; for another tenant the home view leaves for this domain's home. Either way not a plain error.
    if (await handleTenantSuspended(err)) {
      activeSpace.status = 'idle'
      return
    }
    activeSpace.trustRefusal = err instanceof TrustStateRefused ? refusalMessageKey(err) : null
    activeSpace.status = isNoTenant(err) ? 'no-tenant' : 'error'
  }
}

/**
 * Re-open the **currently active** scope's grant to pick up a fresh epoch (ADR 0024, cr-0089). Call after a
 * rotation of the active scope (e.g. the owner removed a member): the caller's grant now carries `E_{n+1}`, so
 * this swaps in the new epoch keys — new content then seals under `E_{n+1}`, not the stale `E_n` — and reloads the
 * tree + favorites. No-op when no scope is open or the re-open fails (the stale keys are kept rather than dropped).
 */
export async function refreshActiveScope(): Promise<void> {
  if (!activeSpace.scopeId) return
  const opened = await openScopeById(activeSpace.scopeId)
  if (!opened) return
  if (epochKeys) zeroEpochKeys(epochKeys)
  epochKeys = opened.epochs
  scopeKey = opened.key
  activeSpace.epoch = opened.epochs.current // bump the reactive signal → key-reading computeds re-run under E_{n+1}
  activeSpace.deletedAt = opened.deletedAt
  activeSpace.purgeAfter = opened.purgeAfter
  activeSpace.name = opened.name ?? null
  resetTree()
  clearFavorites()
  resetCovers() // the covers were decrypted under the old epoch keys (core's, inbox #411)
  registry.scopeReset() // a rotation swaps the epoch keys: every held cover was decrypted under the old ones
  await loadTree()
  // The pin set (core's) and an application's per-scope state survive a rotation untouched (the pins seal under the
  // member's master key, not an epoch key — ADR 0068) but are re-read here, because the reset above dropped them.
  if (!favoritesStore.loaded) await loadFavoritesFor(activeSpace.scopeId) // once per reset
  await registry.scopeEntered(activeSpace.scopeId)
}

/** Drop the active space + zero the held key (logout / re-lock). */
export function clearActiveSpace(): void {
  stopGrantDeliveryPolling()
  stopScopeRotationPolling()
  clearMyInvitations()
  activeSpace.status = 'idle'
  activeSpace.scopeId = null
  activeSpace.tenantId = null
  activeSpace.kind = null
  activeSpace.name = null
  activeSpace.epoch = 0
  activeSpace.deletedAt = null
  activeSpace.purgeAfter = null
  activeSpace.tenantUnavailable = false
  activeSpace.trustRefusal = null
  activeSpace.termsVersion = 0
  activeSpace.acceptedVersion = 0
  if (epochKeys) zeroEpochKeys(epochKeys)
  epochKeys = null
  scopeKey = null
  clearFavorites() // sign-out / re-lock: the decrypted pin set must not outlive the keys
  resetCovers() // …nor the decrypted covers (core's, inbox #411)
  resetPeopleDirectory() // …nor the decrypted names and the profile pictures' object URLs (core's, ADR 0084)
  registry.scopeReset() // …nor what an application decrypted (Memories' covers)
}

/** A 404 from the scope endpoints means no tenant claims this Host (`tenant_not_found`). */
function isNoTenant(err: unknown): boolean {
  return isHttpStatus(err, HttpStatus.notFound)
}

/** Fetch the active tenant's terms status into `activeSpace` (cr-0114). Fail-open: on any error the versions
 *  stay/reset to 0 so `termsStale()` is false — a transient terms hiccup must never lock a member out of the
 *  shell (terms acceptance is an operational gate, not a confidentiality control). */
async function refreshTenantTerms(tenantId: string | null): Promise<void> {
  if (!tenantId) {
    activeSpace.termsVersion = 0
    activeSpace.acceptedVersion = 0
    return
  }
  try {
    const t = await getTenantTerms(tenantId)
    activeSpace.termsVersion = t.termsVersion
    activeSpace.acceptedVersion = t.acceptedVersion
  } catch {
    activeSpace.termsVersion = 0
    activeSpace.acceptedVersion = 0
  }
}
