<script setup lang="ts">
// The platform's name dialog (cr-files-application P4c): CREATE a folder, or RENAME a node of ANY kind. Core's, because
// both are core operations on the node tree (ADR 0080): a folder is core's kind, and a rename is a placement to the
// same parent whatever the node is — its key re-wraps, its name re-seals under the parent's key, and its slug token is
// derived through `registry.slugFor(node.kind, name)` (`core/node/tree.ts` `slugTokenFor`), so a Files node folds by
// ITS kind's function, never by the folder's. The create dialogs of the application kinds are the kinds' own
// (`KindDescriptor.createDialog`). Moved from Memories' former `NodeMutationDialog.vue` with the same markup, labels,
// validation and error mapping; it reads core's `node.*` keys (and `common.save`) since P4d, values unchanged.
// Inline slug_taken (409) / illegal_structure (422) errors.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { createFolder, renameNode, type NodeParent, type TreeNode } from './tree'
import { treeStore } from './treeStore'
import { resealShareNames } from '../sharing/manageShares'
import { useToast } from '../../shared/state/useToast'
import type { EpochKeys } from '../../features/spaces/epochs'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { httpStatus, HttpStatus } from '../../shared/httpError'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
const props = defineProps<{
  mode: 'create' | 'rename'
  scopeId: string
  epochs: EpochKeys // the scope's epoch key set (ADR 0024): create seals under the write epoch, rename under the node's own
  parentId?: string | null // create: the parent (null = root)
  parentKey?: Uint8Array | null // create: the parent node's content key, which the new folder's key wraps under (ADR 0080)
  node?: TreeNode | null // rename: the node being renamed, of any kind
}>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t } = useI18n()
const toast = useToast()

const name = ref(props.mode === 'rename' ? (props.node?.name ?? '') : '')
const error = ref('')

const header = computed(() => (props.mode === 'rename' ? t('node.renameTitle') : t('node.createFolderTitle')))
const confirmLabel = computed(() => (props.mode === 'rename' ? t('common.save') : t('common.create')))

/** Where a create lands: the scope root (wrapped under the write key), or under the parent with ITS key (ADR 0080
 *  Decision 1: a node inside a folder wraps under the folder's key, never `K_SC`). A parent whose key is missing
 *  creates nothing, rather than a node its parent's holders could not open. */
function createParent(): NodeParent {
  if (!props.parentId) return null
  if (!props.parentKey) throw new Error('parent_key_unavailable')
  return { id: props.parentId, key: props.parentKey }
}

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const trimmed = name.value.trim()
    if (props.mode === 'create') {
      await createFolder(props.scopeId, props.epochs, trimmed, createParent())
    } else if (props.node) {
      // A rename is a placement to the same parent (ADR 0080 Decision 3): the key re-wraps and the name re-seals under
      // the parent's key, resolved from the loaded tree, at the next placement version.
      await renameNode(props.scopeId, props.epochs, treeStore.nodes, props.node.id, trimmed)
      await resealAfterRename(props.node.id, trimmed)
    }
    emit('done')
    emit('close')
  },
  {
    onError: (e) => {
      const status = httpStatus(e)
      error.value = status === HttpStatus.conflict ? t('node.slugTaken') : status === HttpStatus.unprocessable ? t('node.illegalStructure') : t('node.saveFailed')
    },
  },
)
/** The rename stands; now every share rooted at the node — any member's, any kind — gets the new name re-sealed under
 *  its own `K_share` (ADR 0080 decision 14, core's `resealShareNames`). A share it cannot re-seal keeps showing the old
 *  name to its guests: that is said in a toast, never as a failed rename (the dialog closes either way). */
async function resealAfterRename(nodeId: string, newName: string): Promise<void> {
  try {
    const report = await resealShareNames(nodeId, newName, props.epochs)
    if (report.failed.length + report.skipped.length > 0) toast.error(t('share.nameResealFailed'))
  } catch {
    toast.error(t('share.nameResealFailed'))
  }
}
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}
</script>

<template>
  <Dialog :visible="true" :header="header" modal :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <InputText v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="loading" @click="emit('close')" />
        <Button type="submit" :label="confirmLabel" icon="pi pi-check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>
</template>
