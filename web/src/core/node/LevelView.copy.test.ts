// @vitest-environment happy-dom
// "Copy to…" on the level (uc.platform.copy-node, cr-copy-node phase 1) against a STUBBED registry (as `LevelView.test.ts`):
// core's own action in every copyable row's menu and in the folder's header, beside the move, handing the host the node to
// copy (`copyNodes`) - for a member whose keys are open, never for a share guest of any role. Every control is clicked for
// real; the dialog is the host's (`CopyNodesDialog`).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
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
import type { Access } from '../access'
import { setViewMode } from '../../shared/state/useViewMode'
import { setLevelTab } from '../../shared/state/useLevelTab'
import { setAccountUserId } from '../../app/account'
import { realI18n, t } from '../../test/i18n'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { stubRows } from '../../test/stubRows'
import { actionIdsOf, clickAction } from '../../test/levelRows'

function node(id: string, kind: NodeKind, parentId: string | null = 'Trips'): TreeNode {
  return treeNode({ id, kind, name: id, parentId, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })
}
const TRIPS = node('Trips', FOLDER_KIND, null)
const TREE = [TRIPS, node('Sub', FOLDER_KIND), node('a1', 'ALPHA'), node('b1', 'BETA')]
const unlocked = memberAccessOf({ epochs: epochsOf(new Uint8Array(32)) })

beforeEach(() => {
  holder.reg = createRegistry()
  localStorage.clear()
  setAccountUserId('user-1')
  setLevelTab('all')
  setViewMode('list')
  // ALPHA copies (both hooks, an import that takes a payload by frame); BETA has no hooks: it is no copy's.
  holder.reg.registerKind({
    kind: 'ALPHA', icon: 'pi pi-a', shareable: false, list: { rows: stubRows('a').factory, tabLabel: 'memory.memories' },
    exportContent: async () => null, importContent: async () => ({ nodeId: 'x', nodeKey: new Uint8Array(0) }), importStreams: true,
  })
  holder.reg.registerKind({ kind: 'BETA', icon: 'pi pi-b', shareable: false, list: { rows: stubRows('b').factory, tabLabel: 'files.title' } })
})

function mountLevel(access: Access) {
  return mount(LevelView, {
    props: { folder: TRIPS, level: childrenOf(TREE, 'Trips'), access, tree: memberTreeOf(() => TREE) },
    global: { plugins: [realI18n(), PrimeVue], directives: { tooltip: {} } },
    attachTo: document.body,
  })
}
const headerLabels = (w: ReturnType<typeof mountLevel>) => w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('aria-label'))

describe('LevelView: Copy to…', () => {
  it('a member\'s copyable row carries it in its menu (a folder\'s too) and hands the host the node', async () => {
    const w = mountLevel(unlocked)
    expect(await actionIdsOf(w, 'a1')).toContain('copy')
    expect(await actionIdsOf(w, 'Sub')).toContain('copy')
    expect(await actionIdsOf(w, 'b1'), 'a kind this build cannot copy').not.toContain('copy')
    await clickAction(w, 'a1', 'copy')
    await clickAction(w, 'Sub', 'copy')
    expect(w.emitted('copyNodes')).toEqual([[[expect.objectContaining({ id: 'a1' })]], [[expect.objectContaining({ id: 'Sub' })]]])
  })

  it('the folder\'s header offers it right after "Move to…"', async () => {
    const w = mountLevel(unlocked)
    const labels = headerLabels(w)
    expect(labels.indexOf(t('node.copy.action'))).toBe(labels.indexOf(t('node.move.action')) + 1)
    await w.find(`.veyq-node-header-actions button[aria-label="${t('node.copy.action')}"]`).trigger('click')
    expect(w.emitted('copyNodes')).toEqual([[[TRIPS]]])
  })

  it('never for a share guest, whatever the role; not for a member whose keys are locked', async () => {
    for (const access of [guestAccessOf('READ'), guestAccessOf('READ_WRITE'), memberAccessOf()]) {
      const w = mountLevel(access)
      expect(headerLabels(w), access.mode).not.toContain(t('node.copy.action'))
      for (const title of ['Sub', 'a1']) expect(await actionIdsOf(w, title), `${access.mode} ${title}`).not.toContain('copy')
      w.unmount()
    }
  })
})
