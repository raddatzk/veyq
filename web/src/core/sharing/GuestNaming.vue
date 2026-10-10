<script setup lang="ts">
// The ONE guest-naming component of a public share page (inbox #401 S2): every place a guest names itself — the
// shell's naming dialog behind `GuestAccess.requestGuest` (a folder link, a Memory embedded below one, Files' list), the
// Memories link's name pre-screen, Files' drop-box page — and the line that says whose session this device holds, with
// its "Not you?". Core's and neutral: what the name is FOR (the identity it creates, the key it is sealed under) is the
// caller's `action`; who reads the name is the caller's `hint`, a sentence only the caller can say truthfully.
//
// Three shapes, one form:
// - `dialog`: the form in a modal titled `title`, with a cancel; emits `close` when named or dismissed.
// - inline with no session (`current` null): the form on the page (a pre-screen, the drop-box page).
// - inline with a session: "You are here as Anna." and, when the caller can `forget`, "Not you?" — which ASKS FIRST
//   (inbox #395: the session is the device's only way back to what this identity did), unless `askBeforeForget` is
//   false (Files' "Start over" after a link stopped taking files, which re-reads the link instead). Forgetting is the
//   caller's (`forgetGuestSession` and whatever it holds beside the session); nothing on the server changes.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useConfirm } from '../../shared/state/useConfirm'
import type { GuestIdentity } from './guestIdentity'

const props = withDefaults(
  defineProps<{
    /** The session this device holds for the link; null: none, the form shows (inline). Ignored as `dialog`. */
    current?: GuestIdentity | null
    /** Names the guest with the trimmed name; resolves once named (the caller saves the session). */
    action?: (name: string) => Promise<void>
    /** Who reads the name — the caller's sentence. */
    hint?: string
    submitLabel?: string
    /** The message a failed `action` shows (default: a neutral "try again"). */
    errorText?: (e: unknown) => string
    /** In a modal titled `title`. */
    dialog?: boolean
    title?: string
    /** "Not you?": forget this device's session. Without it the line offers none. */
    forget?: () => void | Promise<void>
    forgetLabel?: string
    askBeforeForget?: boolean
    forgetDisabled?: boolean
    forgetBusy?: boolean
  }>(),
  { current: null, askBeforeForget: true },
)
const emit = defineEmits<{ close: [] }>()
const { t } = useI18n()
const { ask } = useConfirm()

const name = ref('')
const error = ref('')
const { run, pending: saving } = useAsyncAction(
  async () => {
    await props.action?.(name.value.trim())
    name.value = ''
    if (props.dialog) emit('close')
  },
  { onError: (e) => void (error.value = props.errorText ? props.errorText(e) : t('share.joinFailed')) },
)
const canSubmit = computed(() => name.value.trim().length > 0 && !saving.value && !!props.action)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  await run()
}

const named = computed(() => props.current?.displayName?.trim() ?? '')
async function notYou() {
  if (props.askBeforeForget) {
    const accepted = await ask({
      header: t('share.guest.notYouTitle'),
      message: named.value ? t('share.guest.notYouConfirm', { name: named.value }) : t('share.guest.notYouConfirmUnnamed'),
      acceptLabel: t('share.guest.notYouAccept'),
      acceptIcon: 'pi pi-refresh',
    })
    if (!accepted) return
  }
  await props.forget?.()
}
</script>

<template>
  <Dialog
    v-if="dialog"
    :visible="true"
    :header="title"
    modal
    :closable="!saving"
    :close-on-escape="!saving"
    class="guest-naming"
    @update:visible="emit('close')"
  >
    <form class="veyq-form" @submit.prevent="submit">
      <label>
        {{ t('share.yourName') }}
        <InputText v-model="name" class="guest-naming-input" autocomplete="name" required autofocus :disabled="saving" />
      </label>
      <p v-if="hint" class="veyq-form-copy">{{ hint }}</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="saving" @click="emit('close')" />
        <Button type="submit" class="guest-naming-submit" :label="submitLabel ?? t('share.join')" icon="pi pi-check" :loading="saving" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>

  <p v-else-if="current" class="guest-naming guest-naming-identity">
    <span>{{ named ? t('share.guest.as', { name: named }) : t('share.guest.asUnnamed') }}</span>
    <Button
      v-if="forget"
      type="button"
      class="guest-naming-not-you"
      :label="forgetLabel ?? t('share.guest.notYou')"
      size="small"
      severity="secondary"
      text
      :disabled="forgetDisabled"
      :loading="forgetBusy"
      @click="notYou"
    />
  </p>

  <form v-else class="veyq-form guest-naming" @submit.prevent="submit">
    <label>
      {{ t('share.yourName') }}
      <InputText
        v-model="name"
        class="guest-naming-input"
        autocomplete="name"
        :placeholder="t('share.yourNamePlaceholder')"
        required
        :disabled="saving"
      />
      <small v-if="hint">{{ hint }}</small>
    </label>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <div class="veyq-dialog-action-row">
      <Button
        type="submit"
        class="guest-naming-submit"
        :label="submitLabel ?? t('share.join')"
        icon="pi pi-arrow-right"
        :loading="saving"
        :disabled="!canSubmit"
      />
    </div>
  </form>
</template>

<style scoped>
.guest-naming-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  margin: 0;
}
</style>
