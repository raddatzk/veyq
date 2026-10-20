<script setup lang="ts">
// The shell's top bar (cr-0030). Markup + classes are the design source's WorkspaceTopbar (the
// .workspace-topbar grid) so layout.css styles it identically. Ours differs in the data — the breadcrumb is
// built CLIENT-SIDE from the local decrypted tree (the blind server computes none) — and in the grid: the
// design source's search center is GONE. Search is out of V1 (docs/inbox.md § V1), and a permanently disabled
// box advertising a ⌘K shortcut promises a feature that does not exist. The grid is two columns here; restoring
// the center means restoring the third (`minmax(18rem, 29rem)`) in layout.css alongside it.
//
// No preferences and no settings door live here (ADR 0022 §5) — theme, language and the shell's one entry into
// the hub are all in the sidebar's ProfileMenu. What is left is what is genuinely contextual: the breadcrumb,
// the invitations badge and the upload tray. That matters below the compact threshold, where layout.css gives
// this bar 3rem (≤1020px) and then auto height around a 2.35rem breadcrumb (≤720px): the actions sit in the
// same row as the breadcrumb, so each one is width the crumbs no longer have.
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import { breadcrumb } from '../../app/breadcrumb'
import InvitationsBadge from '../../features/spaces/InvitationsBadge.vue'
import UploadStatusMenu from '../../core/blob/UploadStatusMenu.vue'
import { registry } from '../../core/applications/registry'

const router = useRouter()
// The upload status is the platform's (cr-files-application B3): one core queue, every application's batches, so the
// bar mounts the menu itself, where the Memories tray item sat before. What the applications add to the tray follows
// it, in their `order`; none → nothing more rendered (the case with Memories alone, whose DOM is the pre-B3 one:
// `AppShell.slots.test.ts`).
const trayItems = registry.slotItems('topbarTray')

const home = computed<MenuItem>(() => ({
  label: breadcrumb.trail[0]?.name,
  command: () => void router.push({ hash: breadcrumb.trail[0]?.hash ?? '#~me' }),
}))
const items = computed<MenuItem[]>(() =>
  breadcrumb.trail.slice(1).map((crumb) => ({
    label: crumb.name,
    command: () => void router.push({ hash: crumb.hash }),
  })),
)
</script>

<template>
  <header class="workspace-topbar">
    <Breadcrumb :model="items" :home="home" class="app-breadcrumb" />
    <div class="workspace-topbar-actions">
      <InvitationsBadge />
      <UploadStatusMenu />
      <component :is="item.component" v-for="item in trayItems" :key="item.id" />
    </div>
  </header>
</template>
