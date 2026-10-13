<script setup lang="ts">
// The Memory header's "who was there" row (uc.memory.participants, ADR 0083): a name chip per participant - a link to the
// member's profile (`#people/<userId>`) - and, where the meta may be written, an add / edit control that opens the picker.
// A former member is a greyed chip with no link. The interface shows a guest NOTHING of this (the `participants` capability
// is a member's alone): the host passes `show` false and this renders no element at all - not even an empty row.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import Avatar from 'primevue/avatar'
import MemoryParticipantsDialog from './MemoryParticipantsDialog.vue'
import { personHash } from '../../../core/node/location'
import { useMemoryParticipants } from '../useMemoryParticipants'
import type { NodeMetaUpdate } from '../useMetaWriteFeedback'
import type { TreeNode } from '../../../core/node/tree'
import type { NodeMetaDoor } from '../source'

const props = defineProps<{
  scopeId: string
  node: TreeNode
  memoryKey: Uint8Array | null
  /** The `participants` capability, and not a personal scope. */
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
const visible = computed(() => props.show && (p.ids.value.length > 0 || p.canEdit.value))
const initialOf = (name: string) => name.trim().charAt(0).toUpperCase() || '?'
</script>

<template>
  <div v-if="visible" class="memory-participants" role="group" :aria-label="t('memory.participants.title')" data-testid="memory-participants">
    <template v-for="c in p.chips.value" :key="c.userId">
      <span v-if="c.former" class="memory-participant-chip is-former" data-testid="participant-former">
        <Avatar :label="initialOf(c.name)" shape="circle" size="small" aria-hidden="true" />
        <span>{{ c.name }}</span>
      </span>
      <RouterLink
        v-else
        class="memory-participant-chip"
        :to="{ hash: personHash(c.userId) }"
        :title="t('memory.participants.open', { name: c.name })"
        :aria-label="t('memory.participants.open', { name: c.name })"
        data-testid="participant-link"
      >
        <Avatar :label="initialOf(c.name)" shape="circle" size="small" aria-hidden="true" />
        <span>{{ c.name }}</span>
      </RouterLink>
    </template>
    <span v-if="p.namesPending.value" class="memory-participants-muted" role="status">{{ t('people.loading') }}</span>
    <span v-else-if="p.loadFailed.value && p.ids.value.length > 0" class="memory-participants-muted">{{ t('memory.participants.loadError') }}</span>
    <span v-else-if="p.ids.value.length === 0" class="memory-participants-muted">{{ t('memory.participants.none') }}</span>
    <button
      v-if="p.canEdit.value"
      type="button"
      class="memory-participants-edit"
      :aria-label="p.ids.value.length ? t('memory.participants.edit') : t('memory.participants.add')"
      :title="p.ids.value.length ? t('memory.participants.edit') : t('memory.participants.add')"
      data-testid="participants-edit"
      @click="p.openPicker()"
    >
      <span class="pi" :class="p.ids.value.length ? 'pi-pencil' : 'pi-user-plus'" />
      <span v-if="!p.ids.value.length">{{ t('memory.participants.add') }}</span>
    </button>
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
  </div>
</template>

<style scoped>
.memory-participants {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.3rem 0.4rem;
  /* the same optical pull as the date and place chips, so the first chip's content starts flush with the title */
  margin-left: -0.45rem;
}
.memory-participant-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.15rem 0.55rem 0.15rem 0.2rem;
  border-radius: 999px;
  background: var(--app-control-bg);
  color: var(--app-text);
  font-size: 0.88rem;
  font-weight: 600;
  text-decoration: none;
}
a.memory-participant-chip:hover {
  background: var(--app-hover);
}
a.memory-participant-chip:focus-visible,
.memory-participants-edit:focus-visible {
  outline: 2px solid var(--app-focus);
  outline-offset: 2px;
}
.memory-participant-chip.is-former {
  color: var(--app-muted);
  font-weight: 500;
  font-style: italic;
  opacity: 0.85;
}
.memory-participants-muted {
  color: var(--app-muted);
  font-size: 0.9rem;
  padding: 0 0.45rem;
}
.memory-participants-edit {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.25rem 0.45rem;
  border: none;
  border-radius: var(--app-radius-sm);
  background: transparent;
  color: var(--app-text);
  font-size: 0.9rem;
  cursor: pointer;
}
.memory-participants-edit:hover {
  background: var(--app-hover);
}
.memory-participants-edit .pi {
  color: var(--app-accent);
}
</style>
