// Local dev seeder (cr-0015): mint a first-run Server Owner with a KNOWN password + Secret Key so the
// native/web clients can log in + unlock against a fresh local instance (e.g. to regenerate the apple
// screenshots — see docs/design/screenshots/apple/README.md). Reuses the real cold-account-birth crypto
// (operator-blind — only ciphertext + public keys are POSTed; the password never leaves this process).
//
//   cd web && npx tsx scripts/seed-owner.mts        # against http://localhost:8080 by default
//
// Override via env: VEYQ_BASE / VEYQ_ADMIN_BASE / VEYQ_EMAIL / VEYQ_NAME / VEYQ_PASSWORD. Requires a not-yet-set-up instance.
import { fromB64std } from '@veyq/crypto/base64'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { writeOwner, materializeKeys } from './devSeed.mts'

const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
// The backend's admin listener: the only door the Server-Owner operations answer on — the public port 404s them
// (ADR 0065). 8090 is its dev default (veyq.admin-channel.port).
const ADMIN_BASE = process.env.VEYQ_ADMIN_BASE ?? 'http://127.0.0.1:8090'
const email = process.env.VEYQ_EMAIL ?? 'kevin@veyq.test'
const password = process.env.VEYQ_PASSWORD ?? 'correct horse battery staple'

const status = await (await fetch(`${BASE}/api/setup`)).json()
if (!status.setupRequired) {
  console.error('Instance already set up — wipe it (docker compose down -v) for a fresh seed.')
  process.exit(1)
}
const instanceSalt = fromB64std(status.instanceSalt)

const built = await buildAccount(email, password, instanceSalt)
const { material, emergencyKit } = built
const body = {
  email,
  suiteId: material.suiteId,
  identityPublicKey: material.identityPublicKey,
  wrappedIdentityPrivateKey: material.wrappedIdentityPrivateKey,
  authPublicKey: material.authPublicKey,
  masterKeyWraps: material.masterKeyWraps,
  secretKeyProofPublicKey: material.secretKeyProofPublicKey,
}

const res = await fetch(`${ADMIN_BASE}/api/setup/owner`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify(body),
})
if (!res.ok) {
  console.error('setup/owner failed', res.status, await res.text())
  process.exit(1)
}
const result = await res.json()
// Persist the seed (creds + raw keys) so a harness can reuse this owner without re-seeding (cr-0046).
writeOwner(BASE, { email, password, secretKey: emergencyKit.secretKey, ...materializeKeys(built, password), userId: result.userId })
console.log(JSON.stringify({ ok: true, userId: result.userId, email, password, secretKey: emergencyKit.secretKey, base: BASE, seedFile: true }, null, 2))
