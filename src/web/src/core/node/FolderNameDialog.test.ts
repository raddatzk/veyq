// @vitest-environment happy-dom
// Core's name dialog (cr-files-application P4c): create a folder, rename a node of ANY kind. The folder and rename cases
// moved here from Memories' former `NodeMutationDialog.test.ts` unchanged but for the mode name; the rename cases run
// for a Depot node (`FILE`) beside the platform's and Memories' kinds. The slug token a rename sends is proven on the
// wire, through the real `renameNode`, in `FolderNameDialog.slug.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../test/httpError'
import { mount } from '@vue/test-utils'

vi.mock('./tree', () => ({
  createFolder: vi.fn(),
  renameNode: vi.fn(),
}))
vi.mock('../sharing/manageShares', () => ({ resealShareNames: vi.fn() }))
const { toastError } = vi.hoisted(() => ({ toastError: vi.fn() }))
vi.mock('../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
}))

import FolderNameDialog from './FolderNameDialog.vue'
import { createFolder, renameNode } from './tree'
import { resealShareNames } from '../sharing/manageShares'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { realI18n, t } from '../../test/i18n'
import type { TreeNode } from './tree'
import { treeStore } from './treeStore'
import { treeNode } from '../../test/models'

const stubs = {
  Dialog: { name: 'Dialog', props: ['header'], emits: ['show'], template: '<div><h3 class="hdr">{{ header }}</h3><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event)" />',
  },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const SCOPE = '11111111-1111-1111-1111-111111111111'
const KEY = new Uint8Array(32).fill(7)
const EPOCHS = epochsOf(KEY)

// The test's half of the dialog's props — typed by the component, so a mount that forgets `mode` (or passes a
// node that is not a TreeNode) is refused here rather than mounting a dialog no caller could render.
type DialogProps = InstanceType<typeof FolderNameDialog>['$props']

function mountDialog(props: Omit<DialogProps, 'scopeId' | 'epochs'>) {
  return mount(FolderNameDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, ...props },
    global: { plugins: [realI18n()], stubs },
  })
}
const nodeOf = (kind: string, parentId: string | null = 'p1'): TreeNode =>
  treeNode({ id: 'n9', parentId, kind, name: 'Old', orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaRevision: 0, placementRevision: 0 })

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(createFolder).mockResolvedValue('node-1')
  vi.mocked(renameNode).mockResolvedValue(undefined)
  vi.mocked(resealShareNames).mockResolvedValue({ resealed: [], skipped: [], failed: [] })
})

/** Let the submit's chained awaits (rename, then the share-name re-seal) settle. */
async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
}

describe('FolderNameDialog', () => {
  it('create calls createFolder with the parent and ITS key + closes + emits done', async () => {
    const parentKey = new Uint8Array(32).fill(7)
    const wrapper = mountDialog({ mode: 'create', parentId: 'parent-1', parentKey })
    expect(wrapper.find('.hdr').text()).toBe(t('node.createFolderTitle'))
    expect(wrapper.find('button[type="submit"]').text()).toBe(t('common.create'))
    await wrapper.find('input').setValue('Trips')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createFolder).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trips', { id: 'parent-1', key: parentKey })
    expect(wrapper.emitted('done')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('a create under a parent whose key is missing creates nothing', async () => {
    const wrapper = mountDialog({ mode: 'create', parentId: 'parent-1', parentKey: null })
    await wrapper.find('input').setValue('Trips')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createFolder).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('has no Memory-only location opt-in', () => {
    const wrapper = mountDialog({ mode: 'create', parentId: null })
    expect(wrapper.find('.node-location-optin').exists()).toBe(false)
  })

  it('rename calls renameNode with the node id (a placement to its own parent)', async () => {
    const wrapper = mountDialog({ mode: 'rename', node: nodeOf('FOLDER') })
    expect(wrapper.find('.hdr').text()).toBe(t('node.renameTitle'))
    expect(wrapper.find('button[type="submit"]').text()).toBe(t('common.save'))
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('Old') // prefilled
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    // the loaded tree travels with it: the name re-seals under the PARENT's key, resolved from it (ADR 0080)
    expect(renameNode).toHaveBeenCalledWith(SCOPE, EPOCHS, treeStore.nodes, 'n9', 'New')
  })

  // ADR 0080 decision 14: every share rooted at the renamed node gets the new name — for any kind, through core. FILE is
  // the Depot application's kind: the dialog names no kind, so a node of a second application renames the same way.
  it.each(['FOLDER', 'MEMORY', 'CHAPTER', 'FILE'])('a rename of a %s re-seals its share names after the rename, then closes', async (kind) => {
    const order: string[] = []
    vi.mocked(renameNode).mockImplementation(async () => { order.push('rename') })
    vi.mocked(resealShareNames).mockImplementation(async () => { order.push('reseal'); return { resealed: ['s1'], skipped: [], failed: [] } })
    const wrapper = mountDialog({ mode: 'rename', node: nodeOf(kind) })
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(order).toEqual(['rename', 'reseal'])
    expect(renameNode).toHaveBeenCalledWith(SCOPE, EPOCHS, treeStore.nodes, 'n9', 'New')
    expect(resealShareNames).toHaveBeenCalledWith('n9', 'New', EPOCHS)
    expect(toastError).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it.each([
    ['a share failed', { resealed: ['a'], skipped: [], failed: ['b'] }],
    ['a share had no recovery copy', { resealed: [], skipped: ['b'], failed: [] }],
  ])('says so in a toast when %s, and the rename still stands', async (_, report) => {
    vi.mocked(resealShareNames).mockResolvedValue(report)
    const wrapper = mountDialog({ mode: 'rename', node: nodeOf('MEMORY', null) })
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(toastError).toHaveBeenCalledWith(t('share.nameResealFailed'))
    expect(wrapper.text()).not.toContain(t('node.saveFailed'))
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it('a share list that cannot be read is a toast too, not a failed rename', async () => {
    vi.mocked(resealShareNames).mockRejectedValue(new Error('offline'))
    const wrapper = mountDialog({ mode: 'rename', node: nodeOf('FOLDER', null) })
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(toastError).toHaveBeenCalledWith(t('share.nameResealFailed'))
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it('a refused rename re-seals nothing', async () => {
    vi.mocked(renameNode).mockRejectedValueOnce(httpError(409))
    const wrapper = mountDialog({ mode: 'rename', node: nodeOf('FOLDER', null) })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(resealShareNames).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(t('node.slugTaken'))
  })

  it.each([
    [409, 'node.slugTaken'],
    [422, 'node.illegalStructure'],
    [500, 'node.saveFailed'],
  ])('maps a %i on create to %s and stays open', async (status, key) => {
    vi.mocked(createFolder).mockRejectedValueOnce(httpError(status))
    const wrapper = mountDialog({ mode: 'create', parentId: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await settle()

    expect(wrapper.text()).toContain(t(key))
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('submits nothing for a blank name', async () => {
    const wrapper = mountDialog({ mode: 'create', parentId: null })
    await wrapper.find('input').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await settle()
    expect(createFolder).not.toHaveBeenCalled()
  })

  // inbox #385 (h): a file's rename selects its name without the extension (the kind's `renameSelectionEnd`, Depot's
  // registration through the real registry) as the dialog shows; a kind that declares none leaves the caret alone.
  it('a rename selects what its kind declares as the dialog shows: a file\'s name without its extension', async () => {
    const shown = async (kind: string, name: string) => {
      const w = mountDialog({ mode: 'rename', node: { ...nodeOf(kind), name } })
      const input = w.find('input').element as HTMLInputElement
      input.setSelectionRange(name.length, name.length)
      w.findComponent({ name: 'Dialog' }).vm.$emit('show')
      await w.vm.$nextTick()
      return [input.selectionStart, input.selectionEnd]
    }
    expect(await shown('FILE', 'Bericht 2026.final.pdf')).toEqual([0, 'Bericht 2026.final'.length])
    expect(await shown('FOLDER', 'Reisen.2026')).toEqual([11, 11])
  })
})
