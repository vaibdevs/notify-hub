CREATE TABLE delivery_logs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID         NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    attempt_number  INTEGER      NOT NULL,
    status          VARCHAR(30)  NOT NULL,
    provider        VARCHAR(50)  NOT NULL,
    error_message   TEXT,
    attempted_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT delivery_logs_status_check
        CHECK (status IN ('DELIVERED', 'FAILED', 'CIRCUIT_OPEN', 'REPLAYING'))
);
CREATE INDEX idx_delivery_logs_notification_id ON delivery_logs(notification_id);
CREATE INDEX idx_delivery_logs_status          ON delivery_logs(status);
