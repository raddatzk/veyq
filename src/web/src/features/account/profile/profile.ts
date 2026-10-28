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
import { zeroEpochKeys, type EpochKeys } from '../../spaces/epochs'

export type { MemberProfile } from '../../../api/generated/account'
export { sealName, unsealName, unsealNameUnderEpochs, type MemberName } from './memberName'
export { sealAvatar, openAvatarUnderEpochs } from './memberAvatar'

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
 *  ({@link sealAvatar}); the server stores it verbatim and bumps `avatarRevision`. */
export function setMyAvatar(sealedAvatar: string): Promise<void> {
  return withRefresh(() => accountProfileApi().setMyAvatar({ setAvatarRequest: { sealedAvatar } }))
}

/** Clear the caller's profile picture (idempotent `204`); everyone falls back to the letter. */
export function clearMyAvatar(): Promise<void> {
  return withRefresh(() => accountProfileApi().clearMyAvatar())
}

/** The caller's own account id, from their own key material — the one read that hands it over. */
export async function myUserId(): Promise<string> {
  return (await withRefresh(() => accountApi().getAccountKeyMaterial())).userId
}

/** What the caller's own name and picture are sealed and opened with: `key` (the tenant's write key, the current epoch's,
 *  which a save seals under), `epochs` (every tenant epoch key held, which a read tries — the profile row records no
 *  epoch, `memberName.ts` § `unsealNameUnderEpochs`), the tenant's id and the address (tenant scope, own user id). */
export interface OwnNameKey {
  key: Uint8Array
  epochs: EpochKeys
  tenantId: string
  address: MemberNameAddress
}

/**
 * The tenant's keys plus the address the caller's **own** name is sealed at — (the tenant scope, the caller's user id)
 * (ADR 0072). The user id comes from the caller's own key material, the one read that hands it over; null when
 * the caller holds no tenant grant yet. The caller owns the keys and zeroes them with {@link zeroOwnNameKey} once it is
 * done (inbox #574): after one read, or when the editor that seals with them goes away. When the key-material read
 * fails, the tenant scope it opened beside it is zeroed here, since no caller ever sees it.
 */
export async function openOwnNameKey(): Promise<OwnNameKey | null> {
  const [tenant, material] = await Promise.allSettled([
    openScope(ScopeKind.Tenant),
    withRefresh(() => accountApi().getAccountKeyMaterial()),
  ])
  if (material.status === 'rejected') {
    if (tenant.status === 'fulfilled' && tenant.value) zeroEpochKeys(tenant.value.epochs)
    throw material.reason
  }
  if (tenant.status === 'rejected') throw tenant.reason
  if (!tenant.value) return null
  const t = tenant.value
  return { key: t.key, epochs: t.epochs, tenantId: t.tenantId, address: { scopeId: t.scopeId, userId: material.value.userId } }
}

/** Zero what {@link openOwnNameKey} opened: every tenant epoch key, the write key among them. */
export function zeroOwnNameKey(own: OwnNameKey): void {
  zeroEpochKeys(own.epochs)
  own.key.fill(0)
}
