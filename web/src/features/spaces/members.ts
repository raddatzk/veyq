// uc.spaces.add-member (web, cr-0077) — the member-management data layer for a tenant OWNER. Admission
// (inviting by email) is tenant-owner-gated; the member directory (pubkeys + grant status) is any key-holder's.
// Operator-blind: the server holds only invite metadata + already-stored operational metadata (pubkeys/roles/
// grant-present) — never a scope key. The actual key DELIVERY runs as a grant-delivery job (cr-0076), not here.
import { tenantsApi, scopesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { decodeHttpError } from '../../shared/httpError'
import { admissionFor } from './scopes'
import { SetTenantMemberRoleRequestRoleEnum, type MemberInviteState, type PendingMemberInvite } from '../../api/generated/tenants'
import { SetMemberRoleRequestRoleEnum, type ScopeMember, type ResolvedGroupCandidate, type GroupInvitation } from '../../api/generated/scopes'

export type { ScopeMember, ResolvedGroupCandidate, GroupInvitation } from '../../api/generated/scopes'
export { SetMemberRoleRequestRoleEnum } from '../../api/generated/scopes'
export type { MemberInviteState, PendingMemberInvite } from '../../api/generated/tenants'
export { SetTenantMemberRoleRequestRoleEnum } from '../../api/generated/tenants'
// The invite delivery result (cr-email-invite-send-hardening): SENT / FALLBACK_COPYLINK / FAILED — the three-state
// signal the invite dialogs branch on so the owner always knows whether the mail actually went out.
export { DeliveryResult } from '../../api/generated/tenants'

/**
 * Invite a person (by email) into the active tenant as a MEMBER — owner-gated (403 `not_tenant_owner`). The invite
 * carries an admission tag made under the tenant's current epoch key (ADR 0020 move 6), so this needs the unlocked
 * vault and a tenant grant: an owner's session alone can reach the endpoint but cannot make an invite anyone
 * delivers to.
 */
export async function inviteMember(tenantId: string, email: string): Promise<MemberInviteState> {
  const admission = await admissionFor(tenantId, null)
  return withRefresh(() => tenantsApi().inviteTenantMember({ tenantId, memberInviteRequest: { email, ...admission } }))
}

/** List the tenant's pending member invites (invited-but-not-joined) — owner-gated (uc.spaces.manage-member-invites). */
export function listMemberInvites(tenantId: string): Promise<PendingMemberInvite[]> {
  return withRefresh(() => tenantsApi().listMemberInvites({ tenantId }))
}

/** Re-issue a pending member invite: supersede the old secret + mint a fresh copyable link — owner-gated. */
export function reissueMemberInvite(tenantId: string, inviteId: string): Promise<MemberInviteState> {
  return withRefresh(() => tenantsApi().reissueMemberInvite({ tenantId, inviteId }))
}

/** Revoke a pending member invite (soft-terminal; its secret no longer accepts) — owner-gated. */
export function revokeMemberInvite(tenantId: string, inviteId: string): Promise<void> {
  return withRefresh(() => tenantsApi().revokeMemberInvite({ tenantId, inviteId }))
}

/** List a scope's members with their identity pubkeys + grant status (the directory) — key-holder only. */
export function listScopeMembers(scopeId: string): Promise<ScopeMember[]> {
  return withRefresh(() => scopesApi().listScopeMembers({ scopeId }))
}

/**
 * Remove a member from the **tenant** (uc.spaces.remove-tenant-member, cr-0091) — owner-gated (`403
 * not_tenant_owner`); the last-owner guard rejects removing the last tenant OWNER (`409 last_owner`), `404
 * member_not_found`. The server runs the whole ACL-revoke cascade (cut their tenant + co-shared group access,
 * transfer/tombstone groups they solely owned, grace-tombstone their own scopes with read kept). Operator-blind:
 * no key crosses the wire and the tenant key is NOT rotated (ADR 0025). Distinct from {@link removeGroupMember}
 * (a single group membership + a caller-driven rotation).
 *
 * `revokeLinks` (ADR 0075 point 5) also turns off every link of the TENANT scope in the removal's transaction —
 * each then comes back only by re-issue. Unticked, no parameter is sent at all.
 */
export function removeTenantMember(tenantId: string, userId: string, revokeLinks = false): Promise<void> {
  return withRefresh(() => tenantsApi().removeTenantMember({ tenantId, userId, ...(revokeLinks ? { revokeLinks } : {}) }))
}

/**
 * Make a tenant member an owner, or an owner a member again (ADR 0101 decision 9: a tenant may have several owners, so
 * its last one can hand it on) — owner-gated (`403 not_tenant_owner`); `404 member_not_found`; the last-owner guard
 * refuses demoting the last OWNER (`409 last_owner`). A pure role change: no key moves, the member already holds the
 * tenant's key.
 */
export function setTenantMemberRole(tenantId: string, userId: string, role: SetTenantMemberRoleRequestRoleEnum): Promise<void> {
  return withRefresh(() => tenantsApi().setTenantMemberRole({ tenantId, userId, setTenantMemberRoleRequest: { role } }))
}

/**
 * Add an existing tenant member to a **group** by username (uc.spaces.add-group-member, cr-0086) — owner-gated
 * (`403 not_scope_owner`), tenant-scoped resolve (`404 member_not_found`), idempotency (`409 already_member`).
 * Records a grant-less membership; the key is delivered later by grant-delivery (cr-0076), not here. Returns the
 * new member's directory row (`hasGrant=false`, awaiting access).
 *
 * Consent (cr-0096): when the target has "ask before adding me to groups" on, pass `sealedNameKey` (the group's
 * derived name-key `K_name` wrapped to their identity, see {@link ./scopes#wrapGroupNameKeyForInvitee}) — the add
 * becomes a soft `pendingConsent` proposal grant-delivery skips until they accept. Omitting it for a consent-on
 * target is rejected `400 consent_required` (resolve first to learn whether it is required).
 */
export async function addGroupMember(
  scopeId: string, username: string, targetUserId: string, sealedNameKey?: string,
): Promise<ScopeMember> {
  // Admission is a key-holder act (ADR 0020 move 6): the tag binds the resolved target's account, under the
  // group's current epoch key — the same for a consent proposal, which is delivered to only once accepted.
  const admission = await admissionFor(scopeId, targetUserId)
  return withRefresh(() => scopesApi().addGroupMember({ scopeId, addGroupMemberRequest: { username, sealedNameKey, ...admission } }))
}

/**
 * Resolve a per-tenant username to a group-add candidate (uc.spaces.consent-group-add, cr-0094) — records
 * nothing. Owner-gated; returns the candidate's `identityPublicKey` (to seal the group name to) + whether their
 * consent is required (`requiresConsent`). The owner calls this before {@link addGroupMember} to decide whether
 * to seal a name-proposal (consent on) or direct-add (consent off).
 */
export function resolveGroupMember(scopeId: string, username: string): Promise<ResolvedGroupCandidate> {
  return withRefresh(() => scopesApi().resolveGroupMember({ scopeId, resolveGroupMemberRequest: { username } }))
}

/** The caller's pending group-consent proposals in the active tenant (cr-0094) — the invitee's Accept/Decline inbox. */
export function listMyGroupInvitations(): Promise<GroupInvitation[]> {
  return withRefresh(() => scopesApi().listMyGroupInvitations())
}

/** Accept a group-consent proposal (cr-0094) — become a grant-less member; grant-delivery then wraps `K_SC`. */
export function acceptGroupInvitation(scopeId: string): Promise<void> {
  return withRefresh(() => scopesApi().acceptGroupInvitation({ scopeId }))
}

/** Decline a group-consent proposal (cr-0094) — a silent drop; nothing was wrapped, no rotation. Idempotent. */
export function declineGroupInvitation(scopeId: string): Promise<void> {
  return withRefresh(() => scopesApi().declineGroupInvitation({ scopeId }))
}

/**
 * Promote a group member to co-owner or demote a co-owner (uc.spaces.manage-group-members, cr-0088) — owner-gated
 * (`403 not_scope_owner`); the last-owner guard rejects demoting the last OWNER (`409 last_owner`). A pure role
 * change, no key moves. Returns the member's updated directory row.
 */
export function setMemberRole(scopeId: string, userId: string, role: SetMemberRoleRequestRoleEnum): Promise<ScopeMember> {
  return withRefresh(() => scopesApi().setMemberRole({ scopeId, userId, setMemberRoleRequest: { role } }))
}

/**
 * Rescind a pending consent proposal (uc.spaces.consent-group-add) — owner-gated (`403 not_scope_owner`). A
 * proposal was never keyed, so nothing rotates. Any other member is removed only by the removal-rotation
 * (`rotateGroupForRemoval`, ADR 0024 Decision 4); the server refuses this delete for them
 * (`409 removal_requires_rotation`), so a removal can never end without its rotation.
 */
export function removeGroupMember(scopeId: string, userId: string): Promise<void> {
  return withRefresh(() => scopesApi().removeMember({ scopeId, userId }))
}

/**
 * Decode a failed member call into `{ status, code }` for UI mapping (401 → re-login, 403 → not owner /
 * not a member, 409 → already_member / tenant_not_active). Thin domain-named alias over the shared
 * {@link decodeHttpError} — kept so its call sites read in member terms.
 */
export function memberError(e: unknown): Promise<{ status: number; code: string | null }> {
  return decodeHttpError(e)
}
