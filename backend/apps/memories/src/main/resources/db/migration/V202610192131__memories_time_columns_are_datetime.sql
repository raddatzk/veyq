-- The Memories application's time columns become DATETIME, as core's do in V202610192130__core_time_columns_are_datetime
-- (inbox #509; that file says why and how): MariaDB's TIMESTAMP ends at 2038-01-19T03:14:07Z, DATETIME holds the UTC
-- wall clock Hibernate writes. Precision and nullability are kept; one statement per column.
ALTER TABLE veyq_bucket MODIFY COLUMN created_at DATETIME NOT NULL;
ALTER TABLE veyq_media MODIFY COLUMN deleted_at DATETIME(3) NULL;
ALTER TABLE veyq_media MODIFY COLUMN updated_at DATETIME(3) NOT NULL;
