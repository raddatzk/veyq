// The tree's client cost at volume (cr-volume-measurement-local): `openTree` over synthetic sealed rows in
// the two shapes the volume harness seeds — the realistic one (Folders : Memories : Chapters ≈ 1 : 5 : 20) at 500 and
// at 5,000, and the wide one (2,000 Chapters under one Memory), the worst case for the per-sibling list building.
// `npx vitest bench src/core/node/tree.bench.ts`; `vitest run` does not collect bench files, so `npm run check` is
// unchanged. TEMPORARY: `cr-volume-measurement-cax` deletes it with the rest of the harness.
//
// The control: if the realistic shape costs about 10x as much at 5,000 as at 500, the quadratic terms
// in `openEntries` (the child lists copied per row, the queue taken from the front) do not matter yet at this size.
import { bench, describe, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import type { NodeSummary } from '../../api/generated/content'
import type { EpochKeys } from '../../features/spaces/epochs'
import { openTree, sealName, wrapContentKey } from './tree'
import { FOLDER_KIND } from './kind'

const SCOPE = '00000000-0000-4000-8000-000000000001'
const epochKey = v1.randomBytes(32)
const epochs: EpochKeys = { current: 0, keys: new Map([[0, epochKey]]) }

// Every row is a FOLDER: `openTree` never reads a kind (it opens entries and keys along the parent chain), and core may not
// name an application's kinds (`validate_web_core_imports_no_applications.py`). The SHAPE is the seeder's.
type Spec = { kind: string; name: string; parent: number | null }

/** Rows as the server lists them, sealed the way `createContentNode` seals them: each entry under its parent's
 *  content key (`K_SC` at the root), each content key wrapped under the same. */
function rows(specs: Spec[]): NodeSummary[] {
  const ids = specs.map(() => crypto.randomUUID())
  const keys = specs.map(() => v1.randomBytes(32))
  return specs.map((s, i) => {
    const entryKey = s.parent === null ? epochKey : keys[s.parent]
    const node = { scopeId: SCOPE, nodeId: ids[i], epoch: 0, placementRevision: 0 }
    return {
      id: ids[i], parentId: s.parent === null ? null : ids[s.parent], kind: s.kind, orderIndex: 0, epoch: 0,
      encryptedName: sealName(entryKey, node, s.name), slugToken: 'unused-by-openTree',
      wrappedContentKey: wrapContentKey(entryKey, node, keys[i]), wrappedMeta: null, metaRevision: 0, placementRevision: 0,
      createdAt: new Date(1_700_000_000_000 + i), blockedByOperator: false,
    }
  })
}

/** The seeder's realistic shape, in its creation order, cut at `n` rows (`web/scripts/seed-volume.mts`). */
function realistic(n: number): Spec[] {
  const out: Spec[] = []
  for (let f = 0; out.length < n; f++) {
    const folder = out.push({ kind: FOLDER_KIND, name: `Folder ${f}`, parent: null }) - 1
    for (let m = 0; m < 5 && out.length < n; m++) {
      const memory = out.push({ kind: FOLDER_KIND, name: `Memory ${f}-${m}`, parent: folder }) - 1
      for (let c = 0; c < 4 && out.length < n; c++) out.push({ kind: FOLDER_KIND, name: `Chapter ${f}-${m}-${c}`, parent: memory })
    }
  }
  return out
}

function wide(chapters: number): Spec[] {
  return [{ kind: FOLDER_KIND, name: 'Wide memory', parent: null },
    ...Array.from({ length: chapters }, (_, c) => ({ kind: FOLDER_KIND, name: `Chapter ${c}`, parent: 0 }))]
}

const shapes = { 'realistic 500': rows(realistic(500)), 'realistic 5,000': rows(realistic(5000)), 'wide 2,000': rows(wide(2000)) }
for (const [name, r] of Object.entries(shapes)) {
  expect(openTree(SCOPE, epochs, r).length, `${name}: every row opens`).toBe(r.length)
}

describe('openTree', () => {
  for (const [name, r] of Object.entries(shapes)) {
    bench(`${name} rows`, () => { openTree(SCOPE, epochs, r) }, { iterations: 5, warmupIterations: 1, time: 0 })
  }
})
