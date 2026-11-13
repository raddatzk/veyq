// A veyq sign-out forgets the vendor's sign-in in the music frame (ADR 0131 decision 5, owner 2026-11-13; inbox #858
// closed): on a shared computer the next person's start of a service must not be signed in as the one who signed out.
// Memories' `onSessionEnding` hook (`lifecycle.ts`), which the session ending runs before it loads the sign-in page - on
// this tab's sign-out, a refused refresh, an account erasure and another tab's announcement; never on a lock, which keeps
// the person and their session (they unlock, or sign out from the locked screen, which is a sign-out).
//
// Two cases:
//  - **A frame is mounted** (the music bar shows one): the bar tells it `forget` and waits for `forgotten`, at most
//    FORGET_WAIT_MS - the same wait as Turn music off. The consent stays: it is this browser's, and only the bar withdraws it.
//  - **None is** (the bar was closed, or nothing was played on this page): a sign-in can still sit in the music origin's
//    storage from an earlier page. Only Apple Music keeps one there (MusicKit; Spotify's token lives in the frame's memory
//    and died with it), and only a browser that agreed to Apple Music at the music host served now can have one - so only
//    then, and only in the tab that started the ending, a HIDDEN frame of the music host is mounted just to forget: it
//    is handed no playlist and no credential, loads no vendor script, and on `forget` clears its origin's web storage
//    (`music-frame/main.ts`). It is removed when it said `forgotten`, or after FORGET_WAIT_MS.
// What stays (stated in ADR 0131): a page whose capabilities do not name the music host when the ending runs mounts no
// hidden frame, and a browser that partitions a frame's storage by top-level site forgets only this site's partition -
// which is the one the bar's frame wrote.
import { parseFrameMessage } from '../../../music-frame/protocol'
import type { SessionEndingContext } from '../../../app/sessionEndingSteps'
import { useMusicConsent } from './useMusicConsent'
import { musicReleased } from './musicRelease'

/** How long a forget waits for the frame's `forgotten` before it goes on without it. */
export const FORGET_WAIT_MS = 1500

let liveFrameForget: (() => Promise<void>) | null = null

/** The music bar hands its mounted frame's forget here while it shows one, and null when it removes it. */
export function setLiveFrameForget(forget: (() => Promise<void>) | null): void {
  liveFrameForget = forget
}

/** Mount a hidden frame of [frameUrl], tell it `forget` once it is ready, and resolve once it said `forgotten` or the
 *  wait is spent; the frame is removed either way. Messages are taken only from that frame's window and [origin]. */
export function forgetInHiddenFrame(frameUrl: string, origin: string, waitMs = FORGET_WAIT_MS): Promise<void> {
  return new Promise((resolve) => {
    const frame = document.createElement('iframe')
    frame.hidden = true
    frame.setAttribute('aria-hidden', 'true')
    frame.tabIndex = -1
    frame.dataset.testid = 'music-frame-forget'
    function done() {
      clearTimeout(timer)
      window.removeEventListener('message', onMessage)
      frame.remove()
      resolve()
    }
    function onMessage(e: MessageEvent) {
      if (e.source !== frame.contentWindow || e.origin !== origin) return
      const m = parseFrameMessage(e.data)
      if (m?.type === 'veyq-music:ready') frame.contentWindow?.postMessage({ type: 'veyq-music:forget' }, origin)
      else if (m?.type === 'veyq-music:forgotten') done()
    }
    window.addEventListener('message', onMessage)
    const timer = setTimeout(done, waitMs) // `done` reads it only when called, after this line
    frame.src = frameUrl
    document.body.appendChild(frame)
  })
}

/** Memories' ending step: forget the vendor's sign-in before the sign-in page loads (see the header). */
export async function forgetMusicSignIn(ctx: SessionEndingContext): Promise<void> {
  if (!musicReleased()) return // TEMPORARY: released dark (ADR 0131 decision 8) - the ending skips the music entirely
  if (liveFrameForget) return liveFrameForget()
  if (!ctx.startedHere || typeof document === 'undefined') return
  const consent = useMusicConsent()
  if (!consent.frameUrl.value || !consent.frameOrigin.value || !consent.consented('apple-music')) return
  await forgetInHiddenFrame(consent.frameUrl.value, consent.frameOrigin.value)
}
