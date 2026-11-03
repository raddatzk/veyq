<script setup lang="ts">
// A one-line text field (ADR 0107). It IS the `<input>`, so every native attribute (type, autocomplete, placeholder,
// required, inputmode, maxlength, id, name, aria-*) and listener lands on it, and a caller that focuses it reaches the
// element through the ref's `$el`. A date, or a date and time, is `VeyqDateField`, not a `type` given here. Sheets reach
// it by `veyq-input` (styled in `layer.css`).
const model = defineModel<string | null | undefined>()
defineProps<{
  /** Fills the width of its container. */
  fluid?: boolean
  invalid?: boolean
}>()
</script>

<template>
  <input
    v-model="model"
    type="text"
    :class="['veyq-input', { 'veyq-input--fluid': fluid, 'veyq-input--invalid': invalid }]"
    :aria-invalid="invalid || undefined"
  />
</template>
