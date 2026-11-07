<script setup lang="ts">
// The gates of a public link and what lies behind them, drawn (ADR 0121 decision 6): the share page's own cards — loading,
// unavailable, the password, the share's tenant's terms, the note's acceptance — then the guest's line ("You are here as
// …", a READ_WRITE link's), the body (core's page of the link's tree, or a WRITE_ONLY link's drop page) and the page's one
// naming dialog. The state is `./shareGates.ts`'; two hosts draw it: the share page (`views/ShareView.vue`) and a bookmark's
// page that shows its link in place (`core/bookmark/BookmarkEmbed.vue`, `embedded`). Embedded, the cards head with `h2`
// under the bookmark's own `h1`, the body navigates inside itself (`ShareTreePage`'s `embedded`), and the guest uploader's
// statements — the share page's (`guestPage`) — are not asked; the removal-order notice is, of the share's instance.
import { useI18n } from 'vue-i18n'
import GuestNaming from './GuestNaming.vue'
import OrderNotice from './OrderNotice.vue'
import OperatorSectionView from '../terms/OperatorSectionView.vue'
import StatementNotice from '../../features/moderation/statement/StatementNotice.vue'
import { vSafeHtml } from '../../shared/security/trustedTypes'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqInput from '../../shared/ui/VeyqInput.vue'
import { NOT_A_LOGIN } from '../../shared/ui/notALogin'
import { apiBaseOf, shareIdOf } from './shareAt'
import type { ShareGates } from './shareGates'

const props = withDefaults(defineProps<{ gates: ShareGates; embedded?: boolean }>(), { embedded: false })
const { t } = useI18n()
const shareId = shareIdOf(props.gates.at)
const apiBase = apiBaseOf(props.gates.at)
const heading = props.embedded ? 'h2' : 'h1'
/** What the body is handed beyond the registry's `ShareBodyProps`: core's page navigates inside itself when embedded. */
const bodyExtra = props.embedded ? { embedded: true } : {}
// The template reads the gates' refs through this one reactive alias (auto-unwrapped there).
const g = props.gates
</script>

<template>
  <div v-if="g.status.value === 'loading'" class="share-view-state">
    <VeyqIcon name="spinner" spin />
    <span>{{ t('share.loading') }}</span>
  </div>

  <div v-else-if="g.status.value === 'unavailable'" class="share-view-state share-view-unavailable">
    <VeyqIcon name="lock" />
    <component :is="heading">{{ t('share.unavailable') }}</component>
    <p>{{ t('share.unavailableHint') }}</p>
    <!-- TCO Art. 11: a guest uploader whose upload an authority's removal order took down is told so here, on its own
         link, though the link answers everyone else as unknown (asked only when this device holds its session). -->
    <OrderNotice :share-id="shareId" :api-base="apiBase || undefined" />
    <!-- ADR 0093 § 7: so is one whose upload — or itself, with the link — a moderator blocked, by its statement of reasons. -->
    <StatementNotice v-if="!embedded" :share-id="shareId" />
  </div>

  <!-- cr-0118: a password-protected share resolves to a LOCKED shell (nothing else crossed the wire) — prompt for
       the password, derive the Argon2id token on device, and re-resolve with it. -->
  <div v-else-if="g.status.value === 'locked'" class="share-view-state share-name-gate">
    <form class="veyq-surface share-name-card" @submit.prevent="g.submitPassword">
      <VeyqIcon name="lock" class="share-password-icon" />
      <component :is="heading">{{ t('share.passwordTitle') }}</component>
      <p class="share-name-intro">{{ t('share.passwordIntro') }}</p>
      <label for="share-password-input">{{ t('share.passwordLabel') }}</label>
      <VeyqInput v-bind="NOT_A_LOGIN"
        id="share-password-input"
        v-model="g.pwInput.value"
        type="password"
        name="share-password"
        autocomplete="off"
        :placeholder="t('share.passwordPlaceholder')"
        :disabled="g.pwBusy.value"
      />
      <button class="share-name-submit" type="submit" :disabled="g.pwBusy.value || !g.pwInput.value">
        {{ g.pwBusy.value ? t('share.passwordChecking') : t('share.passwordUnlock') }}
      </button>
      <p v-if="g.pwError.value" class="share-error">{{ t('share.passwordWrong') }}</p>
    </form>
  </div>

  <!-- The accountless terms gate (uc.sharing.share-accept, ADR 0035 §3): the space's terms + an Accept button,
       BETWEEN the password shell and the display-name pre-screen (password → terms → name → content). Acceptance
       is client-only (localStorage) — an operational gate, not enforcement: it records + surfaces consent, it does
       not police access. markdownHtml escapes first (safe subset), shown by v-safe-html. -->
  <div v-else-if="g.needsTerms.value" class="share-view-state share-name-gate">
    <div class="veyq-surface share-name-card share-terms-card">
      <VeyqIcon name="file-check" class="share-terms-icon" />
      <component :is="heading">{{ t('share.termsTitle') }}</component>
      <p class="share-name-intro">{{ t('share.termsIntro') }}</p>
      <div class="share-terms-body">
        <div class="markdown-body" v-safe-html="g.renderedTerms.value"></div>
        <OperatorSectionView v-if="g.operatorSection.value" :section="g.operatorSection.value" />
      </div>
      <button class="share-name-submit" type="button" @click="g.acceptTerms">{{ t('share.termsAccept') }}</button>
    </div>
  </div>

  <!-- The note's acceptance gate (uc.sharing.share-note): the curator's note + Accept, AFTER the terms and before
       anything of the share (every role). No decline: closing the tab is the decline. markdownHtml escapes first
       (safe subset), shown by v-safe-html. -->
  <div v-else-if="g.needsNote.value" class="share-view-state share-name-gate">
    <div class="veyq-surface share-name-card share-terms-card" data-testid="share-note-gate">
      <VeyqIcon name="envelope" class="share-terms-icon" />
      <component :is="heading">{{ t('share.noteGateTitle') }}</component>
      <p class="share-name-intro">{{ t('share.noteGateIntro') }}</p>
      <div class="share-terms-body markdown-body" v-safe-html="g.renderedNote.value"></div>
      <button class="share-name-submit" type="button" @click="g.acceptNote">{{ t('share.noteGateAccept') }}</button>
    </div>
  </div>

  <template v-else-if="g.head.value && g.bodyHead.value && g.body.value && g.access.value">
    <GuestNaming
      v-if="g.showsIdentity.value"
      class="share-view-identity"
      :current="g.session.value"
      :forget="g.forgetGuest"
      :record-days="g.head.value.share.guestWriteRecordDays"
    />
    <!-- Core's page of the link's tree (the member's views), or a WRITE_ONLY link's drop page (its naming too). -->
    <component
      :is="g.body.value"
      :share-id="shareId"
      :fragment-key="g.fragmentKey()"
      :pw-token="g.pwToken.value"
      :head="g.bodyHead.value"
      :reload="g.load"
      :access="g.access.value"
      v-bind="bodyExtra"
      @unavailable="g.status.value = 'unavailable'"
    />
    <GuestNaming
      v-if="g.naming.value"
      dialog
      :title="t('share.guest.nameTitle')"
      :hint="t('share.guest.nameHint')"
      :record-days="g.head.value.share.guestWriteRecordDays"
      :action="g.nameGuest"
      @close="g.onNamingClose"
    />
  </template>
</template>

<style scoped src="../../shared/ui/shareGate.css"></style>

<style scoped>
/* The state cards (loading, unavailable, password, terms, note) come from the shared gate sheet above; the page below the
   gates brings its own look. Moved here from the share page with the markup they style. */
.share-view-identity {
  margin-bottom: 0.75rem;
}
.share-view-unavailable .veyq-icon {
  font-size: 2rem;
}
.share-view-unavailable h1,
.share-view-unavailable h2 {
  margin: 0.25rem 0 0;
  font-size: 1.4rem;
}
.share-view-unavailable p {
  margin: 0;
  max-width: 30rem;
}
/* The terms gate reuses the centered card; its body is the safe-markdown terms, scrollable when long so the
   Accept button stays reachable. Wider than the name card since terms are prose. */
.share-terms-card {
  width: min(38rem, 100%);
}
.share-terms-icon {
  font-size: 1.5rem;
  color: var(--app-accent);
}
.share-terms-body {
  max-height: min(50vh, 26rem);
  overflow-y: auto;
  margin: 0.2rem 0 0.4rem;
  padding: 0.85rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-surface-muted);
  color: var(--app-text);
  font-size: 0.92rem;
  line-height: 1.55;
}
.share-terms-body :deep(h1) {
  font-size: 1.15rem;
  margin: 0 0 0.5rem;
}
.share-terms-body :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.share-terms-body :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
}
.share-terms-body :deep(p) {
  margin: 0 0 0.55rem;
}
.share-terms-body :deep(ul),
.share-terms-body :deep(ol) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
.share-terms-body :deep(a) {
  color: var(--app-accent);
}
</style>
