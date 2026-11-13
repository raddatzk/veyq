// The music frame's page (ADR 0131 decision 2), served by the backend on the instance's music host only, under the
// music host's own policy. Two roles by path:
//  - `/music-frame`: embedded by the app's footer bar after the viewer's opt-in. It takes `load`, `theme` and `forget`
//    from its parent window alone, loads the one vendor's player that the first `load` names, and draws veyq's own player
//    (decision 6): the vendor's sign-in, previous / play-pause / next, what plays - its cover, title and artist, only where
//    the vendor's mark that attributes it is bundled (`brand.ts`) - and the link back into the vendor's app. A vendor's
//    sign-in popup and audio need a click IN this page. A later `load` of the same service switches the playlist and keeps
//    the sign-in; one of another service is ignored (the app mounts a new frame for it). It reports only its state.
//  - `/music-frame/spotify-callback`: Spotify's redirect target in the sign-in popup. It relays the code and state to the
//    frame over a BroadcastChannel of this origin (and to an opener where one survived), then closes.
// Nothing here is the app's: no key, no node, no other content. Who may frame it is the policy's `frame-ancestors`. Its look
// is veyq's: the app's own token sheet is built into this page's (`musicFrame.css`), and the app's theme arrives with
// `load` and `theme`, since a viewer's chosen theme is not the system's the frame could read.
import {
  parseAppMessage, playlistLink, type AppMessage, type FrameError, type FrameMessage, type FrameStatus, type MusicService, type Theme,
} from './protocol'
import { CALLBACK_PATH, parseCallback, SPOTIFY_CHANNEL } from './spotifyAuth'
import { stringsFor, type FrameStrings } from './strings'
import { BRAND_MARKS, type BrandMark } from './brand'
import type { MusicPlayer, Report, Show, Track } from './player'

/** The callback page's whole job: relay what Spotify answered, then go. */
export function relayCallback(win: Window = window): boolean {
  const result = parseCallback(win.location.search)
  if (!result) return false
  const message = { kind: 'veyq-spotify-callback', ...result }
  const channel = new BroadcastChannel(SPOTIFY_CHANNEL)
  channel.postMessage(message)
  channel.close()
  try {
    win.opener?.postMessage(message, win.location.origin)
  } catch {
    // no opener, or one of another origin: the channel carried it
  }
  win.history.replaceState(null, '', win.location.pathname) // the code leaves the address bar at once
  win.close()
  return true
}

/** Clear this origin's local and session storage (in a partitioning browser: this partition's). Storage that is
 *  disabled or refuses is left as it is: there is nothing else to clear it with. */
export function clearOriginStorage(win: Window): void {
  for (const area of ['localStorage', 'sessionStorage'] as const) {
    try {
      win[area].clear()
    } catch {
      /* disabled storage */
    }
  }
}

/** Apply the app's theme: the token sheet's dark roles sit under `:root.veyq-dark`, as in the app. */
export function applyTheme(doc: Document, theme: Theme): void {
  doc.documentElement.classList.toggle('veyq-dark', theme === 'dark')
}

const SVG = 'http://www.w3.org/2000/svg'
/** The controls' glyphs, drawn as paths (no markup string anywhere in this page). */
const GLYPHS = {
  play: 'M8 5v14l11-7z',
  pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
  previous: 'M6 6h2v12H6zM9.5 12 18 18V6z',
  next: 'M16 6h2v12h-2zM6 18l8.5-6L6 6z',
} as const

function glyph(doc: Document, d: string): SVGSVGElement {
  const svg = doc.createElementNS(SVG, 'svg')
  svg.setAttribute('viewBox', '0 0 24 24')
  svg.setAttribute('aria-hidden', 'true')
  svg.setAttribute('focusable', 'false')
  const path = doc.createElementNS(SVG, 'path')
  path.setAttribute('d', d)
  svg.append(path)
  return svg
}

interface View {
  root: HTMLElement
  now: HTMLElement
  cover: HTMLImageElement
  title: HTMLElement
  artist: HTMLElement
  status: HTMLElement
  signIn: HTMLButtonElement
  previous: HTMLButtonElement
  toggle: HTMLButtonElement
  toggleGlyph: SVGPathElement
  next: HTMLButtonElement
  open: HTMLAnchorElement
  openText: HTMLElement
}

function el<K extends keyof HTMLElementTagNameMap>(doc: Document, tag: K, cls: string): HTMLElementTagNameMap[K] {
  const e = doc.createElement(tag)
  e.className = cls
  return e
}

function iconButton(doc: Document, cls: string, d: string): HTMLButtonElement {
  const b = el(doc, 'button', 'mf-icon-button')
  b.classList.add(cls)
  b.type = 'button'
  b.append(glyph(doc, d))
  return b
}

function buildView(doc: Document, mark: BrandMark | null): View {
  const root = el(doc, 'div', 'mf')
  const now = el(doc, 'div', 'mf-now')
  const cover = el(doc, 'img', 'mf-cover')
  cover.alt = ''
  cover.referrerPolicy = 'no-referrer'
  const meta = el(doc, 'div', 'mf-meta')
  const title = el(doc, 'span', 'mf-title')
  const artist = el(doc, 'span', 'mf-artist')
  meta.append(title, artist)
  now.append(cover, meta)
  const status = el(doc, 'p', 'mf-status')
  status.setAttribute('role', 'status')
  const controls = el(doc, 'div', 'mf-controls')
  const signIn = el(doc, 'button', 'mf-sign-in')
  signIn.type = 'button'
  const previous = iconButton(doc, 'mf-previous', GLYPHS.previous)
  const toggle = iconButton(doc, 'mf-toggle', GLYPHS.play)
  const next = iconButton(doc, 'mf-next', GLYPHS.next)
  controls.append(signIn, previous, toggle, next)
  const open = el(doc, 'a', 'mf-open')
  open.target = '_blank'
  open.rel = 'noopener noreferrer'
  if (mark) {
    const logo = el(doc, 'img', 'mf-brand')
    logo.src = mark.src
    logo.width = mark.width
    logo.height = mark.height
    logo.alt = ''
    open.append(logo)
  }
  const openText = el(doc, 'span', 'mf-open-text')
  open.append(openText)
  root.append(now, status, controls, open)
  doc.body.replaceChildren(root)
  return { root, now, cover, title, artist, status, signIn, previous, toggle, toggleGlyph: toggle.querySelector('path')!, next, open, openText }
}

/** Draw [status] (and [error]) and what plays into [view] in [s]'s words. What plays is drawn only where [attributed]:
 *  the vendor's mark is beside it (`brand.ts`); else the state's words stand in its place. */
export function render(
  view: View, s: FrameStrings, service: string, status: FrameStatus, error: FrameError | undefined, track: Track | null, attributed: boolean,
): void {
  const words =
    status === 'error' && error ? s.error[error]
      : status === 'loading' ? s.loading
        : status === 'signed-out' ? s.signedOut(service)
          : status === 'playing' ? s.playing
            : status === 'paused' ? s.paused
              : s.ready
  view.status.textContent = words
  view.status.title = words // cut in a narrow bar: the whole sentence on hover
  const shown = attributed && track !== null && (status === 'playing' || status === 'paused')
  view.now.hidden = !shown
  // the state stays the page's live region; beside a drawn track it is read, not shown
  view.status.classList.toggle('mf-visually-hidden', shown)
  if (shown && track) {
    view.title.textContent = track.title
    view.title.title = track.title // truncated in the bar: the whole name on hover (Spotify: the full metadata viewable)
    view.artist.textContent = track.artist
    view.artist.title = track.artist
    view.cover.hidden = track.artwork === null
    if (track.artwork !== null && view.cover.getAttribute('src') !== track.artwork) view.cover.src = track.artwork
  }
  view.root.dataset.status = status
  view.signIn.hidden = !(status === 'signed-out' || (status === 'error' && error === 'sign-in-failed'))
  const playable = status === 'ready' || status === 'playing' || status === 'paused'
  view.toggle.hidden = !playable
  const label = status === 'playing' ? s.pause : s.play
  view.toggle.setAttribute('aria-label', label)
  view.toggle.title = label
  view.toggleGlyph.setAttribute('d', status === 'playing' ? GLYPHS.pause : GLYPHS.play)
  const started = status === 'playing' || status === 'paused'
  view.previous.hidden = !started
  view.next.hidden = !started
  // a skip the vendor restricts is not offered (Spotify's `disallows`)
  view.previous.disabled = track !== null && !track.canPrevious
  view.next.disabled = track !== null && !track.canNext
}

type MakePlayer = (credential: string, id: string, report: Report, show: Show) => Promise<MusicPlayer>

/** The embedded frame: listen to the parent, mount one player per frame, report every state. */
export function startFrame(
  win: Window = window,
  players: { spotify: MakePlayer; apple: MakePlayer },
  marks: Readonly<Record<MusicService, BrandMark | null>> = BRAND_MARKS,
): void {
  const doc = win.document
  let player: MusicPlayer | null = null
  let service: MusicService | null = null
  let parentOrigin: string | null = null
  let view: View | null = null
  /** The playlist the parent asked for last: a switch that arrives while the player is still being made is kept here. */
  let wanted: string | null = null

  const tell = (m: FrameMessage) => win.parent.postMessage(m, parentOrigin ?? '*')

  async function load(m: Extract<AppMessage, { type: 'veyq-music:load' }>) {
    applyTheme(doc, m.theme)
    if (service !== null) {
      // one service per frame: another one's playlist is the app's to mount a new frame for
      if (m.playlist.service !== service) return
      if (view) view.open.href = playlistLink(m.playlist)
      wanted = m.playlist.id
      await player?.switchTo(m.playlist.id)
      return
    }
    service = m.playlist.service
    wanted = m.playlist.id
    const s = stringsFor(m.locale)
    doc.documentElement.lang = m.locale
    const name = s.service[m.playlist.service]
    const mark = marks[m.playlist.service]
    const v = buildView(doc, mark)
    view = v
    v.signIn.textContent = s.signIn(name)
    for (const [b, label] of [[v.previous, s.previous], [v.next, s.next]] as const) {
      b.setAttribute('aria-label', label)
      b.title = label
    }
    v.openText.textContent = s.open(name)
    // named by its words even where a narrow bar draws the vendor's mark alone
    v.open.setAttribute('aria-label', s.open(name))
    v.open.href = playlistLink(m.playlist)
    let status: FrameStatus = 'loading'
    let error: FrameError | undefined
    let track: Track | null = null
    const draw = () => render(v, s, name, status, error, track, mark !== null)
    const report: Report = (next, e) => {
      status = next
      error = e
      draw()
      tell({ type: 'veyq-music:state', status: next, ...(e ? { error: e } : {}) })
    }
    const show: Show = (t) => {
      track = t
      draw()
    }
    report(m.playlist.service === 'spotify' ? 'signed-out' : 'loading')
    const make = m.playlist.service === 'spotify' ? players.spotify : players.apple
    const p = await make(m.credential, m.playlist.id, report, show)
    player = p
    if (wanted !== m.playlist.id && wanted !== null) await p.switchTo(wanted)
    v.signIn.onclick = () => void p.signIn()
    v.toggle.onclick = () => void p.toggle()
    v.previous.onclick = () => void p.previous()
    v.next.onclick = () => void p.next()
  }

  win.addEventListener('message', (e: MessageEvent) => {
    if (e.source !== win.parent) return // only the window that embeds this frame speaks to it
    const m = parseAppMessage(e.data)
    if (!m) return
    parentOrigin ??= e.origin
    if (e.origin !== parentOrigin) return
    if (m.type === 'veyq-music:load') void load(m)
    else if (m.type === 'veyq-music:theme') applyTheme(doc, m.theme)
    else
      void (async () => {
        await player?.forget().catch(() => undefined)
        // This origin keeps nothing of its own, so all its web storage is a vendor's: MusicKit's sign-in sits there. A
        // frame mounted only to forget (a sign-out with no player on screen, ADR 0131 decision 5) loads no vendor script
        // and clears it here; behind a player's own sign-out it is the belt to its braces.
        clearOriginStorage(win)
        player = null
        view = null
        service = null
        wanted = null
        doc.body.replaceChildren()
        tell({ type: 'veyq-music:forgotten' })
      })()
  })
  tell({ type: 'veyq-music:ready' })
}

/** The page's entry: the callback relays, the frame starts. The vendors' adapters are loaded only when `load` asks. */
export function main(win: Window = window): void {
  if (win.location.pathname === CALLBACK_PATH) {
    relayCallback(win)
    return
  }
  startFrame(win, {
    spotify: async (c, id, r, sh) => (await import('./spotify')).createSpotifyPlayer(c, id, r, sh),
    apple: async (c, id, r, sh) => (await import('./apple')).createApplePlayer(c, id, r, sh),
  })
}
