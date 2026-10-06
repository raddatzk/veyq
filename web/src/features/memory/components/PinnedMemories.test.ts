// @vitest-environment happy-dom
// The Memories fill of the shell's `sidebarPins` slot: which nodes are pinned (Memories only — the kind filter that
// used to sit in AppSidebar), the two variants, and `navigate` for the mobile sheet. The cover rendering through
// the sidebar is pinned in `AppSidebar.test.ts`; this file owns the component's own rules.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../coverStore', () => ({
  covers: { get value() { return new Map() } },
  ensureCovers: vi.fn(),
  resetCovers: vi.fn(),
}))

import PinnedMemories from './PinnedMemories.vue'
import { ensureCovers } from '../coverStore'
import { treeStore } from '../../../core/node/treeStore'
import type { TreeNode } from '../../../core/node/tree'
import { favoritesStore } from '../favoritesStore'
import { FOLDER_KIND } from '../../../core/node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { realI18n, t } from '../../../test/i18n'

function node(over: Partial<TreeNode> & Pick<TreeNode, 'id' | 'kind'>): TreeNode {
  return { parentId: null, orderIndex: 0, epoch: 0, name: over.id, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, ...over }
}

async function mountPins(variant: 'rail' | 'sheet') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash: '#~me' })
  await router.isReady()
  return mount(PinnedMemories, { props: { variant }, global: { plugins: [router, realI18n()] } })
}

beforeEach(() => {
  vi.mocked(ensureCovers).mockClear()
  treeStore.nodes = [
    node({ id: 'm1', kind: MEMORY_KIND, name: 'Rom' }),
    node({ id: 'c1', kind: CHAPTER_KIND, name: 'Tag 1', parentId: 'm1' }),
    node({ id: 'f1', kind: FOLDER_KIND, name: 'Reisen' }),
  ]
  favoritesStore.ids = new Set(['m1', 'c1', 'f1', 'gone'])
})
afterEach(() => {
  treeStore.nodes = []
  favoritesStore.ids = new Set()
})

describe('PinnedMemories', () => {
  it('pins only Memories: a favorited chapter, a folder and a node that no longer exists drop out', async () => {
    const w = await mountPins('rail')
    expect(w.findAll('.sidebar-favorite-link').map((a) => a.text())).toEqual(['Rom'])
    expect(ensureCovers).toHaveBeenLastCalledWith(['m1'])
  })

  it('the rail variant renders the rail section and no sheet markup; the sheet variant the reverse', async () => {
    const rail = await mountPins('rail')
    expect(rail.find('.sidebar-favorites-section').exists()).toBe(true)
    expect(rail.find('.mobile-nav-section').exists()).toBe(false)
    const sheet = await mountPins('sheet')
    expect(sheet.find('.mobile-nav-section h2').text()).toBe(t('shell.favorites'))
    expect(sheet.find('.sidebar-favorites-section').exists()).toBe(false)
  })

  it('renders nothing at all when nothing is pinned (no empty heading)', async () => {
    favoritesStore.ids = new Set()
    expect((await mountPins('rail')).find('section').exists()).toBe(false)
    expect((await mountPins('sheet')).find('section').exists()).toBe(false)
  })

  it('emits navigate when a sheet row is followed, and not for a rail row', async () => {
    const sheet = await mountPins('sheet')
    await sheet.get('.mobile-nav-favorite').trigger('click')
    expect(sheet.emitted('navigate')).toHaveLength(1)
    const rail = await mountPins('rail')
    await rail.get('.sidebar-favorite-link').trigger('click')
    expect(rail.emitted('navigate')).toBeUndefined()
  })
})
