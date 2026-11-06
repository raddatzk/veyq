// A node's kind (ADR 0079): on the wire an open, bounded token — `_common` `NodeKind`, `^[A-Z][A-Z0-9_]{0,15}$` — so
// the generated client types it as a plain `string` and lists no kinds. Core names its own kinds, `FOLDER` and `SHARE_BOOKMARK` (ADR 0120, registered by the composition root);
// every other kind belongs to an application, which registers it through its registrar (`app.kind`), and the
// registry answers `{ supported: false }` for any kind nobody registered — that fail-closed answer and its tests
// carry what a closed enum's exhaustiveness used to (cr-platform-contract-split slice 4).

/** A node kind token. A string: any kind may arrive from the server, registered here or not. */
export type NodeKind = string

/** The platform's own kind: a folder organizes, carries a content key like every node (ADR 0080), browses and never opens. */
export const FOLDER_KIND = 'FOLDER'

/** The platform's bookmark of a received public link (ADR 0120): a node whose sealed meta holds the link and its password,
 *  opened where the share lives — browses nothing, holds nothing, roots no link. */
export const SHARE_BOOKMARK_KIND = 'SHARE_BOOKMARK'
