// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { relayCallback, startFrame } from './main'
import { CALLBACK_PATH, SPOTIFY_CHANNEL } from './spotifyAuth'
import type { FrameError, FrameStatus } from './protocol'
import type { MusicPlayer, Show, Track } from './player'

// The music frame's page (ADR 0131 decisions 2 and 6): it obeys only the window that embeds it, from the one origin that
// spoke first, loads one player for one service, switches its playlist in place, draws veyq's own controls in the app's
// theme, shows what plays only beside the vendor's mark, and reports its state alone; the Spotify callback relays the code
// to it and leaves.

afterEach(() => {
  document.body.replaceChildren()
  document.documentElement.classList.remove('veyq-dark')
})

function frameWindow() {
  const sent: [unknown, string][] = []
  const parent = { postMessage: (m: unknown, o: string) => sent.push([m, o]) }
  let handler: (e: Partial<MessageEvent>) => void = () => {}
  const win = { document, parent, addEventListener: (_t: string, h: typeof handler) => (handler = h) } as unknown as Window
  return { win, parent, sent, receive: (e: Partial<MessageEvent>) => handler(e) }
}

function fakePlayers() {
  const made: { service: string; credential: string; id: string; report: (s: FrameStatus, e?: FrameError) => void; show: Show; player: MusicPlayer }[] = []
  const make = (service: string) => async (credential: string, id: string, report: (s: FrameStatus, e?: FrameError) => void, show: Show) => {
    const player = {
      signIn: vi.fn(async () => {}), toggle: vi.fn(async () => {}), previous: vi.fn(async () => {}), next: vi.fn(async () => {}),
      switchTo: vi.fn(async () => {}), forget: vi.fn(async () => {}),
    }
    made.push({ service, credential, id, report, show, player })
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
    const toggle = () => document.querySelector<HTMLButtonElement>('.mf-toggle')!
    expect(toggle().hidden).toBe(true) // signed out: no play
    expect(document.querySelector<HTMLButtonElement>('.mf-sign-in')?.textContent).toBe('Mit Spotify anmelden')
    made[0].report('ready')
    expect(toggle().hidden).toBe(false)
    expect(toggle().getAttribute('aria-label')).toBe('Abspielen')
    toggle().click()
    expect(made[0].player.toggle).toHaveBeenCalledOnce()
    made[0].report('playing')
    expect(toggle().getAttribute('aria-label')).toBe('Pause')
    document.querySelector<HTMLButtonElement>('.mf-previous')!.click()
    document.querySelector<HTMLButtonElement>('.mf-next')!.click()
    expect(made[0].player.previous).toHaveBeenCalledOnce()
    expect(made[0].player.next).toHaveBeenCalledOnce()
    made[0].report('error', 'premium-required')
    expect(document.querySelector('.mf-status')?.textContent).toContain('Spotify Premium')
    expect(document.querySelector<HTMLAnchorElement>('.mf-open')?.href).toBe('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    expect(document.querySelector('.mf-open')?.getAttribute('aria-label')).toBe('Auf Spotify anhören')
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { type: 'veyq-music:forget' } })
    await vi.waitFor(() => expect(sent.at(-1)?.[0]).toEqual({ type: 'veyq-music:forgotten' }))
    expect(made[0].player.forget).toHaveBeenCalledOnce()
    expect(document.body.children).toHaveLength(0)
  })

  it('a forget with no player (a sign-out mounted it only for that) loads nothing, clears this origin\'s storage and says so', async () => {
    // ADR 0131 decision 5: MusicKit keeps its sign-in in the music origin's storage, which outlives every frame.
    const { win, parent, sent, receive } = frameWindow()
    const store = (entries: Record<string, string>) => {
      const m = new Map(Object.entries(entries))
      return { clear: () => m.clear(), get size() { return m.size } }
    }
    const local = store({ 'music.ABCDE.media-user-token': 'token-of-the-last-person' })
    const session = store({ some: 'thing' })
    Object.assign(win, { localStorage: local, sessionStorage: session })
    const { made, players } = fakePlayers()
    startFrame(win, players)
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { type: 'veyq-music:forget' } })
    await vi.waitFor(() => expect(sent.at(-1)?.[0]).toEqual({ type: 'veyq-music:forgotten' }))
    expect(made, 'no vendor player is made to forget').toHaveLength(0)
    expect(local.size).toBe(0)
    expect(session.size).toBe(0)
  })

  it('draws in the app\'s theme: dark with the load, and follows a theme message from the parent alone', async () => {
    const { win, parent, receive } = frameWindow()
    const { made, players } = fakePlayers()
    startFrame(win, players)
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { ...load, theme: 'dark' } })
    await vi.waitFor(() => expect(made).toHaveLength(1))
    expect(document.documentElement.classList.contains('veyq-dark')).toBe(true)
    receive({ source: {} as Window, origin: 'https://veyq.example.org', data: { type: 'veyq-music:theme', theme: 'light' } })
    expect(document.documentElement.classList.contains('veyq-dark'), 'another window sets no theme').toBe(true)
    receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: { type: 'veyq-music:theme', theme: 'light' } })
    expect(document.documentElement.classList.contains('veyq-dark')).toBe(false)
  })

  it('a later load of the same service switches the playlist on the one player; another service\'s is ignored', async () => {
    const { win, parent, receive } = frameWindow()
    const { made, players } = fakePlayers()
    startFrame(win, players)
    const say = (data: unknown) => receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data })
    say(load)
    await vi.waitFor(() => expect(made).toHaveLength(1))
    say({ ...load, playlist: { service: 'spotify', id: '1111111111111111111111' } })
    await vi.waitFor(() => expect(made[0].player.switchTo).toHaveBeenCalledWith('1111111111111111111111'))
    expect(document.querySelector<HTMLAnchorElement>('.mf-open')?.href, 'the link back follows').toBe('https://open.spotify.com/playlist/1111111111111111111111')
    say({ ...load, playlist: { service: 'apple-music', id: 'pl.u-AbC123', storefront: 'de' }, credential: 'a.b.c-token' })
    await new Promise((r) => setTimeout(r, 10))
    expect(made, 'no second vendor in this frame').toHaveLength(1)
    expect(made[0].player.switchTo).toHaveBeenCalledOnce()
  })

  it('what plays is drawn only beside the vendor\'s mark; without one, the state stands in its place', async () => {
    const track: Track = { title: 'Song', artist: 'Band', artwork: 'https://i.scdn.co/image/ab67', canPrevious: false, canNext: true }
    const run = async (marked: boolean) => {
      document.body.replaceChildren()
      const { win, parent, receive } = frameWindow()
      const { made, players } = fakePlayers()
      const mark = { src: '/brand/spotify-icon.svg', width: 21, height: 21 }
      startFrame(win, players, { spotify: marked ? mark : null, 'apple-music': null })
      receive({ source: parent as unknown as Window, origin: 'https://veyq.example.org', data: load })
      await vi.waitFor(() => expect(made).toHaveLength(1))
      made[0].report('playing')
      made[0].show(track)
      return document
    }
    const unmarked = await run(false)
    expect(unmarked.querySelector<HTMLElement>('.mf-now')!.hidden, 'no mark: no title, artist or cover').toBe(true)
    expect(unmarked.querySelector('.mf-brand')).toBeNull()
    expect(unmarked.querySelector('.mf-status')?.textContent).toBe('Playing')
    const marked = await run(true)
    expect(marked.querySelector<HTMLElement>('.mf-now')!.hidden).toBe(false)
    expect(marked.querySelector('.mf-title')?.textContent).toBe('Song')
    expect(marked.querySelector('.mf-artist')?.textContent).toBe('Band')
    expect(marked.querySelector<HTMLImageElement>('.mf-cover')?.src).toBe('https://i.scdn.co/image/ab67')
    expect(marked.querySelector('.mf-open .mf-brand'), 'the mark sits in the link back').not.toBeNull()
    expect(marked.querySelector<HTMLButtonElement>('.mf-previous')!.disabled, 'a skip the vendor restricts is not offered').toBe(true)
    expect(marked.querySelector<HTMLButtonElement>('.mf-next')!.disabled).toBe(false)
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
