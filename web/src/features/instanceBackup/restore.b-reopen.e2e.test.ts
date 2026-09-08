import { describe, it, expect } from 'vitest'
import { fromB64std } from '../../shared/base64'
import { session } from '../../app/session'
import { setupApi } from '../../api/client'
import { BASE } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { readHandoff } from '../../test/restoreHandoff'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { getTenantMailSettings } from '../admin/createTenant/tenants'
import { openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { listTree, nodeContentKey } from '../memory/tree'
import { listMedia, openOriginal, unsealMeta } from '../memory/media'

/**
 * Part B of the **restore rehearsal** — the sentence `docs/architecture/README.md` §10 sets as the durability
 * bar, executed: *a backup taken by the documented procedure restores onto an empty instance, and afterwards
 * every Memory opens with the keys their owners already hold.*
 *
 * This runs against a **different** instance from part A's: a fresh database, a fresh bucket and a fresh key
 * path, loaded by `scripts/instance_backup.py restore`. Part A's instance no longer exists — the orchestrator
 * destroyed it — which is the only way "everything was restored" and "nothing was restored" can be told apart
 * from out here.
 *
 * **The sign-in is real.** `login` + `unlockMasterKey` is the app's own PASSWORD-floor path: it fetches the key
 * material from the RESTORED server, runs Argon2 over the password and the Emergency Kit's Secret Key, and
 * unwraps the Master Key. Nothing is injected. A helper that handed the vault a Master Key it already held
 * would satisfy every assertion below while skipping the only part the bar is actually about.
 *
 * **The oracle is the bytes, not a row count.** A restored database whose objects never arrived yields a
 * perfectly valid presigned URL that 404s, with no server-side error and a broken picture as the only symptom
 * (ADR 0056 § Context 2). Only comparing the fetched plaintext can tell those apart.
 */
describe.skipIf(!process.env.VEYQ_E2E)('restore rehearsal — reopen', () => {
  it('signs in on the restored instance and reads back the exact bytes', async () => {
    const handoff = readHandoff()
    session.apiBase = BASE

    // NOT a fresh instance. Every other suite asserts `setupRequired === true`; this one asserts the opposite,
    // because a restore that quietly produced an empty instance would otherwise look like a clean start.
    const setup = await setupApi().getSetupStatus()
    expect(setup.setupRequired, 'part B must run against the RESTORED instance, not a fresh one').toBe(false)

    await login(handoff.email, handoff.password)
    await unlockMasterKey(handoff.password, handoff.secretKey)

    // Leg 3 arrived: the KEK that came with the backup is the one whose fingerprint the restored rows carry.
    const mail = await getTenantMailSettings(handoff.tenantId)
    expect(mail.configSecretState, 'leg (c) restored').toBe('OK')

    const scopeKey = (await openPersonalScope())!
    expect(scopeKey, 'the personal scope opens with the restored grant').toBeTruthy()
    const epochs = epochsOf(scopeKey)

    const tree = await listTree(handoff.scopeId!, epochs)
    const memoryKey = nodeContentKey(epochs, tree, handoff.nodeId!)
    expect(memoryKey, 'the Memory content key re-derives from the restored tree').toBeTruthy()

    const items = await listMedia(handoff.scopeId!, handoff.nodeId!)
    expect(items.length).toBeGreaterThan(0)
    const opened = await openOriginal(handoff.scopeId!, memoryKey!, items[0])
    expect(Array.from(opened)).toEqual(Array.from(fromB64std(handoff.bytesB64!)))

    // The canary sweep now runs against the RESTORED database, which is the operator-blindness invariant
    // surviving the most privileged routine an operator has — checked rather than argued.
    recordCanaries(tree.map((n) => n.name), unsealMeta(memoryKey!, items[0]))
  })
})
