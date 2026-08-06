<script setup lang="ts">
// A per-locale markdown note editor (cr-0116), `v-model` a `{ locale: markdown }` map. Legacy UX: pick a language
// from a dropdown + "Add language", each added language is a row with Edit + delete, and Edit opens a modal with a
// large markdown textarea (commit on Save, discard on Cancel). veyq ships de/en, so the addable set is those not yet
// present. Operator-blind: this only edits the plaintext map — the sealing happens in `share.ts` under K_share.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Select from 'primevue/select'
import Dialog from 'primevue/dialog'
import type { LocaleMap } from '../share'

const props = defineProps<{ modelValue: LocaleMap }>()
const emit = defineEmits<{ 'update:modelValue': [LocaleMap] }>()
const { t } = useI18n()

const ALL_LOCALES = ['de', 'en'] as const
const present = computed(() => Object.keys(props.modelValue))
const localeLabel = (l: string) => (l === 'de' ? t('terms.localeDe') : t('terms.localeEn'))
const addable = computed(() =>
  ALL_LOCALES.filter((l) => !present.value.includes(l)).map((l) => ({ value: l as string, label: localeLabel(l) })),
)

// The language chosen in the dropdown but not yet added.
const pending = ref<string | null>(null)

function addPending() {
  const loc = pending.value
  if (!loc || props.modelValue[loc] !== undefined) return
  emit('update:modelValue', { ...props.modelValue, [loc]: '' })
  pending.value = null
}
function removeLocale(locale: string) {
  const next = { ...props.modelValue }
  delete next[locale]
  emit('update:modelValue', next)
}

// --- the per-locale edit modal (large markdown textarea, commit on Save) ---
const editing = ref<string | null>(null)
const draft = ref('')
function openEdit(locale: string) {
  editing.value = locale
  draft.value = props.modelValue[locale] ?? ''
}
function saveEdit() {
  const loc = editing.value
  if (loc) emit('update:modelValue', { ...props.modelValue, [loc]: draft.value })
  editing.value = null
}
function cancelEdit() {
  editing.value = null
}
</script>

<template>
  <div class="locale-md-editor">
    <!-- Add a language: pick from the dropdown, then "Add language". Hidden once both locales are present. -->
    <div v-if="addable.length" class="locale-md-add">
      <Select
        v-model="pending"
        :options="addable"
        option-label="label"
        option-value="value"
        :placeholder="t('share.noteSelectLang')"
        class="locale-md-select"
      />
      <Button
        type="button"
        :label="t('share.noteAddLanguage')"
        icon="pi pi-plus"
        :disabled="!pending"
        @click="addPending"
      />
    </div>

    <p v-if="!present.length" class="locale-md-empty">{{ t('share.noteEmpty') }}</p>

    <!-- One row per added language: name + Edit (opens the modal) + delete. -->
    <div v-for="loc in present" :key="loc" class="locale-md-row">
      <span class="locale-md-lang">{{ localeLabel(loc) }}</span>
      <div class="locale-md-row-actions">
        <Button
          type="button"
          :label="t('share.edit')"
          icon="pi pi-pencil"
          text
          severity="secondary"
          size="small"
          @click="openEdit(loc)"
        />
        <Button
          type="button"
          icon="pi pi-trash"
          text
          severity="danger"
          size="small"
          :aria-label="t('share.noteRemoveLang', { lang: localeLabel(loc) })"
          @click="removeLocale(loc)"
        />
      </div>
    </div>

    <!-- The per-locale edit modal: a large markdown textarea, committed on Save. -->
    <Dialog
      v-if="editing"
      :visible="true"
      :header="t('share.noteEditTitle', { lang: localeLabel(editing) })"
      modal
      class="locale-md-dialog"
      @update:visible="cancelEdit"
    >
      <label class="locale-md-dialog-label" for="locale-md-textarea">{{ t('share.noteMarkdownLabel') }}</label>
      <textarea
        id="locale-md-textarea"
        v-model="draft"
        class="locale-md-input"
        rows="16"
        :placeholder="t('share.notePlaceholder')"
      ></textarea>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined @click="cancelEdit" />
        <Button type="button" :label="t('common.save')" icon="pi pi-save" @click="saveEdit" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.locale-md-editor {
  display: grid;
  gap: 0.6rem;
}
.locale-md-add {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.locale-md-select {
  flex: 1 1 auto;
  min-width: 0;
}
.locale-md-empty {
  margin: 0;
  color: var(--app-muted);
  font-size: 0.85rem;
}
.locale-md-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.4rem 0.6rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
}
.locale-md-lang {
  font-size: 0.9rem;
  font-weight: 600;
}
.locale-md-row-actions {
  display: flex;
  align-items: center;
  gap: 0.15rem;
  flex: 0 0 auto;
}
.locale-md-dialog-label {
  display: block;
  margin-bottom: 0.4rem;
  font-size: 0.85rem;
  font-weight: 600;
}
.locale-md-input {
  width: min(72vw, 620px);
  max-width: 100%;
  box-sizing: border-box;
  padding: 0.55rem 0.7rem;
  border: 1px solid var(--app-line);
  border-radius: 8px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
  font-size: 0.9rem;
  resize: vertical;
}
</style>
