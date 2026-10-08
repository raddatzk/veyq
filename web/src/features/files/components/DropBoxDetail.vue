<script setup lang="ts">
// An opened drop box (cr-files-application F3, ADR 0080 decision 11): its guests' identity folders — one per guest
// identity, each named by its guest (its entry opened through the drop box's drop key, core's tree) — with how many
// files each holds. A folder opens to core's level view, read-only there (a member places nothing in a drop box: the
// host's `readonly` for a node inside an identity folder); a member deletes a folder here, or the drop box, and shares
// it (`DropBoxShareDialog`). Two guests of one name are told apart at display time, never stored ("Anna", "Anna (2)",
// in node-id order: no creation time is listed) — core's `compareNodeIds`, the order the URL segments `anna`, `anna-2`
// of the same two folders come from (`core/node/location.ts` `segmentsOf`).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import type { NodeDetailEmits, NodeDetailProps } from '../../../core/applications/registry'
import { deleteNode, type TreeNode } from '../../../core/node/tree'
import { treeStore, loadTree } from '../../../core/node/treeStore'
import { FOLDER_KIND } from '../../../core/node/kind'
import { compareNodeIds } from '../../../core/node/location'
import { useConfirm } from '../../../shared/state/useConfirm'
import { useToast } from '../../../shared/state/useToast'
import { FILE_KIND } from '../kinds'

const props = defineProps<NodeDetailProps>()
const emit = defineEmits<NodeDetailEmits>()
const { t } = useI18n()
const { ask } = useConfirm()
const toast = useToast()

/** The guests' folders, each with its display name (a repeated name numbered) and its file count. */
const folders = computed(() => {
  const own = treeStore.nodes
    .filter((n) => n.parentId === props.node.id && n.kind === FOLDER_KIND)
    .sort(compareNodeIds)
  const seen = new Map<string, number>()
  return own.map((folder) => {
    const n = (seen.get(folder.name) ?? 0) + 1
    seen.set(folder.name, n)
    const files = treeStore.nodes.filter((c) => c.parentId === folder.id && c.kind === FILE_KIND).length
    return { folder, label: n === 1 ? folder.name : `${folder.name} (${n})`, files }
  })
})

async function removeFolder(folder: TreeNode, label: string) {
  const accepted = await ask({
    header: t('files.dropBox.deleteFolder', { name: label }),
    message: t('files.dropBox.deleteFolderConfirm', { name: label }),
    acceptLabel: t('files.deleteAccept'),
    acceptIcon: 'pi pi-trash',
  })
  if (!accepted) return
  try {
    await deleteNode(props.scopeId, folder.id)
    await loadTree()
  } catch {
    toast.error(t('files.deleteFailed'))
  }
}
</script>

<template>
  <section class="veyq-section dropbox-detail">
    <div class="veyq-section-header">
      <h2><span class="pi pi-inbox" aria-hidden="true" /> {{ node.name }}</h2>
      <span>{{ folders.length }}</span>
      <div class="dropbox-toolbar">
        <Button v-if="shareable" type="button" :label="t('files.dropBox.share')" icon="pi pi-share-alt" size="small" @click="emit('share')" />
        <Button type="button" :label="t('files.dropBox.delete')" icon="pi pi-trash" severity="danger" outlined size="small" @click="emit('delete')" />
      </div>
    </div>
    <p>{{ t('files.dropBox.readOnlyHint') }}</p>
    <p v-if="!folders.length" class="veyq-empty veyq-empty-inline">{{ t('files.dropBox.empty') }}</p>
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
</template>

<style scoped>
.dropbox-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-left: auto;
}
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
