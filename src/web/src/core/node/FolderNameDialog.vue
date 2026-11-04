<script setup lang="ts">
// The platform's name dialog (cr-files-application P4c): CREATE a folder, or RENAME a node of ANY kind. Core's, because
// both are core operations on the node tree (ADR 0080): a folder is core's kind, and a rename is a placement to the
// same parent whatever the node is — its key re-wraps, its name re-seals under the parent's key, and its slug token is
// derived through `registry.tokenInputFor(node.kind, name)` (`core/node/tree.ts` `slugTokenFor`), so a Depot node folds by
// ITS kind's function, never by the folder's. The create dialogs of the application kinds are the kinds' own
// (`KindDescriptor.createDialog`). Moved from Memories' former `NodeMutationDialog.vue` with the same markup, labels,
// validation and error mapping; it reads core's `node.*` keys (and `common.save`) since P4d, values unchanged.
// Inline slug_taken (409) / illegal_structure (422) errors. Before sending, a name that collides with a sibling in the
// loaded tree is refused naming that sibling (`siblingCollision.ts`, inbox #389); the 409 stays the race backstop. The
// check, the rename write and the wording of a refusal are `nodeName.ts`'s, which the header's rename in place shares
// (ADR 0110 decision 11): one rule for both ways in.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { createFolder, type NodeParent, type TreeNode } from './tree'
import { treeStore, loadTree } from './treeStore'
import { memberCreateOptions } from './rereadNode'
import { FOLDER_KIND } from './kind'
import { nodeNameErrorText, refuseTakenName, renameMemberNode, renameSelectionEnd } from './nodeName'
import { useToast } from '../../shared/state/useToast'
import type { ScopeKey } from '../../features/spaces/epochs'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

// Mounted via v-if while open; closing = the parent unmounting it on `close` (robust — no two-way `visible`).
const props = defineProps<{
  mode: 'create' | 'rename'
  scopeId: string
  epochs: ScopeKey // the scope's epoch key set (ADR 0024): create seals under the write epoch, rename under the node's own
  parentId?: string | null // create: the parent (null = root)
  parentKey?: Uint8Array | null // create: the parent node's content key, which the new folder's key wraps under (ADR 0080)
  parentEpoch?: number | null // create: the parent's own epoch as read, stated as `parentEpoch` (ADR 0114 decision 3)
  parentScopeEpoch?: number | null // create: the scope epoch the parent's key was minted under, the floor's (ADR 0115)
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
  if (!props.parentKey || props.parentEpoch == null || props.parentScopeEpoch == null) throw new Error('parent_key_unavailable')
  return { id: props.parentId, key: props.parentKey, epoch: props.parentEpoch, scopeEpoch: props.parentScopeEpoch }
}

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const trimmed = name.value.trim()
    if (props.mode === 'create') {
      const parent = createParent()
      await createFolder(props.scopeId, props.epochs, trimmed, parent, memberCreateOptions(props.scopeId, parent))
      await loadTree()
    } else if (props.node) {
      // A rename (`renameMemberNode`): the placement write, every share rooted at the node re-sealed with the new name (one it
      // cannot re-seal keeps showing the old name to its guests: said in a toast, never as a failed rename), the tree re-read.
      const { resealed } = await renameMemberNode(props.scopeId, props.epochs, props.node, trimmed)
      if (!resealed) toast.error(t('share.nameResealFailed'))
    }
    // The tree is re-read BEFORE the dialog closes, as the move's is (`move.ts` `memberMover`): `done` means the write
    // stands AND the loaded tree carries it, so the host follows the node on screen to its new path in the same step
    // (`SpaceView` `stayOn`). Closed first, the dialog was gone for one tree read while the URL still held the old
    // path — a reload in that window resolved nothing and stood on the scope root (inbox #475). `loadTree` never throws.
    emit('done')
    emit('close')
  },
  { onError: (e) => void (error.value = nodeNameErrorText(t, e)) },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !loading.value)

/** A rename selects what its kind says a rename usually changes (`KindDescriptor.renameSelectionEnd`: a file's name
 *  without its extension), once, as the dialog opens — on its `show`, BEFORE it focuses the field: a programmatic focus
 *  keeps a selection already set, and a focus event is not to be relied on (a window without system focus fires none,
 *  measured in the browser pane). */
const nameInput = ref<{ $el: HTMLElement } | null>(null)
function selectForRename() {
  if (props.mode !== 'rename' || !props.node) return
  const end = renameSelectionEnd(props.node, name.value)
  const el = nameInput.value?.$el
  const input = el instanceof HTMLInputElement ? el : el?.querySelector('input')
  if (end !== undefined && input) input.setSelectionRange(0, end)
}

/** Refuse a name a loaded sibling has, folded by each node's own kind: a create is a FOLDER under the parent, a rename is
 *  the node's own kind under its own parent, never colliding with itself (the rename write checks it again). */
function refuseTaken(): void {
  if (props.mode === 'create') refuseTakenName(treeStore.nodes, props.parentId ?? null, FOLDER_KIND, name.value)
  else if (props.node) refuseTakenName(treeStore.nodes, props.node.parentId, props.node.kind, name.value, props.node.id)
}

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  try {
    refuseTaken()
  } catch (e) {
    error.value = nodeNameErrorText(t, e)
    return
  }
  await runSubmit()
}
</script>

<template>
  <VeyqDialog :visible="true" :header="header" :closable="!loading" :close-on-escape="!loading" @update:visible="emit('close')" @show="selectForRename">
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('node.nameLabel') }}
        <VeyqInput ref="nameInput" v-model="name" autocomplete="off" required autofocus :disabled="loading" />
      </label>

      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="loading" @click="emit('close')" />
        <VeyqButton type="submit" :label="confirmLabel" icon="check" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>
  </VeyqDialog>
</template>
