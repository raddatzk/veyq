<script setup lang="ts">
// The signpost that replaced this app's Server-Owner pages (ADR 0065). Tenant administration, the release
// panel and first-run setup live in `admin-web` now — a separate container an operator can firewall off the
// public origin entirely, which is the whole point of the extraction.
//
// It deliberately offers no link. Where the admin surface is reachable is the OPERATOR's choice, made at
// deploy time (localhost-only by default, or an optional `ADMIN_DOMAIN`), and this app is never told: a
// guessed URL that 404s is worse than a sentence that is true. So the panel says what moved and how it is
// reached in each of the two modes, and stops there.
import { useI18n } from 'vue-i18n'
import Message from 'primevue/message'

const { t } = useI18n()
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('serverAdmin.title') }}</h2>
          <p>{{ t('serverAdmin.sub') }}</p>
        </div>
      </div>
      <Message severity="info" :closable="false">{{ t('serverAdmin.where') }}</Message>
      <p class="settings-hint">{{ t('serverAdmin.whyMoved') }}</p>
    </section>
  </section>
</template>
