-- Cross-tenant dashboard queries filter on timestamp alone, which the existing
-- (tenant_id, timestamp) index cannot serve.
CREATE INDEX idx_traffic_ts ON traffic_events(timestamp);
