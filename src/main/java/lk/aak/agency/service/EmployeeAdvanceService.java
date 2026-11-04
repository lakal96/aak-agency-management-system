package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.model.EmployeeAdvance;
import lk.aak.agency.repository.EmployeeAdvanceRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.util.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class EmployeeAdvanceService {

    private final EmployeeAdvanceRepository employeeAdvanceRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditLogService auditLogService;

    public EmployeeAdvanceService(
            EmployeeAdvanceRepository employeeAdvanceRepository,
            EmployeeRepository employeeRepository,
            AuditLogService auditLogService) {

        this.auditLogService = auditLogService;
        this.employeeAdvanceRepository = employeeAdvanceRepository;
        this.employeeRepository = employeeRepository;
    }

    public List<EmployeeAdvance> getAllAdvances() {
        return employeeAdvanceRepository.findAllByOrderByAdvanceDateDesc();
    }

    public List<EmployeeAdvance> getAdvancesForEmployee(Long employeeId) {
        return employeeAdvanceRepository.findByEmployeeIdOrderByAdvanceDateDesc(employeeId);
    }

    public List<EmployeeAdvance> getUnsettledAdvances(Long employeeId) {
        return employeeAdvanceRepository.findByEmployeeIdAndSettledFalseOrderByAdvanceDateAsc(employeeId);
    }

    public BigDecimal getUnsettledTotal(Long employeeId) {
        return getUnsettledAdvances(employeeId).stream()
                .map(EmployeeAdvance::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public EmployeeAdvance giveAdvance(Long employeeId, BigDecimal amount, LocalDate advanceDate, String notes) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Selected employee was not found."));

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Advance amount must be greater than zero.");
        }

        EmployeeAdvance advance = new EmployeeAdvance();
        advance.setEmployeeId(employeeId);
        advance.setEmployeeName(employee.getFullName());
        advance.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        advance.setAdvanceDate(advanceDate != null ? advanceDate : BusinessTime.today());
        advance.setNotes(notes);

        EmployeeAdvance saved = employeeAdvanceRepository.save(advance);

        auditLogService.record(
                "ADVANCE_GIVEN", "EmployeeAdvance", saved.getId(),
                "Gave advance of Rs. " + saved.getAmount().toPlainString() + " to " + employee.getFullName()
        );

        return saved;
    }

    @Transactional
    public EmployeeAdvance updateAdvance(Long id, BigDecimal amount, LocalDate advanceDate, String notes) {

        EmployeeAdvance advance = employeeAdvanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Advance not found."));

        if (advance.isSettled()) {
            throw new IllegalArgumentException("This advance has already been settled in a salary payment and cannot be edited.");
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Advance amount must be greater than zero.");
        }

        BigDecimal previousAmount = advance.getAmount();

        advance.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
        advance.setAdvanceDate(advanceDate != null ? advanceDate : advance.getAdvanceDate());
        advance.setNotes(notes);

        EmployeeAdvance saved = employeeAdvanceRepository.save(advance);

        auditLogService.record(
                "ADVANCE_UPDATED", "EmployeeAdvance", saved.getId(),
                "Changed advance of " + saved.getEmployeeName() + " from Rs. "
                        + (previousAmount == null ? "?" : previousAmount.toPlainString())
                        + " to Rs. " + saved.getAmount().toPlainString()
        );

        return saved;
    }

    @Transactional
    public void deleteAdvance(Long id) {

        EmployeeAdvance advance = employeeAdvanceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Advance not found."));

        if (advance.isSettled()) {
            throw new IllegalArgumentException("This advance has already been settled in a salary payment and cannot be deleted.");
        }

        employeeAdvanceRepository.deleteById(id);

        auditLogService.record(
                "ADVANCE_DELETED", "EmployeeAdvance", id,
                "Deleted advance of Rs. " + advance.getAmount().toPlainString() + " given to "
                        + advance.getEmployeeName()
        );
    }
}

