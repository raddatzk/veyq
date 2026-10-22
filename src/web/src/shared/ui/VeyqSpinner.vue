<script setup lang="ts">
// A large busy indicator for a whole region still loading (ADR 0107); a busy control shows `VeyqIcon`'s spinner
// instead.
import ProgressSpinner from 'primevue/progressspinner'
</script>

<template>
  <ProgressSpinner class="veyq-spinner" style="width: 2rem; height: 2rem" />
</template>
