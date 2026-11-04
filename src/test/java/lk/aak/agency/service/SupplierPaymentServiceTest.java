package lk.aak.agency.service;

import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.SupplierPayment;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SupplierPaymentRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierPaymentServiceTest {

    @Mock
    private SupplierPaymentRepository supplierPaymentRepository;
    @Mock
    private PurchaseInvoiceRepository purchaseInvoiceRepository;
    @Mock
    private RowLockService rowLockService;
    @Mock
    private AuditLogService auditLogService;

    private SupplierPaymentService newService() {
        return new SupplierPaymentService(
                supplierPaymentRepository, purchaseInvoiceRepository, rowLockService, auditLogService);
    }

    private PurchaseInvoice completedInvoice(Long id, BigDecimal totalAmount) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setId(id);
        invoice.setDocumentNumber("CBL-" + id);
        invoice.setStatus("COMPLETED");
        invoice.setTotalAmount(totalAmount);
        return invoice;
    }

    @Test
    void getSupplierBalanceSummary_onlyIncludesCompletedInvoicesWithOutstandingBalance() {

        SupplierPaymentService service = newService();

        PurchaseInvoice owed = completedInvoice(1L, new BigDecimal("1000"));
        PurchaseInvoice fullyPaid = completedInvoice(2L, new BigDecimal("500"));

        PurchaseInvoice draft = new PurchaseInvoice();
        draft.setId(3L);
        draft.setStatus("DRAFT");
        draft.setTotalAmount(new BigDecimal("999"));

        when(purchaseInvoiceRepository.findAllByOrderByInvoiceDateDesc())
                .thenReturn(List.of(owed, fullyPaid, draft));

        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenReturn(new BigDecimal("400"));
        when(supplierPaymentRepository.calculatePaidAmount(2L)).thenReturn(new BigDecimal("500"));

        SupplierPaymentService.SupplierBalanceSummary summary = service.getSupplierBalanceSummary();

        assertThat(summary.rows()).hasSize(1);
        assertThat(summary.rows().get(0).invoice().getId()).isEqualTo(1L);
        assertThat(summary.rows().get(0).outstandingBalance()).isEqualByComparingTo("600");
        assertThat(summary.totalOwed()).isEqualByComparingTo("600");
    }

    @Test
    void recordPayment_rejectsAmountGreaterThanOutstandingBalance() {

        SupplierPaymentService service = newService();

        PurchaseInvoice invoice = completedInvoice(1L, new BigDecimal("1000"));

        when(purchaseInvoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenReturn(BigDecimal.ZERO);

        SupplierPayment payment = new SupplierPayment();
        payment.setPurchaseInvoiceId(1L);
        payment.setAmount(new BigDecimal("1500"));
        payment.setPaymentDate(BusinessTime.today());

        assertThatThrownBy(() -> service.recordPayment(payment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outstanding balance");
    }

    @Test
    void recordPayment_locksTheInvoiceBeforeItReadsTheBalance() {

        SupplierPaymentService service = newService();

        PurchaseInvoice invoice = completedInvoice(1L, new BigDecimal("1000"));
        when(purchaseInvoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenReturn(BigDecimal.ZERO);
        when(supplierPaymentRepository.save(any(SupplierPayment.class))).thenAnswer(call -> call.getArgument(0));

        SupplierPayment payment = new SupplierPayment();
        payment.setPurchaseInvoiceId(1L);
        payment.setAmount(new BigDecimal("1000"));
        payment.setPaymentDate(BusinessTime.today());

        service.recordPayment(payment);

        InOrder order = inOrder(rowLockService, supplierPaymentRepository);
        order.verify(rowLockService).lockPurchaseInvoice(1L);
        order.verify(supplierPaymentRepository).calculatePaidAmount(1L);
        order.verify(supplierPaymentRepository).save(payment);
    }

    @Test
    void fourPaymentsOfOneThousandOnAThousandInvoice_onlyOneSucceeds() {

        SupplierPaymentService service = newService();

        PurchaseInvoice invoice = completedInvoice(1L, new BigDecimal("1000"));
        when(purchaseInvoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        // What the row lock guarantees: each payment sees everything the earlier ones committed.
        BigDecimal[] paid = {BigDecimal.ZERO};
        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenAnswer(call -> paid[0]);
        when(supplierPaymentRepository.save(any(SupplierPayment.class))).thenAnswer(call -> {
            SupplierPayment saved = call.getArgument(0);
            paid[0] = paid[0].add(saved.getAmount());
            return saved;
        });

        int succeeded = 0;
        for (int i = 0; i < 4; i++) {
            SupplierPayment payment = new SupplierPayment();
            payment.setPurchaseInvoiceId(1L);
            payment.setAmount(new BigDecimal("1000"));
            payment.setPaymentDate(BusinessTime.today());
            try {
                service.recordPayment(payment);
                succeeded++;
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("fully paid");
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(paid[0]).isEqualByComparingTo("1000");
    }

    @Test
    void recordPayment_roundsTheAmountToTwoDecimals() {

        SupplierPaymentService service = newService();

        PurchaseInvoice invoice = completedInvoice(1L, new BigDecimal("1000"));
        when(purchaseInvoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenReturn(BigDecimal.ZERO);
        when(supplierPaymentRepository.save(any(SupplierPayment.class))).thenAnswer(call -> call.getArgument(0));

        SupplierPayment payment = new SupplierPayment();
        payment.setPurchaseInvoiceId(1L);
        payment.setAmount(new BigDecimal("100.005"));
        payment.setPaymentDate(BusinessTime.today());

        assertThat(service.recordPayment(payment).getAmount()).isEqualByComparingTo("100.01");
    }

    @Test
    void anOverpaidInvoiceIsShownSeparately_notHiddenInAZeroBalance() {

        SupplierPaymentService service = newService();

        PurchaseInvoice overpaid = completedInvoice(1L, new BigDecimal("1000"));
        PurchaseInvoice owed = completedInvoice(2L, new BigDecimal("500"));

        when(purchaseInvoiceRepository.findAllByOrderByInvoiceDateDesc()).thenReturn(List.of(overpaid, owed));
        when(supplierPaymentRepository.calculatePaidAmount(1L)).thenReturn(new BigDecimal("4000")); // 4 x 1,000
        when(supplierPaymentRepository.calculatePaidAmount(2L)).thenReturn(new BigDecimal("100"));

        SupplierPaymentService.SupplierBalanceSummary summary = service.getSupplierBalanceSummary();

        assertThat(summary.totalOwed()).isEqualByComparingTo("400");
        assertThat(summary.totalOverpaid()).isEqualByComparingTo("3000");
        assertThat(summary.overpaidRows()).hasSize(1);
        assertThat(summary.overpaidRows().get(0).invoice().getId()).isEqualTo(1L);
        assertThat(summary.overpaidRows().get(0).overpaidAmount()).isEqualByComparingTo("3000");
    }
}
