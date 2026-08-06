import { describe, it, expect } from 'vitest'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { ScopeKind } from '../../api/generated/scopes'
import { createMemory, listTree } from './tree'
import { setMemoryDate, unsealMemoryDate } from './memoryDate'
import { setMemoryLocation, unsealMemoryLocation } from './memoryLocation'
import { setMemoryCover, unsealMemoryCover, type MemoryCover } from './memoryCover'
import { unsealNodeMeta } from './nodeMeta'

/**
 * Opt-in live smoke for the Memory cover (cr-0072) — the one path the API-mocked unit tests cannot cover: the
 * sealed `cover` sub-object round-tripping through the **real** operator-blind backend (`updateNode` → `listNodes`)
 * beside the sibling date + location, read back from the SERVER-returned `wrappedMeta`. Gated on `VEYQ_E2E=1`;
 * default `npm run check` skips it. The instance is cold-birthed over the real API by `src/test/e2eBootstrap.ts`,
 * so this needs a FRESH instance and no seeder.
 *
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d
 *   VEYQ_E2E=1 npx vitest run memoryCover.e2e
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

    // Read back the node's SERVER-persisted wrappedMeta (operator-blind — decrypted here under K_mem).
    const readBack = async () => {
      const node = (await listTree(scopeId, epochsOf(scopeKey))).find((n) => n.id === nodeId)
      expect(node).toBeDefined()
      return node!
    }

    // Build up the shared blob one sub-object at a time (each is a real updateNode; each chains the prior blob
    // as currentMeta, exactly as the UI's read-modify-write does).
    const date = { mode: 'range' as const, from: '2026-05-01', to: '2026-05-09' }
    const location = { name: 'Teneriffa', nameSource: 'manual' as const, derivedMode: 'fixed' as const }
    const cover: MemoryCover = { source: { kind: 'media', mediaId: 'smoke-media-id' }, crop: { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 } }

    let meta = await setMemoryDate(scopeId, nodeId, memoryKey, date, null)
    meta = await setMemoryLocation(scopeId, nodeId, memoryKey, location, meta)
    await setMemoryCover(scopeId, nodeId, memoryKey, cover, meta)

    // All three sub-objects come back on the server-stored blob — the cover never clobbered the date/location.
    const withCover = await readBack()

    // The wire assertion's derivation (ADR 0053). The whole ENVELOPE, not the three asserted fields: one
    // `unsealNodeMeta` covers date + location + cover, including any leaf this suite never looks at — that is the
    // point of recording the envelope rather than the asserted value.
    //
    // ONE named blind spot (ADR 0053 decision 5): the cover's `source` is dropped before recording. It is a
    // reference, not sealed user content — `{ kind: 'media', mediaId }` points at a media row whose id lives in
    // the DB legitimately, and an `{ kind: 'upload', ref }` source carries `ref.wrappedBlobKey`, base64
    // ciphertext that IS present in `veyq_blob` and would be a false hit every run. Its `kind` discriminant is a
    // closed-vocabulary leaf (`'media' | 'upload'`) with no confidentiality value, and `'media'` additionally
    // collides verbatim with the Flyway `media` migration description — the one row a pristine data-only dump
    // carries. Dropped here, at the recording site, with the reason written down — never as a silent filter in
    // the recorder. `cover.crop` is all numbers and drops by type, so the cover contributes nothing after this.
    const envelope = unsealNodeMeta(memoryKey, withCover.wrappedMeta)
    if (envelope.cover && typeof envelope.cover === 'object') delete (envelope.cover as { source?: unknown }).source
    recordCanaries(envelope, (await listTree(scopeId, epochsOf(scopeKey))).map((n) => n.name))

    expect(unsealMemoryDate(memoryKey, withCover)).toEqual(date)
    expect(unsealMemoryLocation(memoryKey, withCover)).toMatchObject({ name: 'Teneriffa', derivedMode: 'fixed' })
    expect(unsealMemoryCover(memoryKey, withCover)).toEqual(cover)

    // Remove the cover (read-modify-write against the live blob) — only the cover drops; date + location remain.
    await setMemoryCover(scopeId, nodeId, memoryKey, null, withCover.wrappedMeta)
    const withoutCover = await readBack()
    expect(unsealMemoryCover(memoryKey, withoutCover)).toBeNull()
    expect(unsealMemoryDate(memoryKey, withoutCover)).toEqual(date)
    expect(unsealMemoryLocation(memoryKey, withoutCover)).toMatchObject({ name: 'Teneriffa' })
  })
})
