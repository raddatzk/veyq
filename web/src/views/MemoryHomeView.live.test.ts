// @vitest-environment happy-dom
// The member's level view picks up what others changed WITHOUT a reload (inbox #408): the real host (`MemoryHomeView`),
// the real level view, the real tree store and the real tree decryption over sealed rows served by a faked content API
// that keeps a tree revision like the server's (ADR 0081: the listing's `ETag`, a `304` for an echo of it);
// Files' blob listing (`listFiles`) is a counting stub, so the rows' "look again" cue (the level's live tick, which Files'
// row factory answers by re-reading it) is read off its call count. What this pins, each with its
// control: a row a peer added appears after one poll interval; the poll is CONDITIONAL — it echoes the tag of what is
// shown, an idle scope is a 304 that does no tree work and tells the rows nothing, a moved one updates the tree; a
// moved revision whose rows did not change replaces nothing; nothing is requested while the tab is hidden (and the
// loop resumes when it shows); an open dialog pauses it; an unmount stops it; and a poll that began before a write's own
// re-read of the tree never overwrites it.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { LIVE_POLL } from '../core/node/useLevelLive'
import { mount } from '@vue/test-utils'
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
    activeEpochKeys: () => ({ current: 0, keys: new Map([[0, new Uint8Array(32).fill(7)]]) }),
  }
})
vi.mock('../api/client', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api/client')>()), contentApi: vi.fn() }))
vi.mock('../features/memory/favoritesStore', () => ({
  favoritesStore: { loaded: true, ids: new Set<string>() },
  loadFavoritesFor: vi.fn(),
  isFavorite: vi.fn(),
  toggleFavorite: vi.fn(),
}))
vi.mock('../features/spaces/scopes', () => ({ listMyTenants: vi.fn() }))
vi.mock('../features/files/api', () => ({ filesApi: vi.fn() }))

import MemoryHomeView from './MemoryHomeView.vue'
import { loadTree, treeStore, resetTree } from '../core/node/treeStore'
import { contentApi } from '../api/client'
import { listMyTenants } from '../features/spaces/scopes'
import { fakeApi } from '../test/apiMock'
import { filesApi } from '../features/files/api'
import type { FilesApi } from '../api/generated/files'
import { titlesOf } from '../test/levelRows'
import { apiResponse } from '../test/apiResponse'
import { httpError } from '../test/httpError'
import type { ContentApi, NodeSummary } from '../api/generated/content'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { realI18n, t } from '../test/i18n'
import { chooseNew } from '../test/newMenu'
import PrimeVue from 'primevue/config'

const sealed = (nodeId: string, name: string) => ({
  encryptedName: b64std(v1.seal({ purpose: 'node-name', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 }, SCOPE_KEY, v1.utf8(name))),
  wrappedContentKey: b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE_ID, nodeId, epoch: 0, placementVersion: 0 }, SCOPE_KEY, v1.randomBytes(32))),
})
const FOLDER_ID = '5c09e000-0000-4000-8000-0000000000f1'
const FILE_ID = '5c09e000-0000-4000-8000-0000000000a1'
function row(id: string, kind: string, name: string): NodeSummary {
  return { id, parentId: null, kind, epoch: 0, slugToken: 'x', orderIndex: 0, metaVersion: 0, placementVersion: 0, createdAt: new Date(0), ...sealed(id, name) }
}
// Stable ciphertext per (id, name): the same row twice must fingerprint equal, as the server serves it.
const cache = new Map<string, NodeSummary>()
const rowOf = (id: string, kind: string, name: string) => cache.get(id + name) ?? (cache.set(id + name, row(id, kind, name)), cache.get(id + name)!)

let served: NodeSummary[]
let revision: number // the tree revision the faked server is at: every change moves it, as every write of the server's does
let listNodes: ReturnType<typeof vi.fn<ContentApi['listNodesRaw']>>
let listFiles: ReturnType<typeof vi.fn<FilesApi['listFiles']>>
/** A level that already holds a file when the tab opens (Files' row factory re-reads its blob listing only for a level with
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

async function mountLevel() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: { template: '<div />' } }] })
  await router.replace({ name: 'home', hash: '#~me' })
  await router.isReady()
  const w = mount(MemoryHomeView, { global: { plugins: [router, realI18n(), PrimeVue], directives: { tooltip: {} } } })
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
    expect(listFiles, 'a level without files asks Files\' listing nothing').not.toHaveBeenCalled()
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
    expect(listFiles.mock.calls.length, 'Files re-reads its blob listing').toBeGreaterThan(listed)
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
})
