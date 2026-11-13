import { describe, it, expect, vi } from 'vitest'
import { createPreviewCache, previewKeyOf } from './previewCache'
import type { MediaItem } from './media'

/** A listed medium with a PREVIEW derivative on blob `preview-<id>` (only the fields `lightboxRef` reads). */
function item(id: string, previewBlob = `preview-${id}`): MediaItem {
  return {
    mediaId: id,
    original: { blobId: `original-${id}` },
    derivatives: [{ kind: 'PREVIEW', ref: { blobId: previewBlob } }],
  } as unknown as MediaItem
}

const bytes = (n: number) => new Uint8Array(n)
const settle = () => new Promise<void>((r) => setTimeout(r, 0))

/** A loader that answers each medium with `size` bytes and records what it was asked. */
function loader(size = 10) {
  return vi.fn(async (_it: MediaItem) => bytes(size))
}

describe('previewCache', () => {
  it('reads a preview once: a second ask, settled or in flight, is the same read', async () => {
    const load = loader()
    const cache = createPreviewCache(load)
    const a = item('a')
    const first = cache.get(a)
    const joined = cache.get(a)
    expect(await joined).toBe(await first)
    await settle()
    expect(cache.peek(a), 'settled: shown in the same tick').toBe(await first)
    await cache.get(a)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('peeks nothing for a read still in flight', () => {
    const cache = createPreviewCache(() => new Promise(() => {}))
    const a = item('a')
    void cache.get(a)
    expect(cache.peek(a)).toBeNull()
  })

  it('keeps no failure: the next ask reads again', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('store_unavailable')).mockResolvedValue(bytes(3))
    const cache = createPreviewCache(load)
    const a = item('a')
    await expect(cache.get(a)).rejects.toThrow('store_unavailable')
    expect(await cache.get(a)).toHaveLength(3)
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('a read ahead that fails is retried once for the viewer who asks for it', async () => {
    let fail: (e: Error) => void = () => {}
    const load = vi.fn()
      .mockImplementationOnce(() => new Promise<Uint8Array>((_, reject) => { fail = reject }))
      .mockResolvedValue(bytes(4))
    const cache = createPreviewCache(load)
    const a = item('a')
    cache.prefetch(a)
    const asked = cache.get(a) // the viewer arrives while the read ahead is in flight
    fail(new Error('chunk_get_failed:500'))
    expect(await asked).toHaveLength(4)
    expect(load).toHaveBeenCalledTimes(2)
  })

  it("a viewer's own read that fails is not retried", async () => {
    const load = vi.fn().mockRejectedValue(new Error('chunk_get_failed:500'))
    const cache = createPreviewCache(load)
    await expect(cache.get(item('a'))).rejects.toThrow()
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('a failed read ahead stays silent and is not kept', async () => {
    const load = vi.fn().mockRejectedValueOnce(new Error('boom')).mockResolvedValue(bytes(2))
    const cache = createPreviewCache(load)
    const a = item('a')
    cache.prefetch(a)
    await settle()
    expect(cache.peek(a)).toBeNull()
    expect(await cache.get(a)).toHaveLength(2)
  })

  it('reads ahead once per item', async () => {
    const load = loader()
    const cache = createPreviewCache(load)
    const a = item('a')
    cache.prefetch(a)
    cache.prefetch(a)
    await cache.get(a)
    expect(load).toHaveBeenCalledTimes(1)
  })

  it('holds at most its bound, dropping the least recently asked first', async () => {
    const cache = createPreviewCache(loader(10), 25)
    const [a, b, c] = [item('a'), item('b'), item('c')]
    await cache.get(a)
    await cache.get(b)
    await settle()
    expect(cache.peek(a), 'asking a again makes b the oldest').not.toBeNull()
    await cache.get(c)
    await settle()
    expect(cache.heldBytes()).toBeLessThanOrEqual(25)
    expect(cache.peek(b), 'the least recently asked went').toBeNull()
    expect(cache.peek(a)).not.toBeNull()
    expect(cache.peek(c), 'the one that just landed stays').not.toBeNull()
  })

  it('keeps a preview larger than the bound on its own: the one just read is never dropped for its own size', async () => {
    const cache = createPreviewCache(loader(40), 25)
    const a = item('a')
    await cache.get(a)
    await settle()
    expect(cache.peek(a)).not.toBeNull()
  })

  it('retain drops what the gallery no longer lists, settled or in flight', async () => {
    let land: (b: Uint8Array) => void = () => {}
    const load = vi.fn()
      .mockResolvedValueOnce(bytes(5))
      .mockImplementationOnce(() => new Promise<Uint8Array>((r) => { land = r }))
      .mockResolvedValue(bytes(6))
    const cache = createPreviewCache(load)
    const [a, b] = [item('a'), item('b')]
    await cache.get(a)
    void cache.get(b)
    await settle()
    cache.retain(new Set())
    expect(cache.peek(a), 'a settled entry goes').toBeNull()
    land(bytes(7))
    await settle()
    expect(cache.peek(b), 'a read in flight is not kept when it lands').toBeNull()
    expect(cache.heldBytes()).toBe(0)
  })

  it('retain keeps what is still listed', async () => {
    const cache = createPreviewCache(loader())
    const [a, b] = [item('a'), item('b')]
    await cache.get(a)
    await cache.get(b)
    await settle()
    cache.retain(new Set([previewKeyOf(a)]))
    expect(cache.peek(a)).not.toBeNull()
    expect(cache.peek(b)).toBeNull()
  })

  it('a replaced preview under the same medium is another entry', async () => {
    const load = loader()
    const cache = createPreviewCache(load)
    await cache.get(item('a', 'old'))
    await settle()
    expect(cache.peek(item('a', 'new'))).toBeNull()
    expect(previewKeyOf(item('a', 'old'))).not.toBe(previewKeyOf(item('a', 'new')))
  })

  it('clear drops everything', async () => {
    const cache = createPreviewCache(loader())
    const a = item('a')
    await cache.get(a)
    await settle()
    cache.clear()
    expect(cache.peek(a)).toBeNull()
    expect(cache.heldBytes()).toBe(0)
  })
})
