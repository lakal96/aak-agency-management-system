-- Control tasks: the daily, weekly and monthly to-do list. A task is "done" when a person confirms it was done in Connect Plus
-- (shared office login, so the person writes their own name). Open and overdue tasks are worked out, not stored.
CREATE TABLE control_task_done (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    control_date DATE         NOT NULL,
    task_code    VARCHAR(40)  NOT NULL,
    vehicle_code VARCHAR(60)  NOT NULL DEFAULT '',
    status       VARCHAR(10)  NOT NULL,
    done_by      VARCHAR(100) NOT NULL,
    login        VARCHAR(100) NOT NULL,
    done_at      DATETIME(6)  NOT NULL,
    note         VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_control_task_done (control_date, task_code, vehicle_code)
);

-- Lines typed into the lists that go into Connect Plus (load, unload, damaged, cancelled bills, cheques, old credit, claims, weekly lists).
CREATE TABLE control_entry (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    control_date DATE          NOT NULL,
    vehicle_code VARCHAR(60)   NOT NULL DEFAULT '',
    kind         VARCHAR(30)   NOT NULL,
    line_no      INT           NOT NULL,
    col_a        VARCHAR(200)  NULL,
    col_b        VARCHAR(200)  NULL,
    qty          INT           NULL,
    amount       DECIMAL(15,2) NULL,
    PRIMARY KEY (id),
    KEY idx_control_entry_day (control_date, kind, vehicle_code)
);

-- Which lorries are in use. A lorry with no row is in use; the owner can switch one off (it then has no tasks).
CREATE TABLE control_lorry_setting (
    vehicle_code VARCHAR(60) NOT NULL,
    active       BIT(1)      NOT NULL,
    changed_by   VARCHAR(100) NULL,
    changed_at   DATETIME(6) NULL,
    PRIMARY KEY (vehicle_code)
);
