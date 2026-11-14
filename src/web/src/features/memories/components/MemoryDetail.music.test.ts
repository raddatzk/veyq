// @vitest-environment happy-dom
// uc.memory.background-music (ADR 0135) through the member's whole detail: an audio file stored as a medium of the Memory -
// sealed and uploaded by the real upload code into the in-memory store with its measured facts, listed back through the
// member's source - is no photo (not in the gallery, not in the count) and is the Music tab's track, with its title and its
// length; the tab's add sends the checked files through the one upload queue as audio media of the node on screen, with
// their facts, grouped apart from the gallery's tiles (`#music`); its removal is a photo's trash.
import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'

vi.mock('leaflet', () => ({ default: {} }))
vi.mock('../../../api/client', () => ({ blobApi: vi.fn(), contentApi: vi.fn(), loginApi: vi.fn(), tokenApi: vi.fn(), sessionApi: vi.fn() }))
vi.mock('../api', () => ({ mediaApi: vi.fn(), itemFavoritesApi: vi.fn(), ownItemFavoritesApi: vi.fn() }))
vi.mock('../uploadQueue', async (orig) => ({ ...(await orig<typeof import('../uploadQueue')>()), enqueueUploads: vi.fn() }))
vi.mock('../music/audio', async (orig) => ({ ...(await orig<object>()), checkAudioFile: vi.fn() }))
const { fakePlayer } = vi.hoisted(() => ({ fakePlayer: { current: null as null | Record<string, unknown> } }))
vi.mock('../../../core/audio/player', async (orig) => ({ ...(await orig<object>()), audioPlayer: () => fakePlayer.current }))

import { flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import MemoryDetail from './MemoryDetail.vue'
import { memberMemorySource } from '../source'
import { addMedia, type MediaDeriver } from '../media'
import { MEMORY_KIND } from '../kinds'
import { blobApi, contentApi } from '../../../api/client'
import { mediaApi, itemFavoritesApi } from '../api'
import { enqueueUploads } from '../uploadQueue'
import { fakeApi, stubApi } from '../../../test/apiMock'
import type { ContentApi } from '../../../api/generated/content'
import type { MediaApi } from '../../../api/generated/media'
import { galleryFakeStore } from './galleryFakeStore'
import { realI18n, t } from '../../../test/i18n'
import { treeNode, memberMemoryRights } from '../../../test/models'
import { mountWithHeader } from '../../../test/nodeHeader'
import { coreActionsStandIn, coverStandIn } from '../../../test/nodePage'
import type { MemoryRights } from '../rights'
import { checkAudioFile } from '../music/audio'
import { shallowRef, nextTick } from 'vue'
import type { NowPlaying, PlayerQueue } from '../../../core/audio/player'
import { headerButtonIds } from '../../../test/nodeHeader'
import { musicQueueKey } from '../music/musicQueue'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const NODE_ID = '22222222-2222-2222-2222-222222222222'
const FACTS = { durationMs: 185_000, trimStartMs: 400, trimEndMs: 184_000 }
const deriver: MediaDeriver = {
  deriveImage: vi.fn(async () => ({ bytes: new Uint8Array([0xff, 0xd8, 1, 2]), source: null })),
  deriveVideoPoster: vi.fn(async () => new Uint8Array([0xff, 0xd8, 3, 4])),
}

let deleteMedia: Mock<MediaApi['deleteMedia']>
const player = {
  nowPlaying: shallowRef<NowPlaying | null>(null), notice: shallowRef(null),
  play: vi.fn(), stop: vi.fn(), pause: vi.fn(), resume: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn(),
}
fakePlayer.current = player

beforeEach(() => {
  vi.clearAllMocks()
  player.nowPlaying.value = null
  fakeApi(contentApi, { updateNode: vi.fn(async () => ({ metaRevision: 1 })) as unknown as Mock<ContentApi['updateNode']> })
  stubApi(itemFavoritesApi, { getItemFavorites: [] })
  const store = galleryFakeStore()
  deleteMedia = vi.fn<MediaApi['deleteMedia']>(async () => undefined)
  fakeApi(mediaApi, { ...store.api, deleteMedia })
  fakeApi(blobApi, store.blob)
  vi.stubGlobal('fetch', store.fetchStub)
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({ width: 960, height: 0, top: 0, left: 0, right: 960, bottom: 0, x: 0, y: 0, toJSON: () => ({}) })
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

async function mountWithOneSongAndOnePhoto(rights: MemoryRights = memberMemoryRights(), withSong = true) {
  const memoryKey = v1.randomBytes(32)
  await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob([new Uint8Array(8).fill(1) as BlobPart]), mediaType: 'image/jpeg', name: 'IMG_1.jpg' }, deriver)
  if (withSong) {
    await addMedia(SCOPE_ID, NODE_ID, memoryKey, { blob: new Blob(['ID3']), mediaType: 'audio/mpeg', name: 'Summer Song.mp3' }, deriver, { restored: FACTS })
  }
  const w = mountWithHeader(MemoryDetail, {
    props: {
      source: memberMemorySource({ scopeId: () => SCOPE_ID }), node: treeNode({ id: NODE_ID, kind: MEMORY_KIND, name: 'Alps', parentId: null }),
      scopeId: SCOPE_ID, memoryKey, scopeKey: { epoch: 0, key: v1.randomBytes(32) }, chapters: [], rights, canReport: true,
    },
    global: { plugins: [realI18n()], provide: coreActionsStandIn([], coverStandIn({ canSet: false })) },
    attachTo: document.body,
  })
  await flushPromises()
  await flushPromises()
  return w
}
const musicTab = (w: Awaited<ReturnType<typeof mountWithOneSongAndOnePhoto>>) => w.find('[data-testid="memory-tab-music"]')

describe('MemoryDetail — music (uc.memory.background-music)', () => {
  it('keeps a music track out of the gallery and its count, and lists it on the Music tab with its title and length', async () => {
    const w = await mountWithOneSongAndOnePhoto()
    expect(w.findAll('.media-tile')).toHaveLength(1)
    expect(w.text()).toContain(t('media.itemCount', { count: 1 }, 1))
    expect(musicTab(w).text()).toContain('1')

    await musicTab(w).trigger('mousedown') // Reka's tabs switch on the press
    await flushPromises()
    const rows = w.findAll('[data-testid="music-row"]')
    expect(rows.map((r) => [r.find('[data-testid="music-title"]').text(), r.find('[data-testid="music-length"]').text()])).toEqual([['Summer Song', '3:05']])
    w.unmount()
  })

  it('shows the Music tab where the node has music or the viewer may add some, and nowhere else', async () => {
    const reader = await mountWithOneSongAndOnePhoto(memberMemoryRights({ readonly: true }))
    expect(musicTab(reader).exists(), 'music to hear').toBe(true)
    reader.unmount()
    const adder = await mountWithOneSongAndOnePhoto(memberMemoryRights(), false)
    expect(musicTab(adder).exists(), 'music to add').toBe(true)
    adder.unmount()
    const neither = await mountWithOneSongAndOnePhoto(memberMemoryRights({ readonly: true }), false)
    expect(neither.find('[data-testid="memory-tab-map"]').exists(), 'control: the tabs are drawn').toBe(true)
    expect(musicTab(neither).exists()).toBe(false)
    neither.unmount()
  })

  it('removes a music track as a photo is removed: into the media trash, with Undo', async () => {
    const w = await mountWithOneSongAndOnePhoto()
    await musicTab(w).trigger('mousedown')
    await flushPromises()
    await w.find('[data-testid="music-more"]').trigger('click')
    await flushPromises()
    const remove = Array.from(document.querySelectorAll('[role="menuitem"]')).find((el) => el.textContent?.includes(t('memory.music.remove')))
    expect(remove, 'the row menu offers the removal').toBeDefined()
    ;(remove as HTMLElement).click()
    await flushPromises()
    expect(deleteMedia).toHaveBeenCalledTimes(1)
    expect(deleteMedia.mock.calls[0][0]).toMatchObject({ scopeId: SCOPE_ID })
    expect(w.findAll('[data-testid="music-row"]')).toHaveLength(0)
    w.unmount()
  })

  // ADR 0135 decisions 3-4: the type the bytes name and the facts measured on the device ride with the file into the queue,
  // which seals them at birth. Lever: drop `facts`, or send the gallery's batch key.
  it('sends the checked files through the upload queue as audio media of this node, with their facts, apart from the gallery', async () => {
    const w = await mountWithOneSongAndOnePhoto()
    await musicTab(w).trigger('mousedown')
    await flushPromises()
    const a = new File(['ID3'], 'a.mp3')
    const b = new File(['x'], 'b.m4a')
    vi.mocked(checkAudioFile).mockImplementation(async (file: File) => ({ file, mediaType: file === a ? 'audio/mpeg' : 'audio/mp4', facts: FACTS }))
    const input = w.find('[data-testid="music-file-input"]')
    Object.defineProperty(input.element, 'files', { value: [a, b], configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(enqueueUploads).toHaveBeenCalledTimes(1)
    const [target, selections] = vi.mocked(enqueueUploads).mock.calls[0]
    expect(target.nodeId).toBe(NODE_ID)
    expect(target.key).toBe(`${NODE_ID}#music`)
    expect(selections.map((s) => [s.file.name, s.mediaType, s.facts])).toEqual([['a.mp3', 'audio/mpeg', FACTS], ['b.m4a', 'audio/mp4', FACTS]])
    w.unmount()
  })

  // ADR 0135 decisions 5, 9: one header button where the node has music, for every viewer, toggling core's player; a press on
  // a track starts the music with it. Levers: `shown: true` (the button without music), or never stopping.
  it('offers the header\'s music button only where the node has music, and toggles this node\'s music with it', async () => {
    const none = await mountWithOneSongAndOnePhoto(memberMemoryRights(), false)
    expect(headerButtonIds(none)).not.toContain('music')
    expect(headerButtonIds(none), 'control: the header is drawn').toContain('upload')
    none.unmount()

    const w = await mountWithOneSongAndOnePhoto(memberMemoryRights({ readonly: true }))
    const button = () => w.find('.veyq-node-header-action[data-action="music"]')
    expect(button().attributes('aria-label')).toBe(t('memory.music.play'))
    await button().trigger('click')
    expect(player.play).toHaveBeenCalledTimes(1)
    const queue = player.play.mock.calls[0][0] as PlayerQueue
    expect([queue.key, queue.album, queue.tracks.map((tr) => [tr.title, tr.startMs, tr.endMs])]).toEqual([
      musicQueueKey(SCOPE_ID, NODE_ID), 'Alps', [['Summer Song', 400, 184_000]],
    ])
    player.nowPlaying.value = { key: queue.key, trackId: queue.tracks[0].id, title: 'Summer Song', playing: true, positionMs: 0, durationMs: 183_600 }
    await nextTick()
    await nextTick()
    expect(button().attributes('aria-label')).toBe(t('memory.music.stop'))
    await button().trigger('click')
    expect([player.stop.mock.calls.length, player.play.mock.calls.length]).toEqual([1, 1])
    w.unmount()
  })

  it('a press on a track in the Music tab starts the music with that track, and marks it while it plays', async () => {
    const w = await mountWithOneSongAndOnePhoto()
    await musicTab(w).trigger('mousedown')
    await flushPromises()
    await w.find('[data-testid="music-play"]').trigger('click')
    const queue = player.play.mock.calls[0][0] as PlayerQueue
    expect(queue.startId).toBe(queue.tracks[0].id)
    player.nowPlaying.value = { key: queue.key, trackId: queue.tracks[0].id, title: 'Summer Song', playing: true, positionMs: 0, durationMs: 1 }
    await nextTick()
    expect(w.find('[data-testid="music-row"]').classes()).toContain('is-playing')
    w.unmount()
  })
})
