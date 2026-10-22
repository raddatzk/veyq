<script setup lang="ts">
// Delete a node (a folder with its subtree, or any application's node) (cr-0030 P4). Wired to the E2EE tree data layer.
// Core serves every application's nodes, so its own strings name none (inbox #375): a folder has its own title and copy,
// any other node takes its title from its kind's `deleteTitle` in the registry (Memories: "Delete Memory") and falls
// back to the neutral "Delete" for a kind that registered none, or that nobody registered; its sentence likewise from
// the kind's `deleteCopy` (a drop box names its guests' folders and links, inbox #395), else the neutral one.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
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
  <VeyqDialog :visible="true" :header="header" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <p class="dialog-copy">{{ copy }}</p>
    <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
    <div class="veyq-dialog-action-row">
      <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
      <VeyqButton :label="t('node.delete')" icon="trash" variant="danger" :loading="loading" @click="confirm" />
    </div>
  </VeyqDialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
