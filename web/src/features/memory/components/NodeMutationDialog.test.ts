// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount } from '@vue/test-utils'

vi.mock('../tree', () => ({
  createFolder: vi.fn(),
  createMemory: vi.fn(),
  createChapter: vi.fn(),
  renameNode: vi.fn(),
}))
vi.mock('../geocode', () => ({ reverseGeocode: vi.fn() }))

import NodeMutationDialog from './NodeMutationDialog.vue'
import { createFolder, createMemory, createChapter, renameNode } from '../tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { setMapsConsent, setTileUrl, setGeocoderUrl } from '../../../shared/state/useMapConfig'
import { realI18n, t } from '../../../test/i18n'
import type { TreeNode } from '../tree'

const AUTO_PENDING = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }
const stubs = {
  Dialog: { template: '<div><slot /></div>' },
  InputText: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event)" />',
  },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  Checkbox: {
    props: ['modelValue', 'binary', 'inputId'],
    emits: ['update:modelValue'],
    template: `<input type="checkbox" class="wants-location" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" />`,
  },
}

const SCOPE = '11111111-1111-1111-1111-111111111111'
const KEY = new Uint8Array(32).fill(7)
const EPOCHS = epochsOf(KEY)

// The test's half of the dialog's props — typed by the component, so a mount that forgets `mode` (or passes a
// node that is not a TreeNode) is refused here rather than mounting a dialog no caller could render.
type DialogProps = InstanceType<typeof NodeMutationDialog>['$props']

function mountDialog(props: Omit<DialogProps, 'scopeId' | 'epochs'>) {
  return mount(NodeMutationDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, ...props },
    global: { plugins: [realI18n()], stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setMapsConsent(false) // default: maps opt-in off → no location step (existing create-memory behaviour)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('') // geocoding off in these tests → names are typed (no network)
  vi.mocked(createFolder).mockResolvedValue('node-1')
  vi.mocked(createMemory).mockResolvedValue({ nodeId: 'node-1', memoryKey: new Uint8Array(32) })
  vi.mocked(createChapter).mockResolvedValue({ nodeId: 'node-1', chapterKey: new Uint8Array(32) })
  vi.mocked(renameNode).mockResolvedValue(undefined)
})

describe('NodeMutationDialog', () => {
  it('create-folder calls createFolder with the parent + closes + emits done', async () => {
    const wrapper = mountDialog({ mode: 'create-folder', parentId: 'parent-1' })
    await wrapper.find('input').setValue('Trips')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createFolder).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trips', 'parent-1')
    expect(wrapper.emitted('done')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('create-memory at the root passes parentId null and no pre-armed location (maps off)', async () => {
    const wrapper = mountDialog({ mode: 'create-memory', parentId: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    // maps opt-in is off in this test's i18n/config → no location step shown, location arg is null
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Italy', null, null)
  })

  it('rename calls renameNode with the node id + parent', async () => {
    const node: TreeNode = { id: 'n9', parentId: 'p1', kind: 'FOLDER', name: 'Old', orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null, metaVersion: 0 }
    const wrapper = mountDialog({ mode: 'rename', node })
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('Old') // prefilled
    await wrapper.find('input').setValue('New')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(renameNode).toHaveBeenCalledWith(SCOPE, EPOCHS, { id: 'n9', parentId: 'p1', epoch: 0 }, 'New')
  })

  it('the maps opt-in arms a PENDING auto location (position + name land with the first media)', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ mode: 'create-memory', parentId: null })
    await wrapper.find('input').setValue('Climbing 2026')
    await wrapper.find('.wants-location').setValue(true) // opt in — "follows your photos"
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Climbing 2026', null, AUTO_PENDING)
  })

  it('leaving the opt-in unchecked creates the Memory with no location', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ mode: 'create-memory', parentId: null })
    await wrapper.find('input').setValue('Trip')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trip', null, null)
  })

  it('maps a 409 to the slug-taken message and stays open', async () => {
    vi.mocked(createFolder).mockRejectedValueOnce(httpError(409))
    const wrapper = mountDialog({ mode: 'create-folder', parentId: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    await Promise.resolve()

    expect(wrapper.text()).toContain(t('memory.slugTaken'))
    expect(wrapper.emitted('done')).toBeFalsy()
  })
})
