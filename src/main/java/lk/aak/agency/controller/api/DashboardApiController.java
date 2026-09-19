package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.DashboardSummaryResponse;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.PaymentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardApiController {

    private static final List<String> OUTSTANDING_STATUSES = List.of("UNPAID", "PARTIALLY_PAID");

    private final CustomerRepository customerRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final ProductRepository productRepository;
    private final AuditLogRepository auditLogRepository;
    private final PaymentService paymentService;

    public DashboardApiController(
            CustomerRepository customerRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            ProductRepository productRepository,
            AuditLogRepository auditLogRepository,
            PaymentService paymentService) {

        this.customerRepository = customerRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.productRepository = productRepository;
        this.auditLogRepository = auditLogRepository;
        this.paymentService = paymentService;
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
                recentActivity
        );
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
