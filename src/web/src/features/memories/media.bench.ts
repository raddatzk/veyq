// The gallery's client crypto at volume (cr-volume-measurement-local): per photo, what opening a 10,000-photo
// Chapter costs on the device before any pixel is decoded — the listed tier (`unsealMeta`), the thumbnail's `K_blob`
// (`unwrapBlobKey`) and its one frame (`openFrame`, a thumbnail-sized frame). The single-operation benches give the
// per-operation time; the 10,000 ones the total. `pnpm exec vitest bench src/features/memories/media.bench.ts`; `vitest run`
// does not collect bench files, so `pnpm run check` is unchanged. TEMPORARY: `cr-volume-measurement-cax` deletes it.
import { expect, test } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { planBlob, unwrapBlobKey } from '../../core/blob/transport'
import type { BlobRef } from '../../core/blob/types'
import { unsealMeta, type MediaItem } from './media'

const SCOPE = '00000000-0000-4000-8000-000000000002'
const N = 10_000
const THUMBNAIL_BYTES = 40_000 // the harness's thumbnail fixture: 512 px at quality 0.8, about 40 KB
const memoryKey = v1.randomBytes(32)
const thumbnail = v1.randomBytes(THUMBNAIL_BYTES)

type Photo = { item: MediaItem; ref: BlobRef; frame: Uint8Array }

function photo(i: number): Photo {
  const mediaId = crypto.randomUUID()
  // The listed tier as the upload seals it (`media.ts`: `sealJson` under the media-meta context bound to the media).
  const meta = { v: 1, w: 1024, h: 768, capturedAt: 1_546_300_800_000 + i * 60_000, capturedAtSource: 'exif' }
  const wrappedMeta = b64std(v1.sealField({ purpose: 'media-meta', scopeId: SCOPE, mediaId }, memoryKey, v1.utf8(JSON.stringify(meta))))
  const plan = planBlob(SCOPE, { ownerId: mediaId, role: 'THUMBNAIL' }, memoryKey, new Blob([thumbnail as BlobPart]))
  const ref: BlobRef = { blobId: plan.blobId, chunkCount: 1, byteSize: plan.byteSize, wrappedBlobKey: plan.wrappedBlobKey, ownerId: mediaId, role: 'THUMBNAIL' }
  const frame = v1.sealFrame(plan.blobKey, 0, 1, thumbnail, v1.randomBytes(12))
  const item = { mediaId, original: ref, derivatives: [], wrappedMeta } as unknown as MediaItem
  return { item, ref, frame }
}

const photos = Array.from({ length: N }, (_, i) => photo(i))
const one = photos[0]
expect(unsealMeta(SCOPE, memoryKey, one.item)?.w, 'the listed tier opens').toBe(1024)
expect(v1.openFrame(unwrapBlobKey(SCOPE, memoryKey, one.ref), 0, 1, one.frame).length, 'the frame opens').toBe(THUMBNAIL_BYTES)

const once = { iterations: 2000, warmupIterations: 100, time: 0 }
const total = { iterations: 3, warmupIterations: 1, time: 0 }

test('one photo', async ({ bench }) => {
  const key = unwrapBlobKey(SCOPE, memoryKey, one.ref)
  await bench.compare(
    bench('1 x unsealMeta', () => { unsealMeta(SCOPE, memoryKey, one.item) }),
    bench('1 x unwrapBlobKey', () => { unwrapBlobKey(SCOPE, memoryKey, one.ref) }),
    bench('1 x openFrame (40 KB)', () => { v1.openFrame(key, 0, 1, one.frame) }),
    once,
  )
})

test('10,000 photos', async ({ bench }) => {
  await bench.compare(
    bench('10,000 x unsealMeta', () => { for (const p of photos) unsealMeta(SCOPE, memoryKey, p.item) }),
    bench('10,000 x unwrapBlobKey', () => { for (const p of photos) unwrapBlobKey(SCOPE, memoryKey, p.ref) }),
    bench('10,000 x (unsealMeta + unwrapBlobKey + openFrame)', () => {
      for (const p of photos) {
        unsealMeta(SCOPE, memoryKey, p.item)
        v1.openFrame(unwrapBlobKey(SCOPE, memoryKey, p.ref), 0, 1, p.frame)
      }
    }),
    total,
  )
})
