INSERT INTO tenants (tenant_id, name, api_key, tier, active) VALUES 
('acme-corp', 'Acme Corporation', 'api_key_acme_123', 'TIER_A', true),
('beta-inc', 'Beta Incorporated', 'api_key_beta_456', 'TIER_B', true),
('free-user-co', 'Free Users Co', 'api_key_free_789', 'TIER_FREE', true);

INSERT INTO rate_limit_rules (tenant_id, tier, algorithm, request_limit, window_ms, burst_capacity, active) VALUES 
('acme-corp', 'TIER_A', 'SLIDING_WINDOW', 100, 60000, 150, true),
('beta-inc', 'TIER_B', 'SLIDING_WINDOW', 1000, 60000, 1500, true),
('free-user-co', 'TIER_FREE', 'TOKEN_BUCKET', 10, 60000, 20, true);
