// This browser's dismissal of the donation banner (uc.platform.support-the-project, ADR 0124): one flag for the whole
// browser, not per audience — a guest's dismissal also holds when the same browser later signs in, which is what "the
// first time anyone opens veyq" means. A member's dismissal is ALSO stored on the account (`donationBannerDismissed`,
// the profile update), which `DonationBanner.vue` reads; this module is only the browser's half and never calls a client.
//
// Every storage access is guarded (A3): a read that fails counts as no dismissal, so the banner shows; a write that fails
// still hides the banner for the rest of this visit (the in-memory flag below).
const KEY = 'veyq.donationBannerDismissed'

let dismissedThisVisit = false

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null // storage disabled (private mode / blocked)
  }
}

/** Whether this browser holds a dismissal — stored, or made during this visit. */
export function dismissedInThisBrowser(): boolean {
  if (dismissedThisVisit) return true
  try {
    return storage()?.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** Record the dismissal in this browser: for this visit at once, and in storage where it can be written. */
export function dismissInThisBrowser(): void {
  dismissedThisVisit = true
  try {
    storage()?.setItem(KEY, '1')
  } catch {
    // blocked storage: the banner stays hidden for this visit only
  }
}

/** Tests only: forget this visit's in-memory dismissal (the stored one is the test's localStorage). */
export function forgetDonationDismissalForTest(): void {
  dismissedThisVisit = false
}
