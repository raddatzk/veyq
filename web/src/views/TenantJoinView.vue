<script setup lang="ts">
// uc.admin.create-tenant Slice B (cr-0010) — the invited owner accepts and activates the tenant. The
// bootstrap secret rides in the URL #fragment (never sent until accept). preview tells us whether the
// invited email already has an account: a NEW owner registers cold (Emergency Kit) here, an EXISTING owner
// signs in + unlocks. Either way the client cold-births the TENANT scope and seals the first grant to its
// own identity — the scope key never leaves the device.
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { useBreachCheck } from '../features/account/passwordWarning'
import PasswordBreachNotice from '../features/account/PasswordBreachNotice.vue'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { previewInvite, acceptInvite, registerAndAccept, bornTenantScope, tenantError, type TenantBirth, type TenantOwner } from '../features/tenant/tenantInvite'
import { sealName, sealAvatar, myUserId } from '../features/account/profile/profile'
import JoinPicturePicker from '../features/account/profile/JoinPicturePicker.vue'
import EmergencyKitSave from '../features/account/emergencyKit/EmergencyKitSave.vue'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import { InviteRole } from '../api/generated/tenants'
import { buildAccount, type BuiltAccount } from '@veyq/crypto/accountBirth'
import { login } from '../features/account/login/login'
import { withRefresh } from '../app/sessionRefresh'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { identityPrivate, vaultState } from '../app/vault'
import { account } from '../app/account'
import { setupApi } from '../api/client'
import { fromB64std } from '@veyq/crypto/base64'
import { identityPublicFromPrivate } from '@veyq/crypto/v1'
import { HttpStatus, isUnauthorized } from '../shared/httpError'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

type Phase = 'loading' | 'missing' | 'invalid' | 'expired' | 'register' | 'kit' | 'signin' | 'done'
const phase = ref<Phase>('loading')
const error = ref('')
const busy = ref(false)

const tenantName = ref('')
// The tenant's id — and so its TENANT scope's id — known from the preview before the scope's birth; the grant and
// the optional sealed name are bound to it (ADR 0072).
const tenantId = ref('')
const domain = ref('')
const ownerEmail = ref('')
// The invite's role (cr-0077): an OWNER cold-births the TENANT scope + seals the first grant; a MEMBER joins
// grant-less (no scope birth, no grant) and a key-holder delivers the key later (cr-0076).
const role = ref<InviteRole>(InviteRole.Owner)
const isMember = computed(() => role.value === InviteRole.Member)

// Role-aware copy: a MEMBER joins the shared space, an OWNER takes ownership. Falls back to the base (owner)
// key when no `<base>Member` variant exists.
function jcopy(base: string, params?: Record<string, unknown>): string {
  return t(`tenant.join.${isMember.value ? `${base}Member` : base}`, params ?? {})
}

// register + signin form: the per-tenant handle chosen on join (ADR 0023), plus an OPTIONAL real name. For an
// OWNER accept the client cold-births K_tenant here, so it can seal the name at join (cr-0106); a grant-less
// MEMBER has no key yet, so its name field is hidden and the name is added later in profile settings.
const username = ref('')
const firstName = ref('')
const lastName = ref('')
// The optional picture's encoded bytes (OWNER only, ADR 0084): sealed with the born tenant key at accept, never held anywhere else.
const avatarBytes = shallowRef<Uint8Array | null>(null)
const password = ref('')
const { state: breachState, useAnyway, checkNow: checkBreaches, proceed: breachCheckPasses } = useBreachCheck(password)
const secretKeyDisplay = ref('')
const kitReady = ref(false)
// sign-in form
const secretKey = ref('')
// Fast path: the session lapsed while the vault stayed unlocked → ask for the password only (re-login), never
// the Secret Key (the identity key is already in memory).
const needsReauth = ref(false)

// This browser already holds the INVITED owner's unlocked vault (same-origin setup → create-tenant flow):
// the identity key is in memory, so we can seal the grant and accept in one click — no password, no Secret
// Key. Gated on the email so we never seal to a different signed-in identity (the server also enforces this).
const alreadyUnlocked = computed(
  () => vaultState.unlocked && account.email.trim().toLowerCase() === ownerEmail.value.trim().toLowerCase(),
)

const secret = String(route.hash || '').replace(/^#/, '')
// Non-reactive: keep the freshly born identity's Uint8Arrays out of Vue's deep proxy.
let built: BuiltAccount | null = null

onMounted(async () => {
  if (!secret) {
    // Not "invalid": the ordinary way here is a reload after the strip below, with a link that still works.
    phase.value = 'missing'
    return
  }
  // Strip the live secret from the address bar now that it has been read — it stays valid until accept, and a
  // register runs through the Emergency Kit screen, the one screen people are told to screenshot. Pass the
  // router's own history.state through: vue-router keeps its position there, and a null state is what it warns
  // about.
  history.replaceState(history.state, '', window.location.pathname + window.location.search)
  try {
    const p = await previewInvite(secret)
    tenantName.value = p.tenantName
    tenantId.value = p.tenantId
    domain.value = p.domain
    ownerEmail.value = p.ownerEmail
    role.value = p.role
    phase.value = p.hasAccount ? 'signin' : 'register'
  } catch (e) {
    const { status } = await tenantError(e)
    phase.value = status === HttpStatus.gone ? 'expired' : 'invalid'
  }
})

/** Register step 1: build the account on-device and show its Emergency Kit before activating. */
async function prepareRegistration() {
  // The length floor belongs to THIS branch only. `password` is one ref serving three different acts in this
  // view — register (here), sign in as an existing user, and re-authenticate after a lapsed session — and
  // only the first one SETS a password. Gating the other two would lock out an account whose password
  // predates the policy, on a screen whose whole job is letting them in. Client-side by construction, too:
  // under Model B (ADR 0003) the password never leaves the device, so there is no server rule to mirror.
  if (!username.value.trim() || password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  busy.value = true
  try {
    if (!(await breachCheckPasses())) return
    const instanceSalt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
    built = await buildAccount(ownerEmail.value, password.value, instanceSalt)
    secretKeyDisplay.value = built.emergencyKit.secretKey
    phase.value = 'kit'
  } catch {
    error.value = t('tenant.join.failed')
  } finally {
    busy.value = false
  }
}

/** Seal the OPTIONAL real name under the freshly-born K_tenant, or undefined when both fields are empty. Only
 *  called on the OWNER path (which holds K_tenant at accept); a grant-less MEMBER never reaches it. */
function sealedNameFor(scopeRootKey: Uint8Array, userId: string): string | undefined {
  if (!firstName.value.trim() && !lastName.value.trim()) return undefined
  // Bound to (this tenant scope, the member it names) — ADR 0072.
  return sealName(scopeRootKey, { scopeId: tenantId.value, userId }, { firstName: firstName.value, lastName: lastName.value })
}

/** Seal the OPTIONAL picture under the same freshly-born K_tenant (`member-avatar`, bound to the tenant scope and the member), or
 *  undefined when none was chosen. OWNER path only - a MEMBER's body never carries it (it holds no key at join). */
function sealedAvatarFor(scopeRootKey: Uint8Array, userId: string): string | undefined {
  return avatarBytes.value ? sealAvatar(scopeRootKey, { scopeId: tenantId.value, userId }, avatarBytes.value) : undefined
}

/** The unlocked vault's identity as the tenant's birth needs it: the grant is sealed to its public bundle and signed
 *  by its signing half (ADR 0028 Decision 6), under the owner's own account id. */
function ownerOf(userId: string, priv: { kemX: Uint8Array; mlkemSeed: Uint8Array; sign: Uint8Array }): TenantOwner {
  return { userId, identity: { pub: identityPublicFromPrivate(priv), priv } }
}

/** Register step 2: cold-birth the scope grant to the new identity and register-and-accept. */
async function finishRegistration() {
  if (!built || !kitReady.value) return
  error.value = ''
  busy.value = true
  try {
    // A MEMBER joins grant-less (no scope birth, no name); only an OWNER cold-births the TENANT scope + seals a
    // grant — and, holding K_tenant here, can seal the optional real name at join (cr-0106).
    // The new account's id is minted here, before the account exists — the optional name is sealed bound to it.
    const userId = crypto.randomUUID()
    let birth: TenantBirth | undefined
    let sealedName: string | undefined
    let sealedAvatar: string | undefined
    if (!isMember.value) {
      const born = bornTenantScope({ userId, identity: built.identity }, tenantId.value)
      birth = born.birth
      sealedName = sealedNameFor(born.scopeRootKey, userId)
      sealedAvatar = sealedAvatarFor(born.scopeRootKey, userId)
    }
    await registerAndAccept(secret, userId, username.value.trim().toLowerCase(), built.material, birth, sealedName, sealedAvatar)
    phase.value = 'done'
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/** Existing owner: sign in, unlock (identity key), then seal the grant to it and accept. */
async function signInAndAccept() {
  if (password.value.length === 0 || secretKey.value.trim().length === 0) return
  error.value = ''
  busy.value = true
  try {
    await login(ownerEmail.value, password.value)
    await unlockMasterKey(password.value, secretKey.value.trim())
    const priv = identityPrivate()
    if (!priv) throw new Error('locked')
    let birth: TenantBirth | undefined
    let sealedName: string | undefined
    let sealedAvatar: string | undefined
    if (!isMember.value) {
      const userId = await myUserId()
      const born = bornTenantScope(ownerOf(userId, priv), tenantId.value)
      birth = born.birth
      sealedName = sealedNameFor(born.scopeRootKey, userId)
      sealedAvatar = sealedAvatarFor(born.scopeRootKey, userId)
    }
    await acceptInvite(secret, username.value.trim().toLowerCase(), birth, sealedName, sealedAvatar)
    phase.value = 'done'
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/** With an already-unlocked identity in memory, cold-birth the scope grant and accept. acceptInvite isn't
 *  withRefresh-wrapped itself, so wrap it here: an expired access token rotates silently from the cookie. */
async function sealAndAccept() {
  const priv = identityPrivate()
  if (!priv) throw new Error('locked')
  let birth: TenantBirth | undefined
  let sealedName: string | undefined
  let sealedAvatar: string | undefined
  if (!isMember.value) {
    const userId = await myUserId()
    const born = bornTenantScope(ownerOf(userId, priv), tenantId.value)
    birth = born.birth
    sealedName = sealedNameFor(born.scopeRootKey, userId)
    sealedAvatar = sealedAvatarFor(born.scopeRootKey, userId)
  }
  await withRefresh(() => acceptInvite(secret, username.value.trim().toLowerCase(), birth, sealedName, sealedAvatar))
  password.value = ''
  phase.value = 'done'
}

/** A "no live session" failure (access expired AND the silent refresh had nothing to rotate) — distinct from
 *  a real invite error (400 malformed grant / 403 / 404 / 410), which must surface via mapError instead.
 *  Only a 401 means the session lapsed: withRefresh already rotated once and still got 401, or the refresh
 *  endpoint itself rejected. Delegates to the shared `isUnauthorized` (the same cross-package status duck-type
 *  as the app's withRefresh), just under a name that carries the invite-flow meaning. */
function isAuthLapse(e: unknown): boolean {
  return isUnauthorized(e)
}

/** One-click accept when this browser already holds the invited owner's unlocked vault. */
async function acceptDirect() {
  error.value = ''
  busy.value = true
  try {
    await sealAndAccept()
  } catch (e) {
    // Vault unlocked but the session is gone and the cookie couldn't refresh it → fall to a password-only
    // re-login (the Secret Key stays unnecessary). Anything else is a real invite error.
    if (isAuthLapse(e)) needsReauth.value = true
    else error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/** Session-lapsed branch: re-establish the session with the password only (the vault is still unlocked, so no
 *  Secret Key), then seal + accept. */
async function reauthAndAccept() {
  if (password.value.length === 0) return
  error.value = ''
  busy.value = true
  try {
    await login(ownerEmail.value, password.value)
    await sealAndAccept()
    needsReauth.value = false
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

async function mapError(e: unknown): Promise<string> {
  const { status, code } = await tenantError(e)
  if (status === HttpStatus.gone) return t('tenant.join.expired')
  if (status === HttpStatus.notFound) return t('tenant.join.invalid')
  if (code === 'email_taken') return t('tenant.join.emailTaken')
  if (code === 'username_taken') return t('tenant.join.usernameTaken')
  if (code === 'not_invited_owner') return t('tenant.join.wrongOwner')
  return t('tenant.join.failed')
}
</script>

<template>
  <Card class="join-card">
    <template #title>{{ t('tenant.join.title') }}</template>
    <template #content>
      <p v-if="phase === 'loading'" class="muted">{{ t('tenant.join.loading') }}</p>

      <Message v-else-if="phase === 'missing'" severity="warn" :closable="false">{{ t('tenant.join.missing') }}</Message>
      <Message v-else-if="phase === 'invalid'" severity="error" :closable="false">{{ t('tenant.join.invalid') }}</Message>
      <Message v-else-if="phase === 'expired'" severity="warn" :closable="false">{{ t('tenant.join.expired') }}</Message>

      <template v-else-if="phase === 'register'">
        <p class="veyq-form-copy">{{ jcopy('invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="veyq-form-copy">{{ jcopy('registerSub', { email: ownerEmail }) }}</p>
        <form class="veyq-form" @submit.prevent="prepareRegistration">
          <label>{{ t('tenant.join.username') }}
            <InputText v-model="username" name="username" autocomplete="username" required />
            <small class="field-hint">{{ t('tenant.join.usernameHint') }}</small>
          </label>
          <template v-if="!isMember">
            <div class="name-row">
              <label>{{ t('profile.firstName') }}
                <InputText v-model="firstName" name="given-name" autocomplete="given-name" />
              </label>
              <label>{{ t('profile.lastName') }}
                <InputText v-model="lastName" name="family-name" autocomplete="family-name" />
              </label>
            </div>
            <small class="field-hint">{{ t('tenant.join.nameHint') }}</small>
            <JoinPicturePicker v-model="avatarBytes" :name="`${firstName} ${lastName}`.trim() || username" />
          </template>
          <label>{{ t('common.password') }}<Password v-model="password" :feedback="false" toggleMask inputId="join-password" @blur="checkBreaches" /></label>
          <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
          <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="join" :disabled="busy" />
          <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
          <Button type="submit" :label="t('tenant.join.continue')" icon="pi pi-arrow-right" :loading="busy"
                  :disabled="!username.trim() || password.length < MIN_PASSWORD_LENGTH" />
        </form>
      </template>

      <template v-else-if="phase === 'kit'">
        <Message severity="info" :closable="false">{{ t('tenant.join.saveKitTitle') }}</Message>
        <EmergencyKitSave :email="ownerEmail" :secret-key="secretKeyDisplay" v-model="kitReady">
          <p class="veyq-form-copy">{{ t('tenant.join.saveKitSub') }}</p>
        </EmergencyKitSave>
        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <Button :label="jcopy('join')" icon="pi pi-check" :loading="busy" :disabled="!kitReady" @click="finishRegistration" />
      </template>

      <template v-else-if="phase === 'signin'">
        <p class="veyq-form-copy">{{ jcopy('invitedTo', { tenant: tenantName, domain }) }}</p>

        <!-- The per-tenant handle (ADR 0023): an existing account still picks a username for THIS tenant. -->
        <label class="veyq-form-field">{{ t('tenant.join.username') }}
          <InputText v-model="username" name="username" autocomplete="username" required />
          <small class="field-hint">{{ t('tenant.join.usernameHint') }}</small>
        </label>
        <template v-if="!isMember">
          <div class="name-row">
            <label class="veyq-form-field">{{ t('profile.firstName') }}
              <InputText v-model="firstName" name="given-name" autocomplete="given-name" />
            </label>
            <label class="veyq-form-field">{{ t('profile.lastName') }}
              <InputText v-model="lastName" name="family-name" autocomplete="family-name" />
            </label>
          </div>
          <small class="field-hint">{{ t('tenant.join.nameHint') }}</small>
          <JoinPicturePicker v-model="avatarBytes" :name="`${firstName} ${lastName}`.trim() || username" />
        </template>

        <!-- Fast path: this browser already holds the invited owner's unlocked vault → accept in one click,
             or ask for the password only if the session lapsed. Never the Secret Key. -->
        <template v-if="alreadyUnlocked">
          <template v-if="needsReauth">
            <p class="veyq-form-copy">{{ t('tenant.join.reauthSub') }}</p>
            <form class="veyq-form" @submit.prevent="reauthAndAccept">
              <label>{{ t('common.password') }}<Password v-model="password" :feedback="false" toggleMask inputId="reauth-password" /></label>
              <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
              <Button type="submit" :label="jcopy('join')" icon="pi pi-check" :loading="busy"
                      :disabled="!username.trim() || password.length === 0" />
            </form>
          </template>
          <template v-else>
            <p class="veyq-form-copy">{{ jcopy('signinReadySub', { email: ownerEmail }) }}</p>
            <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
            <Button :label="jcopy('join')" icon="pi pi-check" :loading="busy" :disabled="!username.trim()" @click="acceptDirect" />
          </template>
        </template>

        <!-- Cold path: locked vault or a different account signed in → full sign-in + unlock. -->
        <template v-else>
          <p class="veyq-form-copy">{{ jcopy('signinSub', { email: ownerEmail }) }}</p>
          <form class="veyq-form" @submit.prevent="signInAndAccept">
            <label>{{ t('common.password') }}<Password v-model="password" :feedback="false" toggleMask inputId="signin-password" /></label>
            <label>{{ t('unlock.secretKey') }}
              <SecretKeyField v-model="secretKey" input-id="signin-secret-key" :expected-email="ownerEmail" required />
              <small class="field-hint">{{ t('unlock.secretKeyHint') }}</small>
            </label>
            <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
            <Button type="submit" :label="jcopy('join')" icon="pi pi-check" :loading="busy"
                    :disabled="!username.trim() || password.length === 0 || secretKey.trim().length === 0" />
          </form>
        </template>
      </template>

      <template v-else-if="phase === 'done'">
        <Message severity="success" :closable="false">{{ jcopy('done', { tenant: tenantName }) }}</Message>
        <p class="veyq-form-copy">{{ jcopy('doneNote') }}</p>
        <Button :label="t('login.title')" icon="pi pi-sign-in" @click="router.push({ name: 'login' })" />
      </template>
    </template>
  </Card>
</template>

<style scoped>
.muted { opacity: 0.7; }
</style>
