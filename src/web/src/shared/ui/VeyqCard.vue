<script setup lang="ts">
// A framed card: an optional title, the content, an optional footer (ADR 0107). The caller's classes land on the root,
// and a sheet reaches the parts by the layer's own classes (`veyq-card-frame__body`, `__title`, `__content`,
// `__footer`), never by the library's.
import Card from 'primevue/card'

const PT = {
  body: { class: 'veyq-card-frame__body' },
  title: { class: 'veyq-card-frame__title' },
  content: { class: 'veyq-card-frame__content' },
  footer: { class: 'veyq-card-frame__footer' },
}
</script>

<template>
  <Card class="veyq-card-frame" :pt="PT">
    <template v-if="$slots.title" #title><slot name="title" /></template>
    <template #content><slot /></template>
    <template v-if="$slots.footer" #footer><slot name="footer" /></template>
  </Card>
</template>
