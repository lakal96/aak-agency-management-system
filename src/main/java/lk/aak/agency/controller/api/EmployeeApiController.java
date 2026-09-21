package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.EmployeeRequest;
import lk.aak.agency.dto.api.EmployeeResponse;
import lk.aak.agency.model.Employee;
import lk.aak.agency.service.EmployeeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeApiController {

    private final EmployeeService employeeService;

    public EmployeeApiController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @GetMapping
    public java.util.List<EmployeeResponse> list() {
        return employeeService.getAllEmployees().stream().map(EmployeeResponse::new).toList();
    }

    @GetMapping("/{id}")
    public EmployeeResponse getById(@PathVariable Long id) {
        return employeeService.getEmployeeById(id)
                .map(EmployeeResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Employee not found."));
    }

    @PostMapping
    public ResponseEntity<EmployeeResponse> create(@Valid @RequestBody EmployeeRequest request) {
        Employee employee = new Employee();
        applyRequest(employee, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new EmployeeResponse(employeeService.saveEmployee(employee)));
    }

    @PutMapping("/{id}")
    public EmployeeResponse update(@PathVariable Long id, @Valid @RequestBody EmployeeRequest request) {
        Employee employee = employeeService.getEmployeeById(id)
                .orElseThrow(() -> new NoSuchElementException("Employee not found."));
        applyRequest(employee, request);
        return new EmployeeResponse(employeeService.saveEmployee(employee));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (employeeService.getEmployeeById(id).isEmpty()) {
            throw new NoSuchElementException("Employee not found.");
        }
        employeeService.deleteEmployee(id);
        return ResponseEntity.noContent().build();
    }

    private void applyRequest(Employee employee, EmployeeRequest request) {
        employee.setFullName(request.getFullName());
        employee.setDesignation(request.getDesignation());
        employee.setBaseSalary(request.getBaseSalary());
        employee.setPhone(request.getPhone());
        employee.setAddress(request.getAddress());
        employee.setNicNumber(request.getNicNumber());
        // Only overwrite the join date if the request actually supplies one - the frontend's
        // edit form doesn't collect it, so omitting this guard silently wiped it on every edit.
        if (request.getJoinDate() != null) {
            employee.setJoinDate(request.getJoinDate());
        }
        employee.setStatus(request.getStatus());
        employee.setSystemUsername(request.getSystemUsername());
        employee.setBiometricDeviceUserId(request.getBiometricDeviceUserId());
        employee.setNotes(request.getNotes());
    }
}
