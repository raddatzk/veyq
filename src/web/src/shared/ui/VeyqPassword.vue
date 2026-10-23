<script setup lang="ts">
// A password field with a reveal toggle (ADR 0107). There is no strength meter: the app judges a password by its own
// policy notice, never by a meter. `inputId` is the `<input>`'s id, for a `<label for>`; `name` and `autocomplete` go
// onto the input. Sheets reach it by `veyq-password`, its input by `veyq-input` (as every text field) or
// `veyq-password__input`, and the toggle by `veyq-password__toggle`.
import Password from 'primevue/password'

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

const PT = {
  pcInputText: { root: { class: 'veyq-input veyq-password__input' } },
  maskIcon: { class: 'veyq-password__toggle' },
  unmaskIcon: { class: 'veyq-password__toggle' },
}
</script>

<template>
  <Password
    v-model="model"
    class="veyq-password"
    :input-id="inputId"
    :input-props="{ name, autocomplete, required }"
    :disabled="disabled"
    :fluid="fluid"
    :feedback="false"
    toggle-mask
    :pt="PT"
    @blur="emit('blur', $event)"
  />
</template>
