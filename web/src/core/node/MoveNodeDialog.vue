<script setup lang="ts">
// Core's move dialog (inbox #358): "Move to…" for a node of ANY kind, for a member and a `READ_WRITE` share guest alike —
// one dialog, the session's own door (`move.ts` `nodeMover`, handed in by the host), never a guest-only copy (inbox #401).
// Not drag and drop: a list of destinations is reachable by keyboard and screen reader, and testable by a click.
//
// It lists the destinations `moveDestinations` derives from the tree the session reads (the member's whole scope with
// its root, a guest's share subtree only), each that cannot be picked disabled with the reason beside it (the item
// itself, inside it, where it is now, a guest's folder, a place that does not hold its kind). Before sending, a sibling
// at the destination whose name folds to the same slug is named (core's `collidingSibling`, inbox #389); the server's
// refusals are explained by their code (`moveRefusalOf`): `slug_taken`, `illegal_structure`, `illegal_scope`,
// `stale_placement` (the tree is re-read, so a retry builds on the node as it now is), `stale_epoch`, … .
//
// A member's move of a drop-box guest's folder (ADR 0080 decision 11) ends its identity: asked first through the app's
// confirm, and offered no undo — nothing moves back into a drop box. Every other move is said in a toast with an Undo
// that moves the node back to where it was, through the same door (`useToast().showUndo`, as the trash's undo).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import type { Access, TreeSource } from '../access'
import type { TreeNode } from './tree'
import { registry } from '../applications/registry'
import { collidingSibling } from './siblingCollision'
import { endsIdentity, moveDestinations, moveRefusalOf, MoveDeclined, type NodeMover } from './move'
import { TRASH_UNDO_MS } from './trashUndo'
import { useConfirm } from '../../shared/state/useConfirm'
import { useToast } from '../../shared/state/useToast'

const props = defineProps<{
  /** The node to move, of any kind. */
  node: TreeNode
  /** Who looks: picks which destinations a session may take (a guest's: its subtree's folders). */
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
const rows = computed(() => moveDestinations(props.tree, props.node, props.access.mode, props.rootLabel ?? ''))
const anyFree = computed(() => rows.value.some((r) => r.blocked === null))
/** The picked destination: a node id, `null` for the scope root, `undefined` while none is picked. */
const picked = ref<string | null | undefined>(undefined)
const error = ref('')
const busy = ref(false)

const rowKey = (id: string | null) => id ?? '\u0000root'
/** A row's icon: its kind's (the scope root: a house). */
function iconOf(kind: string | null): string {
  if (kind === null) return 'pi pi-home'
  const info = registry.kind(kind)
  return info.supported ? info.icon : 'pi pi-question-circle'
}
function pick(id: string | null) {
  picked.value = id
  error.value = ''
}

/** The loaded child of `to` the node's name collides with, folded by each node's own kind (never the node itself). */
function takenBy(to: string | null): TreeNode | null {
  return collidingSibling(props.tree.children(to), to, props.node.kind, props.node.name, props.node.id)
}

function destinationName(to: string | null): string {
  return to === null ? (props.rootLabel ?? '') : props.tree.nameOf(to)
}

async function submit() {
  const to = picked.value
  if (to === undefined || busy.value) return
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
      acceptIcon: 'pi pi-file-export',
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
  <Dialog
    :visible="true"
    :header="t('node.move.title', { name: label })"
    modal
    :closable="!busy"
    :close-on-escape="!busy"
    @update:visible="emit('close')"
  >
    <form class="veyq-form" @submit.prevent="submit">
      <p class="veyq-form-copy">{{ t('node.move.hint') }}</p>
      <p v-if="!anyFree" class="veyq-empty veyq-empty-inline">{{ t('node.move.nothing') }}</p>
      <fieldset class="move-destinations" :disabled="busy">
        <legend>{{ t('node.move.destinations') }}</legend>
        <label
          v-for="row in rows"
          :key="rowKey(row.id)"
          class="move-destination"
          :class="{ 'is-blocked': row.blocked !== null, 'is-picked': picked === row.id }"
          :style="{ paddingInlineStart: `${row.depth * 1.25 + 0.5}rem` }"
        >
          <input
            type="radio"
            name="move-destination"
            :value="rowKey(row.id)"
            :checked="picked === row.id"
            :disabled="row.blocked !== null"
            @change="pick(row.id)"
          />
          <span :class="iconOf(row.kind)" aria-hidden="true" />
          <span class="move-destination-name">{{ row.name }}</span>
          <small v-if="row.blocked" class="move-destination-why">{{ t(`node.move.blocked.${row.blocked}`) }}</small>
        </label>
      </fieldset>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="emit('close')" />
        <Button type="submit" :label="t('node.move.submit')" icon="pi pi-file-export" :loading="busy" :disabled="picked === undefined || busy" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.move-destinations {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  max-height: 50vh;
  overflow-y: auto;
  margin: 0;
  padding: 0;
  border: 0;
}
.move-destinations legend {
  margin-bottom: 0.5rem;
  font-weight: 600;
}
.move-destination {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding-block: 0.375rem;
  padding-inline-end: 0.5rem;
  border-radius: var(--app-radius-sm);
  cursor: pointer;
}
.move-destination.is-picked {
  background: var(--app-surface-muted);
}
.move-destination.is-blocked {
  cursor: default;
  color: var(--app-muted);
}
.move-destination-name {
  min-width: 0;
  overflow-wrap: anywhere;
}
.move-destination-why {
  margin-inline-start: auto;
  color: var(--app-muted);
}
</style>
