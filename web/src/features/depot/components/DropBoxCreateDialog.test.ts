// @vitest-environment happy-dom
// The drop box's create dialog: a name, then core's node create with Depot's kind. A name that collides with a sibling
// in the loaded tree is refused before sending, naming that sibling (inbox #389); the server's 409 is the race backstop.
// Mounted into the document, its submit button CLICKED; `createDropBox` is mocked, registry and foldings are real.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { httpError } from '../../../test/httpError'

vi.mock('../dropBox', () => ({ createDropBox: vi.fn() }))

import DropBoxCreateDialog from './DropBoxCreateDialog.vue'
import { createDropBox } from '../dropBox'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import { treeStore } from '../../../core/node/treeStore'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { realI18n, t } from '../../../test/i18n'
import { treeNode } from '../../../test/models'

const stubs = {
  Dialog: { props: ['header'], template: '<div><slot /></div>' },
  InputText: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  Button: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  Message: { template: '<div class="msg"><slot /></div>' },
}
const SCOPE = '11111111-1111-1111-1111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(7))
const KEY = new Uint8Array(32)

let wrapper: VueWrapper | null = null
async function createWith(value: string) {
  wrapper = mount(DropBoxCreateDialog, {
    props: { kind: DROPBOX_KIND, scopeId: SCOPE, epochs: EPOCHS, parentId: 'p', parentKey: KEY },
    global: { plugins: [realI18n()], stubs },
    attachTo: document.body,
  })
  await wrapper.find('input').setValue(value)
  ;(wrapper.find('button[type="submit"]').element as HTMLButtonElement).click()
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await wrapper.vm.$nextTick()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(createDropBox).mockResolvedValue('new')
  treeStore.nodes = [
    treeNode({ id: 'd1', kind: DROPBOX_KIND, name: 'Einsendungen', parentId: 'p' }),
    treeNode({ id: 'x1', kind: FILE_KIND, name: 'a-b', parentId: 'p' }),
  ]
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  treeStore.nodes = []
})

describe('DropBoxCreateDialog', () => {
  it('a name a sibling drop box takes sends nothing and names it', async () => {
    const w = await createWith('einsendungen')
    expect(createDropBox).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('depot.dropBox.aDropBox'), name: 'Einsendungen' }))
  })

  it('a drop box `a.b` collides with a file `a-b` (each folded by its own kind) and names the file', async () => {
    const w = await createWith('a.b')
    expect(createDropBox).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('depot.aFile'), name: 'a-b' }))
  })

  it('a free name is created under the parent, trimmed', async () => {
    const w = await createWith('  Fotos  ')
    expect(createDropBox).toHaveBeenCalledWith(SCOPE, EPOCHS, { id: 'p', key: KEY }, 'Fotos')
    expect(w.emitted('done')).toBeTruthy()
  })

  it.each([
    [409, 'node.slugTaken'],
    [500, 'depot.dropBox.createFailed'],
  ])('a %i from the server says %s', async (status, key) => {
    vi.mocked(createDropBox).mockRejectedValueOnce(httpError(status))
    const w = await createWith('Fotos')
    expect(w.find('.msg').text()).toBe(t(key))
    expect(w.emitted('done')).toBeFalsy()
  })
})
