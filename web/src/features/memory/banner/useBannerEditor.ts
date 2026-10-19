// uc.memory.banner (web) - what the banner dialog's save does, for the Memory on screen: prepare the bytes on the device, seal
// and upload them, and write the slots with the meta that names them - through the source's `banner` door, a member's only
// (`source.ts`). The dialog (`BannerDialog.vue`) holds no network and no key; it hands an intent here and shows the progress.
//
// A gallery medium is COPIED (ADR 0102 decision 3), from what its upload sealed, never from the preview a viewer is shown:
//  - an image: its ORIGINAL decrypted whole on the device (the cover's bound, 40 MB) and derived into the banner's JPEG;
//  - a video: its original read by frame through its chunk provider and re-sealed as it is (never held whole, whatever its
//    length - Q4), and the poster its upload made (the gallery's preview tier, the very first-frame JPEG the poster derive
//    produces) re-sealed as the banner's poster - so no frame is decoded for it here. A video whose upload made no poster
//    (a browser that could not decode it then) has no frame to show before it plays, and is refused as a banner.
import { framedPayload } from '../../../core/export/copySource'
import { CHUNK_SIZE } from '../../../core/blob/transport'
import { nodeMetaState } from '../../../core/node/nodeMeta'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import type { TreeNode } from '../../../core/node/tree'
import { createBrowserDeriver, type MediaDeriver } from '../../../shared/image/mediaDeriver'
import { hasDerivatives, mediaTypeOf, plaintextSize, type MediaItem } from '../media'
import type { MemorySource } from '../source'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import { reframedBanner, sealedBanner, type BannerFraming } from './bannerModel'
import { BANNER_UNREADABLE, prepareImage, prepareVideo, type PreparedBanner } from './bannerWrite'

/** What the dialog asks for. A picked video comes with the poster the dialog already decoded for its preview. */
export type BannerSaveIntent =
  | { kind: 'file'; file: File; type: string; poster: Uint8Array | null; framing: BannerFraming }
  | { kind: 'medium'; mediaId: string; framing: BannerFraming }
  | { kind: 'keep'; framing: BannerFraming }
  | { kind: 'remove' }

export type BannerSaveOpts = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

export interface BannerEditorContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  source: MemorySource
  /** The gallery's media (a medium the lightbox hands over is found here). */
  items: () => readonly MediaItem[]
  /** The stored `banner` value (what a re-frame keeps the bytes of). */
  raw: () => unknown
  onMetaUpdated: (update: NodeMetaUpdate) => void
  deriver?: MediaDeriver
}

const isVideoType = (type: string | undefined): boolean => !!type && type.startsWith('video/')

export function useBannerEditor(ctx: BannerEditorContext) {
  const deriver = () => ctx.deriver ?? createBrowserDeriver()

  /** A gallery medium's bytes as the banner takes them (see the header). */
  async function fromMedium(mediaId: string, key: Uint8Array): Promise<{ prepared: PreparedBanner; origin: string }> {
    const item = ctx.items().find((i) => i.mediaId === mediaId)
    if (!item) throw new Error(BANNER_UNREADABLE)
    const loaders = ctx.source.loaders
    const type = mediaTypeOf(loaders.blobMeta(item, key)) ?? ''
    if (isVideoType(type)) {
      if (!hasDerivatives(item)) throw new Error(BANNER_UNREADABLE)
      const provider = loaders.chunk(item, key)
      const size = plaintextSize(item.original)
      const video = framedPayload(
        { frameSize: CHUNK_SIZE, chunkCount: provider.chunkCount, getPlaintextChunk: (i) => provider.getPlaintextChunk(i) },
        { size, name: 'banner', type, lastModified: 0, path: 'banner' },
      )
      return { prepared: await prepareVideo(video, type, () => loaders.preview(item, key)), origin: mediaId }
    }
    const original = await loaders.original(item, key)
    return { prepared: await prepareImage(new Blob([original as BlobPart], { type }), deriver()), origin: mediaId }
  }

  /** A picked file's bytes as the banner takes them: an image derived, a video as it is with the poster already decoded. */
  async function fromFile(file: File, type: string, poster: Uint8Array | null): Promise<PreparedBanner> {
    if (isVideoType(type)) {
      return prepareVideo(file, type, async () => poster ?? deriver().deriveVideoPoster(file))
    }
    return prepareImage(file, deriver())
  }

  /** Run one save; the dialog awaits it, shows its progress and cancels it through `signal`. A peer's banner change patches
   *  the tree with what the server holds (the dialog then says so); every failure is rethrown for the dialog to word. */
  async function save(intent: BannerSaveIntent, opts: BannerSaveOpts = {}): Promise<void> {
    try {
      await run(intent, opts)
    } catch (e) {
      if (e instanceof MetaConflictError) ctx.onMetaUpdated({ nodeId: ctx.node().id, ...e.current })
      throw e
    }
  }

  async function run(intent: BannerSaveIntent, opts: BannerSaveOpts): Promise<void> {
    const door = ctx.source.banner
    const key = ctx.memoryKey()
    if (!door || !key) throw new Error('banner_not_writable')
    const node = ctx.node()
    const nodeId = node.id
    const current = nodeMetaState(node)
    let state
    if (intent.kind === 'remove') {
      state = await door.write({ nodeId, nodeKey: key, current, banner: null })
    } else if (intent.kind === 'keep') {
      const banner = reframedBanner(ctx.raw(), intent.framing)
      if (!banner) throw new Error(BANNER_UNREADABLE)
      state = await door.write({ nodeId, nodeKey: key, current, banner })
    } else {
      const { prepared, origin } = intent.kind === 'file'
        ? { prepared: await fromFile(intent.file, intent.type, intent.poster), origin: null }
        : await fromMedium(intent.mediaId, key)
      const uploaded = await door.upload(nodeId, key, prepared, opts)
      const banner = sealedBanner({
        medium: uploaded.medium, blob: uploaded.blob, poster: uploaded.poster, type: uploaded.type, origin, framing: intent.framing,
      })
      state = await door.write({
        nodeId, nodeKey: key, current, banner, upload: { uploadId: uploaded.uploadId, posterUploadId: uploaded.posterUploadId },
      })
    }
    ctx.onMetaUpdated({ nodeId, ...state })
  }

  return { save }
}
