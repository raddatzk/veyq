// @vitest-environment happy-dom
// Who uploaded an opened file's current content, as a MEMBER sees it (ADR 0083 Addendum, uc.files.view-file step 0):
// `FileDetail` mounted as the host does, with the real catalog and a real router (the name is a real link, clicked).
// Stubbed by path: Files' client (`listFiles`, typed by the contract - it carries the `uploaderKey`), core's people
// directory (`loadScopePeople`: the scope's CURRENT members), the download's chunk reader and core's tree store. A guest
// session's page is `FileDetail.guest.test.ts`'s (its source has no uploader door, so it names nobody).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import type { FileListPage } from '../../../api/generated/files'

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
import { FILE_KIND } from '../kinds'
import { FOLDER_KIND } from '../../../core/node/kind'
import { epochsOf } from '../../spaces/epochsTestUtil'
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
async function mountDetail(uploaderKey: string | null | undefined, fileBlob: typeof BLOB | null = BLOB) {
  stubApi(filesApi, { listFiles: { items: [{ nodeId: R, fileBlob, ...(uploaderKey === undefined ? {} : { uploaderKey }) }] } satisfies FileListPage })
  const epochs = epochsOf(v1.randomBytes(32))
  const tree = memberTreeOf(() => [DOCS, REPORT], { scopeId: SCOPE, epochs })
  await router.push('/')
  const w = mount(FileDetail, {
    props: {
      node: REPORT, scopeId: SCOPE, epochs, contentKey: v1.randomBytes(32), children: { folders: [], byKind: new Map() },
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

describe('an opened file names who uploaded its current content (ADR 0083 Addendum)', () => {
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

  it('a guest\'s upload reads "a guest" - no name, no link, no directory read', async () => {
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
