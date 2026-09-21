package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.repository.EmployeeAdvanceRepository;
import lk.aak.agency.repository.EmployeeAttendanceRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.EmployeeSalaryPaymentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeAdvanceRepository employeeAdvanceRepository;
    private final EmployeeSalaryPaymentRepository employeeSalaryPaymentRepository;
    private final EmployeeAttendanceRepository employeeAttendanceRepository;
    private final AuditLogService auditLogService;

    public EmployeeService(
            EmployeeRepository employeeRepository,
            EmployeeAdvanceRepository employeeAdvanceRepository,
            EmployeeSalaryPaymentRepository employeeSalaryPaymentRepository,
            EmployeeAttendanceRepository employeeAttendanceRepository,
            AuditLogService auditLogService) {

        this.employeeRepository = employeeRepository;
        this.employeeAdvanceRepository = employeeAdvanceRepository;
        this.employeeSalaryPaymentRepository = employeeSalaryPaymentRepository;
        this.employeeAttendanceRepository = employeeAttendanceRepository;
        this.auditLogService = auditLogService;
    }

    public List<Employee> getAllEmployees() {
        return employeeRepository.findAll(Sort.by(Sort.Direction.ASC, "fullName"));
    }

    public List<Employee> getActiveDrivers() {
        return employeeRepository.findByDesignationAndStatus("DRIVER", "ACTIVE");
    }

    public List<Employee> getActiveHelpers() {
        return employeeRepository.findByDesignationAndStatus("HELPER", "ACTIVE");
    }

    public List<Employee> getActiveFieldCollectors() {
        List<Employee> fieldCollectors = new ArrayList<>(
                employeeRepository.findByDesignationAndStatus("SALES_REP", "ACTIVE")
        );
        fieldCollectors.addAll(getActiveDrivers());
        return fieldCollectors;
    }

    public Optional<Employee> getEmployeeById(Long id) {
        return employeeRepository.findById(id);
    }

    public Employee saveEmployee(Employee employee) {
        if (employee.getId() == null
                && (employee.getEmployeeCode() == null || employee.getEmployeeCode().isBlank())) {

            employee.setEmployeeCode(generateEmployeeCode());
        }

        return employeeRepository.save(employee);
    }

    private String generateEmployeeCode() {
        long nextNumber = employeeRepository
                .findTopByOrderByIdDesc()
                .map(employee -> employee.getId() + 1)
                .orElse(1L);

        return String.format("EMP-%04d", nextNumber);
    }

    public void deleteEmployee(Long id) {

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Employee not found."));

        boolean hasHistory =
                !employeeAdvanceRepository.findByEmployeeIdOrderByAdvanceDateDesc(id).isEmpty()
                        || !employeeSalaryPaymentRepository.findByEmployeeIdOrderByPaymentDateDesc(id).isEmpty()
                        || !employeeAttendanceRepository.findByEmployeeIdOrderByAttendanceDateDesc(id).isEmpty();

        if (hasHistory) {
            throw new IllegalArgumentException(
                    "This employee has advances, salary payments or attendance history and cannot be deleted. "
                            + "Set their status to INACTIVE instead."
            );
        }

        employeeRepository.deleteById(id);

        auditLogService.record(
                "EMPLOYEE_DELETED", "Employee", id,
                "Deleted employee \"" + employee.getFullName() + "\""
        );
    }
}
