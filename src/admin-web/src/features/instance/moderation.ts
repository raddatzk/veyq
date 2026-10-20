// uc.moderation.appoint-moderators + uc.moderation.take-a-measure step 5 (admin-web, ADR 0099) — the Server Owner's
// half of moderation, and the ONE place in this package that mints the instance moderation key. The console's
// Server → Moderation panel and `veyq-admin moderator …` / `moderation …` / `account block|unblock` all call it, so
// the rules below hold for the UI and the CLI alike.
//
// What it keeps honest:
//   - admin-web holds no vault (ADR 0099 § 3): it draws `M_n`, derives the epoch's public bundle, seals `M_n` to each
//     recipient's identity PUBLIC key, uploads, and forgets it — the bytes are zeroed in a `finally`. It never opens a
//     grant; it imports no open function from `@veyq/crypto/v1` and no namespace through which one could be reached
//     (`moderation.noOpen.test.ts` holds that for this file and for every file of the CLI).
//   - Grants are unsigned (owner decision Q4; the residual is ADR 0099 § 3's): with no vault there is no identity
//     signing key to sign with. A server that substituted a grant would also have to substitute the published bundle,
//     which a moderator's unlock compares against what it derives.
//   - The server is the authority on the roster: a rotation seals to exactly the accepted moderators as the server
//     served them, and a roster that changed in between is the server's refusal (`409 moderation_grants_incomplete`),
//     surfaced, never retried here behind the Server Owner's back.
import { instanceModerationApi } from '../../api/client'
import {
  InstanceModeratorStateEnum,
  type InstanceModeration,
  type MintModerationEpochRequestReasonEnum,
  type ModerationGrantForMint,
  type ModerationSteps,
} from '../../api/generated/instance'
import { deserializeIdentityPublic, generateModerationKey, moderationBundle, sealTo } from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import type { ServerOwnerStatementInput } from './stepStatement'

export type {
  AccountSignInBlock, InstanceModeration, InstanceModerator, ModerationSteps, OpenModerationProposal,
} from '../../api/generated/instance'
// Each re-export carries the generated const AND its union type, so `RotationReason.Removal` and `: RotationReason`
// both read from here.
export {
  InstanceModeratorStateEnum as ModeratorState,
  MintModerationEpochRequestReasonEnum as RotationReason,
  OpenModerationProposalStepEnum as ProposalStep,
} from '../../api/generated/instance'

/** `M_n` sealed to one identity public key (standard base64, as the roster serves it) for epoch `epoch`. */
function sealGrant(m: Uint8Array, epoch: number, identityPublicKey: string): string {
  const pub = deserializeIdentityPublic(fromB64std(identityPublicKey))
  return b64std(sealTo({ purpose: 'moderation-grant', epoch }, pub.kem, m))
}

/** The epoch's public bundle on the wire: what every reporter seals to and every moderator's unlock compares. */
function wireBundle(m: Uint8Array): { kemPublicKey: string; signPublicKey: string } {
  const bundle = moderationBundle(m)
  return { kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign) }
}

/** The roster, the current epoch, and whether a rotation is due. Refusal: 403 `not_server_owner`. */
export function getModeration(): Promise<InstanceModeration> {
  return instanceModerationApi().getInstanceModeration()
}

/**
 * Epoch 1, for first-run setup (ADR 0099 § 3; owner 2026-10-17): drawn, sealed to the identity public key the setup
 * just birthed (`moderation-grant` [1]) and forgotten, so the instance has a moderation key — and reporting is on —
 * from its first day, with the Server Owner as its first moderator. Setup sends it with the owner's account in one
 * request (`createServerOwner`); it is minted here so this file stays the one place that mints.
 */
export function epochOneFor(identityPublicKey: string): { kemPublicKey: string; signPublicKey: string; sealedGrant: string } {
  const m = generateModerationKey()
  try {
    return { ...wireBundle(m), sealedGrant: sealGrant(m, 1, identityPublicKey) }
  } finally {
    m.fill(0)
  }
}

/**
 * Appoint an existing account, by its address. It stays pending until the appointee accepts in src/web/'s moderation
 * page (`/moderation`); a holder's client then delivers the key to it — admin-web cannot, it holds no `M`. Refusals:
 * 404 `account_not_found` (no account has this address), 409 `moderation_off`, 403 `not_server_owner`.
 */
export function appointModerator(address: string): Promise<InstanceModeration> {
  return instanceModerationApi().appointModerator({ appointModeratorRequest: { address: address.trim() } })
}

/**
 * Rotate the instance moderation key: a fresh `M_{n+1}`, sealed to EVERY accepted moderator's identity public key —
 * a pending appointee gets nothing, it is delivered the key once it accepts — and uploaded with its public bundle and
 * the reason category the moderation log records (ADR 0099 § 4). `state` is the roster to seal to; read here when not
 * given. Reports from then on seal to the new epoch; old ones stay sealed to theirs.
 *
 * The request names the epoch the grants are sealed for, so a mint that raced another is refused rather than stored
 * under an epoch its grants do not open as.
 *
 * Refusals (nothing is stored on any of them): 409 `moderation_epoch_stale` (another mint landed since `state` was
 * read — read again), 409 `moderation_grants_incomplete` (the grants do not name exactly the
 * accepted moderators — the roster changed since `state` was read; read again and rotate again), 409 `identity_changed`
 * (a moderator's identity changed since), 409 `moderation_off` (no epoch exists: a null `currentEpoch` is sent as it
 * is and the server answers, rather than a second refusal invented here), 403 `not_server_owner`.
 */
export async function rotateModerationKey(reason: MintModerationEpochRequestReasonEnum, state?: InstanceModeration): Promise<InstanceModeration> {
  const roster = state ?? (await getModeration())
  const epoch = (roster.currentEpoch ?? 0) + 1
  const m = generateModerationKey()
  try {
    const grants: ModerationGrantForMint[] = roster.moderators.flatMap((mod) =>
      mod.state === InstanceModeratorStateEnum.Accepted && mod.identityPublicKey
        ? [{ accountId: mod.accountId, identityPublicKey: mod.identityPublicKey, sealedGrant: sealGrant(m, epoch, mod.identityPublicKey) }]
        : [],
    )
    return await instanceModerationApi().mintModerationEpoch({
      mintModerationEpochRequest: { epoch, ...wireBundle(m), reason, grants },
    })
  } finally {
    m.fill(0)
  }
}

/**
 * Remove a moderator, then — when that left a rotation due (they held the current epoch) — rotate for the rest with
 * reason `REMOVAL`, sealed to the roster the removal answered with. One client step, two calls (ADR 0099 § 4; CR
 * decision 6): the server deletes the leaver's grants of every epoch at once, the new epoch keeps them out of every
 * report after it. A removed pending appointee held nothing, so no rotation follows. If the rotation is refused, the
 * removal stands and the rejection propagates: the roster then reports the rotation as due.
 * Refusals: 404 `not_moderator`, 409 `moderation_last_holder` (the last accepted holder of the key), and the
 * rotation's own (see {@link rotateModerationKey}).
 */
export async function removeModerator(accountId: string): Promise<InstanceModeration> {
  const after = await instanceModerationApi().removeModerator({ accountId })
  return after.rotationDueSince ? rotateModerationKey('REMOVAL', after) : after
}

// uc.moderation.take-a-measure step 5 — the Server Owner's steps. A moderator proposes; only the Server Owner takes
// one: a member's sign-in block here, or the tenant's suspension (`features/tenants/tenants.ts`'s `suspendTenant`,
// which settles every open TENANT_OFFLINE proposal of that tenant as taken). None of these touches a key; each
// answers with the steps as they now stand.

/** The open proposals and every sign-in block in force. Refusal: 403 `not_server_owner`. */
export function getModerationSteps(): Promise<ModerationSteps> {
  return instanceModerationApi().getModerationSteps()
}

/** Decline a proposal; the case shows it, the log records it. Refusal: 404 `proposal_not_found`. */
export function declineProposal(proposalId: string): Promise<ModerationSteps> {
  return instanceModerationApi().declineModerationProposal({ proposalId })
}

/**
 * Block an account's sign-in, by its address, answering `caseId` when a proposal asked for it: every session of the
 * account ends at once and every sign-in is refused until the block is lifted. Settles the account's open
 * MEMBER_SIGN_IN proposals as taken. Over content — a case, an open proposal, or `overContent` — it carries its
 * `statement` (ADR 0093 § 7, `stepStatement.ts`), mailed with the facts to the account's address. Refusals: 400
 * `statement_required` / `statement_facts_required` / `statement_provision_required`, 404 `account_not_found` /
 * `case_not_found`, 409 `sign_in_block_exists` / `cannot_block_server_owner` / `moderation_epoch_changed`.
 */
export function blockSignIn(
  address: string, caseId?: string | null, statement?: ServerOwnerStatementInput | null, overContent = false,
): Promise<ModerationSteps> {
  return instanceModerationApi().blockAccountSignIn({
    blockAccountSignInRequest: { address: address.trim(), caseId: caseId ?? null, overContent: overContent || !!statement, statement: statement ?? null },
  })
}

/** Lift a sign-in block; the account signs in again with its own keys. Refusal: 404 `sign_in_block_not_found`. */
export function liftSignInBlock(blockId: string): Promise<ModerationSteps> {
  return instanceModerationApi().unblockAccountSignIn({ blockId })
}
