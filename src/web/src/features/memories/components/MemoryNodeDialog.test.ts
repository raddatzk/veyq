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
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../../test/services'
import { realI18n, t } from '../../../test/i18n'
import { activeSpace } from '../../../app/activeSpace'
import { account } from '../../../app/account'
import { ScopeKind } from '../../../api/generated/scopes'

const AUTO_PENDING = { name: '', nameSource: 'geocoded', derivedMode: 'auto' }
const ME = 'aaaaaaaa-0000-4000-8000-000000000001'
const stubs = {
  VeyqDialog: { props: ['header'], template: '<div><h3 class="hdr">{{ header }}</h3><slot /></div>' },
  VeyqInput: {
    props: ['modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event)" />',
  },
  VeyqButton: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqCheckbox: {
    props: ['modelValue', 'inputId'],
    emits: ['update:modelValue'],
    template: `<input type="checkbox" :class="inputId" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)" />`,
  },
}

/** A member's create at the scope root: re-opens the scope on a `stale_epoch` and states the epochs held then (inbox #670). */
const ROOT_CREATE = { reread: expect.any(Function), pull: expect.any(Function), epochs: expect.any(Function) }
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
  // the member's view of a PERSONAL scope by default: no "I was there" (the existing cases); the participants cases open a
  // tenant or group scope
  activeSpace.scopeId = SCOPE
  activeSpace.kind = ScopeKind.Personal
  account.userId = ME
  setMapsConsent(false) // default: maps opt-in off → no location step (existing create-memory behaviour)
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('') // geocoding off in these tests → names are typed (no network)
  vi.mocked(createMemory).mockResolvedValue({ nodeId: 'node-1', memoryKey: new Uint8Array(32) })
  vi.mocked(createChapter).mockResolvedValue({ nodeId: 'node-1', chapterKey: new Uint8Array(32) })
})

describe('MemoryNodeDialog', () => {
  it('a MEMORY inside a folder wraps under the folder\'s key (ADR 0080)', async () => {
    const parentKey = new Uint8Array(32).fill(9)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: 'folder-1', parentKey, parentEpoch: 2, parentScopeEpoch: 1 })
    expect(wrapper.find('.hdr').text()).toBe(t('memory.createMemoryTitle'))
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Italy', { id: 'folder-1', key: parentKey, epoch: 2, scopeEpoch: 1 }, { location: null, participants: [] }, { reread: expect.any(Function), pull: expect.any(Function), epochs: expect.any(Function) })
    expect(wrapper.emitted('done')).toBeTruthy()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('a MEMORY at the root passes parentId null and no pre-armed location (maps off)', async () => {
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    // maps opt-in is off in this test's i18n/config → no location step shown, location arg is null
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Italy', null, { location: null, participants: [] }, ROOT_CREATE)
  })

  it('a MEMORY under a parent whose key is missing creates nothing', async () => {
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: 'folder-1', parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Italy')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createMemory).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('a CHAPTER is created inside the opened Memory, under the Memory\'s key, with no location step', async () => {
    setMapsConsent(true) // even with maps on: a chapter has no place of its own
    const memoryKey = new Uint8Array(32).fill(5)
    const wrapper = mountDialog({ kind: CHAPTER_KIND, parentId: 'italy', parentKey: memoryKey, parentEpoch: 2, parentScopeEpoch: 1 })
    expect(wrapper.find('.hdr').text()).toBe(t('memory.createChapterTitle'))
    expect(wrapper.find('.wants-location').exists()).toBe(false)
    await wrapper.find('input').setValue('Day 1')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createChapter).toHaveBeenCalledWith(SCOPE, EPOCHS, memoryKey, 'Day 1', 'italy', expect.objectContaining({ epoch: 2, scopeEpoch: 1 }), { reread: expect.any(Function), pull: expect.any(Function), epochs: expect.any(Function) })
    expect(createMemory).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeTruthy()
  })

  it('a CHAPTER without its Memory creates nothing', async () => {
    const wrapper = mountDialog({ kind: CHAPTER_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Day 1')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    expect(createChapter).not.toHaveBeenCalled()
    expect(wrapper.emitted('done')).toBeFalsy()
  })

  it('the maps opt-in arms a PENDING auto location (position + name land with the first media)', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Climbing 2026')
    await wrapper.find('.wants-location').setValue(true) // opt in — "follows your photos"
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Climbing 2026', null, { location: AUTO_PENDING, participants: [] }, ROOT_CREATE)
  })

  it('leaving the opt-in unchecked creates the Memory with no location', async () => {
    setMapsConsent(true)
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Trip')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trip', null, { location: null, participants: [] }, ROOT_CREATE)
  })

  it('maps a 409 to the slug-taken message and stays open', async () => {
    vi.mocked(createMemory).mockRejectedValueOnce(httpError(409))
    const wrapper = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wrapper.find('input').setValue('Dupe')
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()
    await Promise.resolve()

    expect(wrapper.text()).toContain(t('memory.slugTaken'))
    expect(wrapper.emitted('done')).toBeFalsy()
  })
})

// uc.memory.participants, "at creation" (owner decision 2026-10-14): in a tenant or group scope a member's create offers
// "I was there", ticked by default and not remembered; it never appears in a personal scope, on a chapter, or without the
// member's own id and the open scope (a share guest's create is core's name dialog - `ShareView.naming.test.ts`).
describe('MemoryNodeDialog: "I was there"', () => {
  const wasThere = (w: ReturnType<typeof mountDialog>) => w.find('.was-there')
  async function create(w: ReturnType<typeof mountDialog>, name = 'Rom') {
    await w.find('input').setValue(name)
    await w.find('form').trigger('submit')
    await Promise.resolve()
  }

  for (const kind of [ScopeKind.Tenant, ScopeKind.Group]) {
    it(`${kind}: offered ticked; kept, the creator's own id is the Memory's first participant`, async () => {
      activeSpace.kind = kind
      const w = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
      expect(wasThere(w).exists()).toBe(true)
      expect((wasThere(w).element as HTMLInputElement).checked, 'ticked by default').toBe(true)
      expect(w.text()).toContain(t('memory.participants.wasThere'))
      await create(w)
      expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Rom', null, { location: null, participants: [ME] }, ROOT_CREATE)
    })
  }

  it('unticked: no participants are written', async () => {
    activeSpace.kind = ScopeKind.Tenant
    const w = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wasThere(w).setValue(false)
    await create(w)
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Rom', null, { location: null, participants: [] }, ROOT_CREATE)
  })

  it('is not remembered: the next create offers it ticked again', async () => {
    activeSpace.kind = ScopeKind.Tenant
    const first = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    await wasThere(first).setValue(false)
    first.unmount()
    const next = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    expect((wasThere(next).element as HTMLInputElement).checked).toBe(true)
  })

  it('is absent in a personal scope, and nothing is recorded there', async () => {
    activeSpace.kind = ScopeKind.Personal
    const w = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })
    expect(wasThere(w).exists()).toBe(false)
    expect(w.text()).not.toContain(t('memory.participants.wasThere'))
    await create(w)
    expect(createMemory).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Rom', null, { location: null, participants: [] }, ROOT_CREATE)
  })

  it('is absent on a chapter, without the own id, and for a scope other than the open one', async () => {
    activeSpace.kind = ScopeKind.Tenant
    expect(wasThere(mountDialog({ kind: CHAPTER_KIND, parentId: 'italy', parentKey: new Uint8Array(32), parentEpoch: 0, parentScopeEpoch: 0 })).exists(), 'chapter').toBe(false)
    expect(wasThere(mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })).exists(), 'control').toBe(true)
    account.userId = ''
    expect(wasThere(mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })).exists(), 'no own id').toBe(false)
    account.userId = ME
    activeSpace.scopeId = null
    expect(wasThere(mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null, parentEpoch: null, parentScopeEpoch: null })).exists(), 'no open scope').toBe(false)
  })
})
