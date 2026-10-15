// The node kind the Depot application brings (ADR 0079; docs/apps/files.md). The contract's `NodeKind` is an open token,
// so this is the one definition of the value in the web: `register.ts` registers it, and every Depot file that tests a
// node's kind compares against it. Core never imports it (`validate_web_core_imports_no_applications.py`), nor does
// another application; the backend's counterpart is `DepotNodeKinds`.

/** A file: a node holding one sealed blob, at the root or in a folder, with no children. Not a share root. */
export const FILE_KIND = 'FILE'

/** The role of a file's blob, and the attachment slot it fills (one token space, cr-files-application P3): its `K_blob`
 *  wrap is sealed bound to it (ADR 0072). The backend's counterpart is `DepotBlobRoles.FILE_CONTENT`. */
export const FILE_CONTENT_ROLE = 'FILE_CONTENT'

/** A drop box (cr-files-application F3, ADR 0080 decision 11): a node shared WRITE_ONLY only, whose children are its
 *  guests' identity folders (core FOLDERs, one per guest identity). The backend's counterpart is `DepotNodeKinds.DROPBOX`. */
export const DROPBOX_KIND = 'DEPOT_DROPBOX'
