-- Base monthly salary stored on the employee record, so office staff no longer have to
-- retype the gross salary from memory every time they record a payment (see EmployeeSalaryService).
ALTER TABLE `employees`
  ADD COLUMN `base_salary` decimal(12,2) NULL AFTER `designation`;
