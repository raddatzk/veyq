import { describe, it, expect } from 'vitest'
import { session } from '../../app/session'
import { setupApi } from '../../api/client'
import { BASE, stamp } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { readHandoff } from '../../test/restoreHandoff'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { getTenantMailSettings, tenantError } from '../admin/createTenant/tenants'
import { inviteMember } from '../spaces/members'
import { openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { listTree } from '../memory/tree'

/**
 * Part B of the **leg-3 rehearsal**: what a two-legged backup actually costs. The orchestrator restored legs
 * (a) and (b) and dropped the config-secret key, so the server generated a fresh one — which is why the verdict
 * is `MISMATCH` and not `ABSENT` (`ConfigSecretSeal` reads-or-generates; it never fails for want of a key file).
 *
 * Three assertions, and the third is the one that makes the first two matter (A2, ADR 0055 §2):
 *   1. the instance is up and its **content is unaffected** — the KEK seals operator config, never content;
 *   2. `configSecretState` is `MISMATCH` — the `veyq_instance` fingerprint tripwire doing its job;
 *   3. a member invite refuses with `503 mail_unavailable` — an instance that boots, serves every page, and
 *      cannot admit anybody.
 */
describe.skipIf(!process.env.VEYQ_E2E)('leg-3 rehearsal — a restore without the key file', () => {
  it('serves content, reports MISMATCH, and refuses a member invite with 503', async () => {
    const handoff = readHandoff()
    session.apiBase = BASE

    const setup = await setupApi().getSetupStatus()
    expect(setup.setupRequired, 'part B must run against the RESTORED instance, not a fresh one').toBe(false)

    await login(handoff.email, handoff.password)
    await unlockMasterKey(handoff.password, handoff.secretKey)

    // 1 — content is unaffected. The Memory's name is sealed under the scope's own key material, which travelled
    // in the database and has nothing to do with the operator's KEK.
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()
    const tree = await listTree(handoff.scopeId!, epochsOf(scopeKey))
    const memory = tree.find((n) => n.id === handoff.nodeId)
    expect(memory?.name, 'the Memory name still opens — the KEK never sealed content').toBeTruthy()

    // 2 — the tripwire fires. Only possible because part A caused the fingerprint to be adopted first.
    const mail = await getTenantMailSettings(handoff.tenantId)
    expect(mail.configSecretState, 'leg (c) is missing').toBe('MISMATCH')

    // 3 — and it costs the instance the two flows ADR 0054 and ADR 0055 exist to protect. Anchored on the
    // specific observable (503 + `mail_unavailable`), never on "it threw": a bare rejection is equally produced
    // by a network error, a boot failure or a crypto failure.
    let refused: { status: number; code: string | null } | null = null
    try {
      await inviteMember(handoff.tenantId, `nobody-${stamp}@e2e.test`)
    } catch (e) {
      refused = await tenantError(e)
    }
    expect(refused, 'the invite must be refused, not accepted').toBeTruthy()
    expect(refused!.status).toBe(503)
    expect(refused!.code).toBe('mail_unavailable')

    recordCanaries(tree.map((n) => n.name))
  })
})
