// The Files application's registration with the platform registry (ADR 0079). Called once, from the composition root
// (`web/src/applications.ts`), which is the only core file allowed to import an application. The registry is a
// parameter, not an import of the singleton, so a test can register into a fresh one.
//
// A skeleton today (cr-files-application F0): the `FILE` kind only. No `detail` yet — an opened file renders the host's
// explicit unsupported state (`home.unsupportedKind`), never an empty region — and no `section`: core's folder level
// view lists a FILE child in its fallback section (icon, name, the unsupported state) until F1 brings Files' own
// section (the file list, the upload and the download).
import { FILE_KIND } from './kinds'
import type { Registry } from '../../core/applications/registry'
import { fileNameSlug } from '../../shared/naming'

export function registerFiles(registry: Registry): void {
  // A file is no share root (the backend declares no `ShareRoots` for it): it is shared through its folder. Its name
  // folds by `fileNameSlug` (inbox #350: `Foto.JPG` and `foto.jpg` collide, `Day1.md` and `Day1 md` do not) — frozen
  // into every stored slug token from the first `FILE` on, and pinned by `name_folding` in `contracts/crypto/vectors/v1.json`.
  registry.registerKind({ kind: FILE_KIND, icon: 'pi pi-file', shareable: false, slug: fileNameSlug })
}
