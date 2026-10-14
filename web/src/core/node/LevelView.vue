<script setup lang="ts">
// The folder level view (cr-files-application P4b, ADR 0079; the UNIFIED LIST since inbox #407): what a member sees at the
// scope root or inside a folder. Core's, and it names no application. In this order it renders its TOOLBAR (the "New" menu,
// the space's node trash, which holds every application's nodes, and the view-mode switch), the folder's HEADER (core's
// `NodeHeader`: name, share, rename, move, delete - a folder is core's kind; the move is core's `MoveNodeDialog`, which the
// host mounts on `moveNode`, inbox #358), the application TABS, the applications' NOTICES, and then ONE LIST - or one set of
// tiles - of everything at this level: the folders, the Memories, the files, the drop boxes, every kind, with no section
// headers.
//
// **Core draws every row; an application only supplies row data.** For each node of its kinds a kind's `list.rows` factory
// (`registry.lists()`, `levelRow.ts`) answers a `LevelRow` - icon or cover, title, meta line, primary click, actions - and
// core's `LevelRowItem` draws it as a row (list mode) or a tile (grid mode). A node of a kind nobody registered, or whose
// factory has no row for it, is an explicit unsupported row (its icon, its name, the unsupported state, not clickable) -
// fail closed, never a node silently dropped. Each factory is called ONCE per level view, in this setup, with the level's
// reactive context (folder, its nodes, access, tree, epochs, view mode, live tick) and the host (open / rename / move, and
// `registerAction`); it keeps the data a row needs (Files' blob listing: it re-reads on `liveTick`, inbox #408) and the flows
// behind its actions (Files' upload and replace) beside it, so none of that depends on which tab is shown.
//
// **The order** (`levelList.ts`): folders first, then every other node MIXED BY NAME - natural, locale-aware, case-insensitive,
// equal names in creation order (the rule that numbers equal names everywhere else). The folder cards' labels are numbered
// the same way (`Anna`, `Anna (2)`, inbox #393).
//
// **The tabs are a FILTER** (inbox #348, `levelTabs.ts`): `All | <one tab per application>`, each tab named by its kinds'
// `list.tabLabel` (the application's own i18n key; kinds sharing a label share a tab). A tab shows only the nodes of its
// kinds, over the CURRENT folder's children - no subtree search - and the FOLDERS stay in every tab. A tab is offered where
// its kinds have a node here or the viewer may add one, and the bar only with two or more. The shown tab is the viewer's own
// preference (`shared/state/useLevelTab`: per account, one for share guests), used only where this level has it, else `All`,
// and never rewritten. A tab with nothing says so in its application's own words (`list.emptyText`); a level with nothing at
// all says so ONCE, in plain text composed from what the viewer may create ("Nothing here yet. Choose New to add something
// (New folder, New Memory, Upload files)") - no create tile.
//
// **The view mode is the shared one** (`shared/state/useViewMode`), but the LEVEL decides which mode it shows (inbox #404):
// the stored preference where something on screen offers it, else the grid (`effectiveViewMode`) - the stored value stays as
// it is, so the next level that offers it shows it again. Rows are drawn as tiles (grid) or rows (list) by core for every kind;
// the map is an application's own presentation: where the shown tab includes a kind whose factory offers it (`list.views`;
// Memories), the toggle offers the map, and in map mode the application's component replaces the list (its pins span the
// whole subtree, folders included). The toggle offers the union of what is on screen (`levelViews.ts`, inbox #370).
//
// **Dropped files.** The level receives a drop and hands the files to the level action that takes them (`acceptsDrop` on a
// kind's `levelActions`: Files' "Upload files"), only while that action is allowed AND enabled - a drop is never more than
// the "New" menu entry would be. Nothing in a share guest's READ view takes one.
//
// **Who looks** (inbox #401): the host hands the level its `access` and its `tree` (`core/access.ts`), and the level hands both
// on to every factory; what the level's own controls offer is core's `folderCapabilities` for the folder on screen - no write
// during a pending deletion, none in a guest's identity folder, a share only of a folder. The share guest's folder page renders
// this very level (`core/sharing/ShareTreePage.vue`): every row is a member's and a guest's alike, the guest's functions removed
// by the capability functions and the sources of the applications' data.
//
// In a guest's identity folder (a drop box's, inbox #385) a tab with nothing to show is left out instead of saying "none yet" -
// nobody can add anything there, so an empty tab only says that this kind exists.
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import type { TreeNode } from './tree'
import type { Level } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import { registry, type LevelActionContext, type LevelMenuItem } from '../applications/registry'
import { createLevelActions } from './levelActions'
import { folderCapabilities, targetOf, type Access, type TreeSource } from '../access'
import { useViewMode } from '../../shared/state/useViewMode'
import { setLevelTab, useLevelTab } from '../../shared/state/useLevelTab'
import { compareByCreation, displayNamesOf } from './location'
import { orderLevel } from './levelList'
import { FOLDER_VIEWS, effectiveViewMode, unionViews } from './levelViews'
import { ALL_TAB, effectiveTab, kindVisible, offeredTabs, showTabBar } from './levelTabs'
import type { CoreRowAction, LevelRow, LevelRowsContext, LevelRowsHost } from './levelRow'
import { favoriteHeaderActions, favoriteRowActions } from '../favorites/favoriteActions'
import { useNodeCover } from './cover/useNodeCover'
import NodeCoverImage from './cover/components/NodeCoverImage.vue'
import CoverEditor from './cover/components/CoverEditor.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import NodeHeader from './components/NodeHeader.vue'
import LevelRowItem from './components/LevelRowItem.vue'
import LevelNotices from './components/LevelNotices.vue'
import { standardNodeActions } from './nodeHeader'
import { downloadHeaderActions, downloadRowActions } from '../export/downloadActions'
import { openExport } from '../export/importSubtree'

const props = defineProps<{
  folder: TreeNode | null
  level: Level
  /** Who looks (`core/access.ts`): what the level offers, and what every row source is handed. */
  access: Access
  /** The tree as that session reads it; handed to every row source. */
  tree: TreeSource
  /** The folder's name as its siblings tell it apart (`displayNamesOf`: a guest's folder `Anna (2)` beside `Anna`,
   *  inbox #393), when the host knows the siblings. Omitted: the folder's own name. */
  folderName?: string
  /** The host's live-refresh count (`useLevelLive`, inbox #408), handed to every row source as `LevelRowsContext.liveTick`. */
  liveTick?: number
}>()
const emit = defineEmits<{
  openNode: [node: TreeNode]
  /** The KIND to create at this level: FOLDER from the "New" menu's folder entry (core's `FolderNameDialog`), an
   *  application's own kind otherwise (that kind's `createDialog`, e.g. Memories' MEMORY). */
  create: [kind: NodeKind]
  renameFolder: []
  /** A node to move (inbox #358): the folder on screen (its header) or a row's node (Files' row). The host mounts core's
   *  `MoveNodeDialog` for it. */
  moveNode: [node: TreeNode]
  /** A node to download as a ZIP (ADR 0082): a row's, or the folder on screen. The host mounts core's `ExportSubtreeDialog`. */
  downloadNode: [node: TreeNode]
  /** A file the member picked or dropped that is a veyq export (recognised by its content, ADR 0082): the host mounts core's
   *  `ImportSubtreeDialog` for it, under the folder on screen. */
  importArchive: [file: File]
  /** A row's node renamed in place (a kind that does not open, e.g. Files' file): the host's `FolderNameDialog`. */
  renameNode: [node: TreeNode]
  deleteFolder: []
  openTrash: []
  share: []
}>()

const { t, locale } = useI18n()
const { viewMode } = useViewMode()
const { levelTab } = useLevelTab()

const inIdentityFolder = computed(() => props.tree.inIdentityFolder(props.folder?.id ?? null))
const caps = computed(() => folderCapabilities(props.access, targetOf(props.tree, props.folder)))
const hideEmptyTabs = computed(() => inIdentityFolder.value)

// --- the "New" menu (inbox #406) -----------------------------------------------------------------------------------------
// Core's folder entry plus every entry the registry derives from the kinds' descriptors, each shown only where ITS capability
// function says yes (member and guest alike; a READ guest gets none, so no menu button). An application action (Files'
// upload) runs the handler its row source registered (`levelActions.ts`) - registered for the level's whole life, whichever
// tab is shown; an entry whose handler says it is not ready (keys still locked) is disabled, never a click that does nothing.
const levelActions = createLevelActions()
const FOLDER_ENTRY: LevelMenuItem = { id: 'create:FOLDER', create: FOLDER_KIND, label: 'node.folderCreate', icon: 'pi pi-folder', order: 0, allowed: () => true, kind: FOLDER_KIND }
// Core's import entry (ADR 0082): a member who may create here picks an export file. Not a registry entry: it belongs to no
// application and runs no row-source handler.
const IMPORT_ENTRY: LevelMenuItem = { id: 'core:import', label: 'export.import', icon: 'pi pi-upload', order: 90, allowed: () => true, kind: FOLDER_KIND }
const registryMenu = registry.levelMenu()
const menuCtx = computed<LevelActionContext>(() => ({
  access: props.access,
  tree: props.tree,
  folder: props.folder,
  epochs: props.access.mode === 'member' ? props.access.epochs : null,
}))
const canImport = computed(() => props.access.mode === 'member' && caps.value.createFolder)
const menuEntries = computed(() => [...(caps.value.createFolder ? [FOLDER_ENTRY] : []), ...registryMenu.filter((e) => e.allowed(menuCtx.value)), ...(canImport.value ? [IMPORT_ENTRY] : [])])
const menuItems = computed<MenuItem[]>(() =>
  menuEntries.value.map((e) => {
    if (e.id === IMPORT_ENTRY.id) {
      return { label: t(e.label), icon: e.icon, disabled: menuCtx.value.epochs === null, class: 'veyq-new-menu-item veyq-new-menu-import', command: () => importInput.value?.click() }
    }
    const handler = e.create === undefined ? levelActions.get(e.id) : undefined
    const ready = e.create !== undefined ? true : !!handler?.enabled()
    return {
      label: t(e.label),
      icon: e.icon,
      disabled: !ready || (e.enabled ? !e.enabled(menuCtx.value) : false),
      class: `veyq-new-menu-item veyq-new-menu-${e.id.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`,
      command: () => (e.create !== undefined ? emit('create', e.create) : handler?.run()),
    }
  }),
)
const newMenu = ref<InstanceType<typeof Menu> | null>(null)
const importInput = ref<HTMLInputElement | null>(null)
function onImportPicked(e: Event): void {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // the same file can be picked again
  if (file) emit('importArchive', file)
}
// The folder header's actions: the capability answers for the folder on screen, in the header's fixed order.
const folderActions = computed(() =>
  standardNodeActions(
    t,
    { share: caps.value.share, rename: caps.value.rename, move: caps.value.move, remove: caps.value.deleteNode },
    {
      share: () => emit('share'),
      rename: () => emit('renameFolder'),
      move: () => props.folder && emit('moveNode', props.folder),
      remove: () => emit('deleteFolder'),
    },
  ),
)

// --- what is here, and the tabs: a pure filter over the one list ------------------------------------------------------------
// Read once per mount, like the host's slots: the registry is filled before the first route resolves and never changes.
const lists = registry.lists()
const registryTabs = registry.tabs()

const nodesOfKinds = (kinds: readonly NodeKind[]): TreeNode[] => kinds.flatMap((k) => props.level.byKind.get(k) ?? [])
const present = computed(() => new Set([...props.level.byKind.entries()].filter(([, nodes]) => nodes.length > 0).map(([kind]) => kind)))
// What the viewer may add here, by the kind that declared the entry.
const creatable = computed(() => new Set(registryMenu.filter((e) => e.allowed(menuCtx.value)).map((e) => e.kind)))

const tabs = computed(() => offeredTabs(registryTabs, present.value, creatable.value))
const tabBar = computed(() => showTabBar(tabs.value))
// The tab this level shows: the stored one where the bar has it, else `All`; the stored one is not touched.
const shownTab = computed(() => effectiveTab(levelTab.value, tabs.value))
function onTab(value: string | number): void {
  setLevelTab(String(value))
}
const visible = (kind: NodeKind): boolean => kindVisible(shownTab.value, tabs.value, kind)

const showFolders = computed(() => !hideEmptyTabs.value || props.level.folders.length > 0)

/** Every non-folder node under the shown tab: those of a listed kind whose tab is shown, and (under `All`) the nodes of a kind
 *  nobody lists, which show as unsupported rows. */
const visibleNodes = computed(() => {
  const nodes: TreeNode[] = []
  for (const [kind, bucket] of props.level.byKind) if (visible(kind)) nodes.push(...bucket)
  return nodes
})

// A row source is ACTIVE while one of its kinds is under the shown tab and has something to offer: a node here, an entry the
// viewer may use, or a presentation of its own (Memories' map: its pins span the subtree) outside a guest's identity folder.
const activeIndexes = computed(() =>
  lists.flatMap((list, i) =>
    list.kinds.some((k) => visible(k) && (present.value.has(k) || creatable.value.has(k) || (list.views.length > 0 && !hideEmptyTabs.value))) ? [i] : [],
  ),
)
// The presentations of what is on screen, in the order first named; a switch only between two or more. Core draws the rows in
// both layouts, so the grid and the list are offered whenever something is listed.
const views = computed(() =>
  unionViews([...activeIndexes.value.map((i) => lists[i].views), showFolders.value || visibleNodes.value.length > 0 ? FOLDER_VIEWS : []]),
)
const showViews = computed(() => views.value.length > 1)
// The mode this level shows: the stored one clamped to what is on screen (inbox #404); the stored one is not touched.
const mode = computed(() => effectiveViewMode(viewMode.value, views.value))

// --- the covers (inbox #411) ----------------------------------------------------------------------------------------------
// ONE cover mechanism for the whole level: the covers of the folder on screen and of every node at this level (whatever its
// kind, whichever tab is shown) are resolved here - a member's from the shared store, a guest's through the link's own reads -
// and handed to the row factories (`host.covers`), so an application that draws its own leading visual (Memories) reads the
// same decrypted image instead of loading its own.
const levelNodes = computed<TreeNode[]>(() => [
  ...(props.folder ? [props.folder] : []),
  ...props.level.folders,
  ...[...props.level.byKind.values()].flat(),
])
const cover = useNodeCover(() => props.access, () => props.tree, () => levelNodes.value)

// --- the applications' row sources -------------------------------------------------------------------------------------
const disposers: (() => void)[] = []
onBeforeUnmount(() => disposers.forEach((dispose) => dispose()))
const host: LevelRowsHost = {
  open: (node) => emit('openNode', node),
  rename: (node) => emit('renameNode', node),
  move: (node) => emit('moveNode', node),
  registerAction: (id, handler) => disposers.push(levelActions.register(id, handler)),
  covers: cover.covers,
  ensureCovers: cover.ensure,
}
const epochs = computed(() => (props.access.mode === 'member' ? props.access.epochs : null))
// One source per row factory, in `lists` order. Called once: a factory sets up its own watchers and loaders here.
const sources = lists.map((list) => {
  const ctx = computed<LevelRowsContext>(() => ({
    folder: props.folder,
    nodes: nodesOfKinds(list.kinds),
    epochs: epochs.value,
    access: props.access,
    tree: props.tree,
    viewMode: mode.value,
    liveTick: props.liveTick,
  }))
  return list.rows(ctx, host)
})
const sourceOfKind = new Map(lists.flatMap((list, i) => list.kinds.map((k) => [k, sources[i]] as const)))

// --- the one list ------------------------------------------------------------------------------------------------------
// The sub-folders' labels: a repeated name numbered, in creation order - the rule the URL segments follow too.
const folderLabels = computed(() => displayNamesOf(props.level.folders, (n) => n.name, compareByCreation))

interface Item { key: string; node: TreeNode; kind: NodeKind; row: LevelRow; folder: boolean; unsupported: boolean }
// A node's cover is the leading visual of its row or tile wherever its application did not supply one (a Memory's row draws its
// own, from the same covers): the image with its crop; the kind's icon stays the default (inbox #411).
function withCover(item: Item): Item {
  const loaded = item.row.leading ? null : cover.covers.value.get(item.node.id)
  if (!loaded) return item
  return { ...item, row: { ...item.row, leading: { component: NodeCoverImage, props: { src: loaded.url, crop: loaded.crop, alt: '' } } } }
}
const items = computed<Item[]>(() => {
  const ordered = orderLevel(showFolders.value ? props.level.folders : [], visibleNodes.value, locale.value)
  return ordered.map((node): Item => withCover(rawItem(node)))
})
function rawItem(node: TreeNode): Item {
  if (node.kind === FOLDER_KIND) {
    const label = folderLabels.value.get(node.id) ?? node.name
    return {
      key: node.id, node, kind: node.kind, folder: true, unsupported: false,
      row: { icon: 'pi pi-folder', title: label, meta: t('node.kindFolder'), primary: { label, onClick: () => emit('openNode', node) }, actions: [] },
    }
  }
  const row = sourceOfKind.get(node.kind)?.row(node) ?? null
  if (row) return { key: node.id, node, kind: node.kind, folder: false, unsupported: false, row }
  // A kind no application serves (or one its source has no row for): listed, never dropped, and said plainly that this
  // client cannot show it. Not clickable - opening one lands on the host's same unsupported state.
  const info = registry.kind(node.kind)
  return {
    key: node.id, node, kind: node.kind, folder: false, unsupported: true,
    row: {
      icon: info.supported ? info.icon : 'pi pi-question-circle', title: node.name, meta: `${node.kind} · ${t('home.unsupportedKind')}`,
      primary: { label: node.name, onClick: () => {} }, actions: [],
    },
  }
}
// Core's own row actions: the favorite heart of inbox #409, gated by `favoriteCapabilities` (member only, fail closed) and
// merged around the application's, for EVERY kind and the folders, with no change to any application. An unsupported row
// (a kind nobody draws) gets none.
// The cover of inbox #411 is core's second row action, merged around the application's the same way: "Set cover" /
// cover" / "Remove cover" in the row's menu, gated by `coverCapabilities` (a writer, a kind that opted in; a guest none).
const coreRowActions = (item: Item): CoreRowAction[] =>
  item.unsupported ? [] : [...favoriteRowActions(t, props.access, item.node), ...cover.rowActions(item.node), ...downloadRowActions(t, props.access, item.node, () => emit('downloadNode', item.node))]
// The folder on screen is a node too: its header carries the same heart and cover action (the scope root is no node and
// has neither).
const folderCoreActions = computed(() => [
  ...favoriteHeaderActions(t, props.access, props.folder),
  ...cover.headerActions(props.folder),
  ...downloadHeaderActions(t, props.access, props.folder, () => props.folder && emit('downloadNode', props.folder)),
])
const folderCover = computed(() => (props.folder ? (cover.covers.value.get(props.folder.id) ?? null) : null))
const itemMode = computed<'grid' | 'list'>(() => (mode.value === 'list' ? 'list' : 'grid'))

// The application's own presentation of the mode (Memories' map), in place of the list.
const presentations = computed(() =>
  mode.value === 'list' || mode.value === 'grid'
    ? []
    : activeIndexes.value.flatMap((i) => {
        const p = sources[i].presentation?.(mode.value)
        return p ? [{ key: `presentation-${i}`, ...p }] : []
      }),
)
const notices = computed(() => lists.flatMap((list, i) => (list.kinds.some(visible) ? (sources[i].notices?.value ?? []) : [])))

// The plain empty-state text: ONE for the whole level under `All` (composed from what the viewer may create), the shown
// application tab's own under a tab. A level with folders needs no text under `All`.
const emptyText = computed<string | null>(() => {
  if (presentations.value.length) return null
  if (shownTab.value === ALL_TAB) {
    if (props.level.folders.length > 0 || visibleNodes.value.length > 0) return null
    return menuEntries.value.length ? t('node.levelEmptyNew', { entries: menuEntries.value.map((e) => t(e.label)).join(', ') }) : t('node.levelEmpty')
  }
  if (visibleNodes.value.length > 0) return null
  const tab = tabs.value.find((x) => x.id === shownTab.value)
  return t(tab?.emptyText?.(menuCtx.value) ?? 'node.tabEmpty')
})

// --- a drop onto the level -----------------------------------------------------------------------------------------------
async function onDrop(e: DragEvent): Promise<void> {
  const files = [...(e.dataTransfer?.files ?? [])]
  if (files.length === 0) return
  // A veyq export dropped onto a level where a member may create is offered as an import (ADR 0082 decision 7), decided
  // by its content, never by its name; anywhere else - and for any other file - the drop is the ordinary upload.
  if (canImport.value && menuCtx.value.epochs !== null) {
    for (const file of files) {
      if (await openExport(file).then(() => true, () => false)) {
        emit('importArchive', file)
        return
      }
    }
  }
  for (const entry of registryMenu) {
    if (entry.create !== undefined || !entry.acceptsDrop || !entry.allowed(menuCtx.value)) continue
    if (entry.enabled && !entry.enabled(menuCtx.value)) continue
    const handler = levelActions.get(entry.id)
    if (handler?.drop && handler.enabled()) {
      handler.drop(files)
      return
    }
  }
}
</script>

<template>
  <div class="veyq-level-view" @dragover.prevent @drop.prevent="onDrop">
    <!-- The space's node trash (uc.memory.delete-node): every application's deleted nodes, so the level view's own. Hidden
         where nothing is written (a pending deletion: a restore is a write; a guest's folder). The view switch governs the
         whole list, so it is the level's too - a guest's as well. -->
    <div v-if="menuEntries.length || caps.nodeTrash || showViews" class="veyq-level-toolbar">
      <template v-if="menuEntries.length">
        <Button
          type="button"
          class="veyq-new-menu-button"
          :label="t('node.new')"
          icon="pi pi-plus"
          size="small"
          aria-haspopup="menu"
          @click="newMenu?.toggle($event)"
        />
        <Menu ref="newMenu" :model="menuItems" popup />
        <input ref="importInput" type="file" accept=".zip,application/zip" hidden data-testid="import-input" @change="onImportPicked" />
      </template>
      <Button
        v-if="caps.nodeTrash"
        type="button"
        :label="t('node.trash.open')"
        icon="pi pi-trash"
        severity="secondary"
        outlined
        size="small"
        @click="emit('openTrash')"
      />
      <ViewModeToggle v-if="showViews" :views="views" />
    </div>
    <!-- The folder's header, core's one (`NodeHeader`): a container kind's detail opens with it too. -->
    <NodeHeader v-if="folder" icon="pi pi-folder" :title="folderName ?? folder.name" :actions="folderActions" :core-actions="folderCoreActions">
      <!-- The folder's cover (inbox #411) in the header's leading slot; the folder icon stays the default without one. -->
      <template v-if="folderCover" #leading>
        <span class="veyq-node-header-cover"><NodeCoverImage :src="folderCover.url" :crop="folderCover.crop" /></span>
      </template>
    </NodeHeader>

    <!-- The application tabs (inbox #348): a filter over this folder's children, the folders in every tab; real buttons
         (PrimeVue Tab), arrow keys move between them. -->
    <Tabs v-if="tabBar" :value="shownTab" class="veyq-level-tabs" @update:value="onTab">
      <TabList :pt="{ tabList: { 'aria-label': t('node.tabs') } }">
        <Tab :value="ALL_TAB">{{ t('node.tabAll') }}</Tab>
        <Tab v-for="tab in tabs" :key="tab.id" :value="tab.id">{{ t(tab.label) }}</Tab>
      </TabList>
    </Tabs>

    <!-- What only an application can explain (why a file was not uploaded): above the list. -->
    <LevelNotices v-if="notices.length" :notices="notices" />

    <component :is="p.component" v-for="p in presentations" :key="p.key" v-bind="p.props" v-on="p.on ?? {}" />

    <p v-if="emptyText" class="veyq-empty veyq-empty-inline">{{ emptyText }}</p>

    <ul v-if="!presentations.length && items.length" class="veyq-level-list" :class="{ 'is-list': mode === 'list' }">
      <LevelRowItem
        v-for="item in items"
        :key="item.key"
        :row="item.row"
        :mode="itemMode"
        :kind="item.kind"
        :folder="item.folder"
        :unsupported="item.unsupported"
        :core-actions="coreRowActions(item)"
      />
    </ul>
    <!-- The one cover dialog (inbox #411): opened by the cover action of a row or of the folder's header. -->
    <CoverEditor :cover="cover" />
  </div>
</template>
