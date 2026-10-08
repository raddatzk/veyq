// @vitest-environment happy-dom
// Memories' create dialog (cr-files-application P4c), the `createDialog` of MEMORY and CHAPTER: the kind picks a Memory
// or a chapter. The folder-create and rename cases moved to core's `core/node/FolderNameDialog.test.ts`.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount } from '@vue/test-utils'

vi.mock('../memoryNodes', () => ({ createMemory: vi.fn(), createChapter: vi.fn() }))
vi.mock('../geocode', () => ({ reverseGeocode: vi.fn() }))

import MemoryNodeDialog from './MemoryNodeDialog.vue'
import { createMemory, createChapter } from '../memoryNodes'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { setMapsConsent, setTileUrl, setGeocoderUrl } from '../../../shared/state/useMapConfig'
import { realI18n, t } from '../../../test/i18n'

const AUTO_PENDING = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }
const stubs = {
  Dialog: { props: ['header'], template: '<div><h3 class="hdr">{{ header }}</h3><slot /></div>' },
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

// The test's half of the dialog's props — typed by the component (the registry's `NodeCreateDialogProps`), so a
// mount that forgets the kind or the parent is refused here rather than mounting a dialog no caller could render.
type DialogProps = InstanceType<typeof MemoryNodeDialog>['$props']

function mountDialog(props: Omit<DialogProps, 'scopeId' | 'epochs'>) {
  return mount(MemoryNodeDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, ...props },
    global: { plugins: [realI18n()], stubs },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  setMapsConsent(false) // default: maps opt-in off → no location step (existing create-memory behaviour)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('') // geocoding off in these tests → names are typed (no network)
  vi.mocked(createMemory).mockResolvedValue({ nodeId: 'node-1', memoryKey: new Uint8Array(32) })
  vi.mocked(createChapter).mockResolvedValue({ nodeId: 'node-1', chapterKey: new Uint8Array(32) })
})

describe('MemoryNodeDialog', () => {
  it('a MEMORY inside a folder wraps under the folder\'s key (ADR 0080)', async () => {
    const parentKey = new Uint8Array(32).fill(9)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: 'folder-1', parentKey })
    expect(wrapper.find('.hdr').text()).toBe(t('memory.createMemoryTitle'))
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Italy', { id: 'folder-1', key: parentKey }, null)
    expect(wrapper.emitted('done')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('a MEMORY at the root passes parentId null and no pre-armed location (maps off)', async () => {
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    // maps opt-in is off in this test's i18n/config → no location step shown, location arg is null
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Italy', null, null)
  })

  it('a MEMORY under a parent whose key is missing creates nothing', async () => {
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: 'folder-1', parentKey: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createMemory).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('a CHAPTER is created inside the opened Memory, under the Memory\'s key, with no location step', async () => {
    setMapsConsent(true) // even with maps on: a chapter has no place of its own
    const memoryKey = new Uint8Array(32).fill(5)
    const wrapper = mountDialog({ kind: CHAPTER_KIND, parentId: 'italy', parentKey: memoryKey })
    expect(wrapper.find('.hdr').text()).toBe(t('memory.createChapterTitle'))
    expect(wrapper.find('.wants-location').exists()).toBe(false)
    await wrapper.find('input').setValue('Day 1')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createChapter).toHaveBeenCalledWith(SCOPE, EPOCHS, memoryKey, 'Day 1', 'italy')
    expect(createMemory).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it('a CHAPTER without its Memory creates nothing', async () => {
    const wrapper = mountDialog({ kind: CHAPTER_KIND, parentId: null, parentKey: null })
    await wrapper.find('input').setValue('Day 1')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createChapter).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('the maps opt-in arms a PENDING auto location (position + name land with the first media)', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await wrapper.find('input').setValue('Climbing 2026')
    await wrapper.find('.wants-location').setValue(true) // opt in — "follows your photos"
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Climbing 2026', null, AUTO_PENDING)
  })

  it('leaving the opt-in unchecked creates the Memory with no location', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await wrapper.find('input').setValue('Trip')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trip', null, null)
  })

  it('maps a 409 to the slug-taken message and stays open', async () => {
    vi.mocked(createMemory).mockRejectedValueOnce(httpError(409))
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    await Promise.resolve()

    expect(wrapper.text()).toContain(t('node.slugTaken'))
    expect(wrapper.emitted('done')).toBeFalsy()
  })
})
