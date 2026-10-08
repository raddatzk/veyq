// @vitest-environment happy-dom
// Core's delete dialog serves every application's nodes, so its title comes from the node's kind (inbox #375): a folder
// its own, a kind's `deleteTitle` from the registry (filled by the real applications in `test/setup.ts`), else the
// neutral "Delete". Before, every non-folder node — a Files drop box too — was titled "Delete Memory".
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../tree', () => ({ deleteNode: vi.fn() }))

import DeleteNodeDialog from './DeleteNodeDialog.vue'
import { deleteNode, type TreeNode } from '../tree'
import { FOLDER_KIND } from '../kind'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Dialog: { props: ['header'], template: '<div><h2 class="header">{{ header }}</h2><slot /></div>' },
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const SCOPE = '11111111-1111-1111-1111-111111111111'

function nodeOf(kind: string): TreeNode {
  return { id: 'n1', parentId: null, kind, epoch: 0, placementVersion: 0, name: 'Rom 2026' } as TreeNode
}

function mountDialog(kind: string) {
  return mount(DeleteNodeDialog, {
    props: { scopeId: SCOPE, node: nodeOf(kind) },
    global: { plugins: [realI18n()], stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(deleteNode).mockResolvedValue(undefined)
})

describe('DeleteNodeDialog', () => {
  it('titles a folder as a folder and warns about its subtree', () => {
    const w = mountDialog(FOLDER_KIND)
    expect(w.find('.header').text()).toBe(t('node.deleteFolderTitle'))
    expect(w.text()).toContain(t('node.deleteFolderCopy', { name: 'Rom 2026' }))
  })

  it('titles a node by its kind\'s registered deleteTitle — Memories keeps "Delete Memory", a drop box is no Memory', () => {
    for (const kind of ['MEMORY', 'CHAPTER']) {
      expect(mountDialog(kind).find('.header').text()).toBe('Delete Memory')
    }
    expect(mountDialog('FILES_DROPBOX').find('.header').text()).toBe('Delete drop box')
    expect(mountDialog('MEMORY').text()).toContain(t('node.deleteCopy', { name: 'Rom 2026' }))
  })

  it('falls back to the neutral "Delete" for a kind without a deleteTitle and for a kind nobody registered', () => {
    for (const kind of ['FILE', 'SOME_NEWER_KIND']) {
      const header = mountDialog(kind).find('.header').text()
      expect(header).toBe(t('node.delete'))
      expect(header).not.toMatch(/Memory/)
    }
  })

  it('deletes the node and shows the neutral failure when the delete fails', async () => {
    vi.mocked(deleteNode).mockRejectedValueOnce(new Error('boom'))
    const w = mountDialog('FILES_DROPBOX')
    await w.findAll('button').find((b) => b.text() === t('node.delete'))!.trigger('click')
    for (let i = 0; i < 4; i++) await Promise.resolve()
    await w.vm.$nextTick()
    expect(deleteNode).toHaveBeenCalledWith(SCOPE, 'n1')
    expect(w.find('.msg').text()).toBe(t('node.deleteFailed'))
    expect(w.emitted('done')).toBeUndefined()
  })
})
