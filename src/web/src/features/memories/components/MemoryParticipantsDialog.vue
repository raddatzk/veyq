<script setup lang="ts">
// The participants picker (uc.memory.participants, ADR 0083): the CURRENT members of the Memory's scope as a list of
// checkboxes - by display name, real name else username - and, above them, any ids already listed that no member
// matches any more ("Former member"), which stay ticked until the person unticks them. Presentation only: it hands the
// chosen ids back in the order picked and the host writes them (the server cannot check the ids: the list is sealed, so
// the CLIENT limits the pick to the directory - this list). At the cap the unticked boxes are disabled and it says why.
// A search field above the members narrows them by display name (case- and diacritics-insensitive); it only HIDES rows -
// the draft is untouched, so a ticked person stays ticked while the filter hides them. Former members are never filtered.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../../../shared/ui/VeyqCheckbox.vue'
import VeyqDialog from '../../../shared/ui/VeyqDialog.vue'
import VeyqSearchField from '../../../shared/ui/VeyqSearchField.vue'
import VeyqMessage from '../../../shared/ui/VeyqMessage.vue'
import PersonAvatar from '../../../core/people/PersonAvatar.vue'
import type { Person } from '../../../core/people/directory'
import { fold } from '../../../shared/text/fold'

const props = defineProps<{
  /** The scope's current members; null while they are being read. */
  people: Person[] | null
  loadFailed: boolean
  /** The ids listed now. */
  selected: readonly string[]
  max: number
}>()
const emit = defineEmits<{ save: [string[]]; close: []; retry: [] }>()
const { t } = useI18n()

const draft = ref<string[]>([...props.selected])
const former = computed(() => (props.people ? draft.value.filter((id) => !props.people!.some((p) => p.userId === id)) : []))
const full = computed(() => draft.value.length >= props.max)
const query = ref('')
const visiblePeople = computed(() => {
  const q = fold(query.value.trim())
  return props.people ? props.people.filter((p) => !q || fold(p.displayName).includes(q)) : []
})
const noMatches = computed(() => !!props.people && props.people.length > 0 && visiblePeople.value.length === 0)
const isChecked = (id: string) => draft.value.includes(id)

function toggle(id: string) {
  draft.value = isChecked(id) ? draft.value.filter((x) => x !== id) : full.value ? draft.value : [...draft.value, id]
}
function save() {
  emit('save', draft.value)
  emit('close')
}
</script>

<template>
  <VeyqDialog :visible="true" :style="{ minWidth: 'min(25rem, calc(100vw - 2rem))' }" :header="t('memory.participants.dialogTitle')" @update:visible="emit('close')">
    <form class="veyq-form memory-participants-dialog" @submit.prevent="save">
      <p class="memory-participants-hint">{{ t('memory.participants.dialogHint') }}</p>
      <VeyqMessage v-if="loadFailed" tone="error">
        {{ t('memory.participants.loadError') }}
        <VeyqButton :label="t('people.retry')" variant="secondary" appearance="text" size="small" @click="emit('retry')" />
      </VeyqMessage>
      <p v-else-if="!people" class="memory-participants-hint" role="status">{{ t('people.loading') }}</p>
      <fieldset v-else class="memory-participants-set">
        <legend class="visually-hidden">{{ t('memory.participants.title') }}</legend>
        <div v-if="people.length > 0" class="memory-participants-search">
          <VeyqSearchField v-model="query" :placeholder="t('memory.participants.search')" :aria-label="t('memory.participants.search')" autocomplete="off" data-testid="participants-search" />
        </div>
        <label v-for="id in former" :key="id" class="memory-participants-option is-former">
          <VeyqCheckbox :model-value="true" :aria-label="t('memory.participants.former')" @update:model-value="toggle(id)" />
          <PersonAvatar :name="t('memory.participants.former')" />
          <span class="memory-participants-name">{{ t('memory.participants.former') }}</span>
        </label>
        <label v-for="p in visiblePeople" :key="p.userId" class="memory-participants-option">
          <VeyqCheckbox :model-value="isChecked(p.userId)" :disabled="full && !isChecked(p.userId)" :aria-label="p.displayName" @update:model-value="toggle(p.userId)" />
          <PersonAvatar :name="p.displayName" :user-id="p.userId" />
          <span class="memory-participants-name">{{ p.displayName }}</span>
        </label>
        <p v-if="noMatches" class="memory-participants-hint" role="status" data-testid="participants-no-matches">{{ t('memory.participants.noMatches') }}</p>
        <p v-if="people.length === 0 && former.length === 0" class="memory-participants-hint">{{ t('memory.participants.noMembers') }}</p>
      </fieldset>
      <p v-if="full" class="memory-participants-hint" role="note">{{ t('memory.participants.full', { max }) }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.cancel')" icon="times" variant="secondary" appearance="outlined" @click="emit('close')" />
        <VeyqButton type="submit" :label="t('common.save')" icon="check" :disabled="!people" />
      </div>
    </form>
  </VeyqDialog>
</template>

<style scoped>
.memory-participants-set {
  display: grid;
  gap: 0.15rem;
  margin: 0;
  padding: 0;
  border: none;
  max-height: 50vh;
  overflow-y: auto;
}
.memory-participants-option {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.35rem 0.25rem;
  border-radius: var(--app-radius-sm);
  cursor: pointer;
}
/* `.veyq-form input` makes every input full width. The checkbox's input is an invisible overlay sized to its own
   box (`.veyq-checkbox`, 1.25rem), so it stays right - but the box itself must not flex, or the name is pushed to the edge. */
.memory-participants-option :deep(.veyq-checkbox),
.memory-participants-option :deep(.veyq-avatar) {
  flex: none;
}
.memory-participants-search {
  margin-bottom: 0.25rem;
}
.memory-participants-option:hover {
  background: var(--app-hover);
}
.memory-participants-option.is-former {
  color: var(--app-muted);
  font-style: italic;
}
.memory-participants-hint {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
</style>
