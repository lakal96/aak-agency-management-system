package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.EmployeeSalaryPaymentResponse;
import lk.aak.agency.dto.api.SalaryPaymentRequest;
import lk.aak.agency.service.EmployeeSalaryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/salary")
public class EmployeeSalaryApiController {

    private final EmployeeSalaryService employeeSalaryService;

    public EmployeeSalaryApiController(EmployeeSalaryService employeeSalaryService) {
        this.employeeSalaryService = employeeSalaryService;
    }

    @GetMapping
    public List<EmployeeSalaryPaymentResponse> list(@RequestParam(required = false) Long employeeId) {
        var payments = employeeId != null
                ? employeeSalaryService.getPaymentsForEmployee(employeeId)
                : employeeSalaryService.getAllPayments();

        return payments.stream().map(EmployeeSalaryPaymentResponse::new).toList();
    }

    @PostMapping
    public ResponseEntity<EmployeeSalaryPaymentResponse> create(@Valid @RequestBody SalaryPaymentRequest request) {
        var payment = employeeSalaryService.recordSalaryPayment(
                request.getEmployeeId(),
                request.getPayPeriodMonth(),
                request.getGrossSalary(),
                request.getPaymentDate(),
                request.getNotes(),
                request.getAdvanceIdsToSettle()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(new EmployeeSalaryPaymentResponse(payment));
    }
}
