// Throwaway account — a fresh tenant with its own owner (and, asked, a member) on the dev instance, for a pass or a live
// test that changes, rotates or erases an account (ADR 0148 decision 12). The seeded principal and member are the shared
// test accounts; no such pass signs in as either.
//
//   pnpm run throwaway:account -- --out <file> [--with-member]   create; writes the throwaway's credentials to <file>
//   pnpm run throwaway:account -- --erase <file>                 erase the throwaway (its sole-member tenant along)
//
// The principal's credentials (`VEYQ_ENV_FILE`, else the repository's `.run/.env`; never printed) are used for one thing:
// `createTenant` on the admin listener for `tw-<stamp>.localhost` with owner `tw-<stamp>@veyq.localhost`, and — at the
// erase — to list the pending erasures. The owner then registers from the returned owner invite with its own cold birth
// (`registerAndAcceptTenantInvite` with the TENANT birth grant, the same real client crypto the seeder runs), creates its
// personal scope and points its tenant's mail at Mailpit; `--with-member` invites a second throwaway by a link invite
// (its token answered to the owner, so no mailbox is read). <file> gets `VEYQ_EMAIL`, `VEYQ_PASSWORD`, `VEYQ_SECRET_KEY`,
// `VEYQ_TENANT_DOMAIN` (and the member's as `VEYQ_USER2_*`), mode 0600, and must lie outside the repository.
//
// Refused, exit 1, before anything is sent: an <file> that is the principal's env file or names the principal's or the
// seeded member's address (`VEYQ_EMAIL`, `VEYQ_USER2_EMAIL`) — so neither `--out` overwrites them nor `--erase` erases them.
// FIRST: the Vite define the client's modules read at load (`clientPrelude.mts`) - `memberInvite` reaches them.
import './clientPrelude.mts'
import { chmodSync, existsSync, readFileSync, realpathSync, writeFileSync } from 'node:fs'
import { dirname, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url, fromB64std } from '@veyq/crypto/base64'
import { buildAccount, type BuiltAccount } from '@veyq/crypto/accountBirth'
import { generateInviteCode } from '@veyq/crypto/invite'
import { sealName } from '../src/features/account/profile/memberName'
import { recipientIdentityDigest, sealGrant } from '../src/features/spaces/grant'
import { DEFAULT_INVITE_TTL_SECONDS, sealMemberInvite } from '../src/features/spaces/memberInvite'
import { memberJoinFrom } from '../src/test/memberJoin'
import { MAILPIT_SMTP_PORT, mailpitReachable } from '../src/test/mailpit'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from '../src/api/generated/scopes'
import { AccountApi, AccountErasureApi, Configuration as AccountConfiguration } from '../src/api/generated/account'
import { Configuration as AuthConfiguration, LoginApi } from '../src/api/generated/auth'
import { adminCall, adminChannel, publicChannel, tenantChannel } from './apiTransport.mts'
import { awaitBackend, birthPersonalScope, orThrow, signIn } from './instanceBirth.mts'

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const PRINCIPAL_ENV = process.env.VEYQ_ENV_FILE ?? resolve(REPO, '.run/.env')
const SMTP_HOST = process.env.VEYQ_SMTP_HOST ?? 'mailpit.localhost'

function fail(msg: string): never {
  console.error(`throwaway-account: ${msg}`)
  process.exit(1)
}

function readEnv(file: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (m && !line.trimStart().startsWith('#')) out[m[1]] = m[2].trim()
  }
  return out
}

/** The path as the file system names it (a missing file by its directory's real path), so a link is no way around. */
function real(file: string): string {
  const abs = resolve(file)
  return existsSync(abs) ? realpathSync(abs) : resolve(realpathSync(dirname(abs)), abs.slice(dirname(abs).length + 1))
}

/** Every refusal of <file>: the principal's env file itself, a file naming a seeded address, a file inside the repo. */
function refuseSeeded(file: string, principal: Record<string, string>, mustBeOutsideRepo: boolean): void {
  const seeded = [principal.VEYQ_EMAIL, principal.VEYQ_USER2_EMAIL].filter(Boolean).map((e) => e.trim().toLowerCase())
  if (existsSync(PRINCIPAL_ENV) && real(file) === real(PRINCIPAL_ENV)) fail(`${file} is the principal's env file — refused`)
  if (mustBeOutsideRepo && (real(file) + sep).startsWith(real(REPO) + sep)) fail(`${file} lies inside the repository — write it outside (a scratch directory)`)
  if (existsSync(file)) {
    const named = Object.entries(readEnv(file)).filter(([k]) => k === 'VEYQ_EMAIL' || k === 'VEYQ_USER2_EMAIL').map(([, v]) => v.trim().toLowerCase())
    if (named.some((e) => seeded.includes(e))) fail(`${file} names a seeded account (the principal or its member) — refused`)
  }
}

const args = process.argv.slice(2).filter((a) => a !== '--')
const outAt = args.indexOf('--out')
const eraseAt = args.indexOf('--erase')
const withMember = args.includes('--with-member')
if ((outAt < 0) === (eraseAt < 0)) fail('usage: --out <file> [--with-member] | --erase <file>')
const file = args[(outAt >= 0 ? outAt : eraseAt) + 1] ?? fail('no <file> given')
if (!existsSync(PRINCIPAL_ENV)) fail(`no principal env file at ${PRINCIPAL_ENV} — set VEYQ_ENV_FILE`)
const principal = readEnv(PRINCIPAL_ENV)
refuseSeeded(file, principal, outAt >= 0)

const randomSecret = () => b64std(v1.randomBytes(18)).replace(/[+/=]/g, 'x')

type Born = { email: string; password: string; userId: string; username: string; built: BuiltAccount }

/** One cold-born owner of a fresh tenant, registered from the owner invite `createTenant` handed back. */
async function createOwner(principalToken: string, stamp: string, salt: Uint8Array) {
  const domain = `tw-${stamp}.localhost`
  const email = `tw-${stamp}@veyq.localhost`
  const tenantName = `Throwaway ${stamp}`
  const { tenantId, bootstrapSecret } = await orThrow('createTenant', new TenantsApi(new TenantsConfiguration(adminChannel(principalToken)))
    .createTenant({ createTenantRequest: { tenantName, domain, ownerEmail: email } }))
  const password = randomSecret()
  const built = await buildAccount(email, password, salt)
  const userId = crypto.randomUUID()
  const username = `tw${stamp}`.slice(0, 32)
  const tenantScopeKey = v1.randomBytes(32)
  const pub = b64std(v1.serializeIdentityPublic(built.identity.pub))
  const birth = sealGrant(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT', epoch: 0 }, { userId, identityPublicKeyB64: pub }, built.identity.priv.sign)
  const name = sealName({ epoch: 0, key: tenantScopeKey }, { scopeId: tenantId, userId }, { firstName: 'Throwaway', lastName: stamp })
  const m = built.material
  await orThrow('registerAndAcceptTenantInvite (owner)', new TenantsApi(new TenantsConfiguration(tenantChannel(domain, ''))).registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret, userId, username,
      account: {
        suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
        authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, secretKeyProofPublicKey: m.secretKeyProofPublicKey,
      },
      grant: birth.wrappedGrant, grantSignature: birth.grantSignature, recipientIdentityDigest: recipientIdentityDigest(pub),
      ...name, epoch: 0,
    },
  }))
  const owner: Born = { email, password, userId, username, built }
  return { domain, tenantId, tenantName, tenantScopeKey, owner }
}

/** A member of the throwaway's tenant, through a LINK invite (ADR 0129 decision 1): the owner mints it and the link's
 *  token comes back in the answer, so nothing is read from a mailbox — the dev backend on a machine whose own hostname
 *  does not resolve sends no mail (JavaMail's EHLO stalls), and a link invite still works there. The invitee's half is the
 *  client's own (`memberJoinFrom`: the code opens the sealed part, the invite is checked against the owner's chain). */
async function createMember(o: Awaited<ReturnType<typeof createOwner>>, ownerToken: string, stamp: string, salt: Uint8Array): Promise<Born> {
  const email = `tw-${stamp}-m@veyq.localhost`
  const code = generateInviteCode()
  const onTenant = tenantChannel(o.domain, ownerToken)
  const mine = (await orThrow('listMyTenants', new ScopesApi(new ScopesConfiguration(onTenant)).listMyTenants())).find((t) => t.tenantId === o.tenantId)
  const ob = o.owner.built
  // The owner was born above and never secured the account: its chain is the genesis step, and the link carries its head
  // (ADR 0111 decision 19) as the web client's `myChainHead` would serve it.
  const ownerHead = v1.keyChainHead(v1.keyChainGenesisStatement(o.owner.userId, {
    identityPublicKey: v1.serializeIdentityPublic(ob.identity.pub),
    secretKeyProofPublicKey: fromB64std(ob.material.secretKeyProofPublicKey),
  }))
  // The mailed form's builder, which the link form shares; the address and the sealed part stay here (a link request
  // carries neither).
  const { email: _bound, sealedLink, ...memberInviteLinkRequest } = await sealMemberInvite(
    o.tenantId, email, { mode: 'code', code },
    { userId: o.owner.userId, pub: ob.identity.pub, head: ownerHead, signPrivateKey: ob.identity.priv.sign },
    { epoch: 0, key: o.tenantScopeKey }, mine?.memberInviteTtlSeconds ?? DEFAULT_INVITE_TTL_SECONDS,
  )
  void _bound
  const minted = await orThrow('createMemberInviteLink', new TenantsApi(new TenantsConfiguration(onTenant))
    .createMemberInviteLink({ tenantId: o.tenantId, memberInviteLinkRequest }))
  const fragment = `${minted.bootstrapSecret}.${b64url(fromB64std(sealedLink))}`
  const password = randomSecret()
  const built = await buildAccount(email, password, salt)
  const publicTenants = new TenantsApi(new TenantsConfiguration(tenantChannel(o.domain, '')))
  const preview = await orThrow('member preview', publicTenants.previewTenantInvite({ acceptPreviewRequest: { bootstrapSecret: minted.bootstrapSecret } }))
  const userId = crypto.randomUUID()
  const username = `tw${stamp}m`.slice(0, 32)
  const join = await memberJoinFrom(preview, fragment, code, userId, built)
  const m = built.material
  await orThrow('registerAndAcceptTenantInvite (member)', publicTenants.registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret: minted.bootstrapSecret, userId, username, email,
      account: {
        suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
        authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, secretKeyProofPublicKey: m.secretKeyProofPublicKey,
      },
      ...join,
    },
  }))
  return { email, password, userId, username, built }
}

async function create(): Promise<void> {
  const setup = await awaitBackend()
  if (setup.setupRequired) fail('the instance is not set up — run bootstrap:user first')
  const salt = fromB64std(setup.instanceSalt)
  const principalToken = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  const stamp = `${Date.now().toString(36)}${randomSecret().slice(0, 4).toLowerCase()}`
  const o = await createOwner(principalToken, stamp, salt)
  console.log(`owner: ${o.owner.email} registered and accepted ${o.domain}`)
  // From here on the owner exists: its credentials are written first, so a failing optional step leaves it erasable.
  const fields: Record<string, string> = {
    VEYQ_EMAIL: o.owner.email, VEYQ_PASSWORD: o.owner.password, VEYQ_SECRET_KEY: o.owner.built.emergencyKit.secretKey,
    VEYQ_USERNAME: o.owner.username, VEYQ_TENANT: o.tenantName, VEYQ_TENANT_DOMAIN: o.domain, VEYQ_TENANT_ID: o.tenantId,
  }
  const persist = () => {
    writeFileSync(file, `# A throwaway account (throwaway-account.mts) — erase it with --erase ${file}\n${Object.entries(fields).map(([k, v]) => `${k}=${v}`).join('\n')}\n`, { mode: 0o600 })
    chmodSync(file, 0o600)
  }
  persist()
  const ownerToken = await signIn(o.owner.email, o.owner.password)
  const scopeId = await birthPersonalScope(ownerToken, o.domain, o.owner.userId, o.owner.built)
  console.log(`scope: personal scope ${scopeId}`)
  if (await mailpitReachable()) {
    await orThrow('updateTenantMailSettings', new TenantsApi(new TenantsConfiguration(tenantChannel(o.domain, ownerToken))).updateTenantMailSettings({
      tenantId: o.tenantId,
      tenantMailSettingsUpdate: { host: SMTP_HOST, port: MAILPIT_SMTP_PORT, from: `noreply@${o.domain}`, username: null, tls: false, enabled: true },
    }))
    console.log(`mail: ${o.domain} sends via Mailpit`)
  } else if (withMember) {
    fail('no Mailpit — an invite needs sendable mail settings (ADR 0129 decision 3); the owner is written, erase it with --erase')
  }
  if (withMember) {
    const member = await createMember(o, ownerToken, stamp, salt)
    Object.assign(fields, {
      VEYQ_USER2_EMAIL: member.email, VEYQ_USER2_PASSWORD: member.password,
      VEYQ_USER2_SECRET_KEY: member.built.emergencyKit.secretKey, VEYQ_USER2_USERNAME: member.username,
    })
    persist()
    console.log(`member: ${member.email} joined ${o.domain}`)
  }
  console.log(`\nthrowaway written to ${file} (0600) — erase it with: pnpm run throwaway:account -- --erase ${file}`)
}

/** Erase one account of the file: sign in, read the preview, prove the password, erase with every last-owner tenant
 *  deleted along. Returns false where the account no longer signs in (already erased). */
async function eraseOne(email: string, password: string): Promise<boolean> {
  let token: string
  try { token = await signIn(email, password) } catch (e) {
    console.warn(`${email}: does not sign in (${(e as Error).message}) — already erased?`)
    return false
  }
  // Account-level calls: the public listener, no tenant host.
  const api = new AccountErasureApi(new AccountConfiguration(publicChannel(token)))
  const preview = await orThrow('getOwnAccountErasurePreview', api.getOwnAccountErasurePreview())
  if (preview.serverOwnerDelivery) fail(`${email} holds the moderation key — erase it on the web`)
  const ch = await orThrow('login challenge', new LoginApi(new AuthConfiguration(publicChannel())).requestLoginChallenge({ loginChallengeRequest: { email } }))
  const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), email), v1.ARGON)
  let attestationSignature: string
  try {
    const me = await orThrow('key-material', new AccountApi(new AccountConfiguration(publicChannel(token))).getAccountKeyMaterial())
    attestationSignature = b64std(v1.authSign(auth.privateKey, v1.eraseAccountAttestationStatement({ userId: me.userId, nonce: fromB64std(ch.nonce) })))
  } finally {
    auth.privateKey.fill(0)
  }
  await orThrow('eraseOwnAccount', api.eraseOwnAccount({
    eraseOwnAccountRequest: {
      nonce: ch.nonce, attestationSignature,
      lastOwnerChoices: preview.tenants.filter((t) => t.otherMemberIds.length > 0).map((t) => ({ tenantId: t.tenantId, newOwnerId: null })),
    },
  }))
  console.log(`${email}: erased (${preview.tenants.map((t) => t.tenantId).join(', ') || 'no tenant'} deleted along)`)
  return true
}

async function erase(): Promise<void> {
  if (!existsSync(file)) fail(`no ${file}`)
  const t = readEnv(file)
  if (!t.VEYQ_EMAIL?.startsWith('tw-')) fail(`${file} names no throwaway (an address starting tw-) — refused`)
  // The member first: the owner's erasure then finds its tenant with no other member and deletes it along.
  if (t.VEYQ_USER2_EMAIL) await eraseOne(t.VEYQ_USER2_EMAIL, t.VEYQ_USER2_PASSWORD)
  await eraseOne(t.VEYQ_EMAIL, t.VEYQ_PASSWORD)
  const principalToken = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  const pending = await adminCall<{ erasures: { address: string; dueAt: string }[] }>(principalToken, 'GET', '/api/instance/accounts/erasures')
  const mine = pending.json.erasures.filter((e) => e.address === t.VEYQ_EMAIL || e.address === t.VEYQ_USER2_EMAIL)
  for (const e of mine) console.log(`pending erasure: ${e.address}, purged at ${e.dueAt}`)
  if (mine.length === 0) console.warn('no pending erasure listed for this throwaway')
}

if (outAt >= 0) await create()
else await erase()
