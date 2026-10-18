// The Depot application's API client — the factory over the client generated from its own spec
// (`contracts/api/depot/files.openapi.yaml` → `generated/depot`). It lives here, not in core's `api/client.ts`, because
// core imports no application client (ADR 0079; `validate_web_core_imports_no_applications.py` derives the directory
// from `generate:api`). The credentials are core's: [bearerParams] for a member, [publicParams] (none) for a guest.
import { CaseFilesApi, Configuration as FilesConfiguration, FilesApi, ShareFilesApi } from '../../api/generated/depot'
import { bearerParams, publicParams } from '../../api/client'

/** A member's file operations (the `files` tag): complete an upload into a file node, list a folder's file blobs. */
export function filesApi(): FilesApi {
  return new FilesApi(new FilesConfiguration(bearerParams()))
}

/** A guest's file operations on a share link (the `share-files` tag, no auth): list the file blobs under the shared
 *  subtree, complete a guest's upload into a file below the root. */
export function publicShareFilesApi(): ShareFilesApi {
  return new ShareFilesApi(new FilesConfiguration(publicParams()))
}

/** A moderator's read of a case's held files (`listCaseFiles`, uc.moderation.review-a-case) — the review's stand-in for
 *  [publicShareFilesApi]'s listing, bearer-authed and key-proved (`core/sharing/review.ts`). */
export function caseFilesApi(): CaseFilesApi {
  return new CaseFilesApi(new FilesConfiguration(bearerParams()))
}
