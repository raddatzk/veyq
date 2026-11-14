// @vitest-environment happy-dom
// Core's audio bar (en.platform.audio-player, ADR 0135 decision 8): drawn only while a queue is loaded; its controls reach
// the player; a drag of its progress bar seeks where it is let go; its unmount stops the player (a lock, leaving a share).
import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick, shallowRef } from 'vue'
import AudioBar from './AudioBar.vue'
import type { AudioPlayer, NowPlaying } from './player'
import { realI18n } from '../../test/i18n'

function fakePlayer(now: NowPlaying | null = null) {
  return {
    nowPlaying: shallowRef(now),
    notice: shallowRef<'unplayable' | null>(null),
    play: vi.fn(), stop: vi.fn(), pause: vi.fn(), resume: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn(),
  } satisfies AudioPlayer
}
const NOW: NowPlaying = { key: 'k', trackId: 'a', title: 'Summer Song', album: 'Lisbon', playing: true, positionMs: 61_000, durationMs: 185_000 }
const mountBar = (player: AudioPlayer) => mount(AudioBar, { props: { player }, global: { plugins: [realI18n()] } })

describe('AudioBar', () => {
  it('draws nothing while no queue is loaded', () => {
    expect(mountBar(fakePlayer()).find('[data-testid="audio-bar"]').exists()).toBe(false)
  })

  it('shows the title, the album, the position over the span, and hands each control to the player', async () => {
    const player = fakePlayer(NOW)
    const w = mountBar(player)
    expect(w.find('[data-testid="audio-title"]').text()).toBe('Summer Song · Lisbon')
    expect(w.text()).toContain('1:01')
    expect(w.text()).toContain('3:05')
    await w.find('[data-testid="audio-previous"]').trigger('click')
    await w.find('[data-testid="audio-toggle"]').trigger('click')
    await w.find('[data-testid="audio-next"]').trigger('click')
    await w.find('[data-testid="audio-close"]').trigger('click')
    expect([player.previous, player.pause, player.next, player.stop].map((f) => f.mock.calls.length)).toEqual([1, 1, 1, 1])
    player.nowPlaying.value = { ...NOW, playing: false }
    await nextTick()
    expect(w.find('[data-testid="audio-toggle"]').attributes('aria-label')).toBe('Play')
    await w.find('[data-testid="audio-toggle"]').trigger('click')
    expect(player.resume).toHaveBeenCalledTimes(1)
  })

  it('a drag of the progress bar shows where it goes and seeks where it is let go', async () => {
    const player = fakePlayer(NOW)
    const w = mountBar(player)
    const range = w.find('[data-testid="audio-progress"]')
    expect([range.attributes('max'), (range.element as HTMLInputElement).value]).toEqual(['185000', '61000'])
    ;(range.element as HTMLInputElement).value = '120000'
    await range.trigger('input')
    expect(w.text()).toContain('2:00')
    expect(player.seek).not.toHaveBeenCalled()
    await range.trigger('change')
    expect(player.seek).toHaveBeenCalledWith(120_000)
  })

  it('stops the player when it unmounts: a lock, a sign-out, leaving a share page', () => {
    const player = fakePlayer(NOW)
    mountBar(player).unmount()
    expect(player.stop).toHaveBeenCalledTimes(1)
  })

  it('says so when no track of a queue plays here, then goes (E2)', async () => {
    vi.useFakeTimers()
    try {
      const player = fakePlayer()
      const w = mountBar(player)
      player.notice.value = 'unplayable'
      await nextTick()
      expect(w.find('[data-testid="audio-notice"]').text()).toContain('None of these tracks can be played in this browser.')
      vi.advanceTimersByTime(5000)
      await nextTick()
      expect(w.find('[data-testid="audio-bar"]').exists()).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })
})
