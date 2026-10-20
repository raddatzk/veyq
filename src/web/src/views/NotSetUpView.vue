<script setup lang="ts">
// What this app shows on an instance that has never been set up. Until ADR 0065 the answer was `/setup` — the
// first-run Server-Owner registration lived here, in the SPA served at `VEYQ_DOMAIN`, which quietly made a
// resolving public hostname a precondition for bootstrapping the instance at all. Setup moved to `admin-web`,
// reachable on localhost with no DNS and no TLS, so this app's honest answer is now: not yet, and not by you.
//
// It names no URL: where the admin surface is reachable is the operator's deploy-time choice, and this app is
// never told it.
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'

const { t } = useI18n()
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('notSetUp.title') }}</template>
    <template #content>
      <p class="veyq-form-copy">{{ t('notSetUp.sub') }}</p>
      <p class="veyq-form-copy">{{ t('notSetUp.operator') }}</p>
    </template>
  </Card>
</template>
