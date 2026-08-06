// uc.memory.cover (web) — the Memory-cover reactive slice of MemoryDetail, extracted into a composable
// (cr-0146, audit #77/P4a). A deliberately chosen image + a 16:9 crop, sealed in the SAME node `wrappedMeta` blob
// as the date/location (read-modify-write, operator-blind). The source is one of the Memory's own media (reusing
// its already-decrypted thumbnail) OR a brand-new uploaded image (a standalone cover blob decrypted on demand,
// cr-0073). This owns the reactive wiring: editor/lightbox-seed state, the decrypted cover src (incl. the upload
// object-URL lifecycle), and the read-modify-write save.
import { ref, computed, watch, type Ref } from 'vue'
import { unsealMemoryCover, setMemoryCover, FULL_FRAME, type MemoryCover, type CoverSaveIntent } from './memoryCover'
import { openBlob, uploadCoverBlob, deleteCoverBlob } from './media'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import type { TreeNode } from './tree'

/** The reactive context MemoryDetail hands the cover slice — getters so props stay reactive, plus the live gallery
 *  `thumbs` map (a media-source cover reuses an already-decrypted thumbnail) and the host's `metaUpdated` emit. */
export interface MemoryCoverContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  thumbs: Ref<Map<string, string>>
  onMetaUpdated: (payload: { nodeId: string; wrappedMeta: string }) => void
}

export function useMemoryCover(ctx: MemoryCoverContext) {
  const coverEditorOpen = ref(false)
  // When the cover editor is opened from the lightbox "Set as cover", it is seeded to crop THAT photo (its already-
  // decrypted thumbnail is the crop source); null = the header "edit current cover" flow. Cleared on close.
  const coverEditorMedia = ref<{ mediaId: string; src: string } | null>(null)
  const storedCover = computed(() => (ctx.memoryKey() ? unsealMemoryCover(ctx.memoryKey()!, ctx.node()) : null))
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
        const bytes = await openBlob(ctx.scopeId(), key, cover.source.ref)
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
  // The current cover, decrypted, to re-crop in the dialog (its cropper needs the source image — esp. for uploads).
  const coverExistingSrc = computed(() => coverSrc.value)
  // The current cover's media id (when the cover is a Memory photo) — the lightbox marks that photo as the cover.
  const coverMediaId = computed(() => (storedCover.value?.source.kind === 'media' ? storedCover.value.source.mediaId : null))

  /**
   * "Set as cover" from the lightbox: open the cover **cropper** seeded on that photo so the user frames the 16:9
   * crop right away. The crop source is the photo's already-decrypted thumbnail — the exact tier the cover renders
   * from — so no extra decrypt. Fallback: a photo with no thumbnail yet (non-derivable / still loading) reverts to
   * the one-click full-frame set.
   */
  function onSetCoverFromPhoto(payload: { mediaId: string }) {
    const src = ctx.thumbs.value.get(payload.mediaId)
    if (!src) {
      void onCoverSave({ kind: 'media', mediaId: payload.mediaId, crop: { ...FULL_FRAME } })
      return
    }
    coverEditorMedia.value = { mediaId: payload.mediaId, src }
    coverEditorOpen.value = true
  }

  /** Open the header "edit current cover" flow — not seeded to a specific photo (upload / re-crop the current cover). */
  function openCoverEditor() {
    coverEditorMedia.value = null
    coverEditorOpen.value = true
  }

  /** Close the cover editor and drop any lightbox seeding (so the next header open is the plain edit flow). */
  function closeCoverEditor() {
    coverEditorOpen.value = false
    coverEditorMedia.value = null
  }

  /**
   * Apply the cover dialog's save intent (cr-0072 media + cr-0073 upload): read-modify-write the node meta under
   * K_mem (the date + location are preserved). An **upload** uploads + commits a standalone cover blob (whose
   * commit atomically reclaims any prior cover blob); switching **away** from a prior upload cover reclaims its
   * now-orphaned blob via `deleteCoverBlob`. `keep` re-seals the current source with a moved crop only.
   */
  async function onCoverSave(intent: CoverSaveIntent) {
    const key = ctx.memoryKey()
    if (!key) return
    const nodeId = ctx.node().id
    const priorUpload = storedCover.value?.source.kind === 'upload'
    try {
      let cover: MemoryCover | null
      if (intent.kind === 'upload') {
        const ref = await uploadCoverBlob(ctx.scopeId(), nodeId, key, {
          bytes: new Uint8Array(await intent.file.arrayBuffer()),
          mediaType: intent.file.type,
          lastModified: intent.file.lastModified,
        })
        cover = { source: { kind: 'upload', ref }, crop: intent.crop }
      } else if (intent.kind === 'media') {
        if (priorUpload) await deleteCoverBlob(ctx.scopeId(), nodeId) // reclaim the now-orphaned upload blob
        cover = { source: { kind: 'media', mediaId: intent.mediaId }, crop: intent.crop }
      } else if (intent.kind === 'keep') {
        const source = storedCover.value?.source
        if (!source) return
        cover = { source, crop: intent.crop }
      } else {
        if (priorUpload) await deleteCoverBlob(ctx.scopeId(), nodeId)
        cover = null
      }
      const wrappedMeta = await setMemoryCover(ctx.scopeId(), nodeId, key, cover, ctx.node().wrappedMeta)
      ctx.onMetaUpdated({ nodeId, wrappedMeta })
    } catch {
      // best-effort: keep the header stable; a retry re-seals
    }
  }

  return {
    coverEditorOpen,
    coverEditorMedia,
    storedCover,
    coverSrc,
    coverExistingSrc,
    coverMediaId,
    onSetCoverFromPhoto,
    openCoverEditor,
    closeCoverEditor,
    onCoverSave,
  }
}
