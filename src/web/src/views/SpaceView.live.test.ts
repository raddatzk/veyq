// @vitest-environment happy-dom
// The member's level view picks up what others changed WITHOUT a reload (inbox #408): the real host (`SpaceView`),
// the real level view, the real tree store and the real tree decryption over sealed rows served by a faked content API
// that keeps a tree revision like the server's (ADR 0081: the listing's `ETag`, a `304` for an echo of it);
// Depot's blob listing (`listFiles`) is a counting stub, so the rows' "look again" cue (the level's live tick, which Depot's
// row factory answers by re-reading it) is read off its call count. What this pins, each with its
// control: a row a peer added appears after one poll interval; the poll is CONDITIONAL — it echoes the tag of what is
// shown, an idle scope is a 304 that does no tree work and tells the rows nothing, a moved one updates the tree; a
// moved revision whose rows did not change replaces nothing; nothing is requested while the tab is hidden (and the
// loop resumes when it shows); an open dialog pauses it; an unmount stops it; and a poll that began before a write's own
// re-read of the tree never overwrites it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { LIVE_POLL } from '../core/node/useLevelLive'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const SCOPE_KEY = new Uint8Array(32).fill(7)

vi.mock('../app/activeSpace', async () => {
  const { reactive } = await import('vue')
  return {
    activeSpace: reactive({
      status: 'ready', scopeId: '11111111-1111-1111-1111-111111111111', tenantId: 't-1', kind: 'PERSONAL', name: null, epoch: 0,
      deletedAt: null, purgeAfter: null, trustRefusal: null,
    }),
    enterScope: vi.fn(async () => {}),
    activeEpochKeys: () => ({ epoch: 0, key: new Uint8Array(32).fill(7) }),
  }
})
vi.mock('../api/client', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api/client')>()), contentApi: vi.fn() }))
vi.mock('../core/favorites/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(async () => {}),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../features/depot/api', () => ({ filesApi: vi.fn() }))

import SpaceView from './SpaceView.vue'
import { loadTree, treeStore, resetTree } from '../core/node/treeStore'
import { contentApi } from '../api/client'
import { listMyTenants } from '../features/spaces/scopes'
import { fakeApi } from '../test/apiMock'
import { filesApi } from '../features/depot/api'
import type { FilesApi } from '../api/generated/depot'
import { titlesOf } from '../test/levelRows'
import { apiResponse } from '../test/apiResponse'
import { httpError } from '../test/httpError'
import type { ContentApi, NodeSummary } from '../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n, t } from '../test/i18n'
import { chooseNew } from '../test/newMenu'
import { sealName, wrapContentKey, type NodeAddress } from '../core/node/tree'
import { dropKeysForShare, mintIdentityKey, openDropPublicKey } from '../core/sharing/dropKey'
import { hashForNode } from '../core/node/location'
import { chooseHeaderEntry } from '../test/nodeHeader'

const sealed = (nodeId: string, name: string) => ({
  encryptedName: b64std(v1.sealField({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch: 0, placementRevision: 0 }, SCOPE_KEY, v1.utf8(name))),
  wrappedContentKey: b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch: 0, placementRevision: 0 }, SCOPE_KEY, v1.randomBytes(32))),
})
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f1'
const FILE_ID = '5c09e000-0000-4000-8000-0000000000a1'
function row(id: string, kind: string, name: string): NodeSummary {
  return { blockedByOperator: false, id, parentId: null, kind, epoch: 0, scopeEpoch: 0, slugToken: 'x', orderIndex: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0), ...sealed(id, name) }
}
// Stable ciphertext per (id, name): the same row twice must fingerprint equal, as the server serves it.
const cache = new Map<string, NodeSummary>()
const rowOf = (id: string, kind: string, name: string) => cache.get(id + name) ?? (cache.set(id + name, row(id, kind, name)), cache.get(id + name)!)

let served: NodeSummary[]
let revision: number // the tree revision the faked server is at: every change moves it, as every write of the server's does
let listNodes: ReturnType<typeof vi.fn<ContentApi['listNodesRaw']>>
let listFiles: ReturnType<typeof vi.fn<FilesApi['listFiles']>>
/** A level that already holds a file when the tab opens (Depot's row factory re-reads its blob listing only for a level with
 *  files, and the listing was started by the mount). The tag stays the load's. */
async function startWithAFile() {
  served = [...served, rowOf(FILE_ID, 'FILE', 'foto.png')]
  await loadTree()
  listNodes.mockClear()
}
const change = (rows: NodeSummary[]) => {
  served = rows
  revision++
}

const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}
const settle = async (ms: number) => {
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}
// One poll interval and the jitter's widest draw, so the tests follow the cadence instead of a copied number.
const OVER_ONE_INTERVAL = Math.ceil(LIVE_POLL.intervalMs * (1 + LIVE_POLL.jitter)) + 500

async function mountLevel(hash = '#~me') {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash })
  await router.isReady()
  const w = mount(SpaceView, { global: { plugins: [router, realI18n()] } })
  await settle(0)
  return w
}

beforeEach(async () => {
  vi.spyOn(Math, 'random').mockReturnValue(0.5) // the loop's delay is jittered: pin the draw so the windows below are exact
  vi.useFakeTimers()
  setVisibility('visible')
  resetTree()
  vi.mocked(listMyTenants).mockResolvedValue([])
  served = [rowOf(FOLDER_ID, 'FOLDER', 'Trips')]
  revision = 1
  listNodes = vi.fn<ContentApi['listNodesRaw']>(async ({ ifNoneMatch }) => {
    if (ifNoneMatch === `"${revision}"`) throw httpError(304)
    return apiResponse(served, { ETag: `"${revision}"` })
  })
  fakeApi(contentApi, { listNodesRaw: listNodes })
  listFiles = vi.fn<FilesApi['listFiles']>(async () => ({ items: [] }))
  fakeApi(filesApi, { listFiles })
  await loadTree()
  listNodes.mockClear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.mocked(Math.random).mockRestore()
})

describe('the member level view, live', () => {
  it("shows a peer's new file after one poll interval, with no reload", async () => {
    const w = await mountLevel()
    expect(titlesOf(w)).toEqual(['Trips'])
    expect(listFiles, 'a level without files asks Depot\'s listing nothing').not.toHaveBeenCalled()
    change([...served, rowOf(FILE_ID, 'FILE', 'foto.png')])
    await settle(OVER_ONE_INTERVAL)
    expect(listNodes).toHaveBeenCalled()
    expect(titlesOf(w)).toEqual(['Trips', 'foto.png'])
    expect(listFiles, "the new file's row reads its blob listing").toHaveBeenCalled()
    w.unmount()
  })

  it('requests nothing while the tab is hidden, and catches up the moment it shows', async () => {
    const w = await mountLevel()
    setVisibility('hidden')
    change([...served, rowOf(FILE_ID, 'FILE', 'foto.png')])
    await settle(120_000)
    expect(listNodes).not.toHaveBeenCalled()
    expect(titlesOf(w)).toEqual(['Trips'])
    setVisibility('visible')
    await settle(0)
    expect(listNodes).toHaveBeenCalledTimes(1)
    expect(titlesOf(w)).toEqual(['Trips', 'foto.png'])
    w.unmount()
  })

  it('asks the server nothing while a dialog of the page is open', async () => {
    const w = await mountLevel()
    await chooseNew(w, t('node.folderCreate')) // core's new-folder dialog
    await settle(OVER_ONE_INTERVAL * 2)
    expect(listNodes).not.toHaveBeenCalled()
    w.unmount()
  })

  it('stops with the page', async () => {
    const w = await mountLevel()
    w.unmount()
    await settle(OVER_ONE_INTERVAL * 3)
    expect(listNodes).not.toHaveBeenCalled()
  })

  it('does not rewrite the tree when nothing changed (no churn for the page)', async () => {
    const w = await mountLevel()
    const before = treeStore.nodes
    await settle(OVER_ONE_INTERVAL * 2)
    expect(listNodes).toHaveBeenCalled()
    expect(treeStore.nodes).toBe(before)
    w.unmount()
  })

  it("echoes the tag of what is shown, and an idle scope's 304 is no work and no cue for the rows", async () => {
    await startWithAFile()
    const w = await mountLevel()
    const before = treeStore.nodes
    const listed = listFiles.mock.calls.length
    await settle(OVER_ONE_INTERVAL * 3)
    expect(listNodes.mock.calls.length).toBeGreaterThanOrEqual(2)
    for (const [request] of listNodes.mock.calls) expect(request.ifNoneMatch).toBe('"1"') // the load's tag, on every poll
    expect(treeStore.nodes).toBe(before) // not even re-opened
    expect(listFiles.mock.calls.length, 'a 304 tells the rows nothing: no re-read of the blob listing').toBe(listed)
    w.unmount()
  })

  it('takes the new tag from a moved scope and echoes THAT on the next poll', async () => {
    const w = await mountLevel()
    change([...served, rowOf(FILE_ID, 'FILE', 'foto.png')])
    await settle(OVER_ONE_INTERVAL)
    expect(titlesOf(w)).toEqual(['Trips', 'foto.png'])
    listNodes.mockClear()
    await settle(OVER_ONE_INTERVAL)
    expect(listNodes.mock.calls.map(([r]) => r.ifNoneMatch)).toEqual(['"2"'])
    w.unmount()
  })

  it("a moved revision with this level's rows unchanged replaces nothing, yet tells the rows to look again", async () => {
    await startWithAFile()
    const w = await mountLevel()
    const before = treeStore.nodes
    const listed = listFiles.mock.calls.length
    revision++ // a change the level does not show: another folder, an upload completing on an existing file
    await settle(OVER_ONE_INTERVAL)
    expect(listNodes.mock.calls.some(([r]) => r.ifNoneMatch === '"1"')).toBe(true)
    expect(treeStore.nodes).toBe(before) // the same array: nothing re-renders
    expect(listFiles.mock.calls.length, 'Depot re-reads its blob listing').toBeGreaterThan(listed)
    w.unmount()
  })

  it('falls back to the fingerprint when the server sends no tag: every poll is a full read, applied only on a difference', async () => {
    listNodes.mockImplementation(async () => apiResponse(served)) // no ETag header
    await loadTree()
    const w = await mountLevel()
    const before = treeStore.nodes
    await settle(OVER_ONE_INTERVAL)
    expect(listNodes).toHaveBeenCalled()
    expect(listNodes.mock.calls.every(([r]) => r.ifNoneMatch === undefined)).toBe(true)
    expect(treeStore.nodes).toBe(before)
    served = [...served, rowOf(FILE_ID, 'FILE', 'foto.png')]
    await settle(OVER_ONE_INTERVAL)
    expect(titlesOf(w)).toEqual(['Trips', 'foto.png'])
    w.unmount()
  })

  it("never overwrites a write's own re-read with a poll that began before it", async () => {
    const w = await mountLevel()
    // The poll's read hangs; meanwhile this tab writes and re-reads the tree (loadTree), which sees the new file.
    let releasePoll: (rows: NodeSummary[]) => void = () => {}
    listNodes.mockImplementationOnce(
      () => new Promise((resolve) => (releasePoll = (rows) => resolve(apiResponse(rows, { ETag: '"1"' })))),
    )
    await settle(OVER_ONE_INTERVAL)
    const stale = [...served]
    change([...served, rowOf(FILE_ID, 'FILE', 'mine.png')])
    await loadTree()
    await nextTick()
    expect(titlesOf(w)).toEqual(['Trips', 'mine.png'])
    releasePoll(stale) // the poll lands late with rows that predate the write
    await settle(0)
    expect(titlesOf(w)).toEqual(['Trips', 'mine.png'])
    w.unmount()
  })

  it('backs off after a failed read and never shows the error state', async () => {
    const w = await mountLevel()
    listNodes.mockRejectedValue(new Error('down'))
    await settle(OVER_ONE_INTERVAL)
    const afterFirst = listNodes.mock.calls.length
    expect(afterFirst).toBe(1)
    await settle(OVER_ONE_INTERVAL) // the delay is now doubled (~30 s): nothing yet
    expect(listNodes.mock.calls.length).toBe(1)
    expect(treeStore.error).toBe(false)
    expect(w.find('.home-state').exists()).toBe(false)
    w.unmount()
  })

  // Inbox #546: a member removed from the group while its page is open. The server no longer knows them in the scope and
  // answers its reads `404 scope_not_found`; the page says the access ended and asks no more, rather than poll on.
  it('a 404 scope_not_found on the poll ends the access: it says so and polls no more', async () => {
    const w = await mountLevel()
    expect(w.find('.veyq-level-view').exists()).toBe(true)
    listNodes.mockRejectedValue(httpError(404, 'scope_not_found'))
    await settle(OVER_ONE_INTERVAL)
    expect(listNodes.mock.calls.length).toBe(1)
    expect(treeStore.gone).toBe(true)
    expect(treeStore.error, 'not the error state: nothing to try again').toBe(false)
    expect(w.find('.home-access-ended').text()).toContain(t('home.accessEnded'))
    expect(w.find('.veyq-level-view').exists(), 'the page of the scope is gone with it').toBe(false)
    await settle(OVER_ONE_INTERVAL * 20)
    expect(listNodes.mock.calls.length, 'no read after the access ended').toBe(1)
    w.unmount()
  })

  it('another 404 (a node it cannot see) is no end of the access (control)', async () => {
    const w = await mountLevel()
    listNodes.mockRejectedValue(httpError(404, 'node_not_found'))
    await settle(OVER_ONE_INTERVAL)
    expect(treeStore.gone).toBe(false)
    expect(w.find('.home-access-ended').exists()).toBe(false)
    w.unmount()
  })
})

// The drop box's own page (owner decision 2026-10-17): its guests' folders and their file counts ARE the scope's tree, so
// the host's tree poll, which runs on every opened node's page (inbox #503), keeps it live with the level's own
// condition — a new guest's folder or a new file appears without a reload, an idle drop box is a 304. Real crypto: each
// guest's folder is sealed under its own identity key, which the member opens through the drop box's drop key
// (`tree.identityFolder.test.ts`).
describe("the drop box's page, live", () => {
  const BOX = '22222222-2222-4222-8222-222222222222'
  const ANNA = '33333333-3333-4333-8333-0000000000a1'
  const BERT = '33333333-3333-4333-8333-0000000000b1'
  const at = (nodeId: string): NodeAddress => ({ scopeId: SCOPE_ID, nodeId, scopeEpoch: 0, placementRevision: 0 })
  const sealedRow = (
    id: string, parentId: string | null, kind: string, entryKey: Uint8Array, key: Uint8Array, name: string, extra: Partial<NodeSummary> = {},
  ): NodeSummary => ({
    blockedByOperator: false,
    id, parentId: parentId ?? undefined, kind, orderIndex: 0, epoch: 0, scopeEpoch: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0),
    encryptedName: sealName(entryKey, at(id), name), slugToken: 'c2x1Zw==', wrappedContentKey: wrapContentKey(entryKey, at(id), key), ...extra,
  })
  /** A drop box at the root with its drop key, and the rows a guest's first call and its files add, as the server serves them. */
  function dropBoxWorld() {
    const boxKey = v1.randomBytes(32)
    const kShare = v1.randomBytes(32)
    const drop = dropKeysForShare(SCOPE_ID, BOX, boxKey, kShare, null)
    const box = sealedRow(BOX, null, 'DEPOT_DROPBOX', SCOPE_KEY, boxKey, 'Einsendungen', { sealedDropPrivateKey: drop.sealedDropPrivateKey })
    const folderKeys = new Map<string, Uint8Array>()
    const guestFolder = (folderId: string, identityId: string, name: string, createdAt: number): NodeSummary => {
      const { identityKey, wrappedIdentityKey } = mintIdentityKey(SCOPE_ID, BOX, identityId, openDropPublicKey(kShare, drop.sealedDropPublicKey))
      const folderKey = v1.randomBytes(32)
      folderKeys.set(folderId, folderKey)
      return { ...sealedRow(folderId, BOX, 'FOLDER', identityKey, folderKey, name), createdAt: new Date(createdAt), shareIdentityId: identityId, wrappedIdentityKey }
    }
    const file = (fileId: string, folderId: string, name: string) => sealedRow(fileId, folderId, 'FILE', folderKeys.get(folderId)!, v1.randomBytes(32), name)
    return { box, guestFolder, file }
  }
  /** The drop box's folder rows as the page shows them: the guest's name and its file count. */
  const folderRows = (w: Awaited<ReturnType<typeof mountLevel>>) =>
    w.findAll('.dropbox-row-open').map((b) => `${b.find('.veyq-row-title').text()} · ${b.find('.veyq-row-subtitle').text()}`)
  async function openDropBox(rows: NodeSummary[]) {
    change(rows)
    await loadTree()
    listNodes.mockClear()
    return mountLevel(hashForNode(treeStore.nodes, BOX, '~me'))
  }

  it("shows a new guest's folder and a new file after one poll interval, with no reload; an idle drop box is a 304", async () => {
    const world = dropBoxWorld()
    const w = await openDropBox([
      world.box,
      world.guestFolder(ANNA, '55555555-5555-4555-8555-0000000000a1', 'Anna', 1),
      world.file('44444444-4444-4444-8444-0000000000a1', ANNA, 'Bericht.pdf'),
    ])
    expect(w.find('.dropbox-detail').exists(), "the drop box's page is on screen").toBe(true)
    expect(folderRows(w)).toEqual(['Anna · 1 file'])

    await settle(OVER_ONE_INTERVAL)
    expect(listNodes.mock.calls.map(([r]) => r.ifNoneMatch), 'an idle drop box echoes the tag of what it shows').toEqual([`"${revision}"`])
    expect(folderRows(w)).toEqual(['Anna · 1 file'])

    change([
      ...served,
      world.guestFolder(BERT, '55555555-5555-4555-8555-0000000000b1', 'Bert', 2),
      world.file('44444444-4444-4444-8444-0000000000b1', BERT, 'Foto.jpg'),
    ])
    await settle(OVER_ONE_INTERVAL)
    expect(folderRows(w), "a new guest's folder appears").toEqual(['Anna · 1 file', 'Bert · 1 file'])

    change([...served, world.file('44444444-4444-4444-8444-0000000000a2', ANNA, 'Nachtrag.pdf')])
    await settle(OVER_ONE_INTERVAL)
    expect(folderRows(w), "a new file counts on its guest's folder").toEqual(['Anna · 2 files', 'Bert · 1 file'])
    w.unmount()
  })

  it('asks the server nothing while a dialog of the page is open, and nothing after the page is gone', async () => {
    const world = dropBoxWorld()
    const w = await openDropBox([world.box])
    await chooseHeaderEntry(w, 'delete') // core's delete dialog, from the header's ⋯ (ADR 0110)
    await settle(OVER_ONE_INTERVAL * 2)
    expect(listNodes).not.toHaveBeenCalled()
    w.unmount()
    await settle(OVER_ONE_INTERVAL * 3)
    expect(listNodes).not.toHaveBeenCalled()
  })
})

// Every opened node's page polls the tree, whatever its kind (inbox #503): a moderator's block of the node, or of a folder
// above it, moves the scope's tree revision and no item revision, so this poll is the only read that brings it. The page
// turns to *Blocked by the operator* at once and its detail is unmounted; a lift brings the detail back. A peer's rename of
// the node on screen keeps it on screen, the fragment following it. Real crypto: the file is sealed under its folder's key.
describe("an opened node's page, live", () => {
  const TRIPS = '5c09e000-0000-4000-8000-0000000000f2'
  const REPORT = '5c09e000-0000-4000-8000-0000000000a2'
  const tripsKey = v1.randomBytes(32)
  const FILE_KEY = v1.randomBytes(32)
  const at = (nodeId: string): NodeAddress => ({ scopeId: SCOPE_ID, nodeId, scopeEpoch: 0, placementRevision: 0 })
  /** A folder at the root and a file in it, as the server serves them: sealed once per name, so a block — which changes no
   *  ciphertext — is the only difference between two worlds (`treeFingerprint` must see it). `blocked` marks both, as the
   *  server marks every node below a blocked one (`NodeSummary.blockedByOperator`). */
  const base = { orderIndex: 0, epoch: 0, scopeEpoch: 0, metaRevision: 0, placementRevision: 0, createdAt: new Date(0), blockedByOperator: false, slugToken: 'c2x1Zw==' }
  const tripsRow: NodeSummary = {
    ...base, id: TRIPS, kind: 'FOLDER', encryptedName: sealName(SCOPE_KEY, at(TRIPS), 'Trips'), wrappedContentKey: wrapContentKey(SCOPE_KEY, at(TRIPS), tripsKey),
  }
  const fileRows = new Map<string, NodeSummary>()
  const fileRow = (name: string) => fileRows.get(name) ?? (fileRows.set(name, {
    ...base, id: REPORT, parentId: TRIPS, kind: 'FILE', encryptedName: sealName(tripsKey, at(REPORT), name), wrappedContentKey: wrapContentKey(tripsKey, at(REPORT), FILE_KEY),
  }), fileRows.get(name)!)
  const world = (opts: { blocked?: boolean; fileName?: string } = {}): NodeSummary[] =>
    [tripsRow, fileRow(opts.fileName ?? 'report.pdf')].map((r) => ({ ...r, blockedByOperator: !!opts.blocked }))
  const fileTitle = (w: Awaited<ReturnType<typeof mountLevel>>) => w.find('.veyq-node-header-title')
  async function openFile(rows: NodeSummary[]) {
    change(rows)
    await loadTree()
    listNodes.mockClear()
    return mountLevel(hashForNode(treeStore.nodes, REPORT, '~me'))
  }

  it('turns to the placeholder at once when a folder above it is blocked, and a lift brings the page back', async () => {
    const w = await openFile(world())
    expect(fileTitle(w).text(), "the file's page is on screen").toBe('report.pdf')
    expect(w.find('.node-blocked').exists()).toBe(false)

    await settle(OVER_ONE_INTERVAL)
    expect(listNodes.mock.calls.map(([r]) => r.ifNoneMatch), "an idle file's page polls, conditionally").toEqual([`"${revision}"`])

    change(world({ blocked: true }))
    await settle(OVER_ONE_INTERVAL)
    expect(w.find('.node-blocked').text()).toBe(t('node.blockedByOperator'))
    expect(fileTitle(w).exists(), "the file's page is gone").toBe(false)

    change(world())
    await settle(OVER_ONE_INTERVAL)
    expect(w.find('.node-blocked').exists(), 'the lift reaches the page too: it kept polling under the placeholder').toBe(false)
    expect(fileTitle(w).text()).toBe('report.pdf')
    w.unmount()
  })

  it("keeps a peer's renamed node on screen, unmounted at no point, and the fragment follows it", async () => {
    const w = await openFile(world())
    const router = w.vm.$router
    const before = router.currentRoute.value.hash
    const page = fileTitle(w).element
    change(world({ fileName: 'summary.pdf' }))
    await settle(OVER_ONE_INTERVAL)
    await flushPromises()
    expect(fileTitle(w).text()).toBe('summary.pdf')
    expect(fileTitle(w).element, 'the same page: never collapsed to the scope root and back').toBe(page)
    expect(router.currentRoute.value.hash).toBe(hashForNode(treeStore.nodes, REPORT, '~me'))
    expect(router.currentRoute.value.hash).not.toBe(before)
    w.unmount()
  })

  it('stops with the page', async () => {
    const w = await openFile(world())
    w.unmount()
    await settle(OVER_ONE_INTERVAL * 3)
    expect(listNodes).not.toHaveBeenCalled()
  })

  it('control: a fragment the member navigates to that names nothing is the scope root, as ever', async () => {
    const w = await openFile(world())
    await w.vm.$router.push({ name: 'home', hash: '#~me/nowhere' })
    await settle(0)
    // The scope root's header names the space, not the file.
    expect(fileTitle(w).text()).toBe(t('shell.personal'))
    expect(titlesOf(w)).toEqual(['Trips'])
    w.unmount()
  })
})
