import { describe, it, expect } from 'vitest'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { ScopeKind } from '../../api/generated/scopes'
import { listTree } from '../../core/node/tree'
import { createMemory } from './memoryNodes'
import { setMemoryDate, unsealMemoryDate } from './memoryDate'
import { setMemoryLocation, unsealMemoryLocation } from './memoryLocation'
import { unsealMemoryCover } from './memoryCover'
import { uploadCoverBlob, writeNodeCover } from '../../core/node/cover/coverWrite'
import { uploadedCover } from '../../core/node/cover/coverModel'
import { unsealNodeMeta } from '../../core/node/nodeMeta'

/**
 * Opt-in live smoke for the Memory cover (cr-0072) — the one path the API-mocked unit tests cannot cover: the
 * sealed `cover` sub-object round-tripping through the **real** operator-blind backend (`setNodeCover` → `listNodes`)
 * beside the sibling date + location, read back from the SERVER-returned `wrappedMeta`. Gated on `VEYQ_E2E=1`;
 * default `pnpm run check` skips it. The instance is cold-birthed over the real API by `src/test/e2eBootstrap.ts`,
 * so this needs a FRESH instance and no seeder.
 *
 *   docker compose -f infra/docker/docker-compose.yml --profile s3 up -d
 *   VEYQ_E2E=1 pnpm exec vitest run memoryCover.e2e
 *
 * Or `python3 scripts/run_web_e2e.py`, which is what CI runs.
 */
describe.skipIf(!process.env.VEYQ_E2E)('memory cover e2e (live, operator-blind round-trip)', () => {
  it('a sealed cover survives updateNode → listNodes beside the date + location, and remove drops only it', async () => {
    await birthInstance('E2E Cover')
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal).toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()

    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `Cover smoke ${Date.now()}`)

    // Read back the node's SERVER-persisted wrappedMeta (operator-blind — decrypted here under K_node).
    const readBack = async () => {
      const node = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === nodeId)
      expect(node).toBeDefined()
      return node!
    }

    // Build up the shared blob one sub-object at a time (each is a real updateNode; each chains the prior blob AND
    // the version it is at, exactly as the UI's read-modify-write does — ADR 0077).
    const date = { mode: 'range' as const, from: '2026-05-01', to: '2026-05-09' }
    // Not plain 'Teneriffa': nine base64-alphabet letters are a canary random ciphertext can contain (inbox #323)
    const location = { name: 'Teneriffa Süd', nameSource: 'manual' as const, derivedMode: 'fixed' as const }
    const crop = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }

    let meta = await setMemoryDate(scopeId, nodeId, memoryKey, date, { wrappedMeta: null, metaRevision: 0, nodeEpoch: 0 })
    meta = await setMemoryLocation(scopeId, nodeId, memoryKey, location, { ...meta, nodeEpoch: 0 })
    // A cover is set only by an upload: its blob sealed as it is (no decode here) and attached in the meta's own write.
    const up = await uploadCoverBlob(scopeId, nodeId, memoryKey, new Blob([new Uint8Array(512).fill(9)]), { type: 'image/jpeg' })
    await writeNodeCover({ scopeId, nodeId, nodeKey: memoryKey, current: { ...meta, nodeEpoch: 0 }, cover: uploadedCover(up.plan, crop), uploadId: up.uploadId })

    // All three sub-objects come back on the server-stored blob — the cover never clobbered the date/location.
    const withCover = await readBack()

    // The wire assertion's derivation (ADR 0053). The whole ENVELOPE, not the three asserted fields: one
    // `unsealNodeMeta` covers date + location + cover, including any leaf this suite never looks at — that is the
    // point of recording the envelope rather than the asserted value.
    //
    // ONE named blind spot (ADR 0053 § Consequences): the cover's `source` is dropped before recording. It is a
    // reference, not sealed user content — an `{ kind: 'upload', ref }` source carries a blob id that lives in the DB
    // legitimately and `ref.wrappedBlobKey`, base64 ciphertext that IS present in `veyq_blob` and would be a false hit
    // every run. Its `kind` discriminant is a closed-vocabulary leaf with no confidentiality value. Dropped here, at the
    // recording site, with the reason written down — never as a silent filter in the recorder. `cover.crop` is all
    // numbers and drops by type, so the cover contributes nothing after this.
    const envelope = unsealNodeMeta(memoryKey, { scopeId, nodeId }, withCover.wrappedMeta)
    if (envelope.cover && typeof envelope.cover === 'object') delete (envelope.cover as { source?: unknown }).source
    recordCanaries(envelope, (await listTree(scopeId, epochsOf(scopeKey))).map((n) => n.name))

    expect(unsealMemoryDate(scopeId, memoryKey, withCover)).toEqual(date)
    expect(unsealMemoryLocation(scopeId, memoryKey, withCover)).toMatchObject({ name: 'Teneriffa Süd', derivedMode: 'fixed' })
    const read = unsealMemoryCover(scopeId, memoryKey, withCover)
    expect(read?.source.ref.blobId).toBe(up.plan.blobId)
    expect(read?.crop).toEqual(crop)

    // Remove the cover (read-modify-write against the live blob) — only the cover drops; date + location remain.
    // The live blob is at version 3 (three meta writes), and the server says so on the listing.
    expect(withCover.metaRevision).toBe(3)
    await writeNodeCover({ scopeId, nodeId, nodeKey: memoryKey, current: { wrappedMeta: withCover.wrappedMeta, metaRevision: withCover.metaRevision, nodeEpoch: withCover.epoch }, cover: null })
    const withoutCover = await readBack()
    expect(unsealMemoryCover(scopeId, memoryKey, withoutCover)).toBeNull()
    expect(unsealMemoryDate(scopeId, memoryKey, withoutCover)).toEqual(date)
    expect(unsealMemoryLocation(scopeId, memoryKey, withoutCover)).toMatchObject({ name: 'Teneriffa Süd' })
  })
})
