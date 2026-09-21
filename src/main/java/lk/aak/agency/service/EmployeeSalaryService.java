package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.model.EmployeeAdvance;
import lk.aak.agency.model.EmployeeSalaryPayment;
import lk.aak.agency.repository.EmployeeAdvanceRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.EmployeeSalaryPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class EmployeeSalaryService {

    private final EmployeeSalaryPaymentRepository employeeSalaryPaymentRepository;
    private final EmployeeAdvanceRepository employeeAdvanceRepository;
    private final EmployeeRepository employeeRepository;

    public EmployeeSalaryService(
            EmployeeSalaryPaymentRepository employeeSalaryPaymentRepository,
            EmployeeAdvanceRepository employeeAdvanceRepository,
            EmployeeRepository employeeRepository) {

        this.employeeSalaryPaymentRepository = employeeSalaryPaymentRepository;
        this.employeeAdvanceRepository = employeeAdvanceRepository;
        this.employeeRepository = employeeRepository;
    }

    public List<EmployeeSalaryPayment> getAllPayments() {
        return employeeSalaryPaymentRepository.findAllByOrderByPaymentDateDesc();
    }

    public List<EmployeeSalaryPayment> getPaymentsForEmployee(Long employeeId) {
        return employeeSalaryPaymentRepository.findByEmployeeIdOrderByPaymentDateDesc(employeeId);
    }

    /**
     * Records a salary payment for one employee/month. The advance deduction is the sum of
     * the specific outstanding advances the office chooses to settle with this payment - not
     * a free-typed number - so it can never drift from what's actually still owed.
     */
    @Transactional
    public EmployeeSalaryPayment recordSalaryPayment(
            Long employeeId,
            String payPeriodMonth,
            BigDecimal grossSalary,
            LocalDate paymentDate,
            String notes,
            List<Long> advanceIdsToSettle) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Selected employee was not found."));

        if (grossSalary == null || grossSalary.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Gross salary must be greater than zero.");
        }

        if (payPeriodMonth == null || payPeriodMonth.isBlank()) {
            throw new IllegalArgumentException("Please select the pay period month.");
        }

        LocalDate payPeriodCutoff;
        try {
            payPeriodCutoff = YearMonth.parse(payPeriodMonth).atEndOfMonth();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Pay period month must be in YYYY-MM format.");
        }

        List<EmployeeAdvance> unsettledAdvances =
                employeeAdvanceRepository.findByEmployeeIdAndSettledFalseOrderByAdvanceDateAsc(employeeId);

        // An advance only counts against a pay period if it was taken in that month or earlier -
        // one taken after this period's month must wait for its own (or a later unpaid) period.
        // Among the eligible advances, settle oldest-first, but never let the deduction exceed the
        // gross salary being paid this run - anything that doesn't fit stays unsettled and carries
        // over to a future salary payment instead of blocking this one.
        BigDecimal advanceDeduction = BigDecimal.ZERO;
        List<EmployeeAdvance> advancesToSettle = new java.util.ArrayList<>();

        if (advanceIdsToSettle != null) {
            for (EmployeeAdvance advance : unsettledAdvances) {
                if (!advanceIdsToSettle.contains(advance.getId())) {
                    continue;
                }
                if (advance.getAdvanceDate().isAfter(payPeriodCutoff)) {
                    continue;
                }
                BigDecimal candidateTotal = advanceDeduction.add(advance.getAmount());
                if (candidateTotal.compareTo(grossSalary) > 0) {
                    continue;
                }
                advancesToSettle.add(advance);
                advanceDeduction = candidateTotal;
            }
        }

        EmployeeSalaryPayment payment = new EmployeeSalaryPayment();
        payment.setEmployeeId(employeeId);
        payment.setEmployeeName(employee.getFullName());
        payment.setPayPeriodMonth(payPeriodMonth);
        payment.setGrossSalary(grossSalary);
        payment.setAdvanceDeduction(advanceDeduction);
        payment.setNetPaid(grossSalary.subtract(advanceDeduction));
        payment.setPaymentDate(paymentDate != null ? paymentDate : LocalDate.now());
        payment.setNotes(notes);

        EmployeeSalaryPayment savedPayment = employeeSalaryPaymentRepository.save(payment);

        for (EmployeeAdvance advance : advancesToSettle) {
            advance.setSettled(true);
            employeeAdvanceRepository.save(advance);
        }

        return savedPayment;
    }
}
