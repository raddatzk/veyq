// uc.spaces.consent-group-add (web, cr-invitations-topbar-badge) — the invitee's pending-invitation COUNT as a
// module singleton, so the top-bar attention badge and the invitations view (GroupInvitationsView) share one
// source. Wraps the existing `listMyGroupInvitations` (GET /api/scopes/invitations, tenant-scoped by Host) — no
// new endpoint and no server-visible signal beyond the `pendingConsent` rows the blind server already holds.
// **Self-clearing**: accept/decline drops a row and the count falls; there is no read/unread state to persist.
import { ref, computed } from 'vue'
import { listMyGroupInvitations } from './members'

const count = ref(0)

/** Refresh the open-proposal count for the active tenant. Fail-quiet: on any error the count resets to 0 — a
 *  transient fetch hiccup must never strand a phantom badge (the signal is operational, not a confidentiality
 *  control). Called on scope entry (activeSpace) and after an accept/decline. */
export async function refreshMyInvitations(): Promise<void> {
  try {
    count.value = (await listMyGroupInvitations()).length
  } catch {
    count.value = 0
  }
}

/** Drop the count (logout / re-lock — clearActiveSpace). */
export function clearMyInvitations(): void {
  count.value = 0
}

/** Reactive view for the badge + the view: the open-proposal count. */
export function useMyInvitations() {
  return { count: computed(() => count.value) }
}
