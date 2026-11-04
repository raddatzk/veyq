// @vitest-environment happy-dom
// "Add files only" in core's folder share dialog (owner 2026-11-04): the third role, after the Memory dialog's order, offered
// exactly where the host's offer says `upload` for a folder (or the space's top level) and an application filled the seam
// (`app.folderUploadShare`) - over the REAL registry, so the role reaches Depot's registration and its create
// (`createDropBoxWithLink`, stubbed at its module: a drop box named by the link, under the folder, with its first link). The
// top level mounts the dialog with no node: no list, that role alone. Core's link layer is stubbed as in
// `FolderShareDialog.test.ts`; `dropBox.wire.test.ts` drives the real drop-box writes.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('./shareLink', () => ({ buildShareLink: vi.fn(), recoverShareLink: vi.fn() }))
vi.mock('./manageShares', async () => ({
  rekeyingClosedLinks: (await import('vue')).shallowReactive(new Set<string>()),
  mintShare: vi.fn(),
  remintShare: vi.fn(),
  listSharesFor: vi.fn(),
  revokeShare: vi.fn(),
  reactivateShare: vi.fn(),
  deleteShare: vi.fn(),
  isReissueRequired: vi.fn(),
  isShareDeleted: vi.fn(),
  readShareName: vi.fn(),
  readShareNote: vi.fn(),
  hasLocaleText: vi.fn(),
}))
vi.mock('../../features/depot/dropBox', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../features/depot/dropBox')>()),
  createDropBoxWithLink: vi.fn(),
}))

import FolderShareDialog from './FolderShareDialog.vue'
import * as manage from './manageShares'
import { buildShareLink } from './shareLink'
import { createDropBoxWithLink } from '../../features/depot/dropBox'
import { DROPBOX_KIND, FILE_KIND } from '../../features/depot/kinds'
import { ShareRole } from '../../api/generated/sharing'
import { treeStore } from '../node/treeStore'
import { FOLDER_KIND } from '../node/kind'
import { treeNode } from '../../test/models'
import { realI18n, t } from '../../test/i18n'
import { httpError } from '../../test/httpError'

const createLink = vi.mocked(createDropBoxWithLink)
const SCOPE_ID = '5c09e000-0000-4000-8000-0000000000dd'
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f0'
const EPOCHS = { epoch: 3, key: new Uint8Array(32) }
const FOLDER_KEY = new Uint8Array(32).fill(4)
const FOLDER_EPOCH = 2
const FOLDER_SCOPE_EPOCH = 1

const stubs = {
  VeyqDialog: { props: ['visible', 'header'], template: '<div v-if="visible"><h1 class="header">{{ header }}</h1><slot /></div>' },
  VeyqToggleGroup: {
    props: ['options', 'modelValue'],
    emits: ['update:modelValue'],
    template:
      '<div class="select-button"><button v-for="o in options" :key="o.value" class="opt" :data-value="o.value" @click="$emit(\'update:modelValue\', o.value)">{{ o.label }}</button></div>',
  },
  VeyqButton: { props: ['label', 'disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>' },
  VeyqInput: {
    props: ['modelValue', 'placeholder'],
    emits: ['update:modelValue'],
    template: '<input class="input" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqSwitch: { props: ['modelValue'], emits: ['update:modelValue'], template: '<button v-bind="$attrs" @click="$emit(\'update:modelValue\', !modelValue)" />' },
  VeyqDateField: true,
  LocaleMarkdownEditor: true,
}

type Offer = { view?: boolean; contribute?: boolean; upload?: boolean; readonly?: boolean; nodeKind?: string; nodeScopeEpoch?: number }
function mountFolder(offer: Offer = { view: true, contribute: true, upload: true }) {
  return mount(FolderShareDialog, {
    props: {
      scopeId: SCOPE_ID, nodeId: FOLDER_ID, nodeKey: FOLDER_KEY, epochs: EPOCHS, nodeEpoch: FOLDER_EPOCH, nodeScopeEpoch: FOLDER_SCOPE_EPOCH,
      nodeName: 'Trips', nodeKind: FOLDER_KIND, ...offer,
    },
    global: { plugins: [realI18n()], stubs },
  })
}
/** The top level's dialog, as core's `NodeDialogs` mounts it: no node. */
function mountTop(offer: { upload?: boolean; view?: boolean; contribute?: boolean } = { upload: true }) {
  return mount(FolderShareDialog, { props: { scopeId: SCOPE_ID, epochs: EPOCHS, ...offer }, global: { plugins: [realI18n()], stubs } })
}
type W = ReturnType<typeof mountFolder>
const byText = (w: W, text: string) => w.findAll('button').find((b) => b.text() === text)
const roles = (w: W) => w.findAll('.opt').map((o) => o.text())
async function openForm(w: W) {
  await flushPromises()
  await byText(w, t('share.createNew'))!.trigger('click')
}
async function chooseUpload(w: W, name: string) {
  await w.find(`.opt[data-value="${ShareRole.WriteOnly}"]`).trigger('click')
  await w.find('#folder-share-name').setValue(name)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(manage.listSharesFor).mockResolvedValue([])
  vi.mocked(buildShareLink).mockImplementation((id) => `https://x/s/${id}#k=abc`)
  createLink.mockResolvedValue({ shareId: 'drop-link', kShare: new Uint8Array(32) })
  treeStore.nodes = [treeNode({ id: FOLDER_ID, kind: FOLDER_KIND, name: 'Trips', parentId: null })]
})

describe('FolderShareDialog — "Add files only"', () => {
  it('offers the three roles in the Memory dialog\'s order, the upload-only one in the registering application\'s words', async () => {
    const w = mountFolder()
    await openForm(w)
    expect(roles(w)).toEqual([t('share.roleViewer'), t('share.folder.roleContributor'), t('share.folder.roleUploader')])
    expect(w.text()).toContain(t('share.noteSection')) // a READ link's form carries the note
    await w.find(`.opt[data-value="${ShareRole.WriteOnly}"]`).trigger('click')
    expect(w.text()).toContain(t('depot.dropBox.uploadShareHint'))
    expect(w.text(), 'an upload-only link carries no note').not.toContain(t('share.noteSection'))
    expect(w.get('#folder-share-name').attributes('placeholder')).toBe(t('share.folder.uploadNamePlaceholder'))
  })

  it.each<[string, Offer]>([
    ['the host offers no upload', { view: true, contribute: true }],
    ['the node is no folder (a file\'s link)', { view: true, contribute: true, upload: true, nodeKind: FILE_KIND }],
    ['the host handed no scope epoch to create under', { view: true, contribute: true, upload: true, nodeScopeEpoch: undefined }],
    ['the scope is pending deletion', { view: true, contribute: true, upload: true, readonly: true }],
  ])('offers no upload-only link where %s', async (_, offer) => {
    const w = mountFolder(offer)
    await flushPromises()
    if (offer.readonly) {
      expect(byText(w, t('share.createNew')), 'a pending deletion mints nothing').toBeUndefined()
      return
    }
    await openForm(w)
    expect(roles(w), 'the READ and READ_WRITE roles render').toEqual([t('share.roleViewer'), expect.any(String)])
    expect(w.find(`.opt[data-value="${ShareRole.WriteOnly}"]`).exists()).toBe(false)
  })

  it('offers only "Add files only" where the host grants neither READ nor READ_WRITE', async () => {
    const w = mountFolder({ upload: true })
    await openForm(w)
    expect(roles(w)).toEqual([t('share.folder.roleUploader')])
  })

  it('creates Depot\'s drop box under the folder, named by the link, with its first link - and shows the link', async () => {
    const w = mountFolder()
    await openForm(w)
    await chooseUpload(w, '  Einsendungen ')
    await byText(w, t('share.create'))!.trigger('click')
    await flushPromises()

    expect(createLink).toHaveBeenCalledTimes(1)
    expect(createLink).toHaveBeenCalledWith(
      SCOPE_ID, EPOCHS, { id: FOLDER_ID, key: FOLDER_KEY, epoch: FOLDER_EPOCH, scopeEpoch: FOLDER_SCOPE_EPOCH }, 'Einsendungen', '', null,
    )
    expect(manage.mintShare, 'no link of the folder itself').not.toHaveBeenCalled()
    expect((w.find('.folder-share-link').element as HTMLInputElement).value).toBe('https://x/s/drop-link#k=abc')
    expect(w.emitted('done'), 'the host re-reads the tree: the drop box is a row now').toHaveLength(1)
  })

  it('waits for a name: the drop box needs one', async () => {
    const w = mountFolder()
    await openForm(w)
    await chooseUpload(w, '   ')
    expect(byText(w, t('share.create'))!.attributes('disabled')).toBeDefined()
    await w.find('#folder-share-name').setValue('Einsendungen')
    expect(byText(w, t('share.create'))!.attributes('disabled')).toBeUndefined()
  })

  it('refuses a name a child of the folder already has, before anything is sent', async () => {
    treeStore.nodes = [
      treeNode({ id: FOLDER_ID, kind: FOLDER_KIND, name: 'Trips', parentId: null }),
      treeNode({ id: 'box', kind: DROPBOX_KIND, name: 'Einsendungen', parentId: FOLDER_ID }),
    ]
    const w = mountFolder()
    await openForm(w)
    await chooseUpload(w, 'einsendungen')
    await byText(w, t('share.create'))!.trigger('click')
    await flushPromises()
    expect(createLink).not.toHaveBeenCalled()
    expect(w.get('.folder-share-name-taken').text()).toBe(t('node.nameTakenBy', { kind: t('depot.dropBox.aDropBox'), name: 'Einsendungen' }))
  })

  it('says a name the server found taken (the race backstop), and still has the host re-read the tree', async () => {
    createLink.mockRejectedValueOnce(httpError(409))
    const w = mountFolder()
    await openForm(w)
    await chooseUpload(w, 'Einsendungen')
    await byText(w, t('share.create'))!.trigger('click')
    await flushPromises()
    expect(w.get('.folder-share-name-taken').text()).toBe(t('node.slugTaken'))
    expect(w.emitted('done')).toHaveLength(1)
  })
})

describe('FolderShareDialog at the space\'s top level', () => {
  it('lists nothing and offers the upload-only link alone; it lands at the scope root and Done closes', async () => {
    const w = mountTop()
    await flushPromises()
    expect(manage.listSharesFor).not.toHaveBeenCalled()
    expect(roles(w)).toEqual([t('share.folder.roleUploader')])
    await w.find('#folder-share-name').setValue('Einsendungen')
    await byText(w, t('share.create'))!.trigger('click')
    await flushPromises()
    expect(createLink).toHaveBeenCalledWith(SCOPE_ID, EPOCHS, null, 'Einsendungen', '', null)
    await byText(w, t('common.done'))!.trigger('click')
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('never offers READ or READ_WRITE there, whatever the offer says - and nothing without upload', async () => {
    const all = mountTop({ upload: true, view: true, contribute: true })
    await flushPromises()
    expect(roles(all)).toEqual([t('share.folder.roleUploader')])
    const none = mountTop({ view: true, contribute: true })
    await flushPromises()
    expect(none.find('#folder-share-name').exists(), 'the form renders').toBe(true)
    expect(roles(none)).toEqual([])
    expect(byText(none, t('share.create'))!.attributes('disabled')).toBeDefined()
  })

  it('mints nothing during a pending deletion, even where the offer says upload', async () => {
    const w = mount(FolderShareDialog, {
      props: { scopeId: SCOPE_ID, epochs: EPOCHS, upload: true, readonly: true }, global: { plugins: [realI18n()], stubs },
    })
    await flushPromises()
    expect(w.find('#folder-share-name').exists(), 'the form renders').toBe(true)
    expect(roles(w)).toEqual([])
    expect(byText(w, t('share.create'))!.attributes('disabled')).toBeDefined()
  })
})
