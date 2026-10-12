// What an opened file turns out to be, and how much of it is read to find out (inbox #412). A fake chunk source over plain
// bytes: the decrypt is `transport.ts`'s, this is the decision, the caps and the cancellation.
import { describe, it, expect, vi } from 'vitest'
import { FRAME_OVERHEAD, PLAINTEXT_CHUNK_SIZE } from '../../sw/range'
import { IMAGE_CAP, PDF_CAP, TEXT_CAP, loadFileView, type ViewSource } from './view'

const CHUNK = PLAINTEXT_CHUNK_SIZE
const utf8 = (s: string) => new TextEncoder().encode(s)
const PNG = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d])

/** A source over `bytes`, cut into real-size chunks; `read` records the chunk indices asked for. */
function sourceOf(bytes: Uint8Array): ViewSource & { read: number[] } {
  const chunkCount = Math.max(1, Math.ceil(bytes.length / CHUNK))
  const read: number[] = []
  return {
    read,
    chunkCount,
    byteSize: bytes.length + chunkCount * FRAME_OVERHEAD,
    getPlaintextChunk: async (i) => {
      read.push(i)
      return bytes.slice(i * CHUNK, (i + 1) * CHUNK)
    },
  }
}
/** A source that LOOKS as large as `size` (plaintext) with `head` as chunk 0 and zeros after, without allocating it. */
function hugeSource(head: Uint8Array, size: number): ViewSource & { read: number[] } {
  const chunkCount = Math.ceil(size / CHUNK)
  const read: number[] = []
  return {
    read,
    chunkCount,
    byteSize: size + chunkCount * FRAME_OVERHEAD,
    getPlaintextChunk: async (i) => {
      read.push(i)
      return i === 0 ? head : new Uint8Array(CHUNK)
    },
  }
}
const blobBytes = async (b: Blob) => new Uint8Array(await b.arrayBuffer())

describe('the bytes decide the kind, the claim only narrows', () => {
  it('shows a PNG claimed as text/plain as an image, typed by the SNIFFED type', async () => {
    const v = await loadFileView(sourceOf(PNG), { type: 'text/plain', name: 'notes.txt' })
    expect(v).toMatchObject({ kind: 'image', type: 'image/png' })
    if (v.kind === 'image') {
      expect(v.blob.type).toBe('image/png')
      expect(await blobBytes(v.blob)).toEqual(PNG)
    }
  })

  it('shows a binary claimed as image/png as nothing we render', async () => {
    const binary = Uint8Array.from({ length: 4096 }, (_, i) => (i * 31) % 251)
    expect(await loadFileView(sourceOf(binary), { type: 'image/png', name: 'photo.png' })).toEqual({ kind: 'unsupported', reason: 'format' })
  })

  it('shows utf-8 text, dropping a BOM', async () => {
    const plain = await loadFileView(sourceOf(utf8('Grüße 😀\nzweite Zeile')), { type: 'text/plain' })
    expect(plain).toEqual({ kind: 'text', text: 'Grüße 😀\nzweite Zeile', truncated: false })
    const bom = await loadFileView(sourceOf(Uint8Array.from([0xef, 0xbb, 0xbf, ...utf8('mit BOM')])), { type: 'text/plain' })
    expect(bom).toEqual({ kind: 'text', text: 'mit BOM', truncated: false })
  })

  it('decodes utf-16 when its byte-order mark says so', async () => {
    const le = Uint8Array.from([0xff, 0xfe, 0x68, 0x00, 0xe4, 0x00])
    expect(await loadFileView(sourceOf(le), { name: 'a.txt' })).toEqual({ kind: 'text', text: 'hä', truncated: false })
  })

  it('does not show a binary with NUL bytes as text, even when it claims to be one', async () => {
    const withNul = Uint8Array.from([...utf8('looks like text'), 0, ...utf8('but is not')])
    expect(await loadFileView(sourceOf(withNul), { type: 'text/plain', name: 'a.txt' })).toEqual({ kind: 'unsupported', reason: 'format' })
  })

  it('does not guess a text without a text-ish claim, nor a legacy 8-bit encoding', async () => {
    expect(await loadFileView(sourceOf(utf8('plain words')), { type: 'application/octet-stream', name: 'blob.bin' })).toEqual({ kind: 'unsupported', reason: 'format' })
    expect(await loadFileView(sourceOf(Uint8Array.from([0x63, 0x61, 0x66, 0xe9])), { type: 'text/plain' })).toEqual({ kind: 'unsupported', reason: 'format' })
  })

  it('keeps markup as text: an html payload is a string, never a document', async () => {
    const html = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
    expect(await loadFileView(sourceOf(utf8(html)), { type: 'text/html', name: 'page.html' })).toEqual({ kind: 'text', text: html, truncated: false })
  })

  describe('svg', () => {
    const svg = utf8('<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>')
    it('is an image only when the bytes are svg AND the claim says svg; the blob type is image/svg+xml', async () => {
      const v = await loadFileView(sourceOf(svg), { type: 'image/svg+xml', name: 'logo.svg' })
      expect(v).toMatchObject({ kind: 'image', type: 'image/svg+xml' })
      expect(await loadFileView(sourceOf(svg), { type: '', name: 'logo.svg' })).toMatchObject({ kind: 'image' })
    })
    it('is TEXT when the claim does not say svg (the markup stays visible characters)', async () => {
      expect(await loadFileView(sourceOf(svg), { type: 'text/plain', name: 'logo.txt' })).toMatchObject({ kind: 'text' })
    })
    it('is not an image by claim alone: an html page claimed as image/svg+xml shows as text', async () => {
      const page = utf8('<!doctype html><script>alert(1)</script>')
      expect(await loadFileView(sourceOf(page), { type: 'image/svg+xml', name: 'x.svg' })).toMatchObject({ kind: 'text' })
    })
  })

  describe('a PDF: the bytes are collected whole, pdf.js is the viewer\'s to load', () => {
    const header = utf8('%PDF-1.7\n')
    it('returns every byte of a PDF cut over several chunks, in order, reporting progress', async () => {
      const pdf = new Uint8Array(CHUNK * 2 + 123)
      pdf.set(header)
      for (let i = header.length; i < pdf.length; i++) pdf[i] = (i * 13) % 256
      const progress: number[] = []
      const s = sourceOf(pdf)
      const v = await loadFileView(s, { type: 'application/octet-stream', name: 'x' }, { onProgress: (f) => progress.push(f) })
      expect(v.kind).toBe('pdf')
      if (v.kind === 'pdf') expect(v.bytes).toEqual(pdf)
      expect([...new Set(s.read)].sort()).toEqual([0, 1, 2])
      expect(progress.at(-1)).toBe(1)
    })

    it('is decided by the bytes: a PDF claimed as an image or as text is still a PDF', async () => {
      const pdf = Uint8Array.from([...header, 1, 2, 3, 0xff])
      expect((await loadFileView(sourceOf(pdf), { type: 'image/png', name: 'a.png' })).kind).toBe('pdf')
      expect((await loadFileView(sourceOf(pdf), { type: 'text/plain', name: 'a.txt' })).kind).toBe('pdf')
    })

    it('tolerates junk before the header (within the first KiB) as a reader does, but not a text that mentions it', async () => {
      const junk = Uint8Array.from([...utf8('\n\n  '), 0x00, ...header, 1, 2, 0xff])
      expect((await loadFileView(sourceOf(junk), {})).kind).toBe('pdf')
      expect(await loadFileView(sourceOf(utf8('see %PDF-1.7 in the note')), { type: 'text/plain' })).toMatchObject({ kind: 'text' })
    })

    it('a PDF over the cap is not read past its first chunk', async () => {
      const s = hugeSource(Uint8Array.from([...header, 1, 2]), PDF_CAP + CHUNK)
      expect(await loadFileView(s, { type: 'application/pdf' })).toEqual({ kind: 'too-large', limit: PDF_CAP })
      expect(s.read).toEqual([0])
    })

    it('a file that is larger than its stated size is an error, not a silent truncation', async () => {
      const pdf = Uint8Array.from([...header, 1, 2, 3])
      const s = { ...sourceOf(pdf), byteSize: 10 + FRAME_OVERHEAD } // claims 10 plaintext bytes, delivers 12
      await expect(loadFileView(s, {})).rejects.toThrow(RangeError)
    })

    it('stops reading when aborted', async () => {
      const ctl = new AbortController()
      const pdf = new Uint8Array(CHUNK * 8)
      pdf.set(header)
      const s = sourceOf(pdf)
      const inner = s.getPlaintextChunk
      s.getPlaintextChunk = async (i) => {
        if (i === 1) ctl.abort()
        return inner(i)
      }
      await expect(loadFileView(s, {}, { signal: ctl.signal })).rejects.toBeTruthy()
      expect(Math.max(...s.read)).toBeLessThan(8)
    })
  })

  it.each([
    ['mp4', Uint8Array.from([0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 0]), { element: 'video', type: 'video/mp4' }],
    ['webm', Uint8Array.from([0x1a, 0x45, 0xdf, 0xa3, 0x42, 0x82, 0x84, 0x77, 0x65, 0x62, 0x6d]), { element: 'video', type: 'video/webm' }],
    ['mp3', Uint8Array.from([0x49, 0x44, 0x33, 4, 0]), { element: 'audio', type: 'audio/mpeg' }],
  ])('hands a %s to the media route and reads nothing past chunk 0', async (_n, head, expected) => {
    const s = hugeSource(head, 40 * CHUNK)
    expect(await loadFileView(s, { type: 'application/octet-stream' })).toEqual({ kind: 'stream', ...expected })
    expect(s.read).toEqual([0])
  })

  it('answers an empty file and a blob without chunks', async () => {
    expect(await loadFileView({ chunkCount: 0, byteSize: 0, getPlaintextChunk: vi.fn() }, {})).toEqual({ kind: 'unsupported', reason: 'empty' })
    expect(await loadFileView({ chunkCount: 1, byteSize: FRAME_OVERHEAD, getPlaintextChunk: async () => new Uint8Array(0) }, {})).toEqual({ kind: 'unsupported', reason: 'empty' })
  })
})

describe('size caps', () => {
  it('an image over the cap is not read past chunk 0', async () => {
    const s = hugeSource(PNG, IMAGE_CAP + 1)
    expect(await loadFileView(s, { type: 'image/png' })).toEqual({ kind: 'too-large', limit: IMAGE_CAP })
    expect(s.read).toEqual([0])
  })

  it('an image AT the cap is shown (the boundary is inclusive)', async () => {
    const s = hugeSource(PNG, IMAGE_CAP)
    expect(await loadFileView(s, { type: 'image/png' })).toMatchObject({ kind: 'image' })
    expect(s.read.length).toBe(s.chunkCount)
  })

  it('a text over the cap shows its first TEXT_CAP bytes, says so, and reads only the chunks that covers', async () => {
    const line = 'abcdefghi\n'
    const bytes = utf8(line.repeat(Math.ceil((TEXT_CAP * 3) / line.length)))
    const s = sourceOf(bytes)
    const v = await loadFileView(s, { type: 'text/plain' })
    expect(v.kind).toBe('text')
    if (v.kind === 'text') {
      expect(v.truncated).toBe(true)
      expect(new TextEncoder().encode(v.text).length).toBe(TEXT_CAP)
    }
    expect(s.read).toEqual([0, 1, 2, 3])
    expect(s.chunkCount).toBeGreaterThan(8)
  })

  it('a text of exactly TEXT_CAP bytes is whole, not truncated', async () => {
    const v = await loadFileView(sourceOf(utf8('x'.repeat(TEXT_CAP))), { type: 'text/plain' })
    expect(v).toMatchObject({ kind: 'text', truncated: false })
  })

  it('the cap cutting a multi-byte character in half drops the half, it prints no replacement character', async () => {
    const bytes = utf8('a'.repeat(TEXT_CAP - 1) + '😀' + 'tail')
    const v = await loadFileView(sourceOf(bytes), { type: 'text/plain' })
    expect(v).toMatchObject({ kind: 'text', truncated: true })
    if (v.kind === 'text') expect(v.text).toBe('a'.repeat(TEXT_CAP - 1))
  })
})

describe('cancellation and failure', () => {
  it('a signal aborted while chunks are read stops the reading and rejects', async () => {
    const ctl = new AbortController()
    const bytes = new Uint8Array(40 * CHUNK)
    bytes.set(PNG)
    const s = sourceOf(bytes)
    const inner = s.getPlaintextChunk
    s.getPlaintextChunk = async (i) => {
      if (i === 5) ctl.abort()
      return inner(i)
    }
    await expect(loadFileView(s, { type: 'image/png' }, { signal: ctl.signal })).rejects.toBeDefined()
    expect(Math.max(...s.read)).toBeLessThan(15) // the pump's window, not the 40 chunks
  })

  it('an already-aborted signal reads nothing', async () => {
    const ctl = new AbortController()
    ctl.abort()
    const s = sourceOf(PNG)
    await expect(loadFileView(s, {}, { signal: ctl.signal })).rejects.toBeDefined()
    expect(s.read).toEqual([])
  })

  it('a chunk that does not open rejects (the caller shows its error state)', async () => {
    const s: ViewSource = { chunkCount: 1, byteSize: 100, getPlaintextChunk: async () => { throw new Error('open_refused') } }
    await expect(loadFileView(s, {})).rejects.toThrow('open_refused')
  })

  it('reports progress while an image is collected', async () => {
    const bytes = new Uint8Array(4 * CHUNK)
    bytes.set(PNG)
    const seen: number[] = []
    await loadFileView(sourceOf(bytes), { type: 'image/png' }, { onProgress: (f) => seen.push(f) })
    expect(seen).toEqual([0.25, 0.5, 0.75, 1])
  })
})
