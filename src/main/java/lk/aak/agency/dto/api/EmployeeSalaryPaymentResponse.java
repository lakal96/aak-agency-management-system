package lk.aak.agency.dto.api;

import lk.aak.agency.model.EmployeeSalaryPayment;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EmployeeSalaryPaymentResponse {

    private final Long id;
    private final Long employeeId;
    private final String employeeName;
    private final String payPeriodMonth;
    private final BigDecimal grossSalary;
    private final BigDecimal advanceDeduction;
    private final BigDecimal netPaid;
    private final LocalDate paymentDate;
    private final String notes;

    public EmployeeSalaryPaymentResponse(EmployeeSalaryPayment payment) {
        this.id = payment.getId();
        this.employeeId = payment.getEmployeeId();
        this.employeeName = payment.getEmployeeName();
        this.payPeriodMonth = payment.getPayPeriodMonth();
        this.grossSalary = payment.getGrossSalary();
        this.advanceDeduction = payment.getAdvanceDeduction();
        this.netPaid = payment.getNetPaid();
        this.paymentDate = payment.getPaymentDate();
        this.notes = payment.getNotes();
    }

    public Long getId() {
        return id;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public String getPayPeriodMonth() {
        return payPeriodMonth;
    }

    public BigDecimal getGrossSalary() {
        return grossSalary;
    }

    public BigDecimal getAdvanceDeduction() {
        return advanceDeduction;
    }

    public BigDecimal getNetPaid() {
        return netPaid;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public String getNotes() {
        return notes;
    }
}
