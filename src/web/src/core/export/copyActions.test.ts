// "Copy to…" as core's action (uc.platform.copy-node, cr-copy-node Decisions 1 and 5): a member flow, for every kind this build
// copies, placed beside the application's "Move to…".
import { describe, it, expect, beforeAll } from 'vitest'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { mergeHeaderActions, type NodeHeaderAction } from '../node/nodeHeader'
import { mergeRowActions } from '../node/levelRow'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { canCopy, copiesKind, copyHeaderActions, copyRowActions } from './copyActions'

const unlocked = memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) })
const t = (k: string) => k
const hooks = { exportContent: async () => null, importContent: async () => ({ nodeId: 'x', nodeKey: new Uint8Array(0) }) }

beforeAll(() => {
  if (!registry.kind('CP_STREAMS').supported) {
    registry.registerKind({ kind: 'CP_STREAMS', icon: 'x', shareable: false, ...hooks, importStreams: true })
    registry.registerKind({ kind: 'CP_WHOLE', icon: 'x', shareable: false, ...hooks }) // its import needs whole payloads
    registry.registerKind({ kind: 'CP_AS_FOLDER', icon: 'x', shareable: false, exportContent: hooks.exportContent, importsAsFolder: true })
    registry.registerKind({ kind: 'CP_NO_IMPORT', icon: 'x', shareable: false, exportContent: hooks.exportContent, importStreams: true })
    registry.registerKind({ kind: 'CP_NONE', icon: 'x', shareable: false })
  }
})

describe('canCopy', () => {
  it('a member with open keys copies a folder, a kind whose import takes a payload by frame, and one that imports as a folder', () => {
    for (const kind of [FOLDER_KIND, 'CP_STREAMS', 'CP_AS_FOLDER']) expect(canCopy(unlocked, { kind }), kind).toBe(true)
  })

  it('fails closed on a kind it cannot copy: an import that needs whole payloads, no import, no hooks, nobody registered', () => {
    for (const kind of ['CP_WHOLE', 'CP_NO_IMPORT', 'CP_NONE', 'NOBODY']) {
      expect(copiesKind(kind), kind).toBe(false)
      expect(canCopy(unlocked, { kind }), kind).toBe(false)
    }
  })

  it('never for a guest, whatever the role (a copy is a member flow, as the import)', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY']) {
      for (const kind of [FOLDER_KIND, 'CP_STREAMS']) expect(canCopy(guestAccessOf(role), { kind }), `${role} ${kind}`).toBe(false)
    }
  })

  it('not with locked keys, nor while the scope is pending deletion (a copy writes), nor without a node', () => {
    expect(canCopy(memberAccessOf(), { kind: FOLDER_KIND })).toBe(false)
    expect(canCopy(memberAccessOf({ epochs: epochsOf(new Uint8Array(32)), readonly: true }), { kind: FOLDER_KIND })).toBe(false)
    expect(canCopy(unlocked, null)).toBe(false)
  })
})

describe('the action', () => {
  const app = (...ids: string[]): NodeHeaderAction[] => ids.map((id) => ({ id, label: id, icon: 'x', onClick: () => {} }))

  it('is one "Copy to…" for header and row alike, placed right after the application\'s move, that calls back', () => {
    let called = 0
    const header = copyHeaderActions(t, unlocked, { id: 'n', kind: FOLDER_KIND }, () => called++)
    const row = copyRowActions(t, unlocked, { id: 'n', kind: FOLDER_KIND }, () => called++)
    for (const [a, ...rest] of [header, row]) {
      expect(rest).toEqual([])
      expect(a).toMatchObject({ id: 'copy', label: 'node.copy.action', icon: 'pi pi-copy', placement: { after: 'move' } })
      a.onClick()
    }
    expect(called).toBe(2)
    expect(mergeHeaderActions(header, app('share', 'rename', 'move', 'delete')).map((a) => a.id)).toEqual(['share', 'rename', 'move', 'copy', 'delete'])
    expect(mergeRowActions(row, app('download', 'move', 'delete')).map((a) => a.id)).toEqual(['download', 'move', 'copy', 'delete'])
  })

  it('goes after the application\'s actions where it offers no move; core\'s before / after stay where they were', () => {
    const [copy] = copyRowActions(t, unlocked, { id: 'n', kind: FOLDER_KIND }, () => {})
    const before = { ...app('heart')[0], placement: 'before' as const }
    const after = { ...app('download')[0], placement: 'after' as const }
    expect(mergeRowActions([before, copy, after], app('rename', 'delete')).map((a) => a.id)).toEqual(['heart', 'rename', 'delete', 'copy', 'download'])
    expect(mergeRowActions([before, copy, after], app('rename', 'move')).map((a) => a.id)).toEqual(['heart', 'rename', 'move', 'copy', 'download'])
  })

  it('is not there for a guest', () => {
    expect(copyRowActions(t, guestAccessOf('READ_WRITE'), { id: 'n', kind: FOLDER_KIND }, () => {})).toEqual([])
    expect(copyHeaderActions(t, guestAccessOf('READ'), { id: 'n', kind: FOLDER_KIND }, () => {})).toEqual([])
  })
})
