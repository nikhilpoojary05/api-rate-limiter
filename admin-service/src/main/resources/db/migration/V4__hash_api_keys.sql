-- API keys were stored in plain text, so anyone who could read this table could use
-- every tenant's key. Keep only a SHA-256 hash, which the gateway compares against, and
-- a short prefix so people can tell keys apart. A key is shown in full once, when it is
-- created or rotated.
ALTER TABLE tenants ADD COLUMN api_key_hash VARCHAR(64);
ALTER TABLE tenants ADD COLUMN api_key_prefix VARCHAR(16);

UPDATE tenants
SET api_key_hash   = encode(sha256(convert_to(api_key, 'UTF8')), 'hex'),
    api_key_prefix = left(api_key, 12);

ALTER TABLE tenants ALTER COLUMN api_key_hash SET NOT NULL;
ALTER TABLE tenants ALTER COLUMN api_key_prefix SET NOT NULL;
CREATE UNIQUE INDEX idx_tenants_api_key_hash ON tenants(api_key_hash);

ALTER TABLE tenants DROP COLUMN api_key;
