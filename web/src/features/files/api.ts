// The Files application's API client — the factory over the client generated from its own spec
// (`contracts/api/files/files.openapi.yaml` → `generated/files`). It lives here, not in core's `api/client.ts`, because
// core imports no application client (ADR 0079; `validate_web_core_imports_no_applications.py` derives the directory
// from `generate:api`). The credential is core's: [bearerParams], so the token source stays one.
import { Configuration as FilesConfiguration, FilesApi } from '../../api/generated/files'
import { bearerParams } from '../../api/client'

/** A member's file operations (the `files` tag): complete an upload into a file node, list a folder's file blobs. */
export function filesApi(): FilesApi {
  return new FilesApi(new FilesConfiguration(bearerParams()))
}
