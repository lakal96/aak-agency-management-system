package lk.aak.agency.dto.api;

import lk.aak.agency.model.EmployeeAdvance;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EmployeeAdvanceResponse {

    private final Long id;
    private final Long employeeId;
    private final String employeeName;
    private final LocalDate advanceDate;
    private final BigDecimal amount;
    private final String notes;
    private final boolean settled;

    public EmployeeAdvanceResponse(EmployeeAdvance advance) {
        this.id = advance.getId();
        this.employeeId = advance.getEmployeeId();
        this.employeeName = advance.getEmployeeName();
        this.advanceDate = advance.getAdvanceDate();
        this.amount = advance.getAmount();
        this.notes = advance.getNotes();
        this.settled = advance.isSettled();
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

    public LocalDate getAdvanceDate() {
        return advanceDate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isSettled() {
        return settled;
    }
}
