<script setup lang="ts">
// uc.account.accept-terms (web, cr-0114, ADR 0035) — the member terms accept-gate. A standalone full-screen card
// (no app shell): the router bounces any member whose accepted_terms_version is below the active tenant's current
// version here, and they can only ACCEPT (records it server-side → returns them where they were headed) or SIGN
// OUT. Operator-blind: the terms are the operator's PUBLIC policy; acceptance is coarse operational metadata.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import {
  getTenantTerms,
  acceptTenantTerms,
  tenantError,
  type TenantTerms,
} from '../features/admin/createTenant/tenants'
import { activeSpace, markTermsAccepted, clearActiveSpace } from '../app/activeSpace'
import { takeRoute } from '../app/pendingRoute'
import { logout } from '../features/account/login/login'
import { resetServerRole } from '../features/admin/serverRole'
import { renderMarkdown } from '../shared/markdown'
import { handle401 } from '../shared/handle401'
import { isUnauthorized } from '../shared/httpError'

const { t, locale } = useI18n()
const router = useRouter()
const on401 = handle401(router)

type Phase = 'loading' | 'ready' | 'loadFailed' | 'done'
const phase = ref<Phase>('loading')
const terms = ref<TenantTerms | null>(null)
const error = ref('')
const busy = ref(false)

// The terms text in the active UI locale, falling back to the other locale so a tenant that filled only one is
// never shown blank (the server applies the same fallback intent; here we pick client-side from the returned map).
const body = computed(() => {
  const tm = terms.value?.terms
  if (!tm) return ''
  const preferred = locale.value === 'de' ? tm.de : tm.en
  return preferred || tm.en || tm.de || ''
})

// Rendered to a safe HTML subset (escape-first — no raw HTML/script survives), so `v-html` is safe here.
const renderedBody = computed(() => renderMarkdown(body.value))

onMounted(async () => {
  // Reached directly (a reload on /terms) before the shell resolved a tenant → bounce to home; the shell enters
  // the tenant and the router re-fires the gate with a tenant in hand.
  if (!activeSpace.tenantId) {
    void router.replace({ name: 'home' })
    return
  }
  await load()
})

async function load() {
  phase.value = 'loading'
  error.value = ''
  try {
    terms.value = await getTenantTerms(activeSpace.tenantId!)
    phase.value = 'ready'
  } catch (e) {
    on401(e)
    if (!isUnauthorized(e)) phase.value = 'loadFailed'
  }
}

async function accept() {
  if (!terms.value) return
  error.value = ''
  busy.value = true
  try {
    const updated = await acceptTenantTerms(activeSpace.tenantId!, terms.value.termsVersion)
    markTermsAccepted(updated.acceptedVersion) // clears termsStale() so the guard lets the member back in
    phase.value = 'done'
    // Return the member to where the guard intercepted them (remembered deep link), else the shell home.
    await router.push(takeRoute() ?? { name: 'home' })
  } catch (e) {
    on401(e)
    if (isUnauthorized(e)) return
    const { code } = await tenantError(e)
    if (code === 'terms_version_changed') {
      // The operator bumped the terms while this was open — re-load + re-present the newer version.
      error.value = t('terms.versionChanged')
      await load()
    } else {
      error.value = t('terms.acceptFailed')
    }
  } finally {
    busy.value = false
  }
}

// The only way out without accepting: sign out (mirrors ProfileMenu). Declining records nothing; the member
// stays out of the shell.
async function signOut() {
  try {
    await logout()
  } finally {
    resetServerRole()
    clearActiveSpace()
    await router.push({ name: 'login' })
  }
}
</script>

<template>
  <Card class="terms-card">
    <template #title>{{ t('terms.gateTitle') }}</template>
    <template #content>
      <p v-if="phase === 'loading'" class="muted">{{ t('terms.loading') }}</p>

      <Message v-else-if="phase === 'loadFailed'" severity="error" :closable="false">{{ t('terms.loadFailed') }}</Message>

      <template v-else-if="phase === 'ready'">
        <p class="veyq-form-copy">{{ t('terms.gateIntro') }}</p>
        <!-- eslint-disable-next-line vue/no-v-html — renderMarkdown escapes first (safe subset, no raw HTML). -->
        <div class="terms-body markdown-body" v-html="renderedBody"></div>
        <Message v-if="error" severity="warn" :closable="false">{{ error }}</Message>
        <div class="terms-actions">
          <Button :label="t('terms.accept')" icon="pi pi-check" :loading="busy" @click="accept" />
          <Button
            :label="t('terms.signOut')"
            icon="pi pi-sign-out"
            severity="secondary"
            text
            :disabled="busy"
            @click="signOut"
          />
        </div>
      </template>

      <Message v-else-if="phase === 'done'" severity="success" :closable="false">{{ t('terms.accepted') }}</Message>
    </template>
  </Card>
</template>

<style scoped>
.muted {
  opacity: 0.7;
}
.terms-body {
  max-height: 50vh;
  overflow-y: auto;
  padding: 0.85rem 1rem;
  margin: 0.75rem 0;
  border: 1px solid var(--app-line);
  border-radius: 0.6rem;
  font-size: 0.92rem;
  line-height: 1.5;
}
/* Rendered markdown subset (headings 1-3, paragraphs, lists, inline). */
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
.terms-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
