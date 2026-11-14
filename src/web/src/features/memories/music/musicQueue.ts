// uc.memory.background-music (ADR 0135 decisions 5 and 8): a node's music as core's player takes it. The playlist is the
// node's own tracks, nothing inherited, a snapshot taken when it starts (starting it again picks up tracks added since).
// Each track carries its title, its trimmed span (none where its facts are absent: it then plays whole) and an `open()`
// that is Memories' alone, built as the lightbox builds its playback: the original's chunk provider - which holds the key -
// registered with the service worker for a same-origin `/__media/` stream, or, where no service worker controls the page,
// the whole original decrypted into a `blob:`. The player is handed the URL and a `release()` that revokes it; it never
// sees a key.
import type { PlayerQueue, PlayerTrack } from '../../../core/audio/player'
import { registerPlayback, revokePlayback, handleFromUrl } from '../../../sw/register'
import type { MediaItem } from '../media'
import type { MediaLoaders } from '../source'
import { audioFactsFrom } from './audioFacts'
import { musicTitle } from './audio'

/** Whose queue this is: the node, in its space - what the header asks to know whether *this* node's music plays. */
export const musicQueueKey = (scopeId: string, nodeId: string) => `memories:${scopeId}:${nodeId}`

export type MusicQueueInput = {
  scopeId: string
  nodeId: string
  nodeName: string
  /** The page the music is started from (the node's), which the bar's album links back to. */
  href?: string
  key: Uint8Array
  music: readonly MediaItem[]
  loaders: MediaLoaders
  /** A track's title where its file has no name: its place in the list. */
  untitled: (n: number) => string
  startId?: string
}

/** A track's playable source: the service worker's stream, else a whole-file `blob:`. */
async function openTrack(item: MediaItem, key: Uint8Array, loaders: MediaLoaders, mediaType: string): Promise<{ url: string; release: () => void }> {
  const provider = loaders.chunk(item, key)
  const url = await registerPlayback({ chunkCount: provider.chunkCount, byteSize: provider.byteSize, mediaType }, provider)
  if (url) {
    const handle = handleFromUrl(url)
    return { url, release: () => { if (handle) revokePlayback(handle) } }
  }
  const bytes = await loaders.original(item, key)
  const blobUrl = URL.createObjectURL(new Blob([bytes as BlobPart], { type: mediaType }))
  return { url: blobUrl, release: () => URL.revokeObjectURL(blobUrl) }
}

/** The node's music as a queue for core's player. */
export function musicQueue(input: MusicQueueInput): PlayerQueue {
  // a medium a moderator blocked answers every read with 451: it is no track to play (its row stays listed)
  const tracks: PlayerTrack[] = input.music.filter((item) => !item.blocked).map((item, i) => {
    const blobMeta = input.loaders.blobMeta(item, input.key)
    const facts = audioFactsFrom(input.loaders.meta(item, input.key))
    const mediaType = typeof blobMeta?.type === 'string' && blobMeta.type ? blobMeta.type : 'audio/mpeg'
    return {
      id: item.mediaId,
      title: musicTitle(blobMeta?.name) ?? input.untitled(i + 1),
      ...(facts ? { startMs: facts.trimStartMs, endMs: facts.trimEndMs } : {}),
      open: () => openTrack(item, input.key, input.loaders, mediaType),
    }
  })
  return {
    key: musicQueueKey(input.scopeId, input.nodeId),
    album: input.nodeName,
    ...(input.href ? { href: input.href } : {}),
    tracks,
    ...(input.startId ? { startId: input.startId } : {}),
  }
}
