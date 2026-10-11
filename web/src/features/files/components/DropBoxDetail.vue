<script setup lang="ts">
// An opened drop box (cr-files-application F3, ADR 0080 decision 11): its guests' identity folders — one per guest
// identity, each named by its guest (its entry opened through the drop box's drop key, core's tree) — with how many
// files each holds. A folder opens to core's level view, read-only there (a member places nothing in a guest's identity
// folder) but for a delete per file (inbox #391); a member deletes a folder here, or the drop box, and shares it
// (`DropBoxShareDialog`) from core's header card, the one a folder opens with (`NodeOverviewCard`, inbox #385 (b)) — each
// where Files' capability matrix says so (`../capabilities.ts`, inbox #401: the share with
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
// guest's Files source carries no delete, and the matrix grants a guest no share and no delete either: it opens a
// folder (core's folder page, read-only inside an identity folder) and downloads there — no send form (that is the
// WRITE_ONLY guest's own page, `DropBoxGuestForm`), nothing deleted or moved; the server refuses each to every guest.
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
import NodeOverviewCard from '../../../core/node/components/NodeOverviewCard.vue'
import { filesCapabilities } from '../capabilities'
import { targetOf, type GuestAccess } from '../../../core/access'
import { guestFilesSource, memberFilesSource, sourceCapabilities, type FilesSource } from '../source'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t } = useI18n()
const { ask } = useConfirm()
const toast = useToast()

// One session for the view's life (see FilesSection): the member's source or the link's.
const guest = props.access.mode === 'guest'
const source: FilesSource = guest
  ? guestFilesSource((): GuestAccess => {
      const a = props.access
      if (a.mode !== 'guest') throw new Error('a guest view was handed a member access')
      return a
    })
  : memberFilesSource({ scopeId: () => props.scopeId, epochs: () => props.epochs, keyOf: (id) => props.tree.keyOf(id) })
const caps = computed(() => sourceCapabilities(filesCapabilities(props.access, targetOf(props.tree, props.node)), source))

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
    header: t('files.dropBox.deleteFolder', { name: label }),
    message: t('files.dropBox.deleteFolderConfirm', { name: label }),
    acceptLabel: t('files.deleteAccept'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  try {
    await deleteFile(folder.id)
  } catch {
    toast.error(t('files.deleteFailed'))
  }
}
</script>

<template>
  <!-- Opens like a folder (inbox #385 (b)): core's header card with the drop box's share and delete, then its folders. -->
  <div class="veyq-level-view">
    <NodeOverviewCard
      icon="pi pi-inbox"
      :name="node.name"
      :share="caps.share"
      :remove="caps.deleteNode"
      :share-label="t('files.dropBox.share')"
      :delete-label="t('files.dropBox.delete')"
      @share="emit('share')"
      @delete="emit('delete')"
    />
    <section class="veyq-section dropbox-detail">
      <div class="veyq-section-header">
        <h2>{{ t('node.folders') }}</h2>
        <span>{{ folders.length }}</span>
      </div>
      <!-- A guest's words: it opens and downloads, only the owner deletes or moves; nobody sent anything (no link to share). -->
      <p>{{ guest ? t('files.dropBox.sharedHint') : t('files.dropBox.readOnlyHint') }}</p>
      <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">{{ guest ? t('files.dropBox.sharedEmpty') : t('files.dropBox.empty') }}</p>
      <ul v-else class="veyq-list">
        <li v-for="row in folders" :key="row.folder.id" class="veyq-list-row">
          <button type="button" class="dropbox-row-open" :aria-label="t('files.dropBox.open', { name: row.label })" @click="emit('openNode', row.folder)">
            <span class="pi pi-folder" aria-hidden="true" />
            <span class="veyq-row-main">
              <span class="veyq-row-title">{{ row.label }}</span>
              <span class="veyq-row-subtitle">{{ t('files.dropBox.guestFiles', row.files) }}</span>
            </span>
          </button>
          <span class="veyq-action-group veyq-action-group-icons">
            <Button
              v-if="caps.deleteFile"
              type="button"
              class="veyq-icon-button veyq-prime-icon-button"
              icon="pi pi-trash"
              :aria-label="t('files.dropBox.deleteFolder', { name: row.label })"
              severity="danger"
              outlined
              size="small"
              @click="removeFolder(row.folder, row.label)"
            />
          </span>
        </li>
      </ul>
    </section>
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
