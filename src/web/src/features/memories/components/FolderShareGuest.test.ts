// @vitest-environment happy-dom
// The guest's folder page as it ships (inbox #401 S2): core's `ShareTreePage` with the app's REAL registry (Memories
// and Depot, filled by `src/test/setup.ts`), the real UI layer and the real vue-router, every step a DOM click on the
// rendered element. The folder level is core's member `LevelView` (inbox #407: ONE unified list, every item core's
// `LevelRowItem`): a Memory, the sub-folder and the file are items of that list, the rows' data the applications' own
// — and nothing a member alone may do is on the page (pins, marks, the trash,
// share, rename, delete, a cover, a chapter), while every authenticated client throws (`test/guestMode.ts`). An item
// click keeps the `#k=` fragment that carries the share key, and the Memory opens in the member's detail (inbox #401
// S5; what that detail offers a guest is `MemoryDetail.guest.test.ts`).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../../depot/api', async (orig) => (await import('../../../test/guestMode')).guardedClients(await orig<object>()))
vi.mock('../useThumbnailPipeline', () => ({
  useThumbnailPipeline: () => ({ thumbs: new Map(), reconcile: vi.fn(), want: vi.fn(), revokeAll: vi.fn() }),
}))

import ShareTreePage from '../../../core/sharing/ShareTreePage.vue'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../../api/generated/sharing'
import { FILE_KIND } from '../../depot/kinds'
import { publicShareFilesApi } from '../../depot/api'
import { MEMORY_KIND } from '../kinds'
import { publicShareMediaApi } from '../api'
import { stubApi } from '../../../test/apiMock'
import { realI18n, t } from '../../../test/i18n'
import { guestAccessFor } from '../../../test/models'
import { authenticatedCalls } from '../../../test/guestMode'
import { setViewMode } from '../../../shared/state/useViewMode'

const SCOPE_ID = '88888888-8888-4888-8888-888888888888'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_f'
const ROOT = '6e5a0000-0000-4000-8000-000000000600'
const SUB = '6e5a0000-0000-4000-8000-000000000601'
const MEMORY = '6e5a0000-0000-4000-8000-000000000602'
const FILE = '6e5a0000-0000-4000-8000-000000000603'
const K_SHARE = v1.randomBytes(32)
const keys = { root: v1.randomBytes(32), sub: v1.randomBytes(32), memory: v1.randomBytes(32), file: v1.randomBytes(32) }
const HASH = `#k=${b64url(K_SHARE)}`

function child(id: string, parentId: string, parentKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, scopeEpoch: 0, placementRevision: 0 }
  return { blockedByOperator: false, id, parentId, kind, epoch: 0, scopeEpoch: 0, placementRevision: 0, encryptedName: sealName(parentKey, address, name), wrappedContentKey: wrapContentKey(parentKey, address, key) }
}
function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, scopeEpoch: 0 },
        child(SUB, ROOT, keys.root, keys.sub, 'Briefe', FOLDER_KIND),
        child(MEMORY, ROOT, keys.root, keys.memory, 'Rom 2026', MEMORY_KIND),
        child(FILE, ROOT, keys.root, keys.file, 'rezept.pdf', FILE_KIND),
      ],
    } as PublicShare,
  }
}

let router: Router
beforeEach(async () => {
  localStorage.clear()
  authenticatedCalls.length = 0
  setViewMode('grid')
  stubApi(publicShareMediaApi, { listShareMedia: { media: [], identities: [], more: false } })
  stubApi(publicShareFilesApi, { listShareFiles: { items: [{ nodeId: FILE, fileBlob: { blobId: 'b-1', chunkCount: 1, byteSize: 100, wrappedBlobKey: 'eA==' } }] } })
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

async function mountPage(role: ShareRole) {
  const h = head(role)
  const w = mount(ShareTreePage, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h, reload: async () => {}, access: guestAccessFor(h) },
    global: { plugins: [router, realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
  })
  await flushPromises()
  return w
}
type Page = Awaited<ReturnType<typeof mountPage>>

/** No control only a member has: pins and marks, the trash, share, rename, delete, a cover, a chapter. */
function expectNoMemberControl(w: Page) {
  expect(w.find('.memory-card-pin').exists(), 'pin').toBe(false)
  expect(w.find(`.veyq-level-toolbar button[aria-label="${t('node.trash.open')}"]`).exists(), "the space's trash").toBe(false)
  // the folder's one header action for a guest is its download (ADR 0082 decision 5)
  expect(w.findAll('.veyq-node-header-actions button').map((b) => b.attributes('aria-label')), 'share, rename, delete of the folder').toEqual([t('export.action')])
  for (const label of [
    t('node.favorite'), t('node.unfavorite'), t('media.marks.mark'), t('share.share'), t('node.rename'),
    t('node.delete'), t('node.trash.open'), t('node.cover.set'), t('node.cover.change'), t('memory.createChapterTitle'),
  ]) {
    expect(w.find(`[aria-label="${label}"]`).exists(), label).toBe(false)
    expect(w.findAll('button').some((b) => b.text() === label), label).toBe(false)
  }
}

describe("a folder link's guest page is the member's level (inbox #401 S2)", () => {
  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: the sub-folder, then the Memory and the file, in the member's two headed groups - nothing of a member's`, async () => {
      const w = await mountPage(role)
      expect(w.find('.veyq-node-header-title').text()).toBe('Familie')
      // The folders, then the contents, each group its own list under its heading (share view = member view).
      expect(w.findAll('section.veyq-level-group h2').map((h) => h.text())).toEqual([t('node.groupFolders'), t('node.groupContents')])
      expect(w.findAll('ul.veyq-level-list')).toHaveLength(2)
      // Folders first, then the rest mixed by name (the Memory and the file do not sit in sections of their own).
      expect(w.findAll('.veyq-level-item-title').map((c) => c.text())).toEqual(['Briefe', 'rezept.pdf', 'Rom 2026'])
      expect(w.findAll('li.veyq-level-item.is-folder .veyq-level-item-title').map((c) => c.text())).toEqual(['Briefe'])
      expect(w.find('li.veyq-level-item[data-kind="MEMORY"] .veyq-level-item-main').attributes('aria-label')).toBe('Rom 2026')
      expect(w.find('li.veyq-level-item[data-kind="MEMORY"] .veyq-level-item-meta').text()).toContain(t('memory.kindMemory'))
      expect(w.find('li.veyq-level-item[data-kind="FILE"] .veyq-level-item-meta').text()).toContain(t('depot.kindFile'))
      expectNoMemberControl(w)
      expect(authenticatedCalls).toEqual([])
    })
  }

  it('a click on the Memory item opens the Memory with the real router, the #k= fragment kept', async () => {
    const w = await mountPage(ShareRole.Read)
    const open = w.find('li.veyq-level-item[data-kind="MEMORY"] button.veyq-level-item-main')
    const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })
    open.element.dispatchEvent(click)
    await flushPromises()
    expect(router.currentRoute.value.query.node).toBe(MEMORY)
    expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
    await vi.waitFor(() => expect(w.find('.veyq-node-header-title').exists()).toBe(true))
    expect(w.find('.veyq-node-header-title').text()).toBe('Rom 2026')
    expect(w.find('.memory-detail').exists(), "the member's Memory detail, not a guest body").toBe(true)
    expect(w.findAll('a.veyq-breadcrumb__link').map((a) => a.text())).toEqual(['Familie', 'Rom 2026'])
    expect(authenticatedCalls).toEqual([])
  })

  it('a file a moderator blocked opens as the placeholder, with no reason, for a guest as for a member (uc.moderation.take-a-measure)', async () => {
    vi.mocked(publicShareFilesApi).mockClear()
    const h = head(ShareRole.Read)
    h.share.nodes = h.share.nodes.map((n) => (n.id === FILE ? { ...n, blockedByOperator: true } : n))
    await router.push(`/s/${SHARE_ID}?node=${FILE}${HASH}`)
    const w = mount(ShareTreePage, {
      props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: h, reload: async () => {}, access: guestAccessFor(h) },
      global: { plugins: [router, realI18n()], stubs: { MediaGrid: true, OptimisticTile: true, MediaLightbox: true } },
    })
    await flushPromises()
    expect(w.find('.node-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(w.find('.file-detail-uploader').exists(), 'the file page does not open').toBe(false)
    expect(publicShareFilesApi, 'nothing of the file is read').not.toHaveBeenCalled()
    expect(authenticatedCalls).toEqual([])
  })
})
