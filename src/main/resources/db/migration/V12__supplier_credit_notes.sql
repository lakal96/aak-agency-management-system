-- Credit notes the supplier sends (for example the monthly discount notes): a DRAFT until someone checks it and approves
-- it; an APPROVED note reduces what is owed to the supplier.
CREATE TABLE supplier_credit_notes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  document_number VARCHAR(100) NOT NULL,
  issuer_name VARCHAR(200) NOT NULL,
  note_date DATE NOT NULL,
  ref_invoice_number VARCHAR(100) NULL,
  ref_invoice_date DATE NULL,
  amount DECIMAL(15,2) NOT NULL,
  remarks VARCHAR(500) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
  file_original_name VARCHAR(255) NULL,
  file_stored_name VARCHAR(255) NULL,
  created_at DATETIME(6) NOT NULL,
  approved_by VARCHAR(100) NULL,
  approved_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_supplier_credit_note_number (document_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
