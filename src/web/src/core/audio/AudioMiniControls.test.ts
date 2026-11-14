// @vitest-environment happy-dom
// Core's compact audio controls (en.platform.audio-player): drawn only while a queue is loaded, for a surface that covers
// the bar (Memories' lightbox); they show what plays and reach the same player; they unmount without stopping it.
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, shallowRef } from 'vue'
import AudioMiniControls from './AudioMiniControls.vue'
import type { AudioPlayer, NowPlaying } from './player'
import { realI18n } from '../../test/i18n'

function fakePlayer(now: NowPlaying | null = null) {
  return {
    nowPlaying: shallowRef(now),
    notice: shallowRef<'unplayable' | null>(null),
    play: vi.fn(), stop: vi.fn(), pause: vi.fn(), resume: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn(),
  } satisfies AudioPlayer
}
const NOW: NowPlaying = { key: 'k', trackId: 'a', title: 'Summer Song', album: 'Lisbon', playing: true, positionMs: 0, durationMs: 1000 }
const mountMini = (player: AudioPlayer) =>
  mount(AudioMiniControls, { props: { player, buttonClass: 'lightbox-icon-button' }, global: { plugins: [realI18n()] } })

describe('AudioMiniControls', () => {
  it('draws nothing while no queue is loaded', () => {
    expect(mountMini(fakePlayer()).find('[data-testid="audio-mini"]').exists()).toBe(false)
  })

  it('shows what plays, marks itself for the slideshow, and hands each control to the player', async () => {
    const player = fakePlayer(NOW)
    const w = mountMini(player)
    expect(w.find('[data-testid="audio-mini-title"]').text()).toBe('Summer Song')
    expect(w.find('[data-audio-controls]').exists()).toBe(true)
    expect(w.findAll('button').every((b) => b.classes().includes('lightbox-icon-button'))).toBe(true)
    await w.find('[data-testid="audio-mini-previous"]').trigger('click')
    await w.find('[data-testid="audio-mini-toggle"]').trigger('click')
    await w.find('[data-testid="audio-mini-next"]').trigger('click')
    expect([player.previous, player.pause, player.next].map((f) => f.mock.calls.length)).toEqual([1, 1, 1])
    player.nowPlaying.value = { ...NOW, playing: false }
    await nextTick()
    await w.find('[data-testid="audio-mini-toggle"]').trigger('click')
    expect(player.resume).toHaveBeenCalledTimes(1)
  })

  it('leaves the music playing when it unmounts: closing the lightbox is no stop', () => {
    const player = fakePlayer(NOW)
    mountMini(player).unmount()
    expect(player.stop).not.toHaveBeenCalled()
  })
})
