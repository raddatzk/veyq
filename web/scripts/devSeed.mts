// Reproducible dev seed (cr-0046): a gitignored file holding a local instance's seeded accounts — credentials
// AND raw key material — so a harness or browser smoke can open the vault without re-seeding. The file lives at
// web/scripts/.seed/dev-seed.json and is DEV/TEST-ONLY throwaway data for a local instance (never committed,
// never prod) — exactly the data a real user keeps in their Emergency Kit. The operator-blind wire path is
// untouched: the seed scripts still POST only ciphertext + public keys via the real buildAccount.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import * as v1 from '../src/shared/crypto/v1'
import { b64std, fromB64std } from '../src/shared/base64'
import type { BuiltAccount } from '../src/features/account/register/accountBirth'

const SEED_FILE = fileURLToPath(new URL('.seed/dev-seed.json', import.meta.url))

/** One seeded principal: credentials + raw key material to open its vault two ways. */
export type SeedEntry = {
  email: string
  password: string
  secretKey: string // b64 — the Emergency-Kit Secret Key (with the password, opens the PASSWORD wrap)
  masterKey: string // b64 — the unlocked Master Key (for a direct vault inject, skipping the unlock Argon2)
  identityPrivate: string // b64 — the serialized identity private key (deserialize → vault identity)
  userId?: string
  tenantId?: string
  domain?: string
  scopeId?: string
}

export type SeedFile = {
  base: string
  owner?: SeedEntry
  tenants: Record<string, SeedEntry> // keyed by tenant domain
}

/** Recover the raw key material from a freshly built account, re-deriving the Master Key from its PASSWORD
 *  wrap (no change to accountBirth — buildAccount returns the wraps, not the raw Master Key). */
export function materializeKeys(built: BuiltAccount, password: string): { masterKey: string; identityPrivate: string } {
  const secretKey = fromB64std(built.emergencyKit.secretKey)
  const wrap = built.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')
  if (!wrap) throw new Error('account has no PASSWORD wrap')
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = v1.kekPasswordSecret(secretKey, password, salt, params)
  const masterKey = v1.symUnwrap(kek, fromB64std(wrap.wrappedKey), 'master-key')
  return { masterKey: b64std(masterKey), identityPrivate: b64std(v1.serializeIdentityPrivate(built.identity)) }
}

export function readSeed(base = 'http://localhost:8080'): SeedFile {
  if (!existsSync(SEED_FILE)) return { base, tenants: {} }
  return JSON.parse(readFileSync(SEED_FILE, 'utf8')) as SeedFile
}

function writeSeed(seed: SeedFile): void {
  mkdirSync(dirname(SEED_FILE), { recursive: true })
  writeFileSync(SEED_FILE, JSON.stringify(seed, null, 2) + '\n')
}

/** Merge-write the Server Owner entry. */
export function writeOwner(base: string, owner: SeedEntry): void {
  const seed = readSeed(base)
  seed.base = base
  seed.owner = owner
  writeSeed(seed)
}

/** Merge-write a tenant entry (keyed by domain). */
export function writeTenant(base: string, domain: string, entry: SeedEntry): void {
  const seed = readSeed(base)
  seed.base = base
  seed.tenants[domain] = entry
  writeSeed(seed)
}

/** Drop a tenant entry by domain (e.g. the transient creation hostname after a domain remap). */
export function removeTenant(base: string, domain: string): void {
  const seed = readSeed(base)
  if (seed.tenants[domain]) {
    delete seed.tenants[domain]
    writeSeed(seed)
  }
}

/** Load the seed for a harness. Throws if absent (run `npm run seed:dev` first). */
export function loadDevSeed(): SeedFile {
  if (!existsSync(SEED_FILE)) throw new Error(`no dev seed at ${SEED_FILE} — run \`npm run seed:dev\``)
  return readSeed()
}

/** Turn a seed entry's raw key material into vault inputs (skips the unlock Argon2). */
export function vaultFromSeed(entry: SeedEntry): { masterKey: Uint8Array; identityPrivate: ReturnType<typeof v1.deserializeIdentityPrivate> } {
  return {
    masterKey: fromB64std(entry.masterKey),
    identityPrivate: v1.deserializeIdentityPrivate(fromB64std(entry.identityPrivate)),
  }
}

export const seedFilePath = SEED_FILE

/**
 * Poll the dev-invite file (the mailer's `veyq.tenant.invite.dev-file`, JSON lines) for the latest bootstrap
 * secret for `email`. Deterministic — replaces the fragile console-log scrape (ANSI + flush race).
 */
export async function awaitInvite(file: string, email: string, timeoutMs = 15000): Promise<string> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (existsSync(file)) {
      const lines = readFileSync(file, 'utf8').split('\n').filter((l) => l.trim())
      for (let i = lines.length - 1; i >= 0; i--) {
        try {
          const rec = JSON.parse(lines[i]) as { ownerEmail: string; token: string }
          if (rec.ownerEmail === email) return rec.token
        } catch { /* skip a partially-written line */ }
      }
    }
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(`no invite for ${email} in ${file} within ${timeoutMs}ms — is the backend writing veyq.tenant.invite.dev-file?`)
}
