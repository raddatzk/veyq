<script setup lang="ts">
// Core's move dialog (inbox #358): "Move to…" for a node of ANY kind, for a member and a `READ_WRITE` share guest alike —
// one dialog, the session's own door (`move.ts` `nodeMover`, handed in by the host), never a guest-only copy (inbox #401).
// Not drag and drop: a folder browser (owner decision 2026-10-14), reachable by keyboard and screen reader, and testable by
// a click.
//
// The browser is core's `MoveBrowser` (over the tree the session reads: the member's whole scope with its root, a guest's
// share subtree only). It opens where the node is; the user opens the place it should go to and chooses **Move here**,
// disabled with the reason where the node cannot go (`moveHereBlock`: where it is now, itself or inside it, a guest's
// folder, a place that does not hold its kind), or **Cancel**. The browser takes a node LIST; this dialog moves the one
// node it is handed, and a level's selection of several moves through `MoveNodesDialog` (inbox #421).
//
// Before sending, a sibling at the destination whose name folds to the same slug is named (core's `collidingSibling`,
// inbox #389); the server's refusals are explained by their code (`moveRefusalOf`): `slug_taken`, `illegal_structure`,
// `illegal_scope`, `stale_placement` (the tree is re-read, so a retry builds on the node as it now is), `stale_epoch`, … .
//
// A member's move of a drop-box guest's folder (ADR 0080 decision 11) ends its identity: asked first through the app's
// confirm, and offered no undo — nothing moves back into a drop box. Every other move is said in a toast with an Undo
// that moves the node back to where it was, through the same door (`useToast().showUndo`, as the trash's undo).
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { registry } from '../applications/registry'
import { collidingSibling } from './siblingCollision'
import { endsIdentity, moveHereBlock, moveRefusalOf, moveStart, MoveDeclined, type NodeMover } from './move'
import MoveBrowser from './MoveBrowser.vue'
import { TRASH_UNDO_MS } from './trashUndo'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'

const props = defineProps<{
  /** The node to move, of any kind. */
  node: TreeNode
  /** Who looks: picks which places a session may take (a guest's: its subtree's folders). */
  access: Access
  /** The tree as that session reads it. */
  tree: TreeSource<TreeNode>
  /** The session's door (`nodeMover`); the host mounts the dialog only where there is one. */
  mover: NodeMover
  /** The member's scope root as the breadcrumb names it. */
  rootLabel?: string
  /** The node's name as its siblings tell it apart (`Anna (2)`); omitted: its own. */
  nodeName?: string
  /** Called after the toast's Undo moved the node back — the dialog is long closed then, so no event reaches the host
   *  (the member's host follows a node on screen back to its old path). */
  afterUndo?: (nodeId: string) => void
}>()
const emit = defineEmits<{ moved: [move: { node: TreeNode; from: string | null; to: string | null }]; close: [] }>()
const { t } = useI18n()
const { ask } = useConfirm()
const toast = useToast()

const label = computed(() => props.nodeName ?? props.node.name)
const nodes = computed(() => [props.node])
/** The place on screen: a node id, or null for the member's scope root. Opens where the node is. */
const place = ref<string | null>(moveStart(props.tree, nodes.value))
const hereBlock = computed(() => moveHereBlock(props.tree, nodes.value, props.access.mode, place.value))
const error = ref('')
const busy = ref(false)
watch(place, () => (error.value = ''))

/** The loaded child of `to` the node's name collides with, folded by each node's own kind (never the node itself). */
function takenBy(to: string | null): TreeNode | null {
  return collidingSibling(props.tree.children(to), to, props.node.kind, props.node.name, props.node.id)
}

function destinationName(to: string | null): string {
  return to === null ? (props.rootLabel ?? '') : props.tree.nameOf(to)
}

async function submit() {
  const to = place.value
  if (hereBlock.value !== null || busy.value) return
  error.value = ''
  const sibling = takenBy(to)
  if (sibling) {
    const info = registry.kind(sibling.kind)
    const noun = (info.supported && info.nounWithArticle) || 'node.anItem'
    error.value = t('node.move.nameTakenThere', { kind: t(noun), name: sibling.name })
    return
  }
  const identity = endsIdentity(props.node)
  if (identity) {
    const accepted = await ask({
      header: t('node.move.identityTitle'),
      message: t('node.move.identityConfirm', { name: label.value }),
      acceptLabel: t('node.move.identityAccept'),
      acceptIcon: 'file-export',
    })
    if (!accepted) return
  }
  const node = props.node
  const name = label.value // the undo runs after the dialog closed: nothing of it is read then
  const from = node.parentId
  const mover = props.mover
  busy.value = true
  try {
    await mover.move(node.id, to)
  } catch (e) {
    busy.value = false
    if (e instanceof MoveDeclined) return
    const code = await moveRefusalOf(e)
    // A lost race: re-read, so the next attempt builds on the node as it now is.
    if (code === 'stale_placement') await mover.refresh().catch(() => {})
    error.value = t(`node.move.refused.${code}`)
    return
  }
  busy.value = false
  const summary = t('node.move.moved', { name, to: destinationName(to) })
  if (identity) {
    toast.success(summary) // nothing moves back into a drop box: no undo
  } else {
    toast.showUndo({
      summary,
      life: TRASH_UNDO_MS,
      onUndo: () => {
        const undone = props.afterUndo
        mover.move(node.id, from).then(
          () => undone?.(node.id),
          () => toast.error(t('node.move.undoFailed', { name })),
        )
      },
    })
  }
  emit('moved', { node, from, to })
  emit('close')
}
</script>

<template>
  <VeyqDialog
    :visible="true"
    :header="t('node.move.title', { name: label })"
    :closable="!busy"
    :close-on-escape="!busy"
    class="move-dialog"
    @update:visible="emit('close')"
  >
    <form class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('node.move.hint') }}</p>
      <MoveBrowser v-model:place="place" :nodes="nodes" :access="access" :tree="tree" :root-label="rootLabel" :disabled="busy" />
      <p v-if="hereBlock" id="move-here-why" class="move-here-why">{{ t(`node.move.here.${hereBlock}`) }}</p>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="busy" @click="emit('close')" />
        <VeyqButton
          type="submit"
          :label="t('node.move.submit')"
          icon="file-export"
          :loading="busy"
          :disabled="hereBlock !== null || busy"
          :aria-describedby="hereBlock ? 'move-here-why' : undefined"
        />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.move-here-why {
  margin: 0;
  color: var(--app-muted);
}
</style>
