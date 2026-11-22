// The file facts through the web's own adapters (`sniff.ts`, `meta.ts`' `downloadName`, `export/archiveName.ts`) against
// contracts/platform/files/v1-vectors.json (ADR 0155 decision 7.2): what the web calls answers every case the core's
// FileFactsVectorsTest answers, so a rule mutated in Kotlin turns this red too. The adapters hold no rule; this holds the
// mapping (null → undefined, the meta object, the fallback made only where needed).
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { claimsSvg, claimsText, extensionOf, sniffImage, sniffPdf, sniffStream, sniffSvg, sniffText } from './sniff'
import { downloadName, type BlobMeta } from './meta'
import { archiveName, foldArchiveName, isArchiveName, isArchivePath } from '../export/archiveName'

type Case = { [k: string]: any } // the vectors are JSON, read case by case
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/platform/files/v1-vectors.json'), 'utf8')) as Record<string, Case[]>

const bytes = (hex: string) => Uint8Array.from(hex.match(/../g) ?? [], (h) => parseInt(h, 16))

describe('sniff', () => {
  vectors.sniff.forEach((c, i) => it(`#${i} ${c.rule}`, () => {
    const head = bytes(c.hex)
    expect({
      image: sniffImage(head) ?? null, stream: sniffStream(head) ?? null, pdf: sniffPdf(head), text: sniffText(head) ?? null,
      textComplete: sniffText(head, true) ?? null, svg: sniffSvg(head),
    }).toEqual(c.expected)
  }))
})

describe('claims and extension', () => {
  vectors.claims.forEach((c, i) => it(`claims #${i}`, () => expect({ text: claimsText(c.type, c.name), svg: claimsSvg(c.type, c.name) }).toEqual(c.expected)))
  vectors.extension.forEach((c, i) => it(`extension #${i}`, () => expect(extensionOf(c.name)).toBe(c.expected)))
})

describe('downloadName', () => {
  vectors.downloadName.forEach((c, i) => it(`#${i}`, () => {
    // the blob's meta as an opened one carries it: a type and a name only where the case has them (the case's null is none)
    const meta = c.sealedName === null && c.type === null ? null
      : ({ ...(c.type !== null ? { type: c.type } : {}), ...(c.sealedName !== null ? { name: c.sealedName } : {}) } as unknown as BlobMeta)
    expect(downloadName(c.objectName, meta, () => c.fallback)).toBe(c.expected)
  }))
})

describe('the archive\'s names', () => {
  vectors.archiveName.forEach((c, i) => it(`#${i} ${c.rule}`, () => {
    const taken = new Set<string>()
    expect(c.names.map((n: string) => archiveName(n, taken))).toEqual(c.expected)
    expect(taken.has(foldArchiveName(c.expected[0]))).toBe(true)
  }))
  vectors.isArchiveName.forEach((c, i) => it(`isArchiveName #${i}`, () => expect(isArchiveName(c.segment)).toBe(c.expected)))
  vectors.isArchivePath.forEach((c, i) => it(`isArchivePath #${i}`, () => expect(isArchivePath(c.path)).toBe(c.expected)))
})
