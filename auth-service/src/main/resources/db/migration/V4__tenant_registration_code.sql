-- Registration previously let any caller name any tenant in the request body and
-- inherit that tenant's rate limit tier. Joining a tenant now requires knowing that
-- tenant's registration code, which an operator shares out of band.
--
-- A NULL code means registration is closed for that tenant.
ALTER TABLE tenants ADD COLUMN registration_code VARCHAR(255);

-- Seed codes for the demo tenants. Rotate these before any real deployment.
UPDATE tenants SET registration_code = 'acme-join-4f7c21' WHERE name = 'acme-corp';
UPDATE tenants SET registration_code = 'beta-join-9b3e08' WHERE name = 'beta-inc';
-- free-user-co intentionally left NULL: registration closed.
