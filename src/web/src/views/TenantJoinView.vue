<script setup lang="ts">
// uc.admin.create-tenant Slice B (cr-0010), uc.spaces.add-member — the invitee accepts. The bootstrap secret rides in
// the URL #fragment (never sent until accept). preview tells us whether the invited email already has an account: a
// NEW invitee registers cold (Emergency Kit) here, an EXISTING one signs in + unlocks. An OWNER invite cold-births the
// TENANT scope and seals the first grant to its own identity. A MEMBER invite (ADR 0111 decisions 1 and 3) carries a
// sealed part after the token (`#<token>.<sealed>`): the invitee types the code the owner passed on, the client opens
// the invite and checks it — the owner's chain from the head inside the seal, the owner's anchor grant — before anything
// is sent, grants itself the tenant key the invite holds and writes its join record — it is in the space at once. When the
// owner secured their account between the check and the accept (`409 invite_changed`), the invite is checked again and
// accepted once more without asking for the code (ADR 0111 decision 20). No key leaves the device.
// A LINK invite (ADR 0129) carries the code too (`#<token>.<sealed>.<code>`), so the code step is skipped, and it is bound
// to nobody: the preview names no address, the invitee registers with one they type or signs in with any account, and a
// new account asks for its confirmation mail right after it joined — a redemption proves no address (decision 4). A
// consumed invite answers `410 invite_redeemed`: someone opened the link first, which the page says (decision 6).
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../shared/ui/VeyqButton.vue'
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqInput from '../shared/ui/VeyqInput.vue'
import { NOT_A_LOGIN } from '../shared/ui/notALogin'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import VeyqPassword from '../shared/ui/VeyqPassword.vue'
import { useBreachCheck } from '../features/account/passwordWarning'
import PasswordBreachNotice from '../features/account/PasswordBreachNotice.vue'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import {
  previewInvite, acceptInvite, acceptOwnership, registerAndAccept, bornTenantScope, tenantError, openMemberInvite, forgetMemberInvite,
  memberInviteAnchored, acceptCheckedInvite,
  buildMemberJoin, inviteLinkMode, InviteCodeWrong, InviteCheckFailed,
  type TenantOwner, type OpenedMemberInvite, type MemberJoin,
} from '../features/tenant/tenantInvite'
import type { ServedMemberInvite } from '../api/generated/tenants'
import { parseInviteFragment } from '@veyq/crypto/invite'
import { deriveSecretKeyProofKeypair } from '@veyq/crypto/v1'
import { sealName, sealAvatar, myUserId, type SealedMemberName, type SealedMemberAvatar } from '../features/account/profile/profile'
import type { ScopeKey } from '../features/spaces/epochs'
import JoinPicturePicker from '../features/account/profile/JoinPicturePicker.vue'
import EmergencyKitSave from '../features/account/emergencyKit/EmergencyKitSave.vue'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import { InviteRole } from '../api/generated/tenants'
import { buildAccount, type BuiltAccount } from '@veyq/crypto/accountBirth'
import { login } from '../features/account/login/login'
import { withRefresh } from '../app/sessionRefresh'
import { unlockMasterKey } from '../features/account/unlock/unlock'
import { identityPrivate, vaultState } from '../app/vault'
import { holdEpochKey } from '../features/account/trustMarks'
import { account } from '../app/account'
import { setupApi } from '../api/client'
import { PRIVACY_ANCHOR, privacyLink } from '../core/privacy/privacyNotice'
import { fromB64std } from '@veyq/crypto/base64'
import { secretKeyBytes } from '@veyq/crypto/secretKey'
import { identityPublicFromPrivate } from '@veyq/crypto/v1'
import { HttpStatus, isUnauthorized } from '../shared/httpError'
import { isMailbox } from '../shared/mailbox'
import { requestEmailConfirmation } from '../features/account/email/emailConfirmation'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

type Phase = 'loading' | 'missing' | 'invalid' | 'expired' | 'redeemed' | 'code' | 'register' | 'kit' | 'signin' | 'done'
const phase = ref<Phase>('loading')
const error = ref('')
const busy = ref(false)

const tenantName = ref('')
// The tenant's id — and so its TENANT scope's id — known from the preview before the scope's birth; the grant and
// the optional sealed name are bound to it (ADR 0072).
const tenantId = ref('')
const domain = ref('')
const ownerEmail = ref('')
// A link invite (ADR 0129 decision 2): a MEMBER invite whose preview names no address. Its invitee types one.
const linkInvite = ref(false)
const typedEmail = ref('')
/** The address this invitee registers or signs in with: the invite's, or the one typed for a link invite. */
const inviteeEmail = computed(() => (linkInvite.value ? typedEmail.value.trim() : ownerEmail.value))
const emailReady = computed(() => !linkInvite.value || isMailbox(inviteeEmail.value))
// The address a link registration's confirmation mail went to, once it went (ADR 0129 decision 4) — '' when none did.
const confirmationSentTo = ref('')
// The invite's role (cr-0077): an OWNER cold-births the TENANT scope + seals the first grant; a MEMBER opens its
// invite's grant with the code and grants itself the tenant key (ADR 0111 decision 3).
const role = ref<InviteRole>(InviteRole.Owner)
const isMember = computed(() => role.value === InviteRole.Member)
// An OWNER invite into an ACTIVE tenant whose member the invitee already is (ADR 0101 decision 9): accepting makes them
// an owner — no birth, no username, no name — through `acceptOwnership`. They hold an account by premise, so the
// view goes straight to the sign-in phase.
const handover = ref(false)

// Role-aware copy: a MEMBER joins the shared space, an OWNER takes ownership, a member handed the space becomes its
// owner. Each phase names only the `<base>Member` / `<base>Handover` variants that exist for its bases.
function jcopy(base: string, params?: Record<string, unknown>): string {
  const variant = handover.value ? 'Handover' : isMember.value ? 'Member' : ''
  return t(`tenant.join.${base}${variant}`, params ?? {})
}
/** The register and sign-in subline: a link invite names no address, so it has its own. */
function subline(base: 'registerSub' | 'signinSub'): string {
  return linkInvite.value ? t(`tenant.join.${base}Link`) : jcopy(base, { email: ownerEmail.value })
}

// register + signin form: the per-tenant handle chosen on join (ADR 0023), plus an OPTIONAL real name and picture,
// sealed under K_tenant at join — born here for an OWNER, opened from the invite's grant for a MEMBER (cr-0106).
const username = ref('')
const firstName = ref('')
const lastName = ref('')
// The optional picture's encoded bytes (ADR 0084): sealed with the tenant key at accept, never held anywhere else.
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
// A sign-in accept needs a username — except a handover, which keeps the member's own.
const usernameReady = computed(() => handover.value || username.value.trim().length > 0)

// This browser already holds the INVITED owner's unlocked vault (same-origin setup → create-tenant flow):
// the identity key is in memory, so we can seal the grant and accept in one click — no password, no Secret
// Key. Gated on the email so we never seal to a different signed-in identity (the server also enforces this).
// A MEMBER's join record names its Secret-Key proof key (ADR 0111 decision 9), which only the Secret Key derives — so a
// MEMBER invite always takes the sign-in form, which asks for it.
const alreadyUnlocked = computed(
  () => !isMember.value && vaultState.unlocked && account.email.trim().toLowerCase() === ownerEmail.value.trim().toLowerCase(),
)

// The fragment: the token, and — for a MEMBER invite — the sealed part the code opens (ADR 0111 decision 1), and — for a
// link invite — the code itself (ADR 0129 decision 1).
const fragment = parseInviteFragment(String(route.hash || ''))
const secret = fragment.token
// Non-reactive: keep the freshly born identity's Uint8Arrays out of Vue's deep proxy.
let built: BuiltAccount | null = null
// A MEMBER invite: what the preview served, and — once the code opened and checked it — the invite and its tenant key.
let served: ServedMemberInvite | null = null
let openedInvite: OpenedMemberInvite | null = null
// The code (or password) the owner passed on outside veyq; which of the two the link asks for is in its clear header.
const code = ref('')
const codeMode = ref<'code' | 'password'>('code')

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
    ownerEmail.value = p.ownerEmail ?? ''
    linkInvite.value = p.role === InviteRole.Member && p.ownerEmail === undefined
    role.value = p.role
    handover.value = p.handover
    if (p.role === InviteRole.Member) {
      // A member invite opens only with its sealed part and the code: without them, or with one this client cannot
      // read, the link cannot be checked — refused before anything else.
      const mode = fragment.sealed ? inviteLinkMode(fragment.sealed) : null
      if (!p.invite || !mode) {
        phase.value = 'invalid'
        return
      }
      served = p.invite
      codeMode.value = mode
      hasAccount = p.hasAccount ?? false
      // A link that carries its code opens without asking for it (ADR 0129 decision 1); one whose code does not open
      // the invite falls back to the code step, which says so.
      if (fragment.code && mode === 'code') {
        await openInvite(fragment.code)
        if (openedInvite) return
      }
      phase.value = 'code'
      return
    }
    phase.value = p.hasAccount || p.handover ? 'signin' : 'register'
  } catch (e) {
    const { status, code } = await tenantError(e)
    phase.value = code === 'invite_redeemed' ? 'redeemed' : status === HttpStatus.gone ? 'expired' : 'invalid'
  }
})

let hasAccount = false

/**
 * A MEMBER invite's first step: open the link's sealed part with the code and check the invite before anything is
 * sent (ADR 0111 decision 1) — a wrong code is the invitee's to retype; a check that fails is "ask for a new one".
 */
async function openWithCode() {
  if (code.value.trim().length === 0) return
  await openInvite(code.value)
  if (openedInvite) code.value = ''
}

/** Open the link's sealed part with [typed] — the code typed, or the one the link carries — and check the invite; then
 *  the account step: sign-in for an invitee the preview knows to have an account, else register (a link invite, bound to
 *  nobody, starts at register and offers the sign-in beside it). */
async function openInvite(typed: string) {
  if (!served || !fragment.sealed) return
  error.value = ''
  busy.value = true
  try {
    openedInvite = await openMemberInvite(tenantId.value, served, fragment.sealed, typed)
    phase.value = hasAccount ? 'signin' : 'register'
  } catch (e) {
    if (e instanceof InviteCodeWrong) error.value = t(codeMode.value === 'code' ? 'tenant.join.codeWrong' : 'tenant.join.passwordWrong')
    else if (e instanceof InviteCheckFailed) error.value = t('tenant.join.inviteUncheckable')
    else error.value = t('tenant.join.failed')
  } finally {
    busy.value = false
  }
}

/** A link invite's invitee switches between a new account and one they have (ADR 0129 decision 2). */
function chooseAccount(next: 'register' | 'signin') {
  error.value = ''
  password.value = ''
  phase.value = next
}

/** The MEMBER join for [userId] under [identity] through [opened] (ADR 0111 decision 3), with the Secret-Key proof key
 *  it names. */
function memberJoinFor(
  opened: OpenedMemberInvite, userId: string, identity: TenantOwner['identity'], secretKeyProofPublicKey: Uint8Array,
): MemberJoin {
  return buildMemberJoin(opened, { userId, identity, secretKeyProofPublicKey })
}

/** The invite as the preview serves it now — what a `409 invite_changed` re-checks ({@link acceptCheckedInvite}). */
async function rereadInvite(): Promise<ServedMemberInvite | undefined> {
  return (await previewInvite(secret)).invite ?? undefined
}

/** Register step 1: build the account on-device and show its Emergency Kit before activating. */
async function prepareRegistration() {
  // The length floor belongs to THIS branch only. `password` is one ref serving three different acts in this
  // view — register (here), sign in as an existing user, and re-authenticate after a lapsed session — and
  // only the first one SETS a password. Gating the other two would lock out an account whose password
  // predates the policy, on a screen whose whole job is letting them in. Client-side by construction, too:
  // under Model B (ADR 0003) the password never leaves the device, so there is no server rule to mirror.
  if (!username.value.trim() || password.value.length < MIN_PASSWORD_LENGTH) return
  if (!emailReady.value) {
    error.value = t('tenant.join.emailInvalid')
    return
  }
  error.value = ''
  busy.value = true
  try {
    if (!(await breachCheckPasses())) return
    const instanceSalt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
    built = await buildAccount(inviteeEmail.value, password.value, instanceSalt)
    secretKeyDisplay.value = built.emergencyKit.secretKey
    phase.value = 'kit'
  } catch {
    error.value = t('tenant.join.failed')
  } finally {
    busy.value = false
  }
}

/** Seal the OPTIONAL real name under a fresh value key K_tenant wraps (born for an OWNER, opened from the invite for a MEMBER, ADR 0115), or undefined
 *  when both fields are empty. */
function sealedNameFor(tenant: ScopeKey, userId: string): SealedMemberName | undefined {
  if (!firstName.value.trim() && !lastName.value.trim()) return undefined
  // Bound to (this tenant scope, the member it names) — ADR 0072.
  return sealName(tenant, { scopeId: tenantId.value, userId }, { firstName: firstName.value, lastName: lastName.value })
}

/** Seal the OPTIONAL picture under a fresh value key the same K_tenant wraps (`member-avatar`, bound to the tenant scope and the member), or
 *  undefined when none was chosen. */
function sealedAvatarFor(tenant: ScopeKey, userId: string): SealedMemberAvatar | undefined {
  return avatarBytes.value ? sealAvatar(tenant, { scopeId: tenantId.value, userId }, avatarBytes.value) : undefined
}

/** The tenant key an OWNER's birth minted, at the birth epoch 0 — what its name's and picture's value keys are wrapped under. */
function bornKey(born: { scopeRootKey: Uint8Array }): ScopeKey {
  return { epoch: 0, key: born.scopeRootKey }
}

/** An OWNER's accept went through: this device holds the tenant key its birth minted, at the birth epoch (`bornTenantScope`), so
 *  the owner's first open takes no later epoch that does not link down to it (ADR 0111 decision 23, inbox #740) - under
 *  the vault's Master Key, or a new account's [accountKey] (no vault holds it before the first sign-in). A MEMBER's
 *  checked key is held by {@link acceptCheckedInvite}. */
function holdBorn(scopeRootKey: Uint8Array, accountKey?: Uint8Array): Promise<void> {
  return holdEpochKey(tenantId.value, 0, scopeRootKey, accountKey)
}

/** The unlocked vault's identity as the tenant's birth needs it: the grant is sealed to its public bundle and signed
 *  by its signing half (ADR 0028 Decision 6), under the owner's own account id. */
function ownerOf(userId: string, priv: { kemSeed: Uint8Array; sign: Uint8Array }): TenantOwner {
  return { userId, identity: { pub: identityPublicFromPrivate(priv), priv } }
}

/** Register step 2: cold-birth the scope grant to the new identity and register-and-accept. */
async function finishRegistration() {
  if (!built || !kitReady.value) return
  error.value = ''
  busy.value = true
  try {
    // An OWNER cold-births the TENANT scope + seals a grant; a MEMBER grants itself the key its invite holds and writes
    // its join record (ADR 0111 decision 3). Either holds K_tenant here, so the optional name and picture are sealed at
    // join (cr-0106). The new account's id is minted here, before the account exists — everything is bound to it.
    const userId = crypto.randomUUID()
    const acct = built
    const handle = username.value.trim().toLowerCase()
    if (isMember.value) {
      // Everything sealed under the tenant key is built from the invite it is handed: a re-check after `409
      // invite_changed` may carry the tenant key at another epoch.
      // The new account's Master Key keys the held tenant key: no vault holds it before the first sign-in.
      await acceptCheckedInvite(openedInvite!, (opened) => {
        const join = memberJoinFor(opened, userId, acct.identity, fromB64std(acct.material.secretKeyProofPublicKey))
        const key = { epoch: opened.grantedEpoch, key: opened.epochKey }
        return registerAndAccept(
          secret, userId, handle, acct.material, undefined, sealedNameFor(key, userId), sealedAvatarFor(key, userId), join,
          linkInvite.value ? inviteeEmail.value : undefined,
        )
      }, rereadInvite, undefined, acct.masterKey)
    } else {
      const born = bornTenantScope({ userId, identity: acct.identity }, tenantId.value)
      const key = { epoch: 0, key: born.scopeRootKey }
      await registerAndAccept(secret, userId, handle, acct.material, born.birth, sealedNameFor(key, userId), sealedAvatarFor(key, userId))
      await holdBorn(born.scopeRootKey, acct.masterKey)
    }
    acct.masterKey.fill(0)
    if (linkInvite.value) await askForConfirmation()
    finish()
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/**
 * A link registration proves no address (ADR 0129 decision 4), so the new account asks for its confirmation mail once,
 * at once — in its own session (ADR 0074 decision 4), which the register does not issue: it signs in first. Neither step
 * is the join's: a failure leaves the join done, and the banner of the signed-in app offers the mail again (decision 5).
 */
async function askForConfirmation() {
  try {
    await login(inviteeEmail.value, password.value)
    await requestEmailConfirmation()
    confirmationSentTo.value = inviteeEmail.value
  } catch {
    // not an error of the join
  }
}

/** Existing owner: sign in, unlock (identity key), then seal the grant to it and accept. */
async function signInAndAccept() {
  if (password.value.length === 0 || secretKey.value.trim().length === 0) return
  if (!emailReady.value) {
    error.value = t('tenant.join.emailInvalid')
    return
  }
  error.value = ''
  busy.value = true
  try {
    await login(inviteeEmail.value, password.value)
    await unlockMasterKey(password.value, secretKey.value.trim())
    if (handover.value) {
      await acceptOwnership(secret)
      phase.value = 'done'
      return
    }
    const priv = identityPrivate()
    if (!priv) throw new Error('locked')
    if (isMember.value) {
      // The checks walked the inviting owner's chain from the link's head; this account's pin store must not know them
      // at a head that chain does not pass. A pinned foreign granter is judged by its own chain.
      if (!(await memberInviteAnchored(openedInvite!))) {
        error.value = t('tenant.join.inviteUncheckable')
        return
      }
      const userId = await myUserId()
      const proofKey = deriveSecretKeyProofKeypair(secretKeyBytes(secretKey.value)).publicKey
      const identity = ownerOf(userId, priv).identity
      const handle = username.value.trim().toLowerCase()
      await acceptCheckedInvite(openedInvite!, (opened) => {
        const join = memberJoinFor(opened, userId, identity, proofKey)
        const key = { epoch: opened.grantedEpoch, key: opened.epochKey }
        return acceptInvite(secret, handle, undefined, sealedNameFor(key, userId), sealedAvatarFor(key, userId), join)
      }, rereadInvite, memberInviteAnchored)
      finish()
      return
    }
    const userId = await myUserId()
    const born = bornTenantScope(ownerOf(userId, priv), tenantId.value)
    await acceptInvite(
      secret, username.value.trim().toLowerCase(), born.birth,
      sealedNameFor(bornKey(born), userId), sealedAvatarFor(bornKey(born), userId),
    )
    await holdBorn(born.scopeRootKey)
    finish()
  } catch (e) {
    error.value = await mapError(e)
  } finally {
    busy.value = false
  }
}

/** The accept went through: the invite's keys are wiped, and the done step says what the invitee holds now. */
function finish() {
  if (openedInvite) forgetMemberInvite(openedInvite)
  openedInvite = null
  phase.value = 'done'
}

/** With an already-unlocked identity in memory, cold-birth the scope grant and accept. acceptInvite isn't
 *  withRefresh-wrapped itself, so wrap it here: an expired access token rotates silently from the cookie. */
async function sealAndAccept() {
  if (handover.value) {
    await withRefresh(() => acceptOwnership(secret))
    password.value = ''
    phase.value = 'done'
    return
  }
  // An OWNER invite only: a MEMBER takes the sign-in form (its join record needs the Secret Key, see alreadyUnlocked).
  const priv = identityPrivate()
  if (!priv) throw new Error('locked')
  const userId = await myUserId()
  const born = bornTenantScope(ownerOf(userId, priv), tenantId.value)
  const birth = born.birth
  const sealedName = sealedNameFor(bornKey(born), userId)
  const sealedAvatar = sealedAvatarFor(bornKey(born), userId)
  await withRefresh(() => acceptInvite(secret, username.value.trim().toLowerCase(), birth, sealedName, sealedAvatar))
  await holdBorn(born.scopeRootKey)
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
  // A re-check after `409 invite_changed` that refused the invite: "ask for a new one", as at the code step.
  if (e instanceof InviteCheckFailed) return t('tenant.join.inviteUncheckable')
  const { status, code } = await tenantError(e)
  if (code === 'invite_redeemed') return t('tenant.join.redeemed')
  if (status === HttpStatus.gone) return t('tenant.join.expired')
  if (code === 'already_member') return t('tenant.join.alreadyMember')
  if (status === HttpStatus.notFound) return t('tenant.join.invalid')
  if (code === 'email_taken') return t('tenant.join.emailTaken')
  if (code === 'username_taken') return t('tenant.join.usernameTaken')
  if (code === 'not_invited_owner') return t('tenant.join.wrongOwner')
  if (code === 'not_tenant_member') return t('tenant.join.notMemberHandover')
  if (code === 'invite_proof_invalid') return t('tenant.join.inviteUncheckable')
  return t('tenant.join.failed')
}
</script>

<template>
  <VeyqCard class="join-card">
    <template #title>{{ t('tenant.join.title') }}</template>
    <template #default>
      <p v-if="phase === 'loading'" class="muted">{{ t('tenant.join.loading') }}</p>

      <VeyqMessage v-else-if="phase === 'missing'" tone="warn">{{ t('tenant.join.missing') }}</VeyqMessage>
      <VeyqMessage v-else-if="phase === 'invalid'" tone="error">{{ t('tenant.join.invalid') }}</VeyqMessage>
      <VeyqMessage v-else-if="phase === 'expired'" tone="warn">{{ t('tenant.join.expired') }}</VeyqMessage>
      <VeyqMessage v-else-if="phase === 'redeemed'" tone="warn">{{ t('tenant.join.redeemed') }}</VeyqMessage>

      <template v-else-if="phase === 'code'">
        <p class="veyq-form-copy">{{ jcopy('invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="veyq-form-copy">{{ t(codeMode === 'code' ? 'tenant.join.codeSub' : 'tenant.join.passwordSub') }}</p>
        <form class="veyq-form" @submit.prevent="openWithCode">
          <label v-if="codeMode === 'code'">{{ t('tenant.join.code') }}
            <VeyqInput v-bind="NOT_A_LOGIN" v-model="code" name="invite-code" autocomplete="off" required />
          </label>
          <label v-else>{{ t('tenant.join.invitePassword') }}<VeyqPassword not-a-login v-model="code" input-id="invite-password" /></label>
          <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
          <VeyqButton type="submit" :label="t('tenant.join.continue')" icon="arrow-right" :loading="busy" :disabled="code.trim().length === 0" />
        </form>
      </template>

      <template v-else-if="phase === 'register'">
        <p class="veyq-form-copy">{{ jcopy('invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="veyq-form-copy">{{ subline('registerSub') }}</p>
        <p class="join-privacy"><a :href="privacyLink(PRIVACY_ANCHOR.account)" target="_blank" rel="noopener">{{ t('privacyNotice.atInvite') }}</a></p>
        <form class="veyq-form" @submit.prevent="prepareRegistration">
          <label>{{ t('tenant.join.username') }}
            <VeyqInput v-model="username" name="username" autocomplete="nickname" required />
            <small class="field-hint">{{ t('tenant.join.usernameHint') }}</small>
          </label>
          <template v-if="!handover">
            <div class="name-row">
              <label>{{ t('profile.firstName') }}
                <VeyqInput v-model="firstName" name="given-name" autocomplete="given-name" />
              </label>
              <label>{{ t('profile.lastName') }}
                <VeyqInput v-model="lastName" name="family-name" autocomplete="family-name" />
              </label>
            </div>
            <small class="field-hint">{{ t('tenant.join.nameHint') }}</small>
            <JoinPicturePicker v-model="avatarBytes" :name="`${firstName} ${lastName}`.trim() || username" />
          </template>
          <!-- A link invite names no address: the new account's is typed here, before the password (ADR 0129 decision 2). -->
          <label v-if="linkInvite">{{ t('common.email') }}
            <VeyqInput v-model="typedEmail" name="email" type="email" autocomplete="email" maxlength="254" required />
          </label>
          <label>{{ t('common.password') }}<VeyqPassword v-model="password" input-id="join-password" autocomplete="new-password" @blur="checkBreaches" /></label>
          <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
          <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="join" :disabled="busy" />
          <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
          <VeyqButton type="submit" :label="t('tenant.join.continue')" icon="arrow-right" :loading="busy"
                  :disabled="!username.trim() || password.length < MIN_PASSWORD_LENGTH || !inviteeEmail" />
        </form>
        <VeyqButton v-if="linkInvite" :label="t('tenant.join.haveAccount')" variant="secondary" appearance="text" size="small"
                    data-testid="join-have-account" :disabled="busy" @click="chooseAccount('signin')" />
      </template>

      <template v-else-if="phase === 'kit'">
        <VeyqMessage tone="info">{{ t('tenant.join.saveKitTitle') }}</VeyqMessage>
        <EmergencyKitSave :email="inviteeEmail" :secret-key="secretKeyDisplay" v-model="kitReady">
          <p class="veyq-form-copy">{{ t('tenant.join.saveKitSub') }}</p>
        </EmergencyKitSave>
        <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
        <VeyqButton :label="jcopy('join')" icon="check" :loading="busy" :disabled="!kitReady" @click="finishRegistration" />
      </template>

      <template v-else-if="phase === 'signin'">
        <p class="veyq-form-copy">{{ jcopy('invitedTo', { tenant: tenantName, domain }) }}</p>
        <p class="join-privacy"><a :href="privacyLink(PRIVACY_ANCHOR.account)" target="_blank" rel="noopener">{{ t('privacyNotice.atInvite') }}</a></p>

        <!-- The per-tenant handle (ADR 0023): an existing account still picks a username for THIS tenant — unless it is
             handed the space it is a member of already, which keeps its handle. -->
        <label v-if="!handover" class="veyq-form-field">{{ t('tenant.join.username') }}
          <VeyqInput v-model="username" name="username" autocomplete="nickname" required />
          <small class="field-hint">{{ t('tenant.join.usernameHint') }}</small>
        </label>
        <template v-if="!handover">
          <div class="name-row">
            <label class="veyq-form-field">{{ t('profile.firstName') }}
              <VeyqInput v-model="firstName" name="given-name" autocomplete="given-name" />
            </label>
            <label class="veyq-form-field">{{ t('profile.lastName') }}
              <VeyqInput v-model="lastName" name="family-name" autocomplete="family-name" />
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
              <label>{{ t('common.password') }}<VeyqPassword v-model="password" input-id="reauth-password" autocomplete="current-password" /></label>
              <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
              <VeyqButton type="submit" :label="jcopy('join')" icon="check" :loading="busy"
                      :disabled="!usernameReady || password.length === 0" />
            </form>
          </template>
          <div v-else class="veyq-stack">
            <p class="veyq-form-copy">{{ jcopy('signinReadySub', { email: ownerEmail }) }}</p>
            <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
            <VeyqButton :label="jcopy('join')" icon="check" :loading="busy" :disabled="!usernameReady" @click="acceptDirect" />
          </div>
        </template>

        <!-- Cold path: locked vault or a different account signed in → full sign-in + unlock. -->
        <template v-else>
          <p class="veyq-form-copy">{{ subline('signinSub') }}</p>
          <form class="veyq-form" @submit.prevent="signInAndAccept">
            <!-- A link invite admits any account of this instance: its address is typed (ADR 0129 decision 2). -->
            <label v-if="linkInvite">{{ t('common.email') }}
              <VeyqInput v-model="typedEmail" name="email" type="email" autocomplete="username" maxlength="254" required />
            </label>
            <label>{{ t('common.password') }}<VeyqPassword v-model="password" input-id="signin-password" autocomplete="current-password" /></label>
            <label>{{ t('unlock.secretKey') }}
              <SecretKeyField v-model="secretKey" input-id="signin-secret-key" :expected-email="inviteeEmail" required />
              <small class="field-hint">{{ t('unlock.secretKeyHint') }}</small>
            </label>
            <VeyqMessage v-if="error" tone="error">{{ error }}</VeyqMessage>
            <VeyqButton type="submit" :label="jcopy('join')" icon="check" :loading="busy"
                    :disabled="!usernameReady || password.length === 0 || secretKey.trim().length === 0 || !inviteeEmail" />
          </form>
          <VeyqButton v-if="linkInvite" :label="t('tenant.join.newAccount')" variant="secondary" appearance="text" size="small"
                      data-testid="join-new-account" :disabled="busy" @click="chooseAccount('register')" />
        </template>
      </template>

      <div v-else-if="phase === 'done'" class="veyq-stack">
        <VeyqMessage tone="success">{{ jcopy('done', { tenant: tenantName }) }}</VeyqMessage>
        <VeyqMessage v-if="confirmationSentTo" tone="info">{{ t('tenant.join.confirmationSent', { email: confirmationSentTo }) }}</VeyqMessage>
        <p class="veyq-form-copy">{{ jcopy('doneNote') }}</p>
        <!-- A handover leaves this browser signed in and unlocked on the space it now owns: open it. -->
        <VeyqButton v-if="handover" :label="t('tenant.join.openSpace')" icon="arrow-right" @click="router.push('/')" />
        <VeyqButton v-else :label="t('login.title')" icon="sign-in" @click="router.push({ name: 'login' })" />
      </div>
    </template>
  </VeyqCard>
</template>

<style scoped>
.muted { opacity: 0.7; }
/* GDPR Art. 13 applies here, where an account is born: the notice's account passage, in a tab of its own (the invite's
   secret rides this page's fragment). */
.join-privacy { margin: 0 0 0.75rem; font-size: 0.85rem; }
.join-privacy a { color: var(--app-accent); }
</style>
