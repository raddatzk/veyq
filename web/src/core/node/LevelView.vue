<script setup lang="ts">
// The folder level view (cr-files-application P4b, ADR 0079): what a member sees at the scope root or inside a folder.
// Core's, and it names no application: in this order it renders the folder's OVERVIEW card (name, share, rename,
// delete — a folder is core's kind since cr-node-key-hierarchy 5b.3), then one SECTION per application as the registry
// lists them (`registry.sections()`, sorted by `order`; a section renders its own `<section>`: heading, create action,
// view modes), then a FALLBACK section per kind of child no section serves (its icon, its name, the explicit
// unsupported state — never a child silently dropped), then the sub-FOLDERS section (heading, folder cards, the
// new-folder card).
//
// **Transitional class names.** The overview card, the folders section and their create card were Memories'
// `MemoryBrowse.vue` markup and keep its `memory-*` classes verbatim, so the Memories level a member meets is
// byte-identical (the P4-0 golden, `views/MemoryHomeView.golden.test.ts`, pins it). P4d moves them to `veyq-*` twins.
//
// **The view mode is the shared one** (`shared/state/useViewMode`): the folders grid takes its list variant from it,
// and the folders section steps aside while the map is shown (Memories' section draws the map, whose pins span the
// whole subtree, folders included). Interim until P4e puts grid/list into a core toolbar.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { TreeNode } from './tree'
import type { Level } from './browse'
import { FOLDER_KIND, type NodeKind } from './kind'
import type { EpochKeys } from '../../features/spaces/epochs'
import { registry } from '../applications/registry'
import { useViewMode } from '../../shared/state/useViewMode'

// `readonly` (cr-0090): the scope is being deleted → no create/rename/delete affordance (every write is server-rejected
// during the grace window, uc.spaces.scope-access; view, share listing and download stay open).
// `shareable`: the host offers a public share of this folder (FOLDER's descriptor AND the instance capability) — the
// button opens core's folder share dialog through the `share` event. Always false at the scope root.
const props = defineProps<{
  folder: TreeNode | null
  level: Level
  epochs: EpochKeys | null
  readonly: boolean
  shareable: boolean
}>()
const emit = defineEmits<{
  openNode: [node: TreeNode]
  /** The KIND to create at this level: FOLDER from the folders section's create card (core's `FolderNameDialog`), a
   *  section's own kind otherwise (that kind's `createDialog`, e.g. Memories' MEMORY). */
  create: [kind: NodeKind]
  renameFolder: []
  deleteFolder: []
  openTrash: []
  share: []
}>()

const { t } = useI18n()
const { viewMode } = useViewMode()

// Read once per mount, like the host's slots: the registry is filled before the first route resolves and never changes.
const sections = registry.sections()
const sectioned = new Set<NodeKind>(sections.flatMap((s) => s.kinds))

// Each section with the children of every kind that names it (kinds in registration order, each bucket already
// ordered by `childrenOf`). A section renders even with no child: it carries its own empty state and create action.
const sectionViews = computed(() =>
  sections.map((s) => ({
    key: s.kinds[0],
    component: s.component,
    nodes: s.kinds.length === 1 ? (props.level.byKind.get(s.kinds[0]) ?? []) : s.kinds.flatMap((k) => props.level.byKind.get(k) ?? []),
  })),
)

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
  <div class="memory-route-surface">
    <section v-if="folder" class="veyq-section memory-section">
      <div class="veyq-section-header">
        <div class="veyq-card memory-folder-card memory-folder-overview-card">
          <span class="pi pi-folder" />
          <h2>{{ folder.name }}</h2>
          <!-- Visible at EVERY width: the card has no overflow menu, so a width-hidden button is a lost action. They
               carried the Memory header's collapse classes, which hid all three at 721–1120 px and ≤ 720 px (found in the
               browser, 2026-10-07); three small icon buttons fit, and ≤ 520 px they take their own row (memory.css). -->
          <div v-if="!readonly || shareable" class="memory-folder-overview-actions">
            <!-- The same affordance as a Memory's share button (icon, label, tooltip); listing and copying links is a
                 read, so it stays during a pending deletion, where a new link is refused server-side anyway. -->
            <Button
              v-if="shareable"
              v-tooltip.bottom="t('share.share')"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action"
              icon="pi pi-share-alt"
              :aria-label="t('share.share')"
              severity="secondary"
              outlined
              size="small"
              @click="emit('share')"
            />
            <Button
              v-if="!readonly"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action"
              icon="pi pi-pencil"
              :aria-label="t('memory.rename')"
              severity="secondary"
              outlined
              size="small"
              @click="emit('renameFolder')"
            />
            <Button
              v-if="!readonly"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button memory-folder-overview-action memory-danger-action"
              icon="pi pi-trash"
              :aria-label="t('memory.delete')"
              severity="danger"
              outlined
              size="small"
              @click="emit('deleteFolder')"
            />
          </div>
        </div>
      </div>
    </section>

    <component
      :is="s.component"
      v-for="s in sectionViews"
      :key="s.key"
      :folder="folder"
      :nodes="s.nodes"
      :epochs="epochs"
      :readonly="readonly"
      @open-node="emit('openNode', $event)"
      @create="emit('create', $event)"
      @open-trash="emit('openTrash')"
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

    <section v-if="viewMode !== 'map'" class="veyq-section memory-section">
      <div class="veyq-section-header memory-section-header">
        <h2>{{ t('memory.folders') }}</h2>
        <span>{{ level.folders.length }}</span>
      </div>
      <div class="memory-card-grid" :class="{ 'is-list': viewMode === 'list' }">
        <p v-if="!level.folders.length" class="veyq-empty veyq-empty-inline">
          {{ t('memory.folderEmpty') }}
        </p>
        <button
          v-for="child in level.folders"
          :key="child.id"
          class="veyq-card veyq-card-interactive memory-folder-card"
          type="button"
          @click="emit('openNode', child)"
        >
          <span class="pi pi-folder" />
          <strong>{{ child.name }}</strong>
        </button>
        <button
          v-if="!readonly"
          class="veyq-card veyq-card-interactive memory-create-card"
          type="button"
          @click="emit('create', FOLDER_KIND)"
        >
          <span class="pi pi-plus" />
          <strong>{{ t('memory.folderCreate') }}</strong>
        </button>
      </div>
    </section>
  </div>
</template>
