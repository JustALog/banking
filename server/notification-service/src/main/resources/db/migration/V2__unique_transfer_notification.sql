-- At most one notification of each kind per transfer: redelivered events must not message the customer twice.
-- Keep the earliest row of any duplicates written before this constraint existed.
DELETE FROM notification a USING notification b
 WHERE a.transfer_id = b.transfer_id AND a.title = b.title AND (a.created_at, a.id) > (b.created_at, b.id);
CREATE UNIQUE INDEX uq_notification_transfer_title ON notification (transfer_id, title);
