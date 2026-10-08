// `openSaveTarget` picks a picked file over the SW's download route over an in-memory Blob, opens the picker before it
// awaits anything else (the click's activation), keeps a cancelled picker silent, and refuses a Blob over the cap
// rather than building a multi-GB one.
import { describe, it, expect, vi } from 'vitest'
import { BLOB_SAVE_CAP, SaveCancelled, SaveTooLarge, blobSink, isSaveCancelled, openSaveTarget } from './saveStream'
import type { ByteSink } from '../core/blob/stream'

vi.mock('../sw/register', () => ({ registerDownload: vi.fn(async () => null) }))

function fakeWritable() {
  const calls: string[] = []
  const written: number[] = []
  return {
    calls,
    written,
    writable: {
      write: async (b: Uint8Array) => void written.push(...b),
      close: async () => void calls.push('close'),
      abort: async () => void calls.push('abort'),
    },
  }
}

const swSink: ByteSink = { write: async () => {}, close: async () => {}, abort: async () => {} }
const noSw = vi.fn(async () => null)

describe('openSaveTarget', () => {
  it('opens the save-file picker first, synchronously in the call — before any other await', async () => {
    const order: string[] = []
    const w = fakeWritable()
    const picker = vi.fn(async () => {
      order.push('picker')
      return { createWritable: async () => w.writable }
    })
    const registerDownload = vi.fn(async () => (order.push('sw'), swSink))
    const pending = openSaveTarget('a.txt', 3, { picker, registerDownload })
    // Not one microtask has run: a picker called after an await would have lost the click's activation.
    expect(picker).toHaveBeenCalledWith({ suggestedName: 'a.txt' })
    const sink = await pending
    expect(order).toEqual(['picker'])
    await sink.write(new Uint8Array([1, 2, 3]))
    await sink.close()
    expect(w.written).toEqual([1, 2, 3])
    expect(w.calls).toEqual(['close'])
  })

  it('a picked file is discarded with abort(), never closed, when the stream fails', async () => {
    const w = fakeWritable()
    const sink = await openSaveTarget('a.txt', 3, { picker: async () => ({ createWritable: async () => w.writable }), registerDownload: noSw })
    await sink.abort(new Error('boom'))
    expect(w.calls).toEqual(['abort'])
  })

  it('a dismissed picker is SaveCancelled — silent — and tries no other target', async () => {
    const registerDownload = vi.fn(async () => swSink)
    const picker = async () => { throw new DOMException('The user aborted a request.', 'AbortError') }
    const e = await openSaveTarget('a.txt', 3, { picker, registerDownload }).catch((x: unknown) => x)
    expect(e).toBeInstanceOf(SaveCancelled)
    expect(isSaveCancelled(e)).toBe(true)
    expect(registerDownload).not.toHaveBeenCalled()
  })

  it('a picker that refuses for another reason (activation lost, a policy) falls through to the SW route', async () => {
    const registerDownload = vi.fn(async () => swSink)
    const picker = async () => { throw new DOMException('Must be handling a user gesture', 'SecurityError') }
    expect(await openSaveTarget('a.txt', 3, { picker, registerDownload })).toBe(swSink)
    expect(registerDownload).toHaveBeenCalledWith({ name: 'a.txt', size: 3 })
  })

  it('without a picker, the SW download route is used when a SW at our protocol answers', async () => {
    const registerDownload = vi.fn(async () => swSink)
    expect(await openSaveTarget('big.iso', 8 * BLOB_SAVE_CAP, { picker: null, registerDownload })).toBe(swSink)
  })

  it('with no streaming target, a file of exactly the cap is saved as a Blob', async () => {
    const saveBlob = vi.fn()
    const sink = await openSaveTarget('a.bin', BLOB_SAVE_CAP, { picker: null, registerDownload: noSw, saveBlob })
    await sink.write(new Uint8Array([7]))
    await sink.close()
    expect(saveBlob).toHaveBeenCalledOnce()
    const [blob, name] = saveBlob.mock.calls[0] as [Blob, string]
    expect(name).toBe('a.bin')
    expect(blob.type).toBe('application/octet-stream')
    expect(Array.from(new Uint8Array(await blob.arrayBuffer()))).toEqual([7])
  })

  it('with no streaming target, one byte over the cap is SaveTooLarge — never a silent multi-GB Blob', async () => {
    const saveBlob = vi.fn()
    const e = await openSaveTarget('a.bin', BLOB_SAVE_CAP + 1, { picker: null, registerDownload: noSw, saveBlob }).catch((x: unknown) => x)
    expect(e).toBeInstanceOf(SaveTooLarge)
    expect((e as SaveTooLarge).size).toBe(BLOB_SAVE_CAP + 1)
    expect(isSaveCancelled(e)).toBe(false)
    expect(saveBlob).not.toHaveBeenCalled()
  })
})

describe('blobSink', () => {
  it('saves nothing when aborted', async () => {
    const saveBlob = vi.fn()
    const sink = blobSink('a', 4, saveBlob)
    await sink.write(new Uint8Array([1, 2]))
    await sink.abort()
    expect(saveBlob).not.toHaveBeenCalled()
  })

  it('refuses to grow past the size the cap was checked against', async () => {
    const sink = blobSink('a', 2, vi.fn())
    await sink.write(new Uint8Array([1, 2]))
    await expect(sink.write(new Uint8Array([3]))).rejects.toThrow('save_overrun')
  })
})
