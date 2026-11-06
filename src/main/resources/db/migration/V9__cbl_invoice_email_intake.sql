-- Purchase invoices that arrive by e-mail (CBL's PDF) become drafts. CBL invoices count in dozens/cartons and its
-- printed line amounts cannot be recomputed from the rounded unit price, so a line keeps what CBL printed:
--   quantity / unit_price stay what the system works with (pieces), the invoiced_* columns keep CBL's own figures,
--   and invoiced_amount (when set) is the exact line amount that counts towards what is owed to CBL.
ALTER TABLE purchase_invoice_items
    ADD COLUMN invoiced_quantity DECIMAL(15,2) NULL,
    ADD COLUMN invoiced_unit VARCHAR(20) NULL,
    ADD COLUMN invoiced_unit_price DECIMAL(15,4) NULL,
    ADD COLUMN invoiced_amount DECIMAL(15,2) NULL;

ALTER TABLE purchase_invoices
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
