<script setup lang="ts">
// The console — and the root Server-Owner gate (ADR 0065). This is the artifact the extraction had to
// RECONSTRUCT: in `web/` the same probe only decided whether to show a link, so a non-owner never met a
// refusal at all. Here `/` is the admin surface, so it answers first and answers in words:
//
//   forbidden      -> the backend's own 403, restated for a person. Not a redirect: a Server Owner who
//                     mistyped an account should see WHY, and a non-owner should see that the refusal is the
//                     server's, not this page's politeness.
//   unauthenticated-> back to sign-in (the session lapsed between boot and here)
//   unreachable    -> say so, rather than render an empty console
//
// The gate is a courtesy over an authority that lives elsewhere: every call below is authorized server-side
// on its own (403), so a stale or subverted verdict here can expose nothing.
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import Message from 'primevue/message'
import CreateTenantDialog from '../features/tenants/CreateTenantDialog.vue'
import TenantDetailPanel from '../features/tenants/TenantDetailPanel.vue'
import ReleaseVersionPanel from '../features/instance/ReleaseVersionPanel.vue'
import StoreProvisioningPanel from '../features/instance/StoreProvisioningPanel.vue'
import { listTenants, type TenantSummary } from '../features/tenants/tenants'
import { formatBytes } from '../shared/formatBytes'
import { probeServerRole, serverRole } from '../app/serverRole'
import { logout } from '../features/auth/login'

const router = useRouter()
const { t, locale } = useI18n()

/** A tenant's usage beside its quota, for the list (uc.admin.tenant-storage-quota): "3 MB of 10 GB", or no quota. */
function storageLine(tn: TenantSummary): string {
  const used = formatBytes(tn.storageUsedBytes, locale.value)
  return tn.storageQuotaBytes == null
    ? t('admin.storageNoQuota', { used })
    : t('admin.storageOfQuota', { used, quota: formatBytes(tn.storageQuotaBytes, locale.value) })
}

const tenants = ref<TenantSummary[]>([])
const createOpen = ref(false)
const selected = ref<string | null>(null)
// One rubric at a time under `Server`, so a second boolean cannot leave two panels open at once.
const serverPanel = ref<'release' | 'store' | null>(null)
const loading = ref(true)

const isOwner = computed(() => serverRole.verdict === 'owner')

async function loadTenants() {
  tenants.value = await listTenants()
  if (selected.value && !tenants.value.some((tn) => tn.tenantId === selected.value)) selected.value = null
}

onMounted(async () => {
  const verdict = await probeServerRole()
  if (verdict === 'unauthenticated') {
    await router.replace({ name: 'login' })
    return
  }
  if (verdict === 'owner') await loadTenants().catch(() => {})
  loading.value = false
})

async function onCreated() {
  // The dialog deliberately stays OPEN. On success it switches to the copy-link view, and for a PENDING
  // tenant that link is the ONLY way the invite reaches its owner: a tenant has no mail settings until its
  // owner configures them, so no other channel exists yet (ADR 0055 §3). Closing here would refresh the list
  // and silently throw away the one artifact the Server Owner came for. Caught by driving the real container:
  // the tenant appeared in the list and the link did not.
  await loadTenants().catch(() => {})
}

async function signOut() {
  await logout().catch(() => {})
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="admin-shell">
    <header class="admin-shell__bar">
      <div>
        <h1 class="admin-shell__title">{{ t('admin.title') }}</h1>
        <p class="admin-shell__sub">{{ t('admin.sub') }}</p>
      </div>
      <Button
        type="button"
        severity="secondary"
        text
        icon="pi pi-sign-out"
        :label="t('admin.signOut')"
        @click="signOut"
      />
    </header>

    <p v-if="loading" class="admin-shell__note">{{ t('admin.loading') }}</p>

    <!-- The refusal surface. `severity="error"` and the server's own reason, so the page never reads as a
         glitch — a 403 is an answer, not a failure. -->
    <Card v-else-if="serverRole.verdict === 'forbidden'" class="login-card">
      <template #title>{{ t('admin.forbidden') }}</template>
      <template #content>
        <p class="veyq-form-copy">{{ t('admin.forbiddenSub') }}</p>
        <Button
          type="button"
          severity="secondary"
          icon="pi pi-sign-out"
          :label="t('admin.signOut')"
          @click="signOut"
        />
      </template>
    </Card>

    <Message v-else-if="serverRole.verdict === 'unreachable'" severity="error" :closable="false">
      {{ t('admin.unreachable') }}
    </Message>

    <div v-else-if="isOwner" class="admin-shell__body">
      <nav class="admin-shell__nav">
        <h2>{{ t('admin.tenants') }}</h2>
        <p v-if="!tenants.length" class="admin-shell__note">{{ t('admin.noTenants') }}</p>
        <ul v-else>
          <li v-for="tn in tenants" :key="tn.tenantId">
            <button
              type="button"
              :class="{ 'is-active': selected === tn.tenantId && !serverPanel }"
              @click="selected = tn.tenantId; serverPanel = null"
            >
              <span class="pi pi-server" aria-hidden="true" />
              <span class="admin-shell__tenant">
                {{ tn.name }}
                <small class="admin-shell__storage">{{ storageLine(tn) }}</small>
              </span>
            </button>
          </li>
        </ul>
        <Button
          type="button"
          text
          icon="pi pi-plus"
          :label="t('tenant.create')"
          @click="createOpen = true"
        />
        <h2>{{ t('admin.server') }}</h2>
        <ul>
          <li>
            <button type="button" :class="{ 'is-active': serverPanel === 'release' }" @click="serverPanel = 'release'">
              <span class="pi pi-tag" aria-hidden="true" /> {{ t('release.navTitle') }}
            </button>
          </li>
          <li>
            <button type="button" :class="{ 'is-active': serverPanel === 'store' }" @click="serverPanel = 'store'">
              <span class="pi pi-database" aria-hidden="true" /> {{ t('store.navTitle') }}
            </button>
          </li>
        </ul>
      </nav>

      <section class="admin-shell__detail">
        <ReleaseVersionPanel v-if="serverPanel === 'release'" />
        <StoreProvisioningPanel v-else-if="serverPanel === 'store'" />
        <TenantDetailPanel v-else-if="selected" :key="selected" :tenant-id="selected" />
      </section>
    </div>

    <CreateTenantDialog v-model:visible="createOpen" @created="onCreated" />
  </div>
</template>

<style scoped>
.admin-shell {
  max-width: 68rem;
  margin: 0 auto;
  padding: 1.5rem 1rem 3rem;
}
/* The refusal branch's card is 460px wide (`styles/forms.css` § .login-card) inside this 68rem column — left
   unset it clings to the left edge, which makes an answered `403` read as a half-rendered page. */
.admin-shell > .login-card {
  margin-inline: auto;
}
.admin-shell__bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1.5rem;
}
.admin-shell__title {
  margin: 0;
  font-size: 1.4rem;
}
.admin-shell__sub,
.admin-shell__note {
  margin: 0.25rem 0 0;
  color: var(--app-muted);
  font-size: 0.92rem;
}
.admin-shell__body {
  display: grid;
  grid-template-columns: minmax(12rem, 18rem) 1fr;
  gap: 1.5rem;
  align-items: start;
}
.admin-shell__nav h2 {
  margin: 1rem 0 0.4rem;
  font-size: 0.78rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--app-muted);
}
.admin-shell__nav ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.admin-shell__nav button {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  width: 100%;
  padding: 0.45rem 0.6rem;
  border: none;
  border-radius: var(--app-radius);
  background: transparent;
  color: var(--app-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.admin-shell__tenant {
  display: grid;
}
.admin-shell__storage {
  color: var(--app-muted);
  font-weight: 400;
}
.admin-shell__nav button:hover {
  background: var(--app-hover);
}
.admin-shell__nav button.is-active {
  background: var(--app-accent-soft);
  color: var(--app-accent);
  font-weight: 600;
}
@media (max-width: 720px) {
  .admin-shell__body {
    grid-template-columns: 1fr;
  }
}
</style>
