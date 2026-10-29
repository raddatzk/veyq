// The manifest of a download (ADR 0082, contracts/export/veyq-subtree.schema.json): the types the writer builds and the
// reader validates. Core owns the envelope and the node shape; the `content` of a node is the kind's own (opaque here, the
// kind validates it - `KindDescriptor.validateContent`), and the one thing core reads inside it is the payload references -
// see {@link collectPayloads}.
//
// Two versions are read, one is written. Version 2 (the writer's) sits at `.veyq/manifest.json` beside the tree: every node
// has its archive `path`, every payload lies at or under its node's path, and `applications` names each application the tree
// carries with its content-schema version. Version 1 sat at `manifest.json` with every payload under `data/<id>/<name>`;
// archives written before the tree layout stay importable.
//
// The reader trusts nothing in the file (decision 10): the shape and every field's length against the contract, ids
// unique, depth and node count bounded, every path made of safe names (`archiveName.ts`) and none reaching into `.veyq/`,
// and a `version` newer than this build knows refused (unknown FIELDS are ignored, as the schema says). Each refusal is an
// {@link ExportError} whose code the import dialog explains.
import { isArchivePath } from './archiveName'

export const MANIFEST_FORMAT = 'veyq-subtree'
/** The version this build writes; it reads every version up to it. */
export const MANIFEST_VERSION = 2
/**
 * What `appVersion` names: the platform's one major (ADR 0085 decision 1), never the precise release. The built bundle is
 * served to anyone who asks, before any login, so a release compiled into it is a release on the pre-auth surface, which
 * ADR 0057 §4 keeps to the Server Owner (a precise release is a CVE list); the major is public already (`GET /api/setup`).
 * scripts/validate_release_identity.py holds the image build to it: no stage that compiles the client takes the stamp.
 */
export const MANIFEST_APP_VERSION = String(__VEYQ_MAJOR__)
/** Where version 2 keeps the manifest: beside the tree, in a directory no node may take. */
export const MANIFEST_PATH = '.veyq/manifest.json'
/** Where version 1 kept it. */
export const MANIFEST_PATH_V1 = 'manifest.json'
/** The largest manifest read into memory. A subtree of 100 000 nodes is a few tens of MB. */
export const MANIFEST_MAX_BYTES = 128 * 1024 * 1024
export const MAX_DEPTH = 100
export const MAX_NODES = 200_000
/** The contract's `maxLength` (and `maxItems`) of every field core reads; `manifest.test.ts` pins each to the schema. */
export const LIMITS = {
  appVersion: 64,
  applications: 64,
  kind: 64,
  name: 1024,
  path: 65535,
  contentType: 64,
  mediaType: 255,
} as const

export type ManifestPayload = {
  path: string
  size: number
  mediaType: string
  sha256?: string
  /** Depot only, optional: the file's modified time at export (ms since the epoch), restored on import. */
  modifiedAt?: number
}

export type ManifestContent = { type: string; schema?: number; [field: string]: unknown }

export type ManifestNode = {
  id: string
  kind: string
  name: string
  /** Version 2: where the node sits in the archive (a directory, or the file itself for a kind that is one file). */
  path?: string
  cover?: ManifestPayload | null
  metadata?: Record<string, unknown>
  content?: ManifestContent
  children?: ManifestNode[]
}

export type ManifestApplication = { id: string; contentSchema: number }

export type Manifest = {
  format: typeof MANIFEST_FORMAT
  version: number
  exportedAt: string
  appVersion: string
  /** Version 2: each application the tree carries, with the content-schema version written. */
  applications?: ManifestApplication[]
  /** Version 1 only (ignored). */
  options?: { includeLocation?: boolean }
  root: ManifestNode
}

/** Why an archive cannot be imported (or a download cannot be written). Stable for callers; the import dialog explains each.
 *  - `not_an_export`: no veyq manifest where one belongs (an ordinary ZIP or folder);
 *  - `newer_version`: a manifest version this build does not read;
 *  - `invalid_manifest`: the manifest is not the contract's shape;
 *  - `too_large`: over a cap - node count, depth, a name's or a field's length, the manifest's size;
 *  - `unsafe_path`: a path that is not made of safe names, leaves its node or reaches into `.veyq/`;
 *  - `damaged_archive`: the ZIP's own structure (an offset outside the file, entries that overlap, a size that is not the
 *    declared one, a ZIP64 field out of range, a compressed or encrypted entry);
 *  - `payload_missing`: a file the manifest names is not in the archive;
 *  - `size_mismatch`: a file is not the size the manifest says;
 *  - `checksum`: a file's bytes do not match its SHA-256 (or the ZIP's CRC-32);
 *  - `nothing_to_import`: nothing in it can be imported by this build. */
export type ExportErrorCode =
  | 'not_an_export' | 'newer_version' | 'invalid_manifest' | 'too_large' | 'unsafe_path' | 'damaged_archive'
  | 'payload_missing' | 'size_mismatch' | 'checksum' | 'nothing_to_import'

/** An archive that cannot be imported, or a download that cannot be written. `code` is stable for callers; `detail` (a path,
 *  a node's name) may be shown beside the explanation; the message is for developers. */
export class ExportError extends Error {
  constructor(readonly code: ExportErrorCode, message: string, readonly detail?: string) {
    super(message)
    this.name = 'ExportError'
  }
}

const ID = /^[A-Za-z0-9_-]+$/
const APPLICATION_ID = /^[a-z][a-z0-9-]{0,63}$/
const CONTENT_TYPE = /^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$/
const PATH_V1 = /^data\/[A-Za-z0-9_-]+\/[^/\\]+$/
const SHA = /^[0-9a-f]{64}$/

const fail = (code: ExportErrorCode, message: string, detail?: string): never => {
  throw new ExportError(code, message, detail)
}
const bad = (message: string, detail?: string): never => fail('invalid_manifest', message, detail)
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const isCount = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 1

/** A bounded string field: not a string is the shape's fault, too long is a cap. */
function text(v: unknown, max: number, what: string, detail?: string, min = 0): string {
  if (typeof v !== 'string' || v.length < min) return bad(`${what}: not a string`, detail)
  if (v.length > max) return fail('too_large', `${what}: longer than ${max}`, detail)
  return v
}

/** Whether `path` is a version 1 payload path an importer may open: `data/<id>/<name>`, no `.`/`..` name, no control characters. */
export function isSafePayloadPathV1(path: unknown): path is string {
  if (typeof path !== 'string' || !PATH_V1.test(path)) return false
  const name = path.slice(path.lastIndexOf('/') + 1)
  return name !== '.' && name !== '..' && !/[\u0000-\u001f]/.test(name)
}

/** The application a content type belongs to: the part before the dot. */
export const applicationOf = (contentType: string): string => contentType.slice(0, contentType.indexOf('.'))

/** Whether `path` (a payload's) lies where version 2 puts a payload of the node at `nodePath`: the node's own path (a kind
 *  that is one file) or a file directly in the node's directory. */
const payloadOfNode = (path: string, nodePath: string): boolean =>
  path === nodePath || (path.startsWith(`${nodePath}/`) && !path.slice(nodePath.length + 1).includes('/'))

function checkPayload(v: unknown, version: number, nodePath: string | undefined, where: string): ManifestPayload {
  if (!isObject(v)) return bad(`${where}: not a payload`, where)
  const path = text(v.path, LIMITS.path, `${where} path`, where, 1)
  if (version === 1 ? !isSafePayloadPathV1(path) : !isArchivePath(path) || !payloadOfNode(path, nodePath!)) {
    return fail('unsafe_path', `${where}: unsafe payload path`, path)
  }
  if (typeof v.size !== 'number' || !Number.isSafeInteger(v.size) || v.size < 0) return bad(`${where}: bad size`, path)
  text(v.mediaType, LIMITS.mediaType, `${where} mediaType`, path)
  if (v.sha256 !== undefined && !(typeof v.sha256 === 'string' && SHA.test(v.sha256))) return bad(`${where}: bad sha256`, path)
  if (v.modifiedAt !== undefined && !(typeof v.modifiedAt === 'number' && Number.isFinite(v.modifiedAt))) return bad(`${where}: bad modifiedAt`, path)
  return v as unknown as ManifestPayload
}

function checkApplications(v: unknown): Map<string, number> {
  if (!Array.isArray(v)) return bad('applications is not a list')
  if (v.length > LIMITS.applications) return fail('too_large', 'too many applications')
  const applications = new Map<string, number>()
  for (const a of v) {
    if (!isObject(a) || typeof a.id !== 'string' || !APPLICATION_ID.test(a.id) || !isCount(a.contentSchema)) return bad('bad application entry')
    if (applications.has(a.id)) return bad(`application listed twice: ${a.id}`)
    applications.set(a.id, a.contentSchema)
  }
  return applications
}

/**
 * Validate a parsed manifest of any version this build reads. Throws {@link ExportError}: `not_an_export` (not our format),
 * `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`.
 */
export function parseManifest(json: unknown): Manifest {
  if (!isObject(json) || json.format !== MANIFEST_FORMAT) return fail('not_an_export', 'not a veyq download')
  const version = json.version
  if (!isCount(version)) return bad('bad version')
  if (version > MANIFEST_VERSION) return fail('newer_version', `version ${version} is newer than this build reads`)
  text(json.exportedAt, LIMITS.appVersion, 'exportedAt', undefined, 1)
  text(json.appVersion, LIMITS.appVersion, 'appVersion')
  const applications = version >= 2 ? checkApplications(json.applications) : null
  const ids = new Set<string>()
  let count = 0
  const walk = (n: unknown, depth: number, parentPath: string | null): void => {
    if (depth > MAX_DEPTH) return fail('too_large', 'tree too deep')
    if (++count > MAX_NODES) return fail('too_large', 'too many nodes')
    if (!isObject(n)) return bad('node is not an object')
    if (typeof n.id !== 'string' || !ID.test(n.id) || n.id.length > LIMITS.kind) return bad('bad node id')
    if (ids.has(n.id)) return bad(`node id used twice: ${n.id}`)
    ids.add(n.id)
    const name = text(n.name, LIMITS.name, `${n.id} name`, undefined, 1)
    text(n.kind, LIMITS.kind, `${n.id} kind`, name, 1)
    let path: string | undefined
    if (version >= 2) {
      path = text(n.path, LIMITS.path, `${n.id} path`, name, 1)
      // the exported node is one safe name; every other node is one segment below its parent
      const fits = parentPath === null ? !path.includes('/') : path.startsWith(`${parentPath}/`) && !path.slice(parentPath.length + 1).includes('/')
      if (!isArchivePath(path) || !fits) return fail('unsafe_path', `${n.id}: unsafe node path`, path)
    }
    if (n.cover != null) checkPayload(n.cover, version, path, `${name} (cover)`)
    if (n.content !== undefined) {
      if (!isObject(n.content)) return bad(`${n.id}: bad content`, name)
      const type = text(n.content.type, LIMITS.contentType, `${n.id} content type`, name, 1)
      if (!CONTENT_TYPE.test(type)) return bad(`${n.id}: bad content type`, name)
      if (n.content.schema !== undefined && !isCount(n.content.schema)) return bad(`${n.id}: bad content schema`, name)
      if (applications && !applications.has(applicationOf(type))) return bad(`${n.id}: application ${applicationOf(type)} is not listed`, name)
      for (const p of collectPayloadsOf(n.content)) checkPayload(p, version, path, name)
    }
    if (n.children !== undefined) {
      if (!Array.isArray(n.children)) return bad(`${n.id}: children is not a list`, name)
      for (const c of n.children) walk(c, depth + 1, path ?? null)
    }
  }
  walk(json.root, 0, null)
  return json as unknown as Manifest
}

/** Every payload reference inside a value: an object with a string `path` and a numeric `size`. This is how core finds the
 *  bytes of a kind's content without knowing the kind - and why every one is judged by where it points: a reference to
 *  anywhere but its node's place is refused, never skipped. */
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
