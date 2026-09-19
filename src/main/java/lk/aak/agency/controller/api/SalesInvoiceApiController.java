package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.SalesInvoiceDetailResponse;
import lk.aak.agency.dto.api.SalesInvoiceItemResponse;
import lk.aak.agency.dto.api.SalesInvoiceResponse;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.PaymentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

/**
 * Read-only for now - creating/editing a multi-line invoice needs a proper line-item builder
 * UI, deferred to a follow-up phase (same as the Thymeleaf app's own inline-validation work
 * was deferred for these two invoice types earlier in this project).
 */
@RestController
@RequestMapping("/api/v1/sales-invoices")
public class SalesInvoiceApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final PaymentService paymentService;

    public SalesInvoiceApiController(
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            PaymentService paymentService) {

        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.paymentService = paymentService;
    }

    @GetMapping
    public PageResponse<SalesInvoiceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<SalesInvoice> invoices = salesInvoiceRepository.search(
                q == null ? "" : q,
                PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "invoiceDate"))
        );

        return PageResponse.from(invoices, SalesInvoiceResponse::new);
    }

    @GetMapping("/{id}")
    public SalesInvoiceDetailResponse getById(@PathVariable Long id) {

        SalesInvoice invoice = salesInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sales invoice not found."));

        var items = salesInvoiceItemRepository.findBySalesInvoiceIdOrderByIdAsc(id).stream()
                .map(SalesInvoiceItemResponse::new)
                .toList();

        BigDecimal paid = paymentService.getPaidAmount(id);
        BigDecimal net = invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount();

        return new SalesInvoiceDetailResponse(new SalesInvoiceResponse(invoice), items, paid, net.subtract(paid));
    }
}
