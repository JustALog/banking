CREATE TABLE notification (
    id          UUID PRIMARY KEY,
    customer_id UUID         NOT NULL,
    transfer_id UUID,
    channel     VARCHAR(20)  NOT NULL,
    recipient   VARCHAR(255) NOT NULL,
    title       VARCHAR(255) NOT NULL,
    content     TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_customer ON notification (customer_id, created_at DESC);
