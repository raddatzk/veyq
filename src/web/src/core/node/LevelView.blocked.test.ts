// @vitest-environment happy-dom
// A node a moderator blocked (uc.moderation.take-a-measure; ADR 0093 § 6): the level row is the placeholder "Blocked by
// the operator" — no reason, for a member and a guest alike (inbox #401: a row state, not a guest screen). It does not
// open, of its actions only delete stays (the uploader may still remove it), and it is never drawn as the cover.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../../shared/state/useToast', () => ({ useToast: () => ({ success: vi.fn(), error: vi.fn(), showUndo: vi.fn(), dismissUndo: vi.fn() }) }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})

import LevelView from './LevelView.vue'
import { createRegistry } from '../applications/registry'
import { childrenOf } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { TreeNode } from './tree'
import { setViewMode } from '../../shared/state/useViewMode'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { hostFor } from '../../test/nodeHost'
import { ShareRole } from '../../api/generated/sharing'
import { stubRows } from '../../test/stubRows'
import { actionIdsOf, rowOf } from '../../test/levelRows'
import { nothingOfItsOwnToRekey } from './rekey/hooks'

function node(id: string, kind: NodeKind, parentId: string | null, blocked = false): TreeNode {
  return treeNode({
    id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0,
    blockedByOperator: blocked,
  })
}

const TRIPS = node('Trips', FOLDER_KIND, null)
const tree = [TRIPS, node('open', 'ALPHA', 'Trips'), node('held', 'ALPHA', 'Trips', true), node('Sub', FOLDER_KIND, 'Trips', true)]

beforeEach(() => {
  setViewMode('list')
  holder.reg = createRegistry()
  holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey,
    kind: 'ALPHA', icon: 'images', 
    list: { rows: stubRows('a').factory }, // its rows carry a rename: the placeholder drops it
  })
})

const mountAs = (access: ReturnType<typeof memberAccessOf> | ReturnType<typeof guestAccessOf>) => {
  const rec = hostFor(access)
  const w = mount(LevelView, {
    props: { folder: TRIPS, level: childrenOf(tree, TRIPS.id), access, tree: memberTreeOf(() => tree) },
    global: { plugins: [realI18n()], provide: rec.provide },
  })
  return Object.assign(w, { calls: rec.calls })
}


describe('a blocked node in the level', () => {
  for (const [who, access] of [['member', () => memberAccessOf()], ['guest', () => guestAccessOf(ShareRole.ReadWrite)]] as const) {
    it(`${who}: the row is the placeholder, does not open, and keeps delete alone`, async () => {
      const w = mountAs(access())
      for (const title of ['held', 'Sub']) {
        const row = rowOf(w, title)
        expect(row.text()).toContain(t('node.blockedByOperator'))
        await row.find('button.veyq-level-item-main').trigger('click')
      }
      expect(w.calls.open, 'a blocked node never opens').toBeUndefined()
      expect(rowOf(w, 'open').text()).not.toContain(t('node.blockedByOperator'))
      const held = await actionIdsOf(w, 'held')
      expect(held.filter((id) => id !== 'delete'), 'nothing but delete reaches a blocked node').toEqual([])

      await rowOf(w, 'open').find('button.veyq-level-item-main').trigger('click')
      expect(w.calls.open).toHaveLength(1)
    })
  }
})
