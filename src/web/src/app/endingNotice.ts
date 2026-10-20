// What the sign-in page says after a session ending (uc.account.sign-out). An ending loads the sign-in page as a new
// document, so whatever it has to say crosses that load as ONE WORD in this tab's `sessionStorage` — never account
// data — and the sign-in page takes it once. App infrastructure, beside the ending, because two triggers leave one:
// the sign-out sequence in `features/account/login/signOut.ts`, and a refresh the server refused
// (`sessionRefresh.ts`), which may not import a feature.

/**
 * - `unconfirmed`: the revoke did not reach the server (uc.account.sign-out E4). The refresh cookie is still live,
 *   so this browser can come back signed in, though locked; the sign-in card's own sign-out is the retry.
 * - `everywhere` / `everywhereUnconfirmed`: the locked screen's *sign out everywhere*, whose other half — every
 *   other device — already succeeded (uc.account.sessions E3).
 * - `grantInterrupted`: this device's session was refused while it was giving a new device access
 *   (uc.account.onboard-device E6) — a reset, a password change, a rotation or a sign-out on another device ended
 *   it, and the server let no device in.
 * - `refused`: the server refused this tab's session on a refresh, outside any step that explains it (uc.account.sign-out
 *   E1, inbox #322). Its plain 401 does not say whether the session expired or was ended elsewhere, so neither does the
 *   page: it names the causes. Only a tab that held a session gets here, so it says nothing about any account.
 * - `otherTab`: another tab of this browser ended the session and this one followed (uc.account.sign-out E3).
 * - `erased`: this tab erased its own account (uc.account.erase-account): every session of it ended with the request,
 *   and it cannot sign in again unless the Server Owner stops the erasure. The word names no account and no date.
 */
export type EndingNotice =
  | 'unconfirmed'
  | 'everywhere'
  | 'everywhereUnconfirmed'
  | 'grantInterrupted'
  | 'refused'
  | 'otherTab'
  | 'erased'

// This tab's `sessionStorage`: it survives the document load in the same tab and nowhere else.
const NOTICE_KEY = 'veyq.sign-out-notice'
const NOTICES: readonly EndingNotice[] = [
  'unconfirmed',
  'everywhere',
  'everywhereUnconfirmed',
  'grantInterrupted',
  'refused',
  'otherTab',
  'erased',
]

/** Leave [notice] for the sign-in page the coming ending loads. `null` leaves nothing. */
export function leaveEndingNotice(notice: EndingNotice | null): void {
  if (!notice) return
  try {
    sessionStorage.setItem(NOTICE_KEY, notice)
  } catch {
    /* storage denied — the sign-in page then says nothing, and the ending itself still runs */
  }
}

/** The notice the last ending in this tab left, or null. Read ONCE: taking it removes it. */
export function takeEndingNotice(): EndingNotice | null {
  try {
    const raw = sessionStorage.getItem(NOTICE_KEY)
    sessionStorage.removeItem(NOTICE_KEY)
    return NOTICES.find((n) => n === raw) ?? null
  } catch {
    return null
  }
}

/** What a refused refresh's ending should say, while a caller that asked for one is running; `undefined` while none
 *  is, when the ending says `refused`. */
let explanation: EndingNotice | null | undefined = undefined

/**
 * Run [call]; should the server refuse this tab's session while it runs, the ending that refusal triggers leaves
 * [notice] for the sign-in page instead of the default `refused`. For a step whose interruption the person must hear
 * about: the ending is a document load, so an error the screen showed would be gone before anyone read it. `null`
 * says nothing — the sign-out sequence, whose refusal is what signing out wanted.
 */
export async function explainEnding<T>(notice: EndingNotice | null, call: () => Promise<T>): Promise<T> {
  const outer = explanation
  explanation = notice
  try {
    return await call()
  } finally {
    explanation = outer
  }
}

/** The notice a refused refresh's ending leaves right now: the running {@link explainEnding}'s, else `refused`. */
export function endingExplanation(): EndingNotice | null {
  return explanation === undefined ? 'refused' : explanation
}
