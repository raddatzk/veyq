// @vitest-environment happy-dom
// A drop box inside a shared folder (inbox #380, found in a real browser, 2026-10-08): a READ guest of folder "Familie"
// clicked the drop box's tile, got the WRITE_ONLY guest's upload form under the FOLDER's name, and "Something went
// wrong" after naming itself. Files claims the drop box's body `rootOnly`, so core's folder page shows no drop box and
// nothing below it. Here the app's real registry (Files and Memories, filled by `src/test/setup.ts`), the real
// `FolderShareBody`, the real PrimeVue and the real vue-router: every rendered tile is clicked through the DOM, and a
// typed `?node=` for the drop box or a guest's folder in it lands on the share's root, never on the upload form.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import PrimeVue from 'primevue/config'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'

vi.mock('../../../api/client', () => ({ sharesApi: vi.fn(), publicSharesApi: vi.fn(), tokenApi: vi.fn() }))
vi.mock('../api', () => ({ publicShareFilesApi: vi.fn() }))

import FolderShareBody from '../../../core/sharing/FolderShareBody.vue'
import { FOLDER_KIND } from '../../../core/node/kind'
import { sealName, wrapContentKey } from '../../../core/node/tree'
import { dropKeysForShare, mintIdentityKey, openDropKey } from '../../../core/sharing/dropKey'
import { openGuestTreeParts } from '../../../core/sharing/guestTree'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../../api/generated/sharing'
import { realI18n } from '../../../test/i18n'
import { stubApi } from '../../../test/apiMock'
import { publicShareFilesApi } from '../api'
import { DROPBOX_KIND, FILE_KIND } from '../kinds'
import DropBoxShareBody from './DropBoxShareBody.vue'

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
        // A folder whose only child is a drop box: to this page, an empty folder.
        child(INNER_DROP, LETTERS, keys.letters, keys.innerDrop, 'Kasten', DROPBOX_KIND),
      ],
    } as PublicShare,
  }
}

let router: Router
beforeEach(async () => {
  localStorage.clear()
  stubApi(publicShareFilesApi, { listShareFiles: { items: [] } }) // Files' share section lists the blobs
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/s/:id', component: { render: () => null } }] })
  await router.push(`/s/${SHARE_ID}${HASH}`)
  await router.isReady()
})

async function mountBody(role: ShareRole) {
  const w = mount(FolderShareBody, {
    props: { shareId: SHARE_ID, fragmentKey: b64url(K_SHARE), pwToken: undefined, head: head(role), reload: async () => {} },
    global: { plugins: [router, realI18n(), PrimeVue] },
  })
  await flushPromises()
  return w
}
type Body = Awaited<ReturnType<typeof mountBody>>
const tiles = (w: Body) => w.findAll('button.veyq-node-card:not(.veyq-node-card-create)')
const tileNames = (w: Body) => tiles(w).map((c) => c.find('strong').text())
const heading = (w: Body) => w.find('.veyq-section-header h1').text()
async function click(el: Element) {
  el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
  await flushPromises()
}

describe('a drop box inside a folder share is not shown (inbox #380)', () => {
  it('precondition: the walk DOES open the drop box, the guest folder and its file (hiding is the page\'s rule)', () => {
    const parts = openGuestTreeParts(head(ShareRole.Read))
    for (const id of [DROP, ANNA, ANNA_FILE, INNER_DROP]) expect(parts.keys.has(id), id).toBe(true)
    expect(parts.names.get(ANNA)).toBe('Anna')
  })

  for (const role of [ShareRole.Read, ShareRole.ReadWrite]) {
    it(`${role}: no drop-box tile; every rendered tile, clicked, opens a folder — never the upload form`, async () => {
      const w = await mountBody(role)
      expect(heading(w)).toBe('Familie')
      expect(tileNames(w)).toEqual(['Briefe'])
      for (const tile of tiles(w)) {
        await click(tile.element)
        expect(router.currentRoute.value.query.node).toBe(LETTERS)
        expect(router.currentRoute.value.hash, 'the share key stays in the URL').toBe(HASH)
        expect(heading(w)).toBe('Briefe')
        expect(w.findComponent(DropBoxShareBody).exists()).toBe(false)
      }
      // "Briefe" holds only a drop box: this page says the folder is empty and shows no tile for it.
      expect(tileNames(w)).toEqual([])
      expect(w.find('.veyq-empty').exists()).toBe(true)
    })
  }

  it('a typed ?node= naming the drop box, a guest folder in it or that folder\'s file lands on the share root', async () => {
    for (const id of [DROP, ANNA, ANNA_FILE, INNER_DROP]) {
      await router.push(`/s/${SHARE_ID}?node=${id}${HASH}`)
      const w = await mountBody(ShareRole.Read)
      expect(heading(w), id).toBe('Familie')
      expect(w.findComponent(DropBoxShareBody).exists(), id).toBe(false)
      expect(w.text(), id).not.toContain('Anna')
      expect(w.text(), id).not.toContain('steuer.pdf')
      w.unmount()
    }
  })
})
