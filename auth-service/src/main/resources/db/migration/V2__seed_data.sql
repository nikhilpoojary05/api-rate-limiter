-- Auth service seed data with VALID BCrypt hashes
-- Password: Admin@123! -> hash below
-- Password: Test@123!  -> hash below

INSERT INTO tenants (id, name, tier, description, active, created_at, updated_at) VALUES 
('11111111-1111-1111-1111-111111111111', 'acme-corp',   'TIER_A',    'Acme Corporation',  true, NOW(), NOW()),
('22222222-2222-2222-2222-222222222222', 'beta-inc',    'TIER_B',    'Beta Incorporated', true, NOW(), NOW()),
('33333333-3333-3333-3333-333333333333', 'free-user-co','TIER_FREE', 'Free User Company', true, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Admin user: password = Admin@123!
INSERT INTO app_users (id, username, email, password, tenant_id, tier, active, email_verified, created_at, updated_at) VALUES 
(1, 'admin', 'admin@acme.com',
 '$2b$10$oeXSldFWfPZ1Oxn0Cm3xHOSSrSzIDIMELgbfQ00sRW/N/Uf68d78e',
 '11111111-1111-1111-1111-111111111111', 'TIER_A', true, true, NOW(), NOW())
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role) VALUES (1, 'ROLE_ADMIN'), (1, 'ROLE_USER')
ON CONFLICT DO NOTHING;

-- Regular users: password = Test@123!
INSERT INTO app_users (id, username, email, password, tenant_id, tier, active, email_verified, created_at, updated_at) VALUES 
(2, 'john.doe',   'john.doe@acme.com',   '$2b$10$fxJcgJVXHYvzslTzBA3DTusw26aIV6fbe4W0wYGcUVzyo8zgCu.Ce', '11111111-1111-1111-1111-111111111111', 'TIER_A',    true, true, NOW(), NOW()),
(3, 'jane.smith', 'jane.smith@beta.com', '$2b$10$fxJcgJVXHYvzslTzBA3DTusw26aIV6fbe4W0wYGcUVzyo8zgCu.Ce', '22222222-2222-2222-2222-222222222222', 'TIER_B',    true, true, NOW(), NOW())
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role) VALUES (2, 'ROLE_USER'), (3, 'ROLE_USER')
ON CONFLICT DO NOTHING;

SELECT setval('app_users_id_seq', (SELECT MAX(id) FROM app_users));
