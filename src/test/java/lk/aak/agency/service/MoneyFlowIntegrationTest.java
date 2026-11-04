package lk.aak.agency.service;

import jakarta.persistence.EntityManager;
import lk.aak.agency.controller.api.CollectionApiController;
import lk.aak.agency.controller.api.ReportApiController;
import lk.aak.agency.dto.api.CollectionSummaryResponse;
import lk.aak.agency.dto.api.ReportSummaryResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.ShopReturn;
import lk.aak.agency.model.ShopReturnItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.model.SupplierReturn;
import lk.aak.agency.model.SupplierReturnItem;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnItemRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.repository.SupplierReturnItemRepository;
import lk.aak.agency.repository.SupplierReturnRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end money flows on the real services, repositories and an in-memory database (what the unit tests with
 * mocks cannot show): cash bills, collections, hand-over, reports, stale edits, validation and the audit trail.
 * True row-lock races cannot be reproduced on H2; the stale-copy tests below cover the part that can (the bill is
 * re-read after the lock instead of trusting a copy read earlier in the same request).
 */
@SpringBootTest
@Transactional
class MoneyFlowIntegrationTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private SalesInvoiceRepository invoices;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;
    @Autowired private EmployeeRepository employees;
    @Autowired private PaymentRepository payments;
    @Autowired private StockMovementRepository stockMovements;
    @Autowired private ShopReturnRepository shopReturns;
    @Autowired private ShopReturnItemRepository shopReturnItems;
    @Autowired private SupplierReturnRepository supplierReturns;
    @Autowired private SupplierReturnItemRepository supplierReturnItems;
    @Autowired private CollectionApiController collectionController;
    @Autowired private ReportApiController reportController;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private EntityManager entityManager;

    private static final LocalDate TODAY = BusinessTime.today();

    private Customer shop;
    private Product biscuit;
    private Employee collector;
    private int sequence;

    @BeforeEach
    void setUp() {
        shop = shop("S1", "ACTIVE", new BigDecimal("1000"));
        biscuit = product("P1", "ACTIVE");
        stock(biscuit, "500");

        collector = new Employee();
        collector.setEmployeeCode("E1");
        collector.setFullName("Nimal Collector");
        collector.setDesignation("COLLECTOR");
        collector = employees.save(collector);
    }

    @AfterEach
    void clearLogin() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- helpers

    private Customer shop(String code, String status, BigDecimal creditLimit) {
        Customer customer = new Customer();
        customer.setCustomerCode(code);
        customer.setCustomerName("Shop " + code);
        customer.setStatus(status);
        customer.setCreditLimit(creditLimit);
        customer.setPaymentTermsDays(14);
        return customers.save(customer);
    }

    private Product product(String code, String status) {
        Product product = new Product();
        product.setCblProductCode(code);
        product.setProductName("Product " + code);
        product.setUnit("PKT");
        product.setCategory("Biscuits");
        product.setStatus(status);
        return products.save(product);
    }

    private void stock(Product product, String quantity) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType("OPENING_STOCK");
        movement.setQuantityChange(new BigDecimal(quantity));
        movement.setStockUnit("PKT");
        movement.setReferenceType("TEST_OPENING");
        movement.setReferenceItemId((long) (++sequence));
        stockMovements.save(movement);
    }

    private SalesInvoice header(Customer customer, String saleType) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("INV-" + (++sequence));
        invoice.setInvoiceDate(TODAY);
        invoice.setCustomer(customer);
        invoice.setSaleType(saleType);
        return invoice;
    }

    private SalesInvoiceItem line(Product product, String quantity, String price) {
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(product);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    private SalesInvoice draft(Customer customer, String saleType, String quantity, String price) {
        return salesInvoiceService.saveInvoiceWithItems(
                header(customer, saleType), List.of(line(biscuit, quantity, price)));
    }

    private SalesInvoice completed(Customer customer, String saleType, String quantity, String price) {
        SalesInvoice invoice = draft(customer, saleType, quantity, price);
        salesInvoiceService.completeInvoice(invoice.getId());
        return invoices.findById(invoice.getId()).orElseThrow();
    }

    private Payment payment(SalesInvoice invoice, String method, String amount) {
        Payment payment = new Payment();
        payment.setReceiptNumber("R-" + (++sequence));
        payment.setSalesInvoice(invoice);
        payment.setPaymentDate(TODAY);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod(method);
        payment.setCollectedByEmployeeId(collector.getId());
        if ("CHEQUE".equals(method)) {
            payment.setChequeNumber("CH" + sequence);
            payment.setChequeDate(TODAY);
            payment.setBankName("BOC");
        }
        if ("BANK_TRANSFER".equals(method)) {
            payment.setReferenceNumber("TX" + sequence);
        }
        return payment;
    }

    private Payment pay(SalesInvoice invoice, String method, String amount) {
        return paymentService.savePayment(payment(invoice, method, amount));
    }

    /** Bypasses the persistence context, like another request committing in between. */
    private void setStatusBehindOurBack(Long invoiceId, String status) {
        entityManager.flush();
        entityManager.createQuery("update SalesInvoice i set i.status = :s where i.id = :id")
                .setParameter("s", status).setParameter("id", invoiceId).executeUpdate();
    }

    private boolean audited(String action) {
        return auditLogs.findAll().stream().anyMatch(log -> action.equals(log.getAction()));
    }

    // ---------------------------------------------------------------- #3 stale copies (edit vs complete)

    @Test
    void editingABillThatWasCompletedMeanwhileIsRefused_notSavedBackAsDraft() {

        SalesInvoice stale = draft(shop, "CREDIT", "2", "100");
        entityManager.flush();
        // Our request already holds this copy (status DRAFT) when another request completes the bill.
        setStatusBehindOurBack(stale.getId(), "COMPLETED");

        stale.setNotes("edited late");

        assertThatThrownBy(() -> salesInvoiceService.saveInvoiceWithItems(stale, List.of(line(biscuit, "9", "100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed sales invoice cannot be edited");

        entityManager.clear();
        SalesInvoice stored = invoices.findById(stale.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getNetAmount()).isEqualByComparingTo("200");
    }

    @Test
    void completingABillThatWasCompletedMeanwhileSaysAlreadyCompleted_andDeductsNothingTwice() {

        SalesInvoice bill = draft(shop, "CREDIT", "2", "100");
        entityManager.flush();
        // This request has the DRAFT copy in memory ...
        SalesInvoice heldCopy = invoices.findById(bill.getId()).orElseThrow();
        assertThat(heldCopy.getStatus()).isEqualTo("DRAFT");
        // ... while another request completes the bill.
        setStatusBehindOurBack(bill.getId(), "COMPLETED");
        BigDecimal stockBefore = stockMovements.calculateCurrentStock(biscuit.getId());

        assertThatThrownBy(() -> salesInvoiceService.completeInvoice(bill.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already completed");

        assertThat(stockMovements.calculateCurrentStock(biscuit.getId())).isEqualByComparingTo(stockBefore);
    }

    @Test
    void deletingABillThatWasCompletedMeanwhileIsRefused() {

        SalesInvoice bill = draft(shop, "CREDIT", "2", "100");
        entityManager.flush();
        invoices.findById(bill.getId()).orElseThrow();
        setStatusBehindOurBack(bill.getId(), "COMPLETED");

        assertThatThrownBy(() -> salesInvoiceService.deleteInvoice(bill.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be deleted");
    }

    // ---------------------------------------------------------------- D1 cash bills

    @Test
    void aCashBillCompletesUnpaid_andTheOfficeRecordsTheCashWithTheCollector() {

        SalesInvoice bill = completed(shop, "CASH", "10", "300");

        assertThat(bill.getPaymentStatus()).isEqualTo("UNPAID");

        Payment saved = pay(bill, "CASH", "3000");

        assertThat(saved.getCollectedByName()).isEqualTo("Nimal Collector");
        assertThat(invoices.findById(bill.getId()).orElseThrow().getPaymentStatus()).isEqualTo("PAID");

        // Cash shows up in the collections and in the collector's hand-over.
        CollectionSummaryResponse summary = collectionController.summary(TODAY, TODAY);
        assertThat(summary.getTotalCash()).isEqualByComparingTo("3000");
        assertThat(paymentService.getHandoverSummaryForDate(TODAY)).hasSize(1);
        assertThat(paymentService.getHandoverSummaryForDate(TODAY).get(0).getCashTotal()).isEqualByComparingTo("3000");
    }

    @Test
    void anUnpaidCashBillIsAReceivable_butNeverCountsTowardsTheCreditLimit() {

        // Shop limit is 1000. A Rs 5,000 CASH bill must not use up (or be blocked by) the credit limit ...
        SalesInvoice cash = completed(shop, "CASH", "10", "500");
        assertThat(cash.getPaymentStatus()).isEqualTo("UNPAID");

        // ... so a credit bill within the limit still goes through.
        SalesInvoice credit = draft(shop, "CREDIT", "9", "100");
        salesInvoiceService.completeInvoice(credit.getId());
        assertThat(invoices.findById(credit.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");

        // The unpaid cash bill is outstanding money for the report (credit + unpaid cash).
        ReportSummaryResponse report = reportController.summary(TODAY, TODAY);
        assertThat(report.getOutstandingCredit()).isEqualByComparingTo("5900");
    }

    @Test
    void anOldCashBillMarkedPaidWithoutAPaymentIsLeftAlone_noPaymentCanBeAddedToIt() {

        SalesInvoice old = completed(shop, "CASH", "10", "100");
        old.setPaymentStatus("PAID"); // how cash bills were completed before this change
        invoices.save(old);

        assertThatThrownBy(() -> pay(old, "CASH", "1000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already marked as paid");

        assertThat(paymentService.getRemainingBalance(old)).isEqualByComparingTo("0");
        assertThat(PaymentService.isOwedSale(old, BigDecimal.ZERO)).isFalse();
        assertThat(invoices.findById(old.getId()).orElseThrow().getPaymentStatus()).isEqualTo("PAID");
    }

    // ---------------------------------------------------------------- #5 collections / hand-over

    @Test
    void collectionsAndHandoverIgnoreBouncedChequesAndBankTransfers() {

        SalesInvoice bill = completed(shop, "CREDIT", "10", "100");

        pay(bill, "CASH", "100");
        Payment bounced = pay(bill, "CHEQUE", "200");
        pay(bill, "CHEQUE", "50");
        pay(bill, "BANK_TRANSFER", "300");
        paymentService.updateChequeStatus(bounced.getId(), "RETURNED", TODAY, "Insufficient funds");

        CollectionSummaryResponse summary = collectionController.summary(TODAY, TODAY);

        assertThat(summary.getTotalCash()).isEqualByComparingTo("100");
        assertThat(summary.getTotalCheque()).isEqualByComparingTo("50");          // the bounced 200 is out
        assertThat(summary.getChequeReturnedTotal()).isEqualByComparingTo("200");
        assertThat(summary.getChequeNotYetClearedTotal()).isEqualByComparingTo("50");
        assertThat(summary.getTotalBankTransfer()).isEqualByComparingTo("300");
        assertThat(summary.getChequePaymentCount()).isEqualTo(1);
        // Dashboard rule: cheques are collected only when CLEARED -> cash 100 + bank 300 now.
        assertThat(summary.getTotalCollected()).isEqualByComparingTo("400");

        List<PaymentService.HandoverSummaryRow> handover = paymentService.getHandoverSummaryForDate(TODAY);
        assertThat(handover).hasSize(1);
        assertThat(handover.get(0).getCashTotal()).isEqualByComparingTo("100");      // no bank transfer in "cash"
        assertThat(handover.get(0).getChequeTotal()).isEqualByComparingTo("50");     // no bounced cheque
    }

    @Test
    void aBankTransferOnlyDayHasNothingToHandOver() {

        SalesInvoice bill = completed(shop, "CREDIT", "10", "100");
        pay(bill, "BANK_TRANSFER", "300");

        assertThat(paymentService.getHandoverSummaryForDate(TODAY)).isEmpty();
        assertThatThrownBy(() -> paymentService.markHandedOver(collector.getId(), TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClearedChequeJoinsTheCollectedTotal() {

        SalesInvoice bill = completed(shop, "CREDIT", "10", "100");
        Payment cheque = pay(bill, "CHEQUE", "250");
        paymentService.updateChequeStatus(cheque.getId(), "CLEARED", TODAY, null);

        CollectionSummaryResponse summary = collectionController.summary(TODAY, TODAY);

        assertThat(summary.getChequeClearedTotal()).isEqualByComparingTo("250");
        assertThat(summary.getTotalCollected()).isEqualByComparingTo("250");
    }

    // ---------------------------------------------------------------- #6 reports

    @Test
    void reportReturnTotalsCountApprovedReturnsOnly() {

        shopReturn("APPROVED", "100");
        shopReturn("PENDING", "200");
        shopReturn("REJECTED", "400");
        supplierReturn("APPROVED", "10");
        supplierReturn("PENDING", "20");
        supplierReturn("REJECTED", "40");

        ReportSummaryResponse report = reportController.summary(TODAY.minusDays(1), TODAY);

        assertThat(report.getTotalShopReturnsValue()).isEqualByComparingTo("100");
        assertThat(report.getTotalSupplierReturnsValue()).isEqualByComparingTo("10");
    }

    private void shopReturn(String status, String amount) {
        ShopReturn shopReturn = new ShopReturn();
        shopReturn.setCustomerId(shop.getId());
        shopReturn.setCustomerName(shop.getCustomerName());
        shopReturn.setReturnDate(TODAY);
        shopReturn.setStatus(status);
        shopReturn = shopReturns.save(shopReturn);

        ShopReturnItem item = new ShopReturnItem();
        item.setShopReturnId(shopReturn.getId());
        item.setProduct(biscuit);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("PKT");
        item.setUnitPrice(new BigDecimal(amount));
        item.setCategory("SALEABLE");
        shopReturnItems.save(item);
    }

    private void supplierReturn(String status, String amount) {
        SupplierReturn supplierReturn = new SupplierReturn();
        supplierReturn.setReturnDate(TODAY);
        supplierReturn.setStatus(status);
        supplierReturn = supplierReturns.save(supplierReturn);

        SupplierReturnItem item = new SupplierReturnItem();
        item.setSupplierReturnId(supplierReturn.getId());
        item.setProduct(biscuit);
        item.setQuantity(BigDecimal.ONE);
        item.setUnit("PKT");
        item.setUnitPrice(new BigDecimal(amount));
        supplierReturnItems.save(item);
    }

    // ---------------------------------------------------------------- #12 sales-bill validation

    @Test
    void saleTypeMustBeCreditOrCash_andIsUpperCased() {

        assertThatThrownBy(() -> draft(shop, "FOO", "1", "100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREDIT or CASH");

        SalesInvoice lower = draft(shop, "cash", "1", "100");
        assertThat(lower.getSaleType()).isEqualTo("CASH");

        SalesInvoice blank = draft(shop, null, "1", "100");
        assertThat(blank.getSaleType()).isEqualTo("CREDIT");
    }

    @Test
    void anInactiveShopOrProductCannotBeBilled() {

        Customer closed = shop("CLOSED", "INACTIVE", new BigDecimal("1000"));
        assertThatThrownBy(() -> draft(closed, "CREDIT", "1", "100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");

        Product retired = product("OLD", "INACTIVE");
        assertThatThrownBy(() -> salesInvoiceService.saveInvoiceWithItems(
                header(shop, "CREDIT"), List.of(line(retired, "1", "100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void threeDecimalPricesGiveTheSameTotalOnTheDraftAndAfterCompleting() {

        SalesInvoice bill = draft(shop, "CASH", "3", "10.005");

        // 10.005 -> 10.01 (half up), 3 x 10.01 = 30.03
        assertThat(bill.getGrossAmount()).isEqualByComparingTo("30.03");
        assertThat(bill.getNetAmount()).isEqualByComparingTo("30.03");

        entityManager.flush();
        entityManager.clear();
        salesInvoiceService.completeInvoice(bill.getId());
        entityManager.flush();
        entityManager.clear();

        SalesInvoice after = invoices.findById(bill.getId()).orElseThrow();
        assertThat(after.getNetAmount()).isEqualByComparingTo("30.03");
        assertThat(salesInvoiceService.getItemsByInvoiceId(bill.getId()).get(0).getAmount()).isEqualByComparingTo("30.03");
    }

    @Test
    void dueDateDefaultsFromShopTermsAndCannotBeBeforeTheInvoiceDate() {

        SalesInvoice credit = draft(shop, "CREDIT", "1", "100");
        assertThat(credit.getDueDate()).isEqualTo(TODAY.plusDays(14));

        SalesInvoice cash = draft(shop, "CASH", "1", "100");
        assertThat(cash.getDueDate()).isEqualTo(TODAY);

        SalesInvoice early = header(shop, "CREDIT");
        early.setDueDate(TODAY.minusDays(1));
        assertThatThrownBy(() -> salesInvoiceService.saveInvoiceWithItems(early, List.of(line(biscuit, "1", "100"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("due date");

        SalesInvoice explicit = header(shop, "CREDIT");
        explicit.setDueDate(TODAY.plusDays(3));
        assertThat(salesInvoiceService.saveInvoiceWithItems(explicit, List.of(line(biscuit, "1", "100"))).getDueDate())
                .isEqualTo(TODAY.plusDays(3));
    }

    // ---------------------------------------------------------------- #24 credit override + audit trail

    @Test
    void creditOverrideRecordsTheLoggedInAdmin_andTheTypedNameIsOnlyANote() {

        shop.setCreditLimit(new BigDecimal("100"));
        customers.save(shop);
        SalesInvoice bill = draft(shop, "CREDIT", "5", "100");

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner.admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        salesInvoiceService.completeInvoiceWithCreditOverride(bill.getId(), "Somebody Else", "Long-time customer");

        SalesInvoice stored = invoices.findById(bill.getId()).orElseThrow();
        assertThat(stored.getCreditOverrideApprovedBy()).isEqualTo("owner.admin");
        assertThat(stored.getCreditOverrideReason()).contains("Long-time customer").contains("Somebody Else");
        assertThat(audited("CREDIT_LIMIT_OVERRIDE")).isTrue();
    }

    @Test
    void paymentsAndChequeChangesAndDeletionsAreAudited() {

        SalesInvoice bill = completed(shop, "CREDIT", "10", "100");
        Payment cheque = pay(bill, "CHEQUE", "100");
        assertThat(audited("PAYMENT_CREATED")).isTrue();

        paymentService.updateChequeStatus(cheque.getId(), "DEPOSITED", TODAY, null);
        assertThat(audited("CHEQUE_STATUS_CHANGED")).isTrue();

        Payment cash = pay(bill, "CASH", "50");
        paymentService.deletePayment(cash.getId());
        assertThat(audited("PAYMENT_DELETED")).isTrue();
    }

    @Test
    void aClearedOrHandedOverPaymentCannotBeDeleted() {

        SalesInvoice bill = completed(shop, "CREDIT", "10", "100");

        Payment cleared = pay(bill, "CHEQUE", "100");
        paymentService.updateChequeStatus(cleared.getId(), "CLEARED", TODAY, null);
        assertThatThrownBy(() -> paymentService.deletePayment(cleared.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already cleared");

        Payment cash = pay(bill, "CASH", "100");
        paymentService.markHandedOver(collector.getId(), TODAY);
        entityManager.flush(); // in production each call is its own transaction, so this is already saved
        assertThatThrownBy(() -> paymentService.deletePayment(cash.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("handed it over");

        assertThat(payments.findById(cash.getId())).isPresent();
        assertThat(payments.findById(cleared.getId())).isPresent();
    }

    @Test
    void anUnclearedPaymentCanStillBeDeleted_andTheBillBecomesUnpaidAgain() {

        SalesInvoice bill = completed(shop, "CREDIT", "1", "100");
        Payment cash = pay(bill, "CASH", "100");
        assertThat(invoices.findById(bill.getId()).orElseThrow().getPaymentStatus()).isEqualTo("PAID");

        paymentService.deletePayment(cash.getId());

        assertThat(invoices.findById(bill.getId()).orElseThrow().getPaymentStatus()).isEqualTo("UNPAID");
    }

    @Test
    void receiptRoundingAndOverpaymentChecksStillHold() {

        SalesInvoice bill = completed(shop, "CREDIT", "1", "100");

        assertThatThrownBy(() -> pay(bill, "CASH", "100.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining balance");

        pay(bill, "CASH", "60");
        assertThatThrownBy(() -> pay(bill, "CASH", "60"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining balance of Rs. 40");

        Payment rest = pay(bill, "CASH", "40.004"); // rounds to 40.00
        assertThat(rest.getAmount()).isEqualByComparingTo("40.00");
        assertThat(new ArrayList<>(paymentService.getPaymentsByInvoiceId(bill.getId()))).hasSize(2);
    }
}
