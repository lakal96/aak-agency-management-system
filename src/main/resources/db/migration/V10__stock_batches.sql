-- One row per received purchase line ("batch"): what it cost per piece and the selling price that cost implies
-- (cost + margin, rounded). Phase 1 only RECORDS this; bills still use the product's single selling price.
CREATE TABLE stock_batches (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  purchase_invoice_id BIGINT NOT NULL,
  purchase_invoice_item_id BIGINT NOT NULL,
  document_number VARCHAR(255) NOT NULL,
  batch_date DATE NOT NULL,
  received_quantity DECIMAL(15,2) NOT NULL,
  unit_cost DECIMAL(15,4) NOT NULL,
  margin_percent DECIMAL(5,2) NOT NULL,
  exact_price DECIMAL(15,2) NOT NULL,
  suggested_price DECIMAL(12,2) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_stock_batch_item (purchase_invoice_item_id),
  KEY idx_stock_batch_product (product_id, batch_date, id),
  CONSTRAINT fk_stock_batch_product FOREIGN KEY (product_id) REFERENCES products (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- purchase lines that were completed before this existed
INSERT INTO stock_batches (product_id, purchase_invoice_id, purchase_invoice_item_id, document_number, batch_date,
                           received_quantity, unit_cost, margin_percent, exact_price, suggested_price, created_at)
SELECT i.product_id, p.id, i.id, p.document_number, COALESCE(p.delivery_date, p.invoice_date),
       i.purchased_quantity, ROUND(i.amount_including_vat / i.purchased_quantity, 4), 10.00,
       ROUND(ROUND(i.amount_including_vat / i.purchased_quantity, 4) * 1.10, 2),
       ROUND(ROUND(i.amount_including_vat / i.purchased_quantity, 4) * 1.10, 0),
       UTC_TIMESTAMP(6)
FROM purchase_invoice_items i
JOIN purchase_invoices p ON p.id = i.purchase_invoice_id
WHERE p.status = 'COMPLETED' AND i.purchased_quantity > 0;
