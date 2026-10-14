// @vitest-environment happy-dom
// Who uploaded an opened file's current content, as a MEMBER sees it (ADR 0083 Decision 8, uc.files.view-file step 0):
// `FileDetail` mounted as the host does, with the real catalog and a real router (the name is a real link, clicked).
// Stubbed by path: Files' client (`listFiles`, typed by the contract - it carries the `uploaderKey`), core's people
// directory (`loadScopePeople`: the scope's CURRENT members), the download's chunk reader and core's tree store. A guest
// session's page is `FileDetail.guest.test.ts`'s (its source has no uploader door, so it names nobody).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import type { FileListPage, FileUploaderName } from '../../../api/generated/files'

vi.mock('../pdf/pdfEngine', async (orig) => ({ ...(await orig<typeof import('../pdf/pdfEngine')>()), openPdf: vi.fn() }))
vi.mock('../api', () => ({ filesApi: vi.fn() }))
vi.mock('../../../core/favorites/favoritesStore', () => ({ isFavorite: vi.fn(() => false), toggleFavorite: vi.fn() }))
vi.mock('../download', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../download')>()), downloadFile: vi.fn(), fileChunkProvider: vi.fn(),
}))
vi.mock('../../../core/node/treeStore', async () => {
  const { reactive } = await import('vue')
  return { treeStore: reactive({ nodes: [], loaded: true, error: false }), loadTree: vi.fn(async () => {}) }
})
vi.mock('../../../core/people/directory', async (orig) => ({
  ...(await orig<typeof import('../../../core/people/directory')>()), loadScopePeople: vi.fn(),
}))

import FileDetail from './FileDetail.vue'
import { filesApi } from '../api'
import { fileChunkProvider } from '../download'
import { loadScopePeople, type Person } from '../../../core/people/directory'
import { personHash } from '../../../core/node/location'
import { providerOf } from '../../../test/fileChunks'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { wrapContentKey, type TreeNode } from '../../../core/node/tree'
import { identityNameContext } from '../../../core/sharing/guestIdentity'
import { epochsOf } from '../../spaces/epochsTestUtil'
import type { EpochKeys } from '../../spaces/epochs'
import { realI18n, t } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'
import { treeNode, memberAccessOf, memberTreeOf } from '../../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const D = '5c09e000-0000-4000-8000-0000000000d0'
const R = '5c09e000-0000-4000-8000-0000000000e1'
const ANNA = 'aaaaaaaa-0000-4000-8000-000000000001'
const GONE = 'aaaaaaaa-0000-4000-8000-0000000000ff'
const GUEST_IDENTITY = 'bbbbbbbb-0000-4000-8000-000000000002'
const DOCS = treeNode({ id: D, kind: FOLDER_KIND, name: 'Docs', parentId: null })
const REPORT = treeNode({ id: R, kind: FILE_KIND, name: 'Report.txt', parentId: D })
const BLOB = { blobId: 'b-1', chunkCount: 1, byteSize: 100, wrappedBlobKey: 'eA==' }
const anna: Person = { userId: ANNA, username: 'anna', realName: 'Anna Meyer', displayName: 'Anna Meyer' }

let router: Router
/** A tree whose keys really open (inbox #431): `Docs` under the epoch key, `Report.txt` under `Docs`' key. */
type World = { nodes: TreeNode[]; node: TreeNode; epochs: EpochKeys }
async function mountDetail(
  uploaderKey: string | null | undefined, fileBlob: typeof BLOB | null = BLOB,
  o: { uploaderName?: FileUploaderName; world?: World } = {},
) {
  const node = o.world?.node ?? REPORT
  stubApi(filesApi, {
    listFiles: {
      items: [{ nodeId: node.id, fileBlob, ...(uploaderKey === undefined ? {} : { uploaderKey }), ...(o.uploaderName ? { uploaderName: o.uploaderName } : {}) }],
    } satisfies FileListPage,
  })
  const epochs = o.world?.epochs ?? epochsOf(v1.randomBytes(32))
  const nodes = o.world?.nodes ?? [DOCS, REPORT]
  const tree = memberTreeOf(() => nodes, { scopeId: SCOPE, epochs })
  await router.push('/')
  const w = mount(FileDetail, {
    props: {
      node, scopeId: SCOPE, epochs, contentKey: v1.randomBytes(32), children: { folders: [], byKind: new Map() },
      access: memberAccessOf({ scopeId: SCOPE, epochs }), tree,
    },
    global: { plugins: [router, realI18n()], directives: { tooltip: {} } },
  })
  await flushPromises()
  return w
}
type Wrapper = Awaited<ReturnType<typeof mountDetail>>
const uploaderLine = (w: Wrapper) => w.find('.veyq-node-header [data-testid="file-uploader"]')
/** The line as assistive technology reads it: its text without the decorative (`aria-hidden`) avatar, spaces collapsed. */
const spoken = (w: Wrapper) => {
  const copy = uploaderLine(w).element.cloneNode(true) as HTMLElement
  copy.querySelectorAll('[aria-hidden="true"]').forEach((e) => e.remove())
  return (copy.textContent ?? '').replace(/\s+/g, ' ').trim()
}

beforeEach(() => {
  vi.mocked(fileChunkProvider).mockReset().mockImplementation(() => providerOf(new Uint8Array([1, 2, 3])))
  vi.mocked(loadScopePeople).mockReset().mockResolvedValue([anna])
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
})

describe('an opened file names who uploaded its current content (ADR 0083 Decision 8)', () => {
  it('a member: "Uploaded by" their display name with their avatar, in the header, a real link to their profile', async () => {
    const w = await mountDetail(`user:${ANNA}`)
    const line = uploaderLine(w)
    expect(line.exists()).toBe(true)
    expect(spoken(w)).toBe(`${t('files.detail.uploadedBy')} Anna Meyer`)
    expect(loadScopePeople).toHaveBeenCalledWith(SCOPE)
    expect(line.find('.p-avatar').exists()).toBe(true)
    const link = line.get('a')
    expect(link.attributes('href')).toBe(`/${personHash(ANNA)}`)
    await link.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.hash).toBe(personHash(ANNA))
  })

  it('a member who is no longer in the scope: "former member", greyed, with no link', async () => {
    const w = await mountDetail(`user:${GONE}`)
    const line = uploaderLine(w)
    expect(spoken(w)).toBe(`${t('files.detail.uploadedBy')} ${t('people.formerMember')}`)
    expect(line.find('a').exists()).toBe(false)
    expect(line.find('.is-former').exists()).toBe(true)
  })

  it('a guest\'s upload the listing serves no name for reads "a guest" - no link, no directory read', async () => {
    const w = await mountDetail(`share:${GUEST_IDENTITY}`)
    const line = uploaderLine(w)
    expect(spoken(w)).toBe(`${t('files.detail.uploadedBy')} ${t('files.detail.aGuest')}`)
    expect(line.find('a').exists()).toBe(false)
    expect(line.text()).not.toContain(GUEST_IDENTITY)
    expect(loadScopePeople).not.toHaveBeenCalled()
  })

  it('names nobody where nothing is known: no content, no key, an unknown key shape, or a directory that could not be read', async () => {
    // control: the same mount with a member's key names them (the line exists at all)
    expect(uploaderLine(await mountDetail(`user:${ANNA}`)).exists()).toBe(true)

    expect(uploaderLine(await mountDetail(null, null)).exists(), 'a file without content').toBe(false)
    expect(uploaderLine(await mountDetail(undefined)).exists(), 'a listing without the field').toBe(false)
    expect(uploaderLine(await mountDetail(`owner:${ANNA}`)).exists(), 'a key of no known shape').toBe(false)
    vi.mocked(loadScopePeople).mockRejectedValue(new Error('offline'))
    const failed = await mountDetail(`user:${ANNA}`)
    expect(uploaderLine(failed).exists(), 'an unread directory says nothing about membership: not "former"').toBe(false)
  })
})

// --- a guest's name (inbox #431) ---------------------------------------------------------------------------------------

const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_6'
const OTHER_SHARE = 'Oth3rIdOth3rIdOth3rIdOth3rIdOth3rIdOth3r-_6'
const OTHER_IDENTITY = 'bbbbbbbb-0000-4000-8000-000000000003'
const F = '5c09e000-0000-4000-8000-0000000000f0'
const at = (nodeId: string) => ({ scopeId: SCOPE, nodeId, epoch: 0, placementVersion: 0 })

/** `Docs` at the scope root holding `Report.txt`, every key really wrapped - the chain a member's tree opens. */
function folderWorld() {
  const e0 = v1.randomBytes(32)
  const docsKey = v1.randomBytes(32)
  const reportKey = v1.randomBytes(32)
  const docs = treeNode({ id: D, kind: FOLDER_KIND, name: 'Docs', parentId: null, wrappedContentKey: wrapContentKey(e0, at(D), docsKey) })
  const report = treeNode({ id: R, kind: FILE_KIND, name: 'Report.txt', parentId: D, wrappedContentKey: wrapContentKey(docsKey, at(R), reportKey) })
  return { world: { nodes: [docs, report], node: report, epochs: epochsOf(e0) }, docsKey, reportKey }
}

/** A drop box holding the identity folder `Clara` of `folderIdentity`, which holds `Report.txt`. */
function dropBoxWorld(folderIdentity: string): World {
  const box = treeNode({ id: D, kind: DROPBOX_KIND, name: 'Inbox', parentId: null })
  const folder = treeNode({ id: F, kind: FOLDER_KIND, name: 'Clara', parentId: D, shareIdentityId: folderIdentity, wrappedIdentityKey: 'd3JhcA==' })
  const report = treeNode({ id: R, kind: FILE_KIND, name: 'Report.txt', parentId: F })
  return { nodes: [box, folder, report], node: report, epochs: epochsOf(v1.randomBytes(32)) }
}

/** The guest's display name as `createGuestIdentity` seals it: under `key`, bound to (scope, share, identity). */
const sealedAs = (key: Uint8Array, name: string, shareId = SHARE): FileUploaderName =>
  ({ sealedName: b64std(v1.seal(identityNameContext(SCOPE, shareId, GUEST_IDENTITY), key, v1.utf8(name))), shareId })

describe('a guest\'s upload is named by the name the guest gave itself (inbox #431)', () => {
  it('a guest who took a member\'s name is still told apart: the guest\'s is marked, the member\'s is not (inbox #433)', async () => {
    const { world, docsKey } = folderWorld()
    const guest = await mountDetail(`share:${GUEST_IDENTITY}`, BLOB, { world, uploaderName: sealedAs(docsKey, 'Anna Meyer') })
    expect(spoken(guest)).toBe('Uploaded by Anna Meyer (guest)')
    expect(uploaderLine(guest).find('a').exists()).toBe(false)
    const member = await mountDetail(`user:${ANNA}`)
    expect(spoken(member)).toBe('Uploaded by Anna Meyer')
  })

  const guestKey = `share:${GUEST_IDENTITY}`
  const aGuest = () => `${t('files.detail.uploadedBy')} ${t('files.detail.aGuest')}`

  it('a folder link\'s guest: its name, sealed under the FOLDER\'s key above the file, opens - no link, no directory read', async () => {
    const { world, docsKey } = folderWorld()
    const w = await mountDetail(guestKey, BLOB, { world, uploaderName: sealedAs(docsKey, 'Ben Gast') })
    expect(spoken(w)).toBe('Uploaded by Ben Gast (guest)') // marked as a guest's (inbox #433)
    expect(uploaderLine(w).find('a').exists()).toBe(false)
    expect(loadScopePeople).not.toHaveBeenCalled()
  })

  it('a single-file link\'s guest: its name, sealed under the file\'s own key, opens', async () => {
    const { world, reportKey } = folderWorld()
    const w = await mountDetail(guestKey, BLOB, { world, uploaderName: sealedAs(reportKey, 'Ben Gast') })
    expect(spoken(w)).toBe('Uploaded by Ben Gast (guest)') // marked as a guest's (inbox #433)
  })

  it('"a guest" where the name does not open: another key, another share bound, a chain the session cannot open', async () => {
    const { world, docsKey } = folderWorld()
    // control: the same world and seal opens
    expect(spoken(await mountDetail(guestKey, BLOB, { world, uploaderName: sealedAs(docsKey, 'Ben Gast') }))).toContain('Ben Gast')

    const foreign = sealedAs(v1.randomBytes(32), 'Ben Gast')
    expect(spoken(await mountDetail(guestKey, BLOB, { world, uploaderName: foreign })), 'a key no node holds').toBe(aGuest())
    const rebound = { ...sealedAs(docsKey, 'Ben Gast'), shareId: OTHER_SHARE }
    expect(spoken(await mountDetail(guestKey, BLOB, { world, uploaderName: rebound })), 'the seal bound to another share').toBe(aGuest())
    const locked = { ...world, epochs: epochsOf(v1.randomBytes(32)) }
    expect(spoken(await mountDetail(guestKey, BLOB, { world: locked, uploaderName: sealedAs(docsKey, 'Ben Gast') })), 'a chain that does not open').toBe(aGuest())
    expect(spoken(await mountDetail(guestKey, BLOB, { world, uploaderName: { sealedName: '%%%', shareId: SHARE } })), 'no base64').toBe(aGuest())
  })

  it('a drop box\'s guest: named by ITS identity folder, not by a seal under its own key', async () => {
    const underKUp = sealedAs(v1.randomBytes(32), 'Clara Sealed')
    const w = await mountDetail(guestKey, BLOB, { world: dropBoxWorld(GUEST_IDENTITY), uploaderName: underKUp })
    expect(spoken(w)).toBe('Uploaded by Clara (guest)') // the identity folder's name is the guest's own: marked too (inbox #433)
    expect(uploaderLine(w).find('a').exists()).toBe(false)

    // the folder of ANOTHER guest names nobody else: this file's uploader is still "a guest"
    const other = await mountDetail(guestKey, BLOB, { world: dropBoxWorld(OTHER_IDENTITY), uploaderName: underKUp })
    expect(spoken(other)).toBe(aGuest())
  })
})
