// "Upload folder…" (inbox #424, ADR 0082 decision 7): a picked or dropped directory that is no veyq download is
// uploaded AS ITS TREE - a `FOLDER` per directory, a `FILE` per file - under the folder on screen. Depot's level action, because
// the tree is folders and files (core never names `FILE`, ADR 0079); the folders are core's kind, created through core's own
// create (a member's `createContentNode`, a guest's `createShareNodeAsGuest`) behind the session's upload door
// (`source.ts`'s `FilesTreeUpload`).
//
// **Names** — Depot's Kotlin `FolderUpload` plan, drawn through `@veyq/core-js/depot` (ADR 0155 decision 7.3, § Depot's move;
// one rule with the apps, held by `contracts/depot/v1-vectors.json`'s `folderPlan`). Every name goes through the platform's
// `archiveName` - the one rule that makes a name safe on every file system - and its clash suffix (`Name (2)`, before the
// extension), per directory. On top of it, a name whose SLUG a sibling already holds is numbered further, each sibling folded
// by its own kind into its token input (`registry.tokenInputFor`: a folder `a.b` and a folder `a b` meet, a file `Foto.JPG`
// and `foto.jpg` meet, a Memory `Trip` meets neither), so the server's uniqueness never refuses a name the plan made. The top
// folder is numbered among the level's existing siblings the same way (as the import's root is), and a sibling only the
// server knows (a race, one this session cannot read) moves it to the next suffix. `planFolderUpload` holds no rule: it hands
// the paths to the core and puts each planned name on its `File`.
//
// **Order.** The plan is pure (`planFolderUpload`); `createFolders` creates the folders top-down, then hands back one upload job
// per file for core's queue - so the folders exist before any file is sent, and a file that fails is the queue's per-file
// failure while the others go on. A folder that cannot be created is named, and what lies below it is not sent; one that the
// top-level create refuses ends the upload before anything else is written.
//
// **Taking back.** A guest cannot trash, so once the queue settled the batch, the folders this upload created that hold
// nothing are taken back (`takeBackEmptyFolders`, a guest's door only: core's `abandonShareNode`, which erases an empty
// `FOLDER` at once - ADR 0080 decision 6): a link that refused every file (it was full) keeps none of them. Deepest first,
// so a parent is empty by the time it is asked; a folder below which a file landed, or one a retry could still land, stays,
// and the server keeps any folder somebody else filled or renamed meanwhile (`not_a_placeholder`).
//
// The quota preflight (the tenant's yes or no for the tree's total bytes) is the caller's, before the first write.
import { markRaw, toRaw } from 'vue'
import * as core from '@veyq/core-js/depot'
import { registry } from '../../core/applications/registry'
import type { DirectoryInput } from '../../core/export/importSource'
import type { NodeKind } from '../../core/node/kind'
import { isRetryable, type UploadItem, type UploadJob } from '../../core/blob/queue'
import type { FilesTreeUpload } from './source'
import { FileUploadError, type FileJobExtra, type FileUploadResult } from './uploadFile'

/** One directory of the plan: its safe name, its files (each a `File` under its safe name) and its sub-directories. */
export type FolderPlan = { name: string; files: File[]; folders: FolderPlan[] }

/** What a folder upload will create: the tree, how many files and how many bytes (the preflight's question). `nextRootName`
 *  is the top folder's next free name, for a sibling only the server knew. */
export type FolderUploadPlan = { root: FolderPlan; files: number; bytes: number; nextRootName(): string }

type SlugFor = (kind: NodeKind, name: string) => string

/**
 * Plan the upload of `dir` under a level whose children are `siblings` (each `{ kind, name }`): the directory becomes the top
 * folder, numbered among them. Paths below it are split at `/`; an empty segment is dropped.
 */
export function planFolderUpload(
  dir: DirectoryInput, siblings: readonly { kind: NodeKind; name: string }[], slugFor: SlugFor = (k, n) => registry.tokenInputFor(k, n),
): FolderUploadPlan {
  const entries = dir.files.map(({ path, file }) => ({ path, size: file.size }))
  const plan = core.depotFolderPlan(dir.name, entries, siblings.map(({ kind, name }) => ({ kind, name })), slugFor)
  const names = markRaw(plan.rootNames)
  const build = (folder: core.PlannedFolderShape): FolderPlan => ({
    name: folder.name,
    files: folder.files.map(({ name, entry }) => {
      const file = dir.files[entry]!.file
      return name === file.name ? file : new File([file], name, { type: file.type, lastModified: file.lastModified })
    }),
    folders: folder.folders.map(build),
  })
  return { root: build(plan.root), files: plan.files, bytes: plan.bytes, nextRootName: () => core.depotFolderRootNext(names) }
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
  /** Every folder that was created, each before what lies below it, with the range `[from, to)` of `jobs` below it. */
  folders: { id: string; jobs: [number, number] }[]
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
  const folders: CreatedFolders['folders'] = []
  const walk = async (folder: FolderPlan, id: string, path: string): Promise<void> => {
    const created: CreatedFolders['folders'][number] = { id, jobs: [jobs.length, jobs.length] }
    folders.push(created)
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
    created.jobs[1] = jobs.length
  }
  await walk(plan.root, rootId, rootName)
  return { jobs, rootName, failed, folders }
}

/**
 * Take back, through `door`, the folders of `made` that hold nothing (see the header), once its batch settled: `items` are
 * the queue's items of `made.jobs`, matched by their `File` - one the tray no longer holds was removed and lands nothing. A
 * folder stays when a file below it landed or a retry could still land one. Best effort, one folder at a time, deepest
 * first: a refusal keeps that folder and the next is asked. Nothing without the door's `abandon` (a member trashes).
 */
export async function takeBackEmptyFolders(
  made: CreatedFolders, door: FilesTreeUpload, items: readonly UploadItem<FileUploadResult, FileJobExtra>[],
): Promise<void> {
  const abandon = door.abandon?.bind(door)
  if (!abandon) return
  // the queue's items are reactive: the File it hands back may be a proxy of the job's own
  const byFile = new Map(items.map((item) => [toRaw(item.file), item]))
  const holds = (job: UploadJob<FileUploadResult, FileJobExtra>): boolean => {
    const item = job.extra && byFile.get(job.extra.file)
    if (!item) return false
    return item.status === 'done' || item.status === 'queued' || item.status === 'preparing' || item.status === 'uploading' ||
      (item.status === 'failed' && isRetryable(item))
  }
  for (const folder of [...made.folders].reverse()) {
    if (made.jobs.slice(...folder.jobs).some(holds)) continue
    try {
      await abandon(folder.id)
    } catch {
      /* the server keeps it (somebody filled it, or it is gone): the next is still asked */
    }
  }
}
