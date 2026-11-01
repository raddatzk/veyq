// Core's default row actions rename, move and delete (owner 2026-11-01): shown exactly where the application's answer
// grants them AND the level has a door (a guest's has the move alone), and replaced by an application's own action of
// the same id.
import { describe, it, expect, vi } from 'vitest'
import { STANDARD_ROW_ACTION_IDS, standardRowActions, standardRowDoors, withStandardRowActions } from './standardRowActions'
import type { LevelRowAction } from './levelRow'
import { treeNode, memberAccessOf, guestAccessOf } from '../../test/models'
import { ShareRole } from '../../api/generated/sharing'

const t = (key: string) => key
const node = treeNode({ id: 'n1', kind: 'ALPHA', name: 'n1', parentId: null })
const doors = () => ({ rename: vi.fn(), move: vi.fn(), remove: vi.fn() })
const ids = (list: readonly LevelRowAction[]) => list.map((a) => a.id)
const ALL = { rename: true, move: true, delete: true }

describe('standardRowActions', () => {
  it('offers rename, move and delete, in that order, each only where the application granted it', () => {
    const d = doors()
    expect(ids(standardRowActions(t, node, { standard: ALL }, d))).toEqual([...STANDARD_ROW_ACTION_IDS])
    expect(ids(standardRowActions(t, node, { standard: { move: true } }, d))).toEqual(['move'])
    expect(ids(standardRowActions(t, node, { standard: { rename: true, delete: false } }, d))).toEqual(['rename'])
  })

  it('fails closed: no answer, an empty one or no door is no action', () => {
    expect(standardRowActions(t, node, {}, doors())).toEqual([])
    expect(standardRowActions(t, node, { standard: {} }, doors())).toEqual([])
    expect(standardRowActions(t, node, { standard: ALL }, {})).toEqual([])
  })

  it('runs each through the level\'s door; the delete carries the kind\'s own write where its selection names one', () => {
    const d = doors()
    const remove = async () => {}
    const actions = standardRowActions(t, node, { standard: ALL, selection: { move: true, delete: true, remove } }, d)
    for (const a of actions) a.onClick()
    expect(d.rename).toHaveBeenCalledWith(node)
    expect(d.move).toHaveBeenCalledWith(node)
    expect(d.remove).toHaveBeenCalledWith({ node, remove })
    const del = actions.find((a) => a.id === 'delete')!
    expect(del).toMatchObject({ severity: 'danger', label: 'node.delete' })
  })

  it('a member\'s level has all three doors, a guest\'s the move alone - whatever the guest\'s role or answer', () => {
    const d = doors()
    expect(Object.keys(standardRowDoors(memberAccessOf(), d)).sort()).toEqual(['move', 'remove', 'rename'])
    for (const role of [ShareRole.Read, ShareRole.ReadWrite, ShareRole.WriteOnly]) {
      const guest = standardRowDoors(guestAccessOf(role), d)
      expect(ids(standardRowActions(t, node, { standard: ALL }, guest)), role).toEqual(['move'])
    }
  })
})

describe('withStandardRowActions', () => {
  const own = (id: string, over: Partial<LevelRowAction> = {}): LevelRowAction => ({ id, label: `own ${id}`, icon: 'pencil', onClick: () => {}, ...over })
  const defaults = standardRowActions(t, node, { standard: ALL }, doors())

  it('adds the defaults around the application\'s own: rename and move before, delete after', () => {
    expect(ids(withStandardRowActions([own('download')], defaults))).toEqual(['rename', 'move', 'download', 'delete'])
  })

  it('an application\'s action of the same id REPLACES core\'s default - its own `shown` decides, even when it says no', () => {
    const merged = withStandardRowActions([own('delete'), own('rename', { shown: false })], defaults)
    expect(ids(merged)).toEqual(['move', 'delete', 'rename'])
    expect(merged.find((a) => a.id === 'delete')!.label, 'the application\'s, not core\'s').toBe('own delete')
    expect(merged.filter((a) => a.id === 'rename')).toHaveLength(1)
    expect(merged.find((a) => a.id === 'rename')!.shown).toBe(false)
  })
})
