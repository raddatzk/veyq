<script setup lang="ts">
// Core's page of a `READ` or `READ_WRITE` public share, whatever its root kind (a folder, a Memory, a chapter, …;
// uc.sharing.share-public-viewer:guest-sees-the-member-view; the shell is `views/ShareView.vue`, which mounts this page
// behind its gates and a `WRITE_ONLY` link's drop page instead). The shell has resolved the head, run the password and
// terms gates and built the guest's `access` (its role, the page's one naming flow); this page builds the guest's TREE
// from the head (`guestTreeSource`: the root's key from the share's `wrap`, every key and name below through the chain)
// and browses it.
//
// **The member's page, with functions removed (inbox #401).** At a folder it renders core's `LevelView` — the very view
// a member browses with: the folder's header and ONE list of the folders and every application's rows through the
// registry (Memories' rows with their covers and dates, Depot's files and drop boxes), an unsupported row for a kind nobody
// lists (inbox #407). At any other node — the link's root included — it renders that kind's registered `detail`
// (`NodeDetailProps`: Memories' `MemoryDetail`, Depot's `DropBoxDetail`), as the member's host does. Both are handed the
// guest's `access` and `tree`, so every control is the rights functions' answer for a guest (`folderRights`,
// `memoryRights`, `depotRights`) and every byte the applications' guest data sources'. What stays this
// page's own is what only a guest's page has: the breadcrumb up to the share root, the share's note (once), the role
// badge. A kind without a detail is a local empty state here, never the page's.
//
// **Navigation** is the tree's `linkTo`: `?node=<id>` with the `#k=` fragment KEPT (the shell re-reads the share key
// from it on every re-resolve), and every breadcrumb a real link (src/web/CLAUDE.md, the breadcrumb trap). An id the walk
// did not open falls back to the root.
//
// **Writes (READ_WRITE only)** are what the rights grant on screen: at a folder a new folder, created as the guest
// under the current folder (`createShareNodeAsGuest`) after the shell named it (`access.requestGuest`), and what an
// application's own level action offers (Depot: an upload, whose files it creates itself). A guest creates no Memory, no
// drop box and no other application's node (owner decision 2026-10-14: the server admits a folder and the kinds their
// application declares guest-creatable, `GuestCreatableKinds`), so no such "New" entry is shown - hidden, not greyed; a
// section kind whose entry did allow it would be created through this page's name dialog the same way. Inside a detail
// what its application offers (Memories: an upload into the Memory or
// chapter on screen, the delete of the guest's own media). Inside a guest's identity folder nothing is placed but by its
// own identity, so no create is offered there (the server refuses it: `illegal_structure`), nor any section's write.
// A MOVE (inbox #358) is core's `MoveNodeDialog` through the guest's door (`nodeMover`: `placeShareNodeAsGuest` after the
// naming flow), offered where the rights functions grant it — a node a `FOLDER` of the subtree holds, never the
// link's root, never in or of an identity folder — and its destinations are the subtree's folders only; the level's
// selection of several (inbox #421) moves through core's `MoveNodesDialog` by the same door, and a guest is offered no
// delete of a selection (it deletes no node). A `READ` link has no door, so nothing it renders can move anything. A DOWNLOAD (ADR 0082 decision 5) is core's `ExportSubtreeDialog` over the
// guest's tree and the applications' guest reads, for a folder, a Memory or a chapter of a `READ` / `READ_WRITE` link.
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqBreadcrumb from '../../shared/ui/VeyqBreadcrumb.vue'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import type { VeyqMenuItem } from '../../shared/ui/menu'
import { registry } from '../applications/registry'
import type { GuestAccess } from '../access'
import { FOLDER_KIND, type NodeKind } from '../node/kind'
import { childrenOf, type Level } from '../node/browse'
import LevelView from '../node/LevelView.vue'
import { ShareRole } from '../../api/generated/sharing'
import { pickLocaleText, type PublicShareHead } from './resolvePublicShare'
import { guestTreeContext, createShareNodeAsGuest, guestTreeErrorMessageKey } from './guestTree'
import { guestTreeSource, type GuestTreeSource } from './guestAccess'
import { useGuestSession } from './guestIdentity'
import { renderMarkdown } from '../../shared/markdown'
import ShareNameDialog from './ShareNameDialog.vue'
import MoveNodeDialog from '../node/MoveNodeDialog.vue'
import MoveNodesDialog from '../node/MoveNodesDialog.vue'
import ExportSubtreeDialog from '../export/ExportSubtreeDialog.vue'
import { nodeMover } from '../node/move'
import { useLevelLive } from '../node/useLevelLive'
import { sameKeyOr, type TreeNode } from '../node/tree'
import StatementNotice from '../../features/moderation/statement/StatementNotice.vue'
import OrderNotice from './OrderNotice.vue'

// The props are the shell's, the registry's `ShareBodyProps` (core/applications/registry.ts), spelled out because the
// SFC compiler reads them statically.
const props = defineProps<{
  shareId: string
  fragmentKey: string
  pwToken: string | undefined
  head: PublicShareHead
  reload: () => Promise<void>
  access: GuestAccess
}>()
const emit = defineEmits<{ unavailable: [] }>()

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

// The guest's tree over the opened subtree. A shallow ref, as everything holding key material.
const tree = shallowRef<GuestTreeSource | null>(null)
watch(
  () => props.head,
  (head) => {
    try {
      tree.value = guestTreeSource(head, route)
    } catch {
      emit('unavailable') // a response the fragment key does not open: the shell's honest unavailable state
    }
  },
  { immediate: true },
)

const note = computed(() => pickLocaleText(props.head.noteByLocale, locale.value))
const renderedNote = computed(() => (note.value ? renderMarkdown(note.value) : ''))

/** The node on screen: `?node=` when the walk opened it, else the root. */
const currentId = computed<string | null>(() => {
  const tr = tree.value
  if (!tr) return null
  const q = route.query.node
  return typeof q === 'string' && tr.node(q) ? q : tr.rootId
})
const current = computed(() => (tree.value && currentId.value ? tree.value.node(currentId.value) : null))
const atFolder = computed(() => current.value?.kind === FOLDER_KIND)
const NO_LEVEL: Level = { folders: [], byKind: new Map() }
const level = computed<Level>(() => (tree.value && currentId.value ? childrenOf([...tree.value.nodes], currentId.value) : NO_LEVEL))

/** The link writes, and not here: nobody but its identity places anything in a guest's identity folder. The badge says
 *  so too, so it reads the same everywhere a guest can write. */
const canWriteHere = computed(() => props.head.role === ShareRole.ReadWrite && !(tree.value?.inIdentityFolder(currentId.value) ?? false))

/** Root first, the current node last, along the served `parentId`s (bounded: a served cycle stops at the root). */
const trail = computed<string[]>(() => {
  const tr = tree.value
  if (!tr || !currentId.value) return []
  const ids: string[] = []
  for (let n = tr.node(currentId.value); n && ids.length <= tr.nodes.length; n = n.parentId ? tr.node(n.parentId) : null) ids.unshift(n.id)
  return ids[0] === tr.rootId ? ids : [tr.rootId]
})

function go(id: string) {
  if (tree.value) void router.push(tree.value.linkTo(id))
}
/** A crumb is a real link to its node (a modified click opens it elsewhere, key included) whose plain click the router
 *  handles. `VeyqBreadcrumb` renders every crumb as an `<a>` and only CALLS `command`, so the anchor's default action still
 *  runs unless prevented: without a `url` that is `href="#"`, which — measured in the browser, 2026-10-07 — cleared the
 *  `#k=` fragment AND cancelled the push (`?node=` stayed), leaving a reload keyless and every guest write refused. */
function crumb(tr: GuestTreeSource, id: string): VeyqMenuItem {
  return {
    label: tr.nameOf(id),
    url: router.resolve(tr.linkTo(id)).href,
    command: (e) => {
      if (e instanceof MouseEvent && (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey)) return // the browser opens the href
      e.preventDefault()
      go(id)
    },
  }
}
const home = computed<VeyqMenuItem>(() => (tree.value ? crumb(tree.value, tree.value.rootId) : { label: props.head.name }))
const crumbs = computed<VeyqMenuItem[]>(() => {
  const tr = tree.value
  return tr ? trail.value.slice(1).map((id) => crumb(tr, id)) : []
})

// --- a node that is not a folder: its kind's detail ------------------------------------------------------------------
const detail = computed(() => {
  if (!current.value || atFolder.value) return null
  const info = registry.kind(current.value.kind)
  return info.supported ? (info.detail ?? null) : null
})
// Its key, the same object while it is the same key (`sameKeyOr`): a live poll that swaps `head` re-opens the subtree, and
// a Memory's gallery reloads on a new key.
const currentKey = computed<Uint8Array | null>((previous) =>
  sameKeyOr(previous, tree.value && current.value ? tree.value.keyOf(current.value.id) : null),
)
/** The role badge: what this link lets the guest do here — at a folder in the folder's words (add, rename, move), at any
 *  other node the role's. */
const badge = computed(() => {
  if (!canWriteHere.value) return t('share.readonly')
  return atFolder.value ? t('share.folder.contributorBadge') : t('share.contributorBadge')
})

// --- writes (READ_WRITE): a node created under the current folder --------------------------------------------------
const session = useGuestSession(props.shareId)
/** The kind the open name dialog creates; null: none open. */
const creating = ref<NodeKind | null>(null)
async function startCreate(kind: NodeKind) {
  if (await props.access.requestGuest()) creating.value = kind // named (now or before): name the new node
}
async function createNode(name: string) {
  const s = session.value
  const parentId = currentId.value
  const kind = creating.value
  if (!s || !parentId || !kind) throw new Error('no_guest_session')
  const ctx = guestTreeContext(props.head, { contributorToken: s.contributorToken, fragmentKey: props.fragmentKey, pwToken: props.pwToken })
  await createShareNodeAsGuest(ctx, { parentId, kind, name })
  await props.reload() // the new node lists with its name and key
}
// --- a move (READ_WRITE): core's dialog, the guest's door ----------------------------------------------------------
const mover = nodeMover(() => props.access)
const moving = ref<TreeNode | null>(null)
const movingMany = ref<TreeNode[] | null>(null)
// --- a download as a ZIP (READ / READ_WRITE, ADR 0082 decision 5): core's dialog over the guest's own tree and reads ------
const downloading = ref<TreeNode | null>(null)

// --- the live refresh (inbox #408): a page of the link's tree on screen picks up what others changed, without a reload ---
// A folder level and an opened node's page of any kind, as the member's host does (inbox #503): a moderator's block of the
// node or of a folder above it reaches the page through it (the re-resolve serves the row marked, and its tag is a digest
// of what it serves, ADR 0081 decision 4), and so does its lift. Paused while a dialog of this page is open; the shell's
// `poll` swaps `head` (and with it the tree) only on a change.
const { ticks: liveTick } = useLevelLive({
  active: () => !!current.value && !!props.access.poll,
  paused: () => !!creating.value || !!moving.value || !!movingMany.value || !!downloading.value,
  read: (signal) => props.access.poll?.(signal) ?? Promise.resolve(false),
})

function createError(e: unknown): string {
  const m = guestTreeErrorMessageKey(e)
  return t(m.key, m.params ?? {})
}
</script>

<template>
  <p v-if="!tree" class="veyq-empty">
    <VeyqIcon name="spinner" spin />
    <span>{{ t('share.folder.loading') }}</span>
  </p>
  <section v-else class="veyq-section">
    <div class="share-folder-bar">
      <VeyqBreadcrumb :home="home" :items="crumbs" class="app-breadcrumb" :aria-label="t('share.folder.breadcrumbRoot')" />
      <span class="share-folder-badge">{{ badge }}</span>
    </div>
    <!-- The share's note (the curator's), once for the whole page. Safe markdown (escape-first), so v-html is safe. -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="renderedNote" class="veyq-surface markdown-body" v-html="renderedNote"></div>

    <!-- uc.moderation.take-a-measure: a node a moderator blocked does not open — a guest sees what a member sees; a member
         the statement of reasons names reads it below (uc.moderation.object-to-a-measure). -->
    <template v-if="current?.blockedByOperator">
      <p class="veyq-empty node-blocked">{{ t('node.blockedByOperator') }}</p>
      <StatementNotice :target-id="current.id" />
      <!-- A guest whose upload an authority's removal order blocked through another link learns it here (TCO Art. 11). -->
      <OrderNotice :share-id="shareId" />
    </template>
    <LevelView
      v-else-if="atFolder && current"
      :folder="current"
      :level="level"
      :access="access"
      :tree="tree"
      :folder-name="tree.nameOf(current.id)"
      :live-tick="liveTick"
      @open-node="go($event.id)"
      @create="startCreate"
      @move-node="moving = $event"
      @move-nodes="movingMany = $event"
      @download-node="downloading = $event"
    />
    <!-- The member's detail of the node's kind, for a guest: what it offers is its rights and its data source. -->
    <component
      :is="detail"
      v-else-if="detail && current"
      :key="current.id"
      :node="current"
      :scope-id="head.scopeId"
      :epochs="null"
      :content-key="currentKey"
      :children="level"
      :access="access"
      :tree="tree"
      @open-node="go($event.id)"
      @move="moving = $event"
      @download="downloading = current"
    />
    <p v-else class="veyq-empty">{{ t('share.folder.unsupportedKind') }}</p>
  </section>

  <MoveNodeDialog
    v-if="moving && mover && tree"
    :node="moving"
    :access="access"
    :tree="tree"
    :mover="mover"
    :node-name="tree.nameOf(moving.id)"
    @close="moving = null"
  />
  <MoveNodesDialog v-if="movingMany && mover && tree" :nodes="movingMany" :access="access" :tree="tree" :mover="mover" @close="movingMany = null" />
  <ExportSubtreeDialog
    v-if="downloading && tree"
    :node="downloading"
    :access="access"
    :tree="tree"
    :display-name="tree.nameOf(downloading.id)"
    @close="downloading = null"
  />
  <ShareNameDialog
    v-if="creating"
    :title="creating === FOLDER_KIND ? t('share.folder.newFolderTitle') : t('common.create')"
    :label="t('share.folder.nameLabel')"
    :submit-label="t('common.create')"
    :action="createNode"
    :error-text="createError"
    @close="creating = null"
  />
</template>

<style scoped>
/* The guest's position and role, one row: the breadcrumb up to the share root, the badge beside it (wrapping under it
   on a narrow screen). */
.share-folder-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem 1rem;
}
.share-folder-badge {
  color: var(--app-muted);
  font-size: 0.85rem;
}
</style>
