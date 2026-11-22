// Safe names inside a download's ZIP (ADR 0082 decision 3) — the platform's Kotlin `export/ArchiveName.kt`, drawn through
// `@veyq/core-js/files` (ADR 0155 decision 7.2, the file facts' slice), one rule for the web and the apps, held by
// contracts/platform/files/v1-vectors.json: the writer's for every directory and file, the readers' to judge a path
// (`isArchiveName`, `isArchivePath`), and a folder upload's (inbox #424). NFC; `/`, `\`, control characters and `<>:"|?*`
// become `_`; no leading or trailing white space, no trailing dot; a name of nothing becomes `_`; Windows' device names gain
// a leading `_`; at most 200 bytes of UTF-8, the extension kept; two names of one directory that fold to one ignoring case
// are numbered, `img (2).JPG`. This file holds no rule.
import * as core from '@veyq/core-js/files'

/** The top-level directory that holds the manifest; no node may take its name at the archive's root. */
export const MANIFEST_DIR: string = core.archiveManifestDir()

/** How two names of one directory are compared: NFC, ignoring case. */
export function foldArchiveName(name: string): string {
  return core.foldArchiveName(name)
}

/**
 * The safe name of `name` in a directory whose names so far are `taken` (folded, {@link foldArchiveName}); the result is
 * added to `taken`. A directory's names are taken in the order the writer meets them, so the same tree always yields the
 * same paths.
 */
export function archiveName(name: string, taken: Set<string>): string {
  return core.archiveName(name, taken)
}

/** Whether `segment` is one path segment the rules could have produced: what a reader accepts and nothing else. */
export function isArchiveName(segment: string): boolean {
  return core.isArchiveName(segment)
}

/** Whether `path` is a relative archive path of safe segments that does not reach into the manifest's directory. */
export function isArchivePath(path: string): boolean {
  return core.isArchivePath(path)
}
