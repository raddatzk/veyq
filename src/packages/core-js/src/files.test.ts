import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'files.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const f = await import('@veyq/core-js/files')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/files/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const v = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/files/v1-vectors.json'), 'utf8'))

const bytes = (hex: string) => Uint8Array.from(hex.match(/../g) ?? [], (h) => parseInt(h, 16))

// The file facts' façade against every section of the vectors (ADR 0155 decision 6): the shipped module answers every case
// the core's FileFactsVectorsTest answers on the JVM and natively.
describe('sniff', () => {
  ;(v.sniff as any[]).forEach((c, i) => {
    it(`#${i} ${c.rule}`, () => {
      // read from the middle of a larger buffer: the façade must read the view, not its whole buffer
      const raw = bytes(c.hex)
      const outer = new Uint8Array(raw.length + 8).fill(0x7a)
      outer.set(raw, 3)
      const head = outer.subarray(3, 3 + raw.length)
      const stream = f.sniffStream(head)
      expect({
        image: f.sniffImage(head) ?? null, stream: stream ? { element: stream.element, type: stream.type } : null, pdf: f.sniffPdf(head),
        text: f.sniffText(head, false) ?? null, textComplete: f.sniffText(head, true) ?? null, svg: f.sniffSvg(head),
      }).toEqual(c.expected)
    })
  })
})

describe('claims and extension', () => {
  ;(v.claims as any[]).forEach((c, i) => it(`claims #${i}`, () => {
    expect({ text: f.claimsText(c.type, c.name), svg: f.claimsSvg(c.type, c.name) }).toEqual(c.expected)
  }))
  ;(v.extension as any[]).forEach((c, i) => it(`extension #${i}`, () => expect(f.extensionOf(c.name)).toBe(c.expected)))
})

describe('downloadName', () => {
  ;(v.downloadName as any[]).forEach((c, i) => it(`#${i}`, () => {
    expect(f.downloadName(c.objectName, c.sealedName, c.type, c.fallback)).toBe(c.expected)
  }))
  it('answers null where no name and no fallback is given, so a fallback is made only where it is needed', () => {
    expect(f.downloadName(' ', null, 'image/png', null)).toBeNull()
    expect(f.downloadName(null, 'Sealed', 'image/png', null)).toBe('Sealed.png')
  })
  it('names a type exactly as sealed, never through the prototype', () => {
    expect(f.typeExtension('audio/flac')).toBe('flac')
    expect(f.typeExtension('toString')).toBeNull()
    expect(f.typeExtension('IMAGE/PNG')).toBeNull()
  })
})

describe('the archive\'s names', () => {
  ;(v.archiveName as any[]).forEach((c, i) => it(`#${i} ${c.rule}`, () => {
    const taken = new Set<string>()
    expect(c.names.map((n: string) => f.archiveName(n, taken))).toEqual(c.expected)
    expect([...taken]).toEqual(c.expected.map((n: string) => f.foldArchiveName(n)))
  }))
  ;(v.isArchiveName as any[]).forEach((c, i) => it(`isArchiveName #${i}`, () => expect(f.isArchiveName(c.segment)).toBe(c.expected)))
  ;(v.isArchivePath as any[]).forEach((c, i) => it(`isArchivePath #${i}`, () => expect(f.isArchivePath(c.path)).toBe(c.expected)))
  it('the manifest\'s directory', () => expect(f.archiveManifestDir()).toBe('.veyq'))
})
