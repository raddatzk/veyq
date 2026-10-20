<script setup lang="ts">
// The ONE guest-naming component of a public share page (inbox #401 S2): every place a guest names itself — the
// shell's naming dialog behind `GuestAccess.requestGuest` (a folder link, a Memory embedded below one, Depot's list), the
// Memories link's name pre-screen, Depot's drop-box page — and the line that says whose session this device holds, with
// its "Not you?". Core's and neutral: what the name is FOR (the identity it creates, the key it is sealed under) is the
// caller's `action`; who reads the name is the caller's `hint`, a sentence only the caller can say truthfully.
//
// Three shapes, one form:
// - `dialog`: the form in a modal titled `title`, with a cancel; emits `close` when named or dismissed.
// - inline with no session (`current` null): the form on the page (a pre-screen, the drop-box page).
// - inline with a session: "You are here as Anna." and, when the caller can `forget`, "Not you?" — which ASKS FIRST
//   (inbox #395: the session is the device's only way back to what this identity did), unless `askBeforeForget` is
//   false (Depot's "Start over" after a link stopped taking files, which re-reads the link instead). Forgetting is the
//   caller's (`forgetGuestSession` and whatever it holds beside the session); nothing on the server changes.
//   While this link's writes are recorded (`recordDays`), the line also shows the session's guest id with a copy button
//   (owner 2026-10-18, uc.admin.privacy-notice A3): a guest has no account, so naming this id is how an objection to the
//   guest-write record (GDPR Art. 21(1) with 12(2)) reaches the rows the Server Owner erases. It is the share identity's
//   random id, client-minted, and says nothing else.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useConfirm } from '../../shared/state/useConfirm'
import type { GuestIdentity } from './guestIdentity'
import GuestWriteRecordNotice from './GuestWriteRecordNotice.vue'

const props = withDefaults(
  defineProps<{
    /** Whose session this device holds for the link — only its name is read; null: none, the form shows (inline).
     *  Ignored as `dialog`. A caller may hand a name nothing was sent under yet (Depot's drop page, inbox #385 (a)). */
    current?: (Pick<GuestIdentity, 'displayName'> & Partial<Pick<GuestIdentity, 'identityId'>>) | null
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
    /** The guest-write record's N while this link's writes are recorded (`PublicShare.guestWriteRecordDays`): its
     *  notice shows in every shape, before the guest writes (ADR 0093 § 10). Null or absent: nothing is said. */
    recordDays?: number | null
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
/** The guest id to name in an objection — only while this link's writes are recorded, and once there is an identity. */
const guestId = computed(() => (props.recordDays ? props.current?.identityId ?? null : null))
const copied = ref(false)
async function copyGuestId() {
  if (!guestId.value) return
  try {
    await navigator.clipboard.writeText(guestId.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied — the id stays selectable */
  }
}
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
      <GuestWriteRecordNotice :days="recordDays" />
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="saving" @click="emit('close')" />
        <Button type="submit" class="guest-naming-submit" :label="submitLabel ?? t('share.join')" icon="pi pi-check" :loading="saving" :disabled="!canSubmit" />
      </div>
    </form>
  </Dialog>

  <div v-else-if="current" class="guest-naming">
    <p class="guest-naming-identity">
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
    <GuestWriteRecordNotice :days="recordDays" />
    <p v-if="guestId" class="guest-naming-guest-id" data-testid="guest-naming-guest-id">
      <span>{{ t('share.guest.guestId') }}</span>
      <code class="guest-naming-guest-id-value">{{ guestId }}</code>
      <Button
        type="button"
        class="guest-naming-copy-id"
        :label="copied ? t('share.guest.guestIdCopied') : t('share.guest.copyGuestId')"
        :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
        size="small"
        severity="secondary"
        text
        @click="copyGuestId"
      />
    </p>
  </div>

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
    <GuestWriteRecordNotice :days="recordDays" />
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
.guest-naming-guest-id {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  margin: 0.25rem 0 0;
  font-size: 0.85rem;
  color: var(--app-muted);
}
.guest-naming-guest-id-value {
  user-select: all;
  overflow-wrap: anywhere;
}
.guest-naming-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.25rem 0.5rem;
  margin: 0;
}
</style>
