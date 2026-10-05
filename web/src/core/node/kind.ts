// A node's kind (ADR 0079): on the wire an open, bounded token — `_common` `NodeKind`, `^[A-Z][A-Z0-9_]{0,15}$` — so
// the generated client types it as a plain `string` and lists no kinds. Core names one kind, its own `FOLDER`;
// every other kind belongs to an application, which registers it with the registry (`registerKind`), and the
// registry answers `{ supported: false }` for any kind nobody registered — that fail-closed answer and its tests
// carry what a closed enum's exhaustiveness used to (cr-platform-contract-split slice 4).

/** A node kind token. A string: any kind may arrive from the server, registered here or not. */
export type NodeKind = string

/** The platform's own kind: a folder organizes only, carries no content key, browses and never opens. */
export const FOLDER_KIND = 'FOLDER'
