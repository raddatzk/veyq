// Depot's rules against contracts/depot/v1-vectors.json, through the web's own glue over `@veyq/core-js/depot` (ADR 0155
// decision 7.3, § Depot's move: the rules are the Kotlin core's) — what an opened file is and how much of it is read
// (`loadFileView`), a picked directory's plan (`planFolderUpload`: its tree of names, its count and bytes, the top's further
// names) and an SVG's canvas (`planRaster`). The zoom steps are the package's own check (`depot.test.ts`). The core answers
// every case of the same file on the JVM and natively (`DepotVectorsTest`); a case that fails here names its section and
// name.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { FRAME_OVERHEAD } from '../../sw/range'
import { TEXT_CAP, loadFileView, type FileView, type ViewSource } from './view'
import { planFolderUpload, type FolderPlan } from './uploadFolder'
import { planRaster } from './svgRaster'
import type { DirectoryInput } from '../../core/export/importSource'

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/depot/v1-vectors.json'), 'utf8')) as any

/** A source of `chunkCount` frames and `total` plaintext bytes whose frame 0 is `head`, every later one `head` again;
 *  `read` records the frames asked for. */
function sourceOf(head: Uint8Array, chunkCount: number, total: number): ViewSource & { read: number[] } {
  const read: number[] = []
  return {
    read, chunkCount, byteSize: total + chunkCount * FRAME_OVERHEAD,
    getPlaintextChunk: async (i) => {
      read.push(i)
      return head
    },
  }
}

/** What the web's view is for a case's expected decision, and the frames it reads to build it. */
function viewOf(e: any, head: Uint8Array, chunkCount: number): { view: Partial<FileView> & { kind: string }; frames: number } {
  switch (e.kind) {
    case 'image': return { view: { kind: 'image', type: e.type }, frames: e.frames }
    case 'stream': return { view: { kind: 'stream', element: e.element, type: e.type }, frames: 1 }
    case 'pdf': return { view: { kind: 'pdf' }, frames: e.frames }
    case 'text': {
      const all = new Uint8Array(head.length * e.frames)
      for (let i = 0; i < e.frames; i++) all.set(head, i * head.length)
      const text = new TextDecoder(e.encoding, { fatal: false }).decode(all.subarray(0, TEXT_CAP), { stream: e.truncated })
      return { view: { kind: 'text', text, truncated: e.truncated }, frames: e.frames }
    }
    case 'too-large': return { view: { kind: 'too-large', limit: e.limit }, frames: 1 }
    case 'empty': return { view: { kind: 'unsupported', reason: 'empty' }, frames: chunkCount < 1 ? 0 : 1 }
    default: return { view: { kind: 'unsupported', reason: 'format' }, frames: 1 }
  }
}

describe('view', () => {
  ;(vectors.view as any[]).forEach((c) => {
    it(c.name, async () => {
      const head = Uint8Array.from(Buffer.from(c.head, 'base64'))
      const source = sourceOf(head, c.chunkCount, c.total)
      const got = await loadFileView(source, { type: c.type, name: c.claimedName })
      const { view, frames } = viewOf(c.expected, head, c.chunkCount)
      const shown = got.kind === 'image' ? { kind: got.kind, type: got.type } : got.kind === 'pdf' ? { kind: got.kind } : got
      expect(shown).toEqual(view)
      expect(new Set(source.read).size).toBe(frames)
    })
  })
})

/** A planned folder as the vectors hold it, its files by name (a renamed file is a new `File`). */
const tree = (f: FolderPlan): any => ({ name: f.name, folders: f.folders.map(tree), files: f.files.map((x) => x.name) })
const expectedTree = (f: any): any => ({ name: f.name, folders: f.folders.map(expectedTree), files: f.files.map((x: any) => x.name) })

describe('folderPlan', () => {
  ;(vectors.folderPlan as any[]).forEach((c) => {
    it(c.name, () => {
      const tokens = new Map<string, string>((c.tokens as string[][]).map(([k, n, t]) => [`${k}\u0000${n}`, t!]))
      const slugFor = (kind: string, name: string) => {
        const t = tokens.get(`${kind}\u0000${name}`)
        if (t === undefined) throw new Error(`asks (${kind}, ${name}), a token the case does not list`)
        return t
      }
      const files = (c.entries as { path: string; size: number }[]).map(({ path, size }) => {
        const file = new File([], path.split('/').pop() ?? '')
        Object.defineProperty(file, 'size', { value: size })
        return { path, file }
      })
      const dir: DirectoryInput = { kind: 'directory', name: c.rootName, files }
      const plan = planFolderUpload(dir, c.siblings, slugFor)
      const next = [1, 2, 3].map(() => plan.nextRootName())
      expect({ root: tree(plan.root), files: plan.files, bytes: plan.bytes, nextRootNames: next })
        .toEqual({ ...c.expected, root: expectedTree(c.expected.root) })
    })
  })
})

describe('raster', () => {
  ;(vectors.raster as any[]).forEach((c, i) => {
    it(`#${i} ${c.width} x ${c.height} at ${c.ratio}`, () => {
      expect(planRaster({ width: c.width, height: c.height }, c.ratio === null ? Number.NaN : c.ratio)).toEqual(c.expected)
    })
  })
})
