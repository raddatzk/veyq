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
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import Dialog from 'primevue/dialog'
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import PersonAvatar from './PersonAvatar.vue'
import type { Person } from '../../../core/people/directory'

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
const fold = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase()
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
  <Dialog :visible="true" :header="t('memory.participants.dialogTitle')" modal @update:visible="emit('close')">
    <form class="veyq-form memory-participants-dialog" @submit.prevent="save">
      <p class="memory-participants-hint">{{ t('memory.participants.dialogHint') }}</p>
      <Message v-if="loadFailed" severity="error" :closable="false">
        {{ t('memory.participants.loadError') }}
        <Button type="button" :label="t('people.retry')" severity="secondary" text size="small" @click="emit('retry')" />
      </Message>
      <p v-else-if="!people" class="memory-participants-hint" role="status">{{ t('people.loading') }}</p>
      <fieldset v-else class="memory-participants-set">
        <legend class="visually-hidden">{{ t('memory.participants.title') }}</legend>
        <IconField v-if="people.length > 0" class="memory-participants-search">
          <InputIcon class="pi pi-search" />
          <InputText v-model="query" type="search" :placeholder="t('memory.participants.search')" :aria-label="t('memory.participants.search')" autocomplete="off" data-testid="participants-search" />
        </IconField>
        <label v-for="id in former" :key="id" class="memory-participants-option is-former">
          <Checkbox :model-value="true" binary :aria-label="t('memory.participants.former')" @update:model-value="toggle(id)" />
          <PersonAvatar :name="t('memory.participants.former')" />
          <span class="memory-participants-name">{{ t('memory.participants.former') }}</span>
        </label>
        <label v-for="p in visiblePeople" :key="p.userId" class="memory-participants-option">
          <Checkbox :model-value="isChecked(p.userId)" binary :disabled="full && !isChecked(p.userId)" :aria-label="p.displayName" @update:model-value="toggle(p.userId)" />
          <PersonAvatar :name="p.displayName" />
          <span class="memory-participants-name">{{ p.displayName }}</span>
        </label>
        <p v-if="noMatches" class="memory-participants-hint" role="status" data-testid="participants-no-matches">{{ t('memory.participants.noMatches') }}</p>
        <p v-if="people.length === 0 && former.length === 0" class="memory-participants-hint">{{ t('memory.participants.noMembers') }}</p>
      </fieldset>
      <p v-if="full" class="memory-participants-hint" role="note">{{ t('memory.participants.full', { max }) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="emit('close')" />
        <Button type="submit" :label="t('common.save')" icon="pi pi-check" :disabled="!people" />
      </div>
    </form>
  </Dialog>
</template>

<style scoped>
.memory-participants-dialog {
  min-width: min(360px, 80vw);
}
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
/* `.veyq-form input` makes every input full width. PrimeVue's checkbox input is an invisible overlay sized to its own
   box (`.p-checkbox`, 1.25rem), so it stays right - but the box itself must not flex, or the name is pushed to the edge. */
.memory-participants-option :deep(.p-checkbox),
.memory-participants-option :deep(.p-avatar) {
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
