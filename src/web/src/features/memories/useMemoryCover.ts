// uc.memory.cover (web) — the Memory-cover DISPLAY slice of MemoryDetail (cr-0146, audit #77/P4a). An uploaded image + a
// 16:9 crop, sealed in the SAME node `wrappedMeta` blob as the date/location; its standalone cover blob is decrypted on
// demand. This owns the reactive reading: the stored cover and its decrypted src (incl. the object-URL lifecycle).
//
// **The editing is core's** (inbox #411): the dialog, the crop, the upload and the write are `core/node/cover/`; the header
// action and the tile open it through the host (`MemoryDetailHost`). A cover is set only by an upload there.
import { computed, watch } from 'vue'
import { unsealMemoryCover } from './memoryCover'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import type { TreeNode } from '../../core/node/tree'
import type { MediaLoaders } from './source'

/** The reactive context MemoryDetail hands the cover slice — getters so props stay reactive. */
export interface MemoryCoverContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  /** How the node's uploaded cover blob opens (the source's loader: the member's presign or the link's). */
  loaders: MediaLoaders
}

export function useMemoryCover(ctx: MemoryCoverContext) {
  const storedCover = computed(() => (ctx.memoryKey() ? unsealMemoryCover(ctx.scopeId(), ctx.memoryKey()!, ctx.node()) : null))
  // The cover's blob is decrypted on demand into this object URL (revoked on change/unmount by the primitive).
  const uploadCover = useObjectUrl()
  watch(
    storedCover,
    async (cover) => {
      uploadCover.set(null)
      const key = ctx.memoryKey()
      if (!key || !cover) return
      try {
        const bytes = await ctx.loaders.coverBlob(cover.source.ref, key)
        uploadCover.set(new Blob([bytes as BlobPart]))
      } catch {
        // best-effort: a locked/absent cover blob ⇒ the placeholder tile
      }
    },
    { immediate: true },
  )
  /** The decrypted image of the current cover, or null (none, or not loaded). */
  const coverSrc = computed(() => (storedCover.value ? uploadCover.url.value : null))

  return { storedCover, coverSrc }
}
