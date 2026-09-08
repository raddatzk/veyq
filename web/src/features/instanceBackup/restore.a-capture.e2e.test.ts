import { describe, it, expect } from 'vitest'
import { b64std } from '../../shared/base64'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { testBytes, writeHandoff } from '../../test/restoreHandoff'
import { getTenantMailSettings } from '../admin/createTenant/tenants'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createMemory, listTree } from '../memory/tree'
import { addMedia, listMedia, unsealMeta } from '../memory/media'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Part A of the **restore rehearsal** (uc.admin.back-up-and-restore-instance, ADR 0056) — a GROUP, so it only
 * means anything under `scripts/run_web_e2e.py`. Between this file and `restore.b-reopen.e2e.test.ts` the
 * orchestrator stops the app, captures the instance's three legs with the documented tool, **destroys the
 * source** (database dropped, bucket purged, key file removed) and restores onto a structurally empty one.
 *
 * This half builds something worth losing: a real Memory with real media, uploaded through real presigned URLs.
 * It hands part B only what a person would still have after the disk died — an address, a password, and the
 * Emergency Kit's Secret Key.
 */
describe.skipIf(!process.env.VEYQ_E2E)('restore rehearsal — capture', () => {
  it('births an instance with media and hands the owner’s own credentials to part B', async () => {
    const { owner, tenant } = await birthInstance('E2E Restore')
    await ensurePersonalScope()

    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal).toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()

    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `E2E restore ${Date.now()}`)
    const data = testBytes()
    // No MIME ⇒ the original-only path. The byte transport is what has to survive the restore; a derivative
    // would only add a second copy of the same question.
    const { blobId } = await addMedia(scopeId, nodeId, memoryKey, { bytes: data, mediaType: '' })
    expect(blobId).toBeTruthy()

    // The POSITIVE CONTROL for leg 3, asserted here rather than only in the leg-3 group. ConfigSecretKeyGuard
    // answers MISMATCH only when a fingerprint is ALREADY stored; with none stored it ADOPTS the current key
    // and answers OK. Reading the state here is what causes that adopt — so without this line a later MISMATCH
    // could not occur at all, and a later OK would be proving nothing.
    const before = await getTenantMailSettings(tenant.tenantId)
    expect(before.configSecretState, 'the pre-capture control').toBe('OK')

    writeHandoff({
      email: owner.email,
      password: owner.password,
      secretKey: owner.built.emergencyKit.secretKey,
      tenantId: tenant.tenantId,
      scopeId,
      nodeId,
      bytesB64: b64std(data),
    })

    const tree = await listTree(scopeId, epochsOf(scopeKey))
    const items = await listMedia(scopeId, nodeId)
    recordCanaries(tree.map((n) => n.name), unsealMeta(memoryKey, items[0]))
  })
})
