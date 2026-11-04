// Who is offered a folder level's upload-only link (owner 2026-11-04; the seam is `app.folderUploadShare`, see
// `core/applications/registry.ts`): core's folder share dialog offers "Add files only" beside READ and READ_WRITE, and the
// space's top level - a scope root, which is no link's root - offers it alone. The link names a NEW node of the registering
// application's kind under the level (ADR 0080 decision 11: no `WRITE_ONLY` link roots at a folder), so the answer is the
// application's create right at that level, and FAIL CLOSED everywhere before it: nobody registered it, a share guest (whose
// page has no share door either), a level that is no folder or the scope root, the kind's application switched off
// (`applicationWritable`, uc.platform.switch-application-off), a kind the level cannot hold (`placementAllows`) - then the
// application's own `allowed` (Depot: a writer here, the instance's drop-box leaf on, no pending deletion).
import { registry, type FolderUploadShareRegistration, type LevelActionContext } from '../applications/registry'
import { applicationWritable, targetOf } from '../access'
import { FOLDER_KIND } from '../node/kind'

/** The registered upload-only link where the viewer of `ctx`'s level may create it, else null. */
export function folderUploadShareAt(ctx: LevelActionContext): FolderUploadShareRegistration | null {
  const share = registry.folderUploadShare()
  if (!share || ctx.access.mode !== 'member') return null
  const at = targetOf(ctx.tree, ctx.folder)
  if (at.kind !== null && at.kind !== FOLDER_KIND) return null
  if (at.inIdentityFolder) return null
  if (!applicationWritable(ctx.access, share.kind) || !registry.placementAllows(share.kind, at.kind)) return null
  return share.allowed(ctx) ? share : null
}
