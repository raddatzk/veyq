<script setup lang="ts">
// A space's page (cr-0030/0031, uc.memory.browse): core's member host, whatever applications the build has. Mounted at
// `/`; the current tree position lives in the URL **#fragment** (`#~me/reisen-2026/memories/rom`), resolved against the
// locally-decrypted tree — the operator-blind server computes no navigation and never sees the path. Renders a
// folder/root level (core's `core/node/LevelView.vue`, which asks the registry for each application's rows) or an opened
// node (the kind's registered `detail` inside core's frame `core/node/components/NodePage.vue`, which supplies core's own
// header actions) by the resolved node's kind. It names no application kind (ADR 0079).
//
// **The page's doors** (`core/node/nodeHost.ts`): everything the level, a row factory or a detail asks of the page goes
// through ONE host this page provides - the member's full set: open a node, and every dialog of core's one set
// (`core/node/nodeDialogs.ts` + `components/NodeDialogs.vue`, which a share guest's page mounts too): a create (core's
// `FolderNameDialog` for a folder, the kind's `createDialog` otherwise), every node's rename (`FolderNameDialog`), a move
// of one node or of a selection (core's move dialogs through the member's `nodeMover`, inbox #358/#421), the delete of
// one node or of a selection after one question with one Undo (`DeleteNodesDialog`: the node a page is about is left for
// its parent once it went), the kind's share dialog, the ZIP download, the copy (uc.platform.copy-node), the import of a
// download, and the space's node trash.
// The fragment is a slug path derived from the names (ADR 0015), so
// after every write that re-reads the tree the node on screen is followed to its path in the new tree (`stayOn`): a
// move or a rename of it or of a folder above it, and an older equal-segment sibling restored or moved in, change it.
//
// **Who looks** (inbox #401, `core/access.ts`): the host builds the member's `Access` (the scope, its epoch keys, its
// pending deletion, the instance's sharing capabilities) and its `TreeSource` (the decrypted tree, fragment links) and
// hands both to the level view and to an opened node's detail. What a view offers is its application's rights
// function over them; the host decides no control itself.
import { computed, ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { activeSpace, activeEpochKeys, enterScope } from '../app/activeSpace'
import { suspensionSignals } from '../app/tenantSuspension'
import { setBreadcrumb } from '../app/breadcrumb'
import { treeStore, loadTree, pollTree } from '../core/node/treeStore'
import { useLevelLive } from '../core/node/useLevelLive'
import { childrenOf, breadcrumbOf, displayNameOf, nodeById } from '../core/node/browse'
import { nodeContentKey, sameKeyOr } from '../core/node/tree'
import { parseHash, resolveSlugPath, hashForNode, personHash, TENANT_SCOPE } from '../core/node/location'
import { listMyTenants } from '../features/spaces/scopes'
import { FOLDER_KIND } from '../core/node/kind'
import { ScopeKind, type MyTenant } from '../api/generated/scopes'
import { useCapabilities } from '../shared/state/useCapabilities'
import { CORE_PUBLIC_SHARES, CORE_PUBLIC_SHARES_CONTRIBUTE, CORE_PUBLIC_SHARES_DROP_BOX } from '../features/platform/capabilities'
import type { TreeNode } from '../core/node/tree'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import NodePage from '../core/node/components/NodePage.vue'
import NodeDialogs from '../core/node/components/NodeDialogs.vue'
import { useNodeDialogs } from '../core/node/nodeDialogs'
import { provideNodeHost, type NodeHost } from '../core/node/nodeHost'
import LevelView from '../core/node/LevelView.vue'
import PersonProfile from '../core/people/PersonProfile.vue'
import { loadTenantPeople, personNow } from '../core/people/directory'
import { registry } from '../core/applications/registry'
import { favoritesStore, loadFavoritesFor } from '../core/favorites/favoritesStore'
import { memberAccess, memberTreeSource } from '../core/access'
import TrustResetLink from '../features/account/trustReset/TrustResetLink.vue'
import StatementNotice from '../features/moderation/statement/StatementNotice.vue'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const { hasCapability, isSwitchedOff, erasureDay } = useCapabilities()

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
const nodeKey = computed(() => (isOpenable.value ? currentNodeKey.value : null))

// Who looks: the member (see the header). A pending deletion (cr-0090) takes every write away — the server refuses them
// during the grace window (uc.spaces.scope-access); a guest's identity folder in a drop box (ADR 0080 decision 11,
// `TreeNode.shareIdentityId`) takes placement away, which the rights functions read from the tree.
// The applications switched off for this tenant (uc.platform.switch-application-off): one manifest id each — the server
// ANDs the instance's and the tenant's switches; each is named once in the banner above every page of the space.
const applicationsOff = computed(() => registry.applications().filter((a) => isSwitchedOff(a.id)))
// Each banner names the day the server erases the application's content here, while it names one
// (uc.platform.erase-switched-off-application); `erasureDay` gives one only for an application the set says is off.
const applicationOffBanners = computed(() =>
  applicationsOff.value.map((app) => {
    const day = erasureDay(app.id)
    const application = t(app.name)
    return {
      id: app.id,
      text: day
        ? t('applications.switchedOffUntil', { application, day: day.toLocaleDateString(locale.value) })
        : t('applications.switchedOff', { application }),
    }
  }),
)
const access = computed(() =>
  memberAccess({
    scopeId: activeSpace.scopeId ?? '',
    epochs: epochs.value,
    readonly: isPendingDeletion.value,
    // One manifest id per leaf, never an AND here: the server computes the AND along the path (ADR 0088 decision 2).
    publicShares: hasCapability(CORE_PUBLIC_SHARES),
    publicSharesContribute: hasCapability(CORE_PUBLIC_SHARES_CONTRIBUTE),
    publicSharesDropBox: hasCapability(CORE_PUBLIC_SHARES_DROP_BOX),
    applicationsOff: new Set(applicationsOff.value.map((a) => a.id)),
  }),
)
const tree = memberTreeSource({
  nodes: () => treeStore.nodes,
  scopeId: () => activeSpace.scopeId,
  epochs: () => epochs.value,
  scope: () => currentScope.value,
})
// Core's dialogs behind the member's doors (see the header), and the page's host: every door. A rename, move, copy or
// delete remembers what was on screen, which the write may give a new path (`stayOn`); the node a page is about, deleted
// from its header, leaves the page for its parent once it went.
const dialogs = useNodeDialogs({
  access: () => access.value,
  tree: () => tree,
  onScreen: () => currentNodeId.value,
  rootLabel: () => rootLabel.value,
  importTarget: () => currentFolderName.value ?? rootLabel.value,
  trashTree: () => treeStore.nodes,
  stayOn,
  leave: (node) => void router.push({ hash: hashForNode(treeStore.nodes, node.parentId, currentScope.value) }),
  reload: () => loadTree(),
})
const host: NodeHost = { open: openNode, ...dialogs.doors }
provideNodeHost(host)

// The tree's live refresh (inbox #408): while a page of the space's tree is on screen — a folder level, and an opened
// node's page of any kind (inbox #503) — pick up what others changed (a share guest's upload, a peer's new Memory, a
// drop box's new guest) without a reload. One read of the scope's tree per tick, applied only when it changed and
// dropped if a write of this tab re-read the tree meanwhile (`treeStore.pollTree`); skipped while any dialog is open. On
// an opened node it is how a moderator's block of that node, or of a folder above it, reaches the page: a node block
// moves the scope's tree revision and no item revision (`CaseDecisions.announce`), so a Memory's own gallery poll stays
// a `304`; the poll brings the row marked, and the page turns to *Blocked by the operator* (the template), its detail
// unmounted with every object URL it held; a lift brings it back. A member removed from the group meanwhile is answered
// `404 scope_not_found`: the page turns to its access-ended state and the poll stops (inbox #546). An idle tick is a `304` (ADR 0081; measured in
// `OpenMemoryTreePollSliceTest`: no more statements than the gallery's own idle poll, whatever the tree holds).
const { ticks: liveTick } = useLevelLive({
  active: () => activeSpace.status === 'ready' && !treeStore.error && !treeStore.gone && !place.value.person,
  paused: () => dialogs.open.value,
  read: (signal) => pollTree(signal),
})

// The breadcrumb root label reflects the **active** scope (the tenant's name vs. Personal), not the URL token —
// a no-grant member sees `Personal` at the token-less `/` because the tenant home fell back to Personal. It is also the
// space's name, the `h1` of its top level (`LevelView`'s `spaceName`, styleguide § Heading levels).
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
// Core's name dialog wrote and re-read the tree (it closes only then): a create lands below the position (it cannot change
// its path), a rename may change it — followed before the dialog is gone (inbox #475). A restore from the trash puts a whole
// subtree back, so the tree is refetched exactly as a delete refetches it — no local splice; the restored node reappears
// wherever it belonged — and when it is an older sibling of one plain segment with the node on screen or a folder above
// it, it takes its birth place and that path renumbers, which `stayOn` follows. The open trash dialog re-reads its list
// when the new tree reaches its `tree` prop (a descendant left behind is a root then, inbox #357).
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
    <!-- inbox #546: removed from the group (or it is gone) while a page of it was open — the scope's reads answer
         `404 scope_not_found`; the page says so, and nothing of the scope polls on (`treeStore.noteScopeGone`). -->
    <div v-else-if="treeStore.gone" class="home-state home-access-ended" role="status">
      <h2>{{ t('home.accessEnded') }}</h2>
      <p>{{ t('home.accessEndedHint') }}</p>
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
      <VeyqMessage v-for="banner in applicationOffBanners" :key="banner.id" tone="warn" class="application-off-banner">
        {{ banner.text }}
      </VeyqMessage>
      <PersonProfile v-if="place.person" :key="place.person" :user-id="place.person" />
      <!-- uc.moderation.take-a-measure: a node a moderator blocked does not open — its placeholder, with no reason; a member
           the statement of reasons names reads it below (uc.moderation.object-to-a-measure). -->
      <template v-else-if="currentNode?.blockedByOperator">
        <p class="home-state-unsupported node-blocked">{{ t('node.blockedByOperator') }}</p>
        <StatementNotice :target-id="currentNode.id" />
      </template>
      <NodePage
        v-else-if="kindDetail && currentNode && activeSpace.scopeId"
        :detail="kindDetail"
        :node="currentNode"
        :scope-id="activeSpace.scopeId"
        :epochs="epochs"
        :content-key="nodeKey"
        :children="level"
        :access="access"
        :tree="tree"
      />
      <p v-else-if="isOpenable" class="home-state-unsupported">{{ t('home.unsupportedKind') }}</p>
      <LevelView
        v-else
        :folder="currentFolder"
        :level="level"
        :access="access"
        :tree="tree"
        :folder-name="currentFolderName"
        :space-name="rootLabel"
        :live-tick="liveTick"
      />
    </template>
    <NodeDialogs :dialogs="dialogs" />
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
