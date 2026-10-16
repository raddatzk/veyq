// A tiny cross-component signal that a space's icon changed (uc.spaces.set-space-icon). The owner edits the icon in
// the tenant members panel, but the always-mounted AppSidebar renders it (its own row + the switcher) — a different
// component that would otherwise keep a stale icon until a full reload. The panel bumps this after a set/clear; the
// sidebar watches it and re-decrypts. Carries no data — just a revision tick — so it stays operator-blind by design.
import { ref } from 'vue'

const revision = ref(0)

/** A reactive tick incremented whenever a space icon is set or cleared — watch it to re-read icons. */
export const spaceIconRevision = revision

/** Signal that a space icon changed (call after a successful set/clear). */
export function bumpSpaceIcon(): void {
  revision.value++
}
