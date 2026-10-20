<script setup lang="ts">
// A safety number as a person reads it aloud (ADR 0017, `safetyNumber`): the 6x5 digit groups, monospace and selectable.
// The text is the number exactly as `safetyNumber` returns it (groups joined by one space), so a copy is the canonical
// string; only the width makes it wrap after three groups. `large` is the "compare digits" view.
defineProps<{ number: string; large?: boolean }>()
</script>

<template>
  <code class="safety-digits" :class="{ 'is-large': large }" data-testid="safety-digits">{{ number }}</code>
</template>

<style scoped>
.safety-digits {
  display: block;
  /* three 5-digit groups and their two spaces per line, so the six groups read as 2 x 3 */
  max-width: 17.5ch;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 1.15rem;
  letter-spacing: 0.04em;
  line-height: 1.6;
  user-select: text;
}
.safety-digits.is-large {
  font-size: 1.6rem;
}
</style>
