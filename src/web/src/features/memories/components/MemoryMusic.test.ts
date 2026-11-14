// @vitest-environment happy-dom
// uc.memory.background-music (ADR 0135 decisions 2-4, 9): the Music tab - the node's tracks listed with their titles and
// lengths, the add that checks each file on the device before anything is uploaded (E1), each row's actions by the rights
// handed in, and a press that starts the music with that track (A1).
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { toastError, checkAudioFile } = vi.hoisted(() => ({ toastError: vi.fn(), checkAudioFile: vi.fn() }))
vi.mock('../../../shared/state/useToast', () => ({
  useToast: () => ({ success: vi.fn(), error: toastError, showUndo: vi.fn(), dismissUndo: vi.fn() }),
  UNDO_GROUP: 'undo',
}))
vi.mock('../music/audio', async (orig) => ({ ...(await orig<object>()), checkAudioFile }))

import { mount, flushPromises } from '@vue/test-utils'
import MemoryMusic from './MemoryMusic.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { realI18n } from '../../../test/i18n'
import { mediaItem } from '../../../test/models'
import type { MediaItem } from '../media'
import type { MediaLoaders } from '../source'

const KEY = new Uint8Array(32)

function loaders(names: Record<string, string | undefined>, facts: Record<string, object | null> = {}): MediaLoaders {
  return {
    blobMeta: (it: MediaItem) => ({ type: 'audio/mpeg', name: names[it.mediaId] }),
    meta: (it: MediaItem) => (it.mediaId in facts ? facts[it.mediaId] : { v: 1, durationMs: 185_000, trimStartMs: 0, trimEndMs: 185_000 }),
    chunk: vi.fn(),
    original: vi.fn(),
  } as unknown as MediaLoaders
}

function mountTab(over: Partial<{
  music: MediaItem[]; names: Record<string, string | undefined>; facts: Record<string, object | null>; canAdd: boolean
  canRemove: (id: string) => boolean; canDownload: boolean; canReport: boolean; playingId: string | null; pickReady: () => boolean
}> = {}) {
  return mount(MemoryMusic, {
    props: {
      memoryName: 'Lisbon', memoryKey: KEY,
      music: over.music ?? [mediaItem({ mediaId: 'a' }), mediaItem({ mediaId: 'b' })],
      loaders: loaders(over.names ?? { a: 'Zebra Song.mp3', b: '02 Intro.m4a' }, over.facts),
      canAdd: over.canAdd ?? true,
      canRemove: over.canRemove ?? (() => true),
      canDownload: over.canDownload ?? true,
      canReport: over.canReport ?? true,
      playingId: over.playingId ?? null,
      pickReady: over.pickReady,
    },
    global: { plugins: [realI18n()] },
  })
}

async function pickFiles(w: ReturnType<typeof mountTab>, files: File[]) {
  const input = w.find('[data-testid="music-file-input"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('the Music tab', () => {
  it('lists each track by its file\'s name without the extension, with its sealed length, in title order', async () => {
    const w = mountTab({
      music: [mediaItem({ mediaId: 'a' }), mediaItem({ mediaId: 'b' }), mediaItem({ mediaId: 'c' }), mediaItem({ mediaId: 'd' })],
      names: { a: 'Zebra Song.mp3', b: '10 Outro.mp3', c: '2 Intro.m4a', d: undefined },
      facts: { c: { v: 1, durationMs: 3_725_000, trimStartMs: 0, trimEndMs: 3_725_000 }, d: null },
    })
    await flushPromises()
    const rows = w.findAll('[data-testid="music-row"]').map((r) => [r.find('[data-testid="music-title"]').text(), r.find('[data-testid="music-length"]').exists() ? r.find('[data-testid="music-length"]').text() : null])
    // numbers in titles sort as numbers; a track whose file has no name is named by its place, and one without facts has no length
    expect(rows).toEqual([['2 Intro', '1:02:05'], ['10 Outro', '3:05'], ['Zebra Song', '3:05'], ['Track 4', null]])
  })

  it('a press on a track starts the music with it, and the playing one is marked', async () => {
    const w = mountTab({ playingId: 'a' })
    await flushPromises()
    const rows = w.findAll('[data-testid="music-row"]')
    expect(rows.map((r) => r.classes('is-playing'))).toEqual([false, true]) // '02 Intro' first, 'Zebra Song' playing
    await rows[0].find('[data-testid="music-play"]').trigger('click')
    expect(w.emitted('play')).toEqual([['b']])
    expect(rows[0].find('[data-testid="music-play"]').attributes('aria-label')).toBe('Play 02 Intro')
  })

  it('checks each picked file on the device: a refused one is named with its reason, the good ones go on with their type and facts', async () => {
    const facts = { durationMs: 4000, trimStartMs: 100, trimEndMs: 3900 }
    checkAudioFile.mockImplementation(async (file: File) =>
      file.name === 'big.wav' ? { problem: 'tooLarge' } : file.name === 'photo.jpg' ? { problem: 'notAudio' } : file.name === 'odd.ogg' ? { problem: 'undecodable' }
        : { file, mediaType: 'audio/mpeg', facts })
    const w = mountTab()
    const good = new File(['x'], 'good.mp3')
    await pickFiles(w, [new File(['x'], 'big.wav'), good, new File(['x'], 'photo.jpg'), new File(['x'], 'odd.ogg')])
    expect(toastError.mock.calls.map(([line]) => line)).toEqual([
      'big.wav is larger than 200 MB.', 'photo.jpg is no audio file.', 'odd.ogg cannot be read in this browser.',
    ])
    expect(w.emitted('add')).toEqual([[[{ file: good, mediaType: 'audio/mpeg', facts }]]])
    // the picker asks for audio only; the gallery's keeps refusing it
    expect(w.find('[data-testid="music-file-input"]').attributes('accept')).toBe('audio/*')
  })

  it('adds nothing where every file is refused', async () => {
    checkAudioFile.mockResolvedValue({ problem: 'notAudio' })
    const w = mountTab()
    await pickFiles(w, [new File(['x'], 'a.txt')])
    expect(w.emitted('add')).toBeUndefined()
  })

  it('offers no add where the viewer may not upload, and says so in its empty state', async () => {
    const w = mountTab({ canAdd: false, music: [] })
    await flushPromises()
    expect(w.find('[data-testid="music-add"]').exists()).toBe(false)
    expect(w.text()).toContain('No music yet.')
    expect(w.text()).not.toContain('Add audio files')
    const adder = mountTab({ music: [] })
    expect(adder.find('[data-testid="music-add"]').exists()).toBe(true)
    expect(adder.text()).toContain('Add audio files')
  })

  it('opens the picker only once the session is ready (a guest names itself first)', async () => {
    const w = mountTab({ pickReady: () => false })
    const click = vi.spyOn(w.find('[data-testid="music-file-input"]').element as HTMLInputElement, 'click')
    await w.find('[data-testid="music-add"] button, [data-testid="music-add"]').trigger('click')
    expect(click).not.toHaveBeenCalled()
  })

  it("offers each row's download, report and removal as the rights say, and hands removal and report up", async () => {
    const w = mountTab({ canRemove: (id) => id === 'a', canReport: false })
    await flushPromises()
    const menuOf = async (row: number) => {
      await w.findAll('[data-testid="music-more"]')[row].trigger('click')
      return (w.findComponent(VeyqMenu).props('items') as VeyqMenuItem[]).filter((i) => i.visible !== false)
    }
    expect((await menuOf(0)).map((i) => i.key)).toEqual(['download'])
    const second = await menuOf(1)
    expect(second.map((i) => i.key)).toEqual(['download', 'remove'])
    second.find((i) => i.key === 'remove')!.command!(new Event('click'))
    expect((w.emitted('remove')?.[0]?.[0] as MediaItem).mediaId).toBe('a')

    const reporting = mountTab({ canRemove: () => false, canDownload: false })
    await flushPromises()
    await reporting.findAll('[data-testid="music-more"]')[0].trigger('click')
    const items = (reporting.findComponent(VeyqMenu).props('items') as VeyqMenuItem[]).filter((i) => i.visible !== false)
    expect(items.map((i) => i.key)).toEqual(['report'])
    items[0].command!(new Event('click'))
    expect((reporting.emitted('report')?.[0]?.[0] as MediaItem).mediaId).toBe('b')

    const bare = mountTab({ canRemove: () => false, canDownload: false, canReport: false })
    await flushPromises()
    expect(bare.find('[data-testid="music-more"]').exists(), 'no menu without an entry').toBe(false)
  })
})
