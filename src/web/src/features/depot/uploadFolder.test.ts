// "Upload folder…" (inbox #424, ADR 0082 decision 7): the PLAN of a picked directory - its tree of folders and
// files, every name through core's `archiveName` and numbered where a sibling's slug (folded by its own kind) or a level's
// existing sibling holds it - the folders' CREATION through a session's tree door, top-down, before any file job exists, and
// the TAKE-BACK of the folders a settled upload left holding nothing.
// The registry is a fresh one with Depot registered, so each kind folds as it does in the app.
import { describe, it, expect } from 'vitest'
import { createRegistry } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import type { DirectoryInput } from '../../core/export/importSource'
import { registerDepot } from './register'
import { FILE_KIND } from './kinds'
import { createFolders, planFolderUpload, takeBackEmptyFolders, type FolderPlan } from './uploadFolder'
import { FileUploadError, type FileJobExtra, type FileUploadResult } from './uploadFile'
import type { UploadItem, UploadStatus } from '../../core/blob/queue'
import type { FilesTreeUpload } from './source'

const reg = createRegistry()
registerDepot(reg)
reg.claimRouteSlug('t-routed', ['T_ROUTED']) // an application with a route slug, as Memories or Expenses
// what the plan numbers by: the slug token's input, as the default `registry.tokenInputFor` does in the app
const slugFor = (kind: string, name: string) => reg.tokenInputFor(kind, name)

const file = (path: string, body = 'x', type = 'text/plain') => ({ path, file: new File([body], path.split('/').pop()!, { type, lastModified: 1234 }) })
const dir = (name: string, ...files: { path: string; file: File }[]): DirectoryInput => ({ kind: 'directory', name, files })
/** The plan as `name/` + its files and sub-folders, depth first. */
const shape = (p: FolderPlan, at = ''): string[] => [
  `${at}${p.name}/`,
  ...p.files.map((f) => `${at}${p.name}/${f.name}`),
  ...p.folders.flatMap((sub) => shape(sub, `${at}${p.name}/`)),
]

describe('planFolderUpload', () => {
  it('maps the directory to a tree: a folder per directory, a file per file, the bytes and the count', () => {
    const plan = planFolderUpload(dir('Trip', file('a.txt', 'hello'), file('Day 1/b.jpg', 'xy'), file('Day 1/Night/c.jpg', 'z')), [], slugFor)
    expect(shape(plan.root)).toEqual(['Trip/', 'Trip/a.txt', 'Trip/Day 1/', 'Trip/Day 1/b.jpg', 'Trip/Day 1/Night/', 'Trip/Day 1/Night/c.jpg'])
    expect(plan.files).toBe(3)
    expect(plan.bytes).toBe(8)
  })

  it('makes every name safe by archiveName\'s rules (hostile cases), and numbers a clash ignoring case before the extension', () => {
    const plan = planFolderUpload(dir('CON', file('a:b.txt'), file('IMG.jpg'), file('img.JPG'), file('name. '), file('../x.txt'), file('x/../y.txt')), [], slugFor)
    expect(shape(plan.root)).toEqual([
      // a `..` directory is a folder named `_`: nothing climbs out of the tree
      '_CON/', '_CON/a_b.txt', '_CON/IMG.jpg', '_CON/img (2).JPG', '_CON/name',
      '_CON/_/', '_CON/_/x.txt', '_CON/x/', '_CON/x/_/', '_CON/x/_/y.txt',
    ])
  })

  it('numbers a name whose SLUG a sibling holds, each folded by its own kind (a folder a.b meets a folder a b; a file Foto.JPG meets foto.jpg)', () => {
    expect(slugFor(FOLDER_KIND, 'a.b')).toBe(slugFor(FOLDER_KIND, 'a b')) // the premise: the two fold alike
    const plan = planFolderUpload(dir('T', file('a.b/1.txt'), file('a b/2.txt'), file('x/Foto.JPG'), file('x/foto_jpg')), [], slugFor)
    expect(plan.root.folders.map((f) => f.name)).toEqual(['a.b', 'a b (2)', 'x'])
    expect(plan.root.folders[2].files.map((f) => f.name)).toEqual(['Foto.JPG', 'foto_jpg'])
  })

  it('numbers the top folder among the level\'s siblings (as the import\'s root), whatever their kind; nextRootName goes on', () => {
    const siblings = [{ kind: FOLDER_KIND, name: 'Trip' }, { kind: FILE_KIND, name: 'trip-2' }]
    expect(slugFor(FOLDER_KIND, 'Trip (2)')).toBe(slugFor(FILE_KIND, 'trip-2')) // the premise: a file's slug meets the folder's
    const plan = planFolderUpload(dir('Trip', file('a.txt')), siblings, slugFor)
    expect(plan.root.name).toBe('Trip (3)')
    expect(plan.nextRootName()).toBe('Trip (4)')
  })

  // ADR 0080 decision 16, amended 2026-11-01: a name is unique within its route group, so a sibling of an application with
  // a route slug (a Memory, a Pot) does not number the top folder.
  it('leaves the top folder\'s name free beside a sibling of another route group of that name', () => {
    const plan = planFolderUpload(dir('Trip', file('a.txt')), [{ kind: 'T_ROUTED', name: 'Trip' }], slugFor)
    expect(plan.root.name).toBe('Trip')
  })

  it('a renamed file keeps its bytes, type and modified time; an unchanged one is the picked File itself', async () => {
    const picked = file('a:b.txt', 'body', 'text/x-test')
    const same = file('ok.txt')
    const plan = planFolderUpload(dir('T', picked, same), [], slugFor)
    const [renamed, kept] = plan.root.files
    expect(renamed.name).toBe('a_b.txt')
    expect(await renamed.text()).toBe('body')
    expect(renamed.type).toBe('text/x-test')
    expect(renamed.lastModified).toBe(1234)
    expect(kept).toBe(same.file)
  })

  it('reads the paths in NFC: a decomposed and a composed spelling are one directory', () => {
    const plan = planFolderUpload(dir('T', file('Café/a.txt'), file('Café/b.txt')), [], slugFor)
    expect(shape(plan.root)).toEqual(['T/', 'T/Café/', 'T/Café/a.txt', 'T/Café/b.txt'])
  })
})

/** A tree door that records what it was asked, refusing the names in `refuse` with the given error. */
function door(refuse: Record<string, Error> = {}) {
  const made: { parent: string | null; name: string; id: string }[] = []
  const jobs: { parent: string | null; files: string[] }[] = []
  let n = 0
  const d: FilesTreeUpload = {
    async folder(parent, name) {
      const e = refuse[name]
      if (e) throw e
      const id = `f${++n}`
      made.push({ parent, name, id })
      return id
    },
    jobs(parent, files) {
      jobs.push({ parent, files: files.map((f) => f.name) })
      return files.map((f) => ({ name: f.name, sizeBytes: f.size, run: async () => ({ nodeId: 'x' }), extra: { file: f } }))
    },
  }
  return { d, made, jobs }
}

describe('createFolders', () => {
  const tree = () => planFolderUpload(dir('Trip', file('a.txt'), file('Day 1/b.jpg'), file('Day 2/c.jpg'), file('Day 2/Night/d.jpg')), [], slugFor)

  it('creates the folders top-down under the level, then hands back one job per file under its folder', async () => {
    const { d, made, jobs } = door()
    const out = await createFolders(tree(), d)
    expect(made.map((m) => [m.parent, m.name])).toEqual([[null, 'Trip'], ['f1', 'Day 1'], ['f1', 'Day 2'], ['f3', 'Night']])
    expect(jobs).toEqual([
      { parent: 'f1', files: ['a.txt'] }, { parent: 'f2', files: ['b.jpg'] }, { parent: 'f3', files: ['c.jpg'] }, { parent: 'f4', files: ['d.jpg'] },
    ])
    expect(out.jobs.map((j) => j.name)).toEqual(['a.txt', 'b.jpg', 'c.jpg', 'd.jpg'])
    expect(out).toMatchObject({ rootName: 'Trip', failed: [] })
    // each created folder with the jobs below it, a parent before its children: what a take-back walks in reverse
    expect(out.folders).toEqual([{ id: 'f1', jobs: [0, 4] }, { id: 'f2', jobs: [1, 2] }, { id: 'f3', jobs: [2, 4] }, { id: 'f4', jobs: [3, 4] }])
  })

  it('the top folder takes the next suffix on a slug_taken the plan could not see (a sibling only the server knows)', async () => {
    const { d, made } = door({ Trip: new Error(FileUploadError.slugTaken), 'Trip (2)': new Error(FileUploadError.slugTaken) })
    const out = await createFolders(tree(), d)
    expect(out.rootName).toBe('Trip (3)')
    expect(made[0]).toMatchObject({ parent: null, name: 'Trip (3)' })
  })

  it('any other refusal of the top folder is thrown: nothing was created, no job exists', async () => {
    const { d, made, jobs } = door({ Trip: new Error('network') })
    await expect(createFolders(tree(), d)).rejects.toThrow('network')
    expect(made).toEqual([])
    expect(jobs).toEqual([])
  })

  it('a folder below it that fails is named and its subtree skipped; the others go on', async () => {
    const { d, made } = door({ 'Day 2': new Error('illegal_structure') })
    const out = await createFolders(tree(), d)
    expect(made.map((m) => m.name)).toEqual(['Trip', 'Day 1'])
    expect(out.failed).toEqual(['Trip/Day 2'])
    expect(out.jobs.map((j) => j.name)).toEqual(['a.txt', 'b.jpg'])
    expect(out.folders.map((f) => f.id)).toEqual(['f1', 'f2'])
  })
})

describe('takeBackEmptyFolders', () => {
  const tree = () => planFolderUpload(dir('Trip', file('a.txt'), file('Day 1/b.jpg'), file('Day 2/c.jpg')), [], slugFor)
  const item = (file: File, status: UploadStatus, error?: string) =>
    ({ id: file.name, name: file.name, sizeBytes: 1, status, error, attempts: 1, progress: 0, controller: new AbortController(), file }) as UploadItem<FileUploadResult, FileJobExtra>

  it('asks nothing of a door without a take-back (a member trashes)', async () => {
    const { d } = door()
    const out = await createFolders(tree(), d)
    await expect(takeBackEmptyFolders(out, d, [])).resolves.toBeUndefined()
  })

  it('a folder whose every file was refused, cancelled or removed from the tray goes; one a file is in or may still reach stays', async () => {
    const { d } = door()
    const asked: string[] = []
    const out = await createFolders(tree(), d)
    const [a, b, c] = out.jobs.map((j) => j.extra!.file)
    // Trip f1 (a.txt), Day 1 f2 (b.jpg), Day 2 f3 (c.jpg)
    await takeBackEmptyFolders(out, { ...d, abandon: async (id) => { asked.push(id) } }, [item(a, 'failed', 'quota_exceeded'), item(b, 'done'), item(c, 'cancelled')])
    expect(asked, 'only Day 2: Trip holds Day 1, Day 1 holds b.jpg').toEqual(['f3'])
    asked.length = 0
    await takeBackEmptyFolders(out, { ...d, abandon: async (id) => { asked.push(id) } }, [item(a, 'failed', 'upload_failed'), item(c, 'failed', 'quota_exceeded')])
    expect(asked, 'a retry could still land a.txt in Trip; b.jpg left the tray').toEqual(['f3', 'f2'])
  })

  it('a refusal keeps that folder, and the next is still asked', async () => {
    const { d } = door()
    const asked: string[] = []
    const abandon = async (id: string) => { asked.push(id); if (id === 'f3') throw new Error('not_a_placeholder') }
    const out = await createFolders(tree(), d)
    await takeBackEmptyFolders(out, { ...d, abandon }, [])
    expect(asked).toEqual(['f3', 'f2', 'f1'])
  })
})
