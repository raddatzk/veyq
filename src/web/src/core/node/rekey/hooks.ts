// The application's half of a node's re-key (ADR 0109 point 2): what a kind registers so that every field
// it stores sealed under a node's key moves to the node's new key. Core re-seals the node's own entry, its children's
// entries, its meta (and core's cover inside it), its attachment wraps, its links and its drop key; a kind adds what only
// it knows — its fields inside the node's meta (Memories' banner wraps) and its own rows (Memories' media, buckets),
// staged under the commit's re-key id through its own operation. Required on every kind (`KindDescriptor.rekeyContent`),
// so a kind cannot be registered without saying what its re-key does: one with nothing of its own says so explicitly.
import type { NodeKind } from '../kind'

/** One node's re-key, as core hands it to the node's kind. Every key here lives only for the call. */
export interface RekeyNodeContext {
  scopeId: string
  /** The commit's re-key id: an application stages its own rows under it before core commits. */
  rekeyId: string
  node: { id: string; kind: NodeKind; epoch: number; trashed: boolean }
  /** The node's key from before the rotation, and the one this re-key minted. */
  oldKey: Uint8Array
  newKey: Uint8Array
  /** The node's sealed meta, already opened under [oldKey] (core's cover re-wrapped in it), or null when it has none. The
   *  kind re-wraps its own references in it IN PLACE; core then seals it under [newKey]. */
  meta: Record<string, unknown> | null
  /** The old and new key of the node a link is rooted on, by share id — for a copy a link's guest sealed under its root
   *  (Memories' contributor bucket names). Null for a link this re-key has not met (its root is not re-keyed yet, or not
   *  above this node). */
  shareRootKeys(shareId: string): { oldKey: Uint8Array; newKey: Uint8Array } | null
  signal?: AbortSignal
}

/** Re-key a node's kind-owned fields (see [RekeyNodeContext]). Fails closed: a field the kind cannot re-seal throws, and
 *  the node is not committed. */
export type RekeyContentHook = (ctx: RekeyNodeContext) => Promise<void>

/** The hook of a kind that stores nothing under a node's key beyond what core re-keys (core's FOLDER, Depot's file and
 *  drop box: a file's content wrap is a core attachment). */
export const nothingOfItsOwnToRekey: RekeyContentHook = async () => {}

/**
 * A rotation's scope-level re-seal (ADR 0115 decision 2), as core hands it to an application: the scope key is replaced,
 * and every row the application stores sealed under it (Memories' item marks) must be re-sealed under the new key and
 * staged under [rekeyId] before core commits the rotation — the backend's `ScopeRekeyParticipant` on the web. Core never
 * names an application's rows (ADR 0079 decision 3). Every key here lives only for the call.
 */
export interface ScopeRekeyContext {
  scopeId: string
  /** The rotation's re-key id: what the application stages under and core names in the commit. */
  rekeyId: string
  /** The scope's key before the rotation (`fromEpoch`, the epoch every listed row is under) and the one it minted. */
  oldKey: { readonly epoch: number; readonly key: Uint8Array }
  newKey: { readonly epoch: number; readonly key: Uint8Array }
  /** SHA-256 (base64) of a served ciphertext's TEXT — what each staged value names as `replacedDigest`. */
  digestOf(ciphertext: string): string
}

/** Re-seal and stage an application's rows under the scope key (see [ScopeRekeyContext]). Fails closed: a row it cannot
 *  stage throws, and the rotation is not committed (the server refuses it `rekey_parts_missing` anyway). */
export type ScopeRekeyParticipant = (ctx: ScopeRekeyContext) => Promise<void>
