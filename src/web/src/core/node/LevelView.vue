<script setup lang="ts">
// The folder level view (cr-files-application P4b, ADR 0079; the UNIFIED LIST since inbox #407): what a member sees at the
// scope root or inside a folder. Core's, and it names no application. In this order it renders its HEAD - one row of the
// folder's HEADER (core's `NodeHeader`: the name, renamed in place, then share, move and delete - a folder is core's kind; the
// move is core's `MoveNodeDialog`, behind the page's `move` door, inbox #358), or the space's name at the top level, and then the
// "New" menu, which wraps below the name where both do not fit -; then ONE TOOLS ROW right under it (owner 2026-11-04): the
// application TABS on the left, on the right Select and the space's node trash (which holds every application's nodes) as icon
// buttons - their words the accessible name and the tooltip - and the view-mode switch; without a tab bar the tools alone,
// right-aligned, and nothing of either, no row. Then the applications' NOTICES, and then everything at this level - the
// folders, the Memories, the files, the drop boxes, every kind - in TWO GROUPS (owner 2026-11-04): the folders, then the
// contents, each its own `<ul>`. Where both have something under the shown tab, each is a `<section>` named by its quiet `h2`
// ("Folders", "Contents"); one group alone is one list with no heading. In the list each group is one surface of rows parted by
// hairlines, each led by a 16:9 thumbnail (the compact header's 4 x 2.25 rem); in the grid the folders are compact tiles
// (thumbnail, name, actions) and every other node a card - the 16:9 cover flush at its top, the name and meta line inside, its
// heart and "more" over the cover's corner (`LevelRowItem`, `styles/ui.css`).
//
// **Core draws every row; an application only supplies row data.** For each node of its kinds a kind's `list.rows` factory
// (`registry.lists()`, `levelRow.ts`) answers a `LevelRow` - icon or cover, title, meta line, primary click, actions - and
// core's `LevelRowItem` draws it as a row (list mode) or a tile (grid mode). A node of a kind nobody registered, or whose
// factory has no row for it, is an explicit unsupported row (its icon, its name, the unsupported state, not clickable) -
// fail closed, never a node silently dropped. Each factory is called ONCE per level view, in this setup, with the level's
// reactive context (folder, its nodes, access, tree, epochs, view mode, live tick) and the host (the page's doors,
// `nodeHost.ts`, and `registerAction`); it keeps the data a row needs (Depot's blob listing: it re-reads on `liveTick`, inbox #408) and the flows
// behind its actions (Depot's upload and replace) beside it, so none of that depends on which tab is shown.
//
// **Rename, move and delete are core's** (owner 2026-11-01, `standardRowActions.ts`): every row - a folder's too, by core's
// `folderRights` - gets them from the level where its application's answer (`LevelRow.standard`) grants them, through the
// page's doors (`nodeHost.ts`, injected: `rename`, `move`, and `delete` - core's delete question with its Undo); a guest's
// page hands on the move door alone. An application's own action of the same id replaces the default (Depot's file).
//
// **Sharing the level** (owner 2026-11-04): the folder header's share opens core's folder dialog with the folder's READ and
// READ_WRITE roles as its rights grant them, plus "Add files only" - the upload-only link that births an application's node
// here (`app.folderUploadShare`, `../sharing/folderUploadShare.ts`: Depot's drop box) - and is shown where any of READ or
// that link is offered. The space's top level is no link's root: its header's share offers that link alone, and only
// where it is offered (`share(null, …)`); nothing else is shared there.
//
// **The page's doors** (`nodeHost.ts`): everything the level asks of the page around it - open a node, create one, the name,
// move, delete, share, copy, download, import and trash dialogs - goes through the one host the page provides, the very
// object a `detail` injects; an absent door is an action the level does not offer (fail closed).
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
// **Dropped files and directories.** The level receives a drop and hands the files to the level action that takes them
// (`acceptsDrop` on a kind's `levelActions`, a handler with `drop`: Depot's "Upload files") and a dropped directory to the one
// that takes directories (a handler with `dropDirectory`: Depot's "Upload folder…", inbox #424), only while that action is
// allowed AND enabled - a drop is never more than the "New" menu entry would be. Nothing in a share guest's READ view takes one.
// A veyq download among them - a ZIP, or one unpacked into a directory - is recognised by its content and, for a member who may
// create here, offered as the import (ADR 0082 decision 7); there is no import entry of its own in the "New" menu.
//
// **Who looks** (inbox #401): the host hands the level its `access` and its `tree` (`core/access.ts`), and the level hands both
// on to every factory; what the level's own controls offer is core's `folderRights` for the folder on screen - no write
// during a pending deletion, none in a guest's identity folder, a share only of a folder. The share guest's folder page renders
// this very level (`core/sharing/ShareTreePage.vue`): every row is a member's and a guest's alike, the guest's functions removed
// by the rights functions and the sources of the applications' data.
//
// In a guest's identity folder (a drop box's, inbox #385) a tab with nothing to show is left out instead of saying "none yet" -
// nobody can add anything there, so an empty tab only says that this kind exists.
//
// **Select** (inbox #421, owner decisions 2026-10-14): an icon button of the tools row, beside the trash, turns on the level's
// selection mode (`selection.ts`): a labelled checkbox on every row, and a bar with All / None, the count (announced) and the
// selection's **Move to…**, **Copy to…** (core's, a member's: uc.platform.copy-node) and **Delete**. The selection is the LEVEL's: switching tabs keeps it ("All" adds what the tab on
// screen shows, "None" takes that away, the count says how many of the selected the tab hides), leaving the mode or the
// folder clears it, and a node no longer here drops out. What each selected node allows is its application's rights
// answer (`LevelRow.selection`; a folder's is core's `folderRights`): an action is shown where some node of the level
// allows it, enabled where some SELECTED node does, and its hint says how many of the selected it cannot reach. Select is
// offered only where some node here allows an action (a `READ` guest has none) and never in an application's own
// presentation (Memories' map has no rows). The level writes nothing itself: it hands the movable part to the host
// (`moveMany`, core's move dialog), the copyable part (`copy`, core's copy dialog) and the deletable part
// (`deleteMany`, core's delete question).
//
// **Drag and drop** (inbox #421, owner 2026-11-04; `nodeDrag.ts`, uc.memory.move-node § By drag and drop): a second way to
// MOVE, for a pointer only - the keyboard and touch path stays the move dialog. A row or tile is `draggable` where its node
// may be moved (its part in a selection, `LevelRow.selection.move`) and the page has the `moveTo` door; in Select mode a
// selected node drags the selection's movable part. A sub-folder of this level, and an ancestor in the page's breadcrumb
// (the host's, `crumbDropTarget`), takes it exactly where the move dialog would enable "Move here" (`dropTakes`), lit only
// then; the drop is the page's `moveTo` - the dialogs' own run (`moveRun.ts`). A drag of nodes carries its own type and is
// never an upload: the level takes only a drag from the operating system anywhere on it, and no folder lights for one.
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMenu from '../../shared/ui/VeyqMenu.vue'
import type { VeyqMenuItem } from '../../shared/ui/menu'
import VeyqTabs from '../../shared/ui/VeyqTabs.vue'
import VeyqTabList from '../../shared/ui/VeyqTabList.vue'
import VeyqTab from '../../shared/ui/VeyqTab.vue'
import VeyqTooltip from '../../shared/ui/VeyqTooltip.vue'
import type { TreeNode } from './tree'
import type { Level } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import { registry, type LevelActionContext, type LevelMenuItem } from '../applications/registry'
import { createLevelActions } from './levelActions'
import { folderRights, rowRightsOf, targetOf, type Access, type TreeSource } from '../access'
import { useViewMode } from '../../shared/state/useViewMode'
import { setLevelTab, useLevelTab } from '../../shared/state/useLevelTab'
import { compareByCreation, displayNamesOf } from './location'
import { orderLevel } from './levelList'
import { FOLDER_VIEWS, effectiveViewMode, unionViews } from './levelViews'
import { ALL_TAB, effectiveTab, kindVisible, offeredTabs, showTabBar } from './levelTabs'
import type { CoreRowAction, LevelRow, LevelRowSelection, LevelRowsContext, LevelRowsHost } from './levelRow'
import { useSelection } from './selection'
import { activeNodeDrag, dropOnNodeTarget, dropTakes, endNodeDrag, isNodeDrag, leftTarget, overNodeTarget, startNodeDrag } from './nodeDrag'
import { standardRowActions, withStandardRowActions } from './standardRowActions'
import { hostHandlers, hostRights, injectNodeHost, shareOfferOf, type NodeShareOffer } from './nodeHost'
import { folderUploadShareAt } from '../sharing/folderUploadShare'
import { provideCoreNodeActions, useCoreNodeActions } from './coreNodeActions'
import NodeCoverImage from './cover/components/NodeCoverImage.vue'
import CoverEditor from './cover/components/CoverEditor.vue'
import ViewModeToggle from './ViewModeToggle.vue'
import NodeHeader from './components/NodeHeader.vue'
import LevelRowItem from './components/LevelRowItem.vue'
import LevelNotices from './components/LevelNotices.vue'
import { standardNodeActions, standardNodeRename } from './nodeHeader'
import { canCopy } from '../export/copyActions'
import { canReport, reportNodes } from '../report/reportActions'
import { useReportOffered } from '../report/report'
import { isDownloadArchive } from '../export/importSubtree'
import {
  directoryHoldsManifest, holdsManifest, readDroppedDirectory, type DirectoryInput, type DroppedDirectory, type DroppedEntry,
} from '../export/importSource'

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
  /** The space's name (the tenant's, a group's, or Personal): the `h1` of its top level, where no folder is on screen and
   *  so no node names the page (styleguide § Heading levels). A guest never stands there: a link is rooted at a node. */
  spaceName?: string
  /** The host's live-refresh count (`useLevelLive`, inbox #408), handed to every row source as `LevelRowsContext.liveTick`. */
  liveTick?: number
}>()
// The page's doors (`nodeHost.ts`): open a node; create one - FOLDER from the "New" menu's folder entry (core's
// `FolderNameDialog`), an application's own kind otherwise (its `createDialog`); rename, move and delete a node (the folder on
// screen from its header, a row's); move, copy and delete the level's selection; download a node as a ZIP; share the folder;
// import a veyq download picked or dropped for an upload (ADR 0082 decision 7, under the folder on screen); the trash.
const host = injectNodeHost()

const { t, locale } = useI18n()
const { viewMode } = useViewMode()
const { levelTab } = useLevelTab()

const inIdentityFolder = computed(() => props.tree.inIdentityFolder(props.folder?.id ?? null))
const rights = computed(() => folderRights(props.access, targetOf(props.tree, props.folder)))
const hideEmptyTabs = computed(() => inIdentityFolder.value)

// --- the "New" menu (inbox #406) -----------------------------------------------------------------------------------------
// Core's folder entry plus every entry the registry derives from the kinds' descriptors, each shown only where ITS rights
// function says yes (member and guest alike; a READ guest gets none, so no menu button). An application action (Depot's
// upload) runs the handler its row source registered (`levelActions.ts`) - registered for the level's whole life, whichever
// tab is shown; an entry whose handler says it is not ready (keys still locked) is disabled, never a click that does nothing.
const levelActions = createLevelActions()
const FOLDER_ENTRY: LevelMenuItem = { id: 'create:FOLDER', create: FOLDER_KIND, label: 'node.folderCreate', icon: 'folder', order: 0, allowed: () => true, kind: FOLDER_KIND }
const registryMenu = registry.levelMenu()
const menuCtx = computed<LevelActionContext>(() => ({
  access: props.access,
  tree: props.tree,
  folder: props.folder,
  epochs: props.access.mode === 'member' ? props.access.epochs : null,
}))
// A create entry needs the page's create door; an application action its own handler.
const menuEntries = computed(() => [
  ...(rights.value.createFolder && host.createChild ? [FOLDER_ENTRY] : []),
  ...registryMenu.filter((e) => (e.create === undefined || !!host.createChild) && e.allowed(menuCtx.value)),
])
const menuItems = computed<VeyqMenuItem[]>(() =>
  menuEntries.value.map((e) => {
    const handler = e.create === undefined ? levelActions.get(e.id) : undefined
    const ready = e.create !== undefined ? true : !!handler?.enabled()
    return {
      label: t(e.label),
      icon: e.icon,
      disabled: !ready || (e.enabled ? !e.enabled(menuCtx.value) : false),
      class: `veyq-new-menu-item veyq-new-menu-${e.id.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`,
      command: () => (e.create !== undefined ? host.createChild?.(e.create, props.folder) : handler?.run()),
    }
  }),
)
const newMenu = ref<InstanceType<typeof VeyqMenu> | null>(null)
// The upload-only link of this level (above): offered where the page has the share door and core's answer says so.
const uploadShare = computed(() => !!host.share && folderUploadShareAt(menuCtx.value) !== null)
// The folder header's actions and its name's rename in place (ADR 0110 decision 11): the rights answers for the folder on
// screen, narrowed to the page's doors; its share also where only the upload-only link is offered.
const folderOffer = (): NodeShareOffer => ({ ...shareOfferOf(rights.value), upload: uploadShare.value })
const folderHandlers = () => hostHandlers(host, () => props.folder!, folderOffer)
const folderFlags = computed(() => {
  const r = hostRights(rights.value, host)
  return { ...r, share: r.share || uploadShare.value }
})
const folderActions = computed(() => standardNodeActions(t, folderFlags.value, folderHandlers()))
// The space's top level: its header's share offers the upload-only link alone - never READ or READ_WRITE.
const TOP_LEVEL_OFFER: NodeShareOffer = Object.freeze({ view: false, contribute: false, upload: true })
const topLevelActions = computed(() =>
  standardNodeActions(t, { share: uploadShare.value }, { share: () => host.share?.(null, TOP_LEVEL_OFFER) }),
)
const folderRename = computed(() => standardNodeRename(hostRights(rights.value, host), folderHandlers()))
// The space's node trash, where the rights grant it and the page has the door.
const trashOffered = computed(() => rights.value.nodeTrash && !!host.openTrash)

// --- what is here, and the tabs: a pure filter over the level ---------------------------------------------------------------
// Read once per mount, like the host's slots: the registry is filled before the first route resolves and never changes.
const lists = registry.lists()
const registryTabs = registry.tabs()

// The level as core lists it: a kind registered `listed: false` (a future application's one pool per space, reached through
// its own entry) has no row here - and no unsupported row either; the node stays a node everywhere else.
const listedLevel = computed<Level>(() => ({
  folders: props.level.folders,
  byKind: new Map([...props.level.byKind].filter(([kind]) => registry.listed(kind))),
}))
const nodesOfKinds = (kinds: readonly NodeKind[]): TreeNode[] => kinds.flatMap((k) => listedLevel.value.byKind.get(k) ?? [])
const present = computed(() => new Set([...listedLevel.value.byKind.entries()].filter(([, nodes]) => nodes.length > 0).map(([kind]) => kind)))
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

const showFolders = computed(() => !hideEmptyTabs.value || listedLevel.value.folders.length > 0)

/** Every non-folder node under the shown tab: those of a listed kind whose tab is shown, and (under `All`) the nodes of a kind
 *  nobody lists, which show as unsupported rows. */
const visibleNodes = computed(() => {
  const nodes: TreeNode[] = []
  for (const [kind, bucket] of listedLevel.value.byKind) if (visible(kind)) nodes.push(...bucket)
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
  ...listedLevel.value.folders,
  ...[...listedLevel.value.byKind.values()].flat(),
])
// Core's own actions of every node here (`coreNodeActions.ts`: the heart, the cover, "Copy to…", the download, Report), for
// the rows and - provided to it - the folder header; the copy and the download open the host's dialogs.
const coreActions = useCoreNodeActions(() => props.access, () => props.tree, () => levelNodes.value, {
  ...(host.copy ? { copy: (nodes) => host.copy!(nodes) } : {}),
  ...(host.download ? { download: (node) => host.download!(node) } : {}),
})
provideCoreNodeActions(coreActions)
const cover = coreActions.cover

// --- the applications' row sources -------------------------------------------------------------------------------------
const disposers: (() => void)[] = []
onBeforeUnmount(() => disposers.forEach((dispose) => dispose()))
// The page's doors and the level's own: the one host a row factory is handed.
const rowsHost: LevelRowsHost = {
  ...host,
  registerAction: (id, handler) => disposers.push(levelActions.register(id, handler)),
  covers: cover.covers,
  ensureCovers: cover.ensure,
  screenUpload,
  screenDirectory,
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
  return list.rows(ctx, rowsHost)
})
const sourceOfKind = new Map(lists.flatMap((list, i) => list.kinds.map((k) => [k, sources[i]] as const)))
/** The row with core's default rename, move and delete added where its application granted them, the page has the door
 *  (`standardRowActions.ts`: a guest's page the move alone) and the application has none of its own. */
function withStandard(node: TreeNode, row: LevelRow): LevelRow {
  return { ...row, actions: withStandardRowActions(row.actions, standardRowActions(t, node, row, host)) }
}

// --- the list: the folders, then the contents ----------------------------------------------------------------------------
// The sub-folders' labels: a repeated name numbered, in creation order - the rule the URL segments follow too.
const folderLabels = computed(() => displayNamesOf(listedLevel.value.folders, (n) => n.name, compareByCreation))

interface Item { key: string; node: TreeNode; kind: NodeKind; row: LevelRow; folder: boolean; unsupported: boolean }
// A node's cover is the leading visual of its row or tile wherever its application did not supply one (a Memory's row draws its
// own, from the same covers): the image with its crop; the kind's icon stays the default (inbox #411).
function withCover(item: Item): Item {
  // A blocked node's cover is refused like every byte of it (451): its placeholder draws none.
  const loaded = item.row.leading || item.node.blockedByOperator ? null : cover.covers.value.get(item.node.id)
  if (!loaded) return item
  return { ...item, row: { ...item.row, leading: { component: NodeCoverImage, props: { src: loaded.url, crop: loaded.crop, alt: '' } } } }
}
const items = computed<Item[]>(() => {
  const ordered = orderLevel(showFolders.value ? listedLevel.value.folders : [], visibleNodes.value, locale.value)
  return ordered.map((node): Item => withCover(rawItem(node)))
})
// The list's two groups (owner 2026-11-04): the folders, then everything else. Only where both have something under the shown
// tab does each get its heading (`h2`: the page's `h1` is the header's name) and its `<section>` named by it; one group alone
// has nothing to be told apart from, so it is one list with no heading.
interface Group { id: 'folders' | 'contents'; heading: string; items: Item[] }
const groups = computed<Group[]>(() =>
  [
    { id: 'folders' as const, heading: 'node.groupFolders', items: items.value.filter((i) => i.folder) },
    { id: 'contents' as const, heading: 'node.groupContents', items: items.value.filter((i) => !i.folder) },
  ].filter((g) => g.items.length > 0),
)
const headed = computed(() => groups.value.length > 1)
const groupIds = useId()
const headingId = (group: Group['id']): string => `veyq-level-group-${group}-${groupIds}`
function rawItem(node: TreeNode): Item {
  return node.blockedByOperator ? blockedItem(node, unblockedItem(node)) : unblockedItem(node)
}
/**
 * A node a moderator blocked (uc.moderation.take-a-measure): the placeholder "Blocked by the operator", with no reason, for
 * members and guests alike (inbox #401: a row state, not a guest screen). It does not open and shows no cover; of its
 * actions only delete is kept — the uploader may still delete it, nothing else reaches it.
 */
function blockedItem(node: TreeNode, item: Item): Item {
  return {
    ...item,
    row: {
      icon: 'ban',
      title: node.name,
      meta: t('node.blockedByOperator'),
      primary: { label: node.name, onClick: () => {}, disabled: true },
      actions: item.row.actions.filter((a) => a.id === 'delete'),
      selection: { move: false, delete: item.row.selection?.delete ?? false },
    },
  }
}
function unblockedItem(node: TreeNode): Item {
  if (node.kind === FOLDER_KIND) {
    const label = folderLabels.value.get(node.id) ?? node.name
    return {
      key: node.id, node, kind: node.kind, folder: true, unsupported: false,
      row: withStandard(node, {
        icon: 'folder', title: label, meta: t('node.kindFolder'), primary: { label, onClick: () => host.open?.(node) }, actions: [],
        ...folderParts(node),
      }),
    }
  }
  const row = sourceOfKind.get(node.kind)?.row(node) ?? null
  if (row) return { key: node.id, node, kind: node.kind, folder: false, unsupported: false, row: withStandard(node, row) }
  // A kind no application serves (or one its source has no row for): listed, never dropped, and said plainly that this
  // client cannot show it. Not clickable - opening one lands on the host's same unsupported state.
  const info = registry.kind(node.kind)
  return {
    key: node.id, node, kind: node.kind, folder: false, unsupported: true,
    row: {
      icon: info.supported ? info.icon : 'question-circle', title: node.name, meta: `${node.kind} · ${t('home.unsupportedKind')}`,
      primary: { label: node.name, onClick: () => {} }, actions: [],
    },
  }
}
/** A sub-folder's part in core's default actions and in a selection: core's matrix for THAT folder (`folderRights`). */
function folderParts(node: TreeNode): Pick<LevelRow, 'standard' | 'selection'> {
  return rowRightsOf(folderRights(props.access, targetOf(props.tree, node)))
}

// --- the selection (inbox #421) ------------------------------------------------------------------------------------------
// Every node at the level, whichever tab is shown: a selected node the tab hides still moves and is deleted with the rest.
const levelItems = computed(() => new Map([...listedLevel.value.folders, ...[...listedLevel.value.byKind.values()].flat()].map((n) => [n.id, rawItem(n)])))
const selection = useSelection()
const selecting = ref(false)
const allowsOf = (id: string): LevelRowSelection | undefined => levelItems.value.get(id)?.row.selection
// Offered where something here allows an action and the page has its door, and only while core draws the rows (no
// application presentation).
const anyMove = computed(() => !!host.moveMany && [...levelItems.value.values()].some((i) => i.row.selection?.move === true))
const anyDelete = computed(() => !!host.deleteMany && [...levelItems.value.values()].some((i) => i.row.selection?.delete === true))
// "Copy to…" is core's (uc.platform.copy-node): a member's, for every node whose kind this build copies (`canCopy`).
const copiesNode = (id: string): boolean => {
  const item = levelItems.value.get(id)
  return !!item && !item.unsupported && canCopy(props.access, item.node)
}
const anyCopy = computed(() => !!host.copy && [...levelItems.value.keys()].some(copiesNode))
// "Report" is core's (uc.moderation.report): anyone who sees a node reports it, a READ guest included — so it alone
// makes a level selectable — while the instance publishes a moderation key.
const reportOn = useReportOffered()
const reportsNode = (id: string): boolean => {
  const item = levelItems.value.get(id)
  return !!item && !item.unsupported
}
const anyReport = computed(() => reportOn.value && canReport(props.access) && [...levelItems.value.keys()].some(reportsNode))
const canSelect = computed(
  () => (mode.value === 'grid' || mode.value === 'list') && (anyMove.value || anyDelete.value || anyCopy.value || anyReport.value),
)
/** Whether the level offers any tool beside its tabs: Select, the trash or the view switch ("New" sits in the head). */
const hasTools = computed(() => trashOffered.value || showViews.value || canSelect.value)
const selectedIds = computed(() => [...selection.ids.value].filter((id) => levelItems.value.has(id)))
const movable = computed(() => selectedIds.value.filter((id) => allowsOf(id)?.move === true))
const deletable = computed(() => selectedIds.value.filter((id) => allowsOf(id)?.delete === true))
const copyable = computed(() => selectedIds.value.filter(copiesNode))
const reportable = computed(() => selectedIds.value.filter(reportsNode))
const shownIds = computed(() => items.value.map((i) => i.key))
const hiddenCount = computed(() => {
  const shown = new Set(shownIds.value)
  return selectedIds.value.filter((id) => !shown.has(id)).length
})
const countText = computed(() =>
  hiddenCount.value > 0
    ? t('node.select.countHidden', { count: selectedIds.value.length, hidden: hiddenCount.value })
    : t('node.select.count', { count: selectedIds.value.length }),
)
const cannotMoveText = computed(() => {
  const m = selectedIds.value.length
  const n = m - movable.value.length
  return n > 0 ? t('node.select.cannotMove', { n, m }, n) : ''
})
const cannotCopyText = computed(() => {
  const m = selectedIds.value.length
  const n = m - copyable.value.length
  return anyCopy.value && n > 0 ? t('node.select.cannotCopy', { n, m }, n) : ''
})
const cannotDeleteText = computed(() => {
  const m = selectedIds.value.length
  const n = m - deletable.value.length
  return anyDelete.value && n > 0 ? t('node.select.cannotDelete', { n, m }, n) : ''
})
function toggleSelecting(): void {
  selecting.value = !selecting.value
}
function moveSelected(): void {
  const nodes = movable.value.map((id) => levelItems.value.get(id)!.node)
  if (nodes.length) host.moveMany?.(nodes)
}
function copySelected(): void {
  const nodes = copyable.value.map((id) => levelItems.value.get(id)!.node)
  if (nodes.length) host.copy?.(nodes)
}
function reportSelected(): void {
  const nodes = reportable.value.map((id) => levelItems.value.get(id)!.node)
  if (nodes.length) reportNodes(props.access, props.tree, nodes, props.folder)
}
function deleteSelected(): void {
  const chosen = deletable.value.map((id) => {
    const item = levelItems.value.get(id)!
    return { node: item.node, ...(item.row.selection?.remove ? { remove: item.row.selection.remove } : {}) }
  })
  if (chosen.length) host.deleteMany?.(chosen)
}
// Leaving the mode, another folder, or a presentation without rows clears it; a node that left the level drops out.
watch(selecting, (on) => {
  if (!on) selection.clear()
})
watch(() => props.folder?.id ?? null, () => {
  selecting.value = false
  selection.clear()
})
watch(canSelect, (can) => {
  if (!can) selecting.value = false
})
watch(levelItems, (now) => selection.retain(now.keys()))

// --- drag and drop (inbox #421, owner 2026-11-04; `nodeDrag.ts`) ----------------------------------------------------------
// A second way to move, beside the dialog, through the page's `moveTo` door (the dialogs' own run). A row or tile drags only
// where its node may be moved - its part in a selection (`LevelRow.selection.move`; a folder's is core's `folderRights`) and
// the door, so a guest drags exactly what its move dialog would move. Only core's rows drag: an application's own presentation
// (Memories' map) replaces them and has none. In Select mode a selected node drags the selection's movable part, an unselected
// one itself. A sub-folder of this level takes the drag where the dialog would move it there (`dropTakes`); the breadcrumb's
// ancestors are the page's (`crumbDropTarget`).
const canDrag = (item: Item): boolean => !!host.moveTo && !item.unsupported && item.row.selection?.move === true
/** The id of the folder whose row a drag it takes is over. */
const dropOver = ref<string | null>(null)
const dragged = computed(() => activeNodeDrag()?.ids ?? null)
let ownDrag = false
function draggedNodes(item: Item): TreeNode[] {
  if (selecting.value && selection.has(item.key)) return movable.value.map((id) => levelItems.value.get(id)!.node)
  return [item.node]
}
function onDragStart(e: DragEvent, item: Item): void {
  if (!canDrag(item)) return // not a drag of nodes (a text selection's, say): left to the browser
  const nodes = draggedNodes(item)
  const move = host.moveTo
  ownDrag = true
  startNodeDrag(
    e,
    {
      ids: new Set(nodes.map((n) => n.id)),
      accepts: (to) => dropTakes(props.tree, nodes, props.access.mode, to),
      drop: (to) => move?.(nodes, to),
    },
    nodes.length > 1 ? t('node.drag.count', { count: nodes.length }, nodes.length) : undefined,
  )
}
function onDragEnd(): void {
  if (ownDrag) endNodeDrag()
  ownDrag = false
  dropOver.value = null
}
onBeforeUnmount(onDragEnd)
// A sub-folder's row as a target; a drag from the operating system (files) never lights one.
function onTargetOver(e: DragEvent, item: Item): void {
  if (!item.folder || item.unsupported) return
  if (overNodeTarget(e, item.node.id)) dropOver.value = item.key
  else if (dropOver.value === item.key) dropOver.value = null
}
function onTargetLeave(e: DragEvent, item: Item): void {
  if (dropOver.value === item.key && leftTarget(e)) dropOver.value = null
}
function onTargetDrop(e: DragEvent, item: Item): void {
  dropOver.value = null
  if (item.folder && !item.unsupported) dropOnNodeTarget(e, item.node.id)
}

// Core's own row actions (`coreNodeActions.ts`), merged around the application's for EVERY kind and the folders, with no
// change to any application: the heart (inbox #409), the cover (inbox #411), "Copy to…" beside the move, the download and
// Report. An unsupported row (a kind nobody draws) gets none, and so does a node a moderator blocked: its placeholder keeps
// its delete alone (`blockedItem`), core's default or the application's own.
const coreRowActions = (item: Item): CoreRowAction[] => (item.unsupported || item.node.blockedByOperator ? [] : coreActions.row(item.node))
// The folder on screen is a node too: its header carries the same core actions, which `NodeHeader` takes for its `node` from
// this view (the scope root is no node and has none), and is drawn from FOLDER's descriptor as every kind's page header is
// (ADR 0110 decision 10): its icon, its cover - the leading visual, which opens this view's cover dialog where the member may
// set it - and its priority, `header.order` (core's pre-registration).
const folderKind = registry.kind(FOLDER_KIND)
const folderHeader = folderKind.supported ? { icon: folderKind.icon, cover: !!folderKind.cover, order: folderKind.header?.order } : null
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
    if (listedLevel.value.folders.length > 0 || visibleNodes.value.length > 0) return null
    return menuEntries.value.length ? t('node.levelEmptyNew', { entries: menuEntries.value.map((e) => t(e.label)).join(', ') }) : t('node.levelEmpty')
  }
  if (visibleNodes.value.length > 0) return null
  const tab = tabs.value.find((x) => x.id === shownTab.value)
  return t(tab?.emptyText?.(menuCtx.value) ?? 'node.tabEmpty')
})

// --- a veyq download in an upload, and a drop onto the level ---------------------------------------------------------------
// ADR 0082 decision 7: a ZIP that holds a veyq manifest, picked for an upload or dropped, is recognised by its CONTENT - never by
// its name - and, for a member who may create here, offered as an import with "Upload as a file" beside it; so is a directory
// picked for a folder upload or dropped that holds `.veyq/manifest.json` at its root (a download unpacked: Import only - a
// directory cannot be "uploaded as a file"). One import per pick or drop: the first download found is offered, every other file
// or directory (another download too) is the ordinary upload. A guest - whatever the role - never imports: for a guest a
// download is an ordinary file, and an unpacked one an ordinary folder.
const canImport = computed(() => props.access.mode === 'member' && rights.value.createFolder && !!host.importArchive)
const importReady = computed(() => canImport.value && menuCtx.value.epochs !== null)

async function screenUpload(files: File[], uploadAsFile: ((files: File[]) => void) | null): Promise<File[]> {
  if (!importReady.value) return files
  for (const [i, file] of files.entries()) {
    if (await isDownloadArchive(file)) {
      host.importArchive?.({ input: { kind: 'zip', file }, ...(uploadAsFile ? { uploadAsFile: () => uploadAsFile([file]) } : {}) })
      return files.filter((_, k) => k !== i)
    }
  }
  return files
}

function screenDirectory(dir: DirectoryInput): boolean {
  if (!importReady.value || !directoryHoldsManifest(dir)) return false
  host.importArchive?.({ input: dir })
  return true
}

/** The handlers a drop may reach: those of the level actions that take a drop, while each is allowed AND enabled (a drop is
 *  never more than the menu entry). */
function dropHandlers() {
  return registryMenu.flatMap((entry) => {
    if (entry.create !== undefined || !entry.acceptsDrop || !entry.allowed(menuCtx.value)) return []
    if (entry.enabled && !entry.enabled(menuCtx.value)) return []
    const handler = levelActions.get(entry.id)
    return handler?.enabled() ? [handler] : []
  })
}

/** The level takes a drop from the operating system anywhere on it; a drag of nodes only where a target took it. */
function onLevelDragOver(e: DragEvent): void {
  if (!isNodeDrag(e)) e.preventDefault()
}

async function onDrop(e: DragEvent): Promise<void> {
  if (isNodeDrag(e)) return // a drag of nodes is a move, never an upload
  // The entries are read NOW: a drop's items are gone once the handler awaits anything.
  const entries = [...(e.dataTransfer?.items ?? [])].map((item) => (item.kind === 'file' ? (item.webkitGetAsEntry?.() as DroppedEntry | null) : null))
  const all = [...(e.dataTransfer?.files ?? [])]
  if (all.length === 0) return
  const handlers = dropHandlers()
  const upload = handlers.find((h) => h.drop)?.drop ?? null
  const uploadFolder = handlers.find((h) => h.dropDirectory)?.dropDirectory ?? null
  // A dropped directory: the download unpacked (`.veyq/manifest.json` at its root) is offered as an import - one per drop;
  // any other is the folder upload (inbox #424), where this level takes one.
  const directories = entries.filter((x): x is DroppedEntry => !!x?.isDirectory) as DroppedDirectory[]
  const files = all.filter((_, i) => !entries[i]?.isDirectory)
  let offered = false
  for (const dir of directories) {
    if (!offered && importReady.value && (await holdsManifest(dir))) {
      offered = true
      host.importArchive?.({ input: await readDroppedDirectory(dir) })
    } else if (uploadFolder) {
      uploadFolder(await readDroppedDirectory(dir))
    }
  }
  if (files.length === 0) return
  // One import per drop: after a directory's, every file is the ordinary upload. Nowhere to upload (no upload entry here): a
  // download is still an import where the member may create, with no way back.
  const rest = offered ? files : await screenUpload(files, upload)
  if (rest.length > 0 && upload) upload(rest)
}
</script>

<template>
  <div class="veyq-level-view" @dragover="onLevelDragOver" @drop.prevent="onDrop">
    <!-- The level's head (owner 2026-11-01, tidied 2026-11-04): the header and "New" in ONE row, the name first; where both
         do not fit, "New" wraps below the name, which it never shortens. Name first in the DOM too, so the Tab order is the
         reading order. -->
    <div v-if="folder || spaceName || menuEntries.length" class="veyq-level-head" :class="{ 'has-header': !!(folder || spaceName) }">
      <!-- The folder's header, core's one (`NodeHeader`): a container kind's detail opens with it too. -->
      <NodeHeader
        v-if="folder"
        :node="folder"
        :icon="folderHeader?.icon"
        :cover="folderHeader?.cover"
        :title="folderName ?? folder.name"
        :actions="folderActions"
        :rename="folderRename"
        :order="folderHeader?.order"
      />
      <!-- The space's top level: core's header with the space's name (the space is no node); its one action is the share of
           the upload-only link, where it is offered. -->
      <NodeHeader v-else-if="spaceName" :node="null" :title="spaceName" :actions="topLevelActions" />
      <!-- "New" (inbox #406): the level's one primary action, at the end of the header's row. -->
      <div v-if="menuEntries.length" class="veyq-level-new">
        <VeyqButton
          class="veyq-new-menu-button"
          :label="t('node.new')"
          icon="plus"
          size="small"
          aria-haspopup="menu"
          @click="newMenu?.toggle($event)"
        />
        <VeyqMenu ref="newMenu" :items="menuItems" />
      </div>
    </div>

    <!-- The tools row (owner 2026-11-04), right under the head: the application tabs on the left, the level's tools on the
         right; without a tab bar the tools alone, at the right end; nothing of either, no row. -->
    <div v-if="tabBar || hasTools" class="veyq-level-tools" :class="{ 'has-tabs': tabBar }">
      <!-- The application tabs (inbox #348): a filter over this folder's children, the folders in every tab; real buttons
           (`VeyqTab`), arrow keys move between them. -->
      <VeyqTabs v-if="tabBar" :value="shownTab" class="veyq-level-tabs" @update:value="onTab">
        <VeyqTabList :aria-label="t('node.tabs')">
          <VeyqTab :value="ALL_TAB">{{ t('node.tabAll') }}</VeyqTab>
          <VeyqTab v-for="tab in tabs" :key="tab.id" :value="tab.id">{{ t(tab.label) }}</VeyqTab>
        </VeyqTabList>
      </VeyqTabs>
      <!-- Select and the space's node trash as icon buttons (their words the name and the tooltip), then the view switch.
           The trash (uc.memory.delete-node) holds every application's deleted nodes, so it is the level view's own; hidden
           where nothing is written (a pending deletion: a restore is a write; a guest's folder). The view switch governs the
           whole list, so it is the level's too - a guest's as well. -->
      <div v-if="hasTools" class="veyq-level-toolbar">
        <VeyqTooltip v-if="canSelect" :text="t('node.select.action')">
          <VeyqButton
            class="veyq-select-button"
            :aria-label="t('node.select.action')"
            icon="check-square"
            variant="secondary"
            :appearance="selecting ? 'filled' : 'text'"
            size="small"
            :aria-pressed="selecting"
            @click="toggleSelecting"
          />
        </VeyqTooltip>
        <VeyqTooltip v-if="trashOffered" :text="t('node.trash.open')">
          <VeyqButton
            class="veyq-trash-button"
            :aria-label="t('node.trash.open')"
            icon="trash"
            variant="secondary"
            appearance="text"
            size="small"
            @click="host.openTrash?.()"
          />
        </VeyqTooltip>
        <ViewModeToggle v-if="showViews" :views="views" />
      </div>
    </div>

    <!-- What only an application can explain (why a file was not uploaded): above the list. -->
    <LevelNotices v-if="notices.length" :notices="notices" />

    <component :is="p.component" v-for="p in presentations" :key="p.key" v-bind="p.props" v-on="p.on ?? {}" />

    <p v-if="emptyText" class="veyq-empty veyq-empty-inline">{{ emptyText }}</p>

    <!-- The selection's bar (inbox #421): All / None, the count (announced), and what the selection may do. -->
    <div v-if="selecting" class="veyq-level-selection-bar" role="group" :aria-label="t('node.select.bar')">
      <VeyqButton class="veyq-select-all" :label="t('node.select.all')" variant="secondary" appearance="text" size="small" @click="selection.all(shownIds)" />
      <VeyqButton class="veyq-select-none" :label="t('node.select.none')" variant="secondary" appearance="text" size="small" @click="selection.none(shownIds)" />
      <span class="veyq-level-selection-count" aria-live="polite">{{ countText }}</span>
      <span class="veyq-level-selection-actions">
        <VeyqButton
          v-if="anyMove"
          class="veyq-select-move"
          :label="t('node.move.action')"
          icon="file-export"
          size="small"
          appearance="outlined"
          :disabled="movable.length === 0"
          :aria-describedby="cannotMoveText ? 'veyq-select-cannot-move' : undefined"
          @click="moveSelected"
        />
        <VeyqButton
          v-if="anyCopy"
          class="veyq-select-copy"
          :label="t('node.copy.action')"
          icon="copy"
          size="small"
          appearance="outlined"
          :disabled="copyable.length === 0"
          :aria-describedby="cannotCopyText ? 'veyq-select-cannot-copy' : undefined"
          @click="copySelected"
        />
        <VeyqButton
          v-if="anyReport"
          class="veyq-select-report"
          :label="t('report.action')"
          icon="flag"
          size="small"
          appearance="outlined"
          :disabled="reportable.length === 0"
          @click="reportSelected"
        />
        <VeyqButton
          v-if="anyDelete"
          class="veyq-select-delete"
          :label="t('node.delete')"
          icon="trash"
          variant="danger"
          size="small"
          appearance="outlined"
          :disabled="deletable.length === 0"
          :aria-describedby="cannotDeleteText ? 'veyq-select-cannot-delete' : undefined"
          @click="deleteSelected"
        />
      </span>
      <p v-if="anyMove && cannotMoveText" id="veyq-select-cannot-move" class="veyq-level-selection-hint">{{ cannotMoveText }}</p>
      <p v-if="cannotCopyText" id="veyq-select-cannot-copy" class="veyq-level-selection-hint">{{ cannotCopyText }}</p>
      <p v-if="cannotDeleteText" id="veyq-select-cannot-delete" class="veyq-level-selection-hint">{{ cannotDeleteText }}</p>
    </div>

    <!-- The folders, then everything else: two groups, each its own list (in the list view its own surface), headed and a
         `<section>` named by its heading only where both are there. Tab reaches the folders first. -->
    <div v-if="!presentations.length && items.length" class="veyq-level-groups">
      <component
        :is="headed ? 'section' : 'div'"
        v-for="group in groups"
        :key="group.id"
        class="veyq-level-group"
        :class="`is-${group.id}`"
        :aria-labelledby="headed ? headingId(group.id) : undefined"
      >
        <h2 v-if="headed" :id="headingId(group.id)" class="veyq-level-group-heading">{{ t(group.heading) }}</h2>
        <ul class="veyq-level-list" :class="{ 'is-list': mode === 'list' }">
          <LevelRowItem
            v-for="item in group.items"
            :key="item.key"
            :row="item.row"
            :mode="itemMode"
            :kind="item.kind"
            :folder="item.folder"
            :unsupported="item.unsupported"
            :core-actions="coreRowActions(item)"
            :selected="selecting ? selection.has(item.key) : undefined"
            :can-drag="canDrag(item)"
            :dragging="!!dragged?.has(item.key)"
            :drop-target="dropOver === item.key"
            @toggle-select="selection.toggle(item.key)"
            @dragstart="onDragStart($event, item)"
            @dragend="onDragEnd"
            @dragenter="onTargetOver($event, item)"
            @dragover="onTargetOver($event, item)"
            @dragleave="onTargetLeave($event, item)"
            @drop="onTargetDrop($event, item)"
          />
        </ul>
      </component>
    </div>
    <!-- The one cover dialog (inbox #411): opened by the cover action of a row or of the folder's header. -->
    <CoverEditor :cover="cover" />
  </div>
</template>
