<script setup lang="ts">
// Core's ONE set of the dialogs behind the host doors (`../nodeDialogs.ts`, `../nodeHost.ts`), mounted by both host pages -
// the member's space page and a share guest's page - beside their content. Each dialog mounts while its door holds it open
// and only for a session it can serve: the member's (the name and create dialogs, the delete question, the share dialogs,
// the copy, the import, the space's trash) only with the member's unlocked keys, the move dialogs only where the session
// has a move door (`nodeMover`), the guest's naming of a new node only on a guest's page that writes.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { registry } from '../../applications/registry'
import { FOLDER_KIND } from '../kind'
import type { TreeNode } from '../tree'
import type { NodeDialogs } from '../nodeDialogs'
import FolderNameDialog from '../FolderNameDialog.vue'
import MoveNodeDialog from '../MoveNodeDialog.vue'
import MoveNodesDialog from '../MoveNodesDialog.vue'
import DeleteNodesDialog from './DeleteNodesDialog.vue'
import SpaceTrashDialog from './SpaceTrashDialog.vue'
import ExportSubtreeDialog from '../../export/ExportSubtreeDialog.vue'
import ImportSubtreeDialog from '../../export/ImportSubtreeDialog.vue'
import CopyNodesDialog from '../../export/CopyNodesDialog.vue'
import ShareNameDialog from '../../sharing/ShareNameDialog.vue'
import FolderShareDialog from '../../sharing/FolderShareDialog.vue'

const props = defineProps<{ dialogs: NodeDialogs }>()
const { t } = useI18n()

const env = props.dialogs.env
const s = props.dialogs.state
const access = computed(() => env.access())
const tree = computed(() => env.tree())
/** The member's unlocked scope: what every member dialog writes under. Null for a guest, or while the keys are locked. */
const member = computed(() => {
  const a = access.value
  return a.mode === 'member' && a.scopeId && a.epochs ? { scopeId: a.scopeId, epochs: a.epochs, readonly: a.readonly } : null
})
const nameOf = (node: TreeNode) => tree.value?.nameOf(node.id) || node.name

/** The create dialog of the kind being created; a kind with no `createDialog` (or one nobody registered) opens nothing. */
const createDialog = computed(() => {
  const c = s.create.value
  if (!c) return null
  const info = registry.kind(c.kind)
  return info.supported ? (info.createDialog ?? null) : null
})
const createParent = computed(() => (s.name.value?.mode === 'create' ? s.name.value.parent : (s.create.value?.parent ?? null)))
/** The share dialog of the node being shared: its kind's (`KindDescriptor.shareDialog`, its whole answer; FOLDER's is
 *  core's own, pre-registered). A share wraps the node's own key, never K_SC (ADR 0080 decisions 1 and 12). */
const shareDialog = computed(() => {
  const node = s.share.value?.node
  if (!node) return null
  const info = registry.kind(node.kind)
  return info.supported ? (info.shareDialog ?? null) : null
})
/** The node being shared as the tree reads it now (its name and epoch), the one the door was handed while it is gone. */
const shared = computed(() => {
  const node = s.share.value?.node
  return node ? (tree.value?.node(node.id) ?? node) : null
})
/** The space's top level is being shared: core's folder dialog with no node, its upload-only link alone (`NodeHost.share`). */
const sharingTop = computed(() => !!s.share.value && s.share.value.node === null)
/** The roles a new link may take, as the door's offer says (each false unless granted); the dialog decides none. */
const shareOffer = computed(() => {
  const o = s.share.value?.offer
  return { view: !!o?.view, contribute: !!o?.contribute, upload: !!o?.upload }
})

function nameDone() {
  const n = s.name.value
  props.dialogs.stayOn(n?.mode === 'rename' ? n.shown : null)
}
async function reload() {
  await env.reload?.()
}
async function trashRestored() {
  const shown = env.onScreen()
  await env.reload?.()
  props.dialogs.stayOn(shown)
}
async function guestCreate(name: string) {
  const c = s.guestCreate.value
  if (!c || !env.guestCreate) throw new Error('no_guest_session')
  await env.guestCreate.create(c.kind, c.parentId, name)
}
</script>

<template>
  <FolderNameDialog
    v-if="s.name.value && member"
    :mode="s.name.value.mode"
    :scope-id="member.scopeId"
    :epochs="member.epochs"
    :parent-id="createParent?.id ?? null"
    :parent-key="dialogs.createParentKey.value"
    :parent-epoch="createParent?.epoch ?? null"
    :parent-scope-epoch="createParent?.scopeEpoch ?? null"
    :node="s.name.value.mode === 'rename' ? s.name.value.node : null"
    @done="nameDone"
    @close="s.name.value = null"
  />
  <component
    :is="createDialog"
    v-if="createDialog && s.create.value && member"
    :kind="s.create.value.kind"
    :scope-id="member.scopeId"
    :epochs="member.epochs"
    :parent-id="s.create.value.parent?.id ?? null"
    :parent-key="dialogs.createParentKey.value"
    :parent-epoch="s.create.value.parent?.epoch ?? null"
    :parent-scope-epoch="s.create.value.parent?.scopeEpoch ?? null"
    @done="reload"
    @close="s.create.value = null"
  />
  <ShareNameDialog
    v-if="s.guestCreate.value && env.guestCreate"
    :title="s.guestCreate.value.kind === FOLDER_KIND ? t('share.folder.newFolderTitle') : t('common.create')"
    :label="t('share.folder.nameLabel')"
    :submit-label="t('common.create')"
    :action="guestCreate"
    :error-text="env.guestCreate.errorText"
    @close="s.guestCreate.value = null"
  />
  <DeleteNodesDialog
    v-if="s.deleting.value && member"
    :scope-id="member.scopeId"
    :epochs="member.epochs"
    :items="s.deleting.value.items"
    :name-of="nameOf"
    :after-undo="dialogs.afterUndo(s.deleting.value.shown)"
    @done="dialogs.deleted()"
    @close="s.deleting.value = null"
  />
  <ExportSubtreeDialog
    v-if="s.exporting.value && tree"
    :node="s.exporting.value"
    :access="access"
    :tree="tree"
    :display-name="nameOf(s.exporting.value)"
    @close="s.exporting.value = null"
  />
  <ImportSubtreeDialog
    v-if="s.importing.value && access.mode === 'member'"
    :input="s.importing.value.input"
    :upload-as-file="s.importing.value.uploadAsFile"
    :access="access"
    :parent="dialogs.importParent.value"
    :target-name="env.importTarget?.() ?? ''"
    @done="reload"
    @close="s.importing.value = null"
  />
  <CopyNodesDialog
    v-if="s.copying.value && access.mode === 'member' && tree"
    :nodes="s.copying.value.nodes"
    :access="access"
    :tree="tree"
    :root-label="env.rootLabel?.()"
    @copied="dialogs.stayOn(s.copying.value?.shown ?? null)"
    @close="s.copying.value = null"
  />
  <MoveNodeDialog
    v-if="s.moving.value && dialogs.mover && tree"
    :node="s.moving.value.node"
    :access="access"
    :tree="tree"
    :mover="dialogs.mover"
    :root-label="env.rootLabel?.()"
    :node-name="nameOf(s.moving.value.node)"
    :after-undo="dialogs.afterUndo(s.moving.value.shown)"
    @moved="dialogs.stayOn(s.moving.value?.shown ?? null)"
    @close="s.moving.value = null"
  />
  <MoveNodesDialog
    v-if="s.movingMany.value && dialogs.mover && tree"
    :nodes="s.movingMany.value.nodes"
    :access="access"
    :tree="tree"
    :mover="dialogs.mover"
    :root-label="env.rootLabel?.()"
    :after-undo="dialogs.afterUndo(s.movingMany.value.shown)"
    @moved="dialogs.stayOn(s.movingMany.value?.shown ?? null)"
    @close="s.movingMany.value = null"
  />
  <SpaceTrashDialog
    v-if="s.trash.value && member && env.trashTree"
    :scope-id="member.scopeId"
    :epochs="member.epochs"
    :tree="env.trashTree()"
    @restored="trashRestored"
    @close="s.trash.value = false"
  />
  <component
    :is="shareDialog"
    v-if="shareDialog && shared && dialogs.shareKey.value && member"
    v-bind="shareOffer"
    :scope-id="member.scopeId"
    :node-id="shared.id"
    :node-key="dialogs.shareKey.value"
    :node-epoch="shared.epoch"
    :node-scope-epoch="shared.scopeEpoch"
    :epochs="member.epochs"
    :node-name="shared.name"
    :node-kind="shared.kind"
    :readonly="member.readonly"
    @done="reload"
    @close="s.share.value = null"
  />
  <!-- The top level: no node and no list; never a READ or READ_WRITE link (a scope root is no link's root). -->
  <FolderShareDialog
    v-if="sharingTop && member"
    :upload="shareOffer.upload"
    :scope-id="member.scopeId"
    :epochs="member.epochs"
    :readonly="member.readonly"
    @done="reload"
    @close="s.share.value = null"
  />
</template>
