-- Every CBL e-mail the system receives from the Gmail script: what was imported, what was empty, and what NEEDS ATTENTION
-- (refused PDFs are kept so a person can open them from the system, and e-mails the script could not even read are listed too).
CREATE TABLE cbl_email_intake (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    received_at         DATETIME(6)  NOT NULL,
    last_seen_at        DATETIME(6)  NOT NULL,
    attempts            INT          NOT NULL DEFAULT 1,
    kind                VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    file_name           VARCHAR(255) NULL,
    email_subject       VARCHAR(300) NULL,
    email_sender        VARCHAR(200) NULL,
    email_sent_at       DATETIME(6)  NULL,
    message_key         VARCHAR(100) NULL,
    file_hash           VARCHAR(64)  NULL,
    document_number     VARCHAR(120) NULL,
    summary             VARCHAR(500) NULL,
    problems            TEXT         NULL,
    purchase_invoice_id BIGINT       NULL,
    stored_file_name    VARCHAR(255) NULL,
    file_size           BIGINT       NULL,
    resolved            BIT(1)       NOT NULL DEFAULT 0,
    resolved_by         VARCHAR(100) NULL,
    resolved_at         DATETIME(6)  NULL,
    resolution_note     VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cbl_email_message (message_key),
    KEY idx_cbl_email_hash (file_hash),
    KEY idx_cbl_email_status (status, resolved),
    KEY idx_cbl_email_received (received_at)
);

-- When an outside helper last said "I checked": the Gmail script reports in at every run, so a stopped script shows up.
CREATE TABLE integration_heartbeat (
    name         VARCHAR(60)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    detail       VARCHAR(300) NULL,
    PRIMARY KEY (name)
);
