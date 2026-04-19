CREATE TABLE notifications (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    VARCHAR(100) NOT NULL,
    recipient_id VARCHAR(255) NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    priority     VARCHAR(20)  NOT NULL DEFAULT 'MEDIUM',
    template_id  VARCHAR(100) NOT NULL,
    content      TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP,
    CONSTRAINT notifications_channel_check
        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    CONSTRAINT notifications_priority_check
        CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT notifications_status_check
        CHECK (status IN ('QUEUED', 'PROCESSING', 'DELIVERED', 'FAILED'))
);
CREATE INDEX idx_notifications_tenant_id  ON notifications(tenant_id);
CREATE INDEX idx_notifications_status     ON notifications(status);
CREATE INDEX idx_notifications_created_at ON notifications(created_at DESC);
