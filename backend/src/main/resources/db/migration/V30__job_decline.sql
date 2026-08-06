-- Grant-delivery decline (cr-0098, ADR 0028 §Consequences). A worker that holds a DIFFERENT pin for a
-- grant-delivery target's identity key refuses the job rather than sealing K_SC to a possibly-substituted key:
-- it releases the claim back to PENDING (still claimable by OTHER workers) and records a (job, worker) decline
-- here so the claim/reconcile machinery never re-offers THIS job to THIS worker. Per-device, not a global veto —
-- a different key-holder without a conflicting pin can still TOFU-deliver, and the blind server never
-- adjudicates the crypto claim (a global quarantine would be a DoS). Operational metadata only: no key, no trust
-- graph. Surrogate id + a unique (job, worker) index (mirrors veyq_scope_epoch) keeps the Panache mapping simple.
CREATE TABLE veyq_job_decline (
    id          VARCHAR(36) NOT NULL PRIMARY KEY,
    job_id      VARCHAR(36) NOT NULL,
    worker_id   VARCHAR(36) NOT NULL,
    reason      VARCHAR(256),
    declined_at TIMESTAMP   NOT NULL,
    CONSTRAINT fk_job_decline_job FOREIGN KEY (job_id) REFERENCES veyq_job (id)
);
CREATE UNIQUE INDEX ux_job_decline ON veyq_job_decline (job_id, worker_id);
