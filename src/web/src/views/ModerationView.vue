<script setup lang="ts">
// The moderation surface (ADR 0099 § 6, uc.moderation.serve-as-moderator, uc.moderation.review-a-case): one route on every
// tenant host, outside the member shell — the gate (sign-in, appointment, address, the unlock that keeps only the
// moderation keys) and, behind it, the instance-wide queue with each case's details and decisions.
import ModerationGate from '../features/moderation/ModerationGate.vue'
import ModerationQueue from '../features/moderation/ModerationQueue.vue'
</script>

<template>
  <ModerationGate wide>
    <ModerationQueue />
  </ModerationGate>
</template>
