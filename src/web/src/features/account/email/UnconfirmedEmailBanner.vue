<script setup lang="ts">
// The unconfirmed-address banner (ADR 0129 decision 5, uc.account.confirm-email-address): on every page of the signed-in
// app — `AppShell.vue` draws it above the route, so never on a share, guest, join or other public page — while the
// account's address is unconfirmed. It names the address, sends the confirmation mail again (the request
// `EmailAddressSection.vue` sends, and it says "sent"), says where the space cannot send that an owner must set up mail,
// and has no close button: an account whose address does not reach it cannot be recovered, so it stays until confirmed.
// Its source of truth is the server's copy, the read the Security section uses — asked when the shell mounts, when the
// signed-in account changes and when the tab comes back into view (a confirmation opened in another tab), never stored
// beyond the page: what it read is the shell's one copy (`unconfirmedAddress.ts`), which holds the other notices back.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import { account } from '../../../app/account'
import { getAccountEmail, requestEmailConfirmation, requestErrorKey } from './emailConfirmation'
import { unconfirmedAddress as unconfirmed } from './unconfirmedAddress'

const { t } = useI18n()
const sending = ref(false)
const sent = ref(false)
const failure = ref('')

async function read() {
  try {
    const held = await getAccountEmail()
    unconfirmed.value = held.confirmed ? null : held.email
  } catch {
    unconfirmed.value = null
  }
}

async function send() {
  sending.value = true
  sent.value = false
  failure.value = ''
  try {
    await requestEmailConfirmation()
    sent.value = true
  } catch (e) {
    failure.value = t(requestErrorKey(e))
  } finally {
    sending.value = false
  }
}

function onVisible() {
  if (document.visibilityState === 'visible' && unconfirmed.value !== null) void read()
}
onMounted(() => {
  void read()
  document.addEventListener('visibilitychange', onVisible)
})
onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisible)
  unconfirmed.value = undefined // no banner, nothing known: the next shell asks again
})
watch(() => account.userId, (now, before) => {
  if (now !== before) {
    sent.value = false
    failure.value = ''
    unconfirmed.value = undefined // the last account's answer is not this one's
    void read()
  }
})
</script>

<template>
  <section
    v-if="unconfirmed"
    class="unconfirmed-email-banner"
    role="region"
    :aria-label="t('emailConfirm.banner.label')"
    data-testid="unconfirmed-email-banner"
  >
    <p class="unconfirmed-email-banner__text">
      {{ t('emailConfirm.banner.text', { email: unconfirmed }) }}
      <span v-if="sent" class="unconfirmed-email-banner__status" role="status">{{ t('emailConfirm.banner.sent') }}</span>
      <span v-else-if="failure" class="unconfirmed-email-banner__status" role="alert">{{ failure }}</span>
    </p>
    <VeyqButton
      :label="t('emailConfirm.banner.send')"
      icon="envelope"
      size="small"
      :loading="sending"
      data-testid="unconfirmed-email-send"
      @click="send"
    />
  </section>
</template>

<style scoped>
/* The in-app notice's frame (`DonationBanner.vue`, `FinishProfilePrompt.vue`), with the warning role's edge: it asks the
   member to act on their account, so it is louder than the donation line, and it carries no close. */
.unconfirmed-email-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem 1rem;
  margin-bottom: 1rem;
  padding: 0.55rem 0.5rem 0.55rem 0.9rem;
  border: 1px solid var(--app-line);
  border-left: 4px solid var(--app-warning);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
}
.unconfirmed-email-banner__text {
  flex: 1 1 18rem;
  min-width: 0;
  margin: 0;
  font-size: 0.9rem;
  overflow-wrap: anywhere;
}
.unconfirmed-email-banner__status {
  display: block;
  margin-top: 0.25rem;
  font-weight: 600;
}
</style>
