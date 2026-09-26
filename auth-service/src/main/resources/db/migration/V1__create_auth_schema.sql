CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL,
    tier VARCHAR(50) NOT NULL,
    description TEXT,
    active BOOLEAN DEFAULT true,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE app_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    tenant_id UUID REFERENCES tenants(id),
    tier VARCHAR(50),
    active BOOLEAN DEFAULT true,
    email_verified BOOLEAN DEFAULT false,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE user_roles (
    user_id BIGINT REFERENCES app_users(id),
    role VARCHAR(50) NOT NULL
);

CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(500) UNIQUE NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(255),
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN DEFAULT false,
    created_at TIMESTAMP NOT NULL
);
