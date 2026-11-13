// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MusicDialog from './MusicDialog.vue'
import { realI18n } from '../../../test/i18n'
import type { Playlist } from './musicModel'

// uc.memory.music main flow 1, E1, A5: one field per service, filled from the stored playlists; a link that is not a
// playlist of that service is refused before anything is saved; an emptied field drops its playlist.
const stubs = {
  VeyqDialog: { template: '<div class="dlg"><slot /></div>' },
  VeyqMessage: { template: '<div class="msg"><slot /></div>' },
  VeyqButton: {
    props: ['type', 'label', 'disabled'],
    template: `<button :type="type || 'button'" :disabled="disabled" @click="$emit('click', $event)">{{ label }}</button>`,
  },
}

const spotify: Playlist = { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' }
const apple: Playlist = { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }
const mountDialog = (music: Playlist[]) => mount(MusicDialog, { props: { music }, global: { plugins: [realI18n()], stubs } })

describe('MusicDialog', () => {
  it('opens on the stored playlists\' links and saves what the fields name', async () => {
    const w = mountDialog([spotify])
    expect((w.find('[data-testid="music-spotify"]').element as HTMLInputElement).value).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    await w.find('[data-testid="music-apple"]').setValue('https://music.apple.com/de/playlist/sommer/pl.u-AbC123')
    await w.find('form').trigger('submit')
    expect(w.emitted('save')?.[0]).toEqual([[spotify, apple]])
    expect(w.emitted('close')).toHaveLength(1)
  })

  it('refuses a link that is not a playlist of the field\'s service, and saves nothing (E1)', async () => {
    const w = mountDialog([])
    await w.find('[data-testid="music-spotify"]').setValue('https://music.apple.com/de/playlist/sommer/pl.u-AbC123')
    expect(w.find('.msg').exists()).toBe(true)
    expect(w.find('[data-testid="music-save"]').attributes('disabled')).toBeDefined()
    await w.find('form').trigger('submit')
    expect(w.emitted('save')).toBeUndefined()
    // control: the same link in its own service's field is taken
    await w.find('[data-testid="music-spotify"]').setValue('')
    await w.find('[data-testid="music-apple"]').setValue('https://music.apple.com/de/playlist/sommer/pl.u-AbC123')
    expect(w.find('.msg').exists()).toBe(false)
    await w.find('form').trigger('submit')
    expect(w.emitted('save')?.[0]).toEqual([[apple]])
  })

  it('an emptied field drops its playlist (A5)', async () => {
    const w = mountDialog([spotify, apple])
    await w.find('[data-testid="music-spotify"]').setValue('')
    await w.find('form').trigger('submit')
    expect(w.emitted('save')?.[0]).toEqual([[apple]])
  })
})
