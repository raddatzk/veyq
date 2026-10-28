// uc.spaces.add-member (web, cr-0077) — the member-management data layer for a tenant OWNER. Inviting by email is
// tenant-owner-gated; the member directory (pubkeys) is any key-holder's. Operator-blind: the server holds invite
// metadata, the invite's public key and opaque grants, and already-stored operational metadata (pubkeys/roles) —
// never a scope key. Every grant here names the key it is wrapped to and is signed by its granter (ADR 0111).
import { tenantsApi, scopesApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { decodeHttpError } from '../../shared/httpError'
import { commitRotation, openScopeById } from './scopes'
import { buildMemberInvite, type InviteSecret } from './memberInvite'
import { recipientIdentityDigest, sealGrant } from './grant'
import { writeKey, zeroEpochKeys } from './epochs'
import { anchorIdentity, IdentityUnanchoredError, type ServedIdentity } from '../account/identityPins'
import { identityPrivate } from '../../app/vault'
import { tenantViewOfScope } from '../account/tenantTrust'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { SetTenantMemberRoleRequestRoleEnum, type MemberInviteState, type PendingMemberInvite } from '../../api/generated/tenants'
import { SetMemberRoleRequestRoleEnum, type ScopeMember, type ResolvedGroupCandidate, type GroupInvitation } from '../../api/generated/scopes'

export type { ScopeMember, ResolvedGroupCandidate, GroupInvitation } from '../../api/generated/scopes'
export { SetMemberRoleRequestRoleEnum } from '../../api/generated/scopes'
export type { MemberInviteState, PendingMemberInvite } from '../../api/generated/tenants'
export { SetTenantMemberRoleRequestRoleEnum } from '../../api/generated/tenants'
// The invite delivery result (cr-email-invite-send-hardening): SENT / FALLBACK_COPYLINK / FAILED — the three-state
// signal the invite dialogs branch on so the owner always knows whether the mail actually went out.
export { DeliveryResult } from '../../api/generated/tenants'

export type { InviteSecret } from './memberInvite'
export { prepareInviteCode } from './memberInvite'

/** Where an invite's send stands, for the dialog's progress line (inbox #566): `securing` while the client opens the tenant key
 *  and seals the invite (the link key's Argon2id included, unless it was derived ahead), `sending` while the server stores it
 *  and mails the link — its answer waits for the mail to be handed over. */
export type InvitePhase = 'securing' | 'sending'

/**
 * Invite a person (by email) into the active tenant as a MEMBER — owner-gated (403 `not_tenant_owner`). The invite is a
 * placeholder member (ADR 0111 decisions 1-2, {@link buildMemberInvite}): it needs the unlocked vault and the tenant's
 * key. A `409 stale_epoch` — a rotation landed while it was built — is rebuilt once against the new epoch. [onPhase] hears
 * each phase as it begins.
 */
export async function inviteMember(
  tenantId: string, email: string, secret: InviteSecret, onPhase?: (phase: InvitePhase) => void,
): Promise<MemberInviteState> {
  return onceMoreIfStale(async () => {
    onPhase?.('securing')
    const memberInviteRequest = await buildMemberInvite(tenantId, email, secret)
    onPhase?.('sending')
    return withRefresh(() => tenantsApi().inviteTenantMember({ tenantId, memberInviteRequest }))
  })
}

/** Run [call], and run it once more when it was built on an epoch a rotation left (`409 stale_epoch`). */
async function onceMoreIfStale<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (e) {
    if (await isCodedError(e, HttpStatus.conflict, 'stale_epoch')) return call()
    throw e
  }
}

/** List the tenant's pending member invites (invited-but-not-joined) — owner-gated (uc.spaces.manage-member-invites). */
export function listMemberInvites(tenantId: string): Promise<PendingMemberInvite[]> {
  return withRefresh(() => tenantsApi().listMemberInvites({ tenantId }))
}

/** Re-issue a pending member invite: supersede it (its key and grant go) and mail a new one to the same [email] — a new
 *  invite key, grant, link and code ({@link buildMemberInvite}) — owner-gated. */
export function reissueMemberInvite(tenantId: string, inviteId: string, email: string, secret: InviteSecret): Promise<MemberInviteState> {
  return onceMoreIfStale(async () => {
    const memberInviteRequest = await buildMemberInvite(tenantId, email, secret)
    return withRefresh(() => tenantsApi().reissueMemberInvite({ tenantId, inviteId, memberInviteRequest }))
  })
}

/** Revoke a pending member invite (soft-terminal; its secret no longer accepts, its key and grant are dropped) — owner-gated. */
export function revokeMemberInvite(tenantId: string, inviteId: string): Promise<void> {
  return withRefresh(() => tenantsApi().revokeMemberInvite({ tenantId, inviteId }))
}

/** List a scope's members with their identity pubkeys (the directory) — key-holder only. */
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
 * Give the tenant a new lock (ADR 0025 § 2, uc.spaces.remove-tenant-member A8-A9) — an owner's plain rotation of the
 * TENANT scope, for the compromise case: a member's device lost, or a removed member who may have kept the key. It is
 * {@link commitRotation}, the one path every rotation takes: the next epoch minted on this device, every member whose
 * key it anchors re-granted, the rest and every open invite it cannot vouch for declared pending. Returns the new epoch.
 * The caller re-keys the nodes after it (ADR 0109).
 *
 * The directory is read **now**, after any removal, so the request covers every membership the server holds (`409
 * rotation_incomplete` otherwise). `excluding` — the member a removal just took out — is dropped from it by the owner's
 * own choice, however the server lists them: the new epoch is never wrapped to them. A server that kept their
 * membership meets the rotation's cover check, and nothing commits. Never a `removesUserId`: a tenant member leaves only
 * by the removal cascade (`400 invalid_request` on a TENANT, inbox #595), which runs first.
 */
export async function rekeyTenant(tenantScopeId: string, excluding?: string): Promise<number> {
  const members = (await listScopeMembers(tenantScopeId)).filter((m) => m.userId !== excluding)
  return commitRotation(tenantScopeId, members)
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
 * **A group needs no invite key** (ADR 0111 decision 4): the target has an account, so the owner's client wraps the
 * group's current epoch key to the identity key [target] the resolve served — once it is anchored (the key and its
 * `keyChainHead`, {@link anchorIdentity}), as the name-key's wrap is gated ({@link ./scopes#wrapGroupNameKeyForInvitee});
 * an anomaly throws {@link IdentityUnanchoredError} and nothing is sent. The member holds the key from the add on.
 *
 * Consent (cr-0096): when the target has "ask before adding me to groups" on, pass `sealedNameKey` (the group's
 * derived name-key `K_name` wrapped to their identity) — the add becomes a soft `pendingConsent` proposal holding the
 * same grant, and the group is listed to them once they accept. Omitting it for a consent-on target is rejected
 * `400 consent_required` (resolve first to learn whether it is required).
 */
export async function addGroupMember(
  scopeId: string, username: string, targetUserId: string, target: ServedIdentity, sealedNameKey?: string,
): Promise<ScopeMember> {
  // In the group's tenant (ADR 0111 decision 15): a target without a join record who is no owner there is not wrapped to.
  const tenant = await tenantViewOfScope(scopeId)
  if ((await anchorIdentity(targetUserId, target, { tenant })) === 'anomaly') throw new IdentityUnanchoredError(targetUserId)
  const targetIdentityPublicKeyB64 = target.identityPublicKey
  return onceMoreIfStale(async () => {
    const opened = await openScopeById(scopeId)
    if (!opened) throw new Error('cannot open the group to add — the vault is locked or no grant is held')
    const priv = identityPrivate()
    try {
      if (!priv) throw new Error('vault is locked — cannot add')
      const epoch = opened.epochs.current
      const grant = sealGrant(
        writeKey(opened.epochs), { scopeId, kind: 'GROUP', epoch },
        { userId: targetUserId, identityPublicKeyB64: targetIdentityPublicKeyB64 }, priv.sign,
      )
      const addGroupMemberRequest = {
        username, sealedNameKey, epoch, ...grant, recipientIdentityDigest: recipientIdentityDigest(targetIdentityPublicKeyB64),
      }
      return await withRefresh(() => scopesApi().addGroupMember({ scopeId, addGroupMemberRequest }))
    } finally {
      priv?.kemSeed.fill(0)
      priv?.sign.fill(0)
      zeroEpochKeys(opened.epochs)
    }
  })
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

/** Accept a group-consent proposal (cr-0094) — become a member; the proposal already holds the group's key (ADR 0111). */
export function acceptGroupInvitation(scopeId: string): Promise<void> {
  return withRefresh(() => scopesApi().acceptGroupInvitation({ scopeId }))
}

/** Decline a group-consent proposal (cr-0094) — a silent drop; the proposal held the key, so the group rotates. Idempotent. */
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
 * Rescind a pending consent proposal (uc.spaces.consent-group-add) — owner-gated (`403 not_scope_owner`). Any other
 * member is removed only by the removal-rotation
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
