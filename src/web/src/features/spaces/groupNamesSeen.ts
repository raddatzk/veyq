// The group names this page has opened, by scope id — a device-local label for a group (inbox #280). Securing the
// account lists every scope of every tenant from its plan, which serves no group name, and this page opens only the
// active tenant's groups; so the security panel labels a group's row with the name this page last opened, marked as
// possibly out of date, instead of the raw scope id.
//
// **Memory only, never persisted.** A decrypted name never reaches IndexedDB or Web Storage here — it lives exactly
// as long as the page, like every other decrypted value the app holds, and the session ending's document load
// (ADR 0070) discards it with the rest of the heap. So a reload after the rotation shows the neutral label: no name
// that this page has not itself opened under the keys it holds is ever shown.
import { shallowReactive } from 'vue'

const seen = shallowReactive(new Map<string, string>())

/** Record a group name this page just opened. An empty name (a group without one) records nothing. */
export function rememberGroupName(scopeId: string, name: string): void {
  if (name) seen.set(scopeId, name)
}

/** The name this page last opened for the group, or null. Reactive, so a label fills in when the sidebar's
 *  group list finishes opening after the panel rendered. */
export function groupNameSeen(scopeId: string): string | null {
  return seen.get(scopeId) ?? null
}

/** Tests only: start from a page that has opened nothing. */
export function forgetGroupNamesSeen(): void {
  seen.clear()
}
