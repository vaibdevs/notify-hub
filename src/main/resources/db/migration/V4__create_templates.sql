CREATE TABLE templates (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id VARCHAR(100) NOT NULL UNIQUE,
    channel     VARCHAR(20)  NOT NULL,
    subject     VARCHAR(255),
    body        TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
INSERT INTO templates (template_id, channel, subject, body) VALUES
    ('order-confirmation', 'EMAIL',
     'Order Confirmed - {{orderId}}',
     'Hi {{userName}}, your order {{orderId}} has been confirmed and is being prepared.'),
    ('otp-verification', 'SMS',
     NULL,
     'Your OTP is {{otp}}. Valid for {{expiryMinutes}} minutes. Do not share with anyone.'),
    ('order-update-push', 'PUSH',
     'Order Update',
     'Hi {{userName}}, your order {{orderId}} status: {{status}}'),
    ('password-reset', 'EMAIL',
     'Reset Your Password',
     'Hi {{userName}}, click here to reset your password: {{resetLink}}. Link expires in 30 minutes.');
