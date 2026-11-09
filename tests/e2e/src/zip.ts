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

// --- an archive written in Node ---------------------------------------------------------------------------------------
// A rewrite of a download (export-import.spec.ts, search.spec.ts): every entry plain (stored, no descriptor, no ZIP64),
// which the browser's reader accepts as it accepts its own.

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (const b of bytes) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

export function writeZip(entries: ZipEntries): Buffer {
  const locals: Buffer[] = []
  const centrals: Buffer[] = []
  let offset = 0
  for (const [name, bytes] of entries) {
    const n = Buffer.from(name, 'utf8')
    const crc = crc32(bytes)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6) // UTF-8 names
    local.writeUInt16LE(0x21, 12) // 1980-01-01
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(bytes.length, 18)
    local.writeUInt32LE(bytes.length, 22)
    local.writeUInt16LE(n.length, 26)
    const central = Buffer.alloc(46)
    central.writeUInt32LE(0x02014b50, 0)
    central.writeUInt16LE(20, 4)
    central.writeUInt16LE(20, 6)
    central.writeUInt16LE(0x0800, 8)
    central.writeUInt16LE(0x21, 14)
    central.writeUInt32LE(crc, 16)
    central.writeUInt32LE(bytes.length, 20)
    central.writeUInt32LE(bytes.length, 24)
    central.writeUInt16LE(n.length, 28)
    central.writeUInt32LE(offset, 42)
    locals.push(local, n, bytes)
    centrals.push(central, n)
    offset += 30 + n.length + bytes.length
  }
  const directory = Buffer.concat(centrals)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.size, 8)
  end.writeUInt16LE(entries.size, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, directory, end])
}
