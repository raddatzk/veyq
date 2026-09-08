<script setup lang="ts">
// uc.admin.tenant-terms (web, cr-0114, ADR 0035) — the "Terms" rubric of the tenant settings. The tenant OWNER
// sets the server's terms as per-locale (DE+EN) markdown; a normalized change bumps the version server-side,
// re-gating members (uc.account.accept-terms). Member-readable → non-owners see it read-only; the editor + Save
// are owner-only (the backend 403s otherwise). Operator-blind: terms are the operator's PUBLIC policy, no key.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { getTenantTerms, updateTenantTerms } from './tenantTerms'
import { TERMS_TEMPLATE } from './termsTemplate'
import { listMyTenants } from '../spaces/scopes'
import { activeSpace } from '../../app/activeSpace'
import { renderMarkdown } from '../../shared/markdown'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'
import { handle401 } from '../../shared/handle401'
import { httpStatus, HttpStatus, isUnauthorized } from '../../shared/httpError'

const { t, locale } = useI18n()
const toast = useToast()
const on401 = handle401(useRouter())

const de = ref('')
const en = ref('')
const version = ref(0)
const isOwner = ref(false)
const actionError = ref('')

// The terms in the active UI locale (fallback to the other) — drives the owner's live preview and the non-owner
// read-only view. Rendered to a safe HTML subset (escape-first), so `v-html` is safe.
const previewBody = computed(() => (locale.value === 'de' ? de.value : en.value) || en.value || de.value || '')
const renderedPreview = computed(() => renderMarkdown(previewBody.value))

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  if (!activeSpace.tenantId) throw new Error('no-active-tenant') // no HTTP status → classified as 'failed' below
  const terms = await getTenantTerms(activeSpace.tenantId)
  version.value = terms.termsVersion
  de.value = terms.terms?.de ?? ''
  en.value = terms.terms?.en ?? ''
  const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
  isOwner.value = mine?.role === 'OWNER'
}, { onError: on401 })
const loadError = computed<'' | 'failed'>(() => (loadErrorKind(loadErr.value) ? 'failed' : ''))

/** Seed an empty locale from the clause template (never clobbers text the owner already wrote). */
function insertTemplate() {
  if (!de.value.trim()) de.value = TERMS_TEMPLATE.de
  if (!en.value.trim()) en.value = TERMS_TEMPLATE.en
}

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    const updated = await updateTenantTerms(activeSpace.tenantId!, { de: de.value, en: en.value })
    version.value = updated.termsVersion
    de.value = updated.terms?.de ?? ''
    en.value = updated.terms?.en ?? ''
    toast.success(t('terms.saved', { version: updated.termsVersion }))
  },
  {
    onError: (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      actionError.value = httpStatus(e) === HttpStatus.forbidden ? t('terms.notOwner') : t('terms.saveFailed')
    },
  },
)

async function save() {
  actionError.value = ''
  await runSave()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('terms.navTitle') }}</h2>
          <p>{{ t('terms.editorSub') }}</p>
        </div>
        <span v-if="version > 0" class="veyq-badge">{{ t('terms.version', { version }) }}</span>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('terms.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('terms.loadFailed') }}</Message>

      <!-- Owner: the per-locale markdown editor + a live preview. Non-owner: the rendered terms, read-only. -->
      <template v-else-if="isOwner">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

        <div class="locale-markdown-editor">
          <div class="locale-markdown-editor-entry">
            <span>{{ t('terms.localeDe') }}</span>
            <textarea v-model="de" rows="12" :placeholder="t('terms.placeholder')"></textarea>
          </div>
          <div class="locale-markdown-editor-entry">
            <span>{{ t('terms.localeEn') }}</span>
            <textarea v-model="en" rows="12" :placeholder="t('terms.placeholder')"></textarea>
          </div>
        </div>
        <small class="settings-muted">{{ t('terms.markdownHint') }}</small>
        <div class="terms-editor-actions">
          <Button :label="t('terms.save')" icon="pi pi-save" :loading="saving" @click="save" />
          <Button
            :label="t('terms.insertTemplate')"
            icon="pi pi-file-plus"
            severity="secondary"
            outlined
            :disabled="saving"
            @click="insertTemplate"
          />
        </div>

        <div v-if="previewBody" class="terms-preview">
          <span class="settings-muted terms-preview-label">{{ t('terms.preview') }}</span>
          <!-- eslint-disable-next-line vue/no-v-html — renderMarkdown escapes first (safe subset). -->
          <div class="markdown-body" v-html="renderedPreview"></div>
        </div>
      </template>

      <template v-else>
        <Message severity="info" :closable="false">{{ t('terms.readonlyNote') }}</Message>
        <!-- eslint-disable-next-line vue/no-v-html — renderMarkdown escapes first (safe subset). -->
        <div v-if="previewBody" class="markdown-body terms-readonly" v-html="renderedPreview"></div>
        <p v-else class="settings-muted">{{ t('terms.noneSet') }}</p>
      </template>
    </section>
  </section>
</template>

<style scoped>

.terms-editor-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
.locale-markdown-editor-entry textarea {
  min-height: 14rem;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
  line-height: 1.5;
}
.terms-preview {
  margin-top: 1rem;
  display: grid;
  gap: 0.4rem;
}
.terms-preview-label {
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}
.markdown-body {
  padding: 0.85rem 1rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  font-size: 0.92rem;
  line-height: 1.55;
}
.markdown-body :deep(h1) {
  font-size: 1.15rem;
  margin: 0 0 0.5rem;
}
.markdown-body :deep(h2) {
  font-size: 1rem;
  margin: 1rem 0 0.35rem;
}
.markdown-body :deep(h3) {
  font-size: 0.92rem;
  margin: 0.85rem 0 0.3rem;
}
.markdown-body :deep(p) {
  margin: 0 0 0.6rem;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 0 0 0.6rem;
  padding-left: 1.3rem;
}
.markdown-body :deep(li) {
  margin: 0.15rem 0;
}
.markdown-body :deep(code) {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
}
.markdown-body :deep(a) {
  color: var(--p-primary-color, inherit);
}
</style>
