// uc.memory.banner (web) - what the banner dialog's save does, for the Memory on screen: prepare the bytes on the device, seal
// and upload them, and write the slots with the meta that names them - through the source's `banner` door, a member's only
// (`source.ts`). The dialog (`BannerDialog.vue`) holds no network and no key; it hands an intent here and shows the progress.
//
// A banner is set only by a file picked in its dialog (never from the gallery, so no member overwrites another's choice in
// passing). Where the Memory's gallery already stores that file, the banner shares the stored object instead of storing it
// again (ADR 0013 § 5, `storedMatch.ts`): an image banner a medium's PREVIEW (the same spec of the same picture), a video
// banner the medium's ORIGINAL and its PREVIEW as the poster. The file's SHA-256 is read once here - it is the match key, a
// video's `blob-meta` and the banner meta's `sha256`.
import { contentDigest } from '../../../core/blob/sharedBlob'
import { nodeMetaState, unsealNodeMeta } from '../../../core/node/nodeMeta'
import { MetaConflictError } from '../../../core/node/sealedMetaWrite'
import type { TreeNode } from '../../../core/node/tree'
import { createBrowserDeriver, type MediaDeriver } from '../../../shared/image/mediaDeriver'
import type { MediaItem } from '../media'
import type { MemorySource } from '../source'
import { findEqualMedium, storedTier } from '../storedMatch'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import { reframedBanner, sealedBanner, type BannerFraming } from './bannerModel'
import { BANNER_UNREADABLE, bannerVideoType, prepareImage, preparePoster, type PreparedBanner } from './bannerWrite'

/** What the dialog asks for. A picked video comes with the poster the dialog already decoded for its preview. */
export type BannerSaveIntent =
  | { kind: 'file'; file: File; type: string; poster: Uint8Array | null; framing: BannerFraming }
  | { kind: 'keep'; framing: BannerFraming }
  | { kind: 'remove' }

export type BannerSaveOpts = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

export interface BannerEditorContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  source: MemorySource
  /** The gallery's media: a medium whose original is the picked file is shared, not stored again. */
  items: () => readonly MediaItem[]
  /** The stored `banner` value (what a re-frame keeps the bytes of). */
  raw: () => unknown
  onMetaUpdated: (update: NodeMetaUpdate) => void
  deriver?: MediaDeriver
}

const isVideoType = (type: string | undefined): boolean => !!type && type.startsWith('video/')

export function useBannerEditor(ctx: BannerEditorContext) {
  const deriver = () => ctx.deriver ?? createBrowserDeriver()

  /**
   * A picked file's bytes as the banner takes them, under `key`: the stored tiers of an equal gallery medium where there is
   * one, else an image derived, a video as it is with the poster already decoded (or decoded now). `sha256` is the file's,
   * read once per save.
   */
  async function fromFile(
    file: File, type: string, poster: Uint8Array | null, key: Uint8Array, sha256: string, signal?: AbortSignal,
  ): Promise<PreparedBanner & { sha256: string }> {
    const equal = await findEqualMedium(ctx.scopeId(), key, ctx.items(), file, signal, sha256)
    if (isVideoType(type)) {
      const original = equal && storedTier(equal, 'ORIGINAL')
      const preview = equal && storedTier(equal, 'PREVIEW')
      return {
        medium: 'video',
        video: original ? { share: original } : { upload: file },
        type: bannerVideoType(type),
        sha256,
        poster: original && preview ? { share: preview } : await preparePoster(async () => poster ?? deriver().deriveVideoPoster(file)),
      }
    }
    const preview = equal && storedTier(equal, 'PREVIEW')
    return { medium: 'image', image: preview ? { share: preview } : await prepareImage(file, deriver()), sha256 }
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
    // The file's digest is read once: it is the match key, a video's `blob-meta` and the banner meta's `sha256`, whatever key.
    const sha256 = intent.kind === 'file' ? await contentDigest(intent.file, opts.signal) : ''
    // One save at the Memory's key; on an epoch refusal the door pulls the Memory's path forward and runs it again under the
    // key it re-read, from scratch - the base meta and a kept banner's wraps from the re-read node (the re-key re-sealed
    // them), the bytes uploaded again (ADR 0111 decision 11).
    const state = await door.under(nodeId, { key, epoch: node.epoch }, async (at, reread) => {
      const current = { ...nodeMetaState(reread ?? node), nodeEpoch: at.epoch }
      if (intent.kind === 'remove') return door.write({ nodeId, nodeKey: at.key, current, banner: null })
      if (intent.kind === 'keep') {
        const raw = reread ? unsealNodeMeta(at.key, { scopeId: ctx.scopeId(), nodeId }, reread.wrappedMeta).banner ?? null : ctx.raw()
        const banner = reframedBanner(raw, intent.framing)
        if (!banner) throw new Error(BANNER_UNREADABLE)
        return door.write({ nodeId, nodeKey: at.key, current, banner })
      }
      const prepared = await fromFile(intent.file, intent.type, intent.poster, at.key, sha256, opts.signal)
      const uploaded = await door.upload(nodeId, at.key, prepared, opts)
      const banner = sealedBanner({
        medium: uploaded.medium, blob: uploaded.blob, poster: uploaded.poster, type: uploaded.type, sha256: prepared.sha256,
        framing: intent.framing,
      })
      return door.write({
        nodeId, nodeKey: at.key, current, banner,
        upload: { uploadId: uploaded.uploadId, posterUploadId: uploaded.posterUploadId, shared: uploaded.shared, posterShared: uploaded.posterShared },
      })
    })
    ctx.onMetaUpdated({ nodeId, ...state })
  }

  return { save }
}
