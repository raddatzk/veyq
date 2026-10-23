<script setup lang="ts">
// A large busy indicator for a whole region still loading (ADR 0107); a busy control shows `VeyqIcon`'s spinner
// instead. Styled in `layer.css`.
</script>

<template>
  <div class="veyq-spinner" role="progressbar" aria-busy="true">
    <svg class="veyq-spinner__spin" viewBox="25 25 50 50">
      <circle class="veyq-spinner__circle" cx="50" cy="50" r="20" fill="none" stroke-width="2" stroke-miterlimit="10" />
    </svg>
  </div>
</template>
