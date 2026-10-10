// @vitest-environment happy-dom
// A drop box inside a shared folder (inbox #380, owner decision 2026-10-08): a READ or READ_WRITE guest of folder
// "Familie" was explicitly given that tree, so it SEES the drop box below it — read-only. Its tile opens the drop box's
// guests' folders (names opened through the drop key the walk holds), a folder opens on core's folder page with its files
// and their download, and nowhere is there a form to send files, an upload, a replace, a 'New folder' or a delete: the
// server places nothing in an identity folder but its own identity (`NodesHandler`) and lets no guest move out of one
// (`ShareNodesHandler`). Here the app's real registry (Files and Memories, filled by `src/test/setup.ts`), the real
// `FolderShareBody`, the real PrimeVue and the real vue-router: every step is a DOM click on the rendered element, and
// the `#k=` fragment that carries the share key stays in the URL throughout.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { guestAccessFor } from '../../../test/models'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../api', () => ({ publicShareFilesApi: vi.fn() }))
vi.mock('../download', async (importOriginal) => ({ ...(await importOriginal<typeof import('../download')>()), downloadFile: vi.fn() }))

import FolderShareBody from '../../../core/sharing/FolderShareBody.vue'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import { dropKeysForShare, mintIdentityKey, openDropKey } from '../../../core/sharing/dropKey'
import { openGuestTreeParts } from '../../../core/sharing/guestTree'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../../api/generated/sharing'
import { realI18n, t } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'
import { publicShareFilesApi } from '../api'
import { downloadFile } from '../download'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import DropBoxGuestForm from './DropBoxGuestForm.vue'
import DropBoxSharedView from './DropBoxSharedView.vue'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_8'
const ROOT = '6e5a0000-0000-4000-8000-000000000380'
const LETTERS = '6e5a0000-0000-4000-8000-000000000381'
const DROP = '6e5a0000-0000-4000-8000-000000000382'
const ANNA = '6e5a0000-0000-4000-8000-000000000383'
const ANNA_FILE = '6e5a0000-0000-4000-8000-000000000384'
const INNER_DROP = '6e5a0000-0000-4000-8000-000000000385'
const IDENTITY = '6e5a0000-0000-4000-8000-0000000003a0'
const K_SHARE = v1.randomBytes(32)
const keys = {
  root: v1.randomBytes(32), letters: v1.randomBytes(32), drop: v1.randomBytes(32), anna: v1.randomBytes(32),
  annaFile: v1.randomBytes(32), innerDrop: v1.randomBytes(32),
}
const HASH = `#k=${b64url(K_SHARE)}`

function child(id: string, parentId: string, entryKey: Uint8Array, key: Uint8Array, name: string, kind: string): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return { id, parentId, kind, epoch: 0, placementVersion: 0, encryptedName: sealName(entryKey, address, name), wrappedContentKey: wrapContentKey(entryKey, address, key) }
}

// The drop box as the server serves it to a guest of a link above: its drop private key under its own key, and a
// guest's identity folder whose entry lives under that guest's K_up, wrapped to the drop public key (ADR 0080 decision 11).
const { sealedDropPrivateKey } = dropKeysForShare(SCOPE_ID, DROP, keys.drop, v1.randomBytes(32), null)
const dropPublic = v1.dropKeyPublic(openDropKey(SCOPE_ID, DROP, keys.drop, sealedDropPrivateKey!))
const anna = mintIdentityKey(SCOPE_ID, DROP, IDENTITY, dropPublic)

function head(role: ShareRole): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role, rootKind: FOLDER_KIND,
    share: {
      role, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0 },
        child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe', FOLDER_KIND),
        { ...child(DROP, ROOT, keys.root, keys.drop, 'Postfach', DROPBOX_KIND), sealedDropPrivateKey },
        { ...child(ANNA, DROP, anna.identityKey, keys.anna, 'Anna', FOLDER_KIND), shareIdentityId: IDENTITY, wrappedIdentityKey: anna.wrappedIdentityKey },
        child(ANNA_FILE, ANNA, keys.anna, keys.annaFile, 'steuer.pdf', FILE_KIND),
        // A drop box nobody sent anything to, in a sub-folder.
        child(INNER_DROP, LETTERS, keys.letters, keys.innerDrop, 'Kasten', DROPBOX_KIND),
      ],
    } as PublicShare,
  }
}

let router: Router
beforeEach(async () => {
  localStorage.clear()
  // Files' share section lists the blobs: Anna's file holds one.
  stubApi(publicShareFilesApi, {
    listShareFiles: { items: [{ nodeId: ANNA_FILE, fileBlob: { blobId: 'blob-a', chunkCount: 1, byteSize: 100, wrappedBlobKey: 'eA==' } }] },
  })
  vi.mocked(downloadFile).mockReset().mockResolvedValue(undefined)
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

async function mountBody(role: ShareRole, served: PublicShareHead = head(role)) {
  const w = mount(FolderShareBody, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: served, reload: async () => {}, access: guestAccessFor(served) },
    global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } },
  })
  await flushPromises()
  return w
}
type Body = Awaited<ReturnType<typeof mountBody>>
// The level is core's member view (inbox #401 S2): sub-folders are its folder cards, drop boxes Files' drop-box rows.
const tiles = (w: Body) => [
  ...w.findAll('button.veyq-folder-card').map((c) => ({ name: c.find('strong').text(), el: c })),
  ...w.findAll('button.dropbox-row-open').map((c) => ({ name: c.find('.veyq-row-title').text(), el: c })),
]
const tile = (w: Body, name: string) => tiles(w).find((c) => c.name === name)?.el
const tileNames = (w: Body) => tiles(w).map((c) => c.name)
const heading = (w: Body) => w.find('.veyq-folder-overview-card h2').text()
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}
/** Nothing on the page writes: no file input, no upload/replace/delete control, no 'New folder', no send form. */
function expectNoWriteControl(w: Body) {
  expect(w.findAll('input[type="file"]'), 'no file input').toHaveLength(0)
  expect(w.find('.veyq-create-card').exists(), "no 'New folder', no new Memory").toBe(false)
  expect(w.text()).not.toContain(t('files.upload'))
  expect(w.text()).not.toContain(t('files.dropBox.guestPick'))
  expect(w.text()).not.toContain(t('files.dropBox.guestTitle'))
  expect(w.findComponent(DropBoxGuestForm).exists(), 'never the WRITE_ONLY guest form').toBe(false)
  for (const b of w.findAll('button')) {
    const label = b.attributes('aria-label') ?? b.text()
    expect(label, label).not.toMatch(/Replace|Upload|Delete|Move/)
  }
}

describe('a drop box inside a folder share is shown read-only (inbox #380, owner decision 2026-10-08)', () => {
  it('precondition: the walk opens the drop box, the guest folder (through the drop key) and its file', () => {
    const parts = openGuestTreeParts(head(ShareRole.Read))
    for (const id of [DROP, ANNA, ANNA_FILE, INNER_DROP]) expect(parts.keys.has(id), id).toBe(true)
    expect(parts.names.get(ANNA)).toBe('Anna')
  })

  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: the drop box tile opens its guests' folders, a folder its files with their download — nothing writes`, async () => {
      const w = await mountBody(role)
      expect(tileNames(w).sort()).toEqual(['Briefe', 'Postfach'])
      // control: at the share root a contributor does get 'New folder' and the upload
      if (role === ShareRole.ReadWrite) expect(w.find(`.veyq-create-card[aria-label="${t('node.folderCreate')}"]`).exists()).toBe(true)

      await click(tile(w, 'Postfach')!.element)
      expect(router.currentRoute.value.query.node).toBe(DROP)
      expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
      const view = w.findComponent(DropBoxSharedView)
      expect(view.exists()).toBe(true)
      expect(view.text()).toContain('Postfach')
      expect(view.text()).toContain(t('files.dropBox.sharedHint'))
      expect(view.find('.veyq-row-title').text()).toBe('Anna')
      expect(view.find('.veyq-row-subtitle').text()).toBe('1 file')
      expectNoWriteControl(w)

      await click(view.find(`button[aria-label="${t('files.dropBox.open', { name: 'Anna' })}"]`).element)
      expect(router.currentRoute.value.query.node).toBe(ANNA)
      expect(router.currentRoute.value.hash).toBe(HASH)
      expect(heading(w)).toBe('Anna')
      expect(w.find('.share-folder-badge').text(), 'the badge says view only').toBe(t('share.folder.readonly'))
      expect(w.find('.veyq-row-title').text()).toBe('steuer.pdf')
      expectNoWriteControl(w)

      const download = w.find(`button[aria-label="${t('files.download', { name: 'steuer.pdf' })}"]`)
      await click(download.element)
      expect(vi.mocked(downloadFile).mock.calls.map((c) => [c[2], c[3].blobId, c[4]])).toEqual([[ANNA_FILE, 'blob-a', 'steuer.pdf']])
    })
  }

  it('a typed ?node= naming the drop box or a guest folder opens it, read-only', async () => {
    await router.push(`/s/${SHARE_ID}?node=${DROP}${HASH}`)
    const atDrop = await mountBody(ShareRole.ReadWrite)
    expect(atDrop.findComponent(DropBoxSharedView).exists()).toBe(true)
    expectNoWriteControl(atDrop)
    atDrop.unmount()
    await router.push(`/s/${SHARE_ID}?node=${ANNA}${HASH}`)
    const atAnna = await mountBody(ShareRole.ReadWrite)
    expect(heading(atAnna)).toBe('Anna')
    expectNoWriteControl(atAnna)
  })

  // Inbox #393 (b): a second guest named Anna — the list numbers it "Anna (2)" (node-id order: a guest's resolve serves no
  // creation time), and the folder it opens is headed and crumbed the same, not a second plain "Anna".
  it('two guests of one name: the second folder is "Anna (2)" in the list, its heading and its breadcrumb', async () => {
    const ANNA_2 = '6e5a0000-0000-4000-8000-000000000386'
    const IDENTITY_2 = '6e5a0000-0000-4000-8000-0000000003a1'
    const second = mintIdentityKey(SCOPE_ID, DROP, IDENTITY_2, dropPublic)
    const served = head(ShareRole.Read)
    served.share.nodes.push({
      ...child(ANNA_2, DROP, second.identityKey, v1.randomBytes(32), 'Anna', FOLDER_KIND),
      shareIdentityId: IDENTITY_2, wrappedIdentityKey: second.wrappedIdentityKey,
    })
    await router.push(`/s/${SHARE_ID}?node=${DROP}${HASH}`)
    const atDrop = await mountBody(ShareRole.Read, served)
    expect(atDrop.findComponent(DropBoxSharedView).findAll('.veyq-row-title').map((r) => r.text())).toEqual(['Anna', 'Anna (2)'])
    atDrop.unmount()
    await router.push(`/s/${SHARE_ID}?node=${ANNA_2}${HASH}`)
    const atSecond = await mountBody(ShareRole.Read, served)
    expect(heading(atSecond)).toBe('Anna (2)')
    expect(atSecond.find('.app-breadcrumb').text()).toContain('Anna (2)')
    atSecond.unmount()
    await router.push(`/s/${SHARE_ID}?node=${ANNA}${HASH}`)
    expect(heading(await mountBody(ShareRole.Read, served))).toBe('Anna')
  })

  it('an empty drop box in a sub-folder says nobody sent anything', async () => {
    await router.push(`/s/${SHARE_ID}?node=${INNER_DROP}${HASH}`)
    const w = await mountBody(ShareRole.Read)
    expect(w.findComponent(DropBoxSharedView).text()).toContain(t('files.dropBox.sharedEmpty'))
    expectNoWriteControl(w)
  })
})
