// The ops the Argon2 front door (argonWorker.ts) sends and the worker (argon.worker.ts) answers — named once, here,
// because the two files must not import each other: the worker importing the front door would pull its
// `new Worker(…)` call into the worker chunk. The front door's `dispatch` takes an `ArgonOp`, so tsc refuses an op
// outside this list, and argon.worker.test.ts sends every op in it — an op added here without a dispatcher branch
// is answered `unknown_op`, which the front door REJECTS rather than falling back, and that test goes red.
export const ARGON_OPS = ['authKeypair', 'unlockKek', 'deviceKek', 'recoveryKek', 'sharesPwToken', 'inviteLinkKek'] as const

export type ArgonOp = (typeof ARGON_OPS)[number]
