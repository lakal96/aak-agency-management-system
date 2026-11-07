package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceCreateRequest;
import lk.aak.agency.dto.api.PurchaseInvoiceDetailResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceItemResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceResponse;
import lk.aak.agency.dto.api.SupplierBalanceResponse;
import lk.aak.agency.dto.api.SupplierPaymentRequest;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SupplierPayment;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.service.PurchaseInvoiceService;
import lk.aak.agency.service.PdfService;
import lk.aak.agency.service.SupplierPaymentService;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * List/detail were built first as read-only; create/update were added once the frontend's
 * line-item builder was ready. Completing an invoice (which also requires an uploaded CBL
 * invoice file + verification checkbox) is deferred - it needs its own file-upload UI.
 */
@RestController
@RequestMapping("/api/v1/purchase-invoices")
public class PurchaseInvoiceApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    private final ProductRepository productRepository;
    private final PurchaseInvoiceService purchaseInvoiceService;
    private final PurchaseInvoiceFileService purchaseInvoiceFileService;
    private final SupplierPaymentService supplierPaymentService;
    private final PdfService pdfService;

    public PurchaseInvoiceApiController(
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            PurchaseInvoiceItemRepository purchaseInvoiceItemRepository,
            ProductRepository productRepository,
            PurchaseInvoiceService purchaseInvoiceService,
            PurchaseInvoiceFileService purchaseInvoiceFileService,
            SupplierPaymentService supplierPaymentService,
            PdfService pdfService) {

        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.purchaseInvoiceItemRepository = purchaseInvoiceItemRepository;
        this.productRepository = productRepository;
        this.purchaseInvoiceService = purchaseInvoiceService;
        this.purchaseInvoiceFileService = purchaseInvoiceFileService;
        this.supplierPaymentService = supplierPaymentService;
        this.pdfService = pdfService;
    }

    @GetMapping("/supplier-balance")
    public SupplierBalanceResponse supplierBalance() {
        return new SupplierBalanceResponse(supplierPaymentService.getSupplierBalanceSummary());
    }

    @PostMapping("/supplier-balance/pay")
    public ResponseEntity<SupplierBalanceResponse> paySupplierBalance(@Valid @RequestBody SupplierPaymentRequest request) {

        SupplierPayment payment = new SupplierPayment();
        payment.setPurchaseInvoiceId(request.getPurchaseInvoiceId());
        payment.setAmount(request.getAmount());
        payment.setPaymentDate(request.getPaymentDate());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setReferenceNumber(request.getReferenceNumber());
        payment.setNotes(request.getNotes());

        supplierPaymentService.recordPayment(payment);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SupplierBalanceResponse(supplierPaymentService.getSupplierBalanceSummary()));
    }

    @GetMapping
    public PageResponse<PurchaseInvoiceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<PurchaseInvoice> invoices = purchaseInvoiceRepository.search(
                q == null ? "" : q,
                PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "invoiceDate").and(Sort.by(Sort.Direction.DESC, "id")))
        );

        return PageResponse.from(invoices, PurchaseInvoiceResponse::new);
    }

    @GetMapping("/{id}")
    public PurchaseInvoiceDetailResponse getById(@PathVariable Long id) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));

        var items = purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(id).stream()
                .map(PurchaseInvoiceItemResponse::new)
                .toList();

        return new PurchaseInvoiceDetailResponse(new PurchaseInvoiceResponse(invoice), items);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {

        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));

        var items = purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(id);

        byte[] pdf = pdfService.generatePurchaseInvoicePdf(invoice, items);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"purchase-invoice-" + invoice.getDocumentNumber() + ".pdf\"")
                .body(pdf);
    }

    @PostMapping
    public ResponseEntity<PurchaseInvoiceDetailResponse> create(@Valid @RequestBody PurchaseInvoiceCreateRequest request) {
        PurchaseInvoice invoice = new PurchaseInvoice();
        PurchaseInvoice saved = saveWithItems(invoice, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(saved));
    }

    @PutMapping("/{id}")
    public PurchaseInvoiceDetailResponse update(@PathVariable Long id, @Valid @RequestBody PurchaseInvoiceCreateRequest request) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));
        PurchaseInvoice saved = saveWithItems(invoice, request);
        return toDetail(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        purchaseInvoiceService.deleteInvoice(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/upload")
    public PurchaseInvoiceResponse uploadFile(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));

        // Refuse early (nothing written to disk) once the bill is completed and verified ...
        purchaseInvoiceService.requireFileCanBeReplaced(invoice);

        var storedFile = purchaseInvoiceFileService.storeFile(file);

        PurchaseInvoiceService.FileReplacement replacement;
        try {
            // ... and again under the invoice row lock, in case it was completed in the meantime.
            replacement = purchaseInvoiceService.attachInvoiceFile(id, storedFile);
        } catch (RuntimeException exception) {
            purchaseInvoiceFileService.deleteFile(storedFile.storedFileName());
            throw exception;
        }

        String previousStoredFileName = replacement.previousStoredFileName();

        if (previousStoredFileName != null && !previousStoredFileName.isBlank()) {
            purchaseInvoiceFileService.deleteFile(previousStoredFileName);
        }

        return new PurchaseInvoiceResponse(replacement.invoice());
    }

    @GetMapping("/{id}/original-file")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));

        if (!invoice.hasInvoiceFile()) {
            throw new IllegalArgumentException("No original CBL invoice file is attached.");
        }

        Resource resource = purchaseInvoiceFileService.loadFile(invoice.getInvoiceFileStoredName());

        MediaType mediaType = invoice.getInvoiceFileContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(invoice.getInvoiceFileContentType());

        ContentDisposition disposition = ContentDisposition.inline()
                .filename(invoice.getInvoiceFileOriginalName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    /** Goods check: this line was counted; `quantity` is what really arrived (the invoice's own figure if all of it did). */
    @PostMapping("/{id}/items/{itemId}/receive")
    public PurchaseInvoiceDetailResponse receiveItem(@PathVariable Long id, @PathVariable Long itemId,
                                                     @RequestBody java.util.Map<String, java.math.BigDecimal> body,
                                                     java.security.Principal principal) {
        purchaseInvoiceService.receiveItem(id, itemId, body == null ? null : body.get("quantity"),
                principal == null ? "unknown" : principal.getName());
        return toDetail(purchaseInvoiceRepository.findById(id).orElseThrow());
    }

    /** Takes the tick off a line so it can be counted again. */
    @DeleteMapping("/{id}/items/{itemId}/receive")
    public PurchaseInvoiceDetailResponse unreceiveItem(@PathVariable Long id, @PathVariable Long itemId) {
        purchaseInvoiceService.unreceiveItem(id, itemId);
        return toDetail(purchaseInvoiceRepository.findById(id).orElseThrow());
    }

    @PostMapping("/{id}/complete")
    public PurchaseInvoiceResponse complete(
            @PathVariable Long id, @RequestParam(defaultValue = "false") boolean verificationConfirmed) {

        purchaseInvoiceService.completeInvoice(id, verificationConfirmed);

        return new PurchaseInvoiceResponse(purchaseInvoiceRepository.findById(id).orElseThrow());
    }

    private PurchaseInvoice saveWithItems(PurchaseInvoice invoice, PurchaseInvoiceCreateRequest request) {
        invoice.setDocumentNumber(request.getDocumentNumber());
        invoice.setTaxInvoiceNumber(request.getTaxInvoiceNumber());
        invoice.setPoNumber(request.getPoNumber());
        invoice.setInvoiceDate(request.getInvoiceDate());
        invoice.setDeliveryDate(request.getDeliveryDate());
        invoice.setSupplierName(request.getSupplierName());
        invoice.setTerritory(request.getTerritory());
        invoice.setPlaceOfSupply(request.getPlaceOfSupply());
        invoice.setDiscountAmount(request.getDiscountAmount());
        invoice.setVatAmount(request.getVatAmount());
        invoice.setPaymentMethod(request.getPaymentMethod());
        invoice.setNotes(request.getNotes());

        // lines already on the draft: an untouched line keeps CBL's own figures (quantity in dozens, exact amount)
        java.util.Map<Long, PurchaseInvoiceItem> existing = new java.util.HashMap<>();
        if (invoice.getId() != null) {
            purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(invoice.getId())
                    .forEach(item -> existing.put(item.getId(), item));
        }

        java.util.List<PurchaseInvoiceItem> items = new java.util.ArrayList<>();
        int lineNumber = 0;
        for (var itemRequest : request.getItems()) {
            lineNumber++;
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Selected product was not found."));

            PurchaseInvoiceItem item = new PurchaseInvoiceItem();
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setExpiryDate(itemRequest.getExpiryDate());

            java.math.BigDecimal computed = itemRequest.getQuantity().multiply(itemRequest.getUnitPrice())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal amount = itemRequest.getAmount();

            PurchaseInvoiceItem old = itemRequest.getId() == null ? null : existing.get(itemRequest.getId());
            boolean untouched = old != null && old.getProduct().getId().equals(product.getId())
                    && old.getQuantity().compareTo(itemRequest.getQuantity()) == 0;
            if (untouched) {
                // same product and pieces: CBL's own quantity / unit / price still describe this line
                item.setInvoicedQuantity(old.getInvoicedQuantity());
                item.setInvoicedUnit(old.getInvoicedUnit());
                item.setInvoicedUnitPrice(old.getInvoicedUnitPrice());
                if (amount == null && old.getInvoicedAmount() != null && old.getUnitPrice().compareTo(itemRequest.getUnitPrice()) == 0) {
                    amount = old.getInvoicedAmount();
                }
            }

            if (amount != null) {
                java.math.BigDecimal tolerance = itemRequest.getQuantity().multiply(new java.math.BigDecimal("0.006"))
                        .add(new java.math.BigDecimal("0.02"));
                if (amount.subtract(computed).abs().compareTo(tolerance) > 0) {
                    throw new IllegalArgumentException("Line " + lineNumber + " (" + product.getProductName() + "): the amount "
                            + amount.toPlainString() + " does not match quantity x unit price (" + computed.toPlainString()
                            + "). Correct the unit price or the amount.");
                }
                if (amount.compareTo(computed) != 0) {
                    item.setInvoicedAmount(amount);   // the printed amount counts, not quantity x rounded price
                }
            }
            items.add(item);
        }

        return purchaseInvoiceService.saveInvoiceWithItems(invoice, items);
    }

    private PurchaseInvoiceDetailResponse toDetail(PurchaseInvoice invoice) {
        var items = purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(invoice.getId()).stream()
                .map(PurchaseInvoiceItemResponse::new)
                .toList();

        return new PurchaseInvoiceDetailResponse(new PurchaseInvoiceResponse(invoice), items);
    }
}
