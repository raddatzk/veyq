// The manifest of a download (ADR 0082, contracts/export/veyq-subtree.schema.json): the types the writer builds and the
// reader takes, and its check — the platform's Kotlin `export/ManifestRules`, drawn through `@veyq/core-js/export` (ADR 0155
// decision 7.2, the export archive's slice), one check for the web and the apps, held by
// contracts/platform/export/v1-vectors.json. Core owns the envelope and the node shape; the `content` of a node is the kind's
// own (opaque here, the kind validates it - `KindDescriptor.validateContent`), and the one thing core reads inside it is the
// payload references - see {@link collectPayloads}.
//
// Two versions are read, one is written. Version 2 (the writer's) sits at `.veyq/manifest.json` beside the tree: every node
// has its archive `path`, every payload lies at or under its node's path, and `applications` names each application the tree
// carries with its content-schema version. Version 1 sat at `manifest.json` with every payload under `data/<id>/<name>`;
// archives written before the tree layout stay importable.
//
// The check trusts nothing in the file (decision 10): the shape and every field's length against the contract, ids unique,
// depth and node count bounded, every path made of safe names and none reaching into `.veyq/`, and a `version` newer than
// this build knows refused (unknown FIELDS are ignored, as the schema says). Each refusal is an {@link ExportError} whose
// code the import dialog explains. This file holds no rule: the facts, the check and the payload walk are the core's; what
// stays is the types, the error the web throws and the release major the build stamps.
import * as core from '@veyq/core-js/export'

const facts = core.manifestFacts()

export const MANIFEST_FORMAT = facts.format as 'veyq-subtree'
/** The version this build writes; it reads every version up to it. */
export const MANIFEST_VERSION: number = facts.version
/**
 * What `appVersion` names: the platform's one major (ADR 0085 decision 1), never the precise release. The built bundle is
 * served to anyone who asks, before any login, so a release compiled into it is a release on the pre-auth surface, which
 * ADR 0057 §4 keeps to the Server Owner (a precise release is a CVE list); the major is public already (`GET /api/setup`).
 * scripts/validate_release_identity.py holds the image build to it: no stage that compiles the client takes the stamp.
 */
export const MANIFEST_APP_VERSION = String(__VEYQ_MAJOR__)
/** Where version 2 keeps the manifest: beside the tree, in a directory no node may take. */
export const MANIFEST_PATH: string = facts.path
/** Where version 1 kept it. */
export const MANIFEST_PATH_V1: string = facts.pathV1
/** The largest manifest read into memory. A subtree of 100 000 nodes is a few tens of MB. */
export const MANIFEST_MAX_BYTES: number = facts.maxBytes
export const MAX_DEPTH: number = facts.maxDepth
export const MAX_NODES: number = facts.maxNodes
/** The contract's `maxLength` (and `maxItems`) of every field core reads; `importHardening.test.ts` pins each to the schema. */
export const LIMITS: Readonly<Record<'appVersion' | 'applications' | 'kind' | 'name' | 'path' | 'contentType' | 'mediaType', number>> = { ...facts.limits }

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

/** The core's refusal of a manifest as an {@link ExportError}; anything else as it was thrown. */
function asExportError(e: unknown): unknown {
  const r = e as { veyq?: string; code?: ExportErrorCode; message?: string; detail?: string } | null
  return r?.veyq === 'export' ? new ExportError(r.code!, r.message ?? r.code!, r.detail) : e
}

/** Whether `path` is a version 1 payload path an importer may open: `data/<id>/<name>`, no `.`/`..` name, no control characters. */
export function isSafePayloadPathV1(path: unknown): path is string {
  return typeof path === 'string' && core.manifestPathV1IsSafe(path)
}

/** The application a content type belongs to: the part before the dot. */
export const applicationOf = (contentType: string): string => core.manifestApplicationOf(contentType)

/**
 * Validate a parsed manifest of any version this build reads. Throws {@link ExportError}: `not_an_export` (not our format),
 * `newer_version`, `invalid_manifest`, `too_large`, `unsafe_path`.
 */
export function parseManifest(json: unknown): Manifest {
  try {
    core.manifestCheck(json as core.JsonShape)
  } catch (e) {
    throw asExportError(e)
  }
  return json as Manifest
}

/** Every payload reference inside a value: an object with a string `path` and a numeric `size` — the value's own objects, in
 *  walk order. This is how core finds the bytes of a kind's content without knowing the kind - and why every one is judged
 *  by where it points: a reference to anywhere but its node's place is refused, never skipped. */
export function collectPayloadsOf(value: unknown, out: ManifestPayload[] = []): ManifestPayload[] {
  for (const p of core.manifestPayloads(value as core.JsonShape)) out.push(p as unknown as ManifestPayload)
  return out
}

/** All payloads of a node (its cover and everything in its content), not its children's. */
export function collectPayloads(node: ManifestNode): ManifestPayload[] {
  const out: ManifestPayload[] = []
  if (node.cover) out.push(node.cover)
  if (node.content) collectPayloadsOf(node.content, out)
  return out
}
