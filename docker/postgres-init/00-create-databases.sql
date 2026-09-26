-- auth-service and admin-service each ship their own V1__/V2__ migrations. Pointing
-- both at one database made them share a single flyway_schema_history, so whichever
-- started second failed on a version already applied with a different checksum.
-- They get a database each.
--
-- Postgres runs this only when the data volume is empty. After changing it you must
-- recreate the volume: docker compose down -v
CREATE DATABASE ratelimiter_auth;
CREATE DATABASE ratelimiter_admin;
