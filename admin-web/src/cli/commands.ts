// The CLI's command table — the whole of its "business logic", which is deliberately none: every entry is a
// name, its flags, and a call into the SAME feature facade `admin-web`'s views call (ADR 0065 — the CLI is an
// HTTP client of the Admin API, written in Node specifically so it can import the same generated client
// rather than re-implement the contract). If a command here ever needs a rule of its own, that rule belongs
// in the facade, where the UI gets it too.
//
// The set mirrors what `admin-web` actually holds, not ADR 0065's original (corrected-since) list: tenant CRUD
// + a tenant's deletion and its stop, and the owner invite of an ownerless tenant (ADR 0101 decisions 9 and 10)
// + an account's erasure, its stop and the pending erasures (ADR 0101 decisions 1-3, uc.account.erase-account)
// + the tenant storage quota + release info with the release check's notices and its switch + the config-secret state +
// the object-store setting and its check + the bucket reconcile and its two switches + the capability switches with
// their requirements + the external services +
// the moderator roster and the rotation of the instance moderation key (ADR 0099) + the Server Owner's moderation
// steps: the moderators' open proposals, a member's sign-in block and its lift (uc.moderation.take-a-measure step 5;
// a tenant-offline proposal is taken with `tenant suspend`, which settles it) + the operating mode and the
// operator's published values (ADR 0093 §§ 11 and 14) + the guest-write record's retention (ADR 0093 § 10). The
// operating mode's notice is printed from admin-web's own
// i18n (`i18n/operatingModeNotice.ts`), the words the console shows, and *private* needs `--accept-notice` — the
// facade's rule (`operatingModeRequest`), not one of this file's. The notification address's code is typed into the
// console only, as instance mail's is.
// Per-tenant mail settings, terms authoring and the per-link storage
// limit are TENANT-OWNER actions gated with `403 not_tenant_owner` and live in `web/` (admin-web/CLAUDE.md), so
// they are not Server-Owner commands. The instance's OWN mail settings are the Server Owner's, but stay console-only
// as tenant mail is: their save sends a code that is typed back into the same screen.
// First-run **setup** is excluded on purpose (cr-admin-cli): it is a one-time interactive ceremony that mints
// key material and force-confirms a saved Emergency Kit — not something a script should be able to do blind.
// `moderation rotate` (and `moderator remove`, which rotates after a holder leaves) mints key material too, and is a
// command anyway, because it shares none of what excludes setup: ADR 0099 § 4 names `veyq-admin moderation rotate`; it
// draws `M_n`, seals it to the moderators' identity PUBLIC keys only, uploads, and forgets it — there is nothing to
// save and nothing to confirm, and it opens nothing (`features/instance/moderation.noOpen.test.ts` holds that for
// every file here). The minting itself is the facade's, the same one the console's Moderation panel calls.
import {
  changeTenantDomain,
  createTenant,
  deleteTenant,
  inviteTenantOwner,
  listTenants,
  reactivateTenant,
  reassignInvite,
  resendInvite,
  parseQuotaGib,
  revokeTenant,
  setTenantStorageQuota,
  stopTenantDeletion,
  suspendTenant,
} from '../features/tenants/tenants'
import { eraseAccount, listAccountErasures, stopAccountErasure } from '../features/instance/accounts'
import { CliError, renderResult } from './output'
import { getInstanceRelease } from '../features/instance/release'
import { getReleaseNotices, setReleaseCheck } from '../features/instance/releaseNotices'
import { getInstanceConfigSecret } from '../features/instance/configSecret'
import {
  BUNDLED_STORE_UPDATE,
  checkInstanceStore,
  discardPendingInstanceStore,
  getInstanceStore,
  InstanceStoreType,
  ownStoreUpdate,
  setInstanceStore,
} from '../features/instance/store'
import {
  confirmExclusiveBucket,
  getStoreReconcile,
  pauseReconcile,
  resumeReconcile,
  withdrawExclusiveBucket,
} from '../features/instance/storeReconcile'
import {
  capabilityTree,
  inLocale,
  listInstanceCapabilities,
  setInstanceCapability,
  setInstanceCapabilityRequirement,
  clearInstanceCapabilityRequirement,
  requirementValue,
  type CapabilityLocale,
  type CapabilityNode,
  type InstanceCapability,
} from '../features/instance/capabilities'
import {
  clearExternalService,
  listExternalServices,
  setExternalService,
  type InstanceExternalService,
} from '../features/instance/externalServices'
import {
  appointModerator,
  blockSignIn,
  declineProposal,
  getModeration,
  getModerationSteps,
  liftSignInBlock,
  ProposalStep,
  removeModerator,
  rotateModerationKey,
  RotationReason,
  type InstanceModeration,
  type ModerationSteps,
} from '../features/instance/moderation'
import {
  clearOperator,
  CLI_RECIPIENT_KINDS,
  getOperatingMode,
  getOperator,
  needsAcceptance,
  OperatingMode,
  operatorUpdateOf,
  parseLanguages,
  parseRecipient,
  presetChanges,
  setOperatingMode,
  setOperator,
  type InstanceOperatingMode,
  type InstanceOperatorUpdate,
} from '../features/instance/operator'
import {
  eraseGuestWriteRecordsOfIdentity,
  getGuestWriteRecord,
  isIdentityId,
  MAX_DAYS,
  MIN_DAYS,
  needsReason,
  parseRetentionDays,
  setGuestWriteRecord,
  type GuestWriteRecordErasure,
  type InstanceGuestWriteRecord,
} from '../features/instance/guestWriteRecord'
import * as backupRetention from '../features/instance/backupRetention'
import { noticeText, OPERATING_MODE_NOTICE } from '../i18n/operatingModeNotice'
import { enterOrder, OrderInvalid, type AuthorityOrder, type OrderKind } from '../features/instance/orders'
import { GROUNDS, stepStatement, StepStatementInvalid, type ServerOwnerStatementInput, type StepStatementDraft } from '../features/instance/stepStatement'
import { formTime, renderOrderForm } from '../features/instance/orderForms'

/** One `--flag value` a command takes. `node:util.parseArgs` has no notion of "required", so we carry it. */
export type Flag = {
  describe: string
  required: boolean
  /** A bare `--flag` with no value; it reads as `'true'` when given and is absent otherwise. */
  switch?: boolean
  /** The only values the flag takes; anything else is refused before signing in. */
  oneOf?: string[]
}

export type Command = {
  /** How the command is written on the command line, flags included — the one line `--help` prints. */
  usage: string
  summary: string
  flags: Record<string, Flag>
  /** The one positional argument the command takes after its words (`capabilities off <id>`); it reaches `run`
   *  under its `name` beside the flags. Required when declared. */
  operand?: { name: string; describe: string }
  /** Resolves to the API result, or `undefined` for a 204 operation that returns no body. */
  run: (flags: Record<string, string>, env: Record<string, string | undefined>) => Promise<unknown>
  /** The exit code for a result the command printed — 0 when absent. Lets a read that finds a problem (a failed
   *  check) still print the whole document and then fail a script. */
  exitCode?: (result: unknown) => number
  /** Text for a person instead of the JSON document — for a command whose output is the served words in the
   *  operator's language, which a script has no use for and a person cannot read as JSON. */
  render?: (result: unknown, flags: Record<string, string>, env: Record<string, string | undefined>) => string
}

const tenantId: Flag = { describe: 'tenant id', required: true }
/** A Server Owner's step's statement of reasons over content (ADR 0093 § 7, `statementOf`). */
const STATEMENT_FLAGS: Record<string, Flag> = {
  'over-content': { describe: 'the step is over content though no case or proposal names it — it then carries a statement', required: false, switch: true },
  ground: { describe: 'the ground the statement rests on', required: false, oneOf: GROUNDS },
  provision: { describe: 'the law or the clause it relies on, e.g. "§ 86a StGB" or "Space terms, clause 4"', required: false },
  facts: { describe: 'what you found, in your words — mailed to the affected; the server keeps it only sealed', required: false },
}
const domain: Flag = { describe: 'domain the tenant is reached at', required: true }
const ownerEmail: Flag = { describe: "owner's email address", required: true }
const lang: Flag = { describe: 'the language of the entry texts (default: from LC_ALL, then LANG)', required: false, oneOf: ['en', 'de'] }
const capabilityId = { name: 'id', describe: 'the capability id, as `capabilities list` prints it' }
const serviceId = { name: 'id', describe: 'the service’s register entry, as `services list` prints it' }

/** One service as a line: its entry, then its address (and attribution), or `not configured`. */
function renderService(s: InstanceExternalService): string {
  if (!s.url) return `${s.id}  not configured`
  return `${s.id}  ${s.url}${s.attribution ? `  (${s.attribution})` : ''}`
}

/**
 * The language the served entry texts are printed in: `--lang` if given, else the POSIX locale — `LC_ALL`, then
 * `LANG`, as the C library reads them (a set `LC_ALL` wins even when it is not German). `de*` is German, anything
 * else (`C`, `POSIX`, unset, a language the catalog does not write) is English.
 */
export function cliLocale(flags: Record<string, string>, env: Record<string, string | undefined>): CapabilityLocale {
  if (flags.lang) return flags.lang as CapabilityLocale
  const tag = env.LC_ALL || env.LANG || ''
  return tag.toLowerCase().startsWith('de') ? 'de' : 'en'
}

/** The state token of one entry: `on`, `off`, or `off through <id>`. A fixed token, like `config-secret`'s `MISMATCH`:
 *  it reads the same in every locale, and the id it names is what `capabilities on` takes. */
function stateToken(node: CapabilityNode): string {
  if (node.state.kind === 'notConfigured') return 'not configured'
  return node.state.kind === 'offThrough' ? `off through ${node.state.through.id}` : node.state.kind
}

/** The tree as columns: the id indented by its depth, the state token, the served title, and the served purpose on
 *  the line below, under the title. */
function renderCapabilities(entries: InstanceCapability[], locale: CapabilityLocale): string {
  const tree = capabilityTree(entries)
  const ids = tree.map((n) => `${'  '.repeat(n.depth)}${n.entry.id}`)
  const states = tree.map(stateToken)
  const idWidth = Math.max(...ids.map((i) => i.length))
  const stateWidth = Math.max(...states.map((st) => st.length))
  const under = ' '.repeat(idWidth + stateWidth + 4)
  return tree
    .map((n, i) => `${ids[i].padEnd(idWidth)}  ${states[i].padEnd(stateWidth)}  ${inLocale(n.entry.title, locale)}\n${under}${inLocale(n.entry.purpose, locale)}${
      n.entry.requirements.map((r) => `\n${under}requirement ${r.name}: ${requirementValue(r)}  ${inLocale(r.title, locale)}`).join('')
    }`)
    .join('\n')
}

/** A switched entry's line, read from the PUT's answer. Which ancestor it is off through is not in that answer
 *  (`capabilities list` names it), so the line says only that it is one. */
function renderSwitched(entry: InstanceCapability, locale: CapabilityLocale): string {
  const state = entry.off ? 'off' : entry.effective ? 'on' : 'off through a parent'
  return `${entry.id}  ${state}  ${inLocale(entry.title, locale)}`
}

/** The flags every `order …` command takes (uc.moderation.authority-order). */
const ORDER_FLAGS: Record<string, Flag> = {
  share: { describe: 'the link the order names, or its id', required: true },
  reference: { describe: 'the authority’s reference — sealed here, never sent readable', required: true },
  'received-at': { describe: 'when the order reached the contact point, ISO 8601 (default: now)', required: false },
  lang,
}

/** `--reason` as typed → the reason category the moderation log records. */
const ROTATION_REASONS: Record<string, RotationReason> = {
  'by-hand': RotationReason.ByHand,
  compromise: RotationReason.Compromise,
  'identity-rotation': RotationReason.IdentityRotation,
  removal: RotationReason.Removal,
}

/** The roster as a script reads it: whether moderation is on, the epoch, a due rotation, and per moderator its
 *  address, its state and whether it holds the current key. The identity public keys stay out — they are what the
 *  facade seals to, not what an operator reads. */
function renderRoster(r: InstanceModeration): string {
  return renderResult({
    enabled: r.enabled,
    currentEpoch: r.currentEpoch ?? null,
    rotationDueSince: r.rotationDueSince ?? null,
    moderators: r.moderators.map((m) => ({ address: m.address, state: m.state, holdsKey: m.holdsCurrentEpoch })),
  })
}

/** The roster entry with this address (case-insensitive, as the server matches an account address). */
async function moderatorByAddress(address: string): Promise<string> {
  const wanted = address.trim().toLowerCase()
  const found = (await getModeration()).moderators.find((m) => m.address.toLowerCase() === wanted)
  if (!found) throw new CliError(`${address} is not on the moderator roster (see \`moderator list\`)`)
  return found.accountId
}

/** The Server Owner's steps as a script reads them: per open proposal its id (what `moderation decline` takes), its
 *  case, the case's category, the step and its target — the member's address (what `account block` takes), or the
 *  tenant's id and domain (what `tenant suspend` takes) — and when; per block in force its address, case and since. */
function renderSteps(s: ModerationSteps): string {
  return renderResult({
    proposals: s.proposals.map((p) => ({
      id: p.id,
      caseId: p.caseId ?? null,
      category: p.category,
      step: p.step,
      ...(p.step === ProposalStep.TenantOffline ? { tenantId: p.tenantId ?? null, tenantDomain: p.tenantDomain ?? null } : { address: p.address ?? null }),
      proposedAt: p.proposedAt,
    })),
    signInBlocks: s.signInBlocks.map((b) => ({ address: b.address, caseId: b.caseId ?? null, blockedAt: b.blockedAt })),
  })
}

/** The sign-in block in force for this address (case-insensitive, as the server matches an account address). */
async function signInBlockByAddress(address: string): Promise<string> {
  const wanted = address.trim().toLowerCase()
  const found = (await getModerationSteps()).signInBlocks.find((b) => b.address.toLowerCase() === wanted)
  if (!found) throw new CliError(`${address} has no sign-in block in force (see \`moderation proposals\`)`)
  return found.id
}

/** `mode set <word>` → the mode. */
const MODES: Record<string, OperatingMode> = { private: OperatingMode.Private, organisation: OperatingMode.Organisation }
const MODE_WORD: Record<OperatingMode, string> = { PRIVATE: 'private', ORGANISATION: 'organisation' }

/** The mode, the notice, and which preset switches the other mode would change — what `mode show` prints, and what
 *  `mode set` prints after setting (the notice "either way", ADR 0093 § 14). */
function renderMode(m: InstanceOperatingMode, locale: CapabilityLocale): string {
  const other = m.mode === OperatingMode.Private ? OperatingMode.Organisation : OperatingMode.Private
  const changes = presetChanges(m.preset, other)
  const lines = [
    `mode ${MODE_WORD[m.mode]}${m.chosen ? '' : ' (none chosen; runs as organisation)'}`,
    '',
    noticeText(locale),
    '',
    changes.length
      ? `mode set ${MODE_WORD[other]} would switch:\n${changes.map((c) => `  ${c.entry.id}  ${c.on ? 'on' : 'off'}  ${inLocale(c.entry.title, locale)}`).join('\n')}`
      : `mode set ${MODE_WORD[other]} would switch nothing`,
  ]
  return lines.join('\n')
}

/** The operator flags `operator set` takes, each to its field. `none` clears one; a flag left out keeps its value. */
const OPERATOR_FLAGS: Record<string, keyof InstanceOperatorUpdate> = {
  'contact-email': 'contactEmail',
  'contact-languages': 'contactLanguages',
  'statement-email': 'statementEmail',
  'notification-email': 'notificationEmail',
  'provider-name': 'providerName',
  'provider-address': 'providerAddress',
  'provider-email': 'providerEmail',
  'provider-representative': 'providerRepresentative',
  'supervisory-authority': 'supervisoryAuthority',
  'recipients': 'recipients',
  'data-protection-officer': 'dataProtectionOfficer',
}

/** The kinds `--recipients` takes, as the CLI spells them. */
const RECIPIENT_KIND_WORDS = Object.keys(CLI_RECIPIENT_KINDS).join(', ')

/** `--recipients` as typed: rows `<name>|<kind>|<country>` separated by `;`, the country optional and an `other` kind
 *  with its words (`other:<what they do>`) — the whole list, replacing the one there. A malformed row is refused, naming
 *  it, rather than half-stored. */
function parseRecipients(typed: string): NonNullable<InstanceOperatorUpdate['recipients']> {
  return typed.split(';').map((x) => x.trim()).filter(Boolean).map((row) => {
    const r = parseRecipient(row)
    if (!r) throw new CliError(`--recipients: '${row}' is not <name>|<kind>|<country> — the kind one of ${RECIPIENT_KIND_WORDS}, other with its words as other:<what they do>, the country may be left out`)
    return r
  })
}

/**
 * Keyed by the command words as typed (`tenant create`, `release`) — a flat map rather than a nested tree,
 * because two words is the whole depth and a tree would be machinery for one level of nesting.
 */
export const COMMANDS: Record<string, Command> = {
  'tenant create': {
    usage: 'tenant create --name <name> --domain <domain> --owner-email <email>',
    summary: 'Create a PENDING tenant and mint its owner invite.',
    flags: { name: { describe: 'tenant name', required: true }, domain, 'owner-email': ownerEmail },
    run: (f) => createTenant(f.name, f.domain, f['owner-email']),
  },
  'tenant list': {
    usage: 'tenant list',
    summary: 'List all tenants with their invite status, storage usage and quota (bytes; null = no quota).',
    flags: {},
    run: () => listTenants(),
  },
  'tenant resend': {
    usage: 'tenant resend --tenant-id <id>',
    summary: "Re-mint a pending tenant's invite for the same owner email (no mail: hand over the copy-link).",
    flags: { 'tenant-id': tenantId },
    run: (f) => resendInvite(f['tenant-id']),
  },
  'tenant reassign': {
    usage: 'tenant reassign --tenant-id <id> --owner-email <email>',
    summary: "Re-bind a pending tenant's invite to a different owner email.",
    flags: { 'tenant-id': tenantId, 'owner-email': ownerEmail },
    run: (f) => reassignInvite(f['tenant-id'], f['owner-email']),
  },
  'tenant change-domain': {
    usage: 'tenant change-domain --tenant-id <id> --domain <domain>',
    summary: 'Move a tenant to a different domain.',
    flags: { 'tenant-id': tenantId, domain },
    run: (f) => changeTenantDomain(f['tenant-id'], f.domain),
  },
  'tenant revoke': {
    usage: 'tenant revoke --tenant-id <id>',
    summary: 'Revoke a pending tenant.',
    flags: { 'tenant-id': tenantId },
    run: (f) => revokeTenant(f['tenant-id']),
  },
  'tenant suspend': {
    usage: 'tenant suspend --tenant-id <id> [--over-content --ground <ground> [--provision <text>] [--facts <text>]]',
    summary: 'Take an ACTIVE tenant offline (reversible; touches no key, grant or content). Over content — a moderator proposed it, or it is over what the tenant hosts — it carries a statement of reasons, mailed with the facts to each owner of the tenant; the provision and the facts are required on an instance run by a club, an organisation or a company. A draft until a lawyer has reviewed it.',
    flags: { 'tenant-id': tenantId, ...STATEMENT_FLAGS },
    run: async (f) => suspendTenant(f['tenant-id'], await statementOf(f)),
  },
  'tenant reactivate': {
    usage: 'tenant reactivate --tenant-id <id>',
    summary: 'Bring a SUSPENDED tenant back to ACTIVE.',
    flags: { 'tenant-id': tenantId },
    run: (f) => reactivateTenant(f['tenant-id']),
  },
  'tenant delete': {
    usage: 'tenant delete --tenant-id <id> --domain <domain> [--over-content --ground <ground> [--provision <text>] [--facts <text>]]',
    summary:
      'Delete an ACTIVE or SUSPENDED tenant: offline at once, everything in it erased after 30 days (printed as dueAt) unless `tenant stop-deletion`. Members keep their accounts and are mailed. --domain is the confirmation: the tenant’s own domain, checked by the server. Over content — a moderator proposed taking it offline, or it is over what the tenant hosts — it carries a statement of reasons, mailed with the facts to each owner, as `tenant suspend` does.',
    flags: { 'tenant-id': tenantId, domain: { describe: 'the tenant’s own domain, typed as the confirmation', required: true }, ...STATEMENT_FLAGS },
    run: async (f) => deleteTenant(f['tenant-id'], f.domain, await statementOf(f)),
  },
  'tenant stop-deletion': {
    usage: 'tenant stop-deletion --tenant-id <id>',
    summary: 'Stop a tenant’s pending deletion before it is erased; it returns SUSPENDED (bring it back with `tenant reactivate`).',
    flags: { 'tenant-id': tenantId },
    run: (f) => stopTenantDeletion(f['tenant-id']),
  },
  'tenant invite-owner': {
    usage: 'tenant invite-owner --tenant-id <id> --email <address>',
    summary:
      'Invite one of an ACTIVE tenant’s members to own it — the way out of an ownerless tenant. Mailed where instance mail is set up (delivery SENT); otherwise hand over the copy-link from bootstrapSecret.',
    flags: { 'tenant-id': tenantId, email: { describe: 'the address of one of the tenant’s members', required: true } },
    run: (f) => inviteTenantOwner(f['tenant-id'], f.email),
  },
  'tenant quota': {
    usage: 'tenant quota --tenant-id <id> --gib <GiB|none>',
    summary: "Set a tenant's storage quota in GiB, or clear it with none (deletes nothing).",
    flags: { 'tenant-id': tenantId, gib: { describe: 'the quota in GiB, or none', required: true } },
    run: (f) => {
      const bytes = parseQuotaGib(f.gib)
      if (bytes === undefined) throw new CliError(`--gib must be a number of GiB, 0 or more, or none (got '${f.gib}')`)
      return setTenantStorageQuota(f['tenant-id'], bytes)
    },
  },
  release: {
    usage: 'release',
    summary:
      'Print the release version the running instance was published under, the daily release check (on or off, its source, its last success) and every newer release it found — an UNSCHEDULED_MAJOR means upgrade now.',
    flags: {},
    // `release` stays the document's first key, so a script that read it before still reads it.
    run: async () => ({ ...(await getInstanceRelease()), ...(await getReleaseNotices()) }),
  },
  'release check': {
    usage: 'release check <on|off>',
    summary: 'Switch the daily release check on or off. Off, the instance makes no request at all; the source is kept.',
    flags: {},
    operand: { name: 'state', describe: 'on or off' },
    run: async (f) => {
      if (f.state !== 'on' && f.state !== 'off') throw new CliError(`release check takes on or off (got '${f.state}')`)
      const current = await getReleaseNotices()
      return setReleaseCheck(f.state === 'on', current.source ?? null)
    },
  },
  'config-secret': {
    usage: 'config-secret',
    summary: "Print the config-secret key's state (OK, ABSENT or MISMATCH) — a restore's verification read; it changes nothing.",
    flags: {},
    run: () => getInstanceConfigSecret(),
  },
  'store show': {
    usage: 'store show',
    summary:
      'Print the object-store setting in use and its last check, a saved change still waiting for its check (pending), and whether a bundled store is offered. Secrets are never printed.',
    flags: {},
    run: () => getInstanceStore(),
  },
  'store set': {
    usage:
      'store set --bundled | --endpoint <url> --bucket <bucket> --region <region> --access-key <id> [--type S3|GARAGE] [--public-endpoint <url>] [--garage-admin-endpoint <url>]',
    summary:
      'Save an object-store setting as pending: the bundled one, or one of your own. Nothing changes for the media until `store check` passes, which puts it in use. Secrets come from VEYQ_STORE_SECRET_KEY and VEYQ_STORE_GARAGE_ADMIN_TOKEN (never flags); unset keeps the stored one.',
    flags: {
      bundled: { describe: 'use the store the deployment brings; no other flag may be given', required: false, switch: true },
      type: { describe: 'S3 (default) or GARAGE', required: false },
      endpoint: { describe: 'the S3 endpoint this server reaches the store at', required: false },
      'public-endpoint': { describe: 'the origin browsers reach the store at, if not the endpoint', required: false },
      bucket: { describe: 'the bucket', required: false },
      region: { describe: 'the region the store signs for', required: false },
      'access-key': { describe: 'the access key id', required: false },
      'garage-admin-endpoint': { describe: "Garage's admin API endpoint (GARAGE only)", required: false },
    },
    run: (f, env) => {
      if (f.bundled) {
        const others = Object.keys(f).filter((k) => k !== 'bundled')
        if (others.length) throw new CliError(`--bundled takes no other flag (got --${others.join(', --')})`)
        return setInstanceStore(BUNDLED_STORE_UPDATE)
      }
      for (const flag of ['endpoint', 'bucket', 'region', 'access-key']) {
        if (!f[flag]) throw new CliError(`--${flag} is required unless --bundled is given`)
      }
      const type = (f.type ?? 'S3').toUpperCase()
      if (type !== InstanceStoreType.S3 && type !== InstanceStoreType.Garage) {
        throw new CliError(`--type must be S3 or GARAGE (got '${f.type}')`)
      }
      return setInstanceStore(
        ownStoreUpdate({
          type,
          endpoint: f.endpoint,
          publicEndpoint: f['public-endpoint'] ?? '',
          bucket: f.bucket,
          region: f.region,
          accessKey: f['access-key'],
          secretKey: env.VEYQ_STORE_SECRET_KEY ?? '',
          garageAdminEndpoint: f['garage-admin-endpoint'] ?? '',
          garageAdminToken: env.VEYQ_STORE_GARAGE_ADMIN_TOKEN ?? '',
        }),
      )
    },
  },
  'store check': {
    usage: 'store check',
    summary:
      'Check the pending object store (else the one in use) step by step; a pending one that passes is put in use. Prints every step and exits 1 when the check failed. Tenants can be created only after a passed check.',
    flags: {},
    run: () => checkInstanceStore(),
    exitCode: (r) => ((r as { passed?: boolean }).passed === true ? 0 : 1),
  },
  'store discard': {
    usage: 'store discard',
    summary: 'Discard the pending object-store setting; the one in use stays. Changes nothing when none is pending.',
    flags: {},
    run: () => discardPendingInstanceStore(),
  },
  'store reconcile': {
    usage: 'store reconcile [--confirm-exclusive-bucket | --withdraw-exclusive-bucket | --pause | --resume]',
    summary:
      'Print what the bucket reconcile found: the last full listing, the objects no record claims, the candidates with the date the first is deleted, and the verdict (ARMED, NOT_CONFIRMED, PAUSED, NO_STORE). With one switch, change it first: confirm that this bucket holds this server\'s objects alone (arms deleting after the grace), withdraw that, pause or resume deleting.',
    flags: {
      'confirm-exclusive-bucket': {
        describe: "confirm no other server or tool writes to this bucket; candidates past their grace are then deleted",
        required: false,
        switch: true,
      },
      'withdraw-exclusive-bucket': { describe: 'withdraw that confirmation: only count again', required: false, switch: true },
      pause: { describe: 'pause deleting; the counting goes on', required: false, switch: true },
      resume: { describe: 'resume deleting after a pause', required: false, switch: true },
    },
    run: (f) => {
      const SWITCHES = {
        'confirm-exclusive-bucket': confirmExclusiveBucket,
        'withdraw-exclusive-bucket': withdrawExclusiveBucket,
        pause: pauseReconcile,
        resume: resumeReconcile,
      } as const
      const given = (Object.keys(SWITCHES) as (keyof typeof SWITCHES)[]).filter((k) => f[k])
      if (given.length > 1) throw new CliError(`store reconcile takes one switch at most (got --${given.join(', --')})`)
      return given.length ? SWITCHES[given[0]]() : getStoreReconcile()
    },
  },
  'capabilities list': {
    usage: 'capabilities list [--lang en|de]',
    summary: 'Print the capability register as a tree: each entry on, off, or off through its nearest switched-off ancestor, with its title and purpose.',
    flags: { lang },
    run: () => listInstanceCapabilities(),
    render: (r, f, env) => renderCapabilities(r as InstanceCapability[], cliLocale(f, env)),
  },
  'capabilities off': {
    usage: 'capabilities off <id> [--lang en|de]',
    summary: 'Switch an entry off at once and print what that does to new and to existing data. Deletes nothing.',
    flags: { lang },
    operand: capabilityId,
    run: (f) => setInstanceCapability(f.id, true),
    // The effect is printed from the PUT's answer, so it is the server's word for the entry it actually switched,
    // and nothing is printed for an id it refused (404 capability_not_found, exit 1).
    render: (r, f, env) => {
      const entry = r as InstanceCapability
      const locale = cliLocale(f, env)
      return `${inLocale(entry.whenOff, locale)}\n\n${renderSwitched(entry, locale)}`
    },
  },
  'capabilities require': {
    usage: 'capabilities require <id> --requirement <name> --value <n|off> [--lang en|de]',
    summary: 'Set a requirement on an entry for the instance (1 for a flag, else days 1-3650), or clear it with off, and print what it does to new and to existing data.',
    flags: {
      requirement: { describe: 'the requirement name, as `capabilities list` prints it', required: true },
      value: { describe: '1 for a flag, a number of days (1-3650), or off to clear', required: true },
      lang,
    },
    operand: capabilityId,
    run: (f) => (f.value === 'off'
      ? clearInstanceCapabilityRequirement(f.id, f.requirement)
      : setInstanceCapabilityRequirement(f.id, f.requirement, Number(f.value))),
    // Printed from the answer: the server's words for the requirement it stored, never a copy kept here.
    render: (r, f, env) => {
      const entry = r as InstanceCapability
      const locale = cliLocale(f, env)
      const req = entry.requirements.find((x) => x.name === f.requirement)
      if (!req) return renderSwitched(entry, locale)
      const effect = req.value === null || req.value === undefined ? '' : `${inLocale(req.whenSet, locale)}\n\n`
      return `${effect}${entry.id}  requirement ${req.name}: ${requirementValue(req)}  ${inLocale(req.title, locale)}`
    },
  },
  'services list': {
    usage: 'services list',
    summary: 'Print the external services a member’s browser calls, each with its address or "not configured".',
    flags: {},
    run: () => listExternalServices(),
    render: (r) => (r as InstanceExternalService[]).map(renderService).join('\n'),
  },
  'services set': {
    usage: 'services set <id> --url <url> [--attribution <text>]',
    summary: 'Configure a service’s address (the map tiles: a {z}/{x}/{y} template and an attribution); acts at once.',
    flags: {
      url: { describe: 'an http(s) address; the map tiles take a template with {z}, {x} and {y}', required: true },
      attribution: { describe: 'the attribution shown on every map (map tiles only)', required: false },
    },
    operand: serviceId,
    run: (f) => setExternalService(f.id, f.url, f.attribution ?? null),
    render: (r) => renderService(r as InstanceExternalService),
  },
  'services unset': {
    usage: 'services unset <id>',
    summary: 'Stop using a service: no browser calls it, and the served policy leaves its origin out. Deletes nothing.',
    flags: {},
    operand: serviceId,
    run: (f) => clearExternalService(f.id),
    render: (r) => renderService(r as InstanceExternalService),
  },
  'capabilities on': {
    usage: 'capabilities on <id> [--lang en|de]',
    summary: 'Switch an entry back on at once. Under a parent that is still off, it stays off until that parent is on.',
    flags: { lang },
    operand: capabilityId,
    run: (f) => setInstanceCapability(f.id, false),
    render: (r, f, env) => renderSwitched(r as InstanceCapability, cliLocale(f, env)),
  },
  'moderator list': {
    usage: 'moderator list',
    summary: 'Print the moderator roster: whether moderation is on, the key’s current epoch, a due rotation, and each moderator’s address, state and whether it holds the key.',
    flags: {},
    run: () => getModeration(),
    render: (r) => renderRoster(r as InstanceModeration),
  },
  'moderator appoint': {
    usage: 'moderator appoint --address <address>',
    summary: 'Appoint an existing account as a moderator. It stays PENDING until they accept in the web app’s moderation page; a holder then delivers the key.',
    flags: { address: { describe: 'the account’s address', required: true } },
    run: (f) => appointModerator(f.address),
    render: (r) => renderRoster(r as InstanceModeration),
  },
  'moderator remove': {
    usage: 'moderator remove --address <address>',
    summary: 'Remove a moderator; if they held the key, rotate it for the remaining moderators (reason removal).',
    flags: { address: { describe: 'the moderator’s address, as `moderator list` prints it', required: true } },
    run: async (f) => removeModerator(await moderatorByAddress(f.address)),
    render: (r) => renderRoster(r as InstanceModeration),
  },
  'mode show': {
    usage: 'mode show [--lang en|de]',
    summary: 'Print the operating mode, its notice, and which switches the other mode would set.',
    flags: { lang },
    run: () => getOperatingMode(),
    render: (r, f, env) => renderMode(r as InstanceOperatingMode, cliLocale(f, env)),
  },
  'mode set': {
    usage: 'mode set <private|organisation> [--accept-notice] [--lang en|de]',
    summary: 'Set the operating mode and the switches its preset names. Your own assessment, not veyq’s: private needs --accept-notice. Turns neither reporting nor the moderators off.',
    flags: {
      'accept-notice': { describe: 'you have read the notice and choose private on your own responsibility', required: false, switch: true },
      lang,
    },
    operand: { name: 'mode', describe: 'private or organisation' },
    run: async (f, env) => {
      const mode = MODES[f.mode]
      if (!mode) throw new CliError(`mode set takes private or organisation (got '${f.mode}')`)
      const locale = cliLocale(f, env)
      if (needsAcceptance(mode) && f['accept-notice'] !== 'true') {
        throw new CliError(`${noticeText(locale)}\n\n${OPERATING_MODE_NOTICE[locale].accept}\nPass --accept-notice to choose private; nothing was set.`)
      }
      return setOperatingMode(mode, f['accept-notice'] === 'true')
    },
    render: (r, f, env) => renderMode(r as InstanceOperatingMode, cliLocale(f, env)),
  },
  'guest-write-record show': {
    usage: 'guest-write-record show',
    summary:
      'Print how many days a guest’s write keeps its IP address, source port and time (default 7, whether you set it). Whether anything is kept at all is the capability core.public-shares.guest-write-record.',
    flags: {},
    run: () => getGuestWriteRecord(),
    render: (r) => renderGuestWriteRecord(r as InstanceGuestWriteRecord),
  },
  'guest-write-record set': {
    usage: 'guest-write-record set <days>',
    summary:
      'Set how many days a guest’s write is kept, 1 to 30. A shorter time reaches what is kept at the next cleanup, a longer one only what is kept from now on. More than 7 needs a reason in your record of processing.',
    flags: {},
    operand: { name: 'days', describe: `whole days, ${MIN_DAYS} to ${MAX_DAYS}` },
    run: (f) => {
      const days = parseRetentionDays(f.days ?? '')
      if (days === undefined) throw new CliError(`guest-write-record set takes whole days from ${MIN_DAYS} to ${MAX_DAYS} (got '${f.days}'); nothing was set`)
      return setGuestWriteRecord(days)
    },
    render: (r) => renderGuestWriteRecord(r as InstanceGuestWriteRecord),
  },
  'guest-write-record forget': {
    usage: 'guest-write-record forget <identity-id>',
    summary:
      'A guest objects to the record (GDPR Art. 21): delete every kept write of one share identity — its IP address, source port and time. What a hold copied for a report or an authority’s order stays until the hold ends; the output says how much.',
    flags: {},
    operand: { name: 'identity-id', describe: 'the share identity’s id, as a moderation case names it' },
    run: (f) => {
      const id = f['identity-id'] ?? ''
      if (!isIdentityId(id)) throw new CliError(`guest-write-record forget takes a share identity's id, a UUID (got '${id}'); nothing was deleted`)
      return eraseGuestWriteRecordsOfIdentity(id.trim())
    },
    render: (r, f) => renderErasure(r as GuestWriteRecordErasure, (f['identity-id'] ?? '').trim()),
  },
  'backup-retention show': {
    usage: 'backup-retention show',
    summary:
      'Print how many days every backup veyq’s tools take is kept, the newest too (default 30, whether you set it): veyqctl’s captures on the host, the cluster gate’s on its volume.',
    flags: {},
    run: () => backupRetention.getBackupRetention(),
    render: (r) => renderBackupRetention(r as backupRetention.InstanceBackupRetention),
  },
  'backup-retention set': {
    usage: 'backup-retention set <days>',
    summary:
      'Set how many days a backup is kept, 7 to 365. The next backup, start or daily prune deletes every backup older than that. More than 30 needs a reason in your record of processing.',
    flags: {},
    operand: { name: 'days', describe: `whole days, ${backupRetention.MIN_DAYS} to ${backupRetention.MAX_DAYS}` },
    run: (f) => {
      const days = backupRetention.parseRetentionDays(f.days ?? '')
      if (days === undefined) throw new CliError(`backup-retention set takes whole days from ${backupRetention.MIN_DAYS} to ${backupRetention.MAX_DAYS} (got '${f.days}'); nothing was set`)
      return backupRetention.setBackupRetention(days)
    },
    render: (r) => renderBackupRetention(r as backupRetention.InstanceBackupRetention),
  },
  'operator show': {
    usage: 'operator show',
    summary: 'Print the instance contact, the provider identification, the supervisory authority and the notification address with its confirmation.',
    flags: {},
    run: () => getOperator(),
  },
  'operator set': {
    usage: 'operator set [--contact-email <a>] [--contact-languages <de,en>] [--statement-email <a>] [--notification-email <a>] [--provider-name <n>] [--provider-address <a>] [--provider-email <a>] [--provider-representative <n>] [--supervisory-authority <n>] [--recipients \'<name>|<hosting|object-storage|mail-relay|backup|other:<what they do>>|<country>; …\'] [--data-protection-officer <n>]',
    summary: 'Change the given values (none clears one) and keep the rest; --recipients replaces the privacy notice’s whole list. A new notification address gets a code by instance mail, typed into the console’s Server → Operator.',
    flags: Object.fromEntries(
      Object.keys(OPERATOR_FLAGS).map((k) => [k, { describe: `${k.replace(/-/g, ' ')}, or none to clear it`, required: false }]),
    ),
    run: async (f) => {
      const given = Object.keys(OPERATOR_FLAGS).filter((k) => f[k] !== undefined)
      if (!given.length) throw new CliError('operator set needs at least one value (see --help); operator clear clears them all')
      const update: InstanceOperatorUpdate = operatorUpdateOf(await getOperator())
      for (const k of given) {
        const value = f[k] === 'none' ? null : f[k]
        if (k === 'contact-languages') update.contactLanguages = value === null ? null : parseLanguages(value)
        else if (k === 'recipients') update.recipients = value === null ? [] : parseRecipients(value)
        else (update as Record<string, string | null>)[OPERATOR_FLAGS[k]] = value
      }
      return setOperator(update)
    },
  },
  'operator clear': {
    usage: 'operator clear',
    summary: 'Clear every operator value, the notification address and its confirmation included. The operating mode stays.',
    flags: {},
    run: () => clearOperator(),
  },
  'moderation rotate': {
    usage: 'moderation rotate --reason by-hand|compromise|identity-rotation|removal',
    summary: 'Mint a new moderation key, sealed to every moderator who has accepted; reports from then on are sealed to it. The key is never printed or kept.',
    flags: { reason: { describe: 'the reason category the moderation log records', required: true, oneOf: Object.keys(ROTATION_REASONS) } },
    run: (f) => rotateModerationKey(ROTATION_REASONS[f.reason]),
    render: (r) => renderRoster(r as InstanceModeration),
  },
  'moderation proposals': {
    usage: 'moderation proposals',
    summary: 'Print the moderators’ open proposals — each with its case, category, step and target (a member’s address, or a tenant) — and every sign-in block in force. Take a sign-in proposal with `account block`, a tenant one with `tenant suspend`.',
    flags: {},
    run: () => getModerationSteps(),
    render: (r) => renderSteps(r as ModerationSteps),
  },
  'moderation decline': {
    usage: 'moderation decline --proposal <id>',
    summary: 'Decline an open proposal instead of taking it; the case shows it as declined and the moderation log records it.',
    flags: { proposal: { describe: 'the proposal id, as `moderation proposals` prints it', required: true } },
    run: (f) => declineProposal(f.proposal),
    render: (r) => renderSteps(r as ModerationSteps),
  },
  'account block': {
    usage: 'account block --address <address> [--case <caseId>] [--over-content] [--ground <ground> [--provision <text>] [--facts <text>]]',
    summary: 'Block an account’s sign-in: every session of it ends at once and every sign-in is refused until `account unblock`. Settles its open sign-in proposals as taken. Deletes nothing. Over content — a case, an open proposal, or --over-content — it carries a statement of reasons, mailed with the facts to the account’s address; the provision and the facts are required on an instance run by a club, an organisation or a company. A draft until a lawyer has reviewed it.',
    flags: {
      address: { describe: 'the account’s address', required: true },
      case: { describe: 'the case the block answers, as `moderation proposals` prints it', required: false },
      ...STATEMENT_FLAGS,
    },
    run: async (f) => blockSignIn(f.address, f.case ?? null, await statementOf(f), f['over-content'] === 'true'),
    render: (r) => renderSteps(r as ModerationSteps),
  },
  'account unblock': {
    usage: 'account unblock --address <address>',
    summary: 'Lift the sign-in block of an account; it signs in again with its own keys.',
    flags: { address: { describe: 'the blocked account’s address, as `moderation proposals` prints it', required: true } },
    run: async (f) => liftSignInBlock(await signInBlockByAddress(f.address)),
    render: (r) => renderSteps(r as ModerationSteps),
  },
  'account erase': {
    usage: 'account erase --address <address>',
    summary:
      'Erase an account: it stops at once (sessions end, sign-in refused) and is erased after 30 days (printed as dueAt) unless `account stop-erasure`. Not the Server Owner’s own account; a last owner’s tenant is left ownerless (`tenant list`); holds and moderation records stay.',
    flags: { address: { describe: 'the account’s address', required: true } },
    run: (f) => eraseAccount(f.address),
  },
  'account stop-erasure': {
    usage: 'account stop-erasure --address <address>',
    summary: 'Stop a pending account erasure before the purge; the account signs in again.',
    flags: { address: { describe: 'the account’s address, as `account erasures` prints it', required: true } },
    run: (f) => stopAccountErasure(f.address),
  },
  'account erasures': {
    usage: 'account erasures',
    summary: 'Print the pending account erasures, earliest due first: each address, when and by whom (SELF or SERVER_OWNER) it was requested, and when it is erased.',
    flags: {},
    run: () => listAccountErasures(),
  },
  'order remove': {
    usage: 'order remove --share <link|id> --reference <text> [--received-at <ISO time>] [--defer-until <ISO time>] [--lang en|de]',
    summary: 'Enter a terrorist-content removal order: the link and everything it gives access to are blocked at once, by id, for everyone. Prints the block time and the text for the authority’s feedback form (TCO Annex II), a draft until a lawyer has reviewed it. Only the link’s id is sent — never the key after #.',
    flags: {
      ...ORDER_FLAGS,
      'defer-until': { describe: 'the authority forbids telling the affected until then — at most six weeks ahead', required: false },
    },
    run: (f) => order('REMOVAL', f),
    render: renderOrder,
  },
  'order preserve': {
    usage: 'order preserve --share <link|id> --reference <text> [--received-at <ISO time>] [--lang en|de]',
    summary: 'Enter a European Preservation Order: what the link gives access to is kept for 60 days from receipt, without a block.',
    flags: ORDER_FLAGS,
    run: (f) => order('PRESERVATION', f),
    render: renderOrder,
  },
  'order inform': {
    usage: 'order inform --share <link|id> --reference <text> [--received-at <ISO time>] [--hold-days <days>] [--lang en|de]',
    summary: 'Enter a request for information: what the link gives access to is kept for the days it names (six months when it names none), without a block. Record the handover in the moderation surface.',
    flags: { ...ORDER_FLAGS, 'hold-days': { describe: 'the days the request names', required: false } },
    run: (f) => order('INFORMATION', f),
    render: renderOrder,
  },
}

/**
 * The statement of reasons a Server Owner's step carries over content (ADR 0093 § 7), from the flags: none without
 * --ground (the server refuses a step over content without one, `statement_required`); the facts sealed for the log
 * to the moderation key's public half (`stepStatement.ts`).
 */
async function statementOf(f: Record<string, string>): Promise<ServerOwnerStatementInput | null> {
  if (!f.ground) {
    if (f.provision || f.facts) throw new CliError('--ground names the ground the statement rests on; give it with --provision and --facts')
    if (f['over-content'] === 'true') throw new CliError('a step over content carries a statement of reasons: give --ground, and --provision and --facts')
    return null
  }
  try {
    return await stepStatement({ ground: f.ground as StepStatementDraft['ground'], provision: f.provision ?? '', facts: f.facts ?? '' })
  } catch (e) {
    if (e instanceof StepStatementInvalid) throw new CliError('moderation is off, so the facts cannot be sealed to its key — turn it on first')
    throw e
  }
}

/** An ISO 8601 time as typed, or a refusal naming its flag. */
function instant(text: string, flag: string): Date {
  const d = new Date(text)
  if (Number.isNaN(d.getTime())) throw new CliError(`--${flag} must be an ISO 8601 time, such as 2026-10-18T09:12:00+02:00`)
  return d
}

/** Enter an order of [kind] from the flags; this client's refusals as words, before anything is sent. */
async function order(kind: OrderKind, f: Record<string, string>): Promise<AuthorityOrder> {
  const holdDays = f['hold-days'] ? Number(f['hold-days']) : null
  if (holdDays !== null && (!Number.isInteger(holdDays) || holdDays < 1)) throw new CliError('--hold-days must be a whole number of days')
  try {
    return await enterOrder({
      kind,
      share: f.share,
      reference: f.reference,
      receivedAt: f['received-at'] ? instant(f['received-at'], 'received-at') : new Date(),
      holdDays,
      deferStatementUntil: f['defer-until'] ? instant(f['defer-until'], 'defer-until') : null,
    })
  } catch (e) {
    if (e instanceof OrderInvalid) {
      throw new CliError(
        e.reason === 'share' ? '--share is neither a link of this instance nor a link id'
        : e.reason === 'reference' ? '--reference is empty'
        : 'moderation is off, so nothing can be sealed to its key — turn it on first',
      )
    }
    throw e
  }
}

/** The order as recorded, in words, then the form text to copy (Annex II after a block, the Annex III draft when the
 *  link is not on this instance) with the reference as typed — the server holds it sealed. */
function renderOrder(r: unknown, flags: Record<string, string>, env: Record<string, string | undefined>): string {
  const o = r as AuthorityOrder
  const lines = [`order ${o.id}  ${o.kind}  ${o.found ? 'found' : 'not found on this instance'}`]
  if (o.blockedAt) lines.push(`blocked at ${formTime(o.blockedAt)}`)
  if (o.holdUntil) lines.push(`kept until ${formTime(o.holdUntil)}`)
  if (o.statementDeferredUntil) lines.push(`the affected are told on ${formTime(o.statementDeferredUntil)}, unless renewed`)
  const form = !o.found ? 'annexIII' : o.blockedAt ? 'annexII' : null
  if (form) {
    lines.push('', renderOrderForm(form, cliLocale(flags, env), {
      reference: flags.reference, orderId: o.id, shareId: o.shareId, receivedAt: o.receivedAt, blockedAt: o.blockedAt,
      provider: o.providerName, contact: o.contactPoint,
    }))
  }
  return lines.join('\n')
}

/** The retention in words, with the reason owed above seven days (ADR 0093 § 10) — the console's warning, here. */
function renderGuestWriteRecord(r: InstanceGuestWriteRecord): string {
  const lines = [`guest-write-record ${r.retentionDays} days${r.chosen ? '' : ' (the default)'}`]
  if (needsReason(r.retentionDays)) lines.push('More than 7 days needs a reason, which you write into your record of processing.')
  return lines.join('\n')
}

/** B in words, with the reason owed above thirty days (ADR 0056 decision 8) — the console's warning, here. */
function renderBackupRetention(r: backupRetention.InstanceBackupRetention): string {
  const lines = [`backup-retention ${r.retentionDays} days${r.chosen ? '' : ' (the default)'}`]
  if (backupRetention.needsReason(r.retentionDays)) lines.push('More than 30 days needs a reason, which you write into your record of processing.')
  return lines.join('\n')
}

/** What an objection deleted, and what a hold keeps — said, so the Server Owner can tell the guest (GDPR Art. 12(3)). */
function renderErasure(r: GuestWriteRecordErasure, identityId: string): string {
  const lines = [`guest-write-record forget ${identityId}: ${r.erased} kept write(s) deleted`]
  lines.push(
    r.keptInHolds > 0
      ? `${r.keptInHolds} copied into a hold stay until the hold ends — a report or an authority's order concerns them.`
      : 'No hold keeps a copy.',
  )
  lines.push('A later write by the same identity is recorded again.')
  return lines.join('\n')
}
