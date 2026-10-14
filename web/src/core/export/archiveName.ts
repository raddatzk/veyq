// Safe names inside a download's ZIP (ADR 0082 decision 3): the ONE function that turns a node's or a file's name into a
// path segment every file system accepts, used by the writer for every directory and file, by the readers to judge a path
// (`isArchiveName`), and by a later folder upload (inbox #424). The rules, in order:
//
//   - Unicode NFC (one spelling per name, whichever OS wrote it);
//   - no `/` or `\`, no control character, none of `<>:"|?*` (Windows): each becomes `_`;
//   - no leading or trailing white space, no trailing dot (Windows drops it, so `name.` and `name` would meet);
//   - `.` and `..` (and anything that is only dots) become `_`, and so does an empty name;
//   - none of Windows' reserved device names (`CON`, `NUL`, `COM1`, `LPT1`, ...), with or without an extension: `_CON.txt`;
//   - at most ARCHIVE_NAME_MAX_BYTES of UTF-8, cut on a character boundary, the extension kept;
//   - two names of one directory that fold to the same name ignoring case (`IMG.jpg`, `img.JPG`) are numbered: the second is
//     `img (2).JPG`, the suffix before the extension.
//
// Pure, so the hostile cases are a table test (`archiveName.test.ts`).

/** The longest name before a clash suffix, in UTF-8 bytes: 255 (the common per-name limit) less room for ` (99999)`. */
export const ARCHIVE_NAME_MAX_BYTES = 200
/** The longest segment a reader accepts: the per-name limit of APFS and ext4. */
export const ARCHIVE_SEGMENT_MAX_BYTES = 255
/** The top-level directory that holds the manifest; no node may take its name at the archive's root. */
export const MANIFEST_DIR = '.veyq'

const FORBIDDEN = /[\u0000-\u001f\u007f/\\<>:"|?*]/g
const RESERVED = /^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])$/i
const MAX_EXTENSION = 16
const encoder = new TextEncoder()

const utf8Length = (s: string): number => encoder.encode(s).length

/** The name as the rules make it, before the length bound and the clash suffix. */
function clean(name: string): string {
  let n = name.normalize('NFC').replace(FORBIDDEN, '_').trim().replace(/[. ]+$/u, '')
  if (n === '') return '_'
  // what Windows compares against its device names: the part before the first dot, trailing spaces dropped
  if (RESERVED.test(n.split('.')[0].trimEnd())) n = `_${n}`
  return n
}

/** `name` split before its extension: the last dot that is not the first character, an extension of at most 16 characters. */
function splitExtension(name: string): { stem: string; ext: string } {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || name.length - dot > MAX_EXTENSION) return { stem: name, ext: '' }
  return { stem: name.slice(0, dot), ext: name.slice(dot) }
}

/** `name` cut to `max` UTF-8 bytes on a code-point boundary, its extension kept; no trailing dot or space after the cut. */
function bound(name: string, max: number): string {
  if (utf8Length(name) <= max) return name
  const { stem, ext } = splitExtension(name)
  const room = max - utf8Length(ext)
  let out = ''
  for (const ch of stem) {
    if (utf8Length(out + ch) > room) break
    out += ch
  }
  out = out.replace(/[. ]+$/u, '')
  return (out === '' ? '_' : out) + ext
}

/** How two names of one directory are compared: NFC, ignoring case. */
export function foldArchiveName(name: string): string {
  return name.normalize('NFC').toLowerCase()
}

/**
 * The safe name of `name` in a directory whose names so far are `taken` (folded, {@link foldArchiveName}); the result is
 * added to `taken`. A directory's names are taken in the order the writer meets them, so the same tree always yields the
 * same paths.
 */
export function archiveName(name: string, taken: Set<string>): string {
  const base = bound(clean(name), ARCHIVE_NAME_MAX_BYTES)
  let candidate = base
  if (taken.has(foldArchiveName(candidate))) {
    const { stem, ext } = splitExtension(base)
    for (let i = 2; taken.has(foldArchiveName(candidate)); i++) candidate = `${stem} (${i})${ext}`
  }
  taken.add(foldArchiveName(candidate))
  return candidate
}

/** Whether `segment` is one path segment the rules could have produced: what a reader accepts and nothing else. */
export function isArchiveName(segment: string): boolean {
  return segment !== '' && utf8Length(segment) <= ARCHIVE_SEGMENT_MAX_BYTES && clean(segment) === segment
}

/** Whether `path` is a relative archive path of safe segments that does not reach into the manifest's directory. */
export function isArchivePath(path: string): boolean {
  const segments = path.split('/')
  return foldArchiveName(segments[0]) !== MANIFEST_DIR && segments.every(isArchiveName)
}
