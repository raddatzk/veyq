<script setup lang="ts">
// The Memory's "who was there" (uc.memory.participants, ADR 0083): its model and its picker. Core's header draws the
// participants (owner 2026-11-03, ADR 0110 decision 10): this exposes them as a people fact (`fact`, `NodeHeaderPeopleFact`)
// that `MemoryDetail` contributes - the avatars, the hover card listing everyone, the profile links (on a member's page only,
// core's rule) are core's `PersonGroup`. The fact is also the way to the picker (owner 2026-11-04, ADR 0110 decision 11: what
// is edited at its place has no header action): where `canEdit` holds it carries `onEdit` - core draws an edit button beside
// the people, or, with NO participant yet, the empty chip "Add participants"; without the right and with nobody listed there
// is no fact at all (no "nobody yet", no link). The interface shows a guest NOTHING of this (the `participants` right is a
// member's alone): the detail passes `show` false, and there is no fact and no picker.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import { useMemoryParticipants } from '../useMemoryParticipants'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import type { NodeHeaderPeopleFact } from '../../../core/node/nodeHeader'
import type { TreeNode } from '../../../core/node/tree'
import type { NodeMetaDoor } from '../source'

const props = defineProps<{
  scopeId: string
  node: TreeNode
  memoryKey: Uint8Array | null
  /** The `participants` right, and not a personal scope. */
  show: boolean
  /** The node-meta door when the meta may be written, else null. */
  write: NodeMetaDoor | null
}>()
const emit = defineEmits<{ metaUpdated: [NodeMetaUpdate] }>()
const { t } = useI18n()

const p = useMemoryParticipants({
  scopeId: () => props.scopeId,
  node: () => props.node,
  memoryKey: () => props.memoryKey,
  show: () => props.show,
  write: () => props.write,
  onMetaUpdated: (payload) => emit('metaUpdated', payload),
})
/** The participants as core's header draws them; null where there is nobody to show (or nothing may be shown). */
const fact = computed<NodeHeaderPeopleFact | null>(() => {
  const edit = p.canEdit.value
  if (!props.show || (p.ids.value.length === 0 && !edit)) return null
  const status = p.ids.value.length === 0 ? undefined : p.namesPending.value ? t('people.loading') : p.loadFailed.value ? t('memory.participants.loadError') : undefined
  return {
    kind: 'people',
    label: t('memory.participants.title'),
    people: p.chips.value.map((c) => ({ userId: c.userId, name: c.name, former: c.former })),
    ...(status ? { status } : {}),
    ...(edit ? { onEdit: () => void p.openPicker(), editLabel: t('memory.participants.edit'), emptyLabel: t('memory.participants.add') } : {}),
  }
})
/** The detail contributes `fact` to the header; the fact opens the picker exactly where `canEdit` holds. */
defineExpose({ fact, openPicker: p.openPicker, canEdit: p.canEdit })
</script>

<template>
  <!-- The picker opens from the fact in core's header, also while there is no participant yet (its empty chip). -->
  <MemoryParticipantsDialog
    v-if="p.pickerOpen.value && p.canEdit.value"
    :people="p.people.value"
    :load-failed="p.loadFailed.value"
    :selected="p.ids.value"
    :max="p.max"
    @save="p.onPickerSave"
    @retry="p.ensurePeople(true)"
    @close="p.pickerOpen.value = false"
  />
</template>
