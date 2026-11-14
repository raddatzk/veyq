// A WAV file's samples at both ends, read straight from the file (ADR 0135 decision 4): its `fmt ` and `data` chunks are
// parsed from its head, and only the bytes of the first and the last `limitMs` are sliced out of the file and converted -
// a 200 MiB WAV costs a few MiB here, where a decode cost 1.4 GiB. Integer PCM of 8, 16, 24 and 32 bit and 32-bit float,
// plain or as WAVE_FORMAT_EXTENSIBLE; anything else throws, and the caller then decides without it. Pure but for
// `Blob.slice`; no key, no request.
import type { DecodedAudio } from './audioFacts'

/** What the scan needs of a WAV's header. */
export type WavFormat = { channels: number; sampleRate: number; bitsPerSample: number; float: boolean; blockAlign: number; dataOffset: number; dataBytes: number }

/** How much of the head is read to find the `data` chunk, at most (a `LIST` chunk of tags may stand before it). */
const HEADER_MAX = 1024 * 1024
const PCM = 1
const IEEE_FLOAT = 3
const EXTENSIBLE = 0xfffe

const ascii = (b: Uint8Array, at: number) => String.fromCharCode(b[at], b[at + 1], b[at + 2], b[at + 3])

/** Parse the header in `head` (the file's first bytes) for a file of `fileSize` bytes; null where `data` lies beyond it. */
export function parseWavHeader(head: Uint8Array, fileSize: number): WavFormat | null {
  if (head.length < 12 || ascii(head, 0) !== 'RIFF' || ascii(head, 8) !== 'WAVE') throw new Error('not_wav')
  const view = new DataView(head.buffer, head.byteOffset, head.byteLength)
  let fmt: Omit<WavFormat, 'dataOffset' | 'dataBytes'> | null = null
  let at = 12
  while (at + 8 <= head.length) {
    const id = ascii(head, at)
    const size = view.getUint32(at + 4, true)
    const body = at + 8
    if (id === 'fmt ') {
      if (body + 16 > head.length) return null
      let format = view.getUint16(body, true)
      const channels = view.getUint16(body + 2, true)
      const sampleRate = view.getUint32(body + 4, true)
      const blockAlign = view.getUint16(body + 12, true)
      const bitsPerSample = view.getUint16(body + 14, true)
      if (format === EXTENSIBLE && size >= 40 && body + 26 <= head.length) format = view.getUint16(body + 24, true)
      const float = format === IEEE_FLOAT
      if (!(format === PCM && [8, 16, 24, 32].includes(bitsPerSample)) && !(float && bitsPerSample === 32)) throw new Error('wav_format')
      if (channels < 1 || sampleRate < 1 || blockAlign !== channels * (bitsPerSample / 8)) throw new Error('wav_format')
      fmt = { channels, sampleRate, bitsPerSample, float, blockAlign }
    } else if (id === 'data') {
      if (!fmt) throw new Error('wav_format')
      // a writer that streamed the file may leave the size at 0 or past the end: the data then runs to the file's end
      const dataBytes = size === 0 || body + size > fileSize ? fileSize - body : size
      return { ...fmt, dataOffset: body, dataBytes: dataBytes - (dataBytes % fmt.blockAlign) }
    }
    at = body + size + (size % 2) // chunks are word-aligned
  }
  return null
}

/** Frames of `bytes` (whole blocks) as samples per channel in [-1, 1]. */
export function wavSamples(bytes: Uint8Array, fmt: WavFormat): DecodedAudio {
  const frames = Math.floor(bytes.length / fmt.blockAlign)
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const data = Array.from({ length: fmt.channels }, () => new Float32Array(frames))
  const width = fmt.bitsPerSample / 8
  const read = (o: number): number => {
    if (fmt.float) return view.getFloat32(o, true)
    switch (fmt.bitsPerSample) {
      case 8: return (view.getUint8(o) - 128) / 128
      case 16: return view.getInt16(o, true) / 32768
      case 24: return (((view.getUint8(o + 2) << 24) | (view.getUint8(o + 1) << 16) | (view.getUint8(o) << 8)) >> 8) / 8388608
      default: return view.getInt32(o, true) / 2147483648
    }
  }
  for (let f = 0; f < frames; f++) {
    for (let c = 0; c < fmt.channels; c++) data[c][f] = read(f * fmt.blockAlign + c * width)
  }
  return { numberOfChannels: fmt.channels, sampleRate: fmt.sampleRate, length: frames, getChannelData: (c) => data[c] }
}

/** The samples of the first and the last `limitMs` of a WAV file's data (both the whole data where it is shorter). */
export async function readWavEnds(file: Blob, limitMs: number): Promise<{ head: DecodedAudio; tail: DecodedAudio }> {
  const headOf = async (size: number) => parseWavHeader(new Uint8Array(await file.slice(0, size).arrayBuffer()), file.size)
  const fmt = (await headOf(64 * 1024)) ?? (await headOf(HEADER_MAX))
  if (!fmt) throw new Error('wav_no_data')
  const want = Math.min(fmt.dataBytes, Math.ceil((fmt.sampleRate * limitMs) / 1000) * fmt.blockAlign)
  const end = fmt.dataOffset + fmt.dataBytes
  const bytesAt = async (from: number) => new Uint8Array(await file.slice(from, from + want).arrayBuffer())
  return { head: wavSamples(await bytesAt(fmt.dataOffset), fmt), tail: wavSamples(await bytesAt(end - want), fmt) }
}
