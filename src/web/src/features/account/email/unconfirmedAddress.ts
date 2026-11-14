// The shell's one copy of "is the signed-in account's address confirmed?" (ADR 0129 decision 5). `UnconfirmedEmailBanner.vue`
// is its only writer — its read of the server's copy is the only one asked — and `AppShell.vue` reads it to hold back the
// donation notice and the finish-your-profile prompt while the address is unconfirmed (owner 2026-11-13): a newcomer who
// joined by link meets one notice, the one that asks them to act, and the other two follow once the address is confirmed.
import { ref } from 'vue'

/**
 * `undefined` while not yet known (no banner mounted, or its read still on its way), `null` while confirmed or unreadable
 * (a banner that cannot decide shows nothing, so it holds nothing back either), the address while it is unconfirmed.
 */
export const unconfirmedAddress = ref<string | null | undefined>(undefined)
