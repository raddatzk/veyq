// A generated WAV file for the music tests (uc.memory.background-music): parts of digital silence and of a 440 Hz tone, in
// the sample format asked for, with an optional `LIST` chunk before the data as tag writers put one.

export type WavSpec = {
  bits: 8 | 16 | 24 | 32
  channels: number
  rate: number
  /** `[seconds, amplitude]` - 0 is silence. */
  parts: [number, number][]
  float?: boolean
  /** The `fmt ` format code written: 1 PCM, 3 float, 0xfffe extensible (then `float` picks the sub-format), else as given. */
  format?: number
  /** Bytes of a `LIST` chunk written before `data`. */
  listBytes?: number
}

export function wavBytes(spec: WavSpec): Uint8Array {
  const frames = Math.round(spec.parts.reduce((n, [s]) => n + s, 0) * spec.rate)
  const width = spec.bits / 8
  const blockAlign = spec.channels * width
  const extensible = spec.format === 0xfffe
  const fmtSize = extensible ? 40 : 16
  const list = spec.listBytes ?? 0
  const listPad = list % 2 // a chunk of odd size is followed by a pad byte
  const dataBytes = frames * blockAlign
  const total = 12 + 8 + fmtSize + (list ? 8 + list + listPad : 0) + 8 + dataBytes
  const out = new Uint8Array(total)
  const v = new DataView(out.buffer)
  const str = (at: number, s: string) => { for (let i = 0; i < 4; i++) out[at + i] = s.charCodeAt(i) }
  str(0, 'RIFF'); v.setUint32(4, total - 8, true); str(8, 'WAVE')
  let at = 12
  str(at, 'fmt '); v.setUint32(at + 4, fmtSize, true)
  v.setUint16(at + 8, spec.format ?? (spec.float ? 3 : 1), true)
  v.setUint16(at + 10, spec.channels, true); v.setUint32(at + 12, spec.rate, true)
  v.setUint32(at + 16, spec.rate * blockAlign, true); v.setUint16(at + 20, blockAlign, true); v.setUint16(at + 22, spec.bits, true)
  if (extensible) { v.setUint16(at + 24, 22, true); v.setUint16(at + 32, spec.float ? 3 : 1, true) }
  at += 8 + fmtSize
  if (list) { str(at, 'LIST'); v.setUint32(at + 4, list, true); at += 8 + list + listPad }
  str(at, 'data'); v.setUint32(at + 4, dataBytes, true); at += 8
  let frame = 0
  for (const [sec, amp] of spec.parts) {
    const n = Math.round(sec * spec.rate)
    for (let i = 0; i < n; i++, frame++) {
      const x = amp * Math.sin((2 * Math.PI * 440 * i) / spec.rate)
      for (let c = 0; c < spec.channels; c++) {
        const o = at + frame * blockAlign + c * width
        if (spec.float) v.setFloat32(o, x, true)
        else if (spec.bits === 8) v.setUint8(o, Math.round(x * 127) + 128)
        else if (spec.bits === 16) v.setInt16(o, Math.round(x * 32767), true)
        else if (spec.bits === 24) { const s = Math.round(x * 8388607); v.setUint8(o, s & 0xff); v.setUint8(o + 1, (s >> 8) & 0xff); v.setUint8(o + 2, (s >> 16) & 0xff) }
        else v.setInt32(o, Math.round(x * 2147483647), true)
      }
    }
  }
  return out
}

export const wavFile = (spec: WavSpec, name = 'tone.wav') => new File([wavBytes(spec) as BlobPart], name, { type: 'audio/wav' })
