-- Drop the setup-time instance name: write-only dead data and the wrong altitude for a multi-tenant
-- instance (one server hosts many tenants, each resolved by Host/domain; names are E2EE). See cr-0006.
ALTER TABLE veyq_server_setup DROP COLUMN instance_name;
