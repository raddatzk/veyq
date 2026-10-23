<script setup lang="ts">
// Avatars drawn overlapping, as one group (ADR 0107).
import AvatarGroup from 'primevue/avatargroup'
</script>

<template>
  <AvatarGroup class="veyq-avatar-group">
    <slot />
  </AvatarGroup>
</template>
