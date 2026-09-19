package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.ReportSummaryResponse;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnItemRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import lk.aak.agency.repository.SupplierReturnItemRepository;
import lk.aak.agency.repository.SupplierReturnRepository;
import lk.aak.agency.service.InventoryService;
import lk.aak.agency.service.PaymentService;
import lk.aak.agency.service.SupplierPaymentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * A pragmatic subset of ReportController's much larger Thymeleaf report - the headline
 * figures a distributor checks daily, all backed by real data via existing services/
 * repositories (no new business logic). Full parity (salary/advances aggregation, expiring
 * stock, per-product breakdowns) is left to the Thymeleaf report for now.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportApiController {

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final InventoryService inventoryService;
    private final SupplierPaymentService supplierPaymentService;
    private final ShopReturnRepository shopReturnRepository;
    private final ShopReturnItemRepository shopReturnItemRepository;
    private final SupplierReturnRepository supplierReturnRepository;
    private final SupplierReturnItemRepository supplierReturnItemRepository;

    public ReportApiController(
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            InventoryService inventoryService,
            SupplierPaymentService supplierPaymentService,
            ShopReturnRepository shopReturnRepository,
            ShopReturnItemRepository shopReturnItemRepository,
            SupplierReturnRepository supplierReturnRepository,
            SupplierReturnItemRepository supplierReturnItemRepository) {

        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
        this.supplierPaymentService = supplierPaymentService;
        this.shopReturnRepository = shopReturnRepository;
        this.shopReturnItemRepository = shopReturnItemRepository;
        this.supplierReturnRepository = supplierReturnRepository;
        this.supplierReturnItemRepository = supplierReturnItemRepository;
    }

    @GetMapping("/summary")
    public ReportSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        LocalDate today = LocalDate.now();
        LocalDate resolvedFrom = fromDate != null ? fromDate : today.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate resolvedTo = toDate != null ? toDate : today;

        List<SalesInvoice> salesInvoices = salesInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> inRange(invoice.getInvoiceDate(), resolvedFrom, resolvedTo))
                .toList();

        List<PurchaseInvoice> purchaseInvoices = purchaseInvoiceRepository.findAllByOrderByInvoiceDateDesc().stream()
                .filter(invoice -> inRange(invoice.getInvoiceDate(), resolvedFrom, resolvedTo))
                .toList();

        List<Payment> payments = paymentRepository.findByPaymentDateBetweenOrderByPaymentDateDesc(resolvedFrom, resolvedTo);

        BigDecimal totalSales = salesInvoices.stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .map(invoice -> zeroIfNull(invoice.getNetAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPurchases = purchaseInvoices.stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .map(invoice -> zeroIfNull(invoice.getTotalAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPayments = payments.stream()
                .filter(payment -> !"CHEQUE".equalsIgnoreCase(payment.getPaymentMethod()) || "CLEARED".equalsIgnoreCase(payment.getChequeStatus()))
                .map(payment -> zeroIfNull(payment.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal outstandingCredit = salesInvoices.stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoice -> !"PAID".equalsIgnoreCase(invoice.getPaymentStatus()))
                .map(invoice -> zeroIfNull(invoice.getNetAmount()).subtract(paymentService.getPaidAmount(invoice.getId())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal shopReturnsValue = shopReturnRepository.findAllByOrderByReturnDateDesc().stream()
                .filter(shopReturn -> inRange(shopReturn.getReturnDate(), resolvedFrom, resolvedTo))
                .flatMap(shopReturn -> shopReturnItemRepository.findByShopReturnIdOrderByIdAsc(shopReturn.getId()).stream())
                .map(item -> zeroIfNull(item.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal supplierReturnsValue = supplierReturnRepository.findAllByOrderByReturnDateDesc().stream()
                .filter(supplierReturn -> inRange(supplierReturn.getReturnDate(), resolvedFrom, resolvedTo))
                .flatMap(supplierReturn -> supplierReturnItemRepository.findBySupplierReturnIdOrderByIdAsc(supplierReturn.getId()).stream())
                .map(item -> zeroIfNull(item.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new ReportSummaryResponse(
                resolvedFrom,
                resolvedTo,
                totalSales,
                totalPurchases,
                totalPayments,
                outstandingCredit,
                supplierPaymentService.getSupplierBalanceSummary().totalOwed(),
                shopReturnsValue,
                supplierReturnsValue,
                salesInvoices.stream().filter(i -> "COMPLETED".equalsIgnoreCase(i.getStatus())).count(),
                purchaseInvoices.stream().filter(i -> "COMPLETED".equalsIgnoreCase(i.getStatus())).count(),
                payments.size(),
                inventoryService.getLowStockProducts().size(),
                inventoryService.getOutOfStockProducts().size()
        );
    }

    private boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
