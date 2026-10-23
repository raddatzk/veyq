<script setup lang="ts">
// A framed card: an optional title, the content, an optional footer (ADR 0107). The caller's classes land on the root,
// and a sheet reaches the parts by `veyq-card-frame__body`, `__title`, `__content` and `__footer` (styled in
// `layer.css`).
</script>

<template>
  <div class="veyq-card-frame">
    <div class="veyq-card-frame__body">
      <div v-if="$slots.title" class="veyq-card-frame__caption">
        <div class="veyq-card-frame__title"><slot name="title" /></div>
      </div>
      <div class="veyq-card-frame__content"><slot /></div>
      <div v-if="$slots.footer" class="veyq-card-frame__footer"><slot name="footer" /></div>
    </div>
  </div>
</template>
