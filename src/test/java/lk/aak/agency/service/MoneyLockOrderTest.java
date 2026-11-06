package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real row-lock races need MySQL (the integrator re-runs the concurrent probes there). What can be proven here:
 * every path that changes a bill, purchase invoice or payment takes the row lock FIRST, then reads the record
 * (and the balance) it decides on, and refuses when the re-read record is already completed.
 */
@ExtendWith(MockitoExtension.class)
class MoneyLockOrderTest {

    @Mock private SalesInvoiceRepository salesInvoiceRepository;
    @Mock private SalesInvoiceItemRepository salesInvoiceItemRepository;
    @Mock private StockMovementRepository stockMovementRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private RowLockService rowLockService;
    @Mock private PurchaseInvoiceRepository purchaseInvoiceRepository;
    @Mock private PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    @Mock private EmployeeRepository employeeRepository;

    private SalesInvoiceService salesService() {
        return new SalesInvoiceService(
                salesInvoiceRepository, salesInvoiceItemRepository, stockMovementRepository,
                paymentRepository, auditLogService, rowLockService);
    }

    private PurchaseInvoiceService purchaseService() {
        return new PurchaseInvoiceService(
                purchaseInvoiceRepository, purchaseInvoiceItemRepository, stockMovementRepository,
                auditLogService, rowLockService, org.mockito.Mockito.mock(lk.aak.agency.batch.StockBatchService.class));
    }

    private PaymentService paymentService() {
        return new PaymentService(
                paymentRepository, salesInvoiceRepository, employeeRepository, auditLogService, rowLockService);
    }

    private Product product() {
        Product product = new Product();
        product.setId(10L);
        product.setProductName("P");
        product.setUnit("PKT");
        return product;
    }

    private SalesInvoice salesInvoice(String status) {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setCustomerName("Shop");
        customer.setPaymentTermsDays(14);

        SalesInvoice invoice = new SalesInvoice();
        invoice.setId(5L);
        invoice.setInvoiceNumber("INV-5");
        invoice.setInvoiceDate(BusinessTime.today());
        invoice.setCustomer(customer);
        invoice.setSaleType("CREDIT");
        invoice.setStatus(status);
        invoice.setPaymentStatus("UNPAID");
        invoice.setNetAmount(new BigDecimal("1000"));
        return invoice;
    }

    private SalesInvoiceItem salesLine() {
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(product());
        item.setQuantity(new BigDecimal("1"));
        item.setUnitPrice(new BigDecimal("100"));
        return item;
    }

    // ------------------------------------------------------------ sales bill

    @Test
    void editingABill_locksFirst_thenReadsIt_andRefusesWhenItIsNowCompleted() {

        SalesInvoice completedNow = salesInvoice("COMPLETED");
        SalesInvoice staleCopy = salesInvoice("DRAFT");
        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(completedNow));

        assertThatThrownBy(() -> salesService().saveInvoiceWithItems(staleCopy, List.of(salesLine())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed sales invoice cannot be edited");

        InOrder order = inOrder(rowLockService, salesInvoiceRepository);
        order.verify(rowLockService).forget(staleCopy);
        order.verify(rowLockService).lockSalesInvoice(5L);
        order.verify(salesInvoiceRepository).findById(5L);
        verify(salesInvoiceRepository, never()).save(any());
    }

    @Test
    void editingADraft_keepsTheStoredStatus_andCopiesOnlyTheEditableFieldsOntoTheLockedRow() {

        SalesInvoice stored = salesInvoice("DRAFT");
        stored.setDeliveryStatus("PENDING");
        SalesInvoice incoming = salesInvoice("COMPLETED"); // a stale or hostile status must not leak in
        incoming.setNotes("new note");
        incoming.setStatus("COMPLETED");
        incoming.setPaymentStatus("PAID");

        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(stored));
        when(salesInvoiceRepository.save(any(SalesInvoice.class))).thenAnswer(call -> call.getArgument(0));

        SalesInvoice saved = salesService().saveInvoiceWithItems(incoming, List.of(salesLine()));

        assertThat(saved).isSameAs(stored);
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getPaymentStatus()).isEqualTo("UNPAID");
        assertThat(saved.getNotes()).isEqualTo("new note");
        assertThat(saved.getDeliveryStatus()).isEqualTo("PENDING");
    }

    @Test
    void completingABill_locksBeforeReadingIt_andACompletedBillIsRefusedForEveryCaller() {

        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(salesInvoice("COMPLETED")));

        assertThatThrownBy(() -> salesService().completeInvoice(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already completed");

        InOrder order = inOrder(rowLockService, salesInvoiceRepository);
        order.verify(rowLockService).lockSalesInvoice(5L);
        order.verify(salesInvoiceRepository).findById(5L);
    }

    @Test
    void deletingABill_locksBeforeReadingIt() {

        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(salesInvoice("COMPLETED")));

        assertThatThrownBy(() -> salesService().deleteInvoice(5L))
                .isInstanceOf(IllegalArgumentException.class);

        InOrder order = inOrder(rowLockService, salesInvoiceRepository);
        order.verify(rowLockService).lockSalesInvoice(5L);
        order.verify(salesInvoiceRepository).findById(5L);
    }

    // ------------------------------------------------------------ purchase invoice

    private PurchaseInvoice purchase(String status) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setId(8L);
        invoice.setDocumentNumber("CBL-8");
        invoice.setInvoiceDate(BusinessTime.today());
        invoice.setStatus(status);
        return invoice;
    }

    private PurchaseInvoiceItem purchaseLine() {
        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(product());
        item.setQuantity(new BigDecimal("1"));
        item.setUnitPrice(new BigDecimal("100"));
        return item;
    }

    @Test
    void editingAPurchaseInvoice_locksFirst_thenReadsIt_andRefusesWhenItIsNowCompleted() {

        PurchaseInvoice completedNow = purchase("COMPLETED");
        PurchaseInvoice staleCopy = purchase("DRAFT");
        when(purchaseInvoiceRepository.findById(8L)).thenReturn(Optional.of(completedNow));

        assertThatThrownBy(() -> purchaseService().saveInvoiceWithItems(staleCopy, List.of(purchaseLine())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed purchase invoice cannot be edited");

        InOrder order = inOrder(rowLockService, purchaseInvoiceRepository);
        order.verify(rowLockService).forget(staleCopy);
        order.verify(rowLockService).lockPurchaseInvoice(8L);
        order.verify(purchaseInvoiceRepository).findById(8L);
        verify(purchaseInvoiceRepository, never()).save(any());
    }

    @Test
    void completingAPurchaseInvoice_locksBeforeReadingIt() {

        when(purchaseInvoiceRepository.findById(8L)).thenReturn(Optional.of(purchase("COMPLETED")));

        assertThatThrownBy(() -> purchaseService().completeInvoice(8L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already completed");

        InOrder order = inOrder(rowLockService, purchaseInvoiceRepository);
        order.verify(rowLockService).lockPurchaseInvoice(8L);
        order.verify(purchaseInvoiceRepository).findById(8L);
    }

    @Test
    void deletingAPurchaseInvoice_locksBeforeReadingIt() {

        when(purchaseInvoiceRepository.findById(8L)).thenReturn(Optional.of(purchase("COMPLETED")));

        assertThatThrownBy(() -> purchaseService().deleteInvoice(8L))
                .isInstanceOf(IllegalArgumentException.class);

        InOrder order = inOrder(rowLockService, purchaseInvoiceRepository);
        order.verify(rowLockService).lockPurchaseInvoice(8L);
        order.verify(purchaseInvoiceRepository).findById(8L);
    }

    @Test
    void theBillFileCannotBeReplacedOnceTheInvoiceIsCompletedOrVerified() {

        PurchaseInvoice completed = purchase("COMPLETED");
        PurchaseInvoice verified = purchase("DRAFT");
        verified.setInvoiceVerified(true);
        when(purchaseInvoiceRepository.findById(8L)).thenReturn(Optional.of(completed));

        PurchaseInvoiceFileService.StoredInvoiceFile file =
                new PurchaseInvoiceFileService.StoredInvoiceFile("scan.pdf", "abc.pdf", "application/pdf", 10);

        assertThatThrownBy(() -> purchaseService().attachInvoiceFile(8L, file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be replaced");
        assertThatThrownBy(() -> purchaseService().requireFileCanBeReplaced(verified))
                .isInstanceOf(IllegalArgumentException.class);

        InOrder order = inOrder(rowLockService, purchaseInvoiceRepository);
        order.verify(rowLockService).lockPurchaseInvoice(8L);
        order.verify(purchaseInvoiceRepository).findById(8L);
        verify(purchaseInvoiceRepository, never()).save(any());
    }

    @Test
    void theBillFileCanBeReplacedWhileTheInvoiceIsStillADraft() {

        PurchaseInvoice draft = purchase("DRAFT");
        draft.setInvoiceFileStoredName("old.pdf");
        when(purchaseInvoiceRepository.findById(8L)).thenReturn(Optional.of(draft));
        when(purchaseInvoiceRepository.save(any(PurchaseInvoice.class))).thenAnswer(call -> call.getArgument(0));

        PurchaseInvoiceService.FileReplacement replacement = purchaseService().attachInvoiceFile(
                8L, new PurchaseInvoiceFileService.StoredInvoiceFile("scan.pdf", "new.pdf", "application/pdf", 10));

        assertThat(replacement.previousStoredFileName()).isEqualTo("old.pdf");
        assertThat(replacement.invoice().getInvoiceFileStoredName()).isEqualTo("new.pdf");
    }

    // ------------------------------------------------------------ customer payments

    private Payment cashPayment(String amount) {
        SalesInvoice reference = new SalesInvoice();
        reference.setId(5L);

        Payment payment = new Payment();
        payment.setReceiptNumber("R-1");
        payment.setSalesInvoice(reference);
        payment.setPaymentDate(BusinessTime.today());
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod("CASH");
        return payment;
    }

    @Test
    void recordingAPayment_locksTheBillBeforeItReadsTheBalance() {

        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(salesInvoice("COMPLETED")));
        when(paymentRepository.calculatePaidAmount(5L)).thenReturn(BigDecimal.ZERO);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));

        paymentService().savePayment(cashPayment("1000"));

        InOrder order = inOrder(rowLockService, salesInvoiceRepository, paymentRepository);
        order.verify(rowLockService).lockSalesInvoice(5L);
        order.verify(salesInvoiceRepository).findById(5L);
        order.verify(paymentRepository).calculatePaidAmount(5L);
        order.verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void twoPaymentsOfTheFullBalance_onlyOneSucceeds() {

        SalesInvoice bill = salesInvoice("COMPLETED");
        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(bill));

        // What the row lock guarantees: the second payment sees the first one's committed amount.
        BigDecimal[] paid = {BigDecimal.ZERO};
        when(paymentRepository.calculatePaidAmount(5L)).thenAnswer(call -> paid[0]);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> {
            Payment saved = call.getArgument(0);
            paid[0] = paid[0].add(saved.getAmount());
            return saved;
        });

        paymentService().savePayment(cashPayment("1000"));

        assertThatThrownBy(() -> paymentService().savePayment(cashPayment("1000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already fully paid");
    }

    @Test
    void aCashBillTakesPaymentsLikeACreditBill() {

        SalesInvoice cashBill = salesInvoice("COMPLETED");
        cashBill.setSaleType("CASH");
        when(salesInvoiceRepository.findById(5L)).thenReturn(Optional.of(cashBill));
        when(paymentRepository.calculatePaidAmount(5L)).thenReturn(BigDecimal.ZERO, new BigDecimal("400"));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));

        Payment saved = paymentService().savePayment(cashPayment("400"));

        assertThat(saved.getStatus()).isEqualTo("RECEIVED");
        assertThat(cashBill.getPaymentStatus()).isEqualTo("PARTIALLY_PAID");
    }
}
