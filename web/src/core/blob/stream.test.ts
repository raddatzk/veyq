// `streamBlob` against fake chunk sources and a recording sink: in-order writes whatever order the chunks open in, a
// bounded window, and a failure of any kind ends the sink with `abort` — never with `close`, which would finalise a
// partial file as if it were whole.
import { describe, it, expect } from 'vitest'
import { iterateBlob, streamBlob, type ByteSink, type ChunkSource } from './stream'

/** A source whose chunk `i` opens only when the test releases it; records how many are open at once. */
function gatedSource(count: number) {
  const gates = Array.from({ length: count }, () => {
    let release!: () => void
    let fail!: (e: Error) => void
    const opened = new Promise<void>((res, rej) => {
      release = res
      fail = rej
    })
    return { opened, release, fail }
  })
  const stats = { requested: [] as number[], open: 0, maxOpen: 0 }
  const source: ChunkSource = {
    chunkCount: count,
    async getPlaintextChunk(i) {
      stats.requested.push(i)
      stats.open += 1
      stats.maxOpen = Math.max(stats.maxOpen, stats.open)
      try {
        await gates[i].opened
        return new Uint8Array([i])
      } finally {
        stats.open -= 1
      }
    },
  }
  return { source, gates, stats }
}

const immediate = (chunks: Uint8Array[]): ChunkSource => ({
  chunkCount: chunks.length,
  getPlaintextChunk: async (i) => chunks[i],
})

/** A sink that records every call; `held` counts chunks the stream has started but the sink not yet received. */
function recordingSink(overrides: Partial<ByteSink> = {}) {
  const calls: string[] = []
  const written: number[] = []
  const sink: ByteSink = {
    write: overrides.write ?? (async (b) => {
      calls.push('write')
      written.push(...b)
    }),
    close: overrides.close ?? (async () => void calls.push('close')),
    abort: overrides.abort ?? (async () => void calls.push('abort')),
  }
  return { sink, calls, written }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

describe('streamBlob', () => {
  it('writes the chunks in index order even when they open out of order, then closes', async () => {
    const { source, gates } = gatedSource(6)
    const { sink, calls, written } = recordingSink()
    const done = streamBlob(source, sink, { inFlight: 4 })
    gates[0].release()
    await flush()
    for (const i of [3, 2, 5, 1, 4]) {
      gates[i].release()
      await flush()
    }
    await done
    expect(written).toEqual([0, 1, 2, 3, 4, 5])
    expect(calls.at(-1)).toBe('close')
    expect(calls.filter((c) => c === 'abort')).toEqual([])
  })

  it('keeps at most `inFlight` chunks opened ahead of the write — the memory bound', async () => {
    const { source, gates, stats } = gatedSource(20)
    const { sink } = recordingSink()
    const done = streamBlob(source, sink, { inFlight: 3 })
    gates[0].release()
    await flush()
    // Chunk 1 is held back: the stream must not run ahead past the window while it waits for it.
    for (let i = 2; i < 20; i++) gates[i].release()
    await flush()
    expect(stats.requested).toEqual([0, 1, 2, 3])
    gates[1].release()
    await done
    expect(stats.maxOpen).toBeLessThanOrEqual(3)
  })

  it('does not open chunks while a slow sink write is pending (backpressure)', async () => {
    const chunks = Array.from({ length: 10 }, (_, i) => new Uint8Array([i]))
    const requested: number[] = []
    const source: ChunkSource = { chunkCount: 10, getPlaintextChunk: async (i) => (requested.push(i), chunks[i]) }
    let unblock!: () => void
    const blocked = new Promise<void>((r) => (unblock = r))
    const { sink } = recordingSink({ write: async (b) => { if (b[0] === 1) await blocked } })
    const done = streamBlob(source, sink, { inFlight: 2 })
    await flush()
    expect(Math.max(...requested)).toBeLessThanOrEqual(2) // writing chunk 1: chunk 2 may be open, nothing further
    unblock()
    await done
  })

  it('opens chunk 0 alone before the window fills — a provider presigns on its first read', async () => {
    const { source, gates, stats } = gatedSource(5)
    const { sink } = recordingSink()
    const done = streamBlob(source, sink, { inFlight: 4 })
    await flush()
    expect(stats.requested).toEqual([0])
    gates.forEach((g) => g.release())
    await done
  })

  it('a chunk that fails to open aborts the sink, never closes it, and rethrows', async () => {
    const { source, gates } = gatedSource(4)
    const { sink, calls } = recordingSink()
    const done = streamBlob(source, sink, { inFlight: 2 })
    gates[0].release()
    await flush()
    gates[1].fail(new Error('OpenRefused'))
    gates.slice(2).forEach((g) => g.release())
    await expect(done).rejects.toThrow('OpenRefused')
    expect(calls).toEqual(['write', 'abort'])
  })

  it('a failing sink write aborts the sink and rethrows', async () => {
    const { sink, calls } = recordingSink({ write: async () => { throw new Error('disk_full') } })
    await expect(streamBlob(immediate([new Uint8Array([1])]), sink)).rejects.toThrow('disk_full')
    expect(calls).toEqual(['abort'])
  })

  it('a close that fails aborts the sink', async () => {
    const { sink, calls } = recordingSink({ close: async () => { throw new Error('close_failed') } })
    await expect(streamBlob(immediate([new Uint8Array([1])]), sink)).rejects.toThrow('close_failed')
    expect(calls).toEqual(['write', 'abort'])
  })

  it('a fired signal stops the stream and aborts the sink', async () => {
    const { source, gates } = gatedSource(4)
    const { sink, calls } = recordingSink()
    const controller = new AbortController()
    const done = streamBlob(source, sink, { signal: controller.signal })
    gates[0].release()
    await flush()
    controller.abort()
    gates.slice(1).forEach((g) => g.release())
    await expect(done).rejects.toMatchObject({ name: 'AbortError' })
    expect(calls).toEqual(['write', 'abort'])
  })

  it('an empty blob (one empty frame) closes the sink without writing', async () => {
    const { sink, calls } = recordingSink()
    await streamBlob(immediate([new Uint8Array(0)]), sink)
    expect(calls).toEqual(['close'])
  })

  it('reports progress per written chunk', async () => {
    const seen: number[] = []
    await streamBlob(immediate([1, 2, 3, 4].map((n) => new Uint8Array([n]))), recordingSink().sink, { onProgress: (f) => seen.push(f) })
    expect(seen).toEqual([0.25, 0.5, 0.75, 1])
  })
})

describe('iterateBlob', () => {
  it('yields the chunks in order, opening at most the window ahead of the one taken', async () => {
    const { source, gates, stats } = gatedSource(6)
    const it = iterateBlob(source, { inFlight: 2 })
    const first = it.next()
    await Promise.resolve()
    expect(stats.requested).toEqual([0]) // chunk 0 alone first
    gates[0].release()
    expect((await first).value).toEqual(new Uint8Array([0]))
    const second = it.next()
    await Promise.resolve()
    await Promise.resolve()
    expect(stats.requested).toEqual([0, 1, 2]) // taking chunk 1 opens 2 ahead (window 2), not the rest
    gates.forEach((g) => g.release())
    const rest: number[] = []
    rest.push((await second).value[0])
    for await (const c of it) rest.push(c[0])
    expect(rest).toEqual([1, 2, 3, 4, 5])
    expect(stats.maxOpen).toBeLessThanOrEqual(3)
  })

  it('throws what a chunk threw, and stops at an aborted signal', async () => {
    const failing: ChunkSource = { chunkCount: 2, getPlaintextChunk: async (i) => { if (i === 1) throw new Error('boom'); return new Uint8Array([i]) } }
    const seen: number[] = []
    await expect((async () => { for await (const c of iterateBlob(failing)) seen.push(c[0]) })()).rejects.toThrow('boom')
    expect(seen).toEqual([0])
    const ctl = new AbortController()
    ctl.abort()
    await expect(iterateBlob(immediate([new Uint8Array([1])]), { signal: ctl.signal }).next()).rejects.toBeDefined()
  })
})
