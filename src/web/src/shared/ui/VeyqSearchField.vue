<script setup lang="ts">
// A search field: a text field with a magnifier in front (ADR 0107). Every native attribute (placeholder, aria-label,
// autocomplete, data-*) lands on the `<input>`. Sheets reach it by `veyq-search-field`.
import IconField from 'primevue/iconfield'
import InputIcon from 'primevue/inputicon'
import InputText from 'primevue/inputtext'

defineOptions({ inheritAttrs: false })
const model = defineModel<string>()
</script>

<template>
  <IconField class="veyq-search-field">
    <InputIcon class="pi pi-search" />
    <InputText v-model="model" type="search" class="veyq-input" v-bind="$attrs" />
  </IconField>
</template>
