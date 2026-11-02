// Memories' part of a node's re-key (ADR 0109 point 2; core's driver `core/node/rekey/rekeyScope.ts` calls it through the
// kind's `rekeyContent`): what Memories stores sealed under a Memory's or a chapter's key moves to the node's new key,
// and nothing else changes — every plaintext is re-sealed byte for byte, every `K_blob` kept with only its wrap moved.
//   - in the node's meta: the banner's blob and poster wraps (owner = the node, roles BANNER / BANNER_POSTER);
//   - per medium, live and trashed: its meta and EXIF (`media-meta` / `media-exif`, bound to scope + medium);
//   - each bucket name under the node's key (`bucket-name`, bound to scope + node + bucket).
// A medium's original and derivatives are blobs of core's blob service (ADR 0113): core's driver re-wraps them with the
// node's other service blobs, each bound to its medium as the server lists it (`listApplicationBlobRekey`).
// Not here: a WRITE_ONLY guest's media and primary (under its own `K_up`, which the re-key keeps — the server does not
// list them), and a READ_WRITE contributor's primary (the server copies its name again from the identity, which core's
// part of the link root's commit re-sealed). Staged under the commit's re-key id at the contracts' caps.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { withRefresh } from '../../app/sessionRefresh'
import type { RekeyNodeContext } from '../../core/node/rekey/hooks'
import { STAGE_BATCH, rewrapBlobKey } from '../../core/node/rekey/rekeyScope'
import { mediaApi } from './api'
import type { RekeyMediumSeal, RekeyBucketSeal } from '../../api/generated/media'
import { BANNER_POSTER_ROLE, BANNER_ROLE } from './banner/bannerModel'
import { mediaMetaContext } from './mediaOpen'

const mediaExifContext = (scopeId: string, mediaId: string) => ({ purpose: 'media-exif', scopeId, mediaId }) as const
const bucketNameContext = (scopeId: string, nodeId: string, bucketId: string) =>
  ({ purpose: 'bucket-name', scopeId, nodeId, bucketId }) as const
/** The page size the listing is asked for: the staging cap, so one page is one batch. */
const PAGE = STAGE_BATCH

/** A field re-sealed byte for byte under the new key, bound as before. */
function reseal(context: v1.FieldContext, oldKey: Uint8Array, newKey: Uint8Array, sealed: string): string {
  const plain = v1.openField(context, oldKey, fromB64std(sealed))
  try {
    return b64std(v1.sealField(context, newKey, plain))
  } finally {
    plain.fill(0)
  }
}

/** The kind's `rekeyContent` for MEMORY and CHAPTER (see the header). Fails closed: a field that does not open throws. */
export async function rekeyMemoryContent(ctx: RekeyNodeContext): Promise<void> {
  const { scopeId, node, oldKey, newKey } = ctx
  rewrapBanner(scopeId, node.id, oldKey, newKey, ctx.meta)
  let since: string | undefined
  let bucketsStaged = false
  for (;;) {
    ctx.signal?.throwIfAborted()
    const page = await withRefresh(() => mediaApi().listMediaRekey({ scopeId, nodeId: node.id, since, limit: PAGE }))
    const media: RekeyMediumSeal[] = page.media.map((m) => ({
      mediaId: m.mediaId,
      metaRevision: m.metaRevision,
      wrappedMeta: m.wrappedMeta ? reseal(mediaMetaContext(scopeId, m.mediaId), oldKey, newKey, m.wrappedMeta) : null,
      wrappedExif: m.wrappedExif ? reseal(mediaExifContext(scopeId, m.mediaId), oldKey, newKey, m.wrappedExif) : null,
    }))
    // The buckets ride on every page; staged once (a later page's are the same rows).
    const buckets: RekeyBucketSeal[] = bucketsStaged ? [] : page.buckets
      .filter((b) => !b.shareId) // a contributor's primary: the server copies its name again
      .map((b) => ({ bucketId: b.bucketId, sealedName: reseal(bucketNameContext(scopeId, node.id, b.bucketId), oldKey, newKey, b.sealedName) }))
    for (let i = 0; i < Math.max(Math.ceil(buckets.length / STAGE_BATCH), 1); i++) {
      const slice = buckets.slice(i * STAGE_BATCH, (i + 1) * STAGE_BATCH)
      const items = i === 0 ? media : []
      if (!slice.length && !items.length) continue
      await withRefresh(() => mediaApi().stageMediaRekey({
        scopeId, nodeId: node.id, rekeyId: ctx.rekeyId, mediaRekeyStage: { fromEpoch: node.epoch, media: items, buckets: slice },
      }))
    }
    bucketsStaged = true
    if (!page.more) return
    since = page.cursor ?? undefined
  }
}

/** The banner's refs inside the node's meta (`banner.blob`, `banner.poster`): their wraps moved in place. A shape this
 *  client does not know is left as it is; a ref whose wrap does not open throws (fail closed). */
function rewrapBanner(scopeId: string, nodeId: string, oldKey: Uint8Array, newKey: Uint8Array, meta: Record<string, unknown> | null): void {
  const banner = meta?.banner as { blob?: { blobId?: unknown; wrappedBlobKey?: unknown }; poster?: { blobId?: unknown; wrappedBlobKey?: unknown } } | undefined
  if (!banner || typeof banner !== 'object') return
  for (const [field, role] of [['blob', BANNER_ROLE], ['poster', BANNER_POSTER_ROLE]] as const) {
    const ref = banner[field]
    if (!ref || typeof ref.blobId !== 'string' || typeof ref.wrappedBlobKey !== 'string') continue
    ref.wrappedBlobKey = rewrapBlobKey(scopeId, oldKey, newKey, { blobId: ref.blobId, ownerId: nodeId, role, wrappedBlobKey: ref.wrappedBlobKey })
  }
}
