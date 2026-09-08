import { describe, it, expect } from 'vitest'
import { birthInstance, HOST, stamp } from '../../test/e2eBootstrap'
import { MAILPIT_SMTP_PORT } from '../../test/mailpit'
import { recordCanaries } from '../../test/canaries'
import { writeHandoff } from '../../test/restoreHandoff'
import { getTenantMailSettings, updateTenantMailSettings } from '../admin/createTenant/tenants'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createMemory, listTree } from '../memory/tree'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Part A of the **leg-3 rehearsal** (`three-legs-or-it-is-not-a-backup`, ADR 0056 §1). It cannot share the
 * restore group's run: that group's whole claim is that a COMPLETE restore is complete, and this one needs a
 * restore that is deliberately missing leg (c). Same hook, one artifact removed from the capture.
 *
 * **The assertion below is a positive control, and without it the whole group would be green for the wrong
 * reason.** `ConfigSecretKeyGuard.state()` returns `MISMATCH` only when a fingerprint is *already stored*; with
 * none stored it **adopts** the current key and returns `OK`. So if part A never causes a configured read, part
 * B's missing key would produce `OK` — a bite that proves the exact opposite of what it claims. Reading the
 * state here is what causes the adopt, and asserting it is what makes that dependency load-bearing rather than
 * incidental.
 */
describe.skipIf(!process.env.VEYQ_E2E)('leg-3 rehearsal — capture', () => {
  it('births an instance whose config-secret fingerprint is adopted and OK', async () => {
    const { owner, tenant } = await birthInstance('E2E Leg Three')

    // `birthInstance` deliberately configures mail with `username: null`, which keeps every other suite OFF the
    // sealed-password path. This group needs to be ON it: `TenantMailSender.sendable` requires a username, a
    // stored password AND `state() == OK`, so an auth-less relay stays sendable with no KEK at all — and a
    // leg-3 rehearsal built on it would restore without the key and still send mail happily. Sealing a password
    // here is therefore not setup detail; it is what gives the missing key something to cost.
    await updateTenantMailSettings(tenant.tenantId, {
      host: HOST,
      port: MAILPIT_SMTP_PORT,
      from: `noreply@${HOST}`,
      username: 'veyq',
      password: `smtp-${stamp}`,
      tls: false,
      enabled: true,
    })

    const before = await getTenantMailSettings(tenant.tenantId)
    expect(before.configSecretState, 'the pre-capture control — no adopt, no possible MISMATCH').toBe('OK')

    // A Memory with a sealed name, so part B can prove the other half of A2: content is unaffected by a lost
    // KEK, because the key seals operator config and never content.
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    const name = `E2E leg three ${Date.now()}`
    const { nodeId } = await createMemory(scopeId, epochsOf(scopeKey), name)

    writeHandoff({
      email: owner.email,
      password: owner.password,
      secretKey: owner.built.emergencyKit.secretKey,
      tenantId: tenant.tenantId,
      scopeId,
      nodeId,
    })

    recordCanaries((await listTree(scopeId, epochsOf(scopeKey))).map((n) => n.name))
  })
})
