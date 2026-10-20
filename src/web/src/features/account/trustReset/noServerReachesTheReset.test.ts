import 'fake-indexeddb/auto'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { fakeApi } from '../../../test/apiMock'
import { fakePinServer, sealPinStore, type PinServerState } from '../../../test/pinStore'
import type { AccountDeviceRegisterApi, DeviceRegister } from '../../../api/generated/account'
import type { ClaimedJob, TasksApi } from '../../../api/generated/tasks'

// ADR 0028 Decision 7's way back has exactly one trigger: the person, through the confirm dialog. The server can
// cause the REFUSAL whenever it likes — an old copy reads as rolled back, garbage as unreadable — so the property
// that matters is that no answer it gives reaches a write of either document. This serves every refusal (both
// codes, both documents) to every path a server response drives into the documents, and anchors on the one
// observable that means "it was started over": a recorded write of either document. Not on "it threw" — a throw is
// equally a network error. The positive control serves a readable copy to the same paths and sees the writes.
//
// Which paths: every importer of the two document modules outside the reset and the rotation, measured with
//   grep -rn -E "from '[./]*(account/)?(identityPins|deviceRegister)'" web/src | grep -v '\.test\.'
// — the grant-delivery worker, the member directory (`trustStates`), the changed-key review (`getPin`,
// `acceptKeyChange`), the group invite (`acceptKeyChange`), opening a scope (`scopes.ts` / `grantTrust.ts`, both
// through `checkAndPin`), the sessions panel (`readRegisterView`, the reconcile, the rename), the unlock
// (`rememberThisDevice`), the onboarding tail (`rememberThisDeviceWrap`), and the hub's probe. Opening the
// active space reaches the store only through `checkAndPin`, so it is driven at that entry point; the worker is
// driven whole, through its real claim, because its pause is its own decision.
vi.mock('../../../api/client', () => ({
  accountIdentityPinsApi: vi.fn(),
  accountDeviceRegisterApi: vi.fn(),
  tasksApi: vi.fn(),
}))
vi.mock('../../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../../app/vault', () => ({ masterKey: vi.fn(), identityPrivate: vi.fn() }))

import { acceptKeyChange, checkAndPin, forgetVerifiedPins, getPin, markVerified, trustStates } from '../identityPins'
import { fingerprintDigestB64 } from '../safetyCode'
import {
  readRegisterView,
  reconcileWithSessions,
  rememberThisDevice,
  rememberThisDeviceWrap,
  renameDeviceOfFamily,
} from '../deviceRegister'
import { refusedDocuments } from './refusedDocuments'
import { runGrantDeliveryWorker } from '../../tasks/grantDeliveryWorker'
import { GRANT_DELIVERY } from '../../tasks/capabilityTags'
import { tagAdmission } from '../../spaces/grant'
import { raiseMarks } from '../trustMarks'
import { accountDeviceRegisterApi, tasksApi } from '../../../api/client'
import { identityPrivate, masterKey } from '../../../app/vault'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'

const MK = v1.randomBytes(32)
const enc = new TextEncoder()
const WORKER = v1.generateIdentity()
const TARGET = v1.generateIdentity()
const TARGET_ID = '7a26e000-0000-4000-8000-0000000000c1'
const TARGET_PUB = b64std(v1.serializeIdentityPublic(TARGET.pub))
const S1 = '5c09e000-0000-4000-8000-0000000000c2'

const OTHER_ID = '7a26e000-0000-4000-8000-0000000000c3'
const OTHER_KEY = b64std(v1.serializeIdentityPublic(v1.generateIdentity().pub))
/** The pin store as this account wrote it at `revision`: one contact pinned (ADR 0104: a head plus an entry). */
const pinsAt = (revision: number) =>
  sealPinStore(MK, { [OTHER_ID]: { fingerprint: fingerprintDigestB64(OTHER_KEY), state: 'pinned', firstSeenAt: 1 } }, revision)
const registerAt = (revision: number) =>
  b64std(v1.seal({ purpose: 'device-register', revision }, MK, enc.encode(JSON.stringify({ devices: {}, families: {}, wraps: {} }))))

/** The two documents' endpoints, recording every write; a write lands whenever it is made against the served revision
 *  (and is then what is served), so a write is never masked by a conflict. `withhold` serves the pin listing without
 *  its entries — the per-entry rollback, under an honest head. */
function fakeServer(served: { pins: PinServerState; register: DeviceRegister }, opts: { withhold?: boolean } = {}) {
  const pins = fakePinServer(served.pins)
  if (opts.withhold) pins.tamper(() => new Map())
  const putIdentityPins = pins.writeIdentityPins
  const putDeviceRegister = vi.fn<AccountDeviceRegisterApi['putDeviceRegister']>(async ({ deviceRegisterUpdate }) => {
    served.register = { blob: deviceRegisterUpdate.blob, revision: deviceRegisterUpdate.revision! + 1 }
    return { ...served.register }
  })
  fakeApi(accountDeviceRegisterApi, { getDeviceRegister: async () => ({ ...served.register }), putDeviceRegister })
  // The worker's claim: one grant-delivery job to a genuinely admitted target, then drained.
  const K_SC = v1.randomBytes(32)
  const job: ClaimedJob = {
    jobId: 'j1', scopeId: S1, capabilityTag: GRANT_DELIVERY, attempts: 1,
    grantDelivery: {
      targetUserId: TARGET_ID, targetMembershipId: 'm1', identityPublicKey: TARGET_PUB,
      ...tagAdmission(K_SC, { scopeId: S1, kind: 'GROUP' }, 0, TARGET_ID),
    },
  }
  const completeTask = vi.fn<TasksApi['completeTask']>(async () => undefined)
  fakeApi(tasksApi, {
    claimTask: vi.fn<TasksApi['claimTask']>().mockResolvedValueOnce(job).mockResolvedValueOnce(null),
    completeTask,
    failTask: vi.fn<TasksApi['failTask']>(),
    declineTask: vi.fn<TasksApi['declineTask']>(),
    clearDeclinesForAcceptedKey: async () => ({ cleared: 0 }),
  })
  return { putIdentityPins, putDeviceRegister, completeTask, K_SC }
}

/** Every server-driven path into the two documents, each allowed to fail — only the writes are judged. */
async function driveEveryPath(K_SC: Uint8Array) {
  const attempts: Array<() => Promise<unknown>> = [
    () => getPin(TARGET_ID),
    () => checkAndPin(TARGET_ID, TARGET_PUB),
    () => trustStates([{ userId: TARGET_ID, directoryKey: TARGET_PUB }]),
    () => acceptKeyChange(TARGET_ID, TARGET_PUB),
    () => markVerified(TARGET_ID, fingerprintDigestB64(TARGET_PUB)),
    () => runGrantDeliveryWorker({ scopeId: S1, kind: 'GROUP', key: K_SC, epoch: 0 }),
    () => readRegisterView(['fam1'], [], 'me'),
    () => rememberThisDevice('me', 'Firefox', 'fam1', 'w1'),
    () => rememberThisDeviceWrap('me', 'Firefox', 'w2'),
    () => reconcileWithSessions(['fam1'], ['w1'], 'me', 'Firefox', 'fam1'),
    () => renameDeviceOfFamily('fam1', 'Laptop'),
    () => refusedDocuments(),
  ]
  for (const attempt of attempts) await attempt().catch(() => undefined)
}

beforeEach(() => {
  vi.clearAllMocks()
  globalThis.indexedDB = new IDBFactory()
  vi.mocked(masterKey).mockImplementation(() => MK.slice())
  forgetVerifiedPins()
  vi.mocked(identityPrivate).mockImplementation(() => ({
    kemSeed: WORKER.priv.kemSeed.slice(), sign: WORKER.priv.sign.slice(),
  }))
})

describe('no server response reaches a write of a refused document (ADR 0028 Decision 7)', () => {
  const garbage = () => b64std(v1.randomBytes(64))
  const none = () => new Map<string, string>()
  it.each([
    ['trust_state_unreadable', 'both documents', { pins: 3, register: 3 }, () => ({ pins: { blob: garbage(), revision: 3, entries: none() }, register: { blob: garbage(), revision: 3 } }), false],
    ['trust_state_rolled_back', 'both documents (older copy)', { pins: 7, register: 7 }, () => ({ pins: pinsAt(4), register: { blob: registerAt(4), revision: 4 } }), false],
    ['trust_state_rolled_back', 'both documents (missing after a mark)', { pins: 2, register: 2 }, () => ({ pins: { blob: undefined, revision: 0, entries: none() }, register: { revision: 0 } }), false],
    // ADR 0104: an honest head over a listing that withholds the entry — the register is unreadable beside it.
    ['trust_state_rolled_back', 'the pin store (an entry withheld)', { pins: 3, register: 3 }, () => ({ pins: pinsAt(3), register: { blob: garbage(), revision: 3 } }), true],
  ] as const)('%s, %s: every path is refused, and nothing is written', async (_code, _what, marks, served, withhold) => {
    await raiseMarks(MK, marks)
    const srv = fakeServer(served(), { withhold })

    await driveEveryPath(srv.K_SC)

    expect(srv.putIdentityPins).not.toHaveBeenCalled()
    expect(srv.putDeviceRegister).not.toHaveBeenCalled()
    expect(srv.completeTask).not.toHaveBeenCalled() // the worker paused; nothing was wrapped either
  })

  it('control: the same paths against readable documents DO write — the recorder sees a write when there is one', async () => {
    await raiseMarks(MK, { pins: 3, register: 3 })
    const srv = fakeServer({ pins: pinsAt(3), register: { blob: registerAt(3), revision: 3 } })

    await driveEveryPath(srv.K_SC)

    expect(srv.putIdentityPins).toHaveBeenCalled()
    expect(srv.putDeviceRegister).toHaveBeenCalled()
    expect(srv.completeTask).toHaveBeenCalled()
  })
})
