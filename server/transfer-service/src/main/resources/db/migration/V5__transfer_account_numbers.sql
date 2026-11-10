ALTER TABLE transfer
    ADD COLUMN source_account_no VARCHAR(20),
    ADD COLUMN dest_account_no VARCHAR(20),
    ADD COLUMN dest_customer_id UUID;

CREATE INDEX idx_transfer_dest_customer ON transfer (dest_customer_id, created_at DESC);
