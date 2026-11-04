package lk.aak.agency.service;

import jakarta.persistence.EntityManager;
import lk.aak.agency.controller.api.CollectionApiController;
import lk.aak.agency.controller.api.ReportApiController;
import lk.aak.agency.dto.api.CollectionSummaryResponse;
import lk.aak.agency.dto.api.ShopReturnResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.ShopReturn;
import lk.aak.agency.model.ShopReturnItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shop returns: linked to a completed bill of the same shop, capped at what was sold, and approval credits the
 * shop (a CREDIT_NOTE payment) without ever counting as money collected.
 */
@SpringBootTest
@Transactional
class ShopReturnCreditIntegrationTest {

    private static final LocalDate TODAY = BusinessTime.today();

    @Autowired private ShopReturnService shopReturnService;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private PaymentService paymentService;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;
    @Autowired private EmployeeRepository employees;
    @Autowired private SalesInvoiceRepository invoices;
    @Autowired private PaymentRepository payments;
    @Autowired private StockMovementRepository stockMovements;
    @Autowired private CollectionApiController collectionController;
    @Autowired private ReportApiController reportController;
    @Autowired private EntityManager entityManager;

    private Customer shop;
    private Product biscuit;
    private Product soap;
    private Employee collector;
    private int sequence;

    @BeforeEach
    void setUp() {
        shop = shop("S1");
        biscuit = product("P1");
        soap = product("P2");
        stock(biscuit);
        stock(soap);

        collector = new Employee();
        collector.setEmployeeCode("E1");
        collector.setFullName("Nimal Collector");
        collector.setDesignation("COLLECTOR");
        collector = employees.save(collector);
    }

    // ---------------------------------------------------------------- helpers

    private Customer shop(String code) {
        Customer customer = new Customer();
        customer.setCustomerCode(code);
        customer.setCustomerName("Shop " + code);
        customer.setCreditLimit(new BigDecimal("1000000"));
        return customers.save(customer);
    }

    private Product product(String code) {
        Product product = new Product();
        product.setCblProductCode(code);
        product.setProductName("Product " + code);
        product.setUnit("PKT");
        return products.save(product);
    }

    private void stock(Product product) {
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType("OPENING_STOCK");
        movement.setQuantityChange(new BigDecimal("1000"));
        movement.setStockUnit("PKT");
        movement.setReferenceType("TEST_OPENING");
        movement.setReferenceItemId((long) (++sequence));
        stockMovements.save(movement);
    }

    private SalesInvoiceItem line(Product product, String quantity, String price) {
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(product);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    /** A completed bill of the shop: 10 biscuits at Rs 100 and 5 soaps at Rs 200 = Rs 2,000. */
    private SalesInvoice completedBill(Customer customer) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("INV-" + (++sequence));
        invoice.setInvoiceDate(TODAY);
        invoice.setCustomer(customer);
        invoice.setSaleType("CREDIT");
        SalesInvoice saved = salesInvoiceService.saveInvoiceWithItems(
                invoice, List.of(line(biscuit, "10", "100"), line(soap, "5", "200")));
        salesInvoiceService.completeInvoice(saved.getId());
        return invoices.findById(saved.getId()).orElseThrow();
    }

    private ShopReturn newReturn(Customer customer, SalesInvoice bill) {
        ShopReturn shopReturn = new ShopReturn();
        shopReturn.setCustomerId(customer.getId());
        shopReturn.setSalesInvoiceId(bill == null ? null : bill.getId());
        shopReturn.setReturnDate(TODAY);
        return shopReturnService.saveReturn(shopReturn);
    }

    private ShopReturnItem addItem(ShopReturn shopReturn, Product product, String quantity, String price, String category) {
        return shopReturnService.addItem(
                shopReturn.getId(), product.getId(), new BigDecimal(quantity),
                price == null ? null : new BigDecimal(price), category);
    }

    private BigDecimal stockOf(Product product) {
        return stockMovements.calculateCurrentStock(product.getId());
    }

    private BigDecimal balanceOf(SalesInvoice bill) {
        return paymentService.getRemainingBalance(invoices.findById(bill.getId()).orElseThrow());
    }

    private Payment pay(SalesInvoice bill, String method, String amount) {
        Payment payment = new Payment();
        payment.setReceiptNumber("R-" + (++sequence));
        payment.setSalesInvoice(bill);
        payment.setPaymentDate(TODAY);
        payment.setAmount(new BigDecimal(amount));
        payment.setPaymentMethod(method);
        payment.setCollectedByEmployeeId(collector.getId());
        return paymentService.savePayment(payment);
    }

    // ---------------------------------------------------------------- (a) link to a completed bill of the same shop

    @Test
    void aReturnMustBeLinkedToABill() {
        assertThatThrownBy(() -> newReturn(shop, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Choose the bill");
    }

    @Test
    void theBillMustExist_belongToTheSameShop_andBeCompleted() {

        ShopReturn missing = new ShopReturn();
        missing.setCustomerId(shop.getId());
        missing.setSalesInvoiceId(999_999L);
        assertThatThrownBy(() -> shopReturnService.saveReturn(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was not found");

        SalesInvoice bill = completedBill(shop);
        Customer other = shop("S2");
        assertThatThrownBy(() -> newReturn(other, bill))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different shop");

        SalesInvoice draft = new SalesInvoice();
        draft.setInvoiceNumber("INV-DRAFT");
        draft.setInvoiceDate(TODAY);
        draft.setCustomer(shop);
        SalesInvoice savedDraft = salesInvoiceService.saveInvoiceWithItems(draft, List.of(line(biscuit, "1", "100")));
        assertThatThrownBy(() -> newReturn(shop, savedDraft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not completed");
    }

    // ---------------------------------------------------------------- (b) items must be on the bill, capped, priced

    @Test
    void anItemMustBeOnTheBill_andCannotExceedWhatWasSold() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn shopReturn = newReturn(shop, bill);

        Product notSold = product("P9");
        assertThatThrownBy(() -> addItem(shopReturn, notSold, "1", null, "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not on bill");

        assertThatThrownBy(() -> addItem(shopReturn, biscuit, "11", null, "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only 10");

        addItem(shopReturn, biscuit, "6", null, "SALEABLE");

        // 6 are already on this pending return, so only 4 are left to return.
        assertThatThrownBy(() -> addItem(shopReturn, biscuit, "5", null, "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only 4");

        // Another pending return for the same bill counts too.
        ShopReturn second = newReturn(shop, bill);
        assertThatThrownBy(() -> addItem(second, biscuit, "5", null, "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only 4");
        addItem(second, biscuit, "4", null, "SALEABLE");
    }

    @Test
    void aRejectedReturnGivesItsQuantityBack() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn first = newReturn(shop, bill);
        addItem(first, biscuit, "10", null, "SALEABLE");
        shopReturnService.rejectReturn(first.getId());

        ShopReturn second = newReturn(shop, bill);
        addItem(second, biscuit, "10", null, "SALEABLE");
    }

    @Test
    void theUnitPriceDefaultsToTheBilledPrice_andNegativeOrHigherPricesAreRefused() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn shopReturn = newReturn(shop, bill);

        ShopReturnItem defaulted = addItem(shopReturn, biscuit, "2", null, "SALEABLE");
        assertThat(defaulted.getUnitPrice()).isEqualByComparingTo("100");
        assertThat(defaulted.getAmount()).isEqualByComparingTo("200");

        assertThatThrownBy(() -> addItem(shopReturn, biscuit, "1", "-5", "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> addItem(shopReturn, biscuit, "1", "150", "SALEABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("higher than the price billed");

        ShopReturnItem cheaper = addItem(shopReturn, biscuit, "1", "80", "SALEABLE");
        assertThat(cheaper.getUnitPrice()).isEqualByComparingTo("80");
    }

    // ---------------------------------------------------------------- (c) the credit

    @Test
    void approvingCreditsTheBill_forAllItemsIncludingDamaged_andRestocksSaleableOnly() {

        SalesInvoice bill = completedBill(shop);
        assertThat(balanceOf(bill)).isEqualByComparingTo("2000");
        BigDecimal biscuitStock = stockOf(biscuit);
        BigDecimal soapStock = stockOf(soap);

        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "3", null, "SALEABLE");   // 300
        addItem(shopReturn, soap, "2", null, "DAMAGED");       // 400

        ShopReturn approved = shopReturnService.approveReturn(shopReturn.getId());

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getCreditAppliedAmount()).isEqualByComparingTo("700");
        assertThat(approved.getRefundDueAmount()).isEqualByComparingTo("0");

        // The bill now owes 1,300 and is part-paid.
        entityManager.flush();
        entityManager.clear();
        assertThat(balanceOf(bill)).isEqualByComparingTo("1300");
        assertThat(invoices.findById(bill.getId()).orElseThrow().getPaymentStatus()).isEqualTo("PARTIALLY_PAID");

        // It is shown on the shop's bill as a credit note.
        List<Payment> onBill = paymentService.getPaymentsByInvoiceId(bill.getId());
        assertThat(onBill).hasSize(1);
        assertThat(onBill.get(0).getPaymentMethod()).isEqualTo("CREDIT_NOTE");
        assertThat(onBill.get(0).getCollectedByEmployeeId()).isNull();

        // Stock: the saleable goods went back; damaged goods did not.
        assertThat(stockOf(biscuit)).isEqualByComparingTo(biscuitStock.add(new BigDecimal("3")));
        assertThat(stockOf(soap)).isEqualByComparingTo(soapStock);

        // The response says what happened.
        assertThat(new ShopReturnResponse(approved).getCreditMessage()).contains("700");
    }

    @Test
    void aCreditNoteIsNeverCollectedMoney_noCashNoChequeNoHandover() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "3", null, "SALEABLE");
        shopReturnService.approveReturn(shopReturn.getId());

        CollectionSummaryResponse summary = collectionController.summary(TODAY, TODAY);
        assertThat(summary.getTotalCollected()).isEqualByComparingTo("0");
        assertThat(summary.getTotalCash()).isEqualByComparingTo("0");
        assertThat(summary.getTotalCheque()).isEqualByComparingTo("0");
        assertThat(summary.getPayments()).isEmpty();
        assertThat(paymentService.getHandoverSummaryForDate(TODAY)).isEmpty();
        assertThat(paymentService.getTotalCollected("")).isEqualByComparingTo("0");
        assertThat(reportController.summary(TODAY, TODAY).getTotalPayments()).isEqualByComparingTo("0");
        assertThat(reportController.summary(TODAY, TODAY).getReceivedPaymentCount()).isZero();

        // ... and it cannot be deleted like a payment.
        Payment note = paymentService.getPaymentsByInvoiceId(bill.getId()).get(0);
        assertThatThrownBy(() -> paymentService.deletePayment(note.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credit from an approved shop return");
    }

    @Test
    void whenTheShopAlreadyPaidTheCreditIsCappedAndTheRestIsReportedAsRefundDue() {

        SalesInvoice bill = completedBill(shop);
        pay(bill, "CASH", "1900");                 // only 100 left on the bill

        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "3", null, "SALEABLE");   // worth 300

        ShopReturn approved = shopReturnService.approveReturn(shopReturn.getId());

        assertThat(approved.getCreditAppliedAmount()).isEqualByComparingTo("100");
        assertThat(approved.getRefundDueAmount()).isEqualByComparingTo("200");
        assertThat(new ShopReturnResponse(approved).getCreditMessage()).contains("200").contains("due back");

        entityManager.flush();
        entityManager.clear();
        assertThat(balanceOf(bill)).isEqualByComparingTo("0");
        assertThat(invoices.findById(bill.getId()).orElseThrow().getPaymentStatus()).isEqualTo("PAID");
    }

    @Test
    void aFullyPaidBillGetsNoCreditNote_onlyARefundDue() {

        SalesInvoice bill = completedBill(shop);
        pay(bill, "CASH", "2000");

        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "1", null, "SALEABLE");

        ShopReturn approved = shopReturnService.approveReturn(shopReturn.getId());

        assertThat(approved.getCreditAppliedAmount()).isEqualByComparingTo("0");
        assertThat(approved.getRefundDueAmount()).isEqualByComparingTo("100");
        assertThat(paymentService.getPaymentsByInvoiceId(bill.getId())).hasSize(1); // just the cash
    }

    // ---------------------------------------------------------------- (d) idempotent

    @Test
    void approvingTwiceDoesNotCreditTwice() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "3", null, "SALEABLE");

        shopReturnService.approveReturn(shopReturn.getId());

        assertThatThrownBy(() -> shopReturnService.approveReturn(shopReturn.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been approved");

        entityManager.flush();
        entityManager.clear();
        assertThat(paymentService.getPaymentsByInvoiceId(bill.getId())).hasSize(1);
        assertThat(balanceOf(bill)).isEqualByComparingTo("1700");
    }

    @Test
    void aLeftoverCreditNoteForTheSameReturnGivesAFriendlyMessage_notADuplicateKeyError() {

        SalesInvoice bill = completedBill(shop);

        paymentService.applyCreditNote(bill.getId(), new BigDecimal("100"), "CN-SR-77", TODAY, "first");

        assertThatThrownBy(() ->
                paymentService.applyCreditNote(bill.getId(), new BigDecimal("100"), "CN-SR-77", TODAY, "again"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been recorded");
    }

    @Test
    void aReturnWithoutALinkedBill_legacyData_cannotBeApproved() {

        ShopReturn legacy = new ShopReturn();
        legacy.setCustomerId(shop.getId());
        legacy.setCustomerName(shop.getCustomerName());
        legacy.setReturnDate(TODAY);
        legacy.setStatus("PENDING");
        legacy = shopReturnRepositoryForLegacy(legacy);

        ShopReturn legacyReturn = legacy;
        assertThatThrownBy(() -> shopReturnService.approveReturn(legacyReturn.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not linked to a bill");
    }

    @Autowired private lk.aak.agency.repository.ShopReturnRepository shopReturnRepository;

    private ShopReturn shopReturnRepositoryForLegacy(ShopReturn shopReturn) {
        return shopReturnRepository.save(shopReturn);
    }

    // ---------------------------------------------------------------- (e) damaged / expired for CBL claim

    @Test
    void damagedAndExpiredGoodsAreListedForCblClaim_perProduct_neverInSellableStock() {

        SalesInvoice bill = completedBill(shop);
        BigDecimal soapStock = stockOf(soap);

        ShopReturn first = newReturn(shop, bill);
        addItem(first, soap, "2", null, "DAMAGED");
        addItem(first, biscuit, "1", null, "SALEABLE");
        shopReturnService.approveReturn(first.getId());

        ShopReturn second = newReturn(shop, bill);
        addItem(second, soap, "1", null, "EXPIRED");
        // A pending return is not claimable yet.
        assertThat(shopReturnService.getUnclaimedCblItems())
                .extracting(ShopReturnService.CblClaimRow::category, row -> row.quantity().intValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DAMAGED", 2));

        shopReturnService.approveReturn(second.getId());

        List<ShopReturnService.CblClaimRow> claims = shopReturnService.getUnclaimedCblItems();
        assertThat(claims).hasSize(2);
        assertThat(claims).allSatisfy(row -> assertThat(row.productId()).isEqualTo(soap.getId()));
        assertThat(claims).extracting(ShopReturnService.CblClaimRow::category).containsExactlyInAnyOrder("DAMAGED", "EXPIRED");

        assertThat(shopReturnService.getItemsForReturn(first.getId()))
                .filteredOn(item -> "DAMAGED".equals(item.getCategory()))
                .allSatisfy(item -> assertThat(item.getCblClaimStatus()).isEqualTo("FOR_CLAIM"));
        assertThat(stockOf(soap)).isEqualByComparingTo(soapStock);
    }

    // ---------------------------------------------------------------- (f) never below zero, and the credit reaches the history

    @Test
    void creditsReduceReceivablesAndAreVisibleInTheShopHistory() {

        SalesInvoice bill = completedBill(shop);
        ShopReturn shopReturn = newReturn(shop, bill);
        addItem(shopReturn, biscuit, "10", null, "SALEABLE");  // 1,000
        addItem(shopReturn, soap, "5", null, "DAMAGED");       // 1,000 -> whole bill
        shopReturnService.approveReturn(shopReturn.getId());

        entityManager.flush();
        entityManager.clear();
        SalesInvoice stored = invoices.findById(bill.getId()).orElseThrow();
        assertThat(stored.getPaymentStatus()).isEqualTo("PAID");
        assertThat(balanceOf(bill)).isEqualByComparingTo("0");
        assertThat(payments.findBySalesInvoiceCustomerIdOrderByPaymentDateDesc(shop.getId()))
                .extracting(Payment::getPaymentMethod).containsExactly("CREDIT_NOTE");
    }
}
