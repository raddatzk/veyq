<script setup lang="ts">
// A per-locale markdown editor (cr-0116, lifted out of `features/memory/components` by
// cr-terms-editor-lists-the-languages-that-have-terms). `v-model` a `LocaleMap`, and the whole point of the
// component is that **the map's keys are the languages that exist**: a row per key, an explicit act to add a
// language that has none, and an explicit act to remove one. It never renders a language the map does not
// carry — that is the difference between "no German terms" and "an empty German box".
//
// Two callers shaped it: the share note (`features/memory/ShareCreateDialog`) and the tenant terms
// (`features/tenant/TenantTermsPanel`). `docs/ai/README.md` § *Spike when the shape is unclear* wants five real
// usages before a shared abstraction is trusted, so the props surface below is kept to what the SECOND caller
// actually needed — a `:locales` universe, a placeholder, a vetoable remove and a row-actions slot — and not one
// knob more. The third, core's folder share note (`core/sharing/FolderShareDialog`), needed none.
// `scripts/validate_locale_editor_single_home.py` keeps it the only one.
//
// Operator-blind: this edits plaintext only. Whether the result is sealed (the share note, under `K_share` in
// `share.ts`) or public operational content (the tenant terms) is the caller's business, never this file's.
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import { SUPPORTED_LOCALES } from '../../i18n'
import { localeLabel, type LocaleMap } from './localeMap'

const props = withDefaults(
  defineProps<{
    modelValue: LocaleMap
    /**
     * The languages this editor may ADD. Defaults to everything the app speaks; a caller passes it only to
     * narrow. Keeping it a prop with this default is what stops a fourth copy of the two-element set — the
     * editor used to carry its own private `ALL_LOCALES = ['de', 'en']`.
     */
    locales?: readonly string[]
    /** The textarea placeholder. The one string that is genuinely per-caller: a share note and a server's
     *  terms of service invite very different text. */
    placeholder?: string
    /**
     * Asked before a language is removed; resolving `false` vetoes it. Default: remove straight away, which is
     * the share note's long-standing behaviour. The terms panel passes a confirmation here, because dropping a
     * published language is not the same weight as dropping a note.
     */
    beforeRemove?: (locale: string) => boolean | Promise<boolean>
  }>(),
  {
    locales: () => SUPPORTED_LOCALES,
    placeholder: '',
    beforeRemove: undefined,
  },
)
const emit = defineEmits<{ 'update:modelValue': [LocaleMap] }>()
const { t } = useI18n()

/** The languages that exist — the map's own keys, never the supported set. */
const present = computed(() => Object.keys(props.modelValue))
/** The languages that could still be added. With two supported locales this is 2, 1 or 0 entries — which is
 *  why each one is its own button naming the language it adds, rather than a dropdown plus an "Add" button:
 *  a picker for a set that is never larger than two is a control for a choice that barely exists. */
const addable = computed(() => props.locales.filter((l) => !present.value.includes(l)))

function addLocale(locale: string) {
  if (props.modelValue[locale] !== undefined) return
  emit('update:modelValue', { ...props.modelValue, [locale]: '' })
}

async function removeLocale(locale: string) {
  if (props.beforeRemove && !(await props.beforeRemove(locale))) return
  const next = { ...props.modelValue }
  delete next[locale]
  emit('update:modelValue', next)
}

// --- the per-locale edit modal (large markdown textarea, commit on Save, discard on Cancel) ---
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
    <p v-if="!present.length" class="locale-md-empty">{{ t('localeEditor.empty') }}</p>

    <!-- One row per language that EXISTS: its name, whatever extra actions the caller hangs there, Edit, and
         remove. A language with no entry in the map has no row at all. -->
    <div v-for="loc in present" :key="loc" class="locale-md-row">
      <span class="locale-md-lang">{{ localeLabel(loc) }}</span>
      <div class="locale-md-row-actions">
        <slot name="row-actions" :locale="loc" />
        <Button
          type="button"
          :label="t('localeEditor.edit')"
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
          :aria-label="t('localeEditor.removeLang', { lang: localeLabel(loc) })"
          @click="removeLocale(loc)"
        />
      </div>
    </div>

    <!-- Adding a language: one button per language that could be added, each naming it. Gone once none are. -->
    <div v-if="addable.length" class="locale-md-add">
      <Button
        v-for="loc in addable"
        :key="loc"
        type="button"
        :label="t('localeEditor.addLanguage', { lang: localeLabel(loc) })"
        icon="pi pi-plus"
        severity="secondary"
        outlined
        size="small"
        @click="addLocale(loc)"
      />
    </div>

    <!-- The per-locale edit modal: a large markdown textarea, committed on Save. -->
    <Dialog
      v-if="editing"
      :visible="true"
      :header="t('localeEditor.editTitle', { lang: localeLabel(editing) })"
      modal
      class="locale-md-dialog"
      @update:visible="cancelEdit"
    >
      <label class="locale-md-dialog-label" for="locale-md-textarea">{{ t('localeEditor.bodyLabel') }}</label>
      <textarea
        id="locale-md-textarea"
        v-model="draft"
        class="locale-md-input"
        rows="16"
        :placeholder="placeholder"
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
  flex-wrap: wrap;
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
