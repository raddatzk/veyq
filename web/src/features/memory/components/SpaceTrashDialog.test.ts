// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../tree', () => ({
  listTrash: vi.fn().mockResolvedValue([]),
  restoreNode: vi.fn().mockResolvedValue(undefined),
}))

import SpaceTrashDialog from './SpaceTrashDialog.vue'
import { listTrash, restoreNode } from '../tree'
import { epochsOf } from '../../spaces/epochsTestUtil'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { cancel: 'Cancel', done: 'Done' },
      memory: {
        slugTaken: 'Taken',
        spaceTrash: {
          title: 'Trash', hint: 'Hint', empty: 'Nothing has been deleted here.',
          loadFailed: 'Could not open the trash.', restore: 'Restore',
          restoreFailed: 'Could not restore this.', deletedOn: 'Deleted {day}',
          purgeOn: 'removed for good on {day}', retryName: 'New name', open: 'Trash',
        },
      },
    },
  },
})

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

const ROW = {
  id: 'n1',
  parentId: null,
  kind: 'MEMORY',
  epoch: 0,
  name: 'Rom 2026',
  deletedAt: new Date('2026-08-17T10:15:30Z'),
  purgeAfter: new Date('2026-09-16T10:15:30Z'),
}

function mountDialog() {
  return mount(SpaceTrashDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS } as never,
    global: { plugins: [i18n], stubs },
  })
}

/** Let the mounted `listTrash` and any in-flight restore settle. */
async function settle(wrapper: ReturnType<typeof mountDialog>) {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await wrapper.vm.$nextTick()
}

function restoreButton(wrapper: ReturnType<typeof mountDialog>) {
  return wrapper.findAll('button').find((b) => b.text() === 'Restore')!
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listTrash).mockResolvedValue([])
  vi.mocked(restoreNode).mockResolvedValue(undefined)
})

describe('SpaceTrashDialog', () => {
  it('lists a deleted subtree root with both lifecycle dates', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW] as never)
    const wrapper = mountDialog()
    await settle(wrapper)

    expect(listTrash).toHaveBeenCalledWith(SCOPE, EPOCHS)
    expect(wrapper.text()).toContain('Rom 2026')
    // both dates are rendered — "you can still get it back, and until when"
    expect(wrapper.text()).toContain('Deleted')
    expect(wrapper.text()).toContain('removed for good on')
  })

  it('shows the empty state when nothing was deleted', async () => {
    const wrapper = mountDialog()
    await settle(wrapper)
    expect(wrapper.text()).toContain('Nothing has been deleted here.')
  })

  it('restores a row, drops it from the list, and asks the host to refetch the tree', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW] as never)
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    // no `newName` — an ordinary restore keeps the stored name
    expect(restoreNode).toHaveBeenCalledWith(SCOPE, EPOCHS, expect.objectContaining({ id: 'n1' }), undefined)
    expect(wrapper.text()).not.toContain('Rom 2026')
    expect(wrapper.emitted('restored')).toHaveLength(1)
  })

  /**
   * A1: a node created while this one sat in the trash already holds the name. The row must offer the same
   * disambiguation a create does — a name field and a retry — never a silent rename and never a dead end.
   */
  it('turns a 409 slug_taken into a name field and retries under the new name', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW] as never)
    vi.mocked(restoreNode).mockRejectedValueOnce({
      response: { status: 409, clone: () => ({ json: async () => ({ code: 'slug_taken' }) }) },
    })
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(wrapper.text()).toContain('Taken')
    const field = wrapper.find('input.retry-name')
    expect(field.exists()).toBe(true)
    // the field is pre-filled with the current name, so the user edits rather than retypes
    expect((field.element as HTMLInputElement).value).toBe('Rom 2026')
    expect(wrapper.emitted('restored')).toBeUndefined()

    await field.setValue('Rom 2026 (2)')
    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(restoreNode).toHaveBeenLastCalledWith(SCOPE, EPOCHS, expect.objectContaining({ id: 'n1' }), 'Rom 2026 (2)')
    expect(wrapper.emitted('restored')).toHaveLength(1)
  })

  /** A 409 that is NOT a slug collision has no new-name remedy, so it must not offer one. */
  it('does not offer a rename for a failure a new name cannot fix', async () => {
    vi.mocked(listTrash).mockResolvedValue([ROW] as never)
    vi.mocked(restoreNode).mockRejectedValueOnce({
      response: { status: 409, clone: () => ({ json: async () => ({ code: 'scope_deleting' }) }) },
    })
    const wrapper = mountDialog()
    await settle(wrapper)

    await restoreButton(wrapper).trigger('click')
    await settle(wrapper)

    expect(wrapper.text()).toContain('Could not restore this.')
    expect(wrapper.find('input.retry-name').exists()).toBe(false)
  })

  it('surfaces a failed listing instead of an empty trash', async () => {
    vi.mocked(listTrash).mockRejectedValue(new Error('boom'))
    const wrapper = mountDialog()
    await settle(wrapper)
    expect(wrapper.text()).toContain('Could not open the trash.')
    expect(wrapper.text()).not.toContain('Nothing has been deleted here.')
  })
})
