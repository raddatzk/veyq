<script setup lang="ts">
// The folder level view (cr-files-application P4b, ADR 0079): what a member sees at the scope root or inside a folder.
// Core's, and it names no application: in this order it renders its TOOLBAR (the space's node trash, which holds every
// application's nodes, and the view-mode switch — both were Memories' section's until Files needed them too, inbox
// #370/#385), the folder's OVERVIEW card (name, share, rename,
// move, delete — a folder is core's kind since cr-node-key-hierarchy 5b.3; the move is core's `MoveNodeDialog`, which
// the host mounts on `moveNode`, inbox #358), then one SECTION per application as the registry
// lists them (`registry.sections()`, sorted by `order`; a section renders its own `<section>`: heading, create action,
// view modes), then a FALLBACK section per kind of child no section serves (its icon, its name, the explicit
// unsupported state — never a child silently dropped), then the sub-FOLDERS section (heading, folder cards, the
// new-folder card).
//
// **Core's class names and keys.** The overview card, the folders section and their create card were Memories'
// `MemoryBrowse.vue` markup; since cr-files-application P4d they carry core `veyq-*` classes (`styles/ui.css`, end of
// sheet, with their dark twins in `dark.css`: the same declarations the `memory-*` rules had) and read core's `node.*`
// keys. The P4-0 golden (`views/MemoryHomeView.golden.test.ts`) pins the DOM: P4d changed it in class names only.
//
// **The view mode is the shared one** (`shared/state/useViewMode`), but the LEVEL decides which mode it shows (inbox
// #404): the stored preference where something on screen offers it, else the grid (`effectiveViewMode`) — the stored
// value stays as it is, so the next level that offers it shows it again. The level hands that mode to every section
// (`LevelSectionProps.viewMode`); no section reads the stored value. The folders grid takes its list variant from it,
// and the folders section steps aside while the map is shown (Memories' section draws the map, whose pins span the
// whole subtree, folders included). A stored map where no shown section offers one (a guest's identity folder, whose
// empty Memories section is left out) is the grid, not a blank level. The switch is this toolbar's (inbox #370): it
// offers the union of the presentations on screen — the folders' grid and list (`FOLDER_VIEWS`) and each shown
// section's `views` from its kind's descriptor (Memories: grid, list, map) — and only where there is more than one to
// choose from.
//
// **Who looks** (inbox #401): the host hands the level its `access` and its `tree` (`core/access.ts`), and the level hands
// both on to every section; what the level's own controls offer is core's `folderCapabilities` for the folder on screen
// — no write during a pending deletion, none in a guest's identity folder, a share only of a folder.
//
// The share guest's folder page renders this very level (`core/sharing/ShareTreePage.vue`, inbox #401): every
// section, fallback and the folders are this view's for a member and a guest alike, the guest's functions removed by the
// capability functions and the sections' data sources.
//
// In a guest's identity folder (a drop box's, inbox #385) a section, or the folders section, with nothing to show is
// left out instead of saying "none yet" — nobody can add anything there, so an empty heading only says that this kind
// exists. When nothing is left, the level says so once.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from './tree'
import type { Level } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import { registry } from '../applications/registry'
import { folderCapabilities, targetOf, type Access, type TreeSource } from '../access'
import { useViewMode } from '../../shared/state/useViewMode'
import { compareByCreation, displayNamesOf } from './location'
import { FOLDER_VIEWS, effectiveViewMode, unionViews } from './levelViews'
import ViewModeToggle from './ViewModeToggle.vue'
import NodeOverviewCard from './components/NodeOverviewCard.vue'

const props = defineProps<{
  folder: TreeNode | null
  level: Level
  /** Who looks (`core/access.ts`): what the level offers, and what every section is handed. */
  access: Access
  /** The tree as that session reads it; handed to every section. */
  tree: TreeSource
  /** The folder's name as its siblings tell it apart (`displayNamesOf`: a guest's folder `Anna (2)` beside `Anna`,
   *  inbox #393), when the host knows the siblings. Omitted: the folder's own name. */
  folderName?: string
}>()
const emit = defineEmits<{
  openNode: [node: TreeNode]
  /** The KIND to create at this level: FOLDER from the folders section's create card (core's `FolderNameDialog`), a
   *  section's own kind otherwise (that kind's `createDialog`, e.g. Memories' MEMORY). */
  create: [kind: NodeKind]
  renameFolder: []
  /** A node to move (inbox #358): the folder on screen (its card) or a section's child (Files' row). The host mounts
   *  core's `MoveNodeDialog` for it. */
  moveNode: [node: TreeNode]
  /** A section's child renamed in place (a kind that does not open, e.g. Files' file): the host's `FolderNameDialog`. */
  renameNode: [node: TreeNode]
  deleteFolder: []
  openTrash: []
  share: []
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()

const inIdentityFolder = computed(() => props.tree.inIdentityFolder(props.folder?.id ?? null))
const caps = computed(() => folderCapabilities(props.access, targetOf(props.tree, props.folder)))
const hideEmptySections = computed(() => inIdentityFolder.value)
// The member's epoch keys, for the sections' sealed reads; a guest's keys come through the tree.
const epochs = computed(() => (props.access.mode === 'member' ? props.access.epochs : null))

// Read once per mount, like the host's slots: the registry is filled before the first route resolves and never changes.
const sections = registry.sections()
const sectioned = new Set<NodeKind>(sections.flatMap((s) => s.kinds))

// Each section with the children of every kind that names it (kinds in registration order, each bucket already
// ordered by `childrenOf`). A section renders even with no child: it carries its own empty state and create action.
const sectionViews = computed(() =>
  sections
    .map((s) => ({
      key: s.kinds[0],
      component: s.component,
      views: s.views,
      nodes: s.kinds.length === 1 ? (props.level.byKind.get(s.kinds[0]) ?? []) : s.kinds.flatMap((k) => props.level.byKind.get(k) ?? []),
    }))
    .filter((s) => !hideEmptySections.value || s.nodes.length > 0),
)
const showFolders = computed(() => !hideEmptySections.value || props.level.folders.length > 0)
// The sub-folders' card labels: a repeated name numbered, in creation order — the rule the URL segments follow too.
const folderLabels = computed(() => displayNamesOf(props.level.folders, (n) => n.name, compareByCreation))
// The presentations of what is on screen, in the order first named; a switch only between two or more.
const views = computed(() => unionViews([...sectionViews.value.map((s) => s.views), showFolders.value ? FOLDER_VIEWS : []]))
const showViews = computed(() => views.value.length > 1)
// The mode this level shows: the stored one clamped to what is on screen (inbox #404); the stored one is not touched.
const mode = computed(() => effectiveViewMode(viewMode.value, views.value))
const nothingShown = computed(() => hideEmptySections.value && !sectionViews.value.length && !fallbacks.value.length && !showFolders.value)

// The children no section serves, one bucket per kind, sorted by kind so the order is stable. A kind the registry
// does not know at all still shows (with a generic icon): the server stores any kind.
const fallbacks = computed(() =>
  [...props.level.byKind.entries()]
    .filter(([kind, nodes]) => !sectioned.has(kind) && nodes.length > 0)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([kind, nodes]) => {
      const info = registry.kind(kind)
      return { kind, icon: info.supported ? info.icon : 'pi pi-question-circle', nodes }
    }),
)
</script>

<template>
  <div class="veyq-level-view">
    <!-- The space's node trash (uc.memory.delete-node): every application's deleted nodes, so the level view's own, not
         a section's. Hidden where nothing is written (a pending deletion: a restore is a write; a guest's folder). The
         view switch governs every section and the folders, so it is the level's too — a guest's as well. -->
    <div v-if="caps.nodeTrash || showViews" class="veyq-level-toolbar">
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
    <!-- The folder's header card, core's one (`NodeOverviewCard`): a container kind's detail opens with it too. -->
    <NodeOverviewCard
      v-if="folder"
      icon="pi pi-folder"
      :name="folderName ?? folder.name"
      :share="caps.share"
      :rename="caps.rename"
      :remove="caps.deleteNode"
      :move="caps.move"
      @share="emit('share')"
      @rename="emit('renameFolder')"
      @move="emit('moveNode', folder)"
      @delete="emit('deleteFolder')"
    />

    <component
      :is="s.component"
      v-for="s in sectionViews"
      :key="s.key"
      :folder="folder"
      :nodes="s.nodes"
      :epochs="epochs"
      :access="access"
      :tree="tree"
      :view-mode="mode"
      @open-node="emit('openNode', $event)"
      @create="emit('create', $event)"
      @rename="emit('renameNode', $event)"
      @move="emit('moveNode', $event)"
    />

    <!-- A kind no section serves: listed, never dropped, and said plainly that this client cannot show it. Not
         clickable — opening one lands on the host's same unsupported state. -->
    <section v-for="b in fallbacks" :key="b.kind" class="veyq-section veyq-level-fallback">
      <div class="veyq-section-header">
        <h2>{{ b.kind }}</h2>
        <span>{{ b.nodes.length }}</span>
      </div>
      <div class="veyq-card-grid">
        <div v-for="n in b.nodes" :key="n.id" class="veyq-card veyq-node-card veyq-node-card-unsupported">
          <span :class="b.icon" />
          <span class="veyq-node-card-text">
            <strong>{{ n.name }}</strong>
            <small>{{ t('home.unsupportedKind') }}</small>
          </span>
        </div>
      </div>
    </section>

    <p v-if="nothingShown" class="veyq-empty veyq-empty-inline">{{ t('node.levelEmpty') }}</p>

    <section v-if="mode !== 'map' && showFolders" class="veyq-section">
      <div class="veyq-section-header veyq-level-section-header">
        <h2>{{ t('node.folders') }}</h2>
        <span>{{ level.folders.length }}</span>
      </div>
      <div class="veyq-card-grid veyq-level-grid" :class="{ 'is-list': mode === 'list' }">
        <p v-if="!level.folders.length" class="veyq-empty veyq-empty-inline">
          {{ t('node.folderEmpty') }}
        </p>
        <button
          v-for="child in level.folders"
          :key="child.id"
          class="veyq-card veyq-card-interactive veyq-folder-card"
          type="button"
          @click="emit('openNode', child)"
        >
          <span class="pi pi-folder" aria-hidden="true" />
          <strong>{{ folderLabels.get(child.id) ?? child.name }}</strong>
        </button>
        <button
          v-if="caps.createFolder"
          class="veyq-card veyq-card-interactive veyq-create-card"
          type="button"
          :aria-label="t('node.folderCreate')"
          @click="emit('create', FOLDER_KIND)"
        >
          <span class="pi pi-plus" aria-hidden="true" />
          <strong>{{ t('node.folderCreate') }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>
