// Per-tenant member identity (web, cr-0083 / ADR 0023): the caller's `username` (plaintext handle) + their real
// name (first + last) **sealed under K_tenant**. The sealed-name crypto itself lives in `./memberName` (a
// dependency-light, crypto-only module) so the dev bootstrap script can seal identically; it is re-exported here
// so callers keep a single import site. The username is a plaintext per-tenant handle the server enforces unique on.
import { accountProfileApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { openScope } from '../../spaces/scopes'
import { ScopeKind } from '../../../api/generated/scopes'
import type { MemberProfile } from '../../../api/generated/account'

export type { MemberProfile } from '../../../api/generated/account'
export { sealName, unsealName, type MemberName } from './memberName'

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

/**
 * Open the **active tenant's** scope key `K_tenant` on-device (the caller's tenant grant), independent of which
 * space is currently active — the profile panel + directory need it to seal/unseal names even from Personal.
 * The caller owns the returned key transiently and must not mutate it; null if not a granted tenant member.
 */
export async function openTenantKey(): Promise<Uint8Array | null> {
  return (await openScope(ScopeKind.Tenant))?.key ?? null
}
