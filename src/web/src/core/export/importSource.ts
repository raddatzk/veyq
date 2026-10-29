// What an import reads from (ADR 0082 decision 7): a download as the ZIP it was saved as, or the same download UNPACKED - a
// directory a member dropped (or picked) that holds `.veyq/manifest.json` at its root. Both are recognised by their CONTENT,
// never by a name, and both are read the same way: the manifest's paths name the files, nothing else is opened, and each
// payload's SHA-256 is verified while it streams (the ZIP adds its own CRC-32). A directory's files are keyed by their path
// below the directory, in Unicode NFC (a file system may hand out the decomposed spelling of a name the archive wrote
// composed).
import { ZipError, openZip, type ZipArchive } from './zip'
import { MANIFEST_DIR } from './archiveName'

/** A file of an unpacked download: its path below the directory (`Trip 2025/Lisbon/IMG_1.jpg`), and the file. */
export type DirectoryFile = { path: string; file: File }

/** What an import was handed: a ZIP file, or the files of a directory (paths below it). */
export type ImportInput = { kind: 'zip'; file: File } | { kind: 'directory'; name: string; files: DirectoryFile[] }

/** A picked or dropped directory: its name and its files, by their paths below it. What "Upload folder…" uploads as its tree
 *  (Depot's folder upload, inbox #424) where it is no download, and what the import reads where it is one. */
export type DirectoryInput = Extract<ImportInput, { kind: 'directory' }>

/** Whether a picked directory is a download unpacked: `.veyq/manifest.json` at its root (by its path, in NFC). Nothing is read. */
export function directoryHoldsManifest(dir: DirectoryInput): boolean {
  const at = `${MANIFEST_DIR}/manifest.json`
  return dir.files.some((f) => f.path.normalize('NFC') === at)
}

/** An import the level offers its host: the input, and - for a ZIP that came from an upload - the way back to the ordinary
 *  upload ("Upload as a file", decision 7). */
export type ImportOffer = { input: ImportInput; uploadAsFile?: () => void }

/** The files an import reads, by archive path. */
export interface ImportSource {
  readonly kind: 'zip' | 'directory'
  has(path: string): boolean
  /** The file's size, or undefined when there is none. */
  size(path: string): number | undefined
  /** The file's bytes, a chunk at a time; a ZIP entry's CRC-32 is checked at the end (throws `ZipError` `crc_mismatch`). */
  stream(path: string): AsyncGenerator<Uint8Array>
  /** The file as a lazy, seekable `Blob` (nothing read). */
  blob(path: string, type: string): Blob
  /** The CRC-32 a ZIP entry's directory records, which a pass over {@link blob}'s bytes must match; undefined for a
   *  directory's file, which carries none. */
  crc32(path: string): number | undefined
}

export function zipSource(zip: ZipArchive): ImportSource {
  return {
    kind: 'zip',
    has: (path) => zip.has(path),
    size: (path) => zip.get(path)?.size,
    stream: (path) => zip.stream(path),
    blob: (path, type) => zip.blob(path, type),
    crc32: (path) => zip.get(path)?.crc32,
  }
}

const CHUNK = 1024 * 1024

export function directorySource(files: readonly DirectoryFile[]): ImportSource {
  const byPath = new Map(files.map((f) => [f.path.normalize('NFC'), f.file]))
  const get = (path: string): File => {
    const f = byPath.get(path)
    if (!f) throw new ZipError('missing', `no file ${path}`)
    return f
  }
  return {
    kind: 'directory',
    has: (path) => byPath.has(path),
    size: (path) => byPath.get(path)?.size,
    async *stream(path) {
      const f = get(path)
      for (let at = 0; at < f.size; at += CHUNK) yield new Uint8Array(await f.slice(at, Math.min(at + CHUNK, f.size)).arrayBuffer())
    },
    blob: (path, type) => get(path).slice(0, undefined, type),
    crc32: () => undefined,
  }
}

/** The files of a picked directory (`<input webkitdirectory>`): each `webkitRelativePath` is `<directory>/<path>`. */
export function pickedDirectory(files: readonly File[]): DirectoryInput | null {
  if (files.length === 0) return null
  const name = files[0].webkitRelativePath.split('/')[0]
  return {
    kind: 'directory',
    name,
    files: files.map((file) => ({ path: file.webkitRelativePath.slice(file.webkitRelativePath.indexOf('/') + 1), file })),
  }
}

// --- a dropped directory -------------------------------------------------------------------------------------------------
// A drop hands `FileSystemEntry`s (`DataTransferItem.webkitGetAsEntry`), read with their callback API; only the minimal shape
// is typed here, so a test can hand in its own entries.

export interface DroppedEntry {
  readonly isFile: boolean
  readonly isDirectory: boolean
  readonly name: string
}
export interface DroppedFile extends DroppedEntry {
  file(success: (f: File) => void, failure?: (e: unknown) => void): void
}
export interface DroppedDirectory extends DroppedEntry {
  createReader(): { readEntries(success: (entries: DroppedEntry[]) => void, failure?: (e: unknown) => void): void }
  getFile(path: string, options: object | undefined, success: (e: DroppedFile) => void, failure?: (e: unknown) => void): void
}

const fileOf = (e: DroppedFile) => new Promise<File>((resolve, reject) => e.file(resolve, reject))

/** Whether a dropped directory holds `.veyq/manifest.json` at its root: one lookup, nothing else is read. */
export function holdsManifest(dir: DroppedDirectory): Promise<boolean> {
  return new Promise((resolve) => dir.getFile(`${MANIFEST_DIR}/manifest.json`, {}, (e) => resolve(e.isFile), () => resolve(false)))
}

/** Every file below a dropped directory, with its path below it. A reader hands its entries out in batches, so it is asked
 *  until it hands out none. */
export async function readDroppedDirectory(dir: DroppedDirectory): Promise<DirectoryInput> {
  const files: DirectoryFile[] = []
  const walk = async (d: DroppedDirectory, prefix: string): Promise<void> => {
    const reader = d.createReader()
    for (;;) {
      const batch = await new Promise<DroppedEntry[]>((resolve, reject) => reader.readEntries(resolve, reject))
      if (batch.length === 0) return
      for (const e of batch) {
        if (e.isDirectory) await walk(e as DroppedDirectory, `${prefix}${e.name}/`)
        else if (e.isFile) files.push({ path: `${prefix}${e.name}`, file: await fileOf(e as DroppedFile) })
      }
    }
  }
  await walk(dir, '')
  return { kind: 'directory', name: dir.name, files }
}

/** Open the source of an input: a ZIP's directory is read and checked here (`openZip`). */
export async function sourceOf(input: ImportInput): Promise<ImportSource> {
  return input.kind === 'zip' ? zipSource(await openZip(input.file)) : directorySource(input.files)
}
