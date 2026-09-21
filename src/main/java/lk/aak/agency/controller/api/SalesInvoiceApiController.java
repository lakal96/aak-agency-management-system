package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.CreditOverrideRequest;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.SalesInvoiceCreateRequest;
import lk.aak.agency.dto.api.SalesInvoiceDetailResponse;
import lk.aak.agency.dto.api.SalesInvoiceItemResponse;
import lk.aak.agency.dto.api.SalesInvoiceResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.PaymentService;
import lk.aak.agency.service.SalesInvoiceService;
import lk.aak.agency.service.SalesRepScopeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.NoSuchElementException;

/**
 * List/detail were built first as read-only; create/update/complete were added once the
 * frontend's line-item builder was ready (see SalesInvoiceService.saveInvoiceWithItems, which
 * this delegates to unchanged - same validation, stock, and credit-limit rules as the
 * Thymeleaf form).
 */
@RestController
@RequestMapping("/api/v1/sales-invoices")
public class SalesInvoiceApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final SalesInvoiceService salesInvoiceService;
    private final PaymentService paymentService;
    private final SalesRepScopeService salesRepScopeService;

    public SalesInvoiceApiController(
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            CustomerRepository customerRepository,
            ProductRepository productRepository,
            SalesInvoiceService salesInvoiceService,
            PaymentService paymentService,
            SalesRepScopeService salesRepScopeService) {

        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.salesInvoiceService = salesInvoiceService;
        this.paymentService = paymentService;
        this.salesRepScopeService = salesRepScopeService;
    }

    @GetMapping
    public PageResponse<SalesInvoiceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q,
            Authentication authentication) {

        Page<SalesInvoice> invoices = salesInvoiceRepository.search(
                q == null ? "" : q,
                salesRepScopeService.resolveScopedEmployeeId(authentication),
                PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "invoiceDate"))
        );

        return PageResponse.from(invoices, SalesInvoiceResponse::new);
    }

    @GetMapping("/{id}")
    public SalesInvoiceDetailResponse getById(@PathVariable Long id, Authentication authentication) {

        SalesInvoice invoice = salesInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sales invoice not found."));

        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);
        Customer invoiceCustomer = invoice.getCustomer();
        if (scopedEmployeeId != null
                && (invoiceCustomer == null || !scopedEmployeeId.equals(invoiceCustomer.getAssignedEmployeeId()))) {
            throw new NoSuchElementException("Sales invoice not found.");
        }

        var items = salesInvoiceItemRepository.findBySalesInvoiceIdOrderByIdAsc(id).stream()
                .map(SalesInvoiceItemResponse::new)
                .toList();

        BigDecimal paid = paymentService.getPaidAmount(id);
        BigDecimal net = invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount();

        return new SalesInvoiceDetailResponse(new SalesInvoiceResponse(invoice), items, paid, net.subtract(paid));
    }

    @PostMapping
    public ResponseEntity<SalesInvoiceDetailResponse> create(@Valid @RequestBody SalesInvoiceCreateRequest request) {
        SalesInvoice invoice = new SalesInvoice();
        SalesInvoice saved = saveWithItems(invoice, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(saved));
    }

    @PutMapping("/{id}")
    public SalesInvoiceDetailResponse update(@PathVariable Long id, @Valid @RequestBody SalesInvoiceCreateRequest request) {
        SalesInvoice invoice = salesInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Sales invoice not found."));
        SalesInvoice saved = saveWithItems(invoice, request);
        return toDetail(saved);
    }

    @PostMapping("/{id}/complete")
    public void complete(@PathVariable Long id) {
        salesInvoiceService.completeInvoice(id);
    }

    @PostMapping("/{id}/complete-with-override")
    public void completeWithOverride(@PathVariable Long id, @Valid @RequestBody CreditOverrideRequest request) {
        salesInvoiceService.completeInvoiceWithCreditOverride(id, request.getApprovedBy(), request.getReason());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        salesInvoiceService.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    }

    private SalesInvoice saveWithItems(SalesInvoice invoice, SalesInvoiceCreateRequest request) {
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Selected shop was not found."));

        invoice.setCustomer(customer);
        invoice.setInvoiceNumber(request.getInvoiceNumber());
        invoice.setInvoiceDate(request.getInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setRouteCode(request.getRouteCode());
        invoice.setSaleType(request.getSaleType());
        invoice.setDiscountAmount(request.getDiscountAmount());
        invoice.setReturnAmount(request.getReturnAmount());
        invoice.setNotes(request.getNotes());

        var items = request.getItems().stream().map(itemRequest -> {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Selected product was not found."));

            SalesInvoiceItem item = new SalesInvoiceItem();
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            return item;
        }).toList();

        return salesInvoiceService.saveInvoiceWithItems(invoice, items);
    }

    private SalesInvoiceDetailResponse toDetail(SalesInvoice invoice) {
        var items = salesInvoiceItemRepository.findBySalesInvoiceIdOrderByIdAsc(invoice.getId()).stream()
                .map(SalesInvoiceItemResponse::new)
                .toList();

        BigDecimal paid = paymentService.getPaidAmount(invoice.getId());
        BigDecimal net = invoice.getNetAmount() == null ? BigDecimal.ZERO : invoice.getNetAmount();

        return new SalesInvoiceDetailResponse(new SalesInvoiceResponse(invoice), items, paid, net.subtract(paid));
    }
}
