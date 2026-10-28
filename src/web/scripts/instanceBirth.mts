// One cold birth of a dev/test instance, shared by the seeders that need one (ADR 0065: one cold birth): the local dev
// principal (`bootstrap-user.mts`) and the API fuzzing's seed (`fuzz-seed.mts`). Each step is the real client crypto
// (`@veyq/crypto`) over the generated clients (`apiTransport.mts`), so it is operator-blind — only ciphertext and public
// keys leave this process — and a contract change turns every seeder red at `npm run typecheck`.
//
// Every step THROWS with what the backend answered; the caller decides whether that ends its run (`bootstrap-user`'s
// steps before its owner is persisted) or is downgraded to a warning.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std, b64std } from '@veyq/crypto/base64'
import { buildAccount, type BuiltAccount } from '@veyq/crypto/accountBirth'
import { moderationEpochOne } from '../src/test/moderationEpochOne'
import { sealName } from '../src/features/account/profile/memberName'
import { recipientIdentityDigest, sealGrant } from '../src/features/spaces/grant'
import { Configuration as SetupConfiguration, SetupApi, type SetupStatus } from '../src/api/generated'
import { Configuration as AuthConfiguration, LoginApi } from '../src/api/generated/auth'
import { AccountApi, Configuration as AccountConfiguration } from '../src/api/generated/account'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from '../src/api/generated/scopes'
import { BASE, adminCall, adminChannel, describeFailure, publicChannel, tenantChannel } from './apiTransport.mts'

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Run one generated call, or throw with what the backend answered. */
export async function orThrow<T>(what: string, call: Promise<T>): Promise<T> {
  try { return await call } catch (e) { throw new Error(`${what} failed: ${await describeFailure(e)}`) }
}

/** The setup status, once the backend answers it — the readiness every seeder waits on. */
export async function awaitBackend(timeoutMs = 90_000): Promise<SetupStatus> {
  const setupApi = new SetupApi(new SetupConfiguration(publicChannel()))
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try { return await setupApi.getSetupStatus() } catch (e) { last = await describeFailure(e) }
    await sleep(750)
  }
  throw new Error(`backend did not answer ${BASE}/api/setup within ${timeoutMs}ms (${last})`)
}

/** Cold-birth the Server Owner on the admin listener. `secretKey` keeps an Emergency Kit the human already holds. */
export async function birthServerOwner(email: string, password: string, setup: SetupStatus, secretKey?: Uint8Array): Promise<BuiltAccount> {
  const built = await buildAccount(email, password, fromB64std(setup.instanceSalt), { secretKey })
  const m = built.material
  await orThrow('setup/owner', new SetupApi(new SetupConfiguration(adminChannel())).createServerOwner({
    createServerOwnerRequest: {
      email,
      suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
      authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, secretKeyProofPublicKey: m.secretKeyProofPublicKey,
      moderationEpoch: moderationEpochOne(m.identityPublicKey),
    },
  }))
  return built
}

/** Sign in the way the client does — Argon2id over the password, then a signature over the server's nonce — and
 *  return the account-level access token. */
export async function signIn(email: string, password: string): Promise<string> {
  const loginApi = new LoginApi(new AuthConfiguration(publicChannel()))
  const ch = await orThrow('login challenge', loginApi.requestLoginChallenge({ loginChallengeRequest: { email } }))
  const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), email), v1.ARGON)
  const { accessToken } = await orThrow('login', loginApi.verifyLogin({
    loginVerifyRequest: { email, nonce: ch.nonce, signature: b64std(v1.authSign(auth.privateKey, fromB64std(ch.nonce))) },
  }))
  return accessToken
}

/** The object store is a Server-Owner setting with a passed check (ADR 0089): the server refuses a tenant (409
 *  `store_not_ready`) until one has passed. The backend sets the bundled store and checks it itself after its start
 *  (decision 3), so this usually finds it passed; where it has not (yet), it saves the bundled store — re-saving it
 *  while pending is an equal row — and checks it. Returns whether it had to do anything. */
export async function ensureBundledStore(token: string): Promise<boolean> {
  type StoreState = { bundledAvailable?: boolean; setting?: unknown; lastCheck?: { passed?: boolean } | null }
  const storeNow = await adminCall<StoreState>(token, 'GET', '/api/instance/store')
  if (storeNow.status !== 200) throw new Error(`store: GET /api/instance/store answered ${storeNow.status}`)
  if (storeNow.json.lastCheck?.passed) return false
  if (!storeNow.json.setting) {
    if (!storeNow.json.bundledAvailable) throw new Error('store: no store is set and the deployment offers no bundled one — set one in the admin console first')
    const put = await adminCall<unknown>(token, 'PUT', '/api/instance/store', { bundled: true })
    if (put.status !== 200) throw new Error(`store: PUT /api/instance/store answered ${put.status} ${JSON.stringify(put.json)}`)
  }
  const checked = await adminCall<{ passed?: boolean }>(token, 'POST', '/api/instance/store/check')
  if (checked.status !== 200 || !checked.json.passed) throw new Error(`store: the check did not pass — ${checked.status} ${JSON.stringify(checked.json)}`)
  return true
}

export type BirthedTenant = {
  tenantId: string
  ownerId: string
  /** The freshly-minted TENANT scope key (K_tenant) — a seeder that onboards members or seals more names needs it. */
  tenantScopeKey: Uint8Array
  sealedName: boolean
}

/**
 * The configured-domain tenant, accepted as the existing Server Owner: the TENANT grant sealed to its own identity and
 * signed by it (ADR 0028 Decision 6) — the tenant's trust root, which nobody vouches for (ADR 0111 decision 3) — and,
 * when given, its real name sealed under K_tenant (operator-blind). The owner invite's secret comes back to the Server Owner who minted it
 * (`TenantCreated.bootstrapSecret`), so no mailbox is read for it.
 */
export async function birthTenant(token: string, built: BuiltAccount, t: {
  tenantName: string; domain: string; email: string; username: string; firstName?: string; lastName?: string
}): Promise<BirthedTenant> {
  const onTenant = tenantChannel(t.domain, token)
  const { tenantId, bootstrapSecret } = await orThrow('createTenant', new TenantsApi(new TenantsConfiguration(adminChannel(token)))
    .createTenant({ createTenantRequest: { tenantName: t.tenantName, domain: t.domain, ownerEmail: t.email } }))
  const tenantScopeKey = v1.randomBytes(32)
  const { userId: ownerId } = await orThrow('key-material', new AccountApi(new AccountConfiguration(onTenant)).getAccountKeyMaterial())
  const ownerPub = b64std(v1.serializeIdentityPublic(built.identity.pub))
  const birth = sealGrant(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT', epoch: 0 }, { userId: ownerId, identityPublicKeyB64: ownerPub }, built.identity.priv.sign)
  const firstName = t.firstName ?? ''
  const lastName = t.lastName ?? ''
  const sealedName = firstName || lastName ? sealName(tenantScopeKey, { scopeId: tenantId, userId: ownerId }, { firstName, lastName }) : undefined
  await orThrow('accept', new TenantsApi(new TenantsConfiguration(onTenant)).acceptTenantInvite({
    acceptInviteRequest: {
      bootstrapSecret, username: t.username, grant: birth.wrappedGrant, grantSignature: birth.grantSignature,
      recipientIdentityDigest: recipientIdentityDigest(ownerPub),
      ...(sealedName ? { sealedName } : {}),
    },
  }))
  return { tenantId, ownerId, tenantScopeKey, sealedName: sealedName !== undefined }
}

/** The owner's PERSONAL scope — its id client-minted, because the grant is bound to it (ADR 0072). */
export async function birthPersonalScope(token: string, domain: string, ownerId: string, built: BuiltAccount): Promise<string> {
  const scopeId = crypto.randomUUID()
  const ownerPub = b64std(v1.serializeIdentityPublic(built.identity.pub))
  const grant = sealGrant(v1.randomBytes(32), { scopeId, kind: 'PERSONAL', epoch: 0 }, { userId: ownerId, identityPublicKeyB64: ownerPub }, built.identity.priv.sign)
  const scope = await orThrow('createPersonalScope', new ScopesApi(new ScopesConfiguration(tenantChannel(domain, token))).createPersonalScope({
    createPersonalScopeRequest: { scopeId, ...grant, recipientIdentityDigest: recipientIdentityDigest(ownerPub) },
  }))
  return scope.scopeId
}
