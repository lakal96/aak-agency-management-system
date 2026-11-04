package lk.aak.agency.service;

import lk.aak.agency.model.Employee;
import lk.aak.agency.model.EmployeeAdvance;
import lk.aak.agency.model.EmployeeSalaryPayment;
import lk.aak.agency.repository.EmployeeAdvanceRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.EmployeeSalaryPaymentRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeSalaryServiceTest {

    @Mock
    private EmployeeSalaryPaymentRepository employeeSalaryPaymentRepository;
    @Mock
    private EmployeeAdvanceRepository employeeAdvanceRepository;
    @Mock
    private EmployeeRepository employeeRepository;
    @Mock
    private AuditLogService auditLogService;

    private EmployeeSalaryService newService() {
        return new EmployeeSalaryService(
                employeeSalaryPaymentRepository, employeeAdvanceRepository, employeeRepository, auditLogService
        );
    }

    private Employee employee() {
        Employee employee = new Employee();
        employee.setId(1L);
        employee.setFullName("Nimal Rep");
        return employee;
    }

    @Test
    void recordSalaryPayment_deductsOnlySelectedAdvancesAndMarksThemSettled() {

        EmployeeSalaryService service = newService();

        EmployeeAdvance advance1 = new EmployeeAdvance();
        advance1.setId(10L);
        advance1.setAmount(new BigDecimal("500"));
        advance1.setAdvanceDate(LocalDate.of(2026, 9, 5));

        EmployeeAdvance advance2 = new EmployeeAdvance();
        advance2.setId(11L);
        advance2.setAmount(new BigDecimal("300"));
        advance2.setAdvanceDate(LocalDate.of(2026, 9, 10));

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeAdvanceRepository.findByEmployeeIdAndSettledFalseOrderByAdvanceDateAsc(1L))
                .thenReturn(List.of(advance1, advance2));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryPayment payment = service.recordSalaryPayment(
                1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of(10L)
        );

        assertThat(payment.getAdvanceDeduction()).isEqualByComparingTo("500");
        assertThat(payment.getNetPaid()).isEqualByComparingTo("29500");

        verify(employeeAdvanceRepository, times(1)).save(advance1);
        verify(employeeAdvanceRepository, never()).save(advance2);
        assertThat(advance1.isSettled()).isTrue();
        assertThat(advance2.isSettled()).isFalse();
    }

    @Test
    void recordSalaryPayment_carriesOverAdvanceThatWouldExceedGrossSalary() {

        EmployeeSalaryService service = newService();

        EmployeeAdvance advance = new EmployeeAdvance();
        advance.setId(10L);
        advance.setAmount(new BigDecimal("50000"));
        advance.setAdvanceDate(LocalDate.of(2026, 9, 5));

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeAdvanceRepository.findByEmployeeIdAndSettledFalseOrderByAdvanceDateAsc(1L))
                .thenReturn(List.of(advance));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryPayment payment = service.recordSalaryPayment(
                1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of(10L)
        );

        assertThat(payment.getAdvanceDeduction()).isEqualByComparingTo("0");
        assertThat(payment.getNetPaid()).isEqualByComparingTo("30000");
        assertThat(advance.isSettled()).isFalse();
        verify(employeeAdvanceRepository, never()).save(advance);
    }

    @Test
    void recordSalaryPayment_ignoresAdvanceDatedAfterThePayPeriod() {

        EmployeeSalaryService service = newService();

        EmployeeAdvance advance = new EmployeeAdvance();
        advance.setId(10L);
        advance.setAmount(new BigDecimal("500"));
        advance.setAdvanceDate(LocalDate.of(2026, 10, 2));

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeAdvanceRepository.findByEmployeeIdAndSettledFalseOrderByAdvanceDateAsc(1L))
                .thenReturn(List.of(advance));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryPayment payment = service.recordSalaryPayment(
                1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of(10L)
        );

        assertThat(payment.getAdvanceDeduction()).isEqualByComparingTo("0");
        assertThat(advance.isSettled()).isFalse();
    }

    @Test
    void recordSalaryPayment_rejectsNonPositiveGrossSalary() {

        EmployeeSalaryService service = newService();
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));

        assertThatThrownBy(() -> service.recordSalaryPayment(
                1L, "2026-09", BigDecimal.ZERO, BusinessTime.today(), null, List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordSalaryPayment_secondPaymentForTheSameMonthIsRefusedWithAClearMessage() {

        EmployeeSalaryService service = newService();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeSalaryPaymentRepository.existsByEmployeeIdAndPayPeriodMonth(1L, "2026-09")).thenReturn(true);

        assertThatThrownBy(() -> service.recordSalaryPayment(
                1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Salary for September 2026 was already recorded for Nimal Rep.");

        verify(employeeSalaryPaymentRepository, never()).save(any(EmployeeSalaryPayment.class));
    }

    @Test
    void recordSalaryPayment_locksTheEmployeeBeforeCheckingForAnEarlierPayment() {

        EmployeeSalaryService service = newService();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordSalaryPayment(1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(employeeRepository, employeeSalaryPaymentRepository);
        order.verify(employeeRepository).lockById(1L);
        order.verify(employeeSalaryPaymentRepository).existsByEmployeeIdAndPayPeriodMonth(1L, "2026-09");
        order.verify(employeeSalaryPaymentRepository).save(any(EmployeeSalaryPayment.class));
    }

    @Test
    void recordSalaryPayment_rejectsAMonthAfterTheCurrentMonth_butAllowsTheCurrentOne() {

        EmployeeSalaryService service = newService();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        java.time.YearMonth current = java.time.YearMonth.from(BusinessTime.today());

        assertThatThrownBy(() -> service.recordSalaryPayment(
                1L, current.plusMonths(1).toString(), new BigDecimal("30000"), BusinessTime.today(), null, List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future month");

        assertThatThrownBy(() -> service.recordSalaryPayment(
                1L, "2099-01", new BigDecimal("30000"), BusinessTime.today(), null, List.of()
        )).isInstanceOf(IllegalArgumentException.class);

        EmployeeSalaryPayment thisMonth = service.recordSalaryPayment(
                1L, current.toString(), new BigDecimal("30000"), BusinessTime.today(), null, List.of());
        assertThat(thisMonth.getPayPeriodMonth()).isEqualTo(current.toString());
    }

    @Test
    void recordSalaryPayment_roundsTheSalaryToTwoDecimals() {

        EmployeeSalaryService service = newService();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeSalaryPayment payment = service.recordSalaryPayment(
                1L, "2026-09", new BigDecimal("30000.456"), BusinessTime.today(), null, List.of());

        assertThat(payment.getGrossSalary()).isEqualByComparingTo("30000.46");
        assertThat(payment.getNetPaid()).isEqualByComparingTo("30000.46");

        assertThatThrownBy(() -> service.recordSalaryPayment(
                1L, "2026-08", new BigDecimal("0.004"), BusinessTime.today(), null, List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordSalaryPayment_isAuditedWithTheAmounts() {

        EmployeeSalaryService service = newService();

        when(employeeRepository.findById(1L)).thenReturn(Optional.of(employee()));
        when(employeeSalaryPaymentRepository.save(any(EmployeeSalaryPayment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.recordSalaryPayment(1L, "2026-09", new BigDecimal("30000"), BusinessTime.today(), null, List.of());

        verify(auditLogService).record(
                org.mockito.ArgumentMatchers.eq("SALARY_PAID"),
                org.mockito.ArgumentMatchers.eq("EmployeeSalaryPayment"),
                any(),
                org.mockito.ArgumentMatchers.contains("30000"));
    }
}
