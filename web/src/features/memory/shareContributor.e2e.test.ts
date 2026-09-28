// cr-sharing-contributor-e2e live e2e (VEYQ_E2E=1): the FIRST live coverage of any sharing flow — the
// CONTRIBUTOR share, end-to-end against a REAL backend + MariaDB + the bundled Garage store, with REAL crypto.
//
// Two actors on one instance, serialized (the `scopeRotation.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`), mints the share, reads it back.
//   anonymous  = a contributor with NO session — every call goes through `publicSharesApi()`, which carries no
//                bearer token by construction (`api/client.ts`), so the anonymous path stays anonymous even
//                while A's `session.accessToken` is still set process-wide.
//
// Why the contributor role and not the plain viewer: it is the share role with the most machinery an e2e is the
// only layer that can reach. A session-less actor obtains a capability token from the server, then uploads
// through a *second* byte transport (`startShareBlobUploads` → real presigned PUTs direct-to-store →
// `completeShareMedia`) that the authenticated path never exercises. It subsumes the viewer flow, because a
// contributor share resolves through the identical `resolveShare` / `resolveShareBlob` pair (ADR 0029/0030).
//
// The cross-actor unseal deliberately does NOT go through the contract's `PublicShare.identities`, which
// `resolveShare` drops and no web code reads. The live route is that the server *copies* the identity's
// `sealedDisplayName` onto the contributor's primary bucket (`MediaStore.ensurePrimaryBucket`), which the curator
// opens with `openBucketName(kMem, …, 'contributor')` — a stronger assertion, because it crosses that copy step.
//
// The last two tests are the trash × sharing cross-feature proof at the altitude the original defect occurred at
// (uc.memory.delete-media × uc.sharing.share-public-viewer): the backend already pins it in
// `ShareTrashedMediaSliceTest`, but nothing had ever proven it through the real web client against a live stack —
// which is where the two features actually met, since the trash CR and the sharing CRs share only `veyq_media`.
// **Each negative carries a live control** (a second contributed medium that is NOT trashed), because a share
// that 404s wholesale — revoked, wrong id, a stack that never came up — satisfies a lone negative perfectly.
//
//   python3 scripts/run_web_e2e.py web/src/features/memory/shareContributor.e2e.test.ts
//
// is the only supported way to run it: the orchestrator gives this file its own database and sweeps that database
// for the plaintext recorded below. A hand run (`VEYQ_E2E=1 npx vitest run shareContributor.e2e` against a FRESH
// instance) works too, minus the sweep.
import { describe, it, expect } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { HttpStatus, isHttpStatus } from '../../shared/httpError'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createMemory } from './tree'
import { deleteMedia, listMedia, unsealMeta } from './media'
import { listBuckets, openBucketName } from './mediaBuckets'
import {
  addSharePhoto,
  createContributorIdentity,
  createShare,
  listSharesFor,
  openShareOriginal,
  readShareName,
  resolveShare,
  sessionSealKey,
  type ShareItem,
} from './share'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// The sealed plaintext this suite hands the wire-assertion sweep. Each string is deliberately DISTINCTIVE and
// distinct from every declared-plaintext value on the instance — the tenant name below is operator-visible by
// design, so a canary that merely repeated it would make the sweep fire on a value nobody claims is secret
// (ADR 0053: resolve a noisy canary in the test data, never in an allowlist).
const TENANT_NAME = 'E2E Contributor Share' // declared plaintext (veyq_tenant.name) — NOT a canary
const MEMORY_NAME = 'Ferienalbum Wolkenstein'
const NOTE = { de: 'Bitte ladet eure Bilder vom Wochenende hoch.', en: 'Please add your pictures from the weekend.' }
const SHARE_NAME = 'Interner Link fuer die Familie'
const CONTRIBUTOR_NAME = 'Grossmutter Hedwig'

/** Deterministic, distinguishable bytes — two different fills so a mixed-up read-back cannot pass. */
function bytes(fill: number, size = 300 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 17 + fill) & 0xff
  return out
}

describe.skipIf(!process.env.VEYQ_E2E)('contributor share e2e (live, real crypto, real presigned transport)', () => {
  // State handed from the contributor flow to the two trash assertions. The `ShareItem`s are captured BEFORE the
  // delete on purpose: afterwards the anonymous resolve no longer returns the trashed one, so there would be no
  // `blobId` left to attempt a read with — the ordering is forced by the very behaviour under test.
  let scopeId: string
  let nodeId: string
  let shareId: string
  let fragmentKey: string
  let contributed: ShareItem // trashed by the first trash test
  let control: ShareItem // never trashed — the live control both negatives need
  let controlBytes: Uint8Array

  it('an anonymous contributor names themselves, uploads through the share transport, and the curator recovers the name', async () => {
    // --- A: cold-birth the instance, personal scope, one Memory, one CONTRIBUTOR share -----------------------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    expect(scopeKey, 'the personal scope opens').toBeTruthy()

    const memory = await createMemory(scopeId, epochsOf(scopeKey), MEMORY_NAME)
    nodeId = memory.nodeId
    const kMem = memory.memoryKey

    const minted = await createShare(scopeId, nodeId, kMem, MEMORY_NAME, ShareRole.Contributor, null, NOTE, SHARE_NAME)
    shareId = minted.shareId
    expect(shareId, 'the client minted an opaque shareId (ADR 0072)').toMatch(/^[A-Za-z0-9_-]{43}$/)

    // --- anonymous: resolve with nothing but the id and the FRAGMENT key ------------------------------------
    // `minted.kShare` is what a real visitor carries in `/s/{shareId}#k=…`; the server never saw it.
    fragmentKey = b64url(minted.kShare)
    const resolved = await resolveShare(shareId, fragmentKey)
    expect(resolved.role, 'the share resolves as CONTRIBUTOR').toBe(ShareRole.Contributor)
    expect(resolved.memoryName, 'the anonymous visitor recovered the Memory name under K_share').toBe(MEMORY_NAME)
    expect(resolved.noteByLocale, 'the per-locale curator note opened with the same fragment key').toEqual(NOTE)
    expect(resolved.items, 'a fresh share carries no media yet').toHaveLength(0)
    expect(resolved.scopeId, 'the unlocked share names the scope its seals are bound to').toBe(scopeId)

    // --- anonymous: name yourself, then upload TWO media through the share-scoped transport ------------------
    const session = await createContributorIdentity(shareId, resolved.scopeId, resolved.access, CONTRIBUTOR_NAME)
    expect(session.contributorToken, 'the server minted a capability token for this session').toBeTruthy()
    const sealKey = sessionSealKey(session, resolved.access)
    expect(sealKey, 'a contributor seals under the K_mem its link holds').not.toBeNull()

    const upload = { ...session, shareId, scopeId: resolved.scopeId, sealKey: sealKey! }
    // raw bytes with no MIME ⇒ the original-only path (nothing derivable) — the byte transport is what this gates
    const first = await addSharePhoto(upload, { blob: new Blob([bytes(3) as BlobPart]), mediaType: '' })
    const second = await addSharePhoto(upload, { blob: new Blob([bytes(101) as BlobPart]), mediaType: '' })
    expect(first.mediaId, 'the first contribution attached').toBeTruthy()
    expect(second.mediaId, 'the second contribution attached').toBeTruthy()

    // --- anonymous: read the contributions back through the GATED presign ------------------------------------
    const after = await resolveShare(shareId, fragmentKey)
    expect(after.items, 'both contributions surface in the anonymous resolve').toHaveLength(2)
    contributed = after.items.find((i) => i.item.mediaId === first.mediaId)!
    control = after.items.find((i) => i.item.mediaId === second.mediaId)!
    expect(contributed, 'the first contribution is resolvable').toBeDefined()
    expect(control, 'the second contribution is resolvable').toBeDefined()

    controlBytes = bytes(101)
    expect(Array.from(await openShareOriginal(shareId, contributed)), 'the first blob round-trips through the gated presign')
      .toEqual(Array.from(bytes(3)))
    expect(Array.from(await openShareOriginal(shareId, control)), 'the second blob round-trips through the gated presign')
      .toEqual(Array.from(controlBytes))

    // --- back to A: the contributions land in the curator's own Memory, attributed --------------------------
    const curatorItems = await listMedia(scopeId, nodeId)
    expect(curatorItems.map((i) => i.mediaId).sort(), "both contributions appear in the curator's own listing")
      .toEqual([first.mediaId, second.mediaId].sort())

    // The cross-actor unseal: the server copied the identity's sealed display name onto the contributor's primary
    // bucket, and the curator opens it under K_mem. This is the assertion the whole role exists for — a name the
    // operator relayed as ciphertext, sealed by an anonymous stranger, recovered by someone who never met them.
    const buckets = await listBuckets(scopeId, nodeId)
    const primary = buckets.find((b) => b.isPrimary && b.sealedName)
    expect(primary, "the contributor's primary bucket carries a sealed name").toBeDefined()
    const recoveredName = openBucketName(scopeId, nodeId, kMem, primary!)
    expect(recoveredName, 'the curator recovered the contributor display name through the bucket').toBe(CONTRIBUTOR_NAME)
    expect(primary!.mediaCount, 'both contributions are attributed to that one contributor').toBe(2)

    // The curator's own management read: recover K_share from its K_mem-sealed copy and open the private name.
    const managed = (await listSharesFor(nodeId)).find((s) => s.shareId === shareId)
    expect(managed, 'the share shows up in the curator manage panel').toBeDefined()
    const recoveredShareName = readShareName(kMem, managed!, managed!.sealedShareKey, managed!.sealedShareName)
    expect(recoveredShareName, "the curator recovered the share's private name").toBe(SHARE_NAME)

    // The wire assertion's derivation (ADR 0053) — every value below was recovered by UNSEALING what the server
    // returned, so nothing has to be remembered and none can be forgotten out of the set. Note what is NOT here:
    // `session` (a server-minted capability token), `minted` (an opaque id), the raw `PublicShare` — and
    // `resolved` itself, which looks like the ideal argument and is not: it carries `role`, and 'CONTRIBUTOR' is
    // stored as PLAINTEXT in `veyq_share` because the server enforces the grant. Project the fields, never hand
    // over the envelope.
    //
    // MEASURED: this records exactly 5 canaries — the Memory name, the note's two locales, the contributor name,
    // the share name. `unsealMeta` contributes ZERO here and is passed anyway: an original-only upload with
    // `mediaType: ''` seals a metadata tier of numbers and the empty string, all dropped by type in `canaries.ts`.
    // It stays because the recorder is derivation-shaped — the day that tier grows a string field, this sweeps it
    // with no edit here. Don't mistake it for load-bearing in this suite's current shape.
    recordCanaries(
      resolved.memoryName,
      resolved.noteByLocale,
      recoveredName,
      recoveredShareName,
      unsealMeta(scopeId, kMem, curatorItems[0]),
    )
  }, 240_000)

  // --- trash × sharing (uc.memory.delete-media × uc.sharing.share-public-viewer) --------------------------
  // Two tests, not two assertions in one, because they are anchored on two INDEPENDENT server-side predicates —
  // `SharingStore.subtree` (the listing) and `SharingStore.subtreeBlobIds` (the blob authorization), each with its
  // own `deletedAt is null`. Split this way, removing one predicate reddens exactly one test, which is what makes
  // the CR's bite protocol legible: "the guard bites" and "the run fell over" stop looking alike.

  it('a trashed medium leaves the anonymous resolve — the live one stays', async () => {
    // The curator trashes ONE contribution through the real delete path (soft-delete → the per-Memory trash).
    await deleteMedia(scopeId, contributed.item.mediaId)

    const resolved = await resolveShare(shareId, fragmentKey)
    const ids = resolved.items.map((i) => i.item.mediaId)
    expect(ids, 'the trashed medium is gone from the anonymous resolve').not.toContain(contributed.item.mediaId)
    // The live control: without it, a share that 404s wholesale would satisfy the negative above perfectly.
    expect(ids, 'the surviving contribution is still listed — the filter must not over-reach').toContain(control.item.mediaId)
  }, 120_000)

  it("a trashed medium's blob is no longer authorized through the share — the live one still opens", async () => {
    // Anchored on the STATUS CODE, not on "it threw": a bare rejection is equally produced by a network error, a
    // boot failure, or a crypto failure inside `openBlobFromUrls` — three ways to be green for the wrong reason.
    const err = await openShareOriginal(shareId, contributed).then(() => null, (e: unknown) => e)
    expect(err, "the trashed medium's blob read rejects").not.toBeNull()
    expect(
      isHttpStatus(err, HttpStatus.notFound),
      "the trashed medium's blob is a non-enumerable 404 through the share",
    ).toBe(true)

    // The live control on the same predicate: the surviving contribution's bytes still come back intact.
    expect(
      Array.from(await openShareOriginal(shareId, control)),
      'the surviving contribution still opens through the gated presign',
    ).toEqual(Array.from(controlBytes))
  }, 120_000)
})
