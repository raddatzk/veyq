-- Device linking (ADR 0059): the short-lived, single-use channel two devices run one pairing ceremony over.
--
-- Shape copied deliberately from veyq_account_recovery (ADR 0059 §8 names it): an opaque row with an expiry, a
-- terminal status, enforcement at read AND a scheduled sweep. Explicitly NOT ADR 0021's job queue, whose routing
-- is scope-membership-based and therefore cannot address a device that has no membership yet.
--
-- Only the SHA-256 handle HASH is stored, never the handle: the routing secret is minted client-side and travels
-- in the request body, so a database dump finds no live channel. The three relayed values are opaque to the
-- server — `answer` is an ephemeral transport public key in one direction and a commitment to a transcribed code
-- in the other (§5, §6), and the server must not be able to tell which; `envelope` is the sealed grant, which it
-- relays and cannot open.
--
-- Two departures from the recovery shape, both forced by the ceremony:
--
-- `user_id` is NULLABLE. In the "sign in with a QR code" direction the channel is opened by a device with no
-- session at all, so an account is not merely unnecessary at open, it is unavailable — which account this
-- becomes is decided by whoever scans (§3). The account reaches the row at /api/pairing/delegate, which is also
-- where §8's "one outstanding pairing per account" is enforced by superseding the account's other live channels.
--
-- `delegated_family_id` records the session family minted for this ceremony, so a channel that dies unspent can
-- take that family with it (PairingPurge). Without it an abandoned ceremony would leave a listed family holding
-- a live 30-day refresh token nobody ever picked up. It carries deliberately NO foreign key to
-- veyq_session_family: SessionPurge physically deletes families whose tokens have all expired, and an FK would
-- make that delete fail against a pairing row still in its retention window.
CREATE TABLE veyq_device_pairing (
    id                  VARCHAR(36) NOT NULL PRIMARY KEY,
    handle_hash         VARCHAR(64) NOT NULL,
    user_id             VARCHAR(36) NULL,
    delegated_family_id VARCHAR(36) NULL,
    answer              TEXT        NULL,
    envelope            TEXT        NULL,
    status              VARCHAR(16) NOT NULL,
    expires_at          TIMESTAMP   NOT NULL,
    created_at          TIMESTAMP   NOT NULL,
    CONSTRAINT fk_device_pairing_user FOREIGN KEY (user_id) REFERENCES veyq_user (id)
);

-- Every one of the five endpoints resolves its channel by hash, so this is the table's only hot read. UNIQUE
-- because a handle addresses exactly one channel: a second row under the same hash would make "which channel is
-- this?" ambiguous at the very moment the answer must be single-valued.
CREATE UNIQUE INDEX ux_device_pairing_handle_hash ON veyq_device_pairing (handle_hash);

-- Superseding the account's other live channels at delegation scans by (user, status).
CREATE INDEX ix_device_pairing_user_status ON veyq_device_pairing (user_id, status);
