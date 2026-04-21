CREATE TABLE tenants (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        VARCHAR(100) NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    rate_limit       INTEGER      NOT NULL DEFAULT 5000,
    rate_window_sec  INTEGER      NOT NULL DEFAULT 10,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
INSERT INTO tenants (tenant_id, name, rate_limit, rate_window_sec)
VALUES
    ('swiggy',  'Swiggy',   10000, 10),
    ('zomato',  'Zomato',   8000,  10),
    ('infosys', 'Infosys',  5000,  10),
    ('default', 'Default',  1000,  10);
