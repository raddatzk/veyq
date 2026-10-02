// Live e2e (VEYQ_E2E=1) for inbox #159's first door — "a tenant owner's SESSION is enough to get a scope's plaintext"
// — against a REAL backend + MariaDB with REAL crypto, no mocks (ADR 0020 move 6).
//
// The attack, as the security model states it: a password alone opens a session; the session mints an invite to an
// address the attacker controls; the attacker registers with keys of their own; the next online key-holder's device
// then wraps the tenant key to them with no human involved. With admission a key-holder act, the session can reach
// the invite endpoint but cannot compute the invite's tag (that needs K_tenant, behind the Secret Key), so it sends
// any bytes — and every key-holder's worker declines that member instead of wrapping.
//
// Actors: A = tenant owner (server owner too); X = the attacker's account; M = a genuine member A invites from an
// unlocked vault (the control, on the same worker run). Run against a FRESH instance — `python3
// scripts/run_web_e2e.py` does that, which is what CI runs.
import { describe, it, expect, vi } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { tenantsApi } from '../../api/client'
import { TasksApi } from '../../api/generated/tasks'
import { clearVault } from '../../app/vault'
import { login } from '../account/login/login'
import { registerAndAccept } from '../tenant/tenantInvite'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { inviteMember, listScopeMembers } from './members'
import { openScope } from './scopes'
import { createMemory, listTree } from '../memory/tree'
import { ADMISSION_UNVERIFIED, runGrantDeliveryWorker } from '../tasks/grantDeliveryWorker'
import { become, birthInstance, mkActor, onboardMember } from '../../test/e2eBootstrap'
import { mailCursor, waitForMail } from '../../test/mailpit'
import { recordCanaries } from '../../test/canaries'
import { ScopeKind } from '../../api/generated/scopes'

describe.skipIf(!process.env.VEYQ_E2E)('#159 — an owner session alone admits nobody (live, real crypto)', () => {
  it('an invite minted without the vault is never delivered to; the owner\'s real invite is', async () => {
    const { owner: A, tenant, salt } = await birthInstance('E2E Admission')

    // --- the attacker holds A's PASSWORD only: a session, no Secret Key, so no vault and no K_tenant ---
    await login(A.email, A.password)
    clearVault()
    // The product's own path refuses outright: an admission cannot be tagged without the tenant key.
    const X = mkActor('x')
    await expect(inviteMember(tenant.tenantId, X.email), 'the client cannot admit from a locked vault').rejects.toThrow()
    // So the session goes to the endpoint directly, with the only tag it can make: any bytes.
    const cursor = await mailCursor(X.email)
    await tenantsApi().inviteTenantMember({
      tenantId: tenant.tenantId,
      memberInviteRequest: {
        email: X.email, admissionId: crypto.randomUUID(), admissionEpoch: 0, admissionTag: b64std(v1.randomBytes(32)),
      },
    })
    // The attacker redeems it with an account of their own — a genuine registration, keys born on their device.
    const mail = await waitForMail(X.email, { after: cursor })
    const link = mail.match(/\/tenant\/accept#([A-Za-z0-9_-]+)/)
    expect(link, 'the forged invite was mailed').toBeTruthy()
    X.built = await buildAccount(X.email, X.password, salt)
    X.userId = (await registerAndAccept(link![1], crypto.randomUUID(), X.username, X.built.material)).userId

    // --- the control on the same predicate: A, unlocked, invites M the product's way (a real tag) ---
    await become(A)
    const M = await onboardMember(tenant.tenantId, mkActor('m'), salt)

    // --- a genuine key-holder's worker runs (A's device): it must deliver to M and decline X ---
    // Recorded as the worker sends it — a spy that calls through, so the real decline reaches the live backend.
    const spy = vi.spyOn(TasksApi.prototype, 'declineTask')
    const tenantScope = await openScope(ScopeKind.Tenant)
    expect(tenantScope, 'A holds the tenant key').toBeTruthy()
    const delivered = await runGrantDeliveryWorker({
      scopeId: tenant.tenantId, kind: ScopeKind.Tenant, key: tenantScope!.key, epoch: tenantScope!.epochs.current,
    })
    const reasons = spy.mock.calls.map(([req]) => req.declineRequest?.reason)
    spy.mockRestore()

    expect(delivered, 'exactly one grant delivered — M\'s').toBe(1)
    expect(reasons, 'X\'s job was declined for its admission').toEqual([ADMISSION_UNVERIFIED])
    const roster = await listScopeMembers(tenant.tenantId)
    expect(roster.find((m) => m.userId === M.userId)?.hasGrant, 'the genuine member was delivered to').toBe(true)
    expect(roster.find((m) => m.userId === X.userId)?.hasGrant, 'the attacker\'s membership holds NO grant').toBe(false)

    // X cannot open the tenant scope — there is nothing to open.
    await become(X)
    expect(await openScope(ScopeKind.Tenant), 'X reads the tenant as awaiting access').toBeNull()

    // M can: the delivered grant opens, signed by A (ADR 0028 Decision 6), and M reads what A writes under K_tenant.
    await become(A)
    const openedA = await openScope(ScopeKind.Tenant)
    await createMemory(tenant.tenantId, openedA!.epochs, 'Admission e2e memo')
    await become(M)
    const openedM = await openScope(ScopeKind.Tenant)
    expect(openedM, 'M opens the tenant with the grant A\'s worker signed').toBeTruthy()
    const tree = await listTree(tenant.tenantId, openedM!.epochs)
    expect(tree.some((n) => n.name === 'Admission e2e memo'), 'M reads the tenant\'s content').toBe(true)

    // The wire assertion's derivation (ADR 0053): the one plaintext this flow sealed, as the member recovered it.
    recordCanaries(tree.map((n) => n.name))
  }, 240_000)
})
