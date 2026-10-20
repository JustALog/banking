CREATE TABLE fraud_evaluation (
    id                UUID PRIMARY KEY,
    transfer_id       UUID         NOT NULL,
    source_account_id UUID         NOT NULL,
    dest_account_id   UUID         NOT NULL,
    amount            BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    decision          VARCHAR(20)  NOT NULL,
    rule_triggered    VARCHAR(100),
    reason            TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_source_account_time ON fraud_evaluation (source_account_id, created_at DESC);
CREATE INDEX idx_fraud_transfer ON fraud_evaluation (transfer_id);
