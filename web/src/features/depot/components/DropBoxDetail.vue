<script setup lang="ts">
// An opened drop box (cr-files-application F3, ADR 0080 decision 11): its guests' identity folders — one per guest
// identity, each named by its guest (its entry opened through the drop box's drop key, core's tree) — with how many
// files each holds. A folder opens to core's level view, read-only there (a member places nothing in a guest's identity
// folder) but for a delete per file (inbox #391); a member deletes a folder here, or the drop box, and shares it
// (`DropBoxShareDialog`) from core's header card, the one a folder opens with (`NodeHeader`, inbox #385 (b), #406) — each
// where Depot's capability matrix says so (`../capabilities.ts`, inbox #401: the share with
// the instance's public links, the deletes not during a pending deletion). Two guests of one name are told apart at
// display time, never stored ("Anna", "Anna (2)",
// in the order the server made their folders, a same-millisecond pair by id) — core's `displayNamesOf` in
// `compareByCreation` order, the order the URL segments `anna`, `anna-2` of the same two folders come from
// (`core/node/location.ts` `segmentsOf`), and the label the folder's own heading and breadcrumb show (inbox #393).
//
// **A guest of a folder link above the drop box sees this one view too** (inbox #401 S3; owner decision 2026-10-08,
// inbox #380: it was given that tree): core's folder page renders it as the drop box's `detail`, handed the guest's
// `access` and `tree`, whose walk opened the guests' folders through the drop key (`childEntryKey`). The guest's tree
// serves no creation time (`createdAt` 0), so `compareByCreation` falls to its node-id tie-break: a guest numbers equal
// names in node-id order, which can differ from the member's creation order (documented in docs/apps/files.md). A
// guest's Depot source carries no delete, and the matrix grants a guest no share and no delete either: it opens a
// folder (core's folder page, read-only inside an identity folder) and downloads there — no send form (that is the
// WRITE_ONLY guest's own page, `DropBoxGuestForm`), nothing deleted or moved; the server refuses each to every guest.
//
// **A member moves a guest's folder out** (inbox #358, ADR 0080 decision 11 — the one placement an identity folder takes):
// the row's move asks the host for core's `MoveNodeDialog`, which asks first (the folder becomes an ordinary one, its
// guest can add nothing more, and nothing moves back in) and offers only places outside every drop box. The drop box
// itself moves from the header card. A guest's source carries no move here (a drop box is read-only for it, #380).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { NodeDetailEmits, NodeDetailProps } from '../../../core/applications/registry'
import type { TreeNode } from '../../../core/node/tree'
import { FOLDER_KIND } from '../../../core/node/kind'
import { compareByCreation, displayNamesOf } from '../../../core/node/location'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { FILE_KIND } from '../kinds'
import NodeHeader from '../../../core/node/components/NodeHeader.vue'
import { standardNodeActions } from '../../../core/node/nodeHeader'
import { favoriteHeaderActions } from '../../../core/favorites/favoriteActions'
import { downloadHeaderActions } from '../../../core/export/downloadActions'
import { copyHeaderActions } from '../../../core/export/copyActions'
import { useNodeCover } from '../../../core/node/cover/useNodeCover'
import NodeCoverImage from '../../../core/node/cover/components/NodeCoverImage.vue'
import CoverEditor from '../../../core/node/cover/components/CoverEditor.vue'
import { useTrashUndo } from '../../../core/node/trashUndo'
import { depotCapabilities } from '../capabilities'
import { targetOf, type GuestAccess } from '../../../core/access'
import { guestDepotSource, memberDepotSource, sourceCapabilities, type DepotSource } from '../source'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t } = useI18n()
// Core's heart (inbox #409) and cover (inbox #411): the same for every kind that carries one; a guest gets neither action.
// A drop box's cover is its own image (core's picker), shown in the header's leading slot; the inbox icon is the default.
const cover = useNodeCover(() => props.access, () => props.tree, () => [props.node])
// …and core's Download (cr-download-is-the-export Decision 7): the drop box as a ZIP of its guests' folders, for a member and a READ
// or READ_WRITE guest of a folder link above it - never its own WRITE_ONLY guest, who holds no key (core's `canDownload`).
const coreActions = computed(() => [
  ...favoriteHeaderActions(t, props.access, props.node),
  ...cover.headerActions(props.node),
  ...copyHeaderActions(t, props.access, props.node, () => emit('copy')),
  ...downloadHeaderActions(t, props.access, props.node, () => emit('download')),
])
const nodeCover = computed(() => cover.covers.value.get(props.node.id) ?? null)
const { ask } = useConfirm()
const toast = useToast()
const offerUndo = useTrashUndo()

// One session for the view's life (see `../levelRows.ts`): the member's source or the link's.
const guest = props.access.mode === 'guest'
const source: DepotSource = guest
  ? guestDepotSource((): GuestAccess => {
      const a = props.access
      if (a.mode !== 'guest') throw new Error('a guest view was handed a member access')
      return a
    })
  : memberDepotSource({
      scopeId: () => props.scopeId,
      epochs: () => props.epochs,
      keyOf: (id) => props.tree.keyOf(id),
      move: (node) => emit('move', node),
    })
const caps = computed(() => sourceCapabilities(depotCapabilities(props.access, targetOf(props.tree, props.node)), source))
/** The header's actions: share, move, delete — each only where the capability matrix grants it (a drop box is never renamed here). */
const headerActions = computed(() =>
  standardNodeActions(
    t,
    { share: caps.value.share, move: caps.value.moveNode, remove: caps.value.deleteNode, shareLabel: t('depot.dropBox.share'), deleteLabel: t('depot.dropBox.delete') },
    { share: () => emit('share'), move: () => source.move?.(props.node), remove: () => emit('delete') },
  ),
)

/** The guests' folders, each with its display name (a repeated name numbered) and its file count. */
const folders = computed(() => {
  const own = props.tree
    .children(props.node.id)
    .filter((n) => n.kind === FOLDER_KIND)
    .sort(compareByCreation)
  // Core's one numbering (the folder's heading and breadcrumb say the same, inbox #393).
  const labels = displayNamesOf(own, (n) => n.name, compareByCreation)
  return own.map((folder) => {
    const files = props.tree.children(folder.id).filter((c) => c.kind === FILE_KIND).length
    return { folder, label: labels.get(folder.id) ?? folder.name, files }
  })
})

async function removeFolder(folder: TreeNode, label: string) {
  const deleteFile = source.deleteFile
  if (!deleteFile || !caps.value.deleteFile) return
  const accepted = await ask({
    header: t('depot.dropBox.deleteFolder', { name: label }),
    message: t('depot.dropBox.deleteFolderConfirm', { name: label }),
    acceptLabel: t('depot.deleteAccept'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  try {
    await deleteFile(folder.id)
  } catch {
    toast.error(t('depot.deleteFailed'))
    return
  }
  const restore = source.restore
  if (restore) offerUndo(label, () => restore(folder)) // the undo after a delete (inbox #385 (c))
}
</script>

<template>
  <!-- Opens like a folder (inbox #385 (b)): core's `NodeHeader` with the drop box's share and delete, then its folders. -->
  <div class="veyq-level-view">
    <NodeHeader icon="pi pi-inbox" :title="node.name" :level="1" :actions="headerActions" :core-actions="coreActions">
      <template v-if="nodeCover" #leading>
        <span class="veyq-node-header-cover"><NodeCoverImage :src="nodeCover.url" :crop="nodeCover.crop" /></span>
      </template>
    </NodeHeader>
    <section class="veyq-section dropbox-detail">
      <div class="veyq-section-header">
        <h2>{{ t('node.folders') }}</h2>
        <span>{{ folders.length }}</span>
      </div>
      <!-- A guest's words: it opens and downloads, only the owner deletes or moves; nobody sent anything (no link to share). -->
      <p>{{ guest ? t('depot.dropBox.sharedHint') : t('depot.dropBox.readOnlyHint') }}</p>
      <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">{{ guest ? t('depot.dropBox.sharedEmpty') : t('depot.dropBox.empty') }}</p>
      <ul v-else class="veyq-list">
        <li v-for="row in folders" :key="row.folder.id" class="veyq-list-row">
          <button type="button" class="dropbox-row-open" :aria-label="t('depot.dropBox.open', { name: row.label })" @click="emit('openNode', row.folder)">
            <span class="pi pi-folder" aria-hidden="true" />
            <span class="veyq-row-main">
              <span class="veyq-row-title">{{ row.label }}</span>
              <span class="veyq-row-subtitle">{{ t('depot.dropBox.guestFiles', row.files) }}</span>
            </span>
          </button>
          <span class="veyq-action-group veyq-action-group-icons">
            <Button
              v-if="caps.moveFile"
              v-tooltip.bottom="t('depot.dropBox.moveFolder', { name: row.label })"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-file-export"
              :aria-label="t('depot.dropBox.moveFolder', { name: row.label })"
              severity="secondary"
              outlined
              size="small"
              @click="source.move?.(row.folder)"
            />
            <Button
              v-if="caps.deleteFile"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-trash"
              :aria-label="t('depot.dropBox.deleteFolder', { name: row.label })"
              severity="danger"
              outlined
              size="small"
              @click="removeFolder(row.folder, row.label)"
            />
          </span>
        </li>
      </ul>
    </section>
    <CoverEditor :cover="cover" />
  </div>
</template>

<style scoped>
.dropbox-row-open {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.dropbox-row-open:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
  border-radius: var(--app-radius-sm);
}
</style>
