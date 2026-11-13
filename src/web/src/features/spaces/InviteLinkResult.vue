<script setup lang="ts">
// A link invite's result (ADR 0129 decision 1, uc.spaces.add-member): the message the owner hands over, prefilled in the UI
// language — the space's name, the link with its code, its expiry and that it works once — and editable, with three
// actions over what the field holds NOW: copy it, give it to the system share sheet (only where the browser has one), or
// open it in the owner's own mail program (`mailto:`). Shown once: the link is held nowhere, the server has only its token.
// Drawn by the invite dialog after a mint and by the members panel after a link's re-issue (decision 6).
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import VeyqMessage from '../../shared/ui/VeyqMessage.vue'
import VeyqTextarea from '../../shared/ui/VeyqTextarea.vue'

const props = defineProps<{ link: string; expiresAt: Date; spaceName: string }>()
const { t, locale } = useI18n()

const expiry = computed(() =>
  new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(props.expiresAt),
)
const message = ref('')
// The message follows the link (a re-issue hands a new one in), never the owner's edits back.
watch(
  () => [props.link, expiry.value, props.spaceName] as const,
  () => (message.value = t('members.linkMessage', { space: props.spaceName, link: props.link, expiry: expiry.value })),
  { immediate: true },
)

const subject = computed(() => t('members.linkMailSubject', { space: props.spaceName }))
const mailHref = computed(
  () => `mailto:?subject=${encodeURIComponent(subject.value)}&body=${encodeURIComponent(message.value)}`,
)
// The share sheet exists in some browsers only (most phones, Safari, Chromium on some desktops): no button elsewhere.
const canShare = typeof navigator !== 'undefined' && typeof navigator.share === 'function'

const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | undefined
async function copy() {
  try {
    await navigator.clipboard.writeText(message.value)
    copied.value = true
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard denied — the message stays selectable */
  }
}
onBeforeUnmount(() => clearTimeout(copiedTimer))

async function share() {
  try {
    await navigator.share({ title: subject.value, text: message.value })
  } catch {
    /* the sheet was closed, or refused — nothing to say */
  }
}
</script>

<template>
  <div class="veyq-stack invite-link-result">
    <label class="veyq-form-field">
      {{ t('members.linkMessageLabel') }}
      <VeyqTextarea v-model="message" auto-resize rows="5" data-testid="invite-link-message" />
    </label>
    <div class="veyq-action-group invite-link-actions">
      <VeyqButton
        :label="copied ? t('members.linkCopied') : t('members.linkCopy')"
        icon="copy"
        data-testid="invite-link-copy"
        @click="copy"
      />
      <VeyqButton
        v-if="canShare"
        :label="t('members.linkShare')"
        icon="share-alt"
        variant="secondary"
        appearance="outlined"
        data-testid="invite-link-share"
        @click="share"
      />
      <VeyqButton
        :label="t('members.linkMail')"
        icon="envelope"
        variant="secondary"
        appearance="outlined"
        :href="mailHref"
        data-testid="invite-link-mail"
      />
    </div>
    <VeyqMessage tone="warn">{{ t('members.linkWarning') }}</VeyqMessage>
  </div>
</template>

<style scoped>
/* The three actions start under the field, where the eye is, not at the row's far end. */
.invite-link-actions {
  justify-content: flex-start;
  margin-left: 0;
}
</style>
