// The music frame's page (ADR 0131 decision 2), served by the backend on the instance's music host only, under the
// music host's own policy. Two roles by path:
//  - `/music-frame`: embedded by the app after the viewer's opt-in. It takes `load` and `forget` from its parent window
//    alone, loads the one vendor's player that `load` names, draws the sign-in, play/pause and next controls (a vendor's
//    sign-in popup and audio need a click IN this page), and reports its state to the parent.
//  - `/music-frame/spotify-callback`: Spotify's redirect target in the sign-in popup. It relays the code and state to the
//    frame over a BroadcastChannel of this origin (and to an opener where one survived), then closes.
// Nothing here is the app's: no key, no node, no other content. Who may frame it is the policy's `frame-ancestors`.
import { parseAppMessage, playlistLink, type AppMessage, type FrameError, type FrameMessage, type FrameStatus } from './protocol'
import { CALLBACK_PATH, parseCallback, SPOTIFY_CHANNEL } from './spotifyAuth'
import { stringsFor, type FrameStrings } from './strings'
import type { MusicPlayer } from './player'

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

interface View {
  status: HTMLElement
  signIn: HTMLButtonElement
  toggle: HTMLButtonElement
  next: HTMLButtonElement
  open: HTMLAnchorElement
}

function button(doc: Document, cls: string): HTMLButtonElement {
  const b = doc.createElement('button')
  b.type = 'button'
  b.className = cls
  return b
}

function buildView(doc: Document): View {
  const root = doc.createElement('div')
  root.className = 'mf'
  const status = doc.createElement('p')
  status.className = 'mf-status'
  status.setAttribute('role', 'status')
  const controls = doc.createElement('div')
  controls.className = 'mf-controls'
  const view = {
    status,
    signIn: button(doc, 'mf-button mf-primary'),
    toggle: button(doc, 'mf-button mf-primary'),
    next: button(doc, 'mf-button'),
    open: doc.createElement('a'),
  }
  view.open.className = 'mf-open'
  view.open.target = '_blank'
  view.open.rel = 'noopener noreferrer'
  controls.append(view.signIn, view.toggle, view.next, view.open)
  root.append(status, controls)
  doc.body.replaceChildren(root)
  return view
}

/** Draw [status] (and [error]) into [view] in [s]'s words. */
export function render(view: View, s: FrameStrings, service: string, status: FrameStatus, error?: FrameError): void {
  view.status.textContent =
    status === 'error' && error ? s.error[error]
      : status === 'loading' ? s.loading
        : status === 'signed-out' ? s.signedOut(service)
          : status === 'playing' ? s.playing
            : status === 'paused' ? s.paused
              : s.ready
  view.signIn.hidden = !(status === 'signed-out' || (status === 'error' && error === 'sign-in-failed'))
  const playable = status === 'ready' || status === 'playing' || status === 'paused'
  view.toggle.hidden = !playable
  view.next.hidden = !(status === 'playing' || status === 'paused')
  view.toggle.textContent = status === 'playing' ? s.pause : s.play
}

/** The embedded frame: listen to the parent, mount one player per `load`, report every state. */
export function startFrame(win: Window = window, players: {
  spotify: (credential: string, id: string, report: (st: FrameStatus, e?: FrameError) => void) => Promise<MusicPlayer>
  apple: (credential: string, id: string, report: (st: FrameStatus, e?: FrameError) => void) => Promise<MusicPlayer>
}): void {
  const doc = win.document
  let player: MusicPlayer | null = null
  let parentOrigin: string | null = null

  const tell = (m: FrameMessage) => win.parent.postMessage(m, parentOrigin ?? '*')

  async function load(m: Extract<AppMessage, { type: 'veyq-music:load' }>) {
    if (player) return // one playlist per frame; the app mounts a new frame for another
    const s = stringsFor(m.locale)
    doc.documentElement.lang = m.locale
    const service = s.service[m.playlist.service]
    const v = buildView(doc)
    v.signIn.textContent = s.signIn(service)
    v.next.textContent = s.next
    v.open.textContent = s.open(service)
    v.open.href = playlistLink(m.playlist)
    const report = (status: FrameStatus, error?: FrameError) => {
      render(v, s, service, status, error)
      tell({ type: 'veyq-music:state', status, ...(error ? { error } : {}) })
    }
    report(m.playlist.service === 'spotify' ? 'signed-out' : 'loading')
    const make = m.playlist.service === 'spotify' ? players.spotify : players.apple
    const p = await make(m.credential, m.playlist.id, report)
    player = p
    v.signIn.onclick = () => void p.signIn()
    v.toggle.onclick = () => void p.toggle()
    v.next.onclick = () => void p.next()
  }

  win.addEventListener('message', (e: MessageEvent) => {
    if (e.source !== win.parent) return // only the window that embeds this frame speaks to it
    const m = parseAppMessage(e.data)
    if (!m) return
    parentOrigin ??= e.origin
    if (e.origin !== parentOrigin) return
    if (m.type === 'veyq-music:load') void load(m)
    else
      void (async () => {
        await player?.forget()
        player = null
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
    spotify: async (c, id, r) => (await import('./spotify')).createSpotifyPlayer(c, id, r),
    apple: async (c, id, r) => (await import('./apple')).createApplePlayer(c, id, r),
  })
}
