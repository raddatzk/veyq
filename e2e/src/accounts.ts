// Test identities are minted per run and live only in the run directory. The password is long and random, so no
// run shares an account with another and the (local) breach check has nothing to match.
import type { Download } from '@playwright/test'
import { parseKit, type KitData } from '@veyq/crypto/emergencyKit'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { recordPii } from './canaries'

export interface Owner {
  email: string
  password: string
}

/** A fresh account's identity. Its address is recorded as a `pii` needle (`recordPii`): no log of the run may hold it. */
export function freshOwner(domain: string): Owner {
  const stamp = randomBytes(4).toString('hex')
  const email = recordPii(`owner-${stamp}@${domain}`)
  return { email, password: `e2e ${randomBytes(18).toString('base64')} Gänsefüßchen` }
}

/**
 * The email and Secret Key, read back out of the Emergency Kit PDF the browser produced. The kit is the only place
 * the Secret Key exists, so a scenario that unlocks on a new device needs it from there — which also proves the kit
 * a Server Owner downloads is readable. The PDF carries the `veyqkit:` token uncompressed in its metadata
 * (en.account.key-model § The Emergency Kit); `parseKit` is the shared codec web/ reads kits with, so this reads the
 * kit exactly as the app would.
 */
export async function readKit(download: Download): Promise<KitData> {
  const path = await download.path()
  const kit = parseKit(readFileSync(path).toString('latin1'))
  if (!kit) throw new Error(`the Emergency Kit ${download.suggestedFilename()} carries no readable veyqkit: token`)
  return kit
}
