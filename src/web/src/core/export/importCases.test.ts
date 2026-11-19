// @vitest-environment happy-dom
// The import cases both clients answer to (ADR 0152 decision 4): `contracts/export/import-cases/cases.v1.json`, written by
// its `generate.py` from Python's standard library, read here and by the core's `ImportCasesTest`. Each case is a download
// - a ZIP or the same download unpacked - with whether an upload offers it as an import, and either the dry run's totals
// or the refusal's code at the stage that refuses it. The kinds are the registered applications' own (Depot's file and
// drop box), so a case also holds Depot's content checks; the crafted cases of this build's own test kinds stay in
// `importHardening.test.ts`. Happy-dom: the registry is filled only where there is a window (`test/setup.ts`).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../node/tree', async (orig) => ({ ...(await orig<typeof import('../node/tree')>()), createContentNode: vi.fn(), deleteNode: vi.fn() }))

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createContentNode, deleteNode } from '../node/tree'
import type { ScopeKey } from '../../features/spaces/epochs'
import { ImportFailed, importSubtree, isDownloadArchive, openExport, planImport } from './importSubtree'
import { directoryHoldsManifest, type DirectoryInput, type ImportInput } from './importSource'

type ImportCase = {
  id: string
  zip?: string
  directory?: { path: string; bytes: string }[]
  screen: boolean
  expect:
    | { refused: string; at: 'open' | 'plan' | 'import' }
    | { plan: { nodes: number; bytes: number; skipped: { name: string; application: string; reason: string; nodes: number }[] } }
}

const file = JSON.parse(readFileSync(resolve(__dirname, '../../../../../contracts/export/import-cases/cases.v1.json'), 'utf8')) as { cases: ImportCase[] }
const epochs: ScopeKey = { epoch: 1, key: new Uint8Array(32) }
const bytesOf = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))
const inputOf = (c: ImportCase): ImportInput =>
  c.zip !== undefined
    ? { kind: 'zip', file: new File([bytesOf(c.zip) as BlobPart], 'download.zip') }
    : { kind: 'directory', name: 'download', files: c.directory!.map((f) => ({ path: f.path, file: new File([bytesOf(f.bytes) as BlobPart], f.path.split('/').pop()!) })) }

beforeEach(() => {
  let n = 0
  vi.mocked(createContentNode).mockReset().mockImplementation(async () => ({ nodeId: `new${++n}`, contentKey: new Uint8Array(32), epoch: 0, scopeEpoch: 1 }))
  vi.mocked(deleteNode).mockReset().mockResolvedValue(undefined)
})

describe('contracts/export/import-cases', () => {
  it('holds cases of every stage, and of both inputs', () => {
    const stages = new Set(file.cases.map((c) => ('refused' in c.expect ? c.expect.at : 'plan')))
    expect([...stages].sort()).toEqual(['import', 'open', 'plan'])
    expect(file.cases.some((c) => c.directory) && file.cases.some((c) => c.zip)).toBe(true)
  })

  for (const c of file.cases) {
    it(c.id, async () => {
      const input = inputOf(c)
      const screened = input.kind === 'zip' ? await isDownloadArchive(input.file) : directoryHoldsManifest(input as DirectoryInput)
      expect(screened, 'whether an upload offers it as an import').toBe(c.screen)
      const want = c.expect
      if ('plan' in want) {
        const plan = planImport((await openExport(input)).manifest)
        const skipped = plan.skipped.map(({ name, application, reason, nodes }) => ({ name, application, reason, nodes }))
        expect({ nodes: plan.nodes, bytes: plan.bytes, skipped }).toEqual(want.plan)
        return
      }
      if (want.at === 'open') {
        await expect(openExport(input)).rejects.toMatchObject({ code: want.refused })
        return
      }
      const opened = await openExport(input)
      if (want.at === 'plan') {
        expect(() => planImport(opened.manifest)).toThrow(expect.objectContaining({ code: want.refused }))
        return
      }
      const err = await importSubtree({ scopeId: 's', epochs, tree: [], parent: null, opened }).catch((e) => e)
      expect(err instanceof ImportFailed ? err.cause : err).toMatchObject({ code: want.refused })
    })
  }
})
