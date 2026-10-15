// The record the `upgrade` group's two members share (`scripts/run_web_e2e.py` § the upgrade proof, ADR 0057 Decision 7):
// the SEEDER writes into `$VEYQ_GROUP_DIR` what it sealed into a fresh instance, the orchestrator captures that
// instance, destroys it and restores it onto a freshly installed build, and the READER opens the restored content and
// asserts every item the record names.
//
// **It is a contract between two builds, not between two files of one tree.** Under `run_web_e2e.py --upgrade` the
// seeder runs from the PREDECESSOR's worktree and the reader from the candidate's, so the reader reads a record an
// older copy of this module wrote. Hence the `version`: a change to the shape bumps it, and the reader keeps reading
// every version a predecessor's seeder can still write (the previous main commit until the first release, the last
// release after it) - a reader that only knows the newest shape would make every proof across the change red for a
// reason that is not the upgrade.
//
// What crosses is what a PERSON would still have after the upgrade (the owner's address, password and Emergency Kit
// Secret Key, as `restoreHandoff.ts` argues) plus the QUESTION: the ids to reopen and, per item, the plaintext the
// reader must find - names, the date and place, the cover, and each byte stream as its size and SHA-256. Never a key.
//
// The orchestrator checks the same shape before it captures (`upgrade_record_problems` in `scripts/run_web_e2e.py`),
// so a seeder that wrote no usable record is named as that, not as a failed upgrade. Both validators accept
// `upgradeRecord.fixture.json` and refuse the same mutations of it (`upgradeRecord.test.ts`,
// `scripts/upgrade_proof_selftest.py`) - the one place the two definitions are held to each other.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** The record shape this module writes. Bump it with any change to the shape, and keep reading the older ones. */
export const UPGRADE_RECORD_VERSION = 1

/** The file name inside `$VEYQ_GROUP_DIR` - the orchestrator reads it under the same name. */
export const UPGRADE_RECORD_FILE = 'upgrade-record.json'

/** One byte stream the seeder sealed: what the reader must decrypt it back to. */
export type SealedBytes = { size: number; sha256: string }
export type SealedMedia = SealedBytes & { mediaId: string }
/** A file node: its name, the type and modified time its blob's sealed meta carries, and its bytes. */
export type SealedFile = SealedBytes & { nodeId: string; name: string; type: string; modifiedAt: number }

export type UpgradeRecord = {
  version: 1
  /** What the person still has (and which tenant to read the config-secret state of). */
  owner: { email: string; password: string; secretKey: string; tenantId: string }
  /** The personal scope everything below lives in. */
  scopeId: string
  /** Memories: a Memory with a date, a place, a cover (one of its photos, with a crop) and media; a chapter in it. */
  memory: {
    nodeId: string
    name: string
    date: { mode: 'range'; from: string; to: string }
    place: string
    cover: { mediaId: string; crop: { x: number; y: number; width: number; height: number } }
    media: SealedMedia[]
    chapter: { nodeId: string; name: string; media: SealedMedia[] }
  }
  /** Depot: a folder of a member's files. */
  files: { folderId: string; name: string; files: SealedFile[] }
  /** Depot: a drop box and what its guests left there, each in the folder named after them. */
  dropBox: { nodeId: string; name: string; guests: { name: string; folderId: string; files: SealedFile[] }[] }
  /** A READ link on the Memory, as a guest opens it: its title and the bytes of its media. */
  share: { shareId: string; fragmentKey: string; name: string; media: SealedBytes[] }
}

function groupDir(): string {
  const dir = process.env.VEYQ_GROUP_DIR
  if (!dir) {
    throw new Error(
      'VEYQ_GROUP_DIR is unset - this suite is a member of the `upgrade` GROUP and only means anything when run ' +
        'through scripts/run_web_e2e.py, which captures and restores the instance between its members.',
    )
  }
  return dir
}

export function writeUpgradeRecord(record: UpgradeRecord): void {
  const problems = upgradeRecordProblems(record)
  if (problems.length) throw new Error(`the seeder built a record it would refuse: ${problems.join('; ')}`)
  writeFileSync(join(groupDir(), UPGRADE_RECORD_FILE), JSON.stringify(record, null, 2))
}

/** Read and validate the record; throws naming every problem, so a malformed record never reads as a missing item. */
export function readUpgradeRecord(): UpgradeRecord {
  const raw: unknown = JSON.parse(readFileSync(join(groupDir(), UPGRADE_RECORD_FILE), 'utf8'))
  const problems = upgradeRecordProblems(raw)
  if (problems.length) throw new Error(`the upgrade record is not one this reader can read: ${problems.join('; ')}`)
  return raw as UpgradeRecord
}

/** What the reader saw, beside the record: the orchestrator's summary names it (Decision 5 of cr-upgrade-proof). */
export function writeUpgradeReading(reading: { configSecretState: string; items?: number }): void {
  writeFileSync(join(groupDir(), 'upgrade-reading.json'), JSON.stringify(reading, null, 2))
}

/** Hex SHA-256 of a byte stream - the oracle the reader compares a decrypted stream against. */
export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes as BufferSource))
  return Array.from(digest, (b) => b.toString(16).padStart(2, '0')).join('')
}

export async function sealedBytes(bytes: Uint8Array): Promise<SealedBytes> {
  return { size: bytes.length, sha256: await sha256Hex(bytes) }
}

// ---- validation -------------------------------------------------------------------------------------------------

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * Every problem with `raw` as a record, or none. Structural only - the reader then asserts the CONTENT. Every list must
 * be non-empty: a record without an item of one application would turn the reader's "every item" into "no item".
 */
export function upgradeRecordProblems(raw: unknown): string[] {
  const problems: string[] = []
  const str = (o: Obj, k: string, at: string) => {
    if (typeof o[k] !== 'string' || (o[k] as string).length === 0) problems.push(`${at}.${k} is not a non-empty string`)
  }
  const num = (o: Obj, k: string, at: string) => {
    if (typeof o[k] !== 'number' || !Number.isFinite(o[k] as number)) problems.push(`${at}.${k} is not a number`)
  }
  const obj = (o: Obj, k: string, at: string): Obj | null => {
    if (isObj(o[k])) return o[k] as Obj
    problems.push(`${at}.${k} is not an object`)
    return null
  }
  const list = (o: Obj, k: string, at: string): Obj[] => {
    const v = o[k]
    if (!Array.isArray(v) || v.length === 0) {
      problems.push(`${at}.${k} is not a non-empty list`)
      return []
    }
    return v.map((item, i) => {
      if (isObj(item)) return item
      problems.push(`${at}.${k}[${i}] is not an object`)
      return {}
    })
  }
  const bytes = (o: Obj, at: string) => {
    if (!Number.isInteger(o.size) || (o.size as number) <= 0) problems.push(`${at}.size is not a positive integer`)
    if (typeof o.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(o.sha256)) problems.push(`${at}.sha256 is not a hex SHA-256`)
  }
  const media = (o: Obj, k: string, at: string) =>
    list(o, k, at).forEach((m, i) => {
      str(m, 'mediaId', `${at}.${k}[${i}]`)
      bytes(m, `${at}.${k}[${i}]`)
    })
  const files = (o: Obj, k: string, at: string) =>
    list(o, k, at).forEach((f, i) => {
      const here = `${at}.${k}[${i}]`
      for (const key of ['nodeId', 'name', 'type']) str(f, key, here)
      num(f, 'modifiedAt', here)
      bytes(f, here)
    })

  if (!isObj(raw)) return ['the record is not a JSON object']
  if (raw.version !== UPGRADE_RECORD_VERSION) {
    return [`version ${JSON.stringify(raw.version)} is not one this reader knows (it reads ${UPGRADE_RECORD_VERSION})`]
  }
  const owner = obj(raw, 'owner', 'record')
  if (owner) for (const k of ['email', 'password', 'secretKey', 'tenantId']) str(owner, k, 'owner')
  str(raw, 'scopeId', 'record')

  const memory = obj(raw, 'memory', 'record')
  if (memory) {
    str(memory, 'nodeId', 'memory')
    str(memory, 'name', 'memory')
    str(memory, 'place', 'memory')
    const date = obj(memory, 'date', 'memory')
    if (date) {
      if (date.mode !== 'range') problems.push('memory.date.mode is not "range"')
      str(date, 'from', 'memory.date')
      str(date, 'to', 'memory.date')
    }
    const cover = obj(memory, 'cover', 'memory')
    if (cover) {
      str(cover, 'mediaId', 'memory.cover')
      const crop = obj(cover, 'crop', 'memory.cover')
      if (crop) for (const k of ['x', 'y', 'width', 'height']) num(crop, k, 'memory.cover.crop')
    }
    media(memory, 'media', 'memory')
    const chapter = obj(memory, 'chapter', 'memory')
    if (chapter) {
      str(chapter, 'nodeId', 'memory.chapter')
      str(chapter, 'name', 'memory.chapter')
      media(chapter, 'media', 'memory.chapter')
    }
  }

  const filesSection = obj(raw, 'files', 'record')
  if (filesSection) {
    str(filesSection, 'folderId', 'files')
    str(filesSection, 'name', 'files')
    files(filesSection, 'files', 'files')
  }

  const dropBox = obj(raw, 'dropBox', 'record')
  if (dropBox) {
    str(dropBox, 'nodeId', 'dropBox')
    str(dropBox, 'name', 'dropBox')
    list(dropBox, 'guests', 'dropBox').forEach((g, i) => {
      str(g, 'name', `dropBox.guests[${i}]`)
      str(g, 'folderId', `dropBox.guests[${i}]`)
      files(g, 'files', `dropBox.guests[${i}]`)
    })
  }

  const share = obj(raw, 'share', 'record')
  if (share) {
    for (const k of ['shareId', 'fragmentKey', 'name']) str(share, k, 'share')
    list(share, 'media', 'share').forEach((m, i) => bytes(m, `share.media[${i}]`))
  }
  return problems
}
