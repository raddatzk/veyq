<script setup lang="ts">
// Delete a node (a folder with its subtree, or any application's node) (cr-0030 P4). Wired to the E2EE tree data layer.
// Core serves every application's nodes, so its own strings name none (inbox #375): a folder has its own title and copy,
// any other node takes its title from its kind's `deleteTitle` in the registry (Memories: "Delete Memory") and falls
// back to the neutral "Delete" for a kind that registered none, or that nobody registered; its sentence likewise from
// the kind's `deleteCopy` (a drop box names its guests' folders and links, inbox #395), else the neutral one.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { deleteNode, type TreeNode } from '../tree'
import { FOLDER_KIND } from '../kind'
import { registry } from '../../applications/registry'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

// Mounted via v-if while open; closing = the parent unmounting it on `close`.
const props = defineProps<{ scopeId: string; node: TreeNode }>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t } = useI18n()

const error = ref('')
const isFolder = computed(() => props.node.kind === FOLDER_KIND)
const header = computed(() => {
  if (isFolder.value) return t('node.deleteFolderTitle')
  const info = registry.kind(props.node.kind)
  return t(info.supported && info.deleteTitle ? info.deleteTitle : 'node.delete')
})
const copy = computed(() => {
  if (isFolder.value) return t('node.deleteFolderCopy', { name: props.node.name })
  const info = registry.kind(props.node.kind)
  return t(info.supported && info.deleteCopy ? info.deleteCopy : 'node.deleteCopy', { name: props.node.name })
})

const { run: runConfirm, pending: loading } = useAsyncAction(
  async () => {
    await deleteNode(props.scopeId, props.node.id)
    emit('done')
    emit('close')
  },
  { onError: () => { error.value = t('node.deleteFailed') } },
)

async function confirm() {
  error.value = ''
  await runConfirm()
}
</script>

<template>
  <Dialog :visible="true" :header="header" modal :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <p class="dialog-copy">{{ copy }}</p>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div class="veyq-dialog-action-row">
      <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="loading" @click="emit('close')" />
      <Button type="button" :label="t('node.delete')" icon="pi pi-trash" severity="danger" :loading="loading" @click="confirm" />
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
