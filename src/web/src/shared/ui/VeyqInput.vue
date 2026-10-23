<script setup lang="ts">
// A one-line text field (ADR 0107). Its root IS the `<input>`, so every native attribute (type, autocomplete,
// placeholder, required, inputmode, maxlength, id, name, aria-*) and listener lands on it, and a caller that focuses
// it reaches the element through the ref's `$el`. Sheets reach it by `veyq-input`.
import InputText from 'primevue/inputtext'

const model = defineModel<string | null | undefined>()
defineProps<{
  /** Fills the width of its container. */
  fluid?: boolean
  invalid?: boolean
}>()
</script>

<template>
  <InputText v-model="model" class="veyq-input" :fluid="fluid" :invalid="invalid" />
</template>
