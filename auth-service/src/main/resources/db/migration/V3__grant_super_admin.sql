-- Admin endpoints are now scoped to the caller's own tenant. ROLE_SUPER_ADMIN is the
-- role that may read and modify across tenants; grant it to the seeded operator account
-- so the existing cross-tenant dashboard views keep working.
INSERT INTO user_roles (user_id, role)
SELECT 1, 'ROLE_SUPER_ADMIN'
WHERE NOT EXISTS (
    SELECT 1 FROM user_roles WHERE user_id = 1 AND role = 'ROLE_SUPER_ADMIN'
);
