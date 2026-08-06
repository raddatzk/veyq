<script setup lang="ts">
// Delete a folder (and its subtree) or a Memory (cr-0030 P4). Wired to the E2EE tree data layer.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { deleteNode, type TreeNode } from '../tree'
import { NodeKind } from '../../../api/generated/content'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

// Mounted via v-if while open; closing = the parent unmounting it on `close`.
const props = defineProps<{ scopeId: string; node: TreeNode }>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t } = useI18n()

const error = ref('')
const isFolder = computed(() => props.node.kind === NodeKind.Folder)
const header = computed(() => (isFolder.value ? t('memory.deleteFolderTitle') : t('memory.deleteMemoryTitle')))
const copy = computed(() =>
  isFolder.value
    ? t('memory.deleteFolderCopy', { name: props.node.name })
    : t('memory.deleteMemoryCopy', { name: props.node.name }),
)

const { run: runConfirm, pending: loading } = useAsyncAction(
  async () => {
    await deleteNode(props.scopeId, props.node.id)
    emit('done')
    emit('close')
  },
  { onError: () => { error.value = t('memory.deleteFailed') } },
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
      <Button type="button" :label="t('memory.delete')" icon="pi pi-trash" severity="danger" :loading="loading" @click="confirm" />
    </div>
  </Dialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
