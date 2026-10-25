// A downloaded ZIP read in Node (export-import.spec.ts, expenses-pot.spec.ts). The archive is store-only
// (src/web/src/core/export/zip.ts): an entry's bytes sit after its local header unchanged. The central directory gives each
// entry's offset and size; the export's data descriptors are not needed to read it.
import { expect } from '@playwright/test'

/** An archive's entries: path → bytes. */
export type ZipEntries = Map<string, Buffer>

export function readZip(zip: Buffer): ZipEntries {
  let eocd = -1
  for (let i = zip.length - 22; i >= 0; i--) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new Error('no end-of-central-directory record')
  const count = zip.readUInt16LE(eocd + 10)
  let p = zip.readUInt32LE(eocd + 16)
  const out: ZipEntries = new Map()
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory entry')
    expect(zip.readUInt16LE(p + 10), 'a stored entry').toBe(0)
    const size = zip.readUInt32LE(p + 24)
    const nameLength = zip.readUInt16LE(p + 28)
    const next = p + 46 + nameLength + zip.readUInt16LE(p + 30) + zip.readUInt16LE(p + 32)
    const name = zip.subarray(p + 46, p + 46 + nameLength).toString('utf8')
    const offset = zip.readUInt32LE(p + 42)
    const dataAt = offset + 30 + zip.readUInt16LE(offset + 26) + zip.readUInt16LE(offset + 28)
    out.set(name, zip.subarray(dataAt, dataAt + size))
    p = next
  }
  return out
}
