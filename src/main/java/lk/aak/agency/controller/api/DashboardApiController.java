package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.DashboardSummaryResponse;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.model.Payment;
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
import lk.aak.agency.util.BusinessTime;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private static final List<String> OUTSTANDING_STATUSES = List.of("UNPAID", "PARTIALLY_PAID");
    private static final List<String> PENDING_CHEQUE_STATUSES = List.of("RECEIVED", "DEPOSITED");

    private final CustomerRepository customerRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final ProductRepository productRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final SupplierPaymentService supplierPaymentService;
    private final SalesRepScopeService salesRepScopeService;

    public DashboardApiController(
            CustomerRepository customerRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            ProductRepository productRepository,
            AuditLogRepository auditLogRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            InventoryService inventoryService,
            SupplierPaymentService supplierPaymentService,
            SalesRepScopeService salesRepScopeService) {

        this.customerRepository = customerRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.supplierPaymentService = supplierPaymentService;
        this.salesRepScopeService = salesRepScopeService;
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse summary(Authentication authentication) {

        LocalDate today = BusinessTime.today();
        LocalDate monthStart = today.with(TemporalAdjusters.firstDayOfMonth());

        // A sales rep sees figures for the shops assigned to them only - never the whole agency.
        // null = owner/office (everything); a rep with no linked employee record matches no shop.
        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);
        boolean repView = scopedEmployeeId != null;
        Predicate<SalesInvoice> invoiceVisible = invoice -> !repView
                || (invoice.getCustomer() != null
                && scopedEmployeeId.equals(invoice.getCustomer().getAssignedEmployeeId()));

        // A draft starts out UNPAID too, but nothing is owed until the bill is completed (the credit
        // limit check, credit follow-up and reports all count completed bills only).
        List<SalesInvoice> outstandingInvoices = salesInvoiceRepository.findByPaymentStatusIn(OUTSTANDING_STATUSES).stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoiceVisible)
                .toList();

        BigDecimal outstandingReceivables = outstandingInvoices.stream()
                .map(invoice -> {
                    BigDecimal net = invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount();
                    BigDecimal paid = paymentService.getPaidAmount(invoice.getId());
                    return net.subtract(paid);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SalesInvoice> completedInvoices = salesInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoiceVisible)
                .toList();

        BigDecimal todaySales = completedInvoices.stream()
                .filter(invoice -> today.equals(invoice.getInvoiceDate()))
                .map(invoice -> invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal salesThisMonth = repView
                ? completedInvoices.stream()
                        .filter(invoice -> invoice.getInvoiceDate() != null
                                && !invoice.getInvoiceDate().isBefore(monthStart)
                                && !invoice.getInvoiceDate().isAfter(today))
                        .map(invoice -> invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                : salesInvoiceRepository.sumNetAmountBetween(monthStart, today);

        Predicate<Payment> paymentVisible = payment -> !repView
                || (payment.getSalesInvoice() != null && invoiceVisible.test(payment.getSalesInvoice()));

        List<Payment> todaysPayments = paymentRepository.findByPaymentDateBetweenOrderByPaymentDateDesc(today, today).stream()
                .filter(paymentVisible)
                .toList();

        BigDecimal todayCollections = todaysPayments.stream()
                .filter(payment -> !"CHEQUE".equalsIgnoreCase(payment.getPaymentMethod())
                        || "CLEARED".equalsIgnoreCase(payment.getChequeStatus()))
                .map(payment -> payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingChequeCount = paymentRepository.findAllByOrderByPaymentDateDesc().stream()
                .filter(paymentVisible)
                .filter(payment -> "CHEQUE".equalsIgnoreCase(payment.getPaymentMethod()))
                .filter(payment -> PENDING_CHEQUE_STATUSES.contains(payment.getChequeStatus()))
                .count();

        List<DashboardSummaryResponse.TrendPoint> salesTrend = buildSalesTrend(today, invoiceVisible);

        long totalCustomers;
        long activeCustomers;
        if (repView) {
            List<lk.aak.agency.model.Customer> myShops = customerRepository.findAll().stream()
                    .filter(customer -> scopedEmployeeId.equals(customer.getAssignedEmployeeId()))
                    .toList();
            totalCustomers = myShops.size();
            activeCustomers = myShops.stream().filter(customer -> "ACTIVE".equalsIgnoreCase(customer.getStatus())).count();
        } else {
            totalCustomers = customerRepository.count();
            activeCustomers = customerRepository.countByStatus("ACTIVE");
        }

        // What the agency owes CBL and the audit trail are owner/office information, not a rep's.
        BigDecimal totalOwedToSupplier = repView
                ? BigDecimal.ZERO
                : supplierPaymentService.getSupplierBalanceSummary().totalOwed();

        List<DashboardSummaryResponse.RecentActivity> recentActivity = repView
                ? List.of()
                : auditLogRepository
                        .search(null, PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "id")))
                        .stream()
                        .map(this::toRecentActivity)
                        .toList();

        return new DashboardSummaryResponse(
                totalCustomers,
                activeCustomers,
                salesThisMonth,
                outstandingReceivables,
                outstandingInvoices.size(),
                productRepository.count(),
                todaySales,
                todayCollections,
                inventoryService.getLowStockProducts().size(),
                inventoryService.getOutOfStockProducts().size(),
                totalOwedToSupplier,
                pendingChequeCount,
                salesTrend,
                recentActivity
        );
    }

    /** Last 7 days of completed sales, by day - real data, no synthetic/mock points. */
    private List<DashboardSummaryResponse.TrendPoint> buildSalesTrend(LocalDate today, Predicate<SalesInvoice> invoiceVisible) {

        LocalDate rangeStart = today.minusDays(6);

        Map<LocalDate, BigDecimal> totalsByDate = salesInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoiceVisible)
                .filter(invoice -> invoice.getInvoiceDate() != null
                        && !invoice.getInvoiceDate().isBefore(rangeStart)
                        && !invoice.getInvoiceDate().isAfter(today))
                .collect(Collectors.groupingBy(
                        SalesInvoice::getInvoiceDate,
                        Collectors.reducing(
                                BigDecimal.ZERO,
                                invoice -> invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount(),
                                BigDecimal::add)));

        List<DashboardSummaryResponse.TrendPoint> trend = new ArrayList<>();
        for (LocalDate date = rangeStart; !date.isAfter(today); date = date.plusDays(1)) {
            trend.add(new DashboardSummaryResponse.TrendPoint(date, totalsByDate.getOrDefault(date, BigDecimal.ZERO)));
        }
        return trend;
    }

    private DashboardSummaryResponse.RecentActivity toRecentActivity(AuditLog log) {
        return new DashboardSummaryResponse.RecentActivity(
                log.getUsername(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDetails(),
                log.getCreatedAt()
        );
    }
}

