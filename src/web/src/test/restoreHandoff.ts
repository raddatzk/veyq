// The seam between a grouped e2e suite's members (`scripts/run_web_e2e.py` § Groups). Part A births an instance
// and writes what part B needs into `$VEYQ_GROUP_DIR`; between them the orchestrator captures the instance,
// **destroys** it, and restores onto an empty one.
//
// What crosses this seam is deliberately only what a PERSON would still have after their server died: an email
// address, a password, and the Emergency Kit's Secret Key. Nothing derived, no vault, no Master Key — because
// the sentence under test is *"every Memory opens with the keys their owners already hold"*, and a handoff that
// carried an unwrapped key would satisfy the assertion while proving something else entirely.
//
// The scope/node ids and the plaintext bytes travel too. Those are not credentials — they are the *question*
// ("open this Memory, and does it still hold these exact bytes?"), which part B has no other way to know.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

export type Handoff = {
  /** What the person still has. */
  email: string
  password: string
  /** The Emergency Kit's Secret Key, base64 — the second factor of the PASSWORD floor unlock. */
  secretKey: string
  /** Which tenant to read the config-secret state of (leg 3). */
  tenantId: string
  /** What to reopen afterwards. Absent in the leg-3 group, which asserts about the KEK and not about media. */
  scopeId?: string
  nodeId?: string
  /** The exact plaintext bytes part A uploaded, base64 — part B's oracle. */
  bytesB64?: string
}

function groupDir(): string {
  const dir = process.env.VEYQ_GROUP_DIR
  if (!dir) {
    throw new Error(
      'VEYQ_GROUP_DIR is unset — this suite is a member of a GROUP and only means anything when run through ' +
        'scripts/run_web_e2e.py, which captures and restores the instance between its members.',
    )
  }
  return dir
}

export function writeHandoff(handoff: Handoff): void {
  writeFileSync(join(groupDir(), 'handoff.json'), JSON.stringify(handoff, null, 2))
}

export function readHandoff(): Handoff {
  return JSON.parse(readFileSync(join(groupDir(), 'handoff.json'), 'utf8')) as Handoff
}

/** Deterministic, non-compressible-ish test bytes — the same shape `media.e2e.test.ts` uploads. */
export function testBytes(): Uint8Array {
  const data = new Uint8Array(300 * 1024)
  for (let i = 0; i < data.length; i++) data[i] = (i * 17 + 3) & 0xff
  return data
}
