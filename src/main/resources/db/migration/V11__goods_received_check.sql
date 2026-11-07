-- Goods check on a purchase invoice: each line is ticked after the goods were counted against it. What was received can
-- differ from the invoice: the line then follows what was received, and the invoiced quantity and amount are kept here.
ALTER TABLE purchase_invoice_items
    ADD COLUMN received_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN received_by VARCHAR(100) NULL,
    ADD COLUMN received_at DATETIME(6) NULL,
    ADD COLUMN expected_quantity DECIMAL(15,2) NULL,
    ADD COLUMN expected_amount DECIMAL(15,2) NULL;

-- invoices completed before this existed were verified the old way
UPDATE purchase_invoice_items SET received_confirmed = TRUE
WHERE purchase_invoice_id IN (SELECT id FROM purchase_invoices WHERE status = 'COMPLETED');
