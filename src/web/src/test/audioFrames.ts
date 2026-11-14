// Generated MP3 and ADTS frame streams for the music tests (uc.memory.background-music): valid headers in a row, the
// frame bodies zero - what the frame walk and the tail alignment read, never what a decoder would play.

/** An MPEG-1 Layer III frame at 128 kbit/s, 44.1 kHz, no padding: `FF FB 90 00`, 417 bytes. */
export const MP3_FRAME = 417
/** One ADTS frame at 44.1 kHz carrying one raw block (1024 samples), `length` bytes with its 7-byte header. */
export function adtsHeader(length: number): number[] {
  return [0xff, 0xf1, (1 << 6) | (4 << 2), 0x80 | ((length >> 11) & 0x03), (length >> 3) & 0xff, ((length & 0x07) << 5) | 0x1f, 0xfc]
}

/** `bytes` bytes of MP3 frames from offset `lead` (what comes before is zeros, as a tag's body). */
export function mp3Stream(bytes: number, lead = 0): Uint8Array {
  const b = new Uint8Array(bytes)
  for (let at = lead; at + 4 <= bytes; at += MP3_FRAME) b.set([0xff, 0xfb, 0x90, 0x00], at)
  return b
}

/** `frames` ADTS frames of `length` bytes each, then `trailer` bytes of a tag. */
export function adtsStream(frames: number, length = 512, trailer = 0): Uint8Array {
  const b = new Uint8Array(frames * length + trailer)
  for (let i = 0; i < frames; i++) b.set(adtsHeader(length), i * length)
  if (trailer) b.set([0x54, 0x41, 0x47], frames * length) // "TAG"
  return b
}
