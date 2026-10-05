// The node kinds the Memories application brings (ADR 0079). The contract's `NodeKind` is an open token, so these are
// the one definition of the two values in the web: `register.ts` registers them, and every Memories file that tests a
// node's kind compares against them. Core never imports them (`validate_web_core_imports_no_applications.py`); the
// backend's counterpart is `MemoriesNodeKinds`.

/** A Memory: top level or in a folder, shareable, opens to its gallery. */
export const MEMORY_KIND = 'MEMORY'

/** A chapter: lives in a Memory, opens to the same gallery, not shareable on its own. */
export const CHAPTER_KIND = 'CHAPTER'
