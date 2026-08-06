// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('../tree', () => ({
  createFolder: vi.fn().mockResolvedValue('node-1'),
  createMemory: vi.fn().mockResolvedValue({ nodeId: 'node-1', memoryKey: new Uint8Array(32) }),
  createChapter: vi.fn().mockResolvedValue({ nodeId: 'node-1', chapterKey: new Uint8Array(32) }),
  renameNode: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('../geocode', () => ({ reverseGeocode: vi.fn() }))

import NodeMutationDialog from './NodeMutationDialog.vue'
import { createFolder, createMemory, renameNode } from '../tree'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { setMapsConsent, setTileUrl, setGeocoderUrl } from '../../../shared/state/useMapConfig'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      common: { create: 'Create', cancel: 'Cancel' },
      memory: {
        createFolderTitle: 'Create a folder', createMemoryTitle: 'Create a Memory', renameTitle: 'Rename',
        nameLabel: 'Name', save: 'Save', slugTaken: 'Taken', illegalStructure: 'Illegal', saveFailed: 'Failed',
        place: { autoOptIn: 'Determine location automatically', autoOptInHint: 'from where your photos were taken' },
      },
    },
  },
})

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

function mountDialog(props: Record<string, unknown>) {
  return mount(NodeMutationDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, ...props } as never,
    global: { plugins: [i18n], stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setMapsConsent(false) // default: maps opt-in off → no location step (existing create-memory behaviour)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('') // geocoding off in these tests → names are typed (no network)
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
    const node = { id: 'n9', parentId: 'p1', kind: 'FOLDER', name: 'Old', orderIndex: 0, epoch: 0, wrappedContentKey: null, wrappedMeta: null }
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
    vi.mocked(createFolder).mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = mountDialog({ mode: 'create-folder', parentId: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    await Promise.resolve()

    expect(wrapper.text()).toContain('Taken')
    expect(wrapper.emitted('done')).toBeFalsy()
  })
})
