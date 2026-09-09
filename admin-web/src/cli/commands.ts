// The CLI's command table — the whole of its "business logic", which is deliberately none: every entry is a
// name, its flags, and a call into the SAME feature facade `admin-web`'s views call (ADR 0065 — the CLI is an
// HTTP client of the Admin API, written in Node specifically so it can import the same generated client
// rather than re-implement the contract). If a command here ever needs a rule of its own, that rule belongs
// in the facade, where the UI gets it too.
//
// The set mirrors what `admin-web` actually holds, not ADR 0065's original (corrected-since) list: tenant CRUD
// + release info. Per-tenant mail settings and terms authoring are TENANT-OWNER actions gated with
// `403 not_tenant_owner` and live in `web/` (admin-web/CLAUDE.md), so they are not Server-Owner commands.
// First-run **setup** is excluded on purpose (cr-admin-cli): it is a one-time interactive ceremony that mints
// key material and force-confirms a saved Emergency Kit — not something a script should be able to do blind.
import {
  changeTenantDomain,
  createTenant,
  listTenants,
  reactivateTenant,
  reassignInvite,
  resendInvite,
  revokeTenant,
  suspendTenant,
} from '../features/tenants/tenants'
import { getInstanceRelease } from '../features/instance/release'

/** One `--flag value` a command takes. `node:util.parseArgs` has no notion of "required", so we carry it. */
export type Flag = { describe: string; required: boolean }

export type Command = {
  /** How the command is written on the command line, flags included — the one line `--help` prints. */
  usage: string
  summary: string
  flags: Record<string, Flag>
  /** Resolves to the API result, or `undefined` for a 204 operation that returns no body. */
  run: (flags: Record<string, string>) => Promise<unknown>
}

const tenantId: Flag = { describe: 'tenant id', required: true }
const domain: Flag = { describe: 'domain the tenant is reached at', required: true }
const ownerEmail: Flag = { describe: "owner's email address", required: true }

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
    summary: 'List all tenants with their current invite status.',
    flags: {},
    run: () => listTenants(),
  },
  'tenant resend': {
    usage: 'tenant resend --tenant-id <id>',
    summary: "Re-mint and resend a pending tenant's invite to the same owner email.",
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
    usage: 'tenant suspend --tenant-id <id>',
    summary: 'Take an ACTIVE tenant offline (reversible; touches no key, grant or content).',
    flags: { 'tenant-id': tenantId },
    run: (f) => suspendTenant(f['tenant-id']),
  },
  'tenant reactivate': {
    usage: 'tenant reactivate --tenant-id <id>',
    summary: 'Bring a SUSPENDED tenant back to ACTIVE.',
    flags: { 'tenant-id': tenantId },
    run: (f) => reactivateTenant(f['tenant-id']),
  },
  release: {
    usage: 'release',
    summary: 'Print the release version the running instance was published under.',
    flags: {},
    run: () => getInstanceRelease(),
  },
}
