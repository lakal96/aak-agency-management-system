ALTER TABLE customers
    ADD COLUMN assigned_employee_id BIGINT NULL AFTER assigned_employee;

ALTER TABLE customers
    ADD CONSTRAINT fk_customers_assigned_employee
    FOREIGN KEY (assigned_employee_id) REFERENCES employees (id) ON DELETE SET NULL;
