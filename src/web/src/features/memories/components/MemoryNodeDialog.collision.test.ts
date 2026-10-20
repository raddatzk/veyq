// @vitest-environment happy-dom
// Memories' create dialog names the sibling a new Memory or chapter collides with, before anything is sent (inbox #389,
// through core's `siblingCollision`). Mounted into the document, its submit button CLICKED; the creates are mocked
// (nothing may be sent on a collision), the registry and the foldings are the real ones.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'

vi.mock('../memoryNodes', () => ({ createMemory: vi.fn(), createChapter: vi.fn() }))

import MemoryNodeDialog from './MemoryNodeDialog.vue'
import { createMemory, createChapter } from '../memoryNodes'
import { MEMORY_KIND, CHAPTER_KIND } from '../kinds'
import { treeStore } from '../../../core/node/treeStore'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import { realI18n, t } from '../../../test/i18n'
import { treeNode } from '../../../test/models'

const stubs = {
  Dialog: { props: ['header'], template: '<div><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
  Checkbox: true,
}
const SCOPE = '11111111-1111-1111-1111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(7))
const KEY = new Uint8Array(32)
const node = (id: string, kind: string, name: string, parentId: string | null) => treeNode({ id, kind, name, parentId })

type DialogProps = InstanceType<typeof MemoryNodeDialog>['$props']
let wrapper: VueWrapper | null = null
function mountDialog(props: Omit<DialogProps, 'scopeId' | 'epochs'>): VueWrapper {
  wrapper = mount(MemoryNodeDialog, { props: { scopeId: SCOPE, epochs: EPOCHS, ...props }, global: { plugins: [realI18n()], stubs }, attachTo: document.body })
  return wrapper
}
async function typeAndClickSubmit(w: VueWrapper, value: string) {
  await w.find('input').setValue(value)
  ;(w.find('button[type="submit"]').element as HTMLButtonElement).click()
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await w.vm.$nextTick()
}

beforeEach(() => {
  vi.clearAllMocks()
  setMapsConsent(false)
  vi.mocked(createMemory).mockResolvedValue({ nodeId: 'n', memoryKey: KEY })
  vi.mocked(createChapter).mockResolvedValue({ nodeId: 'n', chapterKey: KEY })
  treeStore.nodes = [node('f1', 'FOLDER', 'Italien', null), node('m1', MEMORY_KIND, 'Rom', 'f1'), node('c1', CHAPTER_KIND, 'Tag 1', 'm1')]
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  treeStore.nodes = []
})

describe('MemoryNodeDialog names the sibling a name collides with (inbox #389)', () => {
  it('a Memory onto a folder\'s slug at the root sends nothing and names the folder', async () => {
    const w = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await typeAndClickSubmit(w, 'italien')

    expect(createMemory).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Italien' }))
  })

  it('a Memory onto a sibling Memory in a folder names the Memory', async () => {
    const w = mountDialog({ kind: MEMORY_KIND, parentId: 'f1', parentKey: KEY })
    await typeAndClickSubmit(w, 'ROM!')

    expect(createMemory).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('memory.aMemory'), name: 'Rom' }))
  })

  it('a chapter onto a sibling chapter names the chapter; another name is sent', async () => {
    const w = mountDialog({ kind: CHAPTER_KIND, parentId: 'm1', parentKey: KEY })
    await typeAndClickSubmit(w, 'tag-1')
    expect(createChapter).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('memory.aChapter'), name: 'Tag 1' }))

    await typeAndClickSubmit(w, 'Tag 2')
    expect(createChapter).toHaveBeenCalledWith(SCOPE, EPOCHS, KEY, 'Tag 2', 'm1')
  })

  it('a collision the loaded tree cannot see still ends on the server\'s 409', async () => {
    vi.mocked(createMemory).mockRejectedValueOnce(httpError(409))
    const w = mountDialog({ kind: MEMORY_KIND, parentId: null, parentKey: null })
    await typeAndClickSubmit(w, 'Neu')

    expect(createMemory).toHaveBeenCalledOnce()
    expect(w.find('.msg').text()).toBe(t('memory.slugTaken'))
  })
})
