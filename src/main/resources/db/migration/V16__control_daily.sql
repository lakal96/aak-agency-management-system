-- Control module: the figures from the paper control book (per lorry per day), the CBL reports uploaded to check them
-- against, and the owner's acknowledgements of checks that did not agree. Nothing here changes stock, bills or payments.

-- One paper file per lorry per day (sheets L1/L2 or, for the van-sales lorry, VS1/VS2).
CREATE TABLE control_lorry_day (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    control_date         DATE          NOT NULL,
    vehicle_code         VARCHAR(60)   NOT NULL,
    rep_name             VARCHAR(100)  NULL,
    driver_name          VARCHAR(100)  NULL,
    cash_counted_rep     DECIMAL(15,2) NULL,
    cash_counted_driver  DECIMAL(15,2) NULL,
    tablet_stock_morning VARCHAR(3)    NULL,
    tablet_stock_after   VARCHAR(3)    NULL,
    morning_stock_ok     VARCHAR(3)    NULL,
    updated_by           VARCHAR(100)  NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_control_lorry_day (control_date, vehicle_code)
);

CREATE TABLE control_bill_line (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    lorry_day_id  BIGINT        NOT NULL,
    line_no       INT           NOT NULL,
    bill_no       VARCHAR(40)   NOT NULL,
    bill_value    DECIMAL(15,2) NULL,
    cash_rep      DECIMAL(15,2) NULL,
    cash_driver   DECIMAL(15,2) NULL,
    cheque_amount DECIMAL(15,2) NULL,
    cheque_no     VARCHAR(40)   NULL,
    credit_amount DECIMAL(15,2) NULL,
    PRIMARY KEY (id),
    KEY idx_control_bill_day (lorry_day_id),
    CONSTRAINT fk_control_bill_day FOREIGN KEY (lorry_day_id) REFERENCES control_lorry_day (id) ON DELETE CASCADE
);

CREATE TABLE control_stock_line (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    lorry_day_id  BIGINT       NOT NULL,
    line_no       INT          NOT NULL,
    product_name  VARCHAR(200) NOT NULL,
    counted       INT          NULL,
    load_tomorrow INT          NULL,
    PRIMARY KEY (id),
    KEY idx_control_stock_day (lorry_day_id),
    CONSTRAINT fk_control_stock_day FOREIGN KEY (lorry_day_id) REFERENCES control_lorry_day (id) ON DELETE CASCADE
);

-- The safe and the day's last steps: one per day, for all lorries together (sheets DC and CC).
CREATE TABLE control_day_book (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    control_date  DATE          NOT NULL,
    safe_opening  DECIMAL(15,2) NULL,
    banked        DECIMAL(15,2) NULL,
    paid_out      DECIMAL(15,2) NULL,
    safe_counted  DECIMAL(15,2) NULL,
    day_end_done  BIT(1)        NOT NULL DEFAULT 0,
    signed_by     VARCHAR(100)  NULL,
    signed_at     DATETIME(6)   NULL,
    updated_by    VARCHAR(100)  NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_control_day_book (control_date)
);

-- A CBL report uploaded for a lorry and a day. The original file is kept; the checks read it again each time.
CREATE TABLE control_upload (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    control_date  DATE         NOT NULL,
    vehicle_code  VARCHAR(60)  NOT NULL,
    kind          VARCHAR(30)  NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    stored_name   VARCHAR(255) NOT NULL,
    file_hash     VARCHAR(64)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    message       VARCHAR(500) NULL,
    rows_read     INT          NULL,
    uploaded_by   VARCHAR(100) NULL,
    uploaded_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_control_upload_day (control_date, vehicle_code, kind)
);

-- "I looked at this check and accept it": a failing check is never hidden, only acknowledged with a note and a name.
CREATE TABLE control_closure (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    control_date  DATE         NOT NULL,
    vehicle_code  VARCHAR(60)  NOT NULL,
    check_code    VARCHAR(40)  NOT NULL,
    note          VARCHAR(500) NOT NULL,
    closed_by     VARCHAR(100) NOT NULL,
    closed_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_control_closure (control_date, vehicle_code, check_code)
);
