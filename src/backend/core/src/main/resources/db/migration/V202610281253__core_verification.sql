-- A member's verification of another member of the tenant (ADR 0111 decisions 12-13): "in this tenant, this verifier at
-- this head of their key chain confirmed this subject at this head of theirs", signed by the verifier's identity and
-- sealed under the tenant key of `epoch` (field `member-verification`). Opaque to the server, which stores and relays
-- it and checks nothing of its content. One row per (tenant, verifier, subject): the verifier's put replaces it.
--
-- The rows go with either account (an erasure) and with the tenant (its purge) by cascade, so neither needs a step of
-- its own; a member's removal from the tenant deletes every row of that tenant naming them, as verifier or as subject,
-- in the removal's transaction (VerificationMembershipEraser).
CREATE TABLE veyq_verification (
    tenant_id        UUID        NOT NULL,
    verifier_user_id UUID        NOT NULL,
    subject_user_id  UUID        NOT NULL,
    epoch            INT         NOT NULL,
    sealed_statement TEXT        NOT NULL,
    created_at       DATETIME(3) NOT NULL,
    CONSTRAINT pk_verification PRIMARY KEY (tenant_id, verifier_user_id, subject_user_id),
    CONSTRAINT fk_verification_tenant FOREIGN KEY (tenant_id) REFERENCES veyq_tenant (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_verifier FOREIGN KEY (verifier_user_id) REFERENCES veyq_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_verification_subject FOREIGN KEY (subject_user_id) REFERENCES veyq_user (id) ON DELETE CASCADE
);
CREATE INDEX ix_verification_subject ON veyq_verification (tenant_id, subject_user_id);
