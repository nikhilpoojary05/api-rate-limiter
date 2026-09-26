CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    api_key VARCHAR(255) NOT NULL,
    tier VARCHAR(50) NOT NULL,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rate_limit_rules (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    tier VARCHAR(50) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    request_limit INT NOT NULL,
    window_ms BIGINT NOT NULL,
    burst_capacity INT NOT NULL,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, tier)
);

CREATE INDEX idx_rules_tenant ON rate_limit_rules(tenant_id);

CREATE TABLE traffic_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(255),
    user_id VARCHAR(255),
    ip_address VARCHAR(255),
    path VARCHAR(255),
    method VARCHAR(50),
    status VARCHAR(50),
    latency_ms BIGINT,
    http_status INT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_traffic_tenant_ts ON traffic_events(tenant_id, timestamp);
