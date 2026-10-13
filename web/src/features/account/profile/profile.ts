// Per-tenant member identity (web, cr-0083 / ADR 0023): the caller's `username` (plaintext handle) + their real
// name (first + last) **sealed under K_tenant**. The sealed-name crypto itself lives in `./memberName` (a
// dependency-light, crypto-only module) so the dev bootstrap script can seal identically; it is re-exported here
// so callers keep a single import site. The username is a plaintext per-tenant handle the server enforces unique on.
import { accountApi, accountProfileApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { openScope } from '../../spaces/scopes'
import { ScopeKind } from '../../../api/generated/scopes'
import type { MemberProfile } from '../../../api/generated/account'
import type { MemberNameAddress } from './memberName'

export type { MemberProfile } from '../../../api/generated/account'
export { sealName, unsealName, type MemberName } from './memberName'
export { sealAvatar } from './memberAvatar'

/** The caller's profile in the active tenant (Host-resolved): the plaintext handle + the sealed name blob. */
export function getMyProfile(): Promise<MemberProfile> {
  return withRefresh(() => accountProfileApi().getMyProfile())
}

/** Update the caller's username, sealed name, and/or the account-global group-add consent preference (cr-0094) —
 *  each may be omitted to leave it unchanged. */
export function updateMyProfile(update: {
  username?: string
  sealedName?: string
  requireGroupAddConsent?: boolean
}): Promise<MemberProfile> {
  return withRefresh(() => accountProfileApi().updateMyProfile({ profileUpdateRequest: update }))
}

/** Set the caller's profile picture in the Host tenant (ADR 0084): the picture already sealed under `K_tenant`
 *  ({@link sealAvatar}); the server stores it verbatim and bumps `avatarVersion`. */
export function setMyAvatar(sealedAvatar: string): Promise<void> {
  return withRefresh(() => accountProfileApi().setMyAvatar({ setAvatarRequest: { sealedAvatar } }))
}

/** Clear the caller's profile picture (idempotent `204`); everyone falls back to the letter. */
export function clearMyAvatar(): Promise<void> {
  return withRefresh(() => accountProfileApi().clearMyAvatar())
}

/**
 * Open the **active tenant's** scope key `K_tenant` on-device (the caller's tenant grant), independent of which
 * space is currently active — the profile panel + directory need it to seal/unseal names even from Personal.
 * The caller owns the returned key transiently and must not mutate it; null if not a granted tenant member.
 */
export async function openTenantKey(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Tenant))?.key ?? null
}

/** The caller's own account id, from their own key material — the one read that hands it over. */
export async function myUserId(): Promise<string> {
  return (await withRefresh(() => accountApi().getAccountKeyMaterial())).userId
}

/**
 * `K_tenant` plus the address the caller's **own** name is sealed at — (the tenant scope, the caller's user id)
 * (ADR 0072). The user id comes from the caller's own key material, the one read that hands it over; null when
 * the caller holds no tenant grant yet.
 */
export async function openOwnNameKey(): Promise<{ key: Uint8Array; address: MemberNameAddress } | null> {
  const [tenant, material] = await Promise.all([
    openScope(ScopeKind.Tenant),
    withRefresh(() => accountApi().getAccountKeyMaterial()),
  ])
  if (!tenant) return null
  return { key: tenant.key, address: { scopeId: tenant.scopeId, userId: material.userId } }
}
