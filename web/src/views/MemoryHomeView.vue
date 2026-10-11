<script setup lang="ts">
// The personal-space content host (cr-0030/0031, uc.memory.browse). Mounted at `/`; the current tree position
// lives in the URL **#fragment** (`#~me/reisen-2026/rom`), resolved against the locally-decrypted tree — the
// operator-blind server computes no navigation and never sees the path. Renders a folder/root level (browse)
// or an opened node (detail) by the resolved node's kind, and owns the create / rename / delete dialogs. It names
// no application kind (ADR 0079): the detail component, the create dialog and the share dialog come from the kind's
// registry descriptor (cr-files-application P4c) — the application that registered the kind fills them. A folder's
// create and every node's rename are core's own `core/node/FolderNameDialog.vue`; FOLDER's share dialog is core's
// `core/sharing/FolderShareDialog.vue`, which the registry pre-registers. The folder level is core's
// (`core/node/LevelView.vue`), which asks the registry for each application's section. A move of any node — the folder
// on screen, a section's row, an opened node, a drop box's guest folder — is core's `core/node/MoveNodeDialog.vue`
// through the member's door (`nodeMover`, inbox #358); a moved node on screen is followed to its new place.
//
// **Who looks** (inbox #401, `core/access.ts`): the host builds the member's `Access` (the scope, its epoch keys, its
// pending deletion, the instance's sharing capabilities) and its `TreeSource` (the decrypted tree, fragment links) and
// hands both to the level view and to an opened node's detail. What a view offers is its application's capability
// function over them; the host decides no control itself.
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, activeEpochKeys, enterScope } from '../app/activeSpace'
import { suspensionSignals } from '../app/tenantSuspension'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree } from '../core/node/treeStore'
import { childrenOf, breadcrumbOf, displayNameOf, nodeById } from '../core/node/browse'
import { nodeContentKey, restoreNode } from '../core/node/tree'
import { useTrashUndo } from '../core/node/trashUndo'
import { parseHash, resolveSlugPath, hashForNode, TENANT_SCOPE } from '../core/node/location'
import { listMyTenants } from '../features/spaces/scopes'
import { FOLDER_KIND, type NodeKind } from '../core/node/kind'
import { ScopeKind, type MyTenant } from '../api/generated/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { SHARING_PUBLIC_LINKS, SHARING_UPLOADER_LINKS } from '../features/platform/capabilities'
import type { TreeNode } from '../core/node/tree'
import Message from 'primevue/message'
import DeleteNodeDialog from '../core/node/components/DeleteNodeDialog.vue'
import SpaceTrashDialog from '../core/node/components/SpaceTrashDialog.vue'
import FolderNameDialog from '../core/node/FolderNameDialog.vue'
import LevelView from '../core/node/LevelView.vue'
import MoveNodeDialog from '../core/node/MoveNodeDialog.vue'
import { nodeMover } from '../core/node/move'
import { registry } from '../core/applications/registry'
import { memberAccess, memberTreeSource } from '../core/access'
import TrustResetLink from '../features/account/trustReset/TrustResetLink.vue'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const offerUndo = useTrashUndo()
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
// Folders and the scope root browse (core's `LevelView`); any other node OPENS, to the detail component its kind
// registered. A kind with no registered detail is not guessed at: the host shows an explicit unsupported state.
const isOpenable = computed(() => !!currentNode.value && currentNode.value.kind !== FOLDER_KIND)
const kindDetail = computed(() => {
  if (!isOpenable.value || !currentNode.value) return null
  const info = registry.kind(currentNode.value.kind)
  return info.supported ? (info.detail ?? null) : null
})
const currentFolder = computed(() =>
  currentNode.value && currentNode.value.kind === FOLDER_KIND ? currentNode.value : null,
)
const level = computed(() => childrenOf(treeStore.nodes, currentNodeId.value))
// The folder's heading as the breadcrumb and the list it was picked from call it (a guest's `Anna (2)`, inbox #393).
const currentFolderName = computed(() => (currentFolder.value ? displayNameOf(treeStore.nodes, currentFolder.value) : undefined))
// The full **epoch key set** (ADR 0024) — a read opens each node under its own epoch, new content seals under the
// write epoch. Threaded through the browse + mutation children in place of a single scope key. `activeEpochKeys()`
// isn't reactive, so depend on BOTH `status` (keys set on scope entry) and `epoch` (keys swapped on a rotation
// without a status flip, `refreshActiveScope`) — else a post-rotation create would seal under the stale/zeroed key.
const epochs = computed(() => (activeSpace.status === 'ready' && activeSpace.epoch >= 0 ? activeEpochKeys() : null))
// The current node's content key, whatever its kind, resolved by walking the key chain through every ancestor (the
// scope-root node opens under its own epoch's K_SC, each node below under its parent's key, folders included —
// ADR 0080 Decision 1; held on device, the server is blind). A create here wraps the new node's key under it.
const currentNodeKey = computed(() =>
  currentNodeId.value && epochs.value
    ? nodeContentKey(activeSpace.scopeId!, epochs.value, treeStore.nodes, currentNodeId.value)
    : null,
)
// The open node's K_mem: the same key, for a kind that opens (a folder browses and is never opened).
const memoryKey = computed(() => (isOpenable.value ? currentNodeKey.value : null))

// The current node's share dialog: its kind's (`KindDescriptor.shareDialog`; FOLDER's is core's own, pre-registered).
// A share of the node wraps the node's own key (`currentNodeKey`, never K_SC), so a guest opens that node's subtree and
// nothing beside it (ADR 0080 decisions 1 and 12).
const shareDialog = computed(() => {
  if (!currentNode.value) return null
  const info = registry.kind(currentNode.value.kind)
  return info.supported ? (info.shareDialog ?? null) : null
})
// A share dialog opens only for a node whose kind descriptor says it is shareable AND brings a share dialog (cr-0104; the
// backend refuses a share root of a kind no application declares). Whether the share BUTTON shows is the capability
// function's (the `sharing.public-links` capability, ADR 0032: an operator can turn public sharing off instance-wide,
// and the server then rejects createShare with 422); this is the host's second gate on what it mounts.
const kindShareable = computed(() => {
  if (!currentNode.value) return false
  const info = registry.kind(currentNode.value.kind)
  return info.supported && info.shareable && !!shareDialog.value
})

// Who looks: the member (see the header). A pending deletion (cr-0090) takes every write away — the server refuses them
// during the grace window (uc.spaces.scope-access); a guest's identity folder in a drop box (ADR 0080 decision 11,
// `TreeNode.shareIdentityId`) takes placement away, which the capability functions read from the tree.
const access = computed(() =>
  memberAccess({
    scopeId: activeSpace.scopeId ?? '',
    epochs: epochs.value,
    readonly: isPendingDeletion.value,
    publicLinks: hasCapability(SHARING_PUBLIC_LINKS),
    uploaderLinks: hasCapability(SHARING_UPLOADER_LINKS),
  }),
)
const tree = memberTreeSource({
  nodes: () => treeStore.nodes,
  scopeId: () => activeSpace.scopeId,
  epochs: () => epochs.value,
  scope: () => currentScope.value,
})
const shareVisible = ref(false)
const uploaderVisible = ref(false) // the uploader (drop-box) variant of the same dialog — a separate detail action

// At most one name dialog is open: core's folder-create/rename one, or the create dialog of the kind being created.
const nameDialog = ref<{ mode: 'create' } | { mode: 'rename'; node: TreeNode } | null>(null)
const createKind = ref<NodeKind | null>(null)
// A kind with no `createDialog` (or one nobody registered) opens nothing: the create action is the application's own
// (its section or detail), so offering one without registering the dialog is that application's bug, not a state the
// host can explain.
const createDialog = computed(() => {
  if (!createKind.value) return null
  const info = registry.kind(createKind.value)
  return info.supported ? (info.createDialog ?? null) : null
})
// The node being moved (core's `MoveNodeDialog`); the member's door, read against the current access on every call.
const moving = ref<{ node: TreeNode; shown: string | null } | null>(null)
const mover = nodeMover(() => access.value)
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
// re-entry). Entering opens the scope key + resets the tree and the applications' state (activeSpace.enterScope); we then reload
// both for the newly active scope.
async function reenter(scope: string) {
  await enterScope(scope)
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  // Each application loads its own per-scope state (Memories: the pin set); the hook is idempotent per reset.
  if (activeSpace.status === 'ready' && activeSpace.scopeId) await registry.scopeEntered(activeSpace.scopeId)
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

function openNode(node: TreeNode) {
  void router.push({ hash: hashForNode(treeStore.nodes, node.id, currentScope.value) })
}
/** A create of `kind` under the current position: a FOLDER is core's (`FolderNameDialog`), any other kind opens its own
 *  `createDialog`. */
function startCreate(kind: NodeKind) {
  nameDialog.value = kind === FOLDER_KIND ? { mode: 'create' } : null
  createKind.value = kind === FOLDER_KIND ? null : kind
}
function startRename(node: TreeNode) {
  createKind.value = null
  nameDialog.value = { mode: 'rename', node }
}
function startDelete(node: TreeNode) {
  deleteTarget.value = node
  deleteVisible.value = true
}
/** Remember what is on screen: a move of it (or of a folder above it) changes its path. */
function startMove(node: TreeNode) {
  moving.value = { node, shown: currentNodeId.value }
}
/** The move re-read the tree; when the node on screen moved — itself or with a folder above it — the URL follows it to
 *  its new path (its slug segments changed with its parent), else the position stays. */
function onMoved({ node }: { node: TreeNode }) {
  follow(moving.value?.shown ?? null, node.id)
}
function follow(shown: string | null, movedId: string) {
  if (shown && liesAtOrBelow(shown, movedId)) void router.replace({ hash: hashForNode(treeStore.nodes, shown, currentScope.value) })
}
/** The undo moved it back (after the dialog closed): follow it back the same way. */
function afterUndo(shown: string | null) {
  return (movedId: string) => follow(shown, movedId)
}
function liesAtOrBelow(id: string, ancestorId: string): boolean {
  for (let n = nodeById(treeStore.nodes, id), steps = 0; n && steps <= treeStore.nodes.length; n = nodeById(treeStore.nodes, n.parentId), steps++) {
    if (n.id === ancestorId) return true
  }
  return false
}
async function onMutationDone() {
  await loadTree()
}
/** A restore puts a whole subtree back, so the tree is refetched exactly as a delete refetches it — no local
 *  splice. The position stays where it is: the restored node reappears wherever it belonged. The open trash dialog
 *  re-reads its list when the new tree reaches its `tree` prop (a descendant left behind is a root then, inbox #357). */
async function onTrashRestored() {
  await loadTree()
}
async function onDeleteDone() {
  const deleted = deleteTarget.value
  const parentId = deleted?.parentId ?? null
  await loadTree()
  void router.push({ hash: hashForNode(treeStore.nodes, parentId, currentScope.value) })
  // It went to the trash: said, with an undo that restores it as the trash dialog does (inbox #385 (c)); the restored
  // node reappears where it belonged, the position stays.
  const scopeId = activeSpace.scopeId
  const keys = epochs.value
  if (deleted && scopeId && keys) {
    offerUndo(deleted.name, async () => {
      await restoreNode(scopeId, keys, treeStore.nodes, deleted)
      await loadTree()
    })
  }
}
</script>

<template>
  <div class="veyq-node-host">
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
      <component
        :is="kindDetail"
        v-if="kindDetail && currentNode && activeSpace.scopeId"
        :node="currentNode"
        :scope-id="activeSpace.scopeId"
        :epochs="epochs"
        :content-key="memoryKey"
        :children="level"
        :access="access"
        :tree="tree"
        @rename="startRename(currentNode)"
        @delete="startDelete(currentNode)"
        @move="startMove"
        @create-child="startCreate"
        @open-node="openNode"
        @share="shareVisible = true"
        @share-upload="uploaderVisible = true"
      />
      <p v-else-if="isOpenable" class="home-state-unsupported">{{ t('home.unsupportedKind') }}</p>
      <LevelView
        v-else
        :folder="currentFolder"
        :level="level"
        :access="access"
        :tree="tree"
        :folder-name="currentFolderName"
        @open-node="openNode"
        @create="startCreate"
        @rename-folder="currentFolder && startRename(currentFolder)"
        @rename-node="startRename"
        @delete-folder="currentFolder && startDelete(currentFolder)"
        @move-node="startMove"
        @open-trash="trashVisible = true"
        @share="shareVisible = true"
      />
    </template>

    <FolderNameDialog
      v-if="nameDialog && epochs && activeSpace.scopeId"
      :mode="nameDialog.mode"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :parent-id="currentNodeId"
      :parent-key="currentNodeKey"
      :node="nameDialog.mode === 'rename' ? nameDialog.node : null"
      @done="onMutationDone"
      @close="nameDialog = null"
    />
    <component
      :is="createDialog"
      v-if="createDialog && createKind && epochs && activeSpace.scopeId"
      :kind="createKind"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :parent-id="currentNodeId"
      :parent-key="currentNodeKey"
      @done="onMutationDone"
      @close="createKind = null"
    />
    <DeleteNodeDialog
      v-if="deleteVisible && epochs && activeSpace.scopeId && deleteTarget"
      :scope-id="activeSpace.scopeId"
      :node="deleteTarget"
      @done="onDeleteDone"
      @close="deleteVisible = false"
    />
    <MoveNodeDialog
      v-if="moving && mover"
      :node="moving.node"
      :access="access"
      :tree="tree"
      :mover="mover"
      :root-label="rootLabel"
      :node-name="displayNameOf(treeStore.nodes, moving.node)"
      :after-undo="afterUndo(moving.shown)"
      @moved="onMoved"
      @close="moving = null"
    />
    <SpaceTrashDialog
      v-if="trashVisible && epochs && activeSpace.scopeId"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :tree="treeStore.nodes"
      @restored="onTrashRestored"
      @close="trashVisible = false"
    />
    <component
      :is="shareDialog"
      v-if="shareDialog && kindShareable && shareVisible && currentNode && currentNodeKey && epochs && activeSpace.scopeId"
      :scope-id="activeSpace.scopeId"
      :node-id="currentNode.id"
      :node-key="currentNodeKey"
      :epochs="epochs"
      :node-name="currentNode.name"
      :node-kind="currentNode.kind"
      @close="shareVisible = false"
    />
    <!-- The SAME dialog component, uploader variant (no role switcher, uploader shares only) — a separate detail action;
         a folder's level view never asks for it (no WRITE_ONLY link on a folder). -->
    <component
      :is="shareDialog"
      v-if="shareDialog && kindShareable && uploaderVisible && currentNode && currentNodeKey && epochs && activeSpace.scopeId"
      uploader
      :scope-id="activeSpace.scopeId"
      :node-id="currentNode.id"
      :node-key="currentNodeKey"
      :epochs="epochs"
      :node-name="currentNode.name"
      :node-kind="currentNode.kind"
      @close="uploaderVisible = false"
    />
  </div>
</template>

<style scoped>
.home-loading {
  color: var(--app-muted);
}
.home-state-unsupported {
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
