<script setup lang="ts">
// Invite a person into the active tenant as a MEMBER (uc.spaces.add-member, cr-0077). Mirrors the create-tenant
// dialog structure; the data layer is the operator-blind member client. Admission only — the invitee onboards
// by email and joins grant-less; a key-holder delivers the scope key afterward (cr-0076), no action here.
// There is deliberately NO copy-link: the invite token is minted for the invited person, so it must not pass
// through whoever is looking at the owner's screen (ADR 0055 §2). Mail is the only channel, and a tenant that
// cannot send is refused by the server before anything is minted.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import { inviteMember, memberError } from './members'
import { useAsyncAction } from '../../shared/state/useAsyncAction'

const props = defineProps<{ tenantId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ invited: [] }>()
const { t } = useI18n()

const email = ref('')
const error = ref('')
const sentTo = ref('') // set on success → switches the dialog to the result view, and names the address
const canSubmit = computed(() => email.value.trim().length > 0)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    email.value = ''
    sentTo.value = ''
  }
})

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const recipient = email.value.trim().toLowerCase()
    await inviteMember(props.tenantId, recipient) // 201 means the mail went out; the response carries no secret
    sentTo.value = recipient
    emit('invited') // refresh the directory behind the dialog
  },
  {
    onError: async (e) => {
      const { code } = await memberError(e)
      error.value =
        code === 'already_member'
          ? t('members.alreadyMember')
          : code === 'not_tenant_owner'
            ? t('members.notOwner')
            : code === 'rate_limited'
              ? t('members.inviteRateLimited')
              : code === 'mail_unavailable'
                ? t('members.mailUnavailable')
                : code === 'mail_send_failed'
                  ? t('members.mailSendFailed')
                  : t('members.inviteFailed')
    },
  },
)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await runSubmit()
}
</script>

<template>
  <VeyqDialog v-model:visible="visible" :header="t('members.invite')" class="tenant-dialog" :closable="!loading" :close-on-escape="!loading">
    <form v-if="!sentTo" class="veyq-form" @submit.prevent="submit">
      <p class="dialog-intro">{{ t('members.inviteIntro') }}</p>
      <label>
        {{ t('members.email') }}
        <VeyqInput v-model="email" type="email" autocomplete="email" required :disabled="loading" />
      </label>
      <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
      <div class="veyq-dialog-action-row">
        <VeyqButton
          :label="t('common.cancel')"
          icon="times"
          variant="secondary"
          appearance="outlined"
          :disabled="loading"
          @click="visible = false"
        />
        <VeyqButton type="submit" :label="t('members.inviteSubmit')" icon="send" :loading="loading" :disabled="!canSubmit" />
      </div>
    </form>

    <!-- One outcome only: a 201 means the mail was accepted. Anything else is an error on the form above, so
         there is no delivery state to branch on here any more (ADR 0055 §7). -->
    <div v-else class="veyq-form">
      <VeyqMessage tone="success">{{ t('members.inviteSentTo', { email: sentTo }) }}</VeyqMessage>
      <p class="dialog-intro">{{ t('members.inviteSentIntro') }}</p>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.done')" icon="check" @click="visible = false" />
      </div>
    </div>
  </VeyqDialog>
</template>

<style scoped>
.dialog-intro {
  margin: 0 0 0.5rem;
  color: var(--app-muted);
  font-size: 0.9em;
}

</style>
