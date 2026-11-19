// This browser's dismissal of the *Open in the app* banner (ADR 0150 § O2): a per-device convenience, not state — one flag
// for the whole browser and every page's banner, kept in `localStorage` and nowhere else (the server never learns it).
// `core/project/donationDismissal.ts`'s guard, deliberately: a read that fails counts as no dismissal, so the banner shows;
// a write that fails still hides it for the rest of this visit.
const KEY = 'veyq.openInAppDismissed'

let dismissedThisVisit = false

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked)
  }
}

/** Whether this browser holds a dismissal — stored, or made during this visit. */
export function openInAppDismissed(): boolean {
  if (dismissedThisVisit) return true
  try {
    return storage()?.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** Record the dismissal: for this visit at once, and in storage where it can be written. */
export function dismissOpenInApp(): void {
  dismissedThisVisit = true
  try {
    storage()?.setItem(KEY, '1')
  } catch {
    // blocked storage: the banner stays hidden for this visit only
  }
}

/** Tests only: forget this visit's in-memory dismissal (the stored one is the test's localStorage). */
export function forgetOpenInAppDismissalForTest(): void {
  dismissedThisVisit = false
}
