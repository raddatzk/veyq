<script setup lang="ts">
// The personal-space content host (cr-0030/0031, uc.memory.browse). Mounted at `/`; the current tree position
// lives in the URL **#fragment** (`#~me/reisen-2026/memories/rom`), resolved against the locally-decrypted tree — the
// operator-blind server computes no navigation and never sees the path. Renders a folder/root level (browse)
// or an opened node (detail) by the resolved node's kind, and owns the create / rename / delete dialogs. It names
// no application kind (ADR 0079): the detail component, the create dialog and the share dialog come from the kind's
// registry descriptor (cr-files-application P4c) — the application that registered the kind fills them. A folder's
// create and every node's rename are core's own `core/node/FolderNameDialog.vue`; FOLDER's share dialog is core's
// `core/sharing/FolderShareDialog.vue`, which the registry pre-registers. The folder level is core's
// (`core/node/LevelView.vue`), which asks the registry for each application's rows. A move of any node — the folder
// on screen, an application's row, an opened node, a drop box's guest folder — is core's `core/node/MoveNodeDialog.vue`
// through the member's door (`nodeMover`, inbox #358); a level's selection of several moves through core's
// `core/node/MoveNodesDialog.vue` by the same door, and is deleted after one question (`DeleteNodesDialog`, inbox #421).
// A copy of one node or of a selection is core's `core/export/CopyNodesDialog.vue` (uc.platform.copy-node), a member's only.
// The fragment is a slug path derived from the names (ADR 0015), so
// after every write that re-reads the tree the node on screen is followed to its path in the new tree (`stayOn`): a
// move or a rename of it or of a folder above it, and an older equal-segment sibling restored or moved in, change it.
//
// **Who looks** (inbox #401, `core/access.ts`): the host builds the member's `Access` (the scope, its epoch keys, its
// pending deletion, the instance's sharing capabilities) and its `TreeSource` (the decrypted tree, fragment links) and
// hands both to the level view and to an opened node's detail. What a view offers is its application's rights
// function over them; the host decides no control itself.
import { computed, ref, shallowRef, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, activeEpochKeys, enterScope } from '../app/activeSpace'
import { suspensionSignals } from '../app/tenantSuspension'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree, pollTree } from '../core/node/treeStore'
import { useLevelLive } from '../core/node/useLevelLive'
import { childrenOf, breadcrumbOf, displayNameOf, nodeById } from '../core/node/browse'
import { nodeContentKey, restoreNode, sameKeyOr } from '../core/node/tree'
import { useTrashUndo } from '../core/node/trashUndo'
import { parseHash, resolveSlugPath, hashForNode, personHash, TENANT_SCOPE } from '../core/node/location'
import { listMyTenants } from '../features/spaces/scopes'
import { FOLDER_KIND, type NodeKind } from '../core/node/kind'
import { ScopeKind, type MyTenant } from '../api/generated/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX } from '../features/platform/capabilities'
import type { TreeNode } from '../core/node/tree'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import DeleteNodeDialog from '../core/node/components/DeleteNodeDialog.vue'
import DeleteNodesDialog from '../core/node/components/DeleteNodesDialog.vue'
import type { SelectedForDelete } from '../core/node/selection'
import SpaceTrashDialog from '../core/node/components/SpaceTrashDialog.vue'
import FolderNameDialog from '../core/node/FolderNameDialog.vue'
import LevelView from '../core/node/LevelView.vue'
import PersonProfile from '../core/people/PersonProfile.vue'
import { loadTenantPeople, personNow } from '../core/people/directory'
import ExportSubtreeDialog from '../core/export/ExportSubtreeDialog.vue'
import ImportSubtreeDialog from '../core/export/ImportSubtreeDialog.vue'
import CopyNodesDialog from '../core/export/CopyNodesDialog.vue'
import type { ImportOffer } from '../core/export/importSource'
import MoveNodeDialog from '../core/node/MoveNodeDialog.vue'
import MoveNodesDialog from '../core/node/MoveNodesDialog.vue'
import { nodeMover } from '../core/node/move'
import { registry, type NodeShareOffer } from '../core/applications/registry'
import { favoritesStore, loadFavoritesFor } from '../core/favorites/favoritesStore'
import { memberAccess, memberTreeSource } from '../core/access'
import TrustResetLink from '../features/account/trustReset/TrustResetLink.vue'
import StatementNotice from '../features/moderation/statement/StatementNotice.vue'

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

// Position from the #fragment (`core/node/location.ts` owns its grammar: a folder ends in a slash, a Depot node is its
// plain name, any other application's node carries the application's slug). The leading token selects the scope
// (token-less = tenant, `~me` = personal — ADR 0019); a path that doesn't resolve (`false`) collapses to the scope root.
const currentScope = computed(() => parseHash(route.hash).scope)
const place = computed(() => parseHash(route.hash))
// The path (application segment included) and its trailing slash resolve together: `…/report` is the file, `…/report/` the folder.
const resolved = computed(() => resolveSlugPath(treeStore.nodes, place.value.segments, place.value.folder))
// The node on screen is the one the fragment resolved to, and it STAYS while the fragment stays: a re-read of the tree
// under an unchanged fragment (a live poll that brought a peer's rename or move of it, or of a folder above it; this tab's
// own write, before `stayOn`) keeps it on screen as long as it is in the tree, and the fragment follows it below — never
// a collapse to the scope root and back in between, which would unmount an open Memory and reload its gallery. A
// fragment the member navigates to resolves as ever (a new one that names nothing is the scope root), and a node gone
// from the tree (trashed elsewhere) is left to the resolve.
const onScreen = computed<{ hash: string; id: string | null }>((previous) => {
  if (previous?.id && previous.hash === route.hash && nodeById(treeStore.nodes, previous.id)) return previous
  return { hash: route.hash, id: typeof resolved.value === 'string' ? resolved.value : null }
})
const currentNodeId = computed(() => onScreen.value.id)
watch(
  () => [resolved.value, currentNodeId.value] as const,
  ([at, id]) => {
    if (id && at !== id) stayOn(id) // the fragment no longer names the node on screen: it follows the node
  },
)
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
// The same object while it is the same key (`sameKeyOr`): the tree is re-read under an open page (the live poll below), and
// a Memory's gallery reloads on a new key.
const currentNodeKey = computed<Uint8Array | null>((previous) =>
  sameKeyOr(
    previous,
    currentNodeId.value && epochs.value
      ? nodeContentKey(activeSpace.scopeId!, epochs.value, treeStore.nodes, currentNodeId.value)
      : null,
  ),
)
// The open node's K_node: the same key, for a kind that opens (a folder browses and is never opened).
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
// backend refuses a share root of a kind no application declares). Whether the share BUTTON shows is the rights
// function's (the `core.public-shares` register leaves, ADR 0032/0088: an operator can turn public sharing off instance-wide,
// and the server then rejects createShare with 422); this is the host's second gate on what it mounts.
const kindShareable = computed(() => {
  if (!currentNode.value) return false
  const info = registry.kind(currentNode.value.kind)
  return info.supported && info.shareable && !!shareDialog.value
})

// Who looks: the member (see the header). A pending deletion (cr-0090) takes every write away — the server refuses them
// during the grace window (uc.spaces.scope-access); a guest's identity folder in a drop box (ADR 0080 decision 11,
// `TreeNode.shareIdentityId`) takes placement away, which the rights functions read from the tree.
const access = computed(() =>
  memberAccess({
    scopeId: activeSpace.scopeId ?? '',
    epochs: epochs.value,
    readonly: isPendingDeletion.value,
    // One manifest id per leaf, never an AND here: the server computes the AND along the path (ADR 0088 decision 2).
    publicShares: hasCapability(CORE_PUBLIC_SHARES),
    publicSharesContribute: hasCapability(CORE_PUBLIC_SHARES_CONTRIBUTE),
    publicSharesDropBox: hasCapability(CORE_PUBLIC_SHARES_DROP_BOX),
  }),
)
const tree = memberTreeSource({
  nodes: () => treeStore.nodes,
  scopeId: () => activeSpace.scopeId,
  epochs: () => epochs.value,
  scope: () => currentScope.value,
})
const shareVisible = ref(false)
// What the rights function that showed the share button grants the dialog beyond sharing at all (`NodeShareOffer`):
// handed to the dialog as props, so no dialog reads a right itself. Fail closed until a `share` emit sets it.
const shareOffer = ref<NodeShareOffer>({ contribute: false })
function openShare(offer: NodeShareOffer) {
  shareOffer.value = offer
  shareVisible.value = true
}
const uploaderVisible = ref(false) // the uploader (drop-box) variant of the same dialog — a separate detail action

// At most one name dialog is open: core's folder-create/rename one, or the create dialog of the kind being created.
// A rename remembers what was on screen (`shown`), as a move does: renaming it (or a folder above it) changes its path.
const nameDialog = ref<{ mode: 'create' } | { mode: 'rename'; node: TreeNode; shown: string | null } | null>(null)
const createKind = ref<NodeKind | null>(null)
// A kind with no `createDialog` (or one nobody registered) opens nothing: the create action is the application's own
// (its rows or detail), so offering one without registering the dialog is that application's bug, not a state the
// host can explain.
const createDialog = computed(() => {
  if (!createKind.value) return null
  const info = registry.kind(createKind.value)
  return info.supported ? (info.createDialog ?? null) : null
})
// The node being moved (core's `MoveNodeDialog`); the member's door, read against the current access on every call.
const moving = ref<{ node: TreeNode; shown: string | null } | null>(null)
// A level's selection being moved (inbox #421), and what was on screen.
const movingMany = ref<{ nodes: TreeNode[]; shown: string | null } | null>(null)
// A level's selection being deleted (inbox #421): one question, core's `DeleteNodesDialog`.
const deletingMany = ref<{ items: SelectedForDelete[]; shown: string | null } | null>(null)
const mover = nodeMover(() => access.value)
// ADR 0082: the node being downloaded as a ZIP (core's `ExportSubtreeDialog`), and the download being imported under the folder on screen.
const exporting = ref<TreeNode | null>(null)
const importing = shallowRef<ImportOffer | null>(null)
// uc.platform.copy-node: the nodes being copied (core's `CopyNodesDialog`), one or a level's selection, and what was on screen.
const copying = shallowRef<{ nodes: TreeNode[]; shown: string | null } | null>(null)
function startCopy(nodes: TreeNode[]) {
  copying.value = { nodes, shown: currentNodeId.value }
}
const deleteVisible = ref(false)
const trashVisible = ref(false) // the SPACE's node trash (uc.memory.delete-node) — not the per-Memory media trash

// The tree's live refresh (inbox #408): while a page of the space's tree is on screen — a folder level, and an opened
// node's page of any kind (inbox #503) — pick up what others changed (a share guest's upload, a peer's new Memory, a
// drop box's new guest) without a reload. One read of the scope's tree per tick, applied only when it changed and
// dropped if a write of this tab re-read the tree meanwhile (`treeStore.pollTree`); skipped while any dialog is open. On
// an opened node it is how a moderator's block of that node, or of a folder above it, reaches the page: a node block
// moves the scope's tree revision and no item revision (`CaseDecisions.announce`), so a Memory's own gallery poll stays
// a `304`; the poll brings the row marked, and the page turns to *Blocked by the operator* (the template), its detail
// unmounted with every object URL it held; a lift brings it back. An idle tick is a `304` (ADR 0081; measured in
// `OpenMemoryTreePollSliceTest`: no more statements than the gallery's own idle poll, whatever the tree holds).
const { ticks: liveTick } = useLevelLive({
  active: () => activeSpace.status === 'ready' && !treeStore.error && !place.value.person,
  paused: () =>
    !!nameDialog.value || !!createKind.value || !!moving.value || !!movingMany.value || !!deletingMany.value || deleteVisible.value || trashVisible.value || shareVisible.value || uploaderVisible.value,
  read: (signal) => pollTree(signal),
})
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

// The shown profile's handle for the last crumb, from the roster already on the device (empty until it is read).
const personHandle = computed(() => (place.value.person ? personNow(place.value.person)?.username ?? null : null))
watch(
  [() => route.hash, () => treeStore.nodes, rootLabel, personHandle],
  () =>
    setBreadcrumb(
      place.value.person
        ? // a person's profile (ADR 0083) is no node: the space root, then the page
          [
            { id: null, name: rootLabel.value, hash: hashForNode(treeStore.nodes, null, TENANT_SCOPE) },
            { id: null, name: t('people.profile'), hash: personHash(place.value.person) },
            ...(personHandle.value ? [{ id: null, name: `~${personHandle.value}`, hash: personHash(place.value.person) }] : []),
          ]
        : breadcrumbOf(treeStore.nodes, currentNodeId.value, rootLabel.value, currentScope.value),
    ),
  { immediate: true },
)

// Re-enter the scope when the leading fragment token flips (segment-only moves keep the same token → no
// re-entry). Entering opens the scope key + resets the tree and the applications' state (activeSpace.enterScope); we then reload
// both for the newly active scope.
async function reenter(scope: string) {
  await enterScope(scope)
  // The tenant's roster is read in the background, ahead of the tree, so a person's name is at hand (a profile page, its
  // breadcrumb) without waiting for anything.
  if (activeSpace.status === 'ready') void loadTenantPeople().catch(() => undefined)
  if (activeSpace.status === 'ready' && !treeStore.loaded) await loadTree()
  // Core loads the member's pin set, each application its own per-scope state; both are idempotent per reset.
  if (activeSpace.status === 'ready' && activeSpace.scopeId) {
    if (!favoritesStore.loaded) await loadFavoritesFor(activeSpace.scopeId) // once per reset
    await registry.scopeEntered(activeSpace.scopeId)
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
  nameDialog.value = { mode: 'rename', node, shown: currentNodeId.value }
}
function startDelete(node: TreeNode) {
  deleteTarget.value = node
  deleteVisible.value = true
}
/** Remember what is on screen: a move of it (or of a folder above it) changes its path. */
function startMove(node: TreeNode) {
  moving.value = { node, shown: currentNodeId.value }
}
/** The move re-read the tree: the node on screen keeps its place on screen, under its new path if the move changed it. */
function onMoved() {
  stayOn(moving.value?.shown ?? null)
}
function startMoveMany(nodes: TreeNode[]) {
  movingMany.value = { nodes, shown: currentNodeId.value }
}
function startDeleteMany(items: SelectedForDelete[]) {
  deletingMany.value = { items, shown: currentNodeId.value }
}
/** The undo moved it back (after the dialog closed): follow it back the same way. */
function afterUndo(shown: string | null) {
  return () => stayOn(shown)
}
/**
 * After a write re-read the tree, keep `shown` — the node that was on screen before it — on screen: rebuild its fragment
 * from the new tree and replace the URL with it when it differs. A slug segment is derived from a name and numbered among
 * equal ones by creation (`location.ts` `segmentsOf`), so a rename or a move of the node or of any folder above it, and an
 * older sibling of one plain segment restored, moved or renamed in, all change the path; left alone, the old path
 * resolves to nothing and the view collapses to the scope root. Rebuilt from the whole path, never patched where the
 * write was: kind-neutral, and right whichever write changed it. A replace, not a push: the same logical position. A
 * node gone from the tree (deleted elsewhere) is left to the resolve, as any unknown path is.
 */
function stayOn(shown: string | null) {
  if (!shown || !nodeById(treeStore.nodes, shown)) return
  const hash = hashForNode(treeStore.nodes, shown, currentScope.value)
  if (hash !== route.hash) void router.replace({ hash })
}
async function onMutationDone() {
  await loadTree()
}
/** Core's name dialog wrote and re-read the tree (it closes only then): a create lands below the position (it cannot
 *  change its path), a rename may change it — followed here, before the dialog is gone (inbox #475). */
function onNameDone() {
  stayOn(nameDialog.value?.mode === 'rename' ? nameDialog.value.shown : null)
}
/** A restore puts a whole subtree back, so the tree is refetched exactly as a delete refetches it — no local
 *  splice. The position stays where it is: the restored node reappears wherever it belonged — and when it is an older
 *  sibling of one plain segment with the node on screen or a folder above it, it takes its birth place and that path
 *  renumbers, which `stayOn` follows. The open trash dialog re-reads its list when the new tree reaches its `tree` prop
 *  (a descendant left behind is a root then, inbox #357). */
async function onTrashRestored() {
  const shown = currentNodeId.value
  await loadTree()
  stayOn(shown)
}
async function onDeleteDone() {
  const deleted = deleteTarget.value
  const parentId = deleted?.parentId ?? null
  await loadTree()
  void router.push({ hash: hashForNode(treeStore.nodes, parentId, currentScope.value) })
  // It went to the trash: said, with an undo that restores it as the trash dialog does (inbox #385 (c)); the restored
  // node reappears where it belonged, the position (wherever the member is by then) stays, renumbered as a trash
  // dialog's restore is.
  const scopeId = activeSpace.scopeId
  const keys = epochs.value
  if (deleted && scopeId && keys) {
    offerUndo(deleted.name, async () => {
      const shown = currentNodeId.value
      await restoreNode(scopeId, keys, treeStore.nodes, deleted)
      await loadTree()
      stayOn(shown)
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
      <VeyqMessage v-if="isPendingDeletion" tone="warn" class="pending-delete-banner">
        {{ t('groups.pendingDeletion', { day: pendingPurgeDay }) }}
      </VeyqMessage>
      <PersonProfile v-if="place.person" :key="place.person" :user-id="place.person" />
      <!-- uc.moderation.take-a-measure: a node a moderator blocked does not open — its placeholder, with no reason; a member
           the statement of reasons names reads it below (uc.moderation.object-to-a-measure). -->
      <template v-else-if="currentNode?.blockedByOperator">
        <p class="home-state-unsupported node-blocked">{{ t('node.blockedByOperator') }}</p>
        <StatementNotice :target-id="currentNode.id" />
      </template>
      <component
        :is="kindDetail"
        v-else-if="kindDetail && currentNode && activeSpace.scopeId"
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
        @download="exporting = currentNode"
        @copy="startCopy([currentNode])"
        @create-child="startCreate"
        @open-node="openNode"
        @share="openShare"
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
        :live-tick="liveTick"
        @open-node="openNode"
        @create="startCreate"
        @rename-folder="currentFolder && startRename(currentFolder)"
        @rename-node="startRename"
        @delete-folder="currentFolder && startDelete(currentFolder)"
        @move-node="startMove"
        @move-nodes="startMoveMany"
        @delete-nodes="startDeleteMany"
        @download-node="exporting = $event"
        @copy-nodes="startCopy"
        @import-archive="importing = $event"
        @open-trash="trashVisible = true"
        @share="openShare"
      />
    </template>

    <FolderNameDialog
      v-if="nameDialog && epochs && activeSpace.scopeId"
      :mode="nameDialog.mode"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :parent-id="currentNodeId"
      :parent-key="currentNodeKey"
      :parent-epoch="currentNode?.epoch ?? null"
      :node="nameDialog.mode === 'rename' ? nameDialog.node : null"
      @done="onNameDone"
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
      :parent-epoch="currentNode?.epoch ?? null"
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
    <DeleteNodesDialog
      v-if="deletingMany && epochs && activeSpace.scopeId"
      :scope-id="activeSpace.scopeId"
      :epochs="epochs"
      :items="deletingMany.items"
      :name-of="(n: TreeNode) => displayNameOf(treeStore.nodes, n)"
      :after-undo="afterUndo(deletingMany.shown)"
      @done="stayOn(deletingMany?.shown ?? null)"
      @close="deletingMany = null"
    />
    <ExportSubtreeDialog
      v-if="exporting"
      :node="exporting"
      :access="access"
      :tree="tree"
      :display-name="displayNameOf(treeStore.nodes, exporting)"
      @close="exporting = null"
    />
    <ImportSubtreeDialog
      v-if="importing"
      :input="importing.input"
      :upload-as-file="importing.uploadAsFile"
      :access="access"
      :parent="currentNodeKey && currentNode ? { id: currentNode.id, key: currentNodeKey, epoch: currentNode.epoch } : null"
      :target-name="currentFolderName ?? rootLabel"
      @done="onMutationDone"
      @close="importing = null"
    />
    <CopyNodesDialog
      v-if="copying && access.mode === 'member'"
      :nodes="copying.nodes"
      :access="access"
      :tree="tree"
      :root-label="rootLabel"
      @copied="stayOn(copying?.shown ?? null)"
      @close="copying = null"
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
    <MoveNodesDialog
      v-if="movingMany && mover"
      :nodes="movingMany.nodes"
      :access="access"
      :tree="tree"
      :mover="mover"
      :root-label="rootLabel"
      :after-undo="afterUndo(movingMany.shown)"
      @moved="stayOn(movingMany?.shown ?? null)"
      @close="movingMany = null"
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
      :node-epoch="currentNode.epoch"
      :epochs="epochs"
      :node-name="currentNode.name"
      :node-kind="currentNode.kind"
      :contribute="shareOffer.contribute"
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
      :node-epoch="currentNode.epoch"
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
