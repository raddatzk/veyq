// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { relayCallback, startFrame } from './main'
import { CALLBACK_PATH, SPOTIFY_CHANNEL } from './spotifyAuth'
import type { FrameError, FrameStatus } from './protocol'
import type { MusicPlayer } from './player'

// The music frame's page (ADR 0131 decision 2): it obeys only the window that embeds it, from the one origin that spoke
// first, loads one player for one playlist, draws its controls and reports its state; the Spotify callback relays the
// code to it and leaves.

afterEach(() => {
  document.body.replaceChildren()
})

function frameWindow() {
  const sent: [unknown, string][] = []
  const parent = { postMessage: (m: unknown, o: string) => sent.push([m, o]) }
  let handler: (e: Partial<MessageEvent>) => void = () => {}
  const win = { document, parent, addEventListener: (_t: string, h: typeof handler) => (handler = h) } as unknown as Window
  return { win, parent, sent, receive: (e: Partial<MessageEvent>) => handler(e) }
}

function fakePlayers() {
  const made: { service: string; credential: string; id: string; report: (s: FrameStatus, e?: FrameError) => void; player: MusicPlayer }[] = []
  const make = (service: string) => async (credential: string, id: string, report: (s: FrameStatus, e?: FrameError) => void) => {
    const player = { signIn: vi.fn(async () => {}), toggle: vi.fn(async () => {}), next: vi.fn(async () => {}), forget: vi.fn(async () => {}) }
    made.push({ service, credential, id, report, player })
    return player
  }
  return { made, players: { spotify: make('spotify'), apple: make('apple-music') } }
}

const load = {
  type: 'veyq-music:load',
  playlist: { service: 'spotify', id: '37i9dQZF1DXcBWIGoYBM5M' },
  credential: '0123456789abcdef0123456789abcdef',
  locale: 'en',
}

describe('the music frame', () => {
  it('says ready, then loads exactly the player its parent asked for, with the playlist and the credential', async () => {
    const { win, parent, sent, receive } = frameWindow()
    const { made, players } = fakePlayers()
    startFrame(win, players)
    expect(sent).toEqual([[{ type: 'veyq-music:ready' }, '*']])
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: load })
    await vi.waitFor(() => expect(made).toHaveLength(1))
    expect(made[0]).toMatchObject({ service: 'spotify', credential: load.credential, id: '37i9dQZF1DXcBWIGoYBM5M' })
    expect(document.querySelector('.mf-status')?.textContent).toContain('Sign in')
    expect(sent.at(-1)).toEqual([{ type: 'veyq-music:state', status: 'signed-out' }, 'https://veyq.example.org'])
  })

  it('takes nothing from another window, and nothing from another origin once its parent spoke', async () => {
    const { win, parent, receive } = frameWindow()
    const { made, players } = fakePlayers()
    startFrame(win, players)
    receive({ source: {} as Window, origin: 'https://veyq.example.org', data: load })
    await new Promise((r) => setTimeout(r, 10))
    expect(made, 'a message from a window that is not the parent').toHaveLength(0)
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: load })
    await vi.waitFor(() => expect(made).toHaveLength(1)) // control: the parent is obeyed
    receive({ source: parent as unknown as Window, origin: 'https://evil.example', data: { type: 'veyq-music:forget' } })
    await new Promise((r) => setTimeout(r, 10))
    expect(made[0].player.forget).not.toHaveBeenCalled()
  })

  it('draws the player\'s state, clicks reach the player, and a forget signs out, empties the page and says so', async () => {
    const { win, parent, sent, receive } = frameWindow()
    const { made, players } = fakePlayers()
    startFrame(win, players)
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { ...load, locale: 'de' } })
    await vi.waitFor(() => expect(made).toHaveLength(1))
    const toggle = () => document.querySelectorAll<HTMLButtonElement>('.mf-button')[1]
    expect(toggle().hidden).toBe(true) // signed out: no play
    made[0].report('ready')
    expect(toggle().hidden).toBe(false)
    expect(toggle().textContent).toBe('Abspielen')
    toggle().click()
    expect(made[0].player.toggle).toHaveBeenCalledOnce()
    made[0].report('error', 'premium-required')
    expect(document.querySelector('.mf-status')?.textContent).toContain('Spotify Premium')
    expect(document.querySelector<HTMLAnchorElement>('.mf-open')?.href).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { type: 'veyq-music:forget' } })
    await vi.waitFor(() => expect(sent.at(-1)?.[0]).toEqual({ type: 'veyq-music:forgotten' }))
    expect(made[0].player.forget).toHaveBeenCalledOnce()
    expect(document.body.children).toHaveLength(0)
  })

  it('the Spotify callback relays the code over the channel and to a same-origin opener, clears the address and closes', async () => {
    const heard = new Promise<unknown>((resolve) => {
      const channel = new BroadcastChannel(SPOTIFY_CHANNEL)
      channel.onmessage = (e) => {
        channel.close()
        resolve(e.data)
      }
    })
    const opener = { postMessage: vi.fn() }
    const win = {
      location: { search: '?code=c1&state=s1', pathname: CALLBACK_PATH, origin: 'https://music.example.org' },
      opener, history: { replaceState: vi.fn() }, close: vi.fn(),
    } as unknown as Window
    expect(relayCallback(win)).toBe(true)
    const message = { kind: 'veyq-spotify-callback', code: 'c1', state: 's1' }
    expect(await heard).toEqual(message)
    expect(opener.postMessage).toHaveBeenCalledWith(message, 'https://music.example.org')
    expect(win.history.replaceState).toHaveBeenCalledWith(null, '', CALLBACK_PATH)
    expect(win.close).toHaveBeenCalled()
    // control: a callback with nothing to relay relays nothing and stays
    const empty = { ...win, location: { ...win.location, search: '' }, close: vi.fn() } as unknown as Window
    expect(relayCallback(empty)).toBe(false)
    expect(empty.close).not.toHaveBeenCalled()
  })
})
