// What first-run setup sends with the Server Owner (ADR 0099 § 3; owner 2026-10-17): epoch 1 of the instance moderation
// key, its M_1 sealed to the identity born in the same request and then forgotten. admin-web's setup mints it in its
// moderation facade (`epochOneFor`); web/ never sets an instance up, so this copy serves only its two setup harnesses —
// the dev seeder (`scripts/bootstrap-user.mts`) and the e2e bootstrap (`e2eBootstrap.ts`) — which must send what the
// contract requires.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

export function moderationEpochOne(identityPublicKey: string): { kemPublicKey: string; signPublicKey: string; sealedGrant: string } {
  const m = v1.generateModerationKey()
  try {
    const bundle = v1.moderationBundle(m)
    const recipient = v1.deserializeIdentityPublic(fromB64std(identityPublicKey))
    return {
      kemPublicKey: b64std(bundle.kem),
      signPublicKey: b64std(bundle.sign),
      sealedGrant: b64std(v1.sealTo({ purpose: 'moderation-grant', epoch: 1 }, recipient.kem, m)),
    }
  } finally {
    m.fill(0)
  }
}
