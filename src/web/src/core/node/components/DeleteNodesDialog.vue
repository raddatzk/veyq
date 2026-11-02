<script setup lang="ts">
// Core's delete question for a level's SELECTION (inbox #421, uc.memory.delete-node step 1): ONE question for the deletable
// part of the selection, naming how many go and of which kinds (each kind's `nounWithArticle`, as core's other dialogs name
// a node they cannot name by kind - core names no application kind), in place of the per-node questions. ONE node - a row's
// default delete (`standardRowActions.ts`), a selection of one, or a page's own node from its header (the folder on screen,
// an opened node: the member's host) - is asked that node's question instead (`deleteQuestion.ts`), with this dialog's Undo.
// A kind's own delete flow (an action of id `delete` in its row, none today) is the application's.
//
// The write is `deleteSelection.ts`: each node through the door its single delete uses (core's `deleteNode`, or the kind's
// own `remove`), one after the other, the tree re-read once. Then one toast with ONE Undo that restores exactly the nodes
// that went (`restoreSelection`, core's trash restore); where a node was not deleted the dialog stays open on one summary
// naming each with its reason, else it closes. Member only: no share role deletes a node, so no guest's level offers it.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import type { TreeNode } from '../tree'
import { registry } from '../../applications/registry'
import { deleteQuestion } from '../deleteQuestion'
import { joinList, type SelectedForDelete } from '../selection'
import { deleteRefusalOf, deleteSelection, restoreSelection } from '../deleteSelection'
import { loadTree, treeStore } from '../treeStore'
import { TRASH_UNDO_MS } from '../trashUndo'
import type { EpochKeys } from '../../../features/spaces/epochs'
import { useToast } from '../../../shared/state/useToast'

const props = defineProps<{
  scopeId: string
  epochs: EpochKeys
  /** The deletable part of the selection, each with its kind's own door where it has one. */
  items: readonly SelectedForDelete[]
  /** Each node's name as its siblings tell it apart; omitted: its own. */
  nameOf?: (node: TreeNode) => string
  /** Called after the toast's Undo restored them - the dialog is long closed then. */
  afterUndo?: () => void
}>()
const emit = defineEmits<{ done: []; close: [] }>()
const { t, locale } = useI18n()
const toast = useToast()

// Read now: the summary and the undo run after the tree moved on.
const names = new Map(props.items.map(({ node }) => [node.id, props.nameOf?.(node) || node.name]))
const named = (n: TreeNode) => names.get(n.id) ?? n.name
const quoted = (ns: readonly TreeNode[]) => joinList(locale.value, ns.map((n) => t('node.deleteMany.quoted', { name: named(n) })))

/** The kinds that go, each once, in the order of the selection: "a folder, a Memory and a file". */
const kinds = computed(() => {
  const nouns: string[] = []
  for (const { node } of props.items) {
    const info = registry.kind(node.kind)
    const noun = t((info.supported && info.nounWithArticle) || 'node.anItem')
    if (!nouns.includes(noun)) nouns.push(noun)
  }
  return joinList(locale.value, nouns)
})

/** The title and sentence: one node's own question when it is one, else the selection's. */
const question = computed(() => {
  const only = props.items.length === 1 ? props.items[0]!.node : null
  return only
    ? deleteQuestion(t, only, named(only))
    : { header: t('node.deleteMany.title', { count: props.items.length }), copy: t('node.deleteMany.copy', { count: props.items.length, kinds: kinds.value }) }
})

const busy = ref(false)
const summary = ref<{ deleted: string; lines: string[] } | null>(null)

async function confirm() {
  if (busy.value) return
  busy.value = true
  const outcomes = await deleteSelection(props.scopeId, props.items, loadTree).finally(() => (busy.value = false))
  const gone = outcomes.filter((o) => o.error === null).map((o) => o.node)
  const lines: string[] = []
  for (const o of outcomes) {
    if (o.error !== null) lines.push(t('node.deleteMany.reason', { name: named(o.node), reason: t(`node.deleteMany.refused.${await deleteRefusalOf(o.error)}`) }))
  }
  const deletedText = gone.length > 0 ? t('node.deleteMany.moved', { count: gone.length }, gone.length) : t('node.deleteMany.noneDeleted')
  if (gone.length > 0) {
    const { scopeId, epochs, afterUndo } = props
    toast.showUndo({
      summary: deletedText,
      life: TRASH_UNDO_MS,
      onUndo: () => {
        restoreSelection(scopeId, epochs, gone, () => treeStore.nodes, loadTree).then(
          (back) => {
            const failed = back.filter((o) => o.error !== null).map((o) => o.node)
            if (failed.length > 0) toast.error(t('node.deleteMany.undoFailed', { names: quoted(failed) }))
            afterUndo?.()
          },
          () => toast.error(t('node.deleteMany.undoFailed', { names: quoted(gone) })),
        )
      },
    })
  }
  emit('done')
  if (lines.length === 0) {
    emit('close')
    return
  }
  summary.value = { deleted: deletedText, lines }
}
</script>

<template>
  <VeyqDialog
    :visible="true"
    :header="question.header"
    :closable="!busy"
    :close-on-escape="!busy"
    class="delete-nodes-dialog"
    @update:visible="emit('close')"
  >
    <div v-if="summary" class="delete-summary" role="status">
      <p class="dialog-copy">{{ summary.deleted }}</p>
      <p class="delete-summary-heading">{{ t('node.deleteMany.notDeleted') }}</p>
      <ul class="delete-summary-lines">
        <li v-for="(line, i) in summary.lines" :key="i">{{ line }}</li>
      </ul>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.close')" icon="check" @click="emit('close')" />
      </div>
    </div>
    <template v-else>
      <p class="dialog-copy">{{ question.copy }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="busy" @click="emit('close')" />
        <VeyqButton :label="t('node.delete')" icon="trash" variant="danger" :loading="busy" @click="confirm" />
      </div>
    </template>
  </VeyqDialog>
</template>

<style scoped>
.dialog-copy {
  margin: 0 0 0.75rem;
}
.delete-summary-heading {
  margin: 0;
  color: var(--app-muted);
}
.delete-summary-lines {
  margin: 0 0 0.75rem;
  padding-left: 1.25rem;
}
</style>
