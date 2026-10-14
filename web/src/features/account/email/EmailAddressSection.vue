<script setup lang="ts">
// uc.account.confirm-email-address (web, ADR 0074) — the email-address section of the settings hub's Security
// rubric, mounted by KeyRotationPanel as its first `settings-form` (a panel stacks sections; the hub mounts one
// component per rubric). A `Section`, not a `Panel`: it carries no `veyq-surface` of its own.
//
// It shows the address **as the server holds it** — the one a reset would mail, not what this device remembers
// typing — and whether it is confirmed, and offers the confirmation mail. It gates nothing and warns of nothing
// app-wide: an unconfirmed address is a fact shown where it can be acted on (ADR 0074 Decision 3). There is no
// copy-link: a link on this screen would prove nothing about the mailbox, so a space that cannot send says so.
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { getAccountEmail, requestEmailConfirmation, requestErrorKey, type AccountEmail } from './emailConfirmation'
import { useAsyncData } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { handle401 } from '../../../shared/handle401'
import { isUnauthorized } from '../../../shared/httpError'
import { activeSpace } from '../../../app/activeSpace'
import ChangeEmailForm from './ChangeEmailForm.vue'

const { t } = useI18n()
const on401 = handle401(useRouter())

const account = ref<AccountEmail | null>(null)
const sent = ref(false)
const actionError = ref('')

const { pending: loading, error: loadError } = useAsyncData(async () => {
  account.value = await getAccountEmail()
}, { onError: on401 })

const { run: send, pending: sending } = useAsyncAction(
  async () => {
    actionError.value = ''
    sent.value = false
    await requestEmailConfirmation()
    sent.value = true
  },
  {
    onError: (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      actionError.value = t(requestErrorKey(e))
    },
  },
)
</script>

<template>
  <section class="settings-form">
    <div class="settings-section-header">
      <div>
        <h2>{{ t('emailConfirm.title') }}</h2>
        <p>{{ t('emailConfirm.sub') }}</p>
      </div>
    </div>

    <p v-if="loading" class="settings-muted">{{ t('emailConfirm.loading') }}</p>
    <Message v-else-if="loadError || !account" severity="error" :closable="false">{{ t('emailConfirm.loadFailed') }}</Message>

    <template v-else>
      <p class="email-address" data-testid="email-address">
        <strong>{{ account.email }}</strong>
      </p>
      <Message v-if="account.confirmed" severity="success" :closable="false">{{ t('emailConfirm.confirmed') }}</Message>
      <Message v-else severity="warn" :closable="false">{{ t('emailConfirm.unconfirmed') }}</Message>

      <Message v-if="sent" severity="info" :closable="false">{{ t('emailConfirm.sent', { email: account.email }) }}</Message>
      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <div class="email-actions">
        <Button
          :label="t('emailConfirm.send')"
          icon="pi pi-envelope"
          :severity="account.confirmed ? 'secondary' : undefined"
          :loading="sending"
          @click="send()"
        />
      </div>

      <!-- uc.account.change-email: the address above is the one this changes. Only inside a space — its relay mails
           the link, so an account in no space (a Server Owner alone) is not offered it (E5). -->
      <ChangeEmailForm v-if="activeSpace.tenantId" :current-email="account.email" />
    </template>
  </section>
</template>

<style scoped>
.email-address {
  margin: 0 0 0.5rem;
  overflow-wrap: anywhere;
}
.email-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
