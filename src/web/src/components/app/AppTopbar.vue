<script setup lang="ts">
// The shell's top bar (cr-0030). Markup + classes are the design source's WorkspaceTopbar (the
// .workspace-topbar grid) so layout.css styles it identically. Ours differs in the data — the breadcrumb is
// built CLIENT-SIDE from the local decrypted tree (the blind server computes none).
//
// **The search is its centre** (uc.platform.search, ADR 0125): core's field over every space, the grid's middle column
// (layout.css). At a phone's width (≤ 720px, where the breadcrumb needs the row) it is an icon button among the actions that
// opens the same field in a sheet. `/` and Ctrl/⌘+K reach it from anywhere but a field being typed in. This bar is the
// member's shell only (`AppShell` draws it for the `app` layout): a share page has no search (ADR 0125 decision 7).
//
// No preferences and no settings door live here (ADR 0022 §5) — theme, language and the shell's one entry into
// the hub are all in the sidebar's ProfileMenu. What is left is what is genuinely contextual: the breadcrumb,
// the invitations badge and the upload tray. That matters below the compact threshold, where layout.css gives
// this bar 3rem (≤1020px) and then auto height around a 2.35rem breadcrumb (≤720px): the actions sit in the
// same row as the breadcrumb, so each one is width the crumbs no longer have.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useTemplateRef } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import SearchField from '../../core/search/SearchField.vue'
import VeyqBreadcrumb from '../../shared/ui/VeyqBreadcrumb.vue'
import type { VeyqMenuItem } from '../../shared/ui/menu'
import { breadcrumb } from '../../app/breadcrumb'
import { crumbDropTarget } from '../../core/node/nodeDrag'
import InvitationsBadge from '../../features/spaces/InvitationsBadge.vue'
import UploadStatusMenu from '../../core/blob/UploadStatusMenu.vue'
import { typingInto } from '../../core/shortcuts/shortcuts'

const router = useRouter()
const { t } = useI18n()

// --- the search: inline from a tablet's width up, a sheet below it -------------------------------------------------------
/** The width at and below which the field is a button opening a sheet (layout.css' phone breakpoint). */
const SHEET_QUERY = '(max-width: 720px)'
const inline = useTemplateRef<{ focus(): void }>('inline')
const sheet = useTemplateRef<{ focus(): void }>('sheet')
const sheetOpen = ref(false)
async function openSheet() {
  sheetOpen.value = true
  await nextTick()
  sheet.value?.focus()
}
/** `/` or Ctrl/⌘+K: to the search - never while a field, a text area or an editable region has the keys. Listed in the
 *  shortcut overview: `core/shortcuts/shortcuts.ts`, `CORE_SHORTCUTS` *Everywhere*. */
function onShortcut(event: KeyboardEvent) {
  if (event.defaultPrevented || event.isComposing || event.altKey) return
  const slash = event.key === '/' && !event.ctrlKey && !event.metaKey
  const k = event.key.toLowerCase() === 'k' && (event.ctrlKey || event.metaKey) && !event.shiftKey
  if (!slash && !k) return
  if (typingInto(event.target)) return
  event.preventDefault()
  if (window.matchMedia?.(SHEET_QUERY).matches) void openSheet()
  else inline.value?.focus()
}
onMounted(() => window.addEventListener('keydown', onShortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', onShortcut))
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
    <SearchField ref="inline" class="workspace-search" data-testid="search-field" />
    <div class="workspace-topbar-actions">
      <VeyqButton
        class="veyq-icon-button workspace-search-open"
        icon="search"
        variant="secondary"
        appearance="text"
        :aria-label="t('shell.search.open')"
        :title="t('shell.search.open')"
        data-testid="search-open"
        @click="openSheet"
      />
      <InvitationsBadge />
      <UploadStatusMenu />
    </div>
    <VeyqDialog v-model:visible="sheetOpen" :header="t('shell.search.title')" position="bottom" class="workspace-search-sheet">
      <SearchField ref="sheet" data-testid="search-sheet-field" @opened="sheetOpen = false" />
    </VeyqDialog>
  </header>
</template>
