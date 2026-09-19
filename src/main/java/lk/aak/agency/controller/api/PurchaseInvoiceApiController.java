package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceResponse;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/** Read-only for now - see SalesInvoiceApiController for why line-item creation is deferred. */
@RestController
@RequestMapping("/api/v1/purchase-invoices")
public class PurchaseInvoiceApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;

    public PurchaseInvoiceApiController(PurchaseInvoiceRepository purchaseInvoiceRepository) {
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
    }

    @GetMapping
    public PageResponse<PurchaseInvoiceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<PurchaseInvoice> invoices = purchaseInvoiceRepository.search(
                q == null ? "" : q,
                PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "invoiceDate"))
        );

        return PageResponse.from(invoices, PurchaseInvoiceResponse::new);
    }

    @GetMapping("/{id}")
    public PurchaseInvoiceResponse getById(@PathVariable Long id) {
        return purchaseInvoiceRepository.findById(id)
                .map(PurchaseInvoiceResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));
    }
}
