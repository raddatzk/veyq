// CRC-32 (IEEE 802.3, the polynomial every ZIP tool uses), incremental — the digest worker's own primitive, beside its
// SHA-256: an import's payload check sums a ZIP entry's CRC-32 in the same pass as its hash, off the page (inbox #557,
// `blob/digest.ts`). The archive's own CRC — the one the writer records and the reader checks — is the platform's Kotlin
// `export/Crc32`, drawn through `@veyq/core-js/export` with the rest of the ZIP (ADR 0155 decision 7.2, the export archive's
// slice); a worker importing that entry would carry the whole platform module (~50 KB gz) for these lines (that slice's D2).
// The two answer to one set of cases: `archive.vectors.test.ts` runs contracts/platform/export/v1-vectors.json's `crc32`
// cases over this file and over the core. The table is built once.
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
