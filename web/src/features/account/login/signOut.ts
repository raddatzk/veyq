// The one sign-out sequence (uc.account.sign-out § Main flow, ADR 0070 § Decision 2). All five sign-out entry
// points call it — the profile menu, revoking your own session, declining the terms, the sign-in card and the
// locked screen — and none carries its own copy: a screen with its own sequence is how a sign-out that skips half
// of it ships. `web/eslint.config.js` keeps `.vue` files from importing the ending directly.
//
// It does only what needs the session still working — delete the convenience wraps, revoke the family — and then
// hands over to the ending (`app/sessionEnd.ts`), which loads the sign-in page as a new document. That document is
// the wipe, so nothing here resets a store: not the vault, not the active space, not anything added next year.
//
// `handle401` is deliberately NOT this: it is redirect-only, for a request that came back unauthorized. Nor is the
// account erasure (`features/account/erasure/eraseAccount.ts`): its request already ended every session server-side,
// so it has no server half left to run and hands straight over to the same ending.
import { sessionApi } from '../../../api/client'
import { isSessionRefusal, withRefresh } from '../../../app/sessionRefresh'
import { endSession } from '../../../app/sessionEnd'
import { clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { clearSessionVault } from '../rememberMe/sessionVault'
import { explainEnding, leaveEndingNotice, takeEndingNotice, type EndingNotice } from '../../../app/endingNotice'

/** What the sign-in page says after an ending, carried across the new document as one word — never account data.
 *  The words and their storage live in `app/endingNotice.ts`, because a refused refresh leaves one too. */
export type SignOutNotice = EndingNotice

/**
 * Sign this tab out: delete the convenience wraps and revoke the family while the session works, then end the
 * session. The ending runs whether or not the server calls succeeded — an unreachable server is no reason to
 * leave keys in the page. Resolves when the ending has started the document load.
 *
 * `everywhere` is the locked screen's case: it has just revoked every other family, and says so.
 */
export async function signOut(opts: { everywhere?: boolean } = {}): Promise<void> {
  await clearSessionVault().catch(() => {})
  await clearDeviceKey().catch(() => {})
  let reached = true
  try {
    // A refresh refused under the logout starts the ending itself; it says nothing (`null`), not `refused`: a session
    // that was already over is what signing out wanted, and the notice below is this sequence's own.
    await explainEnding(null, () => withRefresh(() => sessionApi().logout()))
  } catch (e) {
    // A refusal of the session is the server answering that it is not live any more — revoked or expired — which
    // is what signing out wanted. Any other failure (offline, 5xx, a suspended tenant) leaves the cookie standing.
    reached = await isSessionRefusal(e)
  }
  leaveEndingNotice(opts.everywhere ? (reached ? 'everywhere' : 'everywhereUnconfirmed') : reached ? null : 'unconfirmed')
  await endSession()
}

/** The notice the last ending in this tab left, or null. Read ONCE: taking it removes it. */
export function takeSignOutNotice(): SignOutNotice | null {
  return takeEndingNotice()
}
