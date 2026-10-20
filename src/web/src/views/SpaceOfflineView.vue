<script setup lang="ts">
// What this app shows on the domain of a suspended tenant (uc.spaces.scope-access A7, ADR 0066). Every API call on
// this domain answers `403 tenant_suspended`, so there is nowhere to go: the space is offline, its content untouched,
// until the person who runs the server brings it back. The card shape of the not-set-up notice, and like it this
// names no address — how to reach the operator is not something this app is told.
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'

const { t } = useI18n()
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('spaceOffline.title') }}</template>
    <template #content>
      <p class="veyq-form-copy">{{ t('spaceOffline.sub') }}</p>
      <p class="veyq-form-copy">{{ t('spaceOffline.operator') }}</p>
    </template>
  </Card>
</template>
