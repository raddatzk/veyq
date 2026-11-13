// @vitest-environment happy-dom
// The shell's footer bar (the registry's `app.footerBar`, ADR 0131) with the real composition: Memories' music bar is
// mounted ONCE below the signed-in app's footer, so music a Memory's header started survives every change of page - the
// very iframe, not a new one - and the bar goes with the session: a lock or a sign-out unmounts it and ends the music. An
// application switched off here gets no bar. Core names no application: the bar arrives through the registry.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('../../features/account/email/UnconfirmedEmailBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/project/DonationBanner.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppSidebar.vue', () => ({ default: { render: () => null } }))
vi.mock('./AppTopbar.vue', () => ({ default: { render: () => null } }))
vi.mock('../../features/account/profile/FinishProfilePrompt.vue', () => ({ default: { render: () => null } }))
vi.mock('../../core/operator/operatorDetails', () => ({ loadPublishedOperator: async () => ({ identification: null, contact: null }) }))
vi.mock('../../app/activeSpace', async (orig) => ({
  ...(await orig<typeof import('../../app/activeSpace')>()),
  enterScope: vi.fn(),
}))

import AppShell from './AppShell.vue'
import { setTokens, clearTokens } from '../../app/session'
import { setUnlocked, clearVault } from '../../app/vault'
import { capabilityState } from '../../shared/state/capabilities'
import { realI18n } from '../../test/i18n'
import { MEMORIES, MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY } from '../../features/platform/capabilities'
import { setMusicConsent } from '../../features/memories/music/useMusicConsent'
import { closeBar, playInBar, playlistInBar } from '../../features/memories/music/musicPlayback'

const PageA = { template: '<div class="page-a" />' }
const PageB = { template: '<div class="page-b" />' }
const FRAME = '[data-testid="music-frame"]'
const mounted: { unmount: () => void }[] = []

async function mountShell() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: PageA, meta: { layout: 'app' } },
      { path: '/settings', name: 'settings', component: PageB, meta: { layout: 'app' } },
    ],
  })
  await router.replace('/')
  await router.isReady()
  // not attached: happy-dom would load the iframe's page
  const w = mount(AppShell, { global: { plugins: [router, realI18n()] } })
  mounted.push(w)
  await flushPromises()
  return { w, router }
}

function serve(memoriesOn = true) {
  const entries = { ...capabilityState.entries }
  entries[MEMORIES_MUSIC] = { id: MEMORIES_MUSIC, serviceUrl: 'https://music.example.org/music-frame' }
  entries[MEMORIES_MUSIC_SPOTIFY] = { id: MEMORIES_MUSIC_SPOTIFY, spotifyClientId: '0123456789abcdef0123456789abcdef' }
  capabilityState.tenantEntries = null
  capabilityState.tenantIds = null
  capabilityState.entries = entries
  capabilityState.ids = [MEMORIES_MUSIC, MEMORIES_MUSIC_SPOTIFY, ...(memoriesOn ? [MEMORIES] : [])]
}

beforeEach(() => {
  serve()
  setMusicConsent('spotify', true)
  closeBar()
  setTokens('access', null)
  setUnlocked(new Uint8Array(32), { kemSeed: new Uint8Array(32), sign: new Uint8Array(32) }, 'device')
})
afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  clearVault()
  clearTokens()
  setMusicConsent('spotify', false)
})

describe('AppShell — the footer bar (ADR 0131)', () => {
  it('sits below the footer and keeps the very frame across a change of page', async () => {
    const { w, router } = await mountShell()
    playInBar({ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' })
    await flushPromises()
    const frame = w.find(FRAME).element
    expect(frame).toBeTruthy()
    expect(w.find('.app-operator-footer + [data-testid="footer-bars"]').exists(), 'right below the footer').toBe(true)
    await router.push('/settings')
    await flushPromises()
    expect(w.find('.page-b').exists()).toBe(true) // the page did change
    expect(w.find(FRAME).element, 'the same iframe: the music plays on').toBe(frame)
    await router.push('/')
    await flushPromises()
    expect(w.find(FRAME).element).toBe(frame)
  })

  it('a lock tears the bar down and ends the music', async () => {
    const { w } = await mountShell()
    playInBar({ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' })
    await flushPromises()
    expect(w.find(FRAME).exists()).toBe(true)
    clearVault()
    await flushPromises()
    expect(w.find(FRAME).exists()).toBe(false)
    expect(playlistInBar.value).toBeNull()
  })

  it('a sign-out tears the bar down and ends the music', async () => {
    const { w } = await mountShell()
    playInBar({ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' })
    await flushPromises()
    clearTokens()
    await flushPromises()
    expect(w.find('[data-testid="footer-bars"]').exists()).toBe(false)
    expect(playlistInBar.value).toBeNull()
  })

  it('Memories switched off here mounts no music bar', async () => {
    serve(false)
    const { w } = await mountShell()
    playInBar({ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' })
    await flushPromises()
    expect(w.find('[data-testid="music-bar"]').exists()).toBe(false)
    serve(true)
    await flushPromises()
    playInBar({ service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' })
    await flushPromises()
    expect(w.find('[data-testid="music-bar"]').exists(), 'control: switched on, it is there').toBe(true)
  })
})
