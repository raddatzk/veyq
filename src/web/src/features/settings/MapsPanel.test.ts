// @vitest-environment happy-dom
// Settings → Map and place names (uc.memory.place A5): the switch gives and withdraws this browser's one consent to the map
// tiles and the place-name lookup, through the real layer switch (Reka's `role="switch"` button, clicked). A withdrawal
// removes the stored flag and turns `mapsAvailable` false at once; with no tile service there is nothing to consent to.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import MapsPanel from './MapsPanel.vue'
import { setMapsConsent, useMapConfig } from '../../shared/state/useMapConfig'
import { setTileUrl, setGeocoderUrl } from '../../test/services'
import { realI18n, t } from '../../test/i18n'

const KEY = 'veyq:maps-consent'
const mountPanel = () => mount(MapsPanel, { global: { plugins: [realI18n()] } })

beforeEach(() => {
  setTileUrl('https://tiles.example/{z}/{x}/{y}.png')
  setGeocoderUrl('https://geo.example/reverse')
  setMapsConsent(true)
})

describe('Settings — Map and place names', () => {
  it('names the services this server configured and what they receive', () => {
    const w = mountPanel()
    expect(w.get('.maps-tile-host').text()).toBe('tiles.example')
    expect(w.get('.maps-geocoder-host').text()).toBe('geo.example')
    expect(w.text()).toContain(t('settings.mapsReceive'))
  })

  it('switching off withdraws at once: the flag is removed and no map is available', async () => {
    const { mapsAvailable, geocodingAvailable } = useMapConfig()
    const w = mountPanel()
    const sw = w.get('[role="switch"]')
    expect(sw.attributes('aria-checked')).toBe('true')

    await sw.trigger('click')

    expect(localStorage.getItem(KEY)).toBeNull()
    expect(mapsAvailable.value).toBe(false)
    expect(geocodingAvailable.value).toBe(false)
    expect(w.get('[role="switch"]').attributes('aria-checked')).toBe('false')
  })

  it('switching on gives the consent: the flag is stored and the map is available', async () => {
    setMapsConsent(false)
    const { mapsAvailable } = useMapConfig()
    const w = mountPanel()

    await w.get('[role="switch"]').trigger('click')

    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ tiles: 'tiles.example', places: 'geo.example' })
    expect(mapsAvailable.value).toBe(true)
  })

  it('says "none configured" for a server without the place-name service', () => {
    setGeocoderUrl('')
    const w = mountPanel()
    expect(w.get('.maps-geocoder-host').text()).toBe(t('settings.mapsNone'))
  })

  it('with no tile service there is nothing to consent to: no switch is offered, a stored consent can still be withdrawn', async () => {
    setTileUrl('')
    setMapsConsent(false)
    const off = mountPanel()
    expect(off.text()).toContain(t('settings.mapsNotOffered'))
    expect(off.find('[role="switch"]').exists()).toBe(false)
    expect(off.find('.maps-services').exists()).toBe(false)

    setMapsConsent(true) // a consent given while the server still served a map
    const stale = mountPanel()
    await stale.get('[role="switch"]').trigger('click')
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('a consent given for other hosts holds only for them: the switch is off and the panel names what it was given for', async () => {
    const { mapsAvailable } = useMapConfig()
    setTileUrl('https://tiles.new.example/{z}/{x}/{y}.png') // the Server Owner changed the tile service after the consent
    const w = mountPanel()
    expect(w.get('[role="switch"]').attributes('aria-checked')).toBe('false')
    expect(w.get('.maps-given-for').text()).toBe(t('settings.mapsGivenFor', { tiles: 'tiles.example', places: 'geo.example' }))

    await w.get('[role="switch"]').trigger('click') // agree to the current ones
    expect(mapsAvailable.value).toBe(true)
    expect(JSON.parse(localStorage.getItem(KEY)!)).toEqual({ tiles: 'tiles.new.example', places: 'geo.example' })
    expect(w.find('.maps-given-for').exists()).toBe(false)
  })

  it('names no earlier hosts for the same ones', () => {
    expect(mountPanel().find('.maps-given-for').exists()).toBe(false)
  })
})
