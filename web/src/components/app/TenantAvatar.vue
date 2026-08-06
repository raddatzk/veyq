<script setup lang="ts">
// The tenant's avatar badge (cr-0108). Today it renders the tenant name's initial; it is the **one swap point**
// for a future tenant-chosen icon — when the `MyTenant` contract grows an icon field, pass it as `icon` and the
// `<img>` (styled by the existing `.sidebar-tenant-avatar img` rule) replaces the initial, initial as fallback.
// `active` paints the accent gradient (the current tenant / the selected row); reused by the sidebar's
// active-tenant row, the switcher overview, and the mobile nav sheet.
import { computed } from 'vue'

const props = defineProps<{ name: string; active?: boolean; icon?: string }>()

const initial = computed(() => props.name.trim().charAt(0).toUpperCase() || 'V')
</script>

<template>
  <span class="sidebar-tenant-avatar" :class="{ 'sidebar-tenant-avatar-active': active }">
    <img v-if="icon" :src="icon" alt="" />
    <span v-else>{{ initial }}</span>
  </span>
</template>
