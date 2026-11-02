// @vitest-environment happy-dom
// Core's name dialog names the sibling a create or rename collides with, before anything is sent (inbox #389). The
// dialog is mounted into the document and its submit button CLICKED, so the form's own submit runs as in a browser; the
// tree writes are mocked (nothing may be sent on a collision), the registry and the foldings are the real ones.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { httpError } from '../../test/httpError'

vi.mock('./tree', () => ({ createFolder: vi.fn(), renameNode: vi.fn() }))
vi.mock('../sharing/manageShares', () => ({ resealShareNames: vi.fn() }))

import FolderNameDialog from './FolderNameDialog.vue'
import { createFolder, renameNode } from './tree'
import { resealShareNames } from '../sharing/manageShares'
import { treeStore } from './treeStore'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { realI18n, t } from '../../test/i18n'
import { treeNode } from '../../test/models'

const stubs = {
  VeyqDialog: { props: ['header'], template: '<div><slot /></div>' },
  VeyqInput: { props: ['modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  VeyqButton: { props: ['type', 'label'], template: '<button :type="type || \'button\'" @click="$emit(\'click\', $event)">{{ label }}</button>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
}
const SCOPE = '11111111-1111-1111-1111-111111111111'
const EPOCHS = epochsOf(new Uint8Array(32).fill(7))
const node = (id: string, kind: string, name: string, parentId: string | null = 'p') => treeNode({ id, kind, name, parentId })

type DialogProps = InstanceType<typeof FolderNameDialog>['$props']
let wrapper: VueWrapper | null = null
function mountDialog(props: Omit<DialogProps, 'scopeId' | 'epochs'>): VueWrapper {
  wrapper = mount(FolderNameDialog, {
    props: { scopeId: SCOPE, epochs: EPOCHS, ...props },
    global: { plugins: [realI18n()], stubs },
    attachTo: document.body,
  })
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
  vi.mocked(createFolder).mockResolvedValue('new')
  vi.mocked(renameNode).mockResolvedValue(undefined)
  vi.mocked(resealShareNames).mockResolvedValue({ resealed: [], closed: [], skipped: [], failed: [] })
  treeStore.nodes = [
    node('f1', 'FOLDER', 'Trips'),
    node('x1', 'FILE', 'foto.jpg'),
    node('x2', 'FILE', 'Day1.md'),
    node('m1', 'MEMORY', 'Rom', null),
  ]
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  treeStore.nodes = []
})

describe('FolderNameDialog names the sibling a name collides with (inbox #389)', () => {
  it('a folder create onto a sibling folder\'s slug sends nothing and names it', async () => {
    const w = mountDialog({ mode: 'create', parentId: 'p', parentKey: new Uint8Array(32), parentEpoch: 0, parentScopeEpoch: 0 })
    await typeAndClickSubmit(w, '  trips ')

    expect(createFolder).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('node.aFolder'), name: 'Trips' }))
    expect(w.emitted('done')).toBeFalsy()
  })

  // ADR 0080 decision 16: a Memory's token input is `memories/rom`, a folder's `rom` — another route
  // group, so the two coexist (a sibling's own noun is still named: the file case below).
  it('a folder create at the scope root beside a Memory of its name is sent: the Memory is another route group', async () => {
    const w = mountDialog({ mode: 'create', parentId: null })
    await typeAndClickSubmit(w, 'rom')

    expect(createFolder).toHaveBeenCalledOnce()
    expect(w.find('.msg').exists()).toBe(false)
  })

  it('the same name under another parent is sent (the control: the check is per parent)', async () => {
    const w = mountDialog({ mode: 'create', parentId: null })
    await typeAndClickSubmit(w, 'Trips')

    expect(createFolder).toHaveBeenCalledWith(SCOPE, EPOCHS, 'Trips', null, { reread: expect.any(Function), pull: expect.any(Function), epochs: expect.any(Function) })
    expect(w.emitted('done')).toBeTruthy()
  })

  it('a file rename folds by the file\'s own rule: case collides, a space for a dot does not', async () => {
    const w = mountDialog({ mode: 'rename', node: node('x9', 'FILE', 'Old.txt') })
    await typeAndClickSubmit(w, 'FOTO.JPG')
    expect(renameNode).not.toHaveBeenCalled()
    expect(w.find('.msg').text()).toBe(t('node.nameTakenBy', { kind: t('depot.aFile'), name: 'foto.jpg' }))

    await typeAndClickSubmit(w, 'Day1 md')
    expect(renameNode).toHaveBeenCalledWith(SCOPE, EPOCHS, treeStore.nodes, 'x9', 'Day1 md')
  })

  it('a rename to its own name (another case) collides with nothing', async () => {
    const w = mountDialog({ mode: 'rename', node: treeStore.nodes[0] })
    await typeAndClickSubmit(w, 'TRIPS')

    expect(renameNode).toHaveBeenCalledWith(SCOPE, EPOCHS, treeStore.nodes, 'f1', 'TRIPS')
  })

  it('a collision the loaded tree cannot see still ends on the server\'s 409, unnamed', async () => {
    vi.mocked(createFolder).mockRejectedValueOnce(httpError(409))
    const w = mountDialog({ mode: 'create', parentId: 'p', parentKey: new Uint8Array(32), parentEpoch: 0, parentScopeEpoch: 0 })
    await typeAndClickSubmit(w, 'Elsewhere')

    expect(createFolder).toHaveBeenCalledOnce()
    expect(w.find('.msg').text()).toBe(t('node.slugTaken'))
  })
})
