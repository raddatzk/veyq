// The manifest of a subtree export (ADR 0082, contracts/export/veyq-subtree.schema.json): the types the writer builds and
// the reader validates. Core owns the envelope and the node shape; the `content` of a node is the kind's own (opaque here),
// and the one thing core reads inside it is the payload references - see {@link collectPayloads}.
//
// The reader trusts nothing in the file: a path must be a plain `data/<id>/<name>`, ids are unique, depth and node count
// are bounded, and a `version` newer than this build knows is refused (unknown FIELDS are ignored, as the schema says).

export const MANIFEST_FORMAT = 'veyq-subtree'
export const MANIFEST_VERSION = 1
export const MANIFEST_FILE = 'manifest.json'
/** The largest manifest read into memory. A subtree of 100 000 nodes is a few tens of MB. */
export const MANIFEST_MAX_BYTES = 128 * 1024 * 1024
const MAX_DEPTH = 100
const MAX_NODES = 200_000

export type ManifestPayload = {
  path: string
  size: number
  mediaType: string
  sha256?: string
  /** Files only, optional: the file's modified time at export (ms since the epoch), restored on import. */
  modifiedAt?: number
}

export type ManifestContent = { type: string; schema?: number; [field: string]: unknown }

export type ManifestNode = {
  id: string
  kind: string
  name: string
  cover?: ManifestPayload | null
  metadata?: Record<string, unknown>
  content?: ManifestContent
  children?: ManifestNode[]
}

export type Manifest = {
  format: typeof MANIFEST_FORMAT
  version: number
  exportedAt: string
  appVersion: string
  options?: { includeLocation?: boolean }
  root: ManifestNode
}

export type ExportErrorCode = 'not_an_export' | 'newer_version' | 'invalid_manifest' | 'checksum' | 'payload_missing' | 'nothing_to_import'

/** An archive that cannot be imported, or an export that cannot be written. `code` is stable for callers. */
export class ExportError extends Error {
  constructor(readonly code: ExportErrorCode, message: string) {
    super(message)
    this.name = 'ExportError'
  }
}

const ID = /^[A-Za-z0-9_-]+$/
const PATH = /^data\/[A-Za-z0-9_-]+\/[^/\\]+$/
const SHA = /^[0-9a-f]{64}$/

const bad = (message: string): never => {
  throw new ExportError('invalid_manifest', message)
}
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** Whether `path` is a payload path an importer may open: `data/<id>/<name>`, no `.`/`..` name, no control characters. */
export function isSafePayloadPath(path: unknown): path is string {
  if (typeof path !== 'string' || !PATH.test(path)) return false
  const name = path.slice(path.lastIndexOf('/') + 1)
  return name !== '.' && name !== '..' && !/[\u0000-\u001f]/.test(name)
}

function checkPayload(v: unknown, where: string): ManifestPayload {
  if (!isObject(v)) return bad(`${where}: not a payload`)
  if (!isSafePayloadPath(v.path)) return bad(`${where}: unsafe payload path`)
  if (typeof v.size !== 'number' || !Number.isSafeInteger(v.size) || v.size < 0) return bad(`${where}: bad size`)
  if (typeof v.mediaType !== 'string') return bad(`${where}: bad mediaType`)
  if (v.sha256 !== undefined && !(typeof v.sha256 === 'string' && SHA.test(v.sha256))) return bad(`${where}: bad sha256`)
  if (v.modifiedAt !== undefined && !(typeof v.modifiedAt === 'number' && Number.isFinite(v.modifiedAt))) return bad(`${where}: bad modifiedAt`)
  return v as unknown as ManifestPayload
}

/** Validate a parsed manifest. Throws {@link ExportError}: `not_an_export` (not our format), `newer_version`, `invalid_manifest`. */
export function parseManifest(json: unknown): Manifest {
  if (!isObject(json) || json.format !== MANIFEST_FORMAT) throw new ExportError('not_an_export', 'not a veyq subtree export')
  if (typeof json.version !== 'number' || !Number.isInteger(json.version) || json.version < 1) return bad('bad version')
  if (json.version > MANIFEST_VERSION) throw new ExportError('newer_version', `export version ${json.version} is newer than this build reads`)
  if (typeof json.exportedAt !== 'string' || typeof json.appVersion !== 'string') return bad('missing exportedAt / appVersion')
  const ids = new Set<string>()
  let count = 0
  const walk = (n: unknown, depth: number): void => {
    if (depth > MAX_DEPTH) return bad('tree too deep')
    if (++count > MAX_NODES) return bad('too many nodes')
    if (!isObject(n)) return bad('node is not an object')
    if (typeof n.id !== 'string' || !ID.test(n.id)) return bad('bad node id')
    if (ids.has(n.id)) return bad(`node id used twice: ${n.id}`)
    ids.add(n.id)
    if (typeof n.kind !== 'string' || n.kind === '') return bad(`${n.id}: bad kind`)
    if (typeof n.name !== 'string' || n.name === '') return bad(`${n.id}: bad name`)
    if (n.cover != null) checkPayload(n.cover, `${n.id} cover`)
    if (n.content !== undefined && !(isObject(n.content) && typeof n.content.type === 'string')) return bad(`${n.id}: bad content`)
    if (n.content) for (const p of collectPayloadsOf(n.content)) checkPayload(p, `${n.id} content`)
    if (n.children !== undefined) {
      if (!Array.isArray(n.children)) return bad(`${n.id}: children is not a list`)
      for (const c of n.children) walk(c, depth + 1)
    }
  }
  walk(json.root, 0)
  return json as unknown as Manifest
}

/** Every payload reference inside a value: an object with a string `path` and a numeric `size`. This is how core finds the
 *  bytes of a kind's content without knowing the kind - and why the path is judged by {@link isSafePayloadPath}, not by
 *  where it points: a reference to anywhere but `data/<id>/<name>` is refused, never skipped. */
export function collectPayloadsOf(value: unknown, out: ManifestPayload[] = []): ManifestPayload[] {
  if (Array.isArray(value)) value.forEach((v) => collectPayloadsOf(v, out))
  else if (isObject(value)) {
    if (typeof value.path === 'string' && typeof value.size === 'number') out.push(value as unknown as ManifestPayload)
    else Object.values(value).forEach((v) => collectPayloadsOf(v, out))
  }
  return out
}

/** All payloads of a node (its cover and everything in its content), not its children's. */
export function collectPayloads(node: ManifestNode): ManifestPayload[] {
  const out: ManifestPayload[] = []
  if (node.cover) out.push(node.cover)
  if (node.content) collectPayloadsOf(node.content, out)
  return out
}
