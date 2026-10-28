// A member joins a tenant the one way the product joins one (ADR 0111 decisions 1 and 3) — for the live e2e suites and
// the dev seeders, which onboard members over the real API: the owner's invite (`inviteMember` with a code), the mailed
// link read out of the mailbox, and the invitee's half through the client's own functions — the code opens the link's
// sealed part, `openMemberInvite` checks it against the owner's fingerprint inside the seal, `buildMemberJoin` grants the
// newcomer the tenant key and writes its join record. Nothing here is a shortcut around those checks.
import { parseInviteFragment } from '@veyq/crypto/invite'
import { fromB64std } from '@veyq/crypto/base64'
import type { BuiltAccount } from '@veyq/crypto/accountBirth'
import type { InvitePreview, TenantActivatedWithUser } from '../api/generated/tenants'
import {
  buildMemberJoin, forgetMemberInvite, openMemberInvite, previewInvite, registerAndAccept, type MemberJoin,
} from '../features/tenant/tenantInvite'

/**
 * A member invite's link in a mail body (`SmtpTenantInviteMailer`: `https://$domain/tenant/accept#<token>.<sealed>`),
 * anchored on the accept path and the fragment — never on a base URL: the mail's link is `https` with no port, while a
 * suite talks to `http://…:8080`. Both parts are base64url, so neither holds the `.` between them.
 */
const MEMBER_INVITE_LINK = /\/tenant\/accept#([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)/

/** The fragment (`token.sealed`) of the member invite link in [body], or null when the body carries none. */
export function memberInviteFragment(body: string): string | null {
  return body.match(MEMBER_INVITE_LINK)?.[1] ?? null
}

/**
 * The invitee's half for the account [userId] born as [built]: open the link's sealed part with [code], check the
 * invite [preview] served, and build the join — the self-grant, the invite key's proof and the sealed join record. The
 * invite's keys are wiped before it returns.
 */
export async function memberJoinFrom(
  preview: InvitePreview, fragment: string, code: string, userId: string, built: BuiltAccount,
): Promise<MemberJoin> {
  const { sealed } = parseInviteFragment(fragment)
  if (!sealed || !preview.invite) throw new Error('not a member invite: no sealed part or no served invite')
  const opened = await openMemberInvite(preview.tenantId, preview.invite, sealed, code)
  try {
    return buildMemberJoin(opened, {
      userId, identity: built.identity, secretKeyProofPublicKey: fromB64std(built.material.secretKeyProofPublicKey),
    })
  } finally {
    forgetMemberInvite(opened)
  }
}

/** Register the cold-born [built] and accept the member invite [fragment] with [code] as [username], over the session's
 *  API base — preview, the checks, the join, register-and-accept. */
export async function registerThroughMemberInvite(
  fragment: string, code: string, username: string, built: BuiltAccount,
): Promise<TenantActivatedWithUser> {
  const { token } = parseInviteFragment(fragment)
  const preview = await previewInvite(token)
  const userId = crypto.randomUUID()
  const join = await memberJoinFrom(preview, fragment, code, userId, built)
  return registerAndAccept(token, userId, username, built.material, undefined, undefined, undefined, join)
}
