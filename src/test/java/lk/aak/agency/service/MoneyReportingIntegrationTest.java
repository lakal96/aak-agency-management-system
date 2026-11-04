package lk.aak.agency.service;

import jakarta.persistence.EntityManager;
import lk.aak.agency.controller.api.CustomerApiController;
import lk.aak.agency.dto.api.CreditFollowUpResponse;
import lk.aak.agency.dto.api.CustomerCreditHistoryResponse;
import lk.aak.agency.dto.api.DashboardOverviewResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.EmployeeAdvance;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.StockAdjustment;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dashboard category totals, receivables with unpaid cash bills, the purchase-side stale copy, and the audit trail. */
@SpringBootTest
@Transactional
class MoneyReportingIntegrationTest {

    private static final LocalDate TODAY = BusinessTime.today();

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private PurchaseInvoiceService purchaseInvoiceService;
    @Autowired private StockAdjustmentService stockAdjustmentService;
    @Autowired private EmployeeAdvanceService employeeAdvanceService;
    @Autowired private EmployeeSalaryService employeeSalaryService;
    @Autowired private DashboardOverviewService dashboardOverviewService;
    @Autowired private CustomerApiController customerController;
    @Autowired private CustomerRepository customers;
    @Autowired private ProductRepository products;
    @Autowired private EmployeeRepository employees;
    @Autowired private SalesInvoiceRepository invoices;
    @Autowired private PurchaseInvoiceRepository purchaseInvoices;
    @Autowired private StockMovementRepository stockMovements;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private PaymentService paymentService;
    @Autowired private EntityManager entityManager;

    private Customer shop;
    private Product biscuit;
    private Product soap;
    private int sequence;

    @BeforeEach
    void setUp() {
        shop = new Customer();
        shop.setCustomerCode("S1");
        shop.setCustomerName("Shop S1");
        shop.setCreditLimit(new BigDecimal("1000000"));
        shop = customers.save(shop);

        biscuit = product("P1", "Biscuits");
        soap = product("P2", "Soap");
    }

    private Product product(String code, String category) {
        Product product = new Product();
        product.setCblProductCode(code);
        product.setProductName("Product " + code);
        product.setUnit("PKT");
        product.setCategory(category);
        product = products.save(product);

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType("OPENING_STOCK");
        movement.setQuantityChange(new BigDecimal("1000"));
        movement.setStockUnit("PKT");
        movement.setReferenceType("TEST_OPENING");
        movement.setReferenceItemId((long) (++sequence));
        stockMovements.save(movement);
        return product;
    }

    private SalesInvoiceItem line(Product product, String quantity, String price) {
        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(product);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    private SalesInvoice completed(String saleType, String discount, SalesInvoiceItem... lines) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("INV-" + (++sequence));
        invoice.setInvoiceDate(TODAY);
        invoice.setCustomer(shop);
        invoice.setSaleType(saleType);
        invoice.setDiscountAmount(new BigDecimal(discount));
        SalesInvoice saved = salesInvoiceService.saveInvoiceWithItems(invoice, List.of(lines));
        salesInvoiceService.completeInvoice(saved.getId());
        return invoices.findById(saved.getId()).orElseThrow();
    }

    private boolean audited(String action) {
        return auditLogs.findAll().stream().anyMatch(log -> action.equals(log.getAction()));
    }

    // ---------------------------------------------------------------- #23 dashboard categories

    @Test
    void categoryTotalsAddUpToTheNetSalesAfterDiscount() {

        // Gross 1,000 (biscuits) + 2,000 (soap) = 3,000; discount 300 -> net 2,700.
        completed("CREDIT", "300", line(biscuit, "10", "100"), line(soap, "10", "200"));
        // A second bill whose proportions do not divide evenly into cents.
        completed("CASH", "0.01", line(biscuit, "3", "33.33"), line(soap, "7", "11.11"));

        DashboardOverviewResponse overview = dashboardOverviewService.overview(null, null);

        BigDecimal categoryTotal = overview.categories().stream()
                .map(DashboardOverviewResponse.CategorySale::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // net = 2,700 + (99.99 + 77.77 - 0.01) = 2,877.75
        assertThat(overview.sales().value()).isEqualByComparingTo("2877.75");
        assertThat(categoryTotal).isEqualByComparingTo(overview.sales().value());
    }

    // ---------------------------------------------------------------- receivables with cash bills

    @Test
    void creditHistoryAndFollowUpListAnUnpaidCashBill_butNotAnOldCashBillMarkedPaid() {

        SalesInvoice unpaidCash = completed("CASH", "0", line(biscuit, "5", "100"));

        SalesInvoice oldCash = completed("CASH", "0", line(biscuit, "4", "100"));
        oldCash.setPaymentStatus("PAID"); // completed before cash payments were recorded
        invoices.save(oldCash);

        SalesInvoice credit = completed("CREDIT", "0", line(biscuit, "2", "100"));

        UsernamePasswordAuthenticationToken admin = new UsernamePasswordAuthenticationToken(
                "admin", "x", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        CustomerCreditHistoryResponse history = customerController.creditHistory(shop.getId(), admin);

        assertThat(history.getCreditInvoices()).extracting(row -> row.getId())
                .containsExactlyInAnyOrder(unpaidCash.getId(), credit.getId());
        assertThat(history.getTotalOutstanding()).isEqualByComparingTo("700"); // 500 cash + 200 credit

        CreditFollowUpResponse followUp = customerController.creditFollowUp();
        assertThat(followUp.getTotalOutstanding()).isEqualByComparingTo("700");
    }

    // ---------------------------------------------------------------- purchase side stale copy

    @Test
    void aPurchaseInvoiceCompletedMeanwhileCannotBeEditedFromAStaleCopy() {

        PurchaseInvoice draft = new PurchaseInvoice();
        draft.setDocumentNumber("CBL-1");
        draft.setInvoiceDate(TODAY);

        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(biscuit);
        item.setQuantity(new BigDecimal("5"));
        item.setUnitPrice(new BigDecimal("100"));
        PurchaseInvoice saved = purchaseInvoiceService.saveInvoiceWithItems(draft, List.of(item));
        entityManager.flush();

        // The request holds this copy (DRAFT) while another request completes the invoice.
        PurchaseInvoice stale = purchaseInvoices.findById(saved.getId()).orElseThrow();
        entityManager.createQuery("update PurchaseInvoice i set i.status = 'COMPLETED' where i.id = :id")
                .setParameter("id", saved.getId()).executeUpdate();

        stale.setNotes("late edit");
        PurchaseInvoiceItem other = new PurchaseInvoiceItem();
        other.setProduct(biscuit);
        other.setQuantity(new BigDecimal("50"));
        other.setUnitPrice(new BigDecimal("100"));

        assertThatThrownBy(() -> purchaseInvoiceService.saveInvoiceWithItems(stale, List.of(other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completed purchase invoice cannot be edited");

        entityManager.clear();
        assertThat(purchaseInvoices.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(purchaseInvoiceService.getItemsByInvoiceId(saved.getId()).get(0).getQuantity())
                .isEqualByComparingTo("5");
    }

    // ---------------------------------------------------------------- audit trail

    @Test
    void stockAdjustmentsAreAudited() {

        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setAdjustmentType("DAMAGED");
        adjustment.setQuantity(new BigDecimal("3"));

        stockAdjustmentService.saveAdjustment(adjustment, biscuit.getId(), "office1");

        assertThat(audited("STOCK_ADJUSTED")).isTrue();
    }

    @Test
    void advancesAndSalaryPaymentsAreAudited() {

        Employee employee = new Employee();
        employee.setEmployeeCode("E9");
        employee.setFullName("Kamal Driver");
        employee.setDesignation("DRIVER");
        employee = employees.save(employee);

        EmployeeAdvance advance = employeeAdvanceService.giveAdvance(
                employee.getId(), new BigDecimal("1000.005"), TODAY, "festival");
        assertThat(advance.getAmount()).isEqualByComparingTo("1000.01");
        assertThat(audited("ADVANCE_GIVEN")).isTrue();

        employeeAdvanceService.updateAdvance(advance.getId(), new BigDecimal("900"), TODAY, null);
        assertThat(audited("ADVANCE_UPDATED")).isTrue();

        employeeAdvanceService.deleteAdvance(advance.getId());
        assertThat(audited("ADVANCE_DELETED")).isTrue();

        employeeSalaryService.recordSalaryPayment(
                employee.getId(), java.time.YearMonth.from(TODAY).toString(), new BigDecimal("50000"), TODAY, null, List.of());
        assertThat(audited("SALARY_PAID")).isTrue();
    }

    @Test
    void theSameEmployeeAndMonthCannotBePaidTwice() {

        Employee employee = new Employee();
        employee.setEmployeeCode("E10");
        employee.setFullName("Sunil Rep");
        employee.setDesignation("REP");
        employee = employees.save(employee);

        String month = java.time.YearMonth.from(TODAY).minusMonths(1).toString();
        employeeSalaryService.recordSalaryPayment(employee.getId(), month, new BigDecimal("50000"), TODAY, null, List.of());

        Long employeeId = employee.getId();
        assertThatThrownBy(() -> employeeSalaryService.recordSalaryPayment(
                employeeId, month, new BigDecimal("50000"), TODAY, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already recorded for Sunil Rep");

        // A different month is fine.
        employeeSalaryService.recordSalaryPayment(
                employeeId, java.time.YearMonth.from(TODAY).toString(), new BigDecimal("50000"), TODAY, null, List.of());
    }
}
