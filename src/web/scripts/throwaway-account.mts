// Throwaway account — a fresh tenant with its own owner (and, asked, a member) on the dev instance, for a pass or a live
// test that changes, rotates or erases an account (ADR 0148 decision 12). The seeded principal and member are the shared
// test accounts; no such pass signs in as either.
//
//   pnpm run throwaway:account -- --out <file> [--with-member] [--tenant tw-<name>] [--moderator]
//                                                                create; writes the throwaway's credentials to <file>
//   pnpm run throwaway:account -- --out <file> --last-holder [--with-member [--moderator-is-member]] …
//                                                                the moderator made the last holder of the moderation key
//   pnpm run throwaway:account -- --erase <file>                 erase the throwaway (its sole-member tenant along)
//   pnpm run throwaway:account -- --status                       the roster, and whether the Server Owner holds every epoch
//
// `--tenant tw-<name>` names the tenant `tw-<name>.localhost` instead of today's `tw-<stamp>.localhost`: for a machine
// that resolves no `*.localhost` and maps one fixed name in its `hosts` file (the Windows VM's `tw-win`). The accounts'
// addresses keep their stamp (an erased account's address stays taken until its purge). A deleted tenant keeps its
// domain until its purge too (`PENDING_DELETION`, ADR 0101 decision 10), so `--erase` first moves a still-active
// tenant off its domain onto `tw-<stamp>-erased.localhost` (`changeTenantDomain`, as the principal): the fixed name is
// free again at once. A tenant whose owner was erased in the app is no longer active and cannot be moved: its name
// stays taken until the purge `--erase` lists — so a pass erases the member in the app and leaves the owner to `--erase`.
//
// The principal's credentials (`VEYQ_ENV_FILE`, else the repository's `.run/.env`; never printed) are used for one thing:
// `createTenant` on the admin listener for `tw-<stamp>.localhost` with owner `tw-<stamp>@veyq.localhost`, and — at the
// erase — to move the tenant off its domain and to list the pending erasures. The owner then registers from the returned owner invite with its own cold birth
// (`registerAndAcceptTenantInvite` with the TENANT birth grant, the same real client crypto the seeder runs), creates its
// personal scope and points its tenant's mail at Mailpit; `--with-member` invites a second throwaway by a link invite
// (its token answered to the owner, so no mailbox is read). <file> gets `VEYQ_EMAIL`, `VEYQ_PASSWORD`, `VEYQ_SECRET_KEY`,
// `VEYQ_TENANT_DOMAIN` (and the member's as `VEYQ_USER2_*`), mode 0600, and must lie outside the repository.
//
// `--moderator` makes the throwaway's OWNER a moderator of the instance (ADR 0099 §§ 1 and 3; inbox #992, #1426), by the
// Server Owner's own acts on the admin listener as admin-web does them (`src/admin-web/src/features/instance/moderation.ts`):
// `appointModerator` by the owner's address, the owner's own `acceptModeratorAppointment` (its sessions carry the
// MODERATOR role from then on), then `mintModerationEpoch` — a fresh `M_{n+1}` drawn here, its public bundle derived,
// sealed (`moderation-grant` [n+1]) to the identity PUBLIC key of every accepted moderator as the roster serves it, and
// zeroed. So the throwaway holds the new epoch only, beside every moderator already on the roster (the Server Owner
// among them): no holder's client delivers it the older epochs, and nothing of the principal's but its password is
// used (no grant of its is opened). Refused before anything is appointed: moderation off (turn it on in admin-web), or a
// roster that already lists the address. The file gets `VEYQ_MODERATOR=1` and the epoch minted (`VEYQ_MODERATION_EPOCH`).
// At `--erase`, once the throwaway left (its erasure takes it off the roster and leaves a rotation due, as a removal
// does), the Server Owner mints the next epoch for the moderators left (reason `REMOVAL`, admin-web's `removeModerator`),
// so no rotation stays due; the roster before and after is printed by count and state, never by address.
//
// `--last-holder` (implies `--moderator`) goes on to make the throwaway the LAST holder of the moderation key (ADR 0101
// decision 9, ADR 0099 § 5; inbox #1426), so its own erasure must hand every live epoch to the Server Owner, who is
// reappointed by it: the Server Owner's own client — its identity opened from the principal's password AND Secret Key, as
// the moderation surface opens it (`unlockModeration.ts`) — delivers every older epoch to the throwaway
// (`putModerationDelivery`, key-proved with the current epoch, sealed only after the served identity is checked to be the
// one this script just gave the throwaway), the throwaway's grants are opened and checked against every epoch's bundle,
// then the Server Owner is removed from the roster (`removeModerator`) and the next epoch minted for the throwaway alone
// (reason `REMOVAL`, as admin-web's removal does). Refused before anything is appointed: a roster with anyone but the
// Server Owner (a seeded appointee or any other moderator: removing the Server Owner would not leave the throwaway the
// last holder, and their grants are not ours to move), or a Server Owner not holding every live epoch. A step that fails
// after the removal restores the Server Owner at once (below). The file gets `VEYQ_LAST_HOLDER=1` and whether the Server
// Owner's moderator address was confirmed (`VEYQ_PRINCIPAL_ADDRESS_CONFIRMED`: a removal forgets the confirmation).
// Its `--erase`: where the throwaway still signs in as the last holder (a pass that did not erase it), the Server Owner is
// restored first — appointed, accepted by itself, and handed every epoch by the throwaway's client (its identity opened
// from the file's password and Secret Key) —, then the throwaway is erased as any other; last, the Server Owner's client
// opens every live grant it holds and checks it against the epoch's bundle, red (exit 1) unless it holds every live epoch,
// and a confirmation the removal forgot is made again through Mailpit (the code mailed to the principal's address).
//
// `--moderator-is-member` (with `--with-member`) makes the MEMBER the moderator (and the last holder) instead of the owner:
// a pass that erases the moderator in the app on a fixed `--tenant` name keeps the tenant active, so `--erase` can still
// move it off that name (an owner erased in the app takes its tenant along, onto a name held until the purge).
//
// `--status` prints the roster and whether the Server Owner holds every live epoch (its grants opened and checked as
// above); it changes nothing.
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
import { MAILPIT_SMTP_PORT, mailCursor, mailpitReachable, waitForMail } from '../src/test/mailpit'
import { unlockKekAsync } from '@veyq/crypto/argonWorker'
import { secretKeyBytes } from '@veyq/crypto/secretKey'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from '../src/api/generated/scopes'
import { AccountApi, AccountErasureApi, Configuration as AccountConfiguration } from '../src/api/generated/account'
import { Configuration as ModerationConfiguration, ModerationApi, ResponseError as ModerationRefusal } from '../src/api/generated/moderation'
import { Configuration as AuthConfiguration, LoginApi } from '../src/api/generated/auth'
import { adminCall, adminChannel, describeFailure, publicChannel, tenantChannel } from './apiTransport.mts'
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
const lastHolder = args.includes('--last-holder')
const asModerator = args.includes('--moderator') || lastHolder
const statusOnly = args.includes('--status')
const memberModerates = args.includes('--moderator-is-member')
if (memberModerates && !(asModerator && withMember)) fail('--moderator-is-member goes with --with-member and --moderator or --last-holder')
const tenantAt = args.indexOf('--tenant')
const fixedTenant = tenantAt >= 0 ? (args[tenantAt + 1] ?? fail('--tenant needs a name (tw-<name>)')) : null
if (fixedTenant !== null && !/^tw-[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$/.test(fixedTenant)) fail(`--tenant ${fixedTenant}: a name tw-<name> of a-z, 0-9 and inner hyphens`)
if (fixedTenant !== null && eraseAt >= 0) fail('--tenant is a create option; --erase reads the tenant from <file>')
if (asModerator && eraseAt >= 0) fail('--moderator and --last-holder are create options; --erase reads them from <file>')
const usage = 'usage: --out <file> [--with-member] [--tenant tw-<name>] [--moderator | --last-holder] [--moderator-is-member] | --erase <file> | --status'
if (statusOnly && (outAt >= 0 || eraseAt >= 0 || asModerator || withMember || fixedTenant !== null)) fail(usage)
if (!statusOnly && (outAt < 0) === (eraseAt < 0)) fail(usage)
const file = statusOnly ? '' : (args[(outAt >= 0 ? outAt : eraseAt) + 1] ?? fail('no <file> given'))
if (!existsSync(PRINCIPAL_ENV)) fail(`no principal env file at ${PRINCIPAL_ENV} — set VEYQ_ENV_FILE`)
const principal = readEnv(PRINCIPAL_ENV)
if (!statusOnly) refuseSeeded(file, principal, outAt >= 0)

const randomSecret = () => b64std(v1.randomBytes(18)).replace(/[+/=]/g, 'x')

type Born = { email: string; password: string; userId: string; username: string; built: BuiltAccount }

/** One cold-born owner of a fresh tenant, registered from the owner invite `createTenant` handed back. */
async function createOwner(principalToken: string, stamp: string, salt: Uint8Array) {
  const domain = `${fixedTenant ?? `tw-${stamp}`}.localhost`
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

// --- the instance's moderation, as the Server Owner acts on it (admin-web's `moderation.ts`, ADR 0099) ---------------------

type Roster = {
  enabled: boolean
  currentEpoch: number | null
  rotationDueSince: string | null
  serverOwner: { accountId: string }
  moderators: { accountId: string; address: string; state: 'PENDING' | 'ACCEPTED'; identityPublicKey: string | null; holdsCurrentEpoch: boolean; addressConfirmed: boolean }[]
}

async function roster(principalToken: string): Promise<Roster> {
  const r = await adminCall<Roster>(principalToken, 'GET', '/api/instance/moderation')
  if (r.status !== 200) fail(`getInstanceModeration answered ${r.status} ${JSON.stringify(r.json)}`)
  return r.json
}

/** The roster as a line: the epoch, a due rotation, and each moderator by its role and state — never an address. */
function rosterLine(r: Roster, throwawayId?: string): string {
  const who = (m: Roster['moderators'][number]) =>
    `${m.accountId === r.serverOwner.accountId ? 'the Server Owner' : m.accountId === throwawayId ? 'the throwaway' : `another (${m.accountId.slice(0, 8)})`}` +
    ` ${m.state}${m.holdsCurrentEpoch ? ' holding the current epoch' : ''}${m.addressConfirmed ? ', address confirmed' : ''}`
  return `moderation ${r.enabled ? 'on' : 'off'}, epoch ${r.currentEpoch ?? 'none'}${r.rotationDueSince ? `, a rotation due since ${r.rotationDueSince}` : ''}; ` +
    `roster: ${r.moderators.map(who).join('; ') || 'empty'}`
}

/** admin-web's `rotateModerationKey`: `M_{n+1}` drawn, its bundle derived, sealed to every accepted moderator's identity
 *  public key as [r] serves it, uploaded and zeroed. The server refuses grants that do not name exactly the accepted
 *  moderators, so a roster that moved since [r] is its refusal, never retried here. */
async function mintEpoch(principalToken: string, r: Roster, reason: 'BY_HAND' | 'REMOVAL'): Promise<Roster> {
  const epoch = (r.currentEpoch ?? 0) + 1
  const m = v1.generateModerationKey()
  try {
    const bundle = v1.moderationBundle(m)
    const grants = r.moderators.flatMap((mod) => (mod.state === 'ACCEPTED' && mod.identityPublicKey
      ? [{
          accountId: mod.accountId, identityPublicKey: mod.identityPublicKey,
          sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, v1.deserializeIdentityPublic(fromB64std(mod.identityPublicKey)).kem, m)),
        }]
      : []))
    const minted = await adminCall<Roster>(principalToken, 'POST', '/api/instance/moderation/epochs', {
      epoch, kemPublicKey: b64std(bundle.kem), signPublicKey: b64std(bundle.sign), reason, grants,
    })
    if (minted.status !== 200) fail(`mintModerationEpoch (${reason}) answered ${minted.status} ${JSON.stringify(minted.json)}`)
    return minted.json
  } finally {
    m.fill(0)
  }
}

/** The throwaway owner appointed, accepted by itself, and handed a freshly minted epoch with every accepted moderator. */
async function makeModerator(principalToken: string, owner: Born, ownerToken: string): Promise<number> {
  const seeded = [principal.VEYQ_EMAIL, principal.VEYQ_USER2_EMAIL].filter(Boolean).map((e) => e.trim().toLowerCase())
  if (seeded.includes(owner.email.toLowerCase())) fail('the appointee is a seeded account — refused')
  const before = await roster(principalToken)
  console.log(`moderation before: ${rosterLine(before)}`)
  if (!before.enabled) fail('moderation is off on this instance — turn it on in admin-web first (the throwaway is written, erase it with --erase)')
  if (before.moderators.some((m) => m.accountId === owner.userId)) fail('the throwaway is already on the roster')
  const appointed = await adminCall<Roster>(principalToken, 'POST', '/api/instance/moderation/moderators', { address: owner.email })
  if (appointed.status !== 200) fail(`appointModerator answered ${appointed.status} ${JSON.stringify(appointed.json)}`)
  const accepted = await orThrow('acceptModeratorAppointment', new ModerationApi(new ModerationConfiguration(publicChannel(ownerToken))).acceptModeratorAppointment())
  if (accepted.state !== 'ACCEPTED') fail(`the appointment is ${accepted.state} after its acceptance`)
  const after = await mintEpoch(principalToken, await roster(principalToken), 'BY_HAND')
  const mine = after.moderators.find((m) => m.accountId === owner.userId)
  if (!mine?.holdsCurrentEpoch) fail(`the throwaway does not hold epoch ${after.currentEpoch} after the mint`)
  console.log(`moderation after: ${rosterLine(after, owner.userId)}`)
  return after.currentEpoch!
}

/** After a moderator throwaway left: the epoch its erasure left due minted anew for the moderators left. */
async function settleModeration(principalToken: string, throwawayId: string | undefined): Promise<void> {
  const now = await roster(principalToken)
  if (throwawayId && now.moderators.some((m) => m.accountId === throwawayId)) {
    console.warn(`the throwaway is still on the roster — ${rosterLine(now, throwawayId)}`)
    return
  }
  if (!now.rotationDueSince) {
    console.log(`moderation: ${rosterLine(now)} — nothing due`)
    return
  }
  const after = await mintEpoch(principalToken, now, 'REMOVAL')
  console.log(`moderation: epoch ${after.currentEpoch} minted for the moderators left — ${rosterLine(after)}`)
}

// --- the last holder (`--last-holder`, `--status`): the moderation key as the holders' own clients move it ----------------

type IdentityPriv = ReturnType<typeof v1.deserializeIdentityPrivate>
const moderationOf = (token: string) => new ModerationApi(new ModerationConfiguration(publicChannel(token)))
const zeroIdentity = (priv: IdentityPriv) => { priv.kemSeed.fill(0); priv.sign.fill(0) }
const serializedPublic = (priv: IdentityPriv) => b64std(v1.serializeIdentityPublic(v1.identityPublicFromPrivate(priv)))

/** [token]'s identity private key, opened from the password AND the Secret Key as the moderation surface's unlock opens it
 *  (`unlockMasterKey`, never a device wrap); the KEK, the Secret Key's bytes and the Master Key zeroed at once. The caller
 *  zeroes what it gets ([zeroIdentity]). */
async function openIdentity(token: string, password: string, secretKeyText: string): Promise<IdentityPriv> {
  const material = await orThrow('getAccountKeyMaterial', new AccountApi(new AccountConfiguration(publicChannel(token))).getAccountKeyMaterial())
  const wrap = material.wraps.find((w) => w.method === 'PASSWORD')
  if (!wrap?.kdfParams) fail('the account has no PASSWORD wrap to open its identity with')
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const secret = secretKeyBytes(secretKeyText)
  const kek = await unlockKekAsync(secret, password, salt, params)
  let master: Uint8Array | null = null
  try {
    master = v1.open({ purpose: 'master-key' }, kek, fromB64std(wrap.wrappedKey))
    return v1.deserializeIdentityPrivate(v1.open({ purpose: 'identity-priv' }, master, fromB64std(material.wrappedIdentityPrivateKey)))
  } finally {
    kek.fill(0)
    secret.fill(0)
    master?.fill(0)
  }
}

type Held = { currentEpoch: number; live: number[]; opened: Map<number, Uint8Array> }
const zeroHeld = (h: Held | null) => { for (const m of h?.opened.values() ?? []) m.fill(0) }
const missingOf = (h: Held) => h.live.filter((e) => !h.opened.has(e))

/** Every live epoch as the server serves it to [token]'s account, and each grant it holds opened with [priv] and checked
 *  against the epoch's published bundle (the web's `openGrants`: a grant that does not open, or a bundle it does not
 *  derive, fails). Null for an account that is no moderator. The caller zeroes the opened keys ([zeroHeld]). */
async function openHeld(token: string, priv: IdentityPriv): Promise<Held | null> {
  let grants
  try { grants = await moderationOf(token).listMyModerationGrants() } catch (e) {
    if (e instanceof ModerationRefusal && e.response.status === 403) return null
    throw new Error(`listMyModerationGrants failed: ${await describeFailure(e)}`)
  }
  const opened = new Map<number, Uint8Array>()
  try {
    for (const e of grants.epochs) {
      if (!e.sealedGrant) continue
      const m = v1.openAs({ purpose: 'moderation-grant', epoch: e.epoch }, priv.kemSeed, fromB64std(e.sealedGrant))
      opened.set(e.epoch, m)
      if (!v1.moderationBundleEquals(v1.moderationBundle(m), { kem: fromB64std(e.kemPublicKey), sign: fromB64std(e.signPublicKey) })) {
        throw new Error(`epoch ${e.epoch}: the published bundle is not the one its grant opens to`)
      }
    }
  } catch (e) {
    zeroHeld({ currentEpoch: 0, live: [], opened })
    throw e
  }
  return { currentEpoch: grants.currentEpoch, live: grants.epochs.map((e) => e.epoch), opened }
}

/** A key proof for [operationId] (the web's `proofFor`), signed with the current epoch's `M_n` of [held]. */
async function proofFor(token: string, held: Held, operationId: string) {
  const m = held.opened.get(held.currentEpoch) ?? fail(`no key proof for ${operationId}: the current epoch ${held.currentEpoch} is not held`)
  const { challenge } = await orThrow('createModerationChallenge', moderationOf(token).createModerationChallenge())
  const statement = v1.moderationProofStatement({ challenge: fromB64std(challenge), operationId, caseId: null })
  return { xVeyqModerationChallenge: challenge, xVeyqModerationSignature: b64std(v1.moderationSign(m, statement)) }
}

/** A holder's delivery (the web's `deliverPending`, aimed at one recipient): every epoch [recipientId] misses that
 *  [held] holds, sealed to the recipient's identity — only once the identity the server serves for it is [expectedPublic],
 *  the key this run knows first-hand (the throwaway it created, or the principal whose own key it opened); any other is
 *  refused, nothing delivered. Returns the epochs delivered. */
async function deliverTo(token: string, held: Held, recipientId: string, expectedPublic: string): Promise<number[]> {
  const pending = await orThrow('listPendingModerationDeliveries', moderationOf(token).listPendingModerationDeliveries(await proofFor(token, held, 'listPendingModerationDeliveries')))
  const d = pending.deliveries.find((x) => x.accountId === recipientId)
  if (!d) return []
  if (d.identityPublicKey !== expectedPublic) fail('the identity served for the recipient is not the one this run knows — nothing delivered')
  const recipient = v1.deserializeIdentityPublic(fromB64std(d.identityPublicKey))
  const grants = d.epochs.filter((e) => held.opened.has(e)).map((epoch) => ({
    epoch, sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch }, recipient.kem, held.opened.get(epoch)!)),
  }))
  if (grants.length === 0) return []
  await orThrow('putModerationDelivery', moderationOf(token).putModerationDelivery({
    accountId: recipientId, moderationDelivery: { identityPublicKey: d.identityPublicKey, grants },
    ...(await proofFor(token, held, 'putModerationDelivery')),
  }))
  return grants.map((g) => g.epoch)
}

/** The principal's identity opened (`.run/.env`'s password and Secret Key, never printed) and every grant it holds checked. */
async function principalHeld(): Promise<{ token: string; priv: IdentityPriv; held: Held | null }> {
  if (!principal.VEYQ_SECRET_KEY) fail(`${PRINCIPAL_ENV} holds no VEYQ_SECRET_KEY — the Server Owner's identity cannot be opened`)
  const token = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  const priv = await openIdentity(token, principal.VEYQ_PASSWORD, principal.VEYQ_SECRET_KEY)
  return { token, priv, held: await openHeld(token, priv) }
}

/** Whether the Server Owner holds a checked grant of every live epoch, as a line — the state every `--last-holder` run ends in. */
async function serverOwnerLine(): Promise<{ holdsEvery: boolean; line: string }> {
  const p = await principalHeld()
  try {
    if (!p.held) return { holdsEvery: false, line: 'the Server Owner is no moderator (holds no epoch)' }
    const missing = missingOf(p.held)
    return {
      holdsEvery: missing.length === 0,
      line: missing.length === 0
        ? `the Server Owner holds every live epoch (${p.held.live.join(', ')}), each grant opened and checked against its bundle`
        : `the Server Owner MISSES epoch(s) ${missing.join(', ')} of ${p.held.live.join(', ')}`,
    }
  } finally {
    zeroHeld(p.held)
    zeroIdentity(p.priv)
  }
}

/** The throwaway made the last holder: refused unless the roster is the Server Owner alone holding every live epoch; then
 *  the Server Owner delivers every older epoch to the throwaway, the throwaway's grants are checked, the Server Owner is
 *  removed and the next epoch minted for the throwaway alone. A failure after the removal restores the Server Owner. */
async function makeLastHolder(principalToken: string, owner: Born, ownerToken: string, before: Roster): Promise<void> {
  const ownerId = before.serverOwner.accountId
  const p = await principalHeld()
  try {
    if (!p.held) fail('the Server Owner is no moderator — the throwaway would hold every epoch already')
    // The throwaway's own mint made it hold the current epoch; the principal holds every live one (checked before the mint).
    const delivered = await deliverTo(p.token, p.held, owner.userId, b64std(v1.serializeIdentityPublic(owner.built.identity.pub)))
    console.log(`last holder: the Server Owner's client delivered epoch(s) ${delivered.join(', ') || 'none'} to the throwaway`)
  } finally {
    zeroHeld(p.held)
    zeroIdentity(p.priv)
  }
  const mine = await openHeld(ownerToken, owner.built.identity.priv)
  try {
    if (!mine || missingOf(mine).length > 0) fail(`the throwaway does not hold every live epoch after the delivery (misses ${mine ? missingOf(mine).join(', ') : 'all'})`)
  } finally {
    zeroHeld(mine)
  }
  const removed = await adminCall<Roster>(principalToken, 'DELETE', `/api/instance/moderation/moderators/${encodeURIComponent(ownerId)}`)
  if (removed.status !== 200) fail(`removeModerator (the Server Owner) answered ${removed.status} ${JSON.stringify(removed.json)} — nothing removed`)
  try {
    const after = await mintEpoch(principalToken, removed.json, 'REMOVAL')
    const preview = await orThrow('getOwnAccountErasurePreview', new AccountErasureApi(new AccountConfiguration(publicChannel(ownerToken))).getOwnAccountErasurePreview())
    const live = await openHeld(ownerToken, owner.built.identity.priv)
    try {
      const want = (live?.live ?? []).join(',')
      if (!preview.serverOwnerDelivery || preview.serverOwnerDelivery.epochs.join(',') !== want) {
        throw new Error(`the throwaway is not the last holder: its preview asks for ${preview.serverOwnerDelivery?.epochs.join(', ') ?? 'no hand-over'}, the live epochs are ${want}`)
      }
    } finally {
      zeroHeld(live)
    }
    console.log(`last holder: the Server Owner removed, epoch ${after.currentEpoch} minted for the throwaway alone — ${rosterLine(after, owner.userId)}; ` +
      `its erasure must hand epochs ${preview.serverOwnerDelivery.epochs.join(', ')} to the Server Owner`)
  } catch (e) {
    console.error(`last holder: ${(e as Error).message} — restoring the Server Owner`)
    await restoreServerOwner(principalToken, ownerToken, owner.built.identity.priv)
    throw e
  }
}

/** The Server Owner back on the roster holding every epoch: appointed, accepted by itself, and handed every epoch it misses
 *  by the throwaway's client ([ownerPriv] its identity) — the way a moderator who joins later receives the key. */
async function restoreServerOwner(principalToken: string, ownerToken: string, ownerPriv: IdentityPriv): Promise<void> {
  const r = await roster(principalToken)
  const ownerId = r.serverOwner.accountId
  if (!r.moderators.some((m) => m.accountId === ownerId)) {
    const appointed = await adminCall<Roster>(principalToken, 'POST', '/api/instance/moderation/moderators', { address: principal.VEYQ_EMAIL })
    if (appointed.status !== 200) fail(`appointModerator (the Server Owner) answered ${appointed.status} ${JSON.stringify(appointed.json)}`)
  }
  const p = await principalHeld()
  try {
    if (r.moderators.find((m) => m.accountId === ownerId)?.state !== 'ACCEPTED') {
      await orThrow('acceptModeratorAppointment (the Server Owner)', moderationOf(p.token).acceptModeratorAppointment())
    }
    const held = await openHeld(ownerToken, ownerPriv)
    try {
      if (!held) fail('the throwaway is no moderator any more — nothing it could deliver')
      const delivered = await deliverTo(ownerToken, held, ownerId, serializedPublic(p.priv))
      console.log(`restore: the throwaway's client delivered epoch(s) ${delivered.join(', ') || 'none'} to the Server Owner`)
    } finally {
      zeroHeld(held)
    }
  } finally {
    zeroHeld(p.held)
    zeroIdentity(p.priv)
  }
}

/** The Server Owner's moderator address confirmed again where the removal forgot the confirmation: a code sent to the
 *  principal's address, read from Mailpit, entered as the principal. */
async function reconfirmServerOwnerAddress(): Promise<void> {
  const token = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  const api = moderationOf(token)
  const now = await orThrow('getMyModeratorAppointment', api.getMyModeratorAppointment())
  if (now.confirmation === 'CONFIRMED') return
  if (!(await mailpitReachable())) { console.warn('the Server Owner\'s moderator address is no longer confirmed and no Mailpit answers — confirm it in the web'); return }
  const cursor = await mailCursor(now.address)
  await orThrow('sendModeratorAddressCode', api.sendModeratorAddressCode())
  const text = await waitForMail(now.address, { after: cursor }, 60_000)
  const code = text.match(/^\s*(\d{8})\s*$/m)?.[1] ?? fail('no code found in the confirmation mail — confirm the address in the web')
  const done = await orThrow('confirmModeratorAddress', api.confirmModeratorAddress({ moderatorAddressConfirmation: { code } }))
  console.log(`the Server Owner's moderator address: ${done.confirmation}`)
}

/** `--last-holder`'s refusals, before anything is made: the roster must be the Server Owner alone, accepted, and its own
 *  client must open a checked grant of every live epoch. */
async function lastHolderPrecheck(principalToken: string): Promise<{ roster: Roster; addressConfirmed: boolean }> {
  const r = await roster(principalToken)
  console.log(`moderation before: ${rosterLine(r)}`)
  if (!r.enabled) fail('moderation is off on this instance — turn it on in admin-web first')
  const others = r.moderators.filter((m) => m.accountId !== r.serverOwner.accountId)
  if (others.length > 0) fail(`the roster lists ${others.length} moderator(s) beside the Server Owner (a seeded appointee or another) — refused: the throwaway would not be the last holder, and their grants are not ours to move`)
  const so = r.moderators.find((m) => m.accountId === r.serverOwner.accountId)
  if (so?.state !== 'ACCEPTED') fail('the Server Owner is no accepted moderator — refused')
  const holds = await serverOwnerLine()
  console.log(holds.line)
  if (!holds.holdsEvery) fail('the Server Owner does not hold every live epoch — refused (restore it first)')
  return { roster: r, addressConfirmed: so.addressConfirmed }
}

async function status(): Promise<void> {
  const principalToken = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  console.log(rosterLine(await roster(principalToken)))
  const holds = await serverOwnerLine()
  console.log(holds.line)
  if (!holds.holdsEvery) process.exitCode = 1
}

async function create(): Promise<void> {
  const setup = await awaitBackend()
  if (setup.setupRequired) fail('the instance is not set up — run bootstrap:user first')
  const salt = fromB64std(setup.instanceSalt)
  const principalToken = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  const before = lastHolder ? await lastHolderPrecheck(principalToken) : null
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
  /** The throwaway made a moderator (and the last holder, asked): the owner, or with `--moderator-is-member` the member. */
  const moderate = async (who: Born, whoToken: string) => {
    const epoch = await makeModerator(principalToken, who, whoToken)
    Object.assign(fields, {
      VEYQ_MODERATOR: '1', VEYQ_MODERATION_EPOCH: String(epoch), VEYQ_USER_ID: who.userId,
      ...(memberModerates ? { VEYQ_MODERATOR_ACCOUNT: 'member' } : {}),
    })
    persist()
    if (before) {
      Object.assign(fields, { VEYQ_PRINCIPAL_ADDRESS_CONFIRMED: before.addressConfirmed ? '1' : '0' })
      persist()
      await makeLastHolder(principalToken, who, whoToken, before.roster)
      Object.assign(fields, { VEYQ_LAST_HOLDER: '1' })
      persist()
    }
  }
  if (asModerator && !memberModerates) await moderate(o.owner, ownerToken)
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
    if (memberModerates) await moderate(member, await signIn(member.email, member.password))
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
  const principalToken = await signIn(principal.VEYQ_EMAIL, principal.VEYQ_PASSWORD)
  // Its domain freed first, while the tenant is still active (a fixed `--tenant` name is reused by the next pass).
  if (t.VEYQ_TENANT_ID && t.VEYQ_TENANT_DOMAIN?.startsWith('tw-')) {
    const away = `tw-${Date.now().toString(36)}-erased.localhost`
    const moved = await adminCall(principalToken, 'POST', `/api/tenants/${encodeURIComponent(t.VEYQ_TENANT_ID)}/change-domain`, { domain: away })
    if (moved.status === 204) console.log(`${t.VEYQ_TENANT_DOMAIN}: freed (the tenant moved to ${away})`)
    else console.warn(`${t.VEYQ_TENANT_DOMAIN}: not moved (${moved.status} ${JSON.stringify(moved.json)}) — it stays taken until the tenant's purge`)
  }
  // A last holder a pass left standing: the Server Owner restored first, so no epoch goes with the throwaway.
  if (t.VEYQ_LAST_HOLDER === '1') {
    const who = t.VEYQ_MODERATOR_ACCOUNT === 'member' ? 'VEYQ_USER2' : 'VEYQ'
    let token: string | null = null
    try { token = await signIn(t[`${who}_EMAIL`], t[`${who}_PASSWORD`]) } catch { /* erased in the app: nothing to restore from */ }
    if (token) {
      const r = await roster(principalToken)
      const so = r.moderators.find((m) => m.accountId === r.serverOwner.accountId)
      if (so?.state !== 'ACCEPTED' || !(await serverOwnerLine()).holdsEvery) {
        const priv = await openIdentity(token, t[`${who}_PASSWORD`], t[`${who}_SECRET_KEY`])
        try { await restoreServerOwner(principalToken, token, priv) } finally { zeroIdentity(priv) }
      }
    }
  }
  // The member first: the owner's erasure then finds its tenant with no other member and deletes it along.
  if (t.VEYQ_USER2_EMAIL) await eraseOne(t.VEYQ_USER2_EMAIL, t.VEYQ_USER2_PASSWORD)
  await eraseOne(t.VEYQ_EMAIL, t.VEYQ_PASSWORD)
  if (t.VEYQ_MODERATOR === '1') await settleModeration(principalToken, t.VEYQ_USER_ID)
  if (t.VEYQ_LAST_HOLDER === '1') {
    if (t.VEYQ_PRINCIPAL_ADDRESS_CONFIRMED === '1') await reconfirmServerOwnerAddress()
    const holds = await serverOwnerLine()
    console.log(`moderation after: ${rosterLine(await roster(principalToken))}; ${holds.line}`)
    if (!holds.holdsEvery) {
      console.error('throwaway-account: the Server Owner does NOT hold every live epoch — the key is not restored')
      process.exitCode = 1
    }
  }
  const pending = await adminCall<{ erasures: { address: string; dueAt: string }[] }>(principalToken, 'GET', '/api/instance/accounts/erasures')
  const mine = pending.json.erasures.filter((e) => e.address === t.VEYQ_EMAIL || e.address === t.VEYQ_USER2_EMAIL)
  for (const e of mine) console.log(`pending erasure: ${e.address}, purged at ${e.dueAt}`)
  if (mine.length === 0) console.warn('no pending erasure listed for this throwaway')
}

if (statusOnly) await status()
else if (outAt >= 0) await create()
else await erase()
