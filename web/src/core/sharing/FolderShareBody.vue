<script setup lang="ts">
// Core's body of the public share page for a share rooted at a FOLDER (cr-node-key-hierarchy 5b.4b, ADR 0080 Decisions
// 6, 9 and 13; the shell is `views/ShareView.vue`, which pre-registers this body for `FOLDER` in `createRegistry`). The
// shell has resolved the head, run the password and terms gates and built the guest's `access` (its role, the page's one
// naming flow); this body builds the guest's TREE from the head (`guestTreeSource`: the root's key from the share's
// `wrap`, every key and name below through the chain) and browses it.
//
// **The member's page, with functions removed (inbox #401).** At a folder it renders core's `LevelView` — the very
// view a member browses with: the folder's overview card, every application's level section through the registry
// (Memories' cards with their covers and dates, Files' files and drop boxes), the fallback section for a kind no section
// serves, the sub-folders — handed the guest's `access` and `tree`, so every control is the capability functions' answer
// for a guest (`folderCapabilities`, `memoryCapabilities`, `filesCapabilities`) and the sections' data sources. At any
// other node it renders that kind's `detail` (`NodeDetailProps`, the guest's access and tree; Files' drop box since S3),
// as the member's host does. What stays this body's own is what only a guest's page has: the breadcrumb up to the share
// root, the share's note (once), the role badge.
//
// **Until S5 (Memories)** a kind whose application claimed its share body `embedBelowFolder` (a Memory, a chapter) is
// EMBEDDED through that body (`registry.embeddedShareBody(kind)`) instead of its detail, handed `subtreeRoot` — its id,
// key and name from the chain. A kind with neither, or an embedded body that reports `unavailable`, is a local empty
// state here, never the page's.
//
// **Navigation** is the tree's `linkTo`: `?node=<id>` with the `#k=` fragment KEPT (the shell re-reads the share key
// from it on every re-resolve), and every breadcrumb a real link (web/CLAUDE.md, the breadcrumb trap). An id the walk
// did not open falls back to the root.
//
// **Writes (READ_WRITE only)** are what the capabilities grant at the folder on screen: a new folder, or a node of a
// section's kind (a Memory), created as the guest under the current folder (`createShareNodeAsGuest`) after the shell
// named it (`access.requestGuest`). Inside a guest's identity folder nothing is placed but by its own identity, so no
// create is offered there (the server refuses it: `illegal_structure`), nor any section's write (the capabilities).
import { computed, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
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

// The props are the registry's `ShareBodyProps` (core/applications/registry.ts), spelled out because the SFC compiler
// reads them statically. A folder body is never embedded itself (a folder below the root is browsed here), so it
// takes no `subtreeRoot`.
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

/** The link writes, and not here: nobody but its identity places anything in a guest's identity folder. */
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
 *  handles. PrimeVue renders every crumb as an `<a>` and only CALLS `command`, so the anchor's default action still
 *  runs unless prevented: without a `url` that is `href="#"`, which — measured in the browser, 2026-10-07 — cleared the
 *  `#k=` fragment AND cancelled the push (`?node=` stayed), leaving a reload keyless and every guest write refused. */
function crumb(tr: GuestTreeSource, id: string): MenuItem {
  return {
    label: tr.nameOf(id),
    url: router.resolve(tr.linkTo(id)).href,
    command: ({ originalEvent: e }) => {
      if (e instanceof MouseEvent && (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey)) return // the browser opens the href
      e.preventDefault()
      go(id)
    },
  }
}
const home = computed<MenuItem>(() => (tree.value ? crumb(tree.value, tree.value.rootId) : { label: props.head.name }))
const crumbs = computed<MenuItem[]>(() => {
  const tr = tree.value
  return tr ? trail.value.slice(1).map((id) => crumb(tr, id)) : []
})

// --- a node that is not a folder: its kind's detail, or (until S5) the body its application claimed, embedded --------
const embedded = computed(() => (current.value && !atFolder.value ? registry.embeddedShareBody(current.value.kind) : null))
const detail = computed(() => {
  if (!current.value || atFolder.value || embedded.value) return null
  const info = registry.kind(current.value.kind)
  return info.supported ? (info.detail ?? null) : null
})
const subtreeRoot = computed(() => {
  const tr = tree.value
  const id = currentId.value
  const key = tr && id ? tr.keyOf(id) : null
  return tr && id && key ? { nodeId: id, key, name: tr.nameOf(id) } : null
})
// Embedded bodies that reported their node unusable: a local empty state, never the page's.
const unusable = ref<Set<string>>(new Set())
function markUnusable(id: string) {
  unusable.value = new Set(unusable.value).add(id)
}

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
function createError(e: unknown): string {
  const m = guestTreeErrorMessageKey(e)
  return t(m.key, m.params ?? {})
}
</script>

<template>
  <p v-if="!tree" class="veyq-empty">
    <span class="pi pi-spin pi-spinner" />
    <span>{{ t('share.folder.loading') }}</span>
  </p>
  <section v-else class="veyq-section">
    <div class="share-folder-bar">
      <Breadcrumb :home="home" :model="crumbs" class="app-breadcrumb" :aria-label="t('share.folder.breadcrumbRoot')" />
      <span v-if="atFolder" class="share-folder-badge">{{ canWriteHere ? t('share.folder.contributorBadge') : t('share.folder.readonly') }}</span>
    </div>
    <!-- The share's note, once for the whole page (an embedded body does not repeat it). Safe markdown (escape-first),
         so v-html is safe. -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="renderedNote" class="veyq-surface markdown-body" v-html="renderedNote"></div>

    <LevelView
      v-if="atFolder && current"
      :folder="current"
      :level="level"
      :access="access"
      :tree="tree"
      :folder-name="tree.nameOf(current.id)"
      @open-node="go($event.id)"
      @create="startCreate"
    />
    <!-- The member's detail of the node's kind, for a guest: what it offers is its capabilities and its data source. -->
    <component
      :is="detail"
      v-else-if="detail && current"
      :key="current.id"
      :node="current"
      :scope-id="head.scopeId"
      :epochs="null"
      :content-key="tree.keyOf(current.id)"
      :children="level"
      :access="access"
      :tree="tree"
      @open-node="go($event.id)"
    />
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
      :access="access"
      :subtree-root="subtreeRoot"
      @unavailable="markUnusable(subtreeRoot.nodeId)"
    />
  </section>

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
