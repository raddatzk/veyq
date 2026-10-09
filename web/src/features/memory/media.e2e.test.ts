import { describe, it, expect } from 'vitest'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { listTree } from '../../core/node/tree'
import { createMemory } from './memoryNodes'
import { addMedia, listMedia, openOriginal, originalBlobMeta, unsealMeta } from './media'
import { ScopeKind } from '../../api/generated/scopes'

/**
 * Opt-in web byte-transport e2e (cr-0028, ADR 0013) — the one path the mocked media.test.ts cannot cover:
 * real `fetch` PUT/GET against **real presigned URLs** (and the store's CORS) on a **live instance** with the
 * bundled Garage store (a byte-path store is a hard instance requirement, cr-0105). Gated on `VEYQ_E2E=1`;
 * default `npm run check` skips it.
 *
 *   docker compose -f infra/docker-compose.yml --profile s3 up -d   # backend + store behind Traefik
 *   VEYQ_E2E=1 npx vitest run media.e2e                             # against a FRESH instance
 *
 * Or `python3 scripts/run_web_e2e.py`, which brings the stack up and gives this file its own database — that is
 * what CI runs. The instance is cold-birthed over the real API by `src/test/e2eBootstrap.ts`: no credentials to
 * supply, no seeder to run first.
 *
 * Drives the real flow: cold-birth → personal scope → create a Memory → addMedia → listMedia → openOriginal,
 * asserting the bytes round-trip operator-blind end-to-end.
 */
describe.skipIf(!process.env.VEYQ_E2E)('media e2e (live, direct-to-store)', () => {
  it('round-trips media bytes through real presigned upload + download', async () => {
    await birthInstance('E2E Media')
    await ensurePersonalScope()

    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal).toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey).toBeTruthy()

    const { nodeId, memoryKey } = await createMemory(scopeId, epochsOf(scopeKey), `E2E media ${Date.now()}`)

    const data = new Uint8Array(300 * 1024)
    for (let i = 0; i < data.length; i++) data[i] = (i * 17 + 3) & 0xff

    // raw bytes of a type nothing derives ⇒ the original-only path (no derivable frame); the byte transport is what
    // this gates. A name and a distinctive type, so the original's blob meta (inbox #397) seals two canaries no other
    // row can hold by accident.
    const { blobId } = await addMedia(scopeId, nodeId, memoryKey, {
      blob: new Blob([data as BlobPart]), mediaType: 'application/x-veyq-e2e-canary', name: 'veyq-e2e-original-Ærø.bin',
    })
    expect(blobId).toBeTruthy()

    const items = await listMedia(scopeId, nodeId)
    expect(items.length).toBeGreaterThan(0)
    const opened = await openOriginal(scopeId, memoryKey, items[0])
    expect(Array.from(opened)).toEqual(Array.from(data))

    // The wire assertion's derivation (ADR 0053): what this flow sealed, recovered by unsealing what the SERVER
    // returned. The bytes above are not canaries — `openOriginal` returns a Uint8Array, dropped by type — so the
    // sealed values this suite actually contributes are the node NAME (opened under K_SC inside `listTree`), the
    // media's listed-metadata tier, and the original's blob meta — its name and type (inbox #397). `tree.map(n => n.name)` and not `tree` itself: a TreeNode still carries
    // `wrappedMeta`/`wrappedContentKey`, which ARE in the database as ciphertext.
    const tree = await listTree(scopeId, epochsOf(scopeKey))
    const blobMeta = originalBlobMeta(scopeId, memoryKey, items[0])
    expect(blobMeta).toMatchObject({ type: 'application/x-veyq-e2e-canary', name: 'veyq-e2e-original-Ærø.bin' })
    recordCanaries(tree.map((n) => n.name), unsealMeta(scopeId, memoryKey, items[0]), blobMeta)
  })
})
