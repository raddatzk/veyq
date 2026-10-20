// What a report of a Memories medium hands over (en.moderation.hold § What a report hands over): the K_blob of
// every blob it has, each
// tagged with its blob id, and the K_field of its listed-metadata tier — keys that open exactly those seals. A wrap the
// server moved onto another medium does not open, so it is not handed over.
import { describe, it, expect, vi } from 'vitest'

vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))

import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { mediumReportKeys, type MediaItem } from './media'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f3'
const MEDIA = '5c09e000-0000-4000-8000-0000000000c0'
const ORIGINAL = '5c09e000-0000-4000-8000-0000000000c1'
const THUMB = '5c09e000-0000-4000-8000-0000000000c2'
const K_NODE = v1.randomBytes(32)
const K_ORIGINAL = v1.randomBytes(32)
const K_THUMB = v1.randomBytes(32)

const ref = (blobId: string, role: string, key: Uint8Array, ownerId = MEDIA) => ({
  blobId, chunkCount: 1, byteSize: 3, ownerId, role,
  wrappedBlobKey: b64std(v1.seal({ purpose: 'blob-key', scopeId: SCOPE, ownerId, blobId, role }, K_NODE, key)),
})
const wrappedMeta = b64std(v1.sealField({ purpose: 'media-meta', scopeId: SCOPE, mediaId: MEDIA }, K_NODE, v1.utf8('{"w":1}')))

describe('mediumReportKeys', () => {
  it('every blob\'s K_blob with its id, and the media-meta K_field', async () => {
    const item: MediaItem = {
      mediaId: MEDIA, original: ref(ORIGINAL, 'ORIGINAL', K_ORIGINAL), derivatives: [{ kind: 'THUMBNAIL', ref: ref(THUMB, 'THUMBNAIL', K_THUMB) }], wrappedMeta,
    }
    const keys = (await mediumReportKeys(SCOPE, K_NODE, item, false))!
    expect(keys.map((k) => [k.role, k.ref])).toEqual([['BLOB', ORIGINAL], ['BLOB', THUMB], ['MEDIA_META', null]])
    expect(keys[0].key).toEqual(K_ORIGINAL)
    expect(keys[1].key).toEqual(K_THUMB)
    const meta = v1.openFieldWith({ purpose: 'media-meta', scopeId: SCOPE, mediaId: MEDIA }, keys[2].key, fromB64std(wrappedMeta))
    expect(new TextDecoder().decode(meta)).toBe('{"w":1}')
  })

  it('a wrap bound to another medium is left out; no blob key at all is nothing to report', async () => {
    const moved: MediaItem = { mediaId: MEDIA, original: ref(ORIGINAL, 'ORIGINAL', K_ORIGINAL, '5c09e000-0000-4000-8000-0000000000d0'), derivatives: [] }
    const keys = await mediumReportKeys(SCOPE, K_NODE, { ...moved, original: { ...moved.original, ownerId: MEDIA } }, false)
    expect(keys).toBeNull()
  })
})
