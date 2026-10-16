<script setup lang="ts">
// uc.admin.tenant-terms (web, cr-0114, ADR 0035) — the "Terms" rubric of the tenant settings. The tenant OWNER
// sets the server's terms as per-locale (DE+EN) markdown; a normalized change bumps the revision server-side,
// re-gating members (uc.account.accept-terms). Member-readable → non-owners see it read-only; the editor + Save
// are owner-only (the backend 403s otherwise). Operator-blind: terms are the operator's PUBLIC policy, no key.
//
// **The terms are a `LocaleMap`, not two `de`/`en` refs** (cr-terms-editor-lists-the-languages-that-have-terms).
// The two flat refs this panel used to hold could not tell `''` from *absent*, so it rendered a textarea for a
// language nobody had written and said nothing about whether that emptiness was deliberate. With the map, a
// key's presence IS the answer: the editor lists the languages that carry terms, and adding one is an act.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { getTenantTerms, updateTenantTerms, type LocalizedMarkdown } from './tenantTerms'
import { TERMS_TEMPLATE } from './termsTemplate'
import { listMyTenants } from '../spaces/scopes'
import { activeSpace } from '../../app/activeSpace'
import { renderMarkdown } from '../../shared/markdown'
import { useAsyncData, loadErrorKind } from '../../shared/state/useAsyncData'
import { useAsyncAction } from '../../shared/state/useAsyncAction'
import { useToast } from '../../shared/state/useToast'
import { useConfirm } from '../../shared/state/useConfirm'
import { handle401 } from '../../shared/handle401'
import { httpStatus, HttpStatus, isUnauthorized } from '../../shared/httpError'
import LocaleMarkdownEditor from '../../shared/ui/LocaleMarkdownEditor.vue'
import { localeLabel, type LocaleMap } from '../../shared/ui/localeMap'
import { SUPPORTED_LOCALES } from '../../i18n'

const { t, locale } = useI18n()
const toast = useToast()
const { ask } = useConfirm()
const on401 = handle401(useRouter())

const terms = ref<LocaleMap>({})
const revision = ref(0)
const isOwner = ref(false)
const actionError = ref('')

/**
 * The wire shape → the map: a locale is a KEY only if the server actually carries it. `LocalizedMarkdown` has
 * every locale optional and nullable, and both spellings of "not there" (`undefined`, `null`) mean absent —
 * collapsing them here is what keeps "this language has no terms" expressible at all. Iterated over
 * `SUPPORTED_LOCALES` so the rows come out in a stable order rather than in JSON key order.
 */
function toMap(wire: LocalizedMarkdown | undefined | null): LocaleMap {
  const map: LocaleMap = {}
  for (const loc of SUPPORTED_LOCALES) {
    const text = wire?.[loc]
    if (text !== undefined && text !== null) map[loc] = text
  }
  return map
}

/** Which languages the starter template can fill. Derived from the template itself, never a second list. */
const templated = (loc: string): loc is keyof typeof TERMS_TEMPLATE => loc in TERMS_TEMPLATE

// The terms in the active UI locale (fallback to the other) — drives the owner's live preview and the non-owner
// read-only view. `||` and not `??`: a language that exists but is still empty must fall through to one that
// has text, or the preview would go blank on a language somebody just added.
const previewBody = computed(() => terms.value[locale.value] || terms.value.en || terms.value.de || '')
const renderedPreview = computed(() => renderMarkdown(previewBody.value))

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  if (!activeSpace.tenantId) throw new Error('no-active-tenant') // no HTTP status → classified as 'failed' below
  const current = await getTenantTerms(activeSpace.tenantId)
  revision.value = current.termsRevision
  terms.value = toMap(current.terms)
  const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
  isOwner.value = mine?.role === 'OWNER'
}, { onError: on401 })
const loadError = computed<'' | 'failed'>(() => (loadErrorKind(loadErr.value) ? 'failed' : ''))

/**
 * Fill ONE language from the starter template, and ask first — in both directions.
 *
 * The old shape wrote the template into every empty locale at once and silently skipped the ones that already
 * held text: restraint the owner never asked for and could not see. Now the act names its language, and the
 * question names its consequence: inserting into an empty language is an offer, inserting over text somebody
 * wrote is a replacement, and the owner is told which one they are agreeing to.
 */
async function insertTemplate(loc: string) {
  if (!templated(loc)) return
  const lang = localeLabel(loc)
  const overwrites = (terms.value[loc] ?? '').trim().length > 0
  const accepted = await ask({
    header: t('terms.insertTemplate'),
    message: t(overwrites ? 'terms.insertTemplateOverwriteAsk' : 'terms.insertTemplateAsk', { lang }),
    acceptLabel: t('terms.insertTemplate'),
    acceptIcon: 'pi pi-file-plus',
  })
  if (!accepted) return
  terms.value = { ...terms.value, [loc]: TERMS_TEMPLATE[loc] }
}

/**
 * Which languages exist but still hold nothing. The backend drops a blank locale on normalize, so this is a
 * state the editor can show and the server will not keep — inbox #138's "the UI must not offer that state".
 * The answer is not to forbid the row (an added-but-unwritten language is a real, useful intermediate) but to
 * say out loud that it will not survive Save, which is the part the owner could not see before.
 */
const blankLocales = computed(() => Object.keys(terms.value).filter((loc) => !terms.value[loc].trim()))
const blankLocaleNames = computed(() => blankLocales.value.map(localeLabel).join(', '))

/** Dropping a published language is not the weight of dropping a share note, so the terms panel vetoes it. */
function confirmRemove(loc: string): Promise<boolean> {
  const lang = localeLabel(loc)
  return ask({
    header: t('localeEditor.removeLang', { lang }),
    message: t('terms.removeLangAsk', { lang }),
    acceptLabel: t('localeEditor.removeLang', { lang }),
    acceptIcon: 'pi pi-trash',
  })
}

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    // A language the map does not carry is sent as `undefined`, i.e. omitted — which is how the absence
    // reaches the server at all. Trap: the round-trip is NOT lossless in the other direction. The backend
    // drops a blank locale on normalize and treats an all-blank update as a no-op, so a language that was
    // added and left empty comes back absent, and removing the last language leaves the stored terms standing.
    // That is the right server behaviour (an empty language is not a published translation) — it is written
    // here because the reply below is what the editor re-renders from, so the owner sees the server's truth.
    const updated = await updateTenantTerms(activeSpace.tenantId!, { de: terms.value.de, en: terms.value.en })
    revision.value = updated.termsRevision
    terms.value = toMap(updated.terms)
    toast.success(t('terms.saved', { revision: updated.termsRevision }))
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
        <span v-if="revision > 0" class="veyq-badge">{{ t('terms.revision', { revision }) }}</span>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('terms.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('terms.loadFailed') }}</Message>

      <!-- Owner: the per-locale markdown editor + a live preview. Non-owner: the rendered terms, read-only. -->
      <template v-else-if="isOwner">
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

        <!-- One row per language that HAS terms, plus an explicit act to add one that does not. The starter
             template hangs in the row's own actions, because it fills that one language and asks first. -->
        <LocaleMarkdownEditor
          v-model="terms"
          :placeholder="t('terms.placeholder')"
          :before-remove="confirmRemove"
        >
          <template #row-actions="{ locale: loc }">
            <Button
              v-if="templated(loc)"
              type="button"
              :label="t('terms.insertTemplate')"
              icon="pi pi-file-plus"
              text
              severity="secondary"
              size="small"
              :disabled="saving"
              @click="insertTemplate(loc)"
            />
          </template>
        </LocaleMarkdownEditor>
        <Message v-if="blankLocales.length" severity="warn" :closable="false">
          {{ t('terms.blankLocaleWarning', { langs: blankLocaleNames }) }}
        </Message>
        <small class="settings-muted">{{ t('terms.markdownHint', { contact: '{contact}' }) }}</small>
        <div class="terms-editor-actions">
          <Button :label="t('terms.save')" icon="pi pi-save" :loading="saving" @click="save" />
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
