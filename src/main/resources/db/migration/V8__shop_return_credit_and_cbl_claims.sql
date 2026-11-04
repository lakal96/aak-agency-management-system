-- Approved shop returns now credit the shop's bill and damaged/expired goods are tracked for CBL claims.
-- Everything here is additive and nullable: existing rows are untouched, except that already-approved
-- damaged/expired return lines are marked FOR_CLAIM (they are real goods that were never claimed).

ALTER TABLE shop_returns
    ADD COLUMN credit_applied_amount DECIMAL(15,2) NULL,
    ADD COLUMN refund_due_amount DECIMAL(15,2) NULL;

ALTER TABLE shop_return_items
    ADD COLUMN cbl_claim_status VARCHAR(20) NULL;

UPDATE shop_return_items i
    JOIN shop_returns r ON r.id = i.shop_return_id
SET i.cbl_claim_status = 'FOR_CLAIM'
WHERE r.status = 'APPROVED'
  AND i.category IN ('DAMAGED', 'EXPIRED');
