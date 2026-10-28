// What `seed-volume.mts` and `measure-volume.mts` share (cr-volume-measurement-local): the throwaway instance they are
// pointed at, the transport to it, the generated credentials, and signing in as a person does. Temporary, with the rest
// of the harness (`cr-volume-measurement-cax` deletes it).
//
// The instance is described by the environment `scripts/measure_volume.py` sets (seed-volume.mts' header lists it).
// Import order matters to the caller: `./volumePrelude.mts` and `fake-indexeddb/auto` first, then this module.
import { readFileSync, writeFileSync, renameSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { installPinnedFetch, type Exchange } from './apiTransport.mts'
import { BASE, HOST } from '../src/test/e2eBootstrap'
import { session } from '../src/app/session'
import { clearVault } from '../src/app/vault'
import { login } from '../src/features/account/login/login'
import { unlockMasterKey } from '../src/features/account/unlock/unlock'
import { openScope, type OpenedScope } from '../src/features/spaces/scopes'
import { ScopeKind } from '../src/api/generated/scopes'

export function die(msg: string, code = 1): never {
  console.error(`${process.argv[1]?.split('/').at(-1) ?? 'volume'}: ${msg}`)
  process.exit(code)
}

export function need(name: string): string {
  const v = process.env[name]
  if (!v) die(`${name} is not set — the volume harness is run by scripts/measure_volume.py`)
  return v
}

export const DIR = need('VEYQ_MEASURE_DIR')

/** Every exchange with the instance goes past here; `measure-volume.mts` listens. */
export const exchanges: { listener: ((e: Exchange) => void) | null } = { listener: null }

const [connectHost, connectPort] = need('VEYQ_MEASURE_CONNECT').split(':')
installPinnedFetch({
  domain: HOST, connectHost, connectPort: Number(connectPort),
  caPem: readFileSync(join(DIR, 'pebble-root.pem'), 'utf8'),
  onExchange: (e) => exchanges.listener?.(e),
})
session.apiBase = BASE

export type Account = { email: string; password: string; username: string; secretKey: string; userId?: string }
export type Member = Account & { stage: 'built' | 'granted' | 'profiled' }
export type Accounts = { owner: Account; tenantId: string; members: Member[] }
export type Place = { scopeId: string; nodeId: string; hash: string; name: string }
export type Manifest = Partial<Record<'photos' | 'bare' | 'upload' | 'treeDeep' | 'wideDeep', Place>> & {
  tree?: { scopeId: string; folderName: string; hash: string }
  wide?: { scopeId: string; memoryName: string; hash: string }
  /** `marks`: the Memory whose owner's mark set is full, and the one photo in it past the cap (unmarked, newest). */
  marks?: Place & { marked: number; pastCap: string }
}

export function readJson<T>(name: string, fallback: T): T {
  const path = join(DIR, name)
  return existsSync(path) ? (JSON.parse(readFileSync(path, 'utf8')) as T) : fallback
}

/** Written whole and renamed into place, so a stopped run never leaves half a file. */
export function writeJson(name: string, value: unknown): void {
  const path = join(DIR, name)
  writeFileSync(`${path}.tmp`, JSON.stringify(value, null, 2))
  renameSync(`${path}.tmp`, path)
}

export function accounts(): Accounts {
  const a = readJson<Accounts | null>('accounts.json', null)
  if (!a) die('no accounts.json — run `seed-volume.mts birth` first')
  return a
}

export const manifest = (): Manifest => readJson<Manifest>('manifest.json', {})

/** Sign in and unlock as `a` through the app's own login and its PASSWORD-floor unlock — as a person at the login page. */
export async function signIn(a: Account): Promise<void> {
  clearVault()
  await login(a.email, a.password)
  await unlockMasterKey(a.password, a.secretKey, { noteDevice: false })
}

export async function tenantScope(): Promise<OpenedScope> {
  const t = await openScope(ScopeKind.Tenant)
  if (!t) die('the signed-in account holds no tenant grant')
  return t
}
