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
 */
export type EndingNotice = 'unconfirmed' | 'everywhere' | 'everywhereUnconfirmed' | 'grantInterrupted'

// This tab's `sessionStorage`: it survives the document load in the same tab and nowhere else.
const NOTICE_KEY = 'veyq.sign-out-notice'
const NOTICES: readonly EndingNotice[] = ['unconfirmed', 'everywhere', 'everywhereUnconfirmed', 'grantInterrupted']

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

/** What a refused refresh's ending should say, while a caller that asked for one is running. */
let explanation: EndingNotice | null = null

/**
 * Run [call]; should the server refuse this tab's session while it runs, the ending that refusal triggers leaves
 * [notice] for the sign-in page. For a step whose interruption the person must hear about: the ending is a document
 * load, so an error the screen showed would be gone before anyone read it.
 */
export async function explainEnding<T>(notice: EndingNotice, call: () => Promise<T>): Promise<T> {
  const outer = explanation
  explanation = notice
  try {
    return await call()
  } finally {
    explanation = outer
  }
}

/** The notice a refused refresh's ending leaves right now, or null (see {@link explainEnding}). */
export function endingExplanation(): EndingNotice | null {
  return explanation
}
