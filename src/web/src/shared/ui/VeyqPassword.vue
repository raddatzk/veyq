<script setup lang="ts">
// A password field with a reveal toggle (ADR 0107). There is no strength meter: the app judges a password by its own
// policy notice. The field's accessible name is its label alone (ADR 0107 § Behaviour changed on purpose). `inputId` is
// the `<input>`'s id, for a `<label for>`; `name` and `autocomplete` go onto the input; the caller's other attributes
// land on the wrapper. The toggle is a real button, named from the catalog. A label's name is computed from its whole
// content, so a toggle inside the caller's `<label>` would add "Show password" to the field's name: the input therefore
// names itself with the label's visible text, read again when the language changes. Sheets reach it by `veyq-password`,
// its input by `veyq-input` (as every text field) or `veyq-password__input`, and the toggle by `veyq-password__toggle`
// (its place; its look is every field's `veyq-field-action`, both in `layer.css`).
import { nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqIcon from './VeyqIcon.vue'

const model = defineModel<string | null | undefined>()
defineProps<{
  inputId?: string
  name?: string
  autocomplete?: string
  required?: boolean
  disabled?: boolean
  fluid?: boolean
}>()
const emit = defineEmits<{ blur: [FocusEvent] }>()
const { t, locale } = useI18n()

const revealed = ref(false)
const input = ref<HTMLInputElement | null>(null)
const labelText = ref<string | undefined>()
function readLabel() {
  const label = input.value?.labels?.[0]
  labelText.value = label?.innerText?.trim() || undefined
}
onMounted(readLabel)
watch(locale, () => void nextTick(readLabel))
</script>

<template>
  <div :class="['veyq-password', { 'veyq-password--fluid': fluid }]">
    <input
      :id="inputId"
      ref="input"
      v-model="model"
      :aria-label="labelText"
      :type="revealed ? 'text' : 'password'"
      class="veyq-input veyq-password__input"
      :class="{ 'veyq-input--fluid': fluid }"
      :name="name"
      :autocomplete="autocomplete"
      :required="required"
      :disabled="disabled"
      @blur="emit('blur', $event)"
    />
    <button
      type="button"
      class="veyq-password__toggle veyq-field-action"
      :aria-label="revealed ? t('ui.hidePassword') : t('ui.showPassword')"
      :aria-pressed="revealed"
      :disabled="disabled"
      @click="revealed = !revealed"
    >
      <VeyqIcon :name="revealed ? 'eye-slash' : 'eye'" />
    </button>
  </div>
</template>
