-- One evaluation per transfer: replayed transfer.requested events must not add rows (velocity rules count them).
-- Keep the earliest row of any duplicates written before this constraint existed.
DELETE FROM fraud_evaluation a USING fraud_evaluation b
 WHERE a.transfer_id = b.transfer_id AND (a.created_at, a.id) > (b.created_at, b.id);
DROP INDEX idx_fraud_transfer;
CREATE UNIQUE INDEX uq_fraud_transfer ON fraud_evaluation (transfer_id);
