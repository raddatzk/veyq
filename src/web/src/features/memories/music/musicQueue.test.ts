// @vitest-environment happy-dom
// uc.memory.background-music (ADR 0135 decisions 5 and 8): the node's music as core's player takes it - titles, trimmed
// spans, a blocked medium left out - and each track's `open()` built as the lightbox builds its playback: the service
// worker's stream, else a whole-file `blob:`; its `release()` lets it go. No key reaches the queue.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../../sw/register', () => ({ registerPlayback: vi.fn(), revokePlayback: vi.fn(), handleFromUrl: (u: string) => u.replace('/__media/', '') }))

import { registerPlayback, revokePlayback } from '../../../sw/register'
import { musicQueue, musicQueueKey } from './musicQueue'
import { mediaItem } from '../../../test/models'
import type { MediaItem } from '../media'
import type { MediaLoaders } from '../source'

const KEY = new Uint8Array(32).fill(7)
const provider = { chunkCount: 3, byteSize: 300, getPlaintextChunk: vi.fn() }
function loaders(): MediaLoaders {
  return {
    blobMeta: (it: MediaItem) => ({ type: it.mediaId === 'w' ? 'audio/wav' : 'audio/mpeg', name: it.mediaId === 'n' ? undefined : `${it.mediaId} Song.mp3` }),
    meta: (it: MediaItem) => (it.mediaId === 'a' ? { v: 1, durationMs: 9000, trimStartMs: 300, trimEndMs: 8000 } : null),
    chunk: vi.fn(() => provider),
    original: vi.fn(async () => new Uint8Array([1, 2, 3])),
  } as unknown as MediaLoaders
}
const input = (music: MediaItem[], l = loaders()) => ({ scopeId: 's1', nodeId: 'n1', nodeName: 'Lisbon', key: KEY, music, loaders: l, untitled: (n: number) => `Track ${n}`, startId: 'w' })

beforeEach(() => vi.clearAllMocks())

describe('musicQueue', () => {
  it('names each track by its file, spans it by its facts (none: it plays whole), leaves a blocked one out, keys the node', () => {
    const q = musicQueue(input([mediaItem({ mediaId: 'a' }), mediaItem({ mediaId: 'n' }), mediaItem({ mediaId: 'w' }), mediaItem({ mediaId: 'x', blocked: true })]))
    expect(q.key).toBe(musicQueueKey('s1', 'n1'))
    expect([q.album, q.startId]).toEqual(['Lisbon', 'w'])
    expect(q.href, 'no href given: the album links nowhere').toBeUndefined()
    expect(musicQueue({ ...input([mediaItem({ mediaId: 'a' })]), href: '/#memories/lisbon' }).href).toBe('/#memories/lisbon')
    expect(q.tracks.map(({ id, title, startMs, endMs }) => ({ id, title, startMs, endMs }))).toEqual([
      { id: 'a', title: 'a Song', startMs: 300, endMs: 8000 },
      { id: 'n', title: 'Track 2', startMs: undefined, endMs: undefined },
      { id: 'w', title: 'w Song', startMs: undefined, endMs: undefined },
    ])
    expect(JSON.stringify(q), 'no key in what the player is handed').not.toContain(Array.from(KEY).join(','))
  })

  it('opens a track as a service-worker stream of the original, typed by its sealed type, and revokes it on release', async () => {
    vi.mocked(registerPlayback).mockResolvedValue('/__media/h1')
    const l = loaders()
    const [, , wav] = musicQueue(input([mediaItem({ mediaId: 'a' }), mediaItem({ mediaId: 'n' }), mediaItem({ mediaId: 'w' })], l)).tracks
    const source = await wav.open()
    expect(source.url).toBe('/__media/h1')
    expect(vi.mocked(registerPlayback).mock.calls[0][0]).toEqual({ chunkCount: 3, byteSize: 300, mediaType: 'audio/wav' })
    expect(vi.mocked(registerPlayback).mock.calls[0][1]).toBe(provider)
    expect(l.original).not.toHaveBeenCalled()
    source.release()
    expect(revokePlayback).toHaveBeenCalledWith('h1')
  })

  it('without a service worker, decrypts the whole original into a blob: and revokes it on release', async () => {
    vi.mocked(registerPlayback).mockResolvedValue(null)
    const revoke = vi.spyOn(URL, 'revokeObjectURL')
    const l = loaders()
    const source = await musicQueue(input([mediaItem({ mediaId: 'a' })], l)).tracks[0].open()
    expect(source.url).toMatch(/^blob:/)
    expect(l.original).toHaveBeenCalledWith(expect.objectContaining({ mediaId: 'a' }), KEY)
    source.release()
    expect(revoke).toHaveBeenCalledWith(source.url)
  })
})
