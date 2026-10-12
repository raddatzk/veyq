// uc.memory.cover (web) — the Memory-cover DISPLAY slice of MemoryDetail (cr-0146, audit #77/P4a). A deliberately chosen image +
// a 16:9 crop, sealed in the SAME node `wrappedMeta` blob as the date/location. The source is one of the Memory's own media
// (reusing its already-decrypted thumbnail) OR a brand-new uploaded image (a standalone cover blob decrypted on demand,
// cr-0073). This owns the reactive reading: the stored cover, its decrypted src (incl. the upload object-URL lifecycle) and
// which photo is the cover.
//
// **The editing is core's** (inbox #411): the dialog, the crop, the upload and the write are `core/node/cover/`; the header action
// and the tile open it through the host (`MemoryDetailHost`), and the gallery's "Set as cover" hands it the picked photo as
// the Memory's extra source (`coverSourceOf`, below).
import { computed, watch } from 'vue'
import { unsealMemoryCover } from './memoryCover'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import type { TreeNode } from '../../core/node/tree'
import type { MediaLoaders } from './source'
import type { Ref } from 'vue'

/** The reactive context MemoryDetail hands the cover slice — getters so props stay reactive, plus the live gallery
 *  `thumbs` map (a media-source cover reuses an already-decrypted thumbnail). */
export interface MemoryCoverContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  /** How the node's uploaded cover blob opens (the source's loader: the member's presign or the link's). */
  loaders: MediaLoaders
  thumbs: Ref<Map<string, string>>
}

export function useMemoryCover(ctx: MemoryCoverContext) {
  const storedCover = computed(() => (ctx.memoryKey() ? unsealMemoryCover(ctx.scopeId(), ctx.memoryKey()!, ctx.node()) : null))
  // An **upload**-source cover isn't in `thumbs` (no media row), so its standalone blob is decrypted on demand into
  // this object URL (revoked on change/unmount by the primitive). A **media**-source cover reuses the thumbnail.
  const uploadCover = useObjectUrl()
  watch(
    storedCover,
    async (cover) => {
      uploadCover.set(null)
      const key = ctx.memoryKey()
      if (!key || cover?.source.kind !== 'upload') return
      try {
        const bytes = await ctx.loaders.coverBlob(cover.source.ref, key)
        uploadCover.set(new Blob([bytes as BlobPart]))
      } catch {
        // best-effort: a locked/absent cover blob ⇒ the placeholder tile
      }
    },
    { immediate: true },
  )
  // The decrypted image of the current cover: the media thumbnail (already in `thumbs`) or the uploaded cover blob.
  const coverSrc = computed(() => {
    const cover = storedCover.value
    if (!cover) return null
    return cover.source.kind === 'upload' ? uploadCover.url.value : ctx.thumbs.value.get(cover.source.mediaId) ?? null
  })
  // The current cover's media id (when the cover is a Memory photo) — the lightbox marks that photo as the cover.
  const coverMediaId = computed(() => (storedCover.value?.source.kind === 'media' ? storedCover.value.source.mediaId : null))

  /**
   * "Set as cover" from the lightbox: the picked photo's already-decrypted thumbnail — the exact tier the cover renders from —
   * is the crop source, handed to core's dialog by the host so the user frames the 16:9 crop right away. `src` is null for a
   * photo with no thumbnail yet (non-derivable / still loading): the host then sets it full-frame in one click.
   */
  function coverSourceOf(mediaId: string): { mediaId: string; src: string | null } {
    return { mediaId, src: ctx.thumbs.value.get(mediaId) ?? null }
  }

  return { storedCover, coverSrc, coverMediaId, coverSourceOf }
}
