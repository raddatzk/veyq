<script setup lang="ts">
// Core's move dialog for SEVERAL nodes (inbox #421, owner decisions 2026-10-14): the movable part of a level's selection,
// moved through the same folder browser (`MoveBrowser`) and the session's same door (`move.ts` `nodeMover`, handed in by the
// host) as one node's `MoveNodeDialog` - for a member and a `READ_WRITE` share guest alike, never a guest-only copy. That
// dialog stays as it is for one node (the row menu, the header); this is the selection's path.
//
// **The browser** lists the places that take every node; "Move here" is blocked where ANY node cannot go, the reason naming
// that node (`moveHereBlockOf`). **Before anything is sent** each node is checked as one is (`planMove`): one already there,
// or whose name folds to a sibling's there, is left out and named. A drop-box guest's folder among them keeps its question
// (asked once, naming them) and has no undo, as for one. **The write** is N single-node writes, one after the other, the
// tree re-read once (`NodeMover.moveMany`); a refusal does not stop the rest. **Afterwards**: one toast says how many moved,
// with ONE Undo that moves back exactly the nodes that moved (through the same door, again one re-read); where something
// was left out or refused, the dialog stays open on one summary that names each such node with its reason
// (`moveRefusalOf`), else it closes.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { registry } from '../applications/registry'
import { endsIdentity, moveHereBlockOf, moveRefusalOf, moveStart, MoveDeclined, planMove, type NodeMover } from './move'
import { joinList } from './selection'
import MoveBrowser from './MoveBrowser.vue'
import { TRASH_UNDO_MS } from './trashUndo'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'

const props = defineProps<{
  /** The nodes to move: the movable part of a level's selection, every one of them on that level. */
  nodes: readonly TreeNode[]
  access: Access
  tree: TreeSource<TreeNode>
  /** The session's door (`nodeMover`); the host mounts the dialog only where there is one. */
  mover: NodeMover
  /** The member's scope root as the breadcrumb names it. */
  rootLabel?: string
  /** Called after the toast's Undo moved them back - the dialog is long closed then (the member's host follows the node on
   *  screen back to its old path). */
  afterUndo?: () => void
}>()
const emit = defineEmits<{ moved: [move: { nodes: TreeNode[]; to: string | null }]; close: [] }>()
const { t, locale } = useI18n()
const { ask } = useConfirm()
const toast = useToast()

/** Each node's name as its siblings tell it apart, read now: the summary and the undo run after the tree moved. */
const names = new Map(props.nodes.map((n) => [n.id, props.tree.nameOf(n.id) || n.name]))
const nameOf = (n: TreeNode) => names.get(n.id) ?? n.name
const quoted = (ns: readonly TreeNode[]) => joinList(locale.value, ns.map((n) => t('node.move.many.quoted', { name: nameOf(n) })))

const place = ref<string | null>(moveStart(props.tree, props.nodes))
const here = computed(() => moveHereBlockOf(props.tree, props.nodes, props.access.mode, place.value))
const hereText = computed(() => {
  const h = here.value
  if (!h) return ''
  return h.node ? t(`node.move.many.here.${h.block}`, { name: nameOf(h.node) }) : t('node.move.many.here.current')
})
const busy = ref(false)
const error = ref('')
/** After the write, where something was left out or refused: what moved, and each node that did not with its reason. */
const summary = ref<{ moved: string; lines: string[] } | null>(null)
watch(place, () => (error.value = ''))

function destinationName(to: string | null): string {
  return to === null ? (props.rootLabel ?? '') : props.tree.nameOf(to)
}
function nameTakenText(sibling: TreeNode): string {
  const info = registry.kind(sibling.kind)
  const noun = (info.supported && info.nounWithArticle) || 'node.anItem'
  return t('node.move.nameTakenThere', { kind: t(noun), name: sibling.name })
}

async function submit() {
  const to = place.value
  if (here.value !== null || busy.value) return
  error.value = ''
  const { send, leftOut } = planMove(props.tree, props.nodes, to)
  const leftOutLines = leftOut.map((x) =>
    t('node.move.many.reason', { name: nameOf(x.node), reason: x.reason === 'current' ? t('node.move.here.current') : nameTakenText(x.sibling) }),
  )
  if (send.length === 0) {
    // Nothing would go: said here, and the browser stays for another place.
    error.value = leftOutLines.join(' ')
    return
  }
  const identities = send.filter(endsIdentity)
  if (identities.length > 0) {
    const accepted = await ask({
      header: t('node.move.identityTitle'),
      message: t('node.move.many.identityConfirm', { names: quoted(identities) }),
      acceptLabel: t('node.move.identityAccept'),
      acceptIcon: 'file-export',
    })
    if (!accepted) return
  }
  const mover = props.mover
  const from = new Map(send.map((n) => [n.id, n.parentId]))
  busy.value = true
  let outcomes
  try {
    outcomes = await mover.moveMany(send.map((n) => ({ nodeId: n.id, to })))
  } catch (e) {
    busy.value = false
    if (e instanceof MoveDeclined) return // a guest declined to name itself: nothing sent, nothing said
    error.value = t(`node.move.refused.${await moveRefusalOf(e)}`)
    return
  }
  busy.value = false
  const byId = new Map(send.map((n) => [n.id, n]))
  const moved = outcomes.filter((o) => o.error === null).map((o) => byId.get(o.nodeId)!)
  const refusedLines: string[] = []
  for (const o of outcomes) {
    if (o.error !== null) refusedLines.push(t('node.move.many.reason', { name: nameOf(byId.get(o.nodeId)!), reason: t(`node.move.refused.${await moveRefusalOf(o.error)}`) }))
  }
  const movedText = moved.length > 0 ? t('node.move.many.moved', { count: moved.length, to: destinationName(to) }) : t('node.move.many.noneMoved')
  if (moved.length > 0) {
    // Nothing moves back into a drop box: a guest's folder that left one has no undo, the rest move back where they were.
    const undoable = moved.filter((n) => !endsIdentity(n))
    if (undoable.length === 0) {
      toast.success(movedText)
    } else {
      const afterUndo = props.afterUndo
      toast.showUndo({
        summary: movedText,
        life: TRASH_UNDO_MS,
        onUndo: () => {
          mover.moveMany(undoable.map((n) => ({ nodeId: n.id, to: from.get(n.id) ?? null }))).then(
            (back) => {
              const failed = back.filter((o) => o.error !== null).map((o) => byId.get(o.nodeId)!)
              if (failed.length > 0) toast.error(t('node.move.many.undoFailed', { names: quoted(failed) }))
              afterUndo?.()
            },
            () => toast.error(t('node.move.many.undoFailed', { names: quoted(undoable) })),
          )
        },
      })
    }
    emit('moved', { nodes: moved, to })
  }
  const lines = [...leftOutLines, ...refusedLines]
  if (lines.length === 0) {
    emit('close')
    return
  }
  summary.value = { moved: movedText, lines }
}
</script>

<template>
  <VeyqDialog
    :visible="true"
    :header="t('node.move.many.title', { count: nodes.length })"
    :closable="!busy"
    :close-on-escape="!busy"
    class="move-dialog"
    @update:visible="emit('close')"
  >
    <div v-if="summary" class="veyq-form move-summary" role="status">
      <p class="veyq-form-copy">{{ summary.moved }}</p>
      <p class="move-summary-heading">{{ t('node.move.many.notMoved') }}</p>
      <ul class="move-summary-lines">
        <li v-for="(line, i) in summary.lines" :key="i">{{ line }}</li>
      </ul>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.close')" icon="check" @click="emit('close')" />
      </div>
    </div>
    <form v-else class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('node.move.many.hint') }}</p>
      <MoveBrowser v-model:place="place" :nodes="nodes" :access="access" :tree="tree" :root-label="rootLabel" :disabled="busy" />
      <p v-if="hereText" id="move-many-here-why" class="move-here-why">{{ hereText }}</p>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" :disabled="busy" @click="emit('close')" />
        <VeyqButton
          type="submit"
          :label="t('node.move.submit')"
          icon="file-export"
          :loading="busy"
          :disabled="here !== null || busy"
          :aria-describedby="hereText ? 'move-many-here-why' : undefined"
        />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.move-here-why,
.move-summary-heading {
  margin: 0;
  color: var(--app-muted);
}
.move-summary-lines {
  margin: 0;
  padding-left: 1.25rem;
}
</style>
