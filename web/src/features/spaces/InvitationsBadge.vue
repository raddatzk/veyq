<script setup lang="ts">
// uc.spaces.consent-group-add (web, cr-invitations-topbar-badge) — the top-bar attention badge for pending group
// invitations. **Appears-on-demand**: rendered only while the invitee has ≥1 open proposal; it shows the count and
// routes to the invitations view (GroupInvitationsView). The count is the module-singleton `useMyInvitations()`
// (shared with that view), derived from the existing `listMyGroupInvitations` — no new server signal, self-clearing
// (accept/decline drops the row → the count falls), no read-state. Trigger mirrors the upload tray (UploadStatusMenu):
// a transparent square top-bar icon button (scoped styles don't cross component boundaries, so the shared look is
// replicated here, as the tray does).
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useMyInvitations } from './invitations'

const { t } = useI18n()
const router = useRouter()
const { count } = useMyInvitations()
</script>

<template>
  <button
    v-if="count > 0"
    type="button"
    class="invitations-trigger"
    :aria-label="t('shell.invitations')"
    :title="t('shell.invitations')"
    @click="router.push('/invitations')"
  >
    <span class="pi pi-inbox" aria-hidden="true" />
    <span class="invitations-badge">{{ count }}</span>
  </button>
</template>

<style scoped>
/* Match the one sibling top-bar icon button, .uploads-trigger (UploadStatusMenu): transparent + borderless,
   hover tint only, same 2.25rem (36px) square + radius. */
.invitations-trigger {
  position: relative;
  display: grid;
  place-items: center;
  width: 2.25rem;
  height: 2.25rem;
  border: none;
  border-radius: 0.5rem;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.invitations-trigger:hover {
  background: var(--p-content-hover-background, rgba(127, 127, 127, 0.08));
}
.invitations-badge {
  position: absolute;
  top: -0.4rem;
  right: -0.4rem;
  min-width: 1.1rem;
  height: 1.1rem;
  padding: 0 0.25rem;
  border-radius: 999px;
  background: var(--app-accent);
  color: var(--app-on-strong);
  font-size: 0.7rem;
  line-height: 1.1rem;
  text-align: center;
}
</style>
