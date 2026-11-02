package lk.aak.agency.controller;

import lk.aak.agency.controller.api.DashboardApiController;
import lk.aak.agency.dto.api.DashboardSummaryResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.InventoryService;
import lk.aak.agency.service.PaymentService;
import lk.aak.agency.service.SalesRepScopeService;
import lk.aak.agency.service.SupplierPaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sales reps may only see the shops assigned to them. The dashboard used to total the whole
 * agency for everyone, including what is owed to CBL and the latest audit-log entries.
 */
@ExtendWith(MockitoExtension.class)
class DashboardApiScopeTest {

    private static final Long REP_EMPLOYEE_ID = 5L;

    @Mock private CustomerRepository customerRepository;
    @Mock private SalesInvoiceRepository salesInvoiceRepository;
    @Mock private ProductRepository productRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentService paymentService;
    @Mock private InventoryService inventoryService;
    @Mock private SupplierPaymentService supplierPaymentService;
    @Mock private SalesRepScopeService salesRepScopeService;

    private DashboardApiController controller;
    private SalesInvoice myInvoice;
    private SalesInvoice otherInvoice;

    @BeforeEach
    void setUp() {
        controller = new DashboardApiController(
                customerRepository, salesInvoiceRepository, productRepository, auditLogRepository,
                paymentRepository, paymentService, inventoryService, supplierPaymentService, salesRepScopeService
        );

        Customer myShop = shop(1L, REP_EMPLOYEE_ID);
        Customer otherShop = shop(2L, 6L);
        myInvoice = invoice(10L, myShop, "1000");
        otherInvoice = invoice(11L, otherShop, "5000");

        lenient().when(salesInvoiceRepository.findByPaymentStatusIn(any())).thenReturn(List.of(myInvoice, otherInvoice));
        lenient().when(salesInvoiceRepository.findAllByOrderByInvoiceDateDesc()).thenReturn(List.of(myInvoice, otherInvoice));
        lenient().when(salesInvoiceRepository.sumNetAmountBetween(any(), any())).thenReturn(new BigDecimal("6000"));
        lenient().when(paymentService.getPaidAmount(any())).thenReturn(BigDecimal.ZERO);
        lenient().when(paymentRepository.findByPaymentDateBetweenOrderByPaymentDateDesc(any(), any())).thenReturn(List.of());
        lenient().when(paymentRepository.findAllByOrderByPaymentDateDesc()).thenReturn(List.of());
        lenient().when(customerRepository.findAll()).thenReturn(List.of(myShop, otherShop));
        lenient().when(customerRepository.count()).thenReturn(2L);
        lenient().when(customerRepository.countByStatus("ACTIVE")).thenReturn(2L);
        lenient().when(productRepository.count()).thenReturn(3L);
        lenient().when(inventoryService.getLowStockProducts()).thenReturn(List.of());
        lenient().when(inventoryService.getOutOfStockProducts()).thenReturn(List.of());
        lenient().when(supplierPaymentService.getSupplierBalanceSummary())
                .thenReturn(new SupplierPaymentService.SupplierBalanceSummary(List.of(), new BigDecimal("304250")));
        lenient().when(auditLogRepository.search(any(), any())).thenReturn(Page.empty());
    }

    private Customer shop(Long id, Long assignedEmployeeId) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setStatus("ACTIVE");
        customer.setAssignedEmployeeId(assignedEmployeeId);
        return customer;
    }

    private SalesInvoice invoice(Long id, Customer customer, String net) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setId(id);
        invoice.setCustomer(customer);
        invoice.setStatus("COMPLETED");
        invoice.setPaymentStatus("UNPAID");
        invoice.setInvoiceDate(LocalDate.now());
        invoice.setNetAmount(new BigDecimal(net));
        return invoice;
    }

    private Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("someone", "x", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void salesRepSeesOnlyTheirOwnShopsFigures() {

        Authentication rep = auth("SALES_REP");
        when(salesRepScopeService.resolveScopedEmployeeId(rep)).thenReturn(REP_EMPLOYEE_ID);

        DashboardSummaryResponse summary = controller.summary(rep);

        assertThat(summary.getTodaySales()).isEqualByComparingTo("1000");
        assertThat(summary.getSalesThisMonth()).isEqualByComparingTo("1000");
        assertThat(summary.getOutstandingReceivables()).isEqualByComparingTo("1000");
        assertThat(summary.getOutstandingInvoiceCount()).isEqualTo(1);
        assertThat(summary.getTotalCustomers()).isEqualTo(1);
        assertThat(summary.getActiveCustomers()).isEqualTo(1);
        assertThat(summary.getSalesTrend()).allSatisfy(point -> assertThat(point.amount()).isIn(BigDecimal.ZERO, new BigDecimal("1000")));
    }

    @Test
    void salesRepNeverReceivesTheCblDebtOrTheAuditTrail() {

        Authentication rep = auth("SALES_REP");
        when(salesRepScopeService.resolveScopedEmployeeId(rep)).thenReturn(REP_EMPLOYEE_ID);

        DashboardSummaryResponse summary = controller.summary(rep);

        assertThat(summary.getTotalOwedToSupplier()).isEqualByComparingTo("0");
        assertThat(summary.getRecentActivity()).isEmpty();
        verifyNoInteractions(supplierPaymentService);
        verify(auditLogRepository, never()).search(any(), any());
    }

    @Test
    void aRepWhoseLoginIsNotLinkedToAnEmployeeSeesNothing() {

        Authentication rep = auth("SALES_REP");
        when(salesRepScopeService.resolveScopedEmployeeId(rep)).thenReturn(SalesRepScopeService.NO_ACCESS_SENTINEL);

        DashboardSummaryResponse summary = controller.summary(rep);

        assertThat(summary.getTodaySales()).isEqualByComparingTo("0");
        assertThat(summary.getOutstandingReceivables()).isEqualByComparingTo("0");
        assertThat(summary.getTotalCustomers()).isEqualTo(0);
    }

    @Test
    void ownerAndOfficeStillSeeTheWholeAgency() {

        Authentication owner = auth("ADMIN");
        when(salesRepScopeService.resolveScopedEmployeeId(owner)).thenReturn(null);

        DashboardSummaryResponse summary = controller.summary(owner);

        assertThat(summary.getTodaySales()).isEqualByComparingTo("6000");
        assertThat(summary.getSalesThisMonth()).isEqualByComparingTo("6000");
        assertThat(summary.getOutstandingReceivables()).isEqualByComparingTo("6000");
        assertThat(summary.getOutstandingInvoiceCount()).isEqualTo(2);
        assertThat(summary.getTotalCustomers()).isEqualTo(2);
        assertThat(summary.getTotalOwedToSupplier()).isEqualByComparingTo("304250");
        verify(auditLogRepository).search(any(), any());
    }
}
