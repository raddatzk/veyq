<script setup lang="ts">
// A search field: a text field with a magnifier in front (ADR 0107). Every native attribute (placeholder, aria-label,
// autocomplete, data-*) lands on the `<input>`. Sheets reach it by `veyq-search-field` (styled in `layer.css`).
import VeyqIcon from './VeyqIcon.vue'

defineOptions({ inheritAttrs: false })
const model = defineModel<string>()
</script>

<template>
  <div class="veyq-search-field">
    <VeyqIcon name="search" class="veyq-search-field__icon" />
    <input v-model="model" type="search" class="veyq-input veyq-search-field__input" v-bind="$attrs" />
  </div>
</template>
