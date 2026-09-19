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
import lk.aak.agency.service.SupplierPaymentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public DashboardApiController(
            CustomerRepository customerRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            ProductRepository productRepository,
            AuditLogRepository auditLogRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            InventoryService inventoryService,
            SupplierPaymentService supplierPaymentService) {

        this.customerRepository = customerRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.supplierPaymentService = supplierPaymentService;
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse summary() {

        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.with(TemporalAdjusters.firstDayOfMonth());

        List<SalesInvoice> outstandingInvoices = salesInvoiceRepository.findByPaymentStatusIn(OUTSTANDING_STATUSES);

        BigDecimal outstandingReceivables = outstandingInvoices.stream()
                .map(invoice -> {
                    BigDecimal net = invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount();
                    BigDecimal paid = paymentService.getPaidAmount(invoice.getId());
                    return net.subtract(paid);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal todaySales = salesInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoice -> today.equals(invoice.getInvoiceDate()))
                .map(invoice -> invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Payment> todaysPayments = paymentRepository.findByPaymentDateBetweenOrderByPaymentDateDesc(today, today);

        BigDecimal todayCollections = todaysPayments.stream()
                .filter(payment -> !"CHEQUE".equalsIgnoreCase(payment.getPaymentMethod())
                        || "CLEARED".equalsIgnoreCase(payment.getChequeStatus()))
                .map(payment -> payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long pendingChequeCount = paymentRepository.findAllByOrderByPaymentDateDesc().stream()
                .filter(payment -> "CHEQUE".equalsIgnoreCase(payment.getPaymentMethod()))
                .filter(payment -> PENDING_CHEQUE_STATUSES.contains(payment.getChequeStatus()))
                .count();

        List<DashboardSummaryResponse.TrendPoint> salesTrend = buildSalesTrend(today);

        List<DashboardSummaryResponse.RecentActivity> recentActivity = auditLogRepository
                .search(null, PageRequest.of(0, 8, Sort.by(Sort.Direction.DESC, "id")))
                .stream()
                .map(this::toRecentActivity)
                .toList();

        return new DashboardSummaryResponse(
                customerRepository.count(),
                customerRepository.countByStatus("ACTIVE"),
                salesInvoiceRepository.sumNetAmountBetween(monthStart, today),
                outstandingReceivables,
                outstandingInvoices.size(),
                productRepository.count(),
                todaySales,
                todayCollections,
                inventoryService.getLowStockProducts().size(),
                inventoryService.getOutOfStockProducts().size(),
                supplierPaymentService.getSupplierBalanceSummary().totalOwed(),
                pendingChequeCount,
                salesTrend,
                recentActivity
        );
    }

    /** Last 7 days of completed sales, by day - real data, no synthetic/mock points. */
    private List<DashboardSummaryResponse.TrendPoint> buildSalesTrend(LocalDate today) {

        LocalDate rangeStart = today.minusDays(6);

        Map<LocalDate, BigDecimal> totalsByDate = salesInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
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

