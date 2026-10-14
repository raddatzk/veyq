// "Upload folder…" (inbox #424, cr-download-is-the-export Decision 6): a picked or dropped directory that is no veyq download is
// uploaded AS ITS TREE - a `FOLDER` per directory, a `FILE` per file - under the folder on screen. Files' level action, because
// the tree is folders and files (core never names `FILE`, ADR 0079); the folders are core's kind, created through core's own
// create (a member's `createContentNode`, a guest's `createShareNodeAsGuest`) behind the session's upload door
// (`source.ts`'s `FilesTreeUpload`).
//
// **Names.** Every name goes through core's `archiveName` - the one rule that makes a name safe on every file system - and its
// clash suffix (`Name (2)`, before the extension), per directory. On top of it, a name whose SLUG a sibling already holds is
// numbered further, each sibling folded by its own kind (`registry.slugFor`: a folder `a.b` and a folder `a b` meet, a file
// `Foto.JPG` and `foto.jpg` meet), so the server's uniqueness never refuses a name the plan made. The top folder is numbered
// among the level's existing siblings the same way (as the import's root is), and a sibling only the server knows (a race, one
// this session cannot read) moves it to the next suffix.
//
// **Order.** The plan is pure (`planFolderUpload`); `createFolders` creates the folders top-down, then hands back one upload job
// per file for core's queue - so the folders exist before any file is sent, and a file that fails is the queue's per-file
// failure while the others go on. A folder that cannot be created is named, and what lies below it is not sent; one that the
// top-level create refuses ends the upload before anything else is written.
//
// The quota preflight (the tenant's yes or no for the tree's total bytes) is the caller's, before the first write.
import { registry } from '../../core/applications/registry'
import { archiveName } from '../../core/export/archiveName'
import type { DirectoryInput } from '../../core/export/importSource'
import { FOLDER_KIND, type NodeKind } from '../../core/node/kind'
import type { UploadJob } from '../../core/blob/queue'
import { FILE_KIND } from './kinds'
import type { FilesTreeUpload } from './source'
import { FileUploadError, type FileJobExtra, type FileUploadResult } from './uploadFile'

/** One directory of the plan: its safe name, its files (each a `File` under its safe name) and its sub-directories. */
export type FolderPlan = { name: string; files: File[]; folders: FolderPlan[] }

/** What a folder upload will create: the tree, how many files and how many bytes (the preflight's question). `nextRootName`
 *  is the top folder's next free name, for a sibling only the server knew. */
export type FolderUploadPlan = { root: FolderPlan; files: number; bytes: number; nextRootName(): string }

type SlugFor = (kind: NodeKind, name: string) => string

/** The names one directory hands out: `archiveName`'s rules and suffix, and a slug no sibling holds yet. */
function namer(slugFor: SlugFor, slugs: Set<string> = new Set()) {
  const taken = new Set<string>()
  return (name: string, kind: NodeKind): string => {
    // each archiveName call takes its result, so the next one is the next suffix: this ends
    for (;;) {
      const candidate = archiveName(name, taken)
      const slug = slugFor(kind, candidate)
      if (!slugs.has(slug)) {
        slugs.add(slug)
        return candidate
      }
    }
  }
}

type Draft = { files: { name: string; file: File }[]; folders: Map<string, Draft> }

/**
 * Plan the upload of `dir` under a level whose children are `siblings` (each `{ kind, name }`): the directory becomes the top
 * folder, numbered among them. Paths below it are split at `/`; an empty segment is dropped.
 */
export function planFolderUpload(
  dir: DirectoryInput, siblings: readonly { kind: NodeKind; name: string }[], slugFor: SlugFor = (k, n) => registry.slugFor(k, n),
): FolderUploadPlan {
  const top: Draft = { files: [], folders: new Map() }
  let files = 0
  let bytes = 0
  for (const { path, file } of dir.files) {
    const segments = path.normalize('NFC').split('/').filter((s) => s !== '')
    const fileName = segments.pop()
    if (fileName === undefined) continue
    let at = top
    for (const segment of segments) {
      let next = at.folders.get(segment)
      if (!next) at.folders.set(segment, (next = { files: [], folders: new Map() }))
      at = next
    }
    at.files.push({ name: fileName, file })
    files++
    bytes += file.size
  }
  const build = (draft: Draft, name: string): FolderPlan => {
    const next = namer(slugFor)
    // the sub-directories first, then the files: the same tree always gets the same names
    const folders = [...draft.folders].map(([n, d]) => build(d, next(n, FOLDER_KIND)))
    const renamed = draft.files.map(({ name: n, file }) => {
      const safe = next(n, FILE_KIND)
      return safe === file.name ? file : new File([file], safe, { type: file.type, lastModified: file.lastModified })
    })
    return { name, files: renamed, folders }
  }
  const rootName = namer(slugFor, new Set(siblings.map((s) => slugFor(s.kind, s.name))))
  return { root: build(top, rootName(dir.name, FOLDER_KIND)), files, bytes, nextRootName: () => rootName(dir.name, FOLDER_KIND) }
}

/** How often the top folder moves to the next suffix on the server's `slug_taken` before the upload gives up. */
const ROOT_TRIES = 20

export type CreatedFolders = {
  /** One upload job per file of every folder that was created, in tree order. */
  jobs: UploadJob<FileUploadResult, FileJobExtra>[]
  /** The top folder's name as created. */
  rootName: string
  /** The paths (below the level, `/`-joined) of the folders that could not be created; nothing below them was sent. */
  failed: string[]
}

/**
 * Create the plan's folders through `door`, top-down, and return the jobs of its files. The top folder takes the next suffix
 * on a `slug_taken` the plan could not foresee; any other refusal of it is thrown (nothing was written). A refusal of a folder
 * below it is reported in `failed` and its subtree skipped; the others go on.
 */
export async function createFolders(plan: FolderUploadPlan, door: FilesTreeUpload): Promise<CreatedFolders> {
  let rootName = plan.root.name
  let rootId: string | null = null
  for (let i = 0; rootId === null; i++) {
    try {
      rootId = await door.folder(null, rootName)
    } catch (e) {
      if (!(e instanceof Error && e.message === FileUploadError.slugTaken) || i + 1 >= ROOT_TRIES) throw e
      rootName = plan.nextRootName()
    }
  }
  const jobs: UploadJob<FileUploadResult, FileJobExtra>[] = []
  const failed: string[] = []
  const walk = async (folder: FolderPlan, id: string, path: string): Promise<void> => {
    jobs.push(...door.jobs(id, folder.files))
    for (const sub of folder.folders) {
      const subPath = `${path}/${sub.name}`
      let subId: string
      try {
        subId = await door.folder(id, sub.name)
      } catch {
        failed.push(subPath)
        continue
      }
      await walk(sub, subId, subPath)
    }
  }
  await walk(plan.root, rootId, rootName)
  return { jobs, rootName, failed }
}
