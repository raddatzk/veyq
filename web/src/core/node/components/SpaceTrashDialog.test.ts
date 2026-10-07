// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount } from '@vue/test-utils'

vi.mock('../tree', () => ({
  listTrash: vi.fn(),
  restoreNode: vi.fn(),
}))

import SpaceTrashDialog from './SpaceTrashDialog.vue'
import { listTrash, restoreNode, type TrashedNode, type TreeNode } from '../tree'
import { epochsOf } from '../../../features/spaces/epochsTestUtil'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  Dialog: { template: '<div><slot /></div>' },
  ProgressSpinner: { template: '<div class="spinner" />' },
  InputText: {
    props: ['modelValue'],
    template: '<input class="retry-name" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  // No `$emit('click')` here: the parent's `@click` already lands as a NATIVE listener via attribute
  // fallthrough, so re-emitting fires every handler twice — which silently consumed a `mockRejectedValueOnce`
  // and made a failed restore look like a successful one.
  Button: { props: ['label'], template: '<button>{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}

const SCOPE = '11111111-1111-1111-1111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(7))
// The live tree the dialog is handed: a trashed root's entry lives under its live parent's key (ADR 0080).
const TREE: TreeNode[] = []

const ROW: TrashedNode = {
  id: 'n1',
  parentId: null,
  kind: 'MEMORY',
  epoch: 0,
  placementVersion: 0,
  name: 'Rom 2026',
  deletedAt: new Date('2026-08-17T10:15:30Z'),
  purgeAfter: new Date('2026-09-16T10:15:30Z'),
}

function mountDialog() {
  return mount(SpaceTrashDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, tree: TREE },
    global: { plugins: [realI18n()], stubs },
  })
}

/** Let the mounted `listTrash` and any in-flight restore settle. */
async function settle(wrapper: ReturnType<typeof mountDialog>) {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await wrapper.vm.$nextTick()
}

function restoreButton(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('button').find((b) => b.text() === t('memory.spaceTrash.restore'))!
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listTrash).mockResolvedValue([])
  vi.mocked(restoreNode).mockResolvedValue(undefined)
})

describe('SpaceTrashDialog', () => {
  it('lists a deleted subtree root with both lifecycle dates', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW])
    const wrapper = mountDialog()
    await settle(wrapper)

    expect(listTrash).toHaveBeenCalledWith(SCOPE, EPOCHS, TREE)
    expect(wrapper.text()).toContain('Rom 2026')
    // both dates are rendered — "you can still get it back, and until when"
    expect(wrapper.text()).toContain(t('memory.spaceTrash.deletedOn', { day: ROW.deletedAt.toLocaleDateString('en') }))
    expect(wrapper.text()).toContain(t('memory.spaceTrash.purgeOn', { day: ROW.purgeAfter.toLocaleDateString('en') }))
  })

  it('draws each row with its kind\'s own icon from the registry; a kind nobody registered gets a neutral one', async () => {
    vi.mocked(listTrash).mockResolvedValue([
      { ...ROW, id: 'a', name: 'AMem', kind: 'MEMORY' },
      { ...ROW, id: 'b', name: 'BFolder', kind: 'FOLDER' },
      { ...ROW, id: 'c', name: 'CFuture', kind: 'FUTURE' as TrashedNode['kind'] },
    ])
    const wrapper = mountDialog()
    await settle(wrapper)
    const iconOf = (name: string) =>
      wrapper.findAll('.trash-row').find((r) => r.text().includes(name))!.find('strong i').classes().join(' ')
    expect(iconOf('AMem')).toBe('pi pi-images')
    expect(iconOf('BFolder')).toBe('pi pi-folder')
    expect(iconOf('CFuture')).toBe('pi pi-file')
  })

  it('shows the empty state when nothing was deleted', async () => {
    const wrapper = mountDialog()
    await settle(wrapper)
    expect(wrapper.text()).toContain(t('memory.spaceTrash.empty'))
  })

  // Changed deliberately in cr-node-key-hierarchy 5b.5 (inbox #357): this used to assert only that the row is filtered
  // out locally. The list is now read again once the host has reloaded the tree — a descendant left in the trash (its
  // name was freed by an earlier delete) becomes a root of its own, and its name opens only under the parent the
  // reloaded tree holds — so the order is part of the contract: restore → `restored` → host's tree → re-read.
  it('restores a row, asks the host to refetch the tree, and re-reads the list against the RELOADED tree', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW])
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    // no `newName` — an ordinary restore keeps the stored name
    expect(restoreNode).toHaveBeenCalledWith(SCOPE, EPOCHS, TREE, expect.objectContaining({ id: 'n1' }), undefined)
    expect(wrapper.text()).not.toContain('Rom 2026')
    expect(wrapper.emitted('restored')).toHaveLength(1)
    // not re-read against the old tree: the restored parent is not in it yet
    expect(listTrash).toHaveBeenCalledTimes(1)

    // the host reloads the tree: now the list is read again, against the new tree, and the left-behind child shows
    const RELOADED: TreeNode[] = [{ id: 'n1', parentId: null, kind: 'MEMORY', orderIndex: 0, epoch: 0, name: 'Rom 2026', wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 }]
    const CHILD: TrashedNode = { ...ROW, id: 'c1', parentId: 'n1', kind: 'CHAPTER', name: 'Tag 2' }
    vi.mocked(listTrash).mockResolvedValue([CHILD])
    await wrapper.setProps({ tree: RELOADED })
    await settle(wrapper)

    expect(listTrash).toHaveBeenCalledTimes(2)
    expect(listTrash).toHaveBeenLastCalledWith(SCOPE, EPOCHS, RELOADED)
    expect(wrapper.text()).toContain('Tag 2')
    expect(wrapper.text()).not.toContain('Rom 2026')
  })

  it('a re-read that fails after a restore says so instead of showing an empty trash', async () => {
    vi.mocked(listTrash).mockResolvedValueOnce([ROW]).mockRejectedValueOnce(new Error('OpenRefused'))
    const wrapper = mountDialog()
    await settle(wrapper)
    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)
    await wrapper.setProps({ tree: [...TREE] })
    await settle(wrapper)

    expect(wrapper.text()).toContain(t('memory.spaceTrash.loadFailed'))
  })

  /**
   * A1: a node created while this one sat in the trash already holds the name. The row must offer the same
   * disambiguation a create does — a name field and a retry — never a silent rename and never a dead end.
   */
  it('turns a 409 slug_taken into a name field and retries under the new name', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW])
    vi.mocked(restoreNode).mockRejectedValueOnce(httpError(409, 'slug_taken'))
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(wrapper.text()).toContain(t('memory.slugTaken'))
    const field = wrapper.find('input.retry-name')
    expect(field.exists()).toBe(true)
    // the field is pre-filled with the current name, so the user edits rather than retypes
    expect((field.element as HTMLInputElement).value).toBe('Rom 2026')
    expect(wrapper.emitted('restored')).toBeUndefined()

    await field.setValue('Rom 2026 (2)')
    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(restoreNode).toHaveBeenLastCalledWith(SCOPE, EPOCHS, TREE, expect.objectContaining({ id: 'n1' }), 'Rom 2026 (2)')
    expect(wrapper.emitted('restored')).toHaveLength(1)
  })

  /** A 409 that is NOT a slug collision has no new-name remedy, so it must not offer one. */
  it('does not offer a rename for a failure a new name cannot fix', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW])
    vi.mocked(restoreNode).mockRejectedValueOnce(httpError(409, 'scope_deleting'))
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(wrapper.text()).toContain(t('memory.spaceTrash.restoreFailed'))
    expect(wrapper.find('input.retry-name').exists()).toBe(false)
  })

  it('surfaces a failed listing instead of an empty trash', async () => {
    vi.mocked(listTrash).mockRejectedValue(new Error('boom'))
    const wrapper = mountDialog()
    await settle(wrapper)
    expect(wrapper.text()).toContain(t('memory.spaceTrash.loadFailed'))
    expect(wrapper.text()).not.toContain(t('memory.spaceTrash.empty'))
  })
})
