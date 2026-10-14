import { describe, it, expect, beforeAll } from 'vitest'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { canExport, exportHeaderActions, exportRowActions } from './exportActions'

const unlocked = memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) })
const t = (k: string) => k

beforeAll(() => {
  if (!registry.kind('X_WITH').supported) {
    registry.registerKind({ kind: 'X_WITH', icon: 'x', shareable: false, exportContent: async () => null })
    registry.registerKind({ kind: 'X_WITHOUT', icon: 'x', shareable: false })
  }
})

describe('canExport', () => {
  it('a member with open keys may export a folder and a kind that brings an export hook', () => {
    expect(canExport(unlocked, { kind: FOLDER_KIND })).toBe(true)
    expect(canExport(unlocked, { kind: 'X_WITH' })).toBe(true)
  })
  it('fails closed: locked keys, a kind without a hook, a kind nobody registered, no node, a share guest', () => {
    expect(canExport(memberAccessOf(), { kind: FOLDER_KIND })).toBe(false)
    expect(canExport(unlocked, { kind: 'X_WITHOUT' })).toBe(false)
    expect(canExport(unlocked, { kind: 'NOBODY' })).toBe(false)
    expect(canExport(unlocked, null)).toBe(false)
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY']) expect(canExport(guestAccessOf(role), { kind: FOLDER_KIND })).toBe(false)
  })
  it('stays available while the scope is read-only (a pending deletion): reading is not a write', () => {
    expect(canExport(memberAccessOf({ epochs: epochsOf(new Uint8Array(32)), readonly: true }), { kind: FOLDER_KIND })).toBe(true)
  })
})

describe('the action', () => {
  it('is one "Export" action after the application\'s, for header and row alike, that calls back', () => {
    let called = 0
    for (const make of [exportHeaderActions, exportRowActions]) {
      const [a, ...rest] = make(t, unlocked, { id: 'n', kind: FOLDER_KIND }, () => called++)
      expect(rest).toEqual([])
      expect(a).toMatchObject({ id: 'export', label: 'export.action', icon: 'pi pi-download', placement: 'after' })
      a.onClick()
    }
    expect(called).toBe(2)
    expect(exportRowActions(t, memberAccessOf(), { id: 'n', kind: FOLDER_KIND }, () => {})).toEqual([])
  })
})
