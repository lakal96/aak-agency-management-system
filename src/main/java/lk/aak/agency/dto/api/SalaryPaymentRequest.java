package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class SalaryPaymentRequest {

    @NotNull(message = "Employee is required.")
    private Long employeeId;

    @NotBlank(message = "Pay period month is required.")
    private String payPeriodMonth;

    @NotNull(message = "Gross salary is required.")
    @DecimalMin(value = "0.01", message = "Gross salary must be greater than zero.")
    private BigDecimal grossSalary;

    private LocalDate paymentDate;
    private String notes;
    private List<Long> advanceIdsToSettle;

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getPayPeriodMonth() {
        return payPeriodMonth;
    }

    public void setPayPeriodMonth(String payPeriodMonth) {
        this.payPeriodMonth = payPeriodMonth;
    }

    public BigDecimal getGrossSalary() {
        return grossSalary;
    }

    public void setGrossSalary(BigDecimal grossSalary) {
        this.grossSalary = grossSalary;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<Long> getAdvanceIdsToSettle() {
        return advanceIdsToSettle;
    }

    public void setAdvanceIdsToSettle(List<Long> advanceIdsToSettle) {
        this.advanceIdsToSettle = advanceIdsToSettle;
    }
}
