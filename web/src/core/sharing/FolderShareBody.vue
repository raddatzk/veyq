<script setup lang="ts">
// Core's body of the public share page for a share rooted at a FOLDER (cr-node-key-hierarchy 5b.4b, ADR 0080 Decisions
// 6, 9 and 13; the shell is `views/ShareView.vue`, which pre-registers this body for `FOLDER` in `createRegistry`). The
// shell has resolved the head and run the password and terms gates; this body opens the subtree from it — the root's key
// from the share's `wrap`, every key and name below through the chain (`openGuestTreeParts`) — and browses it.
//
// **What it shows.** A breadcrumb along the `parentId` chain (the root is the share's own name), the share's note ONCE,
// and the current node: a folder's children as cards; any other node EMBEDDED through the body its application claimed
// (`registry.shareBody(kind)`), handed `subtreeRoot` — that node's id, key and name from the chain. A kind nobody claimed,
// or an embedded body that reports `unavailable`, is a local empty state here, never the whole page's unavailable state.
// Core reads no kind but its own `FOLDER_KIND`: what a child IS is the registry's answer.
//
// **Navigation** is the route query `?node=<id>` with the `#k=` fragment KEPT: the shell reads the share key from it on
// every re-resolve, so a push without it leaves the next reload keyless. An id the walk did not open falls back to the root.
//
// **Share sections (cr-files-application F2).** At a folder, a kind that registered a `shareSection` has its children
// rendered by that component after the cards (Files' file list), handed `ShareSectionProps` — the folder, those
// children with the chain's keys and names, whether the link writes, and `requestGuest` (this body's naming flow, so a
// section never asks for a name itself). Its children are not cards. A section renders at every folder, an empty one too.
//
// **Writes (READ_WRITE only).** 'New folder' creates under the current folder as the guest
// (`createShareNodeAsGuest`). The guest names itself once per link, at its first write (`nameShareGuest`, the session
// one per link — an embedded body reuses it); a returning guest's session is loaded. A READ guest sees no write
// control. No move or rename here (inbox #358).
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import { registry } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { ShareRole } from '../../api/generated/sharing'
import { pickLocaleText, type PublicShareHead } from './resolvePublicShare'
import { openGuestTreeParts, guestTreeContext, createShareNodeAsGuest, guestTreeErrorMessageKey, type GuestTreeParts } from './guestTree'
import { loadGuestSession, nameShareGuest, type GuestIdentity } from './guestIdentity'
import { renderMarkdown } from '../../shared/markdown'
import ShareNameDialog from './ShareNameDialog.vue'

// The props are the registry's `ShareBodyProps` (core/applications/registry.ts), spelled out because the SFC compiler
// reads them statically. A folder body is never embedded itself (a folder below the root is browsed here), so it
// takes no `subtreeRoot`.
const props = defineProps<{
  shareId: string
  fragmentKey: string
  pwToken: string | undefined
  head: PublicShareHead
  reload: () => Promise<void>
}>()
const emit = defineEmits<{ unavailable: [] }>()

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

// The opened subtree: keys, names and served rows. A shallow ref, as everything holding key material.
const parts = shallowRef<GuestTreeParts | null>(null)
watch(
  () => props.head,
  (head) => {
    try {
      parts.value = openGuestTreeParts(head)
    } catch {
      emit('unavailable') // a response the fragment key does not open: the shell's honest unavailable state
    }
  },
  { immediate: true },
)

const note = computed(() => pickLocaleText(props.head.noteByLocale, locale.value))
const renderedNote = computed(() => (note.value ? renderMarkdown(note.value) : ''))
const canWrite = computed(() => props.head.role === ShareRole.ReadWrite)

/** The node on screen: `?node=` when the walk opened it, else the root. */
const currentId = computed<string | null>(() => {
  const p = parts.value
  if (!p) return null
  const q = route.query.node
  return typeof q === 'string' && p.keys.has(q) ? q : p.rootId
})
const nameOf = (p: GuestTreeParts, id: string) => (id === p.rootId ? props.head.name : (p.names.get(id) ?? ''))
const currentKind = computed(() => (parts.value && currentId.value ? (parts.value.nodes.get(currentId.value)?.kind ?? null) : null))
const atFolder = computed(() => currentKind.value === FOLDER_KIND)
const currentName = computed(() => (parts.value && currentId.value ? nameOf(parts.value, currentId.value) : ''))

/** Root first, the current node last, along the served `parentId`s (bounded: a served cycle stops at the root). */
const trail = computed<string[]>(() => {
  const p = parts.value
  if (!p || !currentId.value) return []
  const ids: string[] = []
  for (let id: string | null | undefined = currentId.value; id && ids.length <= p.nodes.size; id = id === p.rootId ? null : p.nodes.get(id)?.parentId) {
    ids.unshift(id)
  }
  return ids[0] === p.rootId ? ids : [p.rootId]
})

/** Where node `id` is: `?node=<id>` (no query for the root), the fragment KEPT — it carries the share key the shell
 *  re-resolves with. The one target the cards and the breadcrumb both navigate to. */
function targetOf(id: string) {
  const p = parts.value
  return { query: p && id === p.rootId ? {} : { node: id }, hash: route.hash }
}
function go(id: string) {
  void router.push(targetOf(id))
}
/** A crumb is a real link to its node (a modified click opens it elsewhere, key included) whose plain click the router
 *  handles. PrimeVue renders every crumb as an `<a>` and only CALLS `command`, so the anchor's default action still
 *  runs unless prevented: without a `url` that is `href="#"`, which — measured in the browser, 2026-10-07 — cleared the
 *  `#k=` fragment AND cancelled the push (`?node=` stayed), leaving a reload keyless and every guest write refused. */
function crumb(label: string, id: string): MenuItem {
  return {
    label,
    url: router.resolve(targetOf(id)).href,
    command: ({ originalEvent: e }) => {
      if (e instanceof MouseEvent && (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey)) return // the browser opens the href
      e.preventDefault()
      go(id)
    },
  }
}
const home = computed<MenuItem>(() => crumb(props.head.name, parts.value?.rootId ?? ''))
const crumbs = computed<MenuItem[]>(() => {
  const p = parts.value
  return p ? trail.value.slice(1).map((id) => crumb(nameOf(p, id), id)) : []
})

// The kinds whose children a share section renders (read once per mount, as the level view reads its sections).
const shareSections = registry.shareSections()
const sectionedKinds = new Set(shareSections.flatMap((s) => s.kinds))

/** The current folder's children the walk opened, with a name — every kind. */
const openedChildren = computed(() => {
  const p = parts.value
  if (!p || !currentId.value) return []
  return [...p.nodes.values()].filter((n) => n.parentId === currentId.value && p.names.has(n.id))
})
/** The children of `kinds` (a share section's), as served. */
const childrenOf = (kinds: readonly string[]) => openedChildren.value.filter((n) => kinds.includes(n.kind))

/** The current folder's children shown as cards — every kind no share section renders — by name. */
const children = computed(() => {
  const p = parts.value
  if (!p) return []
  return openedChildren.value
    .filter((n) => !sectionedKinds.has(n.kind))
    .map((n) => {
      const kind = registry.kind(n.kind)
      return { id: n.id, name: nameOf(p, n.id), icon: kind.supported ? kind.icon : 'pi pi-question-circle' }
    })
    .sort((a, b) => a.name.localeCompare(b.name, locale.value))
})

// --- a node that is not a folder: the body its application claimed, embedded --------------------------------------
const embedded = computed(() => (currentKind.value && !atFolder.value ? registry.shareBody(currentKind.value) : null))
const subtreeRoot = computed(() => {
  const p = parts.value
  const id = currentId.value
  const key = p && id ? p.keys.get(id) : undefined
  return p && id && key ? { nodeId: id, key, name: nameOf(p, id) } : null
})
// Embedded bodies that reported their node unusable: a local empty state, never the page's.
const unusable = ref<Set<string>>(new Set())
function markUnusable(id: string) {
  unusable.value = new Set(unusable.value).add(id)
}

// --- writes (READ_WRITE) ---------------------------------------------------------------------------------------------
const session = shallowRef<GuestIdentity | null>(null)
const dialog = ref<'guestName' | 'newFolder' | null>(null)
// What the open name dialog is for: the new folder (the default), or a share section's write waiting for the identity.
let pendingGuest: ((g: GuestIdentity | null) => void) | null = null
function startNewFolder() {
  session.value ??= loadGuestSession(props.shareId) // an embedded body may have named the guest since this mounted
  dialog.value = session.value ? 'newFolder' : 'guestName'
}
/** A share section's write asks for the guest: the link's session, else the visitor names itself first (null: it
 *  closed the dialog without a name). */
function requestGuest(): Promise<GuestIdentity | null> {
  session.value ??= loadGuestSession(props.shareId)
  if (session.value) return Promise.resolve(session.value)
  pendingGuest?.(null)
  dialog.value = 'guestName'
  return new Promise((resolve) => {
    pendingGuest = resolve
  })
}
async function nameGuest(name: string) {
  session.value = await nameShareGuest(props.head, { name, fragmentKey: props.fragmentKey, pwToken: props.pwToken })
}
async function createFolder(name: string) {
  const s = session.value
  const parentId = currentId.value
  if (!s || !parentId) throw new Error('no_guest_session')
  const ctx = guestTreeContext(props.head, { contributorToken: s.contributorToken, fragmentKey: props.fragmentKey, pwToken: props.pwToken })
  await createShareNodeAsGuest(ctx, { parentId, kind: FOLDER_KIND, name })
  await props.reload() // the new folder lists with its name and key
}
function folderError(e: unknown): string {
  const m = guestTreeErrorMessageKey(e)
  return t(m.key, m.params ?? {})
}
/** A dialog closed: once named, the guest goes on to the folder it asked for; otherwise nothing is open. */
function onDialogClose() {
  const waiting = pendingGuest
  pendingGuest = null
  if (waiting) {
    dialog.value = null
    waiting(session.value)
    return
  }
  dialog.value = dialog.value === 'guestName' && session.value ? 'newFolder' : null
}
</script>

<template>
  <p v-if="!parts" class="veyq-empty">
    <span class="pi pi-spin pi-spinner" />
    <span>{{ t('share.folder.loading') }}</span>
  </p>
  <section v-else class="veyq-section">
    <Breadcrumb :home="home" :model="crumbs" class="app-breadcrumb" :aria-label="t('share.folder.breadcrumbRoot')" />
    <!-- The share's note, once for the whole page (an embedded body does not repeat it). Safe markdown (escape-first),
         so v-html is safe. -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="renderedNote" class="veyq-surface markdown-body" v-html="renderedNote"></div>

    <template v-if="atFolder">
      <div class="veyq-section-header">
        <h1>{{ currentName }}</h1>
        <span>{{ canWrite ? t('share.folder.contributorBadge') : t('share.folder.readonly') }}</span>
      </div>
      <div class="veyq-card-grid">
        <p v-if="!openedChildren.length" class="veyq-empty veyq-empty-inline">{{ t('share.folder.empty') }}</p>
        <button
          v-for="child in children"
          :key="child.id"
          class="veyq-card veyq-card-interactive veyq-node-card"
          type="button"
          :data-node-id="child.id"
          @click="go(child.id)"
        >
          <span :class="child.icon" />
          <strong>{{ child.name }}</strong>
        </button>
        <button
          v-if="canWrite"
          class="veyq-card veyq-card-interactive veyq-node-card veyq-node-card-create"
          type="button"
          @click="startNewFolder"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('share.folder.newFolder') }}</strong>
        </button>
      </div>
      <component
        :is="section.component"
        v-for="(section, i) in shareSections"
        :key="i"
        :share-id="shareId"
        :fragment-key="fragmentKey"
        :pw-token="pwToken"
        :head="head"
        :reload="reload"
        :folder-id="currentId!"
        :nodes="childrenOf(section.kinds)"
        :keys="parts.keys"
        :names="parts.names"
        :can-write="canWrite"
        :request-guest="requestGuest"
      />
    </template>
    <p v-else-if="!embedded || !subtreeRoot" class="veyq-empty">{{ t('share.folder.unsupportedKind') }}</p>
    <p v-else-if="currentId && unusable.has(currentId)" class="veyq-empty">{{ t('share.folder.gone') }}</p>
    <component
      :is="embedded"
      v-else
      :key="subtreeRoot.nodeId"
      :share-id="shareId"
      :fragment-key="fragmentKey"
      :pw-token="pwToken"
      :head="head"
      :reload="reload"
      :subtree-root="subtreeRoot"
      @unavailable="markUnusable(subtreeRoot.nodeId)"
    />
  </section>

  <ShareNameDialog
    v-if="dialog === 'guestName'"
    key="guestName"
    :title="t('share.folder.guestNameTitle')"
    :label="t('share.yourName')"
    :submit-label="t('share.join')"
    :hint="t('share.folder.guestNameHint')"
    :action="nameGuest"
    :error-text="() => t('share.joinFailed')"
    @close="onDialogClose"
  />
  <ShareNameDialog
    v-else-if="dialog === 'newFolder'"
    key="newFolder"
    :title="t('share.folder.newFolderTitle')"
    :label="t('share.folder.nameLabel')"
    :submit-label="t('common.create')"
    :action="createFolder"
    :error-text="folderError"
    @close="onDialogClose"
  />
</template>
