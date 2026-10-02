<script setup lang="ts">
// The personal-space content host (cr-0030/0031, uc.memory.browse). Mounted at `/`; the current tree position
// lives in the URL **#fragment** (`#~me/reisen-2026/rom`), resolved against the locally-decrypted tree — the
// operator-blind server computes no navigation and never sees the path. Renders a folder/root level (browse)
// or a Memory leaf (detail) by the resolved node's kind, and owns the create / rename / delete dialogs.
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, activeEpochKeys, enterScope } from '../app/activeSpace'
import { suspensionSignals } from '../app/tenantSuspension'
import { keyAt } from '../features/spaces/epochs'
import { setBreadcrumb } from '../app/breadcrumb'
import { isFavorite, toggleFavorite, loadFavoritesFor, favoritesStore } from '../app/favoritesStore'
import { treeStore, loadTree, patchNodeMeta } from '../features/memory/treeStore'
import { childrenOf, breadcrumbOf, nodeById } from '../features/memory/browse'
import { nodeContentKey } from '../features/memory/tree'
import { parseHash, resolveSlugPath, hashForNode, TENANT_SCOPE } from '../features/memory/location'
import { listMyTenants } from '../features/spaces/scopes'
import { NodeKind } from '../api/generated/content'
import { ScopeKind, type MyTenant } from '../api/generated/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { SHARING_PUBLIC_LINKS } from '../features/platform/capabilities'
import type { TreeNode } from '../features/memory/tree'
import Message from 'primevue/message'
import MemoryBrowse from '../features/memory/components/MemoryBrowse.vue'
import MemoryDetail from '../features/memory/components/MemoryDetail.vue'
import NodeMutationDialog from '../features/memory/components/NodeMutationDialog.vue'
import DeleteNodeDialog from '../features/memory/components/DeleteNodeDialog.vue'
import ShareDialog from '../features/memory/components/ShareDialog.vue'
import SpaceTrashDialog from '../features/memory/components/SpaceTrashDialog.vue'
import TrustResetLink from '../features/account/trustReset/TrustResetLink.vue'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const { hasCapability } = useCapabilities()

// Pending-deletion (cr-0090): the active GROUP is being deleted — the shell shows a banner and the browse hides
// its create affordances (every write is server-rejected during the grace window anyway, uc.spaces.scope-access).
const isPendingDeletion = computed(() => activeSpace.deletedAt !== null)
const pendingPurgeDay = computed(() =>
  activeSpace.purgeAfter ? new Date(activeSpace.purgeAfter).toLocaleDateString(locale.value) : '',
)

// Position from the #fragment. The leading token selects the scope (token-less = tenant, `~me` = personal —
// ADR 0019); a slug segment that doesn't resolve (`false`) collapses to the scope root.
const currentScope = computed(() => parseHash(route.hash).scope)
const segments = computed(() => parseHash(route.hash).segments)
const resolved = computed(() => resolveSlugPath(treeStore.nodes, segments.value))
const currentNodeId = computed(() => (typeof resolved.value === 'string' ? resolved.value : null))
const currentNode = computed(() => nodeById(treeStore.nodes, currentNodeId.value))
// A MEMORY or a CHAPTER opens to the gallery (a chapter is itself a Memory, cr-0060); folders/root browse.
const isOpenable = computed(
  () => currentNode.value?.kind === NodeKind.Memory || currentNode.value?.kind === NodeKind.Chapter,
)
const currentFolder = computed(() =>
  currentNode.value && currentNode.value.kind === NodeKind.Folder ? currentNode.value : null,
)
const level = computed(() => childrenOf(treeStore.nodes, currentNodeId.value))
// The full **epoch key set** (ADR 0024) — a read opens each node under its own epoch, new content seals under the
// write epoch. Threaded through the browse + mutation children in place of a single scope key. `activeEpochKeys()`
// isn't reactive, so depend on BOTH `status` (keys set on scope entry) and `epoch` (keys swapped on a rotation
// without a status flip, `refreshActiveScope`) — else a post-rotation create would seal under the stale/zeroed key.
const epochs = computed(() => (activeSpace.status === 'ready' && activeSpace.epoch >= 0 ? activeEpochKeys() : null))
// The open node's K_mem, resolved by walking the content-key chain (the top-level Memory opens under its own
// epoch's K_SC, a chapter under its parent Memory's K_mem, recursive K_mem; held on device — the server is blind).
const memoryKey = computed(() =>
  isOpenable.value && currentNodeId.value && epochs.value
    ? nodeContentKey(activeSpace.scopeId!, epochs.value, treeStore.nodes, currentNodeId.value)
    : null,
)

// Public-share affordance: offered for any **MEMORY** in a scope the caller is a member of — personal, group, or
// tenant (cr-0104; a chapter or folder is out of scope, the backend rejects a non-MEMORY node). Also gated on the
// `sharing.public-links` capability (ADR 0032): an operator can turn public sharing off instance-wide, in which
// case the affordance is hidden entirely (and the server rejects createShare with 422).
const shareable = computed(
  () => hasCapability(SHARING_PUBLIC_LINKS) && currentNode.value?.kind === NodeKind.Memory,
)
const shareVisible = ref(false)
const uploaderVisible = ref(false) // the uploader (drop-box) share dialog — a separate memory action

type Mode = 'create-folder' | 'create-memory' | 'create-chapter' | 'rename'
const mutationVisible = ref(false)
const mutationMode = ref<Mode>('create-folder')
const mutationNode = ref<TreeNode | null>(null)
const deleteVisible = ref(false)
const trashVisible = ref(false) // the SPACE's node trash (uc.memory.delete-node) — not the per-Memory media trash
const deleteTarget = ref<TreeNode | null>(null)

// The breadcrumb root label reflects the **active** scope (the tenant's name vs. Personal), not the URL token —
// a no-grant member sees `Personal` at the token-less `/` because the tenant home fell back to Personal.
const tenants = ref<MyTenant[]>([])
async function loadTenants() {
  try {
    tenants.value = await listMyTenants()
  } catch {
    /* the label tolerates an unknown tenant name */
  }
}
onMounted(loadTenants)
watch(() => suspensionSignals.tenantList, () => void loadTenants())
const activeTenantName = computed(
  () => tenants.value.find((tn) => tn.tenantId === activeSpace.tenantId)?.name ?? '',
)
// A GROUP scope's root label is its **decrypted** name (en.spaces.group-scope, cr-0032) — resolved once at scope
// entry and held on `activeSpace` (shared with the settings hub); `Gruppen` shows if a name won't decrypt.
const rootLabel = computed(() =>
  activeSpace.kind === ScopeKind.Tenant
    ? activeTenantName.value || t('shell.shared')
    : activeSpace.kind === ScopeKind.Group
      ? activeSpace.name || t('shell.groups')
      : t('shell.personal'),
)

watch(
  [() => route.hash, () => treeStore.nodes, rootLabel],
  () => setBreadcrumb(breadcrumbOf(treeStore.nodes, currentNodeId.value, rootLabel.value, currentScope.value)),
  { immediate: true },
)

// Re-enter the scope when the leading fragment token flips (segment-only moves keep the same token → no
// re-entry). Entering opens the scope key + resets the tree/favorites (activeSpace.enterScope); we then reload
// both for the newly active scope.
async function reenter(scope: string) {
  await enterScope(scope)
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  if (activeSpace.status === 'ready' && activeSpace.scopeId && !favoritesStore.loaded) {
    // No epoch key here any more: the pin set seals under the member's MASTER key (ADR 0068), which does not
    // rotate with the scope, so the whole base-epoch dance left with it. It still applies to item marks below.
    await loadFavoritesFor(activeSpace.scopeId)
  }
}
watch(currentScope, (scope) => void reenter(scope), { immediate: true })
// A space in another, suspended tenant (uc.spaces.scope-access A7): leave it for this domain's own home. (The home
// itself belongs to this domain's tenant, so it is never the one to leave.)
watch(
  () => suspensionSignals.leaveHome,
  () => {
    if (currentScope.value !== TENANT_SCOPE) void router.replace({ name: 'home', hash: '' })
  },
)

// Item marks seal under `K_SC` at the **base epoch `E_0`** (ADR 0068), never under `activeScopeKey()`, which is
// the *write* key `E_current`: the marked set is re-sealed whole on every toggle, so a rotation would strand it.
// `activeSpace.epoch` is the reactive signal that a rotation happened — without reading it this would not
// recompute after `refreshActiveScope`.
const itemMarkKey = computed<Uint8Array | null>(() => {
  void activeSpace.epoch
  const eks = activeEpochKeys()
  return eks ? keyAt(eks, 0) : null
})

function onToggleFavorite(node: TreeNode) {
  if (activeSpace.scopeId) void toggleFavorite(node.id, activeSpace.scopeId)
}

function openFolder(folder: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, folder.id, currentScope.value) })
}
function openMemory(memory: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, memory.id, currentScope.value) })
}
function openChapter(chapter: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, chapter.id, currentScope.value) })
}
function startCreate(mode: 'create-folder' | 'create-memory' | 'create-chapter') {
  mutationMode.value = mode
  mutationNode.value = null
  mutationVisible.value = true
}
function startRename(node: TreeNode) {
  mutationMode.value = 'rename'
  mutationNode.value = node
  mutationVisible.value = true
}
function startDelete(node: TreeNode) {
  deleteTarget.value = node
  deleteVisible.value = true
}
async function onMutationDone() {
  await loadTree()
}
/** A restore puts a whole subtree back, so the tree is refetched exactly as a delete refetches it — no local
 *  splice. The position stays where it is: the restored node reappears wherever it belonged. */
async function onTrashRestored() {
  await loadTree()
}
async function onDeleteDone() {
  const parentId = deleteTarget.value?.parentId ?? null
  await loadTree()
  void router.push({ hash: hashForNode(treeStore.nodes, parentId, currentScope.value) })
}
</script>

<template>
  <div class="memory-home">
    <p v-if="activeSpace.status === 'loading' || activeSpace.status === 'idle'" class="home-loading">
      {{ t('home.loading') }}
    </p>
    <div v-else-if="activeSpace.status === 'no-tenant'" class="home-state">
      <h2>{{ t('home.noTenant') }}</h2>
      <p>{{ t('home.noTenantHint') }}</p>
    </div>
    <div v-else-if="activeSpace.status === 'error' || treeStore.error" class="home-state">
      <p>{{ t('home.error') }}</p>
      <p v-if="activeSpace.trustRefusal">{{ t(activeSpace.trustRefusal) }}</p>
      <TrustResetLink v-if="activeSpace.trustRefusal" />
    </div>
    <template v-else>
      <Message v-if="isPendingDeletion" severity="warn" :closable="false" class="pending-delete-banner">
        {{ t('groups.pendingDeletion', { day: pendingPurgeDay }) }}
      </Message>
      <MemoryDetail
        v-if="isOpenable && currentNode && activeSpace.scopeId"
        :node="currentNode"
        :scope-id="activeSpace.scopeId"
        :memory-key="memoryKey"
        :scope-key="itemMarkKey"
        :personal-scope="activeSpace.kind === 'PERSONAL'"
        :favorite="isFavorite(currentNode.id)"
        :chapters="level.chapters"
        :shareable="shareable"
        @toggle-favorite="currentNode && onToggleFavorite(currentNode)"
        @rename="currentNode && startRename(currentNode)"
        @delete="currentNode && startDelete(currentNode)"
        @create-chapter="startCreate('create-chapter')"
        @open-chapter="openChapter"
        @share="shareVisible = true"
        @share-upload="uploaderVisible = true"
        @meta-updated="patchNodeMeta($event.nodeId, $event.wrappedMeta, $event.metaVersion)"
      />
      <MemoryBrowse
        v-else
        :folder="currentFolder"
        :folders="level.folders"
        :memories="level.memories"
        :epochs="epochs"
        :readonly="isPendingDeletion"
        @open-folder="openFolder"
        @open-memory="openMemory"
        @toggle-favorite="onToggleFavorite"
        @create-folder="startCreate('create-folder')"
        @create-memory="startCreate('create-memory')"
        @rename-folder="currentFolder && startRename(currentFolder)"
        @delete-folder="currentFolder && startDelete(currentFolder)"
        @open-trash="trashVisible = true"
      />
    </template>

    <NodeMutationDialog
      v-if="mutationVisible && epochs && activeSpace.scopeId"
      :mode="mutationMode"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :parent-id="currentNodeId"
      :parent-key="memoryKey"
      :node="mutationNode"
      @done="onMutationDone"
      @close="mutationVisible = false"
    />
    <DeleteNodeDialog
      v-if="deleteVisible && epochs && activeSpace.scopeId && deleteTarget"
      :scope-id="activeSpace.scopeId"
      :node="deleteTarget"
      @done="onDeleteDone"
      @close="deleteVisible = false"
    />
    <SpaceTrashDialog
      v-if="trashVisible && epochs && activeSpace.scopeId"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      @restored="onTrashRestored"
      @close="trashVisible = false"
    />
    <ShareDialog
      v-if="shareVisible && currentNode && memoryKey && epochs"
      :scope-id="activeSpace.scopeId!"
      :node-id="currentNode.id"
      :memory-key="memoryKey"
      :epochs="epochs"
      :memory-name="currentNode.name"
      @close="shareVisible = false"
    />
    <!-- The SAME dialog component, uploader variant (no role switcher, uploader shares only) — a separate action. -->
    <ShareDialog
      v-if="uploaderVisible && currentNode && memoryKey && epochs"
      uploader
      :scope-id="activeSpace.scopeId!"
      :node-id="currentNode.id"
      :memory-key="memoryKey"
      :epochs="epochs"
      :memory-name="currentNode.name"
      @close="uploaderVisible = false"
    />
  </div>
</template>

<style scoped>
/* Fill the full workspace width — the outer .content already applies the page padding, and the gallery's
   justified layout / the browse card grid both reflow to whatever width they're given (no readable-line-length
   concern here, unlike prose). */
.memory-home {
  width: 100%;
}
.home-loading {
  color: var(--app-muted);
}
.home-state h2 {
  margin: 0 0 6px;
}
.home-state p {
  color: var(--app-muted);
  margin: 0;
}
</style>
