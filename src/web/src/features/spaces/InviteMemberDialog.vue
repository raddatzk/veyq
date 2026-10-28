<script setup lang="ts">
// Invite a person into the active tenant as a MEMBER (uc.spaces.add-member, cr-0077). Mirrors the create-tenant
// dialog structure; the data layer is the operator-blind member client. The invite is a placeholder member holding an
// owner-signed key (ADR 0111 decisions 1-2): its link is mailed, and opens only with a code minted here and shown once —
// the owner passes it on outside veyq, because the operator mails the link. The owner may choose a password instead.
// There is deliberately NO copy-link: the invite token is minted for the invited person, so it must not pass
// through whoever is looking at the owner's screen (ADR 0055 §2). Mail is the only channel, and a tenant that
// cannot send is refused by the server before anything is minted.
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqDialog from '../../shared/ui/VeyqDialog.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqPassword from '../../shared/ui/VeyqPassword.vue'
import { inviteMember, memberError } from './members'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { formatInviteCode, generateInviteCode } from '@veyq/crypto/invite'
import { MIN_PASSWORD_LENGTH } from '../../shared/passwordPolicy'

const props = defineProps<{ tenantId: string }>()
const visible = defineModel<boolean>('visible', { required: true })
const emit = defineEmits<{ invited: [] }>()
const { t } = useI18n()

const email = ref('')
const error = ref('')
const sentTo = ref('') // set on success → switches the dialog to the result view, and names the address
// A password the owner chooses instead of the generated code — the account password policy's length floor.
const usePassword = ref(false)
const password = ref('')
// The code the invite was sealed under, shown once on the result view; never stored anywhere.
const sentCode = ref('')
const canSubmit = computed(
  () => email.value.trim().length > 0 && (!usePassword.value || password.value.length >= MIN_PASSWORD_LENGTH),
)

watch(visible, (isVisible) => {
  if (isVisible) {
    error.value = ''
    email.value = ''
    sentTo.value = ''
    usePassword.value = false
    password.value = ''
    sentCode.value = ''
  }
})

async function copyCode() {
  await navigator.clipboard?.writeText(sentCode.value).catch(() => undefined)
}

const { run: runSubmit, pending: loading } = useAsyncAction(
  async () => {
    const recipient = email.value.trim().toLowerCase()
    const code = usePassword.value ? '' : generateInviteCode()
    // 201 means the mail went out; the response carries no secret, and the code never left this device.
    await inviteMember(props.tenantId, recipient, usePassword.value ? { mode: 'password', password: password.value } : { mode: 'code', code })
    sentCode.value = code ? formatInviteCode(code) : ''
    password.value = ''
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
      <template v-if="usePassword">
        <label>{{ t('members.invitePasswordLabel') }}<VeyqPassword v-model="password" input-id="invite-member-password" :disabled="loading" /></label>
        <small class="dialog-intro">{{ t('members.invitePasswordNote') }}</small>
      </template>
      <VeyqButton
        :label="usePassword ? t('members.inviteUseCode') : t('members.inviteUsePassword')"
        variant="secondary"
        appearance="text"
        size="small"
        :disabled="loading"
        @click="usePassword = !usePassword"
      />
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
      <p class="dialog-intro">{{ sentCode ? t('members.inviteSentIntro') : t('members.inviteSentPasswordIntro') }}</p>
      <template v-if="sentCode">
        <label>{{ t('members.inviteCodeLabel') }}
          <code class="invite-code" data-testid="invite-code">{{ sentCode }}</code>
        </label>
        <p class="dialog-intro">{{ t('members.inviteCodeNote') }}</p>
        <VeyqButton :label="t('members.inviteCodeCopy')" icon="copy" variant="secondary" appearance="outlined" size="small" @click="copyCode" />
      </template>
      <div class="veyq-dialog-action-row">
        <VeyqButton :label="t('common.done')" icon="check" @click="visible = false" />
      </div>
    </div>
  </VeyqDialog>
</template>

<style scoped>
.invite-code {
  display: block;
  margin: 0.25rem 0;
  font-size: 1.4em;
  letter-spacing: 0.08em;
}

.dialog-intro {
  margin: 0 0 0.5rem;
  color: var(--app-muted);
  font-size: 0.9em;
}

</style>
