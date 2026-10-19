-- Every time column is DATETIME, never TIMESTAMP (inbox #509). MariaDB's TIMESTAMP counts seconds in 32 bits: on the
-- pinned 11.4 it holds 1970-01-01T00:00:01Z to 2038-01-19T03:14:07Z, so a share's expiresAt in 2577 failed its UPDATE
-- (a 500) and the server's own clock reaches the end in 2038. DATETIME holds 1000-01-01 to 9999-12-31 and converts
-- nothing: it keeps the wall clock it is given, and Hibernate writes an Instant as its UTC wall clock (a UTC calendar
-- on the bind and on the read), so every time column holds UTC by convention, whatever the session's time_zone.
--
-- Each column keeps its precision (a plain TIMESTAMP becomes DATETIME, whole seconds; TIMESTAMP(3) becomes DATETIME(3))
-- and its nullability; none had a default or an ON UPDATE clause. One statement per column, the form the earlier type
-- changes use (V202610191600's MODIFY COLUMN), which MariaDB and the tests' H2 in MariaDB mode both run.
--
-- The conversion reads each TIMESTAMP in this session's time_zone, which is the zone its writer's session had: the
-- driver sets none (forceConnectionTimeZoneToSession is off), so both are the server's own. A UTC wall clock comes out.
-- scripts/validate_time_columns.py keeps a TIMESTAMP column from coming back.
ALTER TABLE veyq_access_token MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_account_erasure MODIFY COLUMN due_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_account_erasure MODIFY COLUMN mailed_at DATETIME(3) NULL;
ALTER TABLE veyq_account_erasure MODIFY COLUMN requested_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_account_recovery MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_account_recovery MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_authority_order MODIFY COLUMN blocked_at DATETIME(3) NULL;
ALTER TABLE veyq_authority_order MODIFY COLUMN entered_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_authority_order MODIFY COLUMN hold_until DATETIME(3) NULL;
ALTER TABLE veyq_authority_order MODIFY COLUMN received_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_device_pairing MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_device_pairing MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_email_change MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_email_confirmation MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_email_confirmation MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_erasure_outbox MODIFY COLUMN recorded_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_erasure_outbox MODIFY COLUMN sent_at DATETIME(3) NULL;
ALTER TABLE veyq_guest_write_record MODIFY COLUMN expires_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_guest_write_record MODIFY COLUMN observed_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_hold MODIFY COLUMN ceiling_until DATETIME(3) NULL;
ALTER TABLE veyq_hold MODIFY COLUMN expires_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_hold MODIFY COLUMN floor_until DATETIME(3) NULL;
ALTER TABLE veyq_hold_pin MODIFY COLUMN expires_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_instance_mail_settings MODIFY COLUMN code_expires_at DATETIME(3) NULL;
ALTER TABLE veyq_instance_mail_settings MODIFY COLUMN confirmed_at DATETIME(3) NULL;
ALTER TABLE veyq_job MODIFY COLUMN created_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_job MODIFY COLUMN lease_expires_at DATETIME NULL;
ALTER TABLE veyq_login_challenge MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_membership MODIFY COLUMN created_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_block MODIFY COLUMN lifted_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_block MODIFY COLUMN taken_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_case MODIFY COLUMN closed_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_case MODIFY COLUMN decided_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_case MODIFY COLUMN noticed_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_challenge MODIFY COLUMN expires_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_epoch MODIFY COLUMN rotation_due_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_epoch MODIFY COLUMN rotation_due_mailed_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_event MODIFY COLUMN expires_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_event MODIFY COLUMN hold_until DATETIME(3) NULL;
ALTER TABLE veyq_moderation_event MODIFY COLUMN occurred_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_objection MODIFY COLUMN decided_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_objection MODIFY COLUMN received_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_proposal MODIFY COLUMN proposed_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_proposal MODIFY COLUMN settled_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_report MODIFY COLUMN received_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderation_statement MODIFY COLUMN deferred_until DATETIME(3) NULL;
ALTER TABLE veyq_moderation_statement MODIFY COLUMN sent_at DATETIME(3) NULL;
ALTER TABLE veyq_moderation_statement_recipient MODIFY COLUMN mailed_at DATETIME(3) NULL;
ALTER TABLE veyq_moderator MODIFY COLUMN accepted_at DATETIME(3) NULL;
ALTER TABLE veyq_moderator MODIFY COLUMN appointed_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_moderator MODIFY COLUMN code_expires_at DATETIME(3) NULL;
ALTER TABLE veyq_moderator MODIFY COLUMN confirmed_at DATETIME(3) NULL;
ALTER TABLE veyq_moderator MODIFY COLUMN removed_at DATETIME(3) NULL;
ALTER TABLE veyq_node MODIFY COLUMN created_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_node MODIFY COLUMN deleted_at DATETIME NULL;
ALTER TABLE veyq_operator_details MODIFY COLUMN code_expires_at DATETIME(3) NULL;
ALTER TABLE veyq_operator_details MODIFY COLUMN confirmed_at DATETIME(3) NULL;
ALTER TABLE veyq_refresh_token MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_release_check MODIFY COLUMN last_success_at DATETIME(3) NULL;
ALTER TABLE veyq_release_notice MODIFY COLUMN found_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_release_notice MODIFY COLUMN mailed_at DATETIME(3) NULL;
ALTER TABLE veyq_scope MODIFY COLUMN deleted_at DATETIME NULL;
ALTER TABLE veyq_session_family MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_session_family MODIFY COLUMN last_used_at DATETIME NOT NULL;
ALTER TABLE veyq_share MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_share MODIFY COLUMN expires_at DATETIME NULL;
ALTER TABLE veyq_store_orphan MODIFY COLUMN first_seen_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_store_reclaim MODIFY COLUMN abandoned_at DATETIME NULL;
ALTER TABLE veyq_store_reclaim MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_store_reclaim MODIFY COLUMN next_attempt_at DATETIME NOT NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN clocks_restarted_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN completed_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN completed_started_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN exclusive_confirmed_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN failed_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN last_listed_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN pass_started_at DATETIME(3) NULL;
ALTER TABLE veyq_store_reconcile MODIFY COLUMN paused_at DATETIME(3) NULL;
ALTER TABLE veyq_store_setting MODIFY COLUMN checked_at DATETIME(3) NULL;
ALTER TABLE veyq_tenant MODIFY COLUMN deletion_due_at DATETIME(3) NULL;
ALTER TABLE veyq_tenant_invite MODIFY COLUMN created_at DATETIME(3) NOT NULL;
ALTER TABLE veyq_tenant_invite MODIFY COLUMN expires_at DATETIME NOT NULL;
ALTER TABLE veyq_tenant_terms_acceptance MODIFY COLUMN accepted_at DATETIME NOT NULL;
ALTER TABLE veyq_upload_session MODIFY COLUMN last_window_at DATETIME NOT NULL;
ALTER TABLE veyq_user MODIFY COLUMN email_confirmed_at DATETIME NULL;
