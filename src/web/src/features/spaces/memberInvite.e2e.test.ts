// Live e2e (VEYQ_E2E=1) for ADR 0111 decisions 1-4 — against a REAL backend +
// MariaDB with REAL crypto, no mocks. A member invite is a placeholder member holding an owner-signed invite key and a
// grant of the tenant's current epoch; the link's secret part is sealed under a code the owner passes on outside veyq.
// What only this layer proves: that the server stores and serves the invite exactly as the owner's client built it, so
// the invitee's client opens it with the code, checks it, grants ITSELF the tenant key and is in the space at once — with
// no other device online, nobody running a grant delivery — and that the join record it wrote comes back on the
// member listing, opens under the tenant key and verifies.
//
// Actors: A = tenant owner (Server Owner too); B = invited by A with a code; R = an invite A revokes. Every negative
// has its control on the same predicate. Run against a FRESH instance — `python3 scripts/run_web_e2e.py <this file>`.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import * as invite from '@veyq/crypto/invite'
import { b64std } from '@veyq/crypto/base64'
import { buildAccount } from '@veyq/crypto/accountBirth'
import {
  acceptGroupInvitation, addGroupMember, inviteMember, listMemberInvites, listMyGroupInvitations, listScopeMembers,
  resolveGroupMember, revokeMemberInvite,
} from './members'
import { createGroup, listMyScopes, openScope, openScopeById, wrapGroupNameKeyForInvitee } from './scopes'
import { InviteCodeWrong, forgetMemberInvite, openMemberInvite, previewInvite, registerAndAccept } from '../tenant/tenantInvite'
import { updateMyProfile } from '../account/profile/profile'
import { createFolder, listTree } from '../../core/node/tree'
import { decodeHttpError } from '../../shared/httpError'
import { become, birthInstance, mkActor, type Actor } from '../../test/e2eBootstrap'
import { memberInviteFragment, memberJoinFrom } from '../../test/memberJoin'
import { mailCursor, waitForMail } from '../../test/mailpit'
import { recordCanaries, recordPii } from '../../test/canaries'
import { ScopeKind } from '../../api/generated/scopes'
import { openJoinRecord } from '../account/tenantTrust'

const TENANT_FOLDER = 'Familienrezepte Oma Hildegard'
const GROUP_NAME = 'Segelfreunde Ammersee'
const GROUP_FOLDER = 'Toerns Sommer Utting'

/** A invites [who] with a fresh code through the real client and returns the code and the mailed link's fragment. */
async function invited(tenantId: string, who: Actor): Promise<{ code: string; fragment: string }> {
  const cursor = await mailCursor(who.email)
  const code = invite.generateInviteCode()
  await inviteMember(tenantId, who.email, { mode: 'code', code })
  const body = await waitForMail(who.email, { after: cursor })
  const fragment = memberInviteFragment(body)
  expect(fragment, `a member invite link (token.sealed) in the mail to ${who.email}:\n${body}`).toBeTruthy()
  return { code, fragment: fragment! }
}

/** The status and code a refused call ended in, or `resolved` — anchored, never "it threw". */
async function refusal(p: Promise<unknown>): Promise<string> {
  try {
    await p
    return 'resolved'
  } catch (e) {
    const { status, code } = await decodeHttpError(e)
    return `${status} ${code}`
  }
}

describe.skipIf(!process.env.VEYQ_E2E)('a member invite is a placeholder member (ADR 0111, live, real crypto)', () => {
  it('B joins with the code and holds the tenant key at once; a wrong code opens nothing; a revoked link is gone; a group add carries the grant', async () => {
    const { owner: A, tenant, salt } = await birthInstance('E2E Member Invite')
    const tenantId = tenant.tenantId

    // --- A writes into the tenant before anyone else is in it ---------------------------------------------------
    const openedA = await openScope(ScopeKind.Tenant)
    expect(openedA, 'A holds the tenant key').toBeTruthy()
    await createFolder(tenantId, openedA!.epochs, TENANT_FOLDER)
    recordCanaries(TENANT_FOLDER, GROUP_NAME, GROUP_FOLDER)

    // --- A invites B with a code; the link travels by mail, the code by hand ------------------------------------
    const B = mkActor('b')
    const { code, fragment } = await invited(tenantId, B)
    const { token, sealed } = invite.parseInviteFragment(fragment)
    const preview = await previewInvite(token)
    expect(preview.role, 'a MEMBER invite').toBe('MEMBER')
    expect(preview.invite, 'the preview serves the placeholder member: invite key, signature, grant').toBeTruthy()
    expect(preview.invite!.signerUserId, 'signed by A').toBe(A.userId)

    // A wrong code opens nothing — the client refuses before anything is sent.
    let wrong = invite.generateInviteCode()
    while (wrong === code) wrong = invite.generateInviteCode()
    await expect(openMemberInvite(tenantId, preview.invite!, sealed!, wrong), 'another code does not open the link').rejects.toBeInstanceOf(InviteCodeWrong)
    // Control on the same call: the right code opens it and the invite's checks pass.
    const opened = await openMemberInvite(tenantId, preview.invite!, sealed!, code)
    expect(opened.grantedEpoch, 'the invite holds the tenant\'s current epoch').toBe(0)
    expect(b64std(opened.epochKey), 'the invite\'s grant carries the tenant key A holds').toBe(b64std(openedA!.epochs.key))
    forgetMemberInvite(opened)

    // --- B registers through the real accept with its join: the self-grant, the invite key's proof, the join record --
    B.built = await buildAccount(recordPii(B.email), B.password, salt)
    const bId = crypto.randomUUID()
    const join = await memberJoinFrom(preview, fragment, code, bId, B.built)
    B.userId = (await registerAndAccept(token, bId, B.username, B.built.material, undefined, undefined, undefined, join)).userId
    expect(B.userId, 'the account is the one the join was built for').toBe(bId)
    expect((await listMemberInvites(tenantId)).some((i) => i.email === B.email), 'the accepted invite is no longer open').toBe(false)

    // --- B, with nobody else online and no grant delivery run: in the tenant at once, reading what A wrote --------
    await become(B)
    const rowB = (await listMyScopes()).find((s) => s.id === tenantId)
    expect(rowB?.grantedEpoch, 'B\'s membership was born with its grant').toBe(0)
    const openedB = await openScope(ScopeKind.Tenant)
    expect(openedB, 'B opens the tenant with the grant it gave itself').toBeTruthy()
    const treeB = await listTree(tenantId, openedB!.epochs)
    expect(treeB.map((n) => n.name), 'B reads the tenant\'s content').toContain(TENANT_FOLDER)

    // --- the join record: on the listing, sealed under the tenant key, signed by the invite key and B's identity ---
    await become(A)
    const rowOfB = (await listScopeMembers(tenantId)).find((m) => m.userId === B.userId)
    expect(rowOfB?.sealedJoinRecord, 'the listing serves B\'s sealed join record').toBeTruthy()
    // Sealed under a value key of its own, wrapped under the tenant key (ADR 0115 decision 8): opened the way the
    // client's own reader opens it, `tenantTrust.openJoinRecord`, never with the tenant key directly.
    expect(rowOfB?.wrappedJoinRecordValueKey, 'the listing serves the record\'s wrapped value key beside it').toBeTruthy()
    const tenantEpochs = (await openScope(ScopeKind.Tenant))!.epochs
    const openRecordAs = (userId: string) =>
      openJoinRecord(tenantId, userId, rowOfB!.sealedJoinRecord!, rowOfB!.wrappedJoinRecordValueKey, tenantEpochs)
    const record = openRecordAs(B.userId!)!
    expect(record, 'the record opens').toBeTruthy()
    expect(invite.joinRecordVerifies({ tenantId, userId: B.userId! }, record), 'both signatures and A\'s invite-key signature verify').toBe(true)
    expect(b64std(record.identityPublicKey), 'it names B\'s identity').toBe(b64std(v1.serializeIdentityPublic(B.built.identity.pub)))
    expect(b64std(record.secretKeyProofPublicKey), 'and the Secret-Key proof key B registered').toBe(B.built.material.secretKeyProofPublicKey)
    expect(record.signerUserId, 'through the invite A signed').toBe(A.userId)
    // Control: the record is bound to its member — opened as another member's, it does not open.
    expect(openRecordAs(A.userId!), 'bound to (tenant, B)').toBeNull()

    // --- a revoked invite: its link is gone at preview ----------------------------------------------------------
    const R = mkActor('r')
    const { token: rToken } = invite.parseInviteFragment((await invited(tenantId, R)).fragment)
    // Control: before the revoke, the same token previews.
    expect((await previewInvite(rToken)).invite, 'an open invite previews').toBeTruthy()
    const rInvite = (await listMemberInvites(tenantId)).find((i) => i.email === R.email)
    expect(rInvite, 'R\'s invite is open').toBeTruthy()
    await revokeMemberInvite(tenantId, rInvite!.inviteId)
    expect(await refusal(previewInvite(rToken)), 'a revoked invite\'s token is gone').toBe('410 invite_no_longer_valid')

    // --- a group add carries the grant: B, asked to consent, opens the group as soon as it accepts ---------------
    await become(B)
    await updateMyProfile({ requireGroupAddConsent: true })
    await become(A)
    const g = await createGroup(GROUP_NAME)
    const openedG = await openScopeById(g)
    await createFolder(g, openedG!.epochs, GROUP_FOLDER)
    const candidate = await resolveGroupMember(g, B.username)
    expect(candidate.requiresConsent, 'B asked to be consulted').toBe(true)
    expect(candidate.identityPublicKey, 'the resolve serves B\'s identity key').toBe(b64std(v1.serializeIdentityPublic(B.built.identity.pub)))
    const sealedNameKey = await wrapGroupNameKeyForInvitee(g, candidate.userId, candidate)
    await addGroupMember(g, B.username, candidate.userId, candidate, sealedNameKey!)
    expect((await listScopeMembers(g)).find((m) => m.userId === B.userId)?.pendingConsent, 'a proposal until B accepts').toBe(true)

    await become(B)
    expect((await listMyGroupInvitations()).map((i) => i.scopeId), 'B sees the invitation').toEqual([g])
    await acceptGroupInvitation(g)
    // Nobody else online, no grant delivery: the proposal held the grant, so accepting is all it takes.
    const openedGB = await openScopeById(g)
    expect(openedGB?.name, 'B opens the group with the grant the add carried').toBe(GROUP_NAME)
    expect((await listTree(g, openedGB!.epochs)).map((n) => n.name), 'B reads the group\'s content').toContain(GROUP_FOLDER)
  }, 300_000)
})
