// The session ending (uc.account.sign-out § The ending, ADR 0070): the one ending all three triggers run — this
// tab signing out, a refresh the server rejected, and another tab's announcement. It drops the local convenience
// tiers, tells the media service worker to drop every playback, announces the end to the other tabs, and loads
// the sign-in page as a NEW DOCUMENT. The new document is the wipe: it discards the page's whole heap, so every
// decrypted store goes, including one nobody has written yet. There is no list of stores to reset, on purpose.
//
// It imports no feature and not the router (ADR 0070 § Decision 1), which is what lets the refresh path call it
// without a cycle. Screens never call it: they call the one sign-out sequence in
// `features/account/login/signOut.ts`, and an ESLint rule keeps `.vue` files from importing this module.
import { session } from './session'
import { clearVault, vaultState } from './vault'
import { dropConvenienceTiers } from './convenienceTiers'
import { endAllPlaybacks } from '../sw/register'
import { leaveEndingNotice, type EndingNotice } from './endingNotice'

/** The same-origin channel the other tabs of this instance listen on. Its one message carries nothing but its
 *  type: no account, no id (uc.account.sign-out § Quality notes). */
export const SESSION_CHANNEL = 'veyq-session'
type SessionAnnouncement = { type: 'ended' }

/** Where an ending lands. A literal rather than a route name, because this module does not import the router. */
const SIGN_IN_PATH = '/login'

let ending: Promise<void> | null = null

/**
 * Whether this tab holds a session: an access token in memory, or an unlocked vault. A tab that holds none — the
 * sign-in page at boot, a public share, a device waiting in a pairing ceremony — never runs the ending: there is
 * nothing to end, and an ending on the sign-in page would load the sign-in page into itself (E2).
 */
export function holdsSession(): boolean {
  return session.accessToken !== null || vaultState.unlocked
}

/**
 * End this tab's session and tell the other tabs. Runs only when the tab holds a session, and at most once:
 * a second trigger while an ending runs joins it. The check is made synchronously, before anything awaits, so a
 * caller may drop the tokens right after calling this.
 *
 * [notice] is what the sign-in page says afterwards, left only when this call starts an ending: a tab that holds no
 * session loads nothing, and a notice left there would surface on some later sign-in page instead.
 */
export function endSession(notice: EndingNotice | null = null): Promise<void> {
  if (ending) return ending
  if (!holdsSession()) return Promise.resolve()
  leaveEndingNotice(notice)
  ending = runEnding(true)
  return ending
}

/**
 * Listen for another tab's announcement. A received *ended* runs the ending WITHOUT re-announcing it, so the
 * message never echoes, and without calling the server: the tabs share one family through the refresh cookie,
 * so it is already revoked or dead (E3). Installed once at boot; returns the teardown for tests.
 */
export function listenForSessionEnd(): () => void {
  if (typeof BroadcastChannel === 'undefined') return () => {}
  const channel = new BroadcastChannel(SESSION_CHANNEL)
  channel.onmessage = (e: MessageEvent) => {
    if ((e.data as SessionAnnouncement | null)?.type !== 'ended') return
    if (ending || !holdsSession()) return
    // The sign-in page says why this tab's session ended (inbox #322): another tab ended it.
    leaveEndingNotice('otherTab')
    ending = runEnding(false)
  }
  return () => channel.close()
}

async function runEnding(announce: boolean): Promise<void> {
  // 1. The local convenience tiers. After a sign-out this is a no-op; on the other two triggers the session is
  //    already dead, so their server halves are out of reach and only the local halves can go.
  await dropConvenienceTiers()
  // 2. The media service worker lives outside the page, so the new document does not take its plaintext along.
  await endAllPlaybacks()
  // 3. Only the tab that ended the session announces it.
  if (announce && typeof BroadcastChannel !== 'undefined') {
    const channel = new BroadcastChannel(SESSION_CHANNEL)
    channel.postMessage({ type: 'ended' } satisfies SessionAnnouncement)
    channel.close()
  }
  // Not the wipe — the document load below is — but the one buffer that holds the Master Key at rest is zeroed
  // first, so a heap snapshot taken before the old page is collected finds zeroes there (best-effort, as
  // `clearVault` says: the copies its getters handed out are the new document's to drop).
  clearVault()
  // 4. A DOCUMENT LOAD, never a router push. This line is the whole guarantee (ADR 0070): a route change keeps the
  //    page's memory, and a list of stores to reset is how decrypted names outlived every sign-out before. `replace`
  //    rather than `assign`, so Back does not return to the page that held the keys.
  location.replace(SIGN_IN_PATH)
}
