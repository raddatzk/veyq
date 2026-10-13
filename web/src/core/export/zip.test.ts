// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { crc32 } from './crc32'
import { openZip, READ_CHUNK, ZipError, ZipWriter } from './zip'
import type { ByteSink } from '../blob/stream'

function memorySink() {
  const parts: Uint8Array[] = []
  const state = { closed: false, aborted: false }
  const sink: ByteSink = {
    write: async (b) => void parts.push(b.slice()),
    close: async () => void (state.closed = true),
    abort: async () => void (state.aborted = true),
  }
  return { sink, state, blob: () => new Blob(parts as BlobPart[]) }
}
const text = (s: string) => new TextEncoder().encode(s)
async function collect(it: AsyncIterable<Uint8Array>): Promise<Uint8Array> {
  const out: Uint8Array[] = []
  for await (const c of it) out.push(c)
  return new Uint8Array(await new Blob(out as BlobPart[]).arrayBuffer())
}
async function* split(bytes: Uint8Array, n: number) {
  for (let i = 0; i < bytes.length; i += n) yield bytes.subarray(i, i + n)
}
const expectCode = async (p: Promise<unknown>, code: string) => {
  await expect(p).rejects.toBeInstanceOf(ZipError)
  await expect(p).rejects.toMatchObject({ code })
}

describe('crc32', () => {
  it('matches the standard check value', () => {
    expect(crc32(text('123456789'))).toBe(0xcbf43926)
    expect(crc32(new Uint8Array(0))).toBe(0)
  })
})

describe.each([
  ['plain', undefined],
  ['zip64 (limit lowered so small data takes the zip64 branches)', 8],
])('a store-only archive, %s', (_label, zip64At) => {
  async function build() {
    const m = memorySink()
    const w = new ZipWriter(m.sink, { zip64At, modified: new Date('2026-10-13T09:30:00Z') })
    const big = new Uint8Array(READ_CHUNK * 2 + 17).map((_, i) => (i * 31) & 0xff)
    await w.addBytes('manifest.json', text('{"format":"veyq-subtree"}'))
    await w.addFile('data/n2/größe ü.bin', big.length, split(big, 99_999))
    await w.addBytes('data/n3/empty', new Uint8Array(0))
    await w.finish()
    return { m, big }
  }

  it('reads back byte for byte, in order, with names in UTF-8', async () => {
    const { m, big } = await build()
    expect(m.state.closed).toBe(true)
    const zip = await openZip(m.blob())
    expect(zip.entries.map((e) => e.name)).toEqual(['manifest.json', 'data/n2/größe ü.bin', 'data/n3/empty'])
    expect(await collect(zip.stream('data/n2/größe ü.bin'))).toEqual(big)
    expect(new TextDecoder().decode(await zip.bytes('manifest.json', 1000))).toBe('{"format":"veyq-subtree"}')
    expect((await collect(zip.stream('data/n3/empty'))).length).toBe(0)
    expect(zip.get('data/n2/größe ü.bin')!.size).toBe(big.length)
  })

  it('hands an entry out in chunks no larger than READ_CHUNK', async () => {
    const { m } = await build()
    const zip = await openZip(m.blob())
    const sizes: number[] = []
    for await (const c of zip.stream('data/n2/größe ü.bin')) sizes.push(c.length)
    expect(Math.max(...sizes)).toBe(READ_CHUNK)
    expect(sizes.length).toBe(3)
  })

  it('refuses a flipped payload byte at the end of the read (crc_mismatch)', async () => {
    const { m } = await build()
    const bytes = new Uint8Array(await m.blob().arrayBuffer())
    const at = 200 // inside the first entries' data
    bytes[at] ^= 0xff
    const zip = await openZip(new Blob([bytes]))
    const hit = zip.entries.filter((e) => e.offset <= at).pop()!
    await expectCode(collect(zip.stream(hit.name)), 'crc_mismatch')
  })

  it('refuses a truncated file', async () => {
    const { m } = await build()
    const bytes = new Uint8Array(await m.blob().arrayBuffer())
    await expectCode(openZip(new Blob([bytes.subarray(0, bytes.length - 30)])), 'not_zip')
  })
})

describe('what the writer refuses', () => {
  it('aborts on a source that delivers a different number of bytes than declared', async () => {
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await expectCode(w.addFile('a', 5, [text('abc')]), 'size_mismatch')
    await expectCode(w.addFile('b', 2, [text('abc')]), 'size_mismatch')
  })

  it('refuses a duplicate path and any path that is not plain relative', async () => {
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('a/b', text('x'))
    await expectCode(w.addBytes('a/b', text('y')), 'duplicate')
    for (const bad of ['', '/abs', '../up', 'a/../b', 'a\\b', 'a//b']) await expectCode(w.addBytes(bad, text('x')), 'corrupt')
  })

  it('abort() aborts the sink and never closes it', async () => {
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('a', text('x'))
    await w.abort(new Error('stop'))
    expect(m.state).toEqual({ closed: false, aborted: true })
  })
})

describe('what the reader refuses', () => {
  const zipOf = async (mutate: (b: Uint8Array) => void) => {
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('a', text('hello'))
    await w.finish()
    const b = new Uint8Array(await m.blob().arrayBuffer())
    mutate(b)
    return new Blob([b])
  }
  it('a file that is not a ZIP', async () => {
    await expectCode(openZip(new Blob([text('this is just some text, long enough to read')])), 'not_zip')
  })
  it('a compressed entry (method 8 in the directory)', async () => {
    const blob = await zipOf((b) => {
      const dv = new DataView(b.buffer)
      for (let i = 0; i + 4 < b.length; i++) if (dv.getUint32(i, true) === 0x02014b50) dv.setUint16(i + 10, 8, true)
    })
    await expectCode(openZip(blob), 'unsupported')
  })
  it('an entry listed twice', async () => {
    const m = memorySink()
    const w = new ZipWriter(m.sink)
    await w.addBytes('a', text('1'))
    await w.addBytes('b', text('2'))
    await w.finish()
    const b = new Uint8Array(await m.blob().arrayBuffer())
    const dv = new DataView(b.buffer)
    // rename the second directory entry 'b' to 'a'
    let seen = 0
    for (let i = 0; i + 46 < b.length; i++) if (dv.getUint32(i, true) === 0x02014b50 && ++seen === 2) b[i + 46] = 'a'.charCodeAt(0)
    await expectCode(openZip(new Blob([b])), 'duplicate')
  })
  it('an entry the directory does not list', async () => {
    const zip = await openZip(await zipOf(() => {}))
    await expectCode(collect(zip.stream('nope')), 'missing')
    await expectCode(zip.bytes('nope', 10), 'missing')
  })
  it('a small entry read whole above its limit', async () => {
    const zip = await openZip(await zipOf(() => {}))
    await expectCode(zip.bytes('a', 2), 'unsupported')
  })
})
