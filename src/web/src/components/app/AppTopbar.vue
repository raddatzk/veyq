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
import VeyqBreadcrumb from '../../shared/ui/VeyqBreadcrumb.vue'
import type { VeyqMenuItem } from '../../shared/ui/menu'
import { breadcrumb } from '../../app/breadcrumb'
import { crumbDropTarget } from '../../core/node/nodeDrag'
import InvitationsBadge from '../../features/spaces/InvitationsBadge.vue'
import UploadStatusMenu from '../../core/blob/UploadStatusMenu.vue'

const router = useRouter()
// The upload status is the platform's (cr-files-application B3): one core queue, every application's batches, so the
// bar mounts the menu itself. No application adds anything to the bar (ADR 0079: the shell names no application).

// Every crumb is a real link to its place (`VeyqBreadcrumb` runs the push on a plain click and lets a modified one
// open the link elsewhere). A crumb of the space's tree is also a drop target for nodes dragged from the level (inbox #421,
// `core/node/nodeDrag.ts`): an ancestor takes them where the move dialog would; the drag decides, so nothing lights without one.
const places = new WeakMap<VeyqMenuItem, string | null>()
function crumb(label: string | undefined, hash: string, place?: string | null): VeyqMenuItem {
  const item: VeyqMenuItem = { label, url: router.resolve({ hash }).href, command: () => void router.push({ hash }) }
  if (place !== undefined) places.set(item, place)
  return item
}
const home = computed<VeyqMenuItem>(() => crumb(breadcrumb.trail[0]?.name, breadcrumb.trail[0]?.hash ?? '#~me', breadcrumb.trail[0]?.id))
const items = computed<VeyqMenuItem[]>(() => breadcrumb.trail.slice(1).map((c) => crumb(c.name, c.hash, c.id)))
const dropTarget = crumbDropTarget((item) => places.get(item))
</script>

<template>
  <header class="workspace-topbar">
    <VeyqBreadcrumb :items="items" :home="home" :drop-target="dropTarget" class="app-breadcrumb" />
    <div class="workspace-topbar-actions">
      <InvitationsBadge />
      <UploadStatusMenu />
    </div>
  </header>
</template>
