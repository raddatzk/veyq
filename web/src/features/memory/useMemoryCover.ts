// uc.memory.cover (web) — the Memory-cover reactive slice of MemoryDetail, extracted into a composable
// (cr-0146, audit #77/P4a). A deliberately chosen image + a 16:9 crop, sealed in the SAME node `wrappedMeta` blob
// as the date/location (read-modify-write, operator-blind). The source is one of the Memory's own media (reusing
// its already-decrypted thumbnail) OR a brand-new uploaded image (a standalone cover blob decrypted on demand,
// cr-0073). This owns the reactive wiring: editor/lightbox-seed state, the decrypted cover src (incl. the upload
// object-URL lifecycle), and the read-modify-write save.
import { ref, computed, watch, type Ref } from 'vue'
import { unsealMemoryCover, FULL_FRAME, type MemoryCover, type CoverSaveIntent } from './memoryCover'
import { useObjectUrl } from '../../shared/state/useObjectUrls'
import { useToast } from '../../shared/state/useToast'
import { QUOTA_EXCEEDED } from './uploadQueue'
import { CoverStaleError } from '../../core/node/cover/coverWrite'
import type { SealedMetaState } from '../../core/node/sealedMetaWrite'
import { nodeMetaState } from '../../core/node/nodeMeta'
import { useMetaWriteFeedback, type NodeMetaUpdate } from './useMetaWriteFeedback'
import type { TreeNode } from '../../core/node/tree'
import type { CoverDoor, MediaLoaders } from './source'

/** The reactive context MemoryDetail hands the cover slice — getters so props stay reactive, plus the live gallery
 *  `thumbs` map (a media-source cover reuses an already-decrypted thumbnail) and the host's `metaUpdated` emit. */
export interface MemoryCoverContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  /** How the node's uploaded cover blob opens (the source's loader: the member's presign or the link's). */
  loaders: MediaLoaders
  /** The cover writes this view may make — the source's door where the capabilities grant `setCover`, else null: then
   *  no save writes anything. */
  write: () => CoverDoor | null
  thumbs: Ref<Map<string, string>>
  onMetaUpdated: (payload: NodeMetaUpdate) => void
  /** The resolved "no space left" wording (`media.uploadNoSpace`), for a cover refused `quota_exceeded`. */
  noSpaceMessage: () => string
  /** The resolved wording for a cover another writer replaced meanwhile (`node.cover.stale`). */
  coverStaleMessage: () => string
}

export function useMemoryCover(ctx: MemoryCoverContext) {
  const toast = useToast()
  const coverEditorOpen = ref(false)
  const { editorFailed } = useMetaWriteFeedback(ctx.onMetaUpdated)
  // When the cover editor is opened from the lightbox "Set as cover", it is seeded to crop THAT photo (its already-
  // decrypted thumbnail is the crop source); null = the header "edit current cover" flow. Cleared on close.
  const coverEditorMedia = ref<{ mediaId: string; src: string } | null>(null)
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
   * K_mem (the date + location are preserved). An **upload** uploads the image and attaches it as the node's cover in ONE
   * write with the meta that names it (core's `setNodeCover`, inbox #411) - replacing, in that same request, the uploaded
   * cover it supersedes. A switch to a photo, or no cover, frees the uploaded blob it replaces the same way
   * (`removeNodeCover`); `keep` re-seals the current source with a moved crop only.
   *
   * Version-checked like every meta write (ADR 0077): a peer's cover written meanwhile re-opens the editor on it —
   * unless this save moves the node's one cover blob (an upload), in which case its own cover is re-applied.
   *
   * No half state: the blob step and the meta are one request, so a failed save leaves the previous cover, its blob and its
   * meta exactly as they were (a cover another writer replaced meanwhile is a `CoverStaleError`, said as a conflict). What
   * the write replaced is read from the blob the server accepted it over (inbox #284), never from this tree copy.
   */
  async function onCoverSave(intent: CoverSaveIntent) {
    const key = ctx.memoryKey()
    const write = ctx.write()
    if (!key || !write) return
    const nodeId = ctx.node().id
    try {
      const current = nodeMetaState(ctx.node())
      let state: SealedMetaState
      if (intent.kind === 'upload') {
        const { replaced: _replaced, cover: _cover, ...written } = await write.setUpload(
          nodeId, key,
          { blob: intent.file, mediaType: intent.file.type, lastModified: intent.file.lastModified, name: intent.file.name },
          intent.crop, current,
        )
        state = written
      } else {
        let cover: MemoryCover | null
        if (intent.kind === 'media') {
          cover = { source: { kind: 'media', mediaId: intent.mediaId }, crop: intent.crop }
        } else if (intent.kind === 'keep') {
          const source = storedCover.value?.source
          if (!source) return
          cover = { source, crop: intent.crop }
        } else {
          cover = null
        }
        const { replaced: _replaced, ...written } = await write.set(nodeId, key, cover, current, true)
        state = written
      }
      ctx.onMetaUpdated({ nodeId, ...state })
    } catch (e) {
      // best-effort: keep the header stable; a retry re-seals. A refusal for lack of room is the one failure the
      // user must hear about — the cover would otherwise just not appear — and it reads as the uploads tray's does.
      if ((e as Error)?.message === QUOTA_EXCEEDED) toast.error(ctx.noSpaceMessage())
      else if (e instanceof CoverStaleError) toast.error(ctx.coverStaleMessage())
      else await editorFailed(nodeId, e, () => (coverEditorOpen.value = true))
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
