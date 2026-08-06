// The opaque capability tokens the web client pins from the public registry (`contracts/tasks/tags/v1.json`,
// ADR 0021). A tag is the value that rides a worker's claim + the job envelope on the wire, so a bare DB dump
// sees `02f271ff`, not `grant-delivery`. A conformance test asserts these equal the registry (drift-proofing —
// the same guard the crypto vectors get); the residual "kind of work" leak is correlation-bounded, not
// label-bounded (docs/crosscutting/background-tasks.md), so these tokens only keep plaintext labels out of the
// store, they do not hide the work from a correlating operator.

/** grant-delivery (cr-0076 / ADR 0020): wrap K_SC to a pending member's identity — the first consumer. */
export const GRANT_DELIVERY = '02f271ff'

/** scope-rotation (cr-0092 / ADR 0025): complete a queued group-key rotation after a co-shared member was cut
 *  with no key-holder online — the next remaining key-holder mints E_{n+1} + re-grants the survivors on-device. */
export const SCOPE_ROTATION = '663157d8'
