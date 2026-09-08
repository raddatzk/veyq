// Web Worker that runs the heavy Argon2id derivations off the main thread, so the UI stays responsive while
// login/unlock spend hundreds of ms in the KDF. It imports the conformance-pinned suite UNCHANGED and just
// runs it here instead of on the main thread — same bytes, same params, no behaviour change.
import * as v1 from './v1'
import type { ArgonParams } from './v1'

type Request =
  | { id: number; op: 'authKeypair'; password: string; salt: Uint8Array; params?: ArgonParams }
  | { id: number; op: 'unlockKek'; secretKey: Uint8Array; password: string; salt: Uint8Array; params?: ArgonParams }
  | { id: number; op: 'deviceKek'; deviceFactor: Uint8Array; password: string; salt: Uint8Array; params?: ArgonParams }
  | { id: number; op: 'recoveryKek'; secretKey: Uint8Array; salt: Uint8Array; params?: ArgonParams }

const ctx = self as unknown as { postMessage(message: unknown): void }

self.addEventListener('message', (event: MessageEvent<Request>) => {
  const msg = event.data
  try {
    let result: unknown
    if (msg.op === 'authKeypair') result = v1.deriveAuthKeypair(msg.password, msg.salt, msg.params)
    else if (msg.op === 'unlockKek') result = v1.kekPasswordSecret(msg.secretKey, msg.password, msg.salt, msg.params)
    else if (msg.op === 'deviceKek') result = v1.kekDevicePassword(msg.deviceFactor, msg.password, msg.salt, msg.params)
    else if (msg.op === 'recoveryKek') result = v1.kekRecovery(msg.secretKey, msg.salt, msg.params)
    else throw new Error('unknown_op') // explicit — a new op must be wired here, not silently misrouted
    ctx.postMessage({ id: msg.id, ok: true, result })
  } catch (err) {
    ctx.postMessage({ id: msg.id, ok: false, error: String(err) })
  }
})
