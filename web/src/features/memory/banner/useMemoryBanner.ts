// uc.memory.banner (web) - the banner's READ side: the stored banner of the node on screen and its decrypted picture (an
// image banner's JPEG, a video banner's poster), the same for a member and a share guest - the source's loaders read
// through the member's presign or the link's gated one (`source.ts`). Rights only remove the action (share view = member
// view), so nothing here asks who looks.
//
// **Keyed on what the sealed meta names.** The picture re-resolves when the banner's refs change - a new banner, a removal,
// a node switch - and not when another field of the same meta moves (a date, a place): `patchNodeMeta` writes a new blob
// into the tree after every meta write, and re-reading the banner's bytes for a date edit would be a fetch for nothing. A
// removed banner drops its object URL at once (`useObjectUrl` revokes on replace and on unmount), and a read that lands
// after a newer one started is dropped. The banner draws from the node's own attachments, without the media list, so it
// never waits on the gallery (inbox #488-#490); its reads take the scheduler's `now` lane (`readScheduler.ts`).
import { computed, ref, watch } from 'vue'
import type { TreeNode } from '../../../core/node/tree'
import { unsealNodeMeta } from '../../../core/node/nodeMeta'
import { useObjectUrl } from '../../../shared/state/useObjectUrls'
import type { ChunkProvider } from '../../../sw/protocol'
import type { MediaLoaders } from '../source'
import { normalizeBanner, type Banner } from './bannerModel'

export interface MemoryBannerContext {
  scopeId: () => string
  node: () => TreeNode
  memoryKey: () => Uint8Array | null
  loaders: Pick<MediaLoaders, 'coverBlob' | 'attachmentChunks'>
}

/** Where the picture stands: none to show, on its way, shown, or failed (E5: the header row moves up). */
export type BannerStatus = 'none' | 'loading' | 'ready' | 'failed'

export function useMemoryBanner(ctx: MemoryBannerContext) {
  /** The stored banner, normalized; null where the node has none, its meta does not open, or its shape is unknown. */
  const banner = computed<Banner | null>(() => {
    const key = ctx.memoryKey()
    if (!key) return null
    const node = ctx.node()
    return normalizeBanner(unsealNodeMeta(key, { scopeId: ctx.scopeId(), nodeId: node.id }, node.wrappedMeta).banner, node.id)
  })
  /** The node's raw `banner` value (what a writer compares against and a dialog re-opens on). */
  const raw = computed<unknown>(() => {
    const key = ctx.memoryKey()
    if (!key) return null
    const node = ctx.node()
    return unsealNodeMeta(key, { scopeId: ctx.scopeId(), nodeId: node.id }, node.wrappedMeta).banner ?? null
  })

  const picture = useObjectUrl()
  const status = ref<BannerStatus>('none')
  let run = 0
  /** What the picture is read from: the image, or a video's poster; a node switch is a new read even for equal bytes. */
  const pictureKey = computed(() => {
    const b = banner.value
    const ref = b ? (b.medium === 'image' ? b.blob : b.poster) : null
    return `${ctx.node().id}:${b?.medium ?? '-'}:${ref?.blobId ?? '-'}`
  })

  watch(
    pictureKey,
    async () => {
      const mine = ++run
      picture.set(null)
      const b = banner.value
      const key = ctx.memoryKey()
      if (!b || !key) {
        status.value = 'none'
        return
      }
      const source = b.medium === 'image' ? b.blob : b.poster
      if (!source) {
        status.value = 'ready' // a video whose poster is gone: the play button on the frame alone
        return
      }
      status.value = 'loading'
      try {
        const bytes = await ctx.loaders.coverBlob(source, key)
        if (mine !== run) return
        picture.set(new Blob([bytes as BlobPart], { type: 'image/jpeg' }))
        status.value = 'ready'
      } catch {
        if (mine === run) status.value = 'failed' // never a broken frame, never a throw
      }
    },
    { immediate: true },
  )

  /** A video banner's chunk source for the service worker's playback, built over the `BANNER` ref - never anything else. */
  function videoChunks(): ChunkProvider | null {
    const b = banner.value
    const key = ctx.memoryKey()
    if (!b || b.medium !== 'video' || !key) return null
    return ctx.loaders.attachmentChunks(b.blob, key)
  }

  /** The whole video, decrypted - the playback without a service worker (as the lightbox's fallback). */
  async function videoBytes(): Promise<Uint8Array | null> {
    const b = banner.value
    const key = ctx.memoryKey()
    if (!b || b.medium !== 'video' || !key) return null
    return ctx.loaders.coverBlob(b.blob, key)
  }

  return { banner, raw, pictureUrl: picture.url, status, videoChunks, videoBytes }
}
