-- Which sales rep wrote the bill. A rep may bill any shop, not only the shops assigned to them, so a rep must
-- still see (and be counted for) the bills they made for another rep's shop. NULL = made by the office/owner:
-- the shop's own rep then sees it, as before.
ALTER TABLE sales_invoices
    ADD COLUMN sales_rep_employee_id BIGINT NULL AFTER delivery_status;

CREATE INDEX idx_sales_invoices_sales_rep ON sales_invoices (sales_rep_employee_id);
