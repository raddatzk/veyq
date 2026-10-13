// CRC-32 (IEEE 802.3, the polynomial every ZIP tool uses), incremental: the export writer and reader feed it chunk by
// chunk while the bytes stream past, so a multi-GB payload is never held to be summed. The table is built once.
const TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

/** Start a running CRC-32. Feed it with {@link crc32Update}, read it with {@link crc32Finish}. */
export const crc32Start = (): number => 0xffffffff

export function crc32Update(state: number, bytes: Uint8Array): number {
  let c = state
  for (let i = 0; i < bytes.length; i++) c = TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  return c >>> 0
}

export const crc32Finish = (state: number): number => (state ^ 0xffffffff) >>> 0

export function crc32(bytes: Uint8Array): number {
  return crc32Finish(crc32Update(crc32Start(), bytes))
}
