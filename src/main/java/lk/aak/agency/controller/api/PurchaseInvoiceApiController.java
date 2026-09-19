package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceCreateRequest;
import lk.aak.agency.dto.api.PurchaseInvoiceDetailResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceItemResponse;
import lk.aak.agency.dto.api.PurchaseInvoiceResponse;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.service.PurchaseInvoiceFileService;
import lk.aak.agency.service.PurchaseInvoiceService;
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

    public PurchaseInvoiceApiController(
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            PurchaseInvoiceItemRepository purchaseInvoiceItemRepository,
            ProductRepository productRepository,
            PurchaseInvoiceService purchaseInvoiceService,
            PurchaseInvoiceFileService purchaseInvoiceFileService) {

        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.purchaseInvoiceItemRepository = purchaseInvoiceItemRepository;
        this.productRepository = productRepository;
        this.purchaseInvoiceService = purchaseInvoiceService;
        this.purchaseInvoiceFileService = purchaseInvoiceFileService;
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
    public PurchaseInvoiceDetailResponse getById(@PathVariable Long id) {
        PurchaseInvoice invoice = purchaseInvoiceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Purchase invoice not found."));

        var items = purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(id).stream()
                .map(PurchaseInvoiceItemResponse::new)
                .toList();

        return new PurchaseInvoiceDetailResponse(new PurchaseInvoiceResponse(invoice), items);
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

        var storedFile = purchaseInvoiceFileService.storeFile(file);
        String previousStoredFileName = invoice.getInvoiceFileStoredName();

        invoice.setInvoiceFileOriginalName(storedFile.originalFileName());
        invoice.setInvoiceFileStoredName(storedFile.storedFileName());
        invoice.setInvoiceFileContentType(storedFile.contentType());
        invoice.setInvoiceFileSize(storedFile.fileSize());
        invoice.setInvoiceFileUploadedAt(LocalDateTime.now());

        PurchaseInvoice saved = purchaseInvoiceRepository.save(invoice);

        if (previousStoredFileName != null && !previousStoredFileName.isBlank()) {
            purchaseInvoiceFileService.deleteFile(previousStoredFileName);
        }

        return new PurchaseInvoiceResponse(saved);
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

        var items = request.getItems().stream().map(itemRequest -> {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Selected product was not found."));

            PurchaseInvoiceItem item = new PurchaseInvoiceItem();
            item.setProduct(product);
            item.setQuantity(itemRequest.getQuantity());
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.setExpiryDate(itemRequest.getExpiryDate());
            return item;
        }).toList();

        return purchaseInvoiceService.saveInvoiceWithItems(invoice, items);
    }

    private PurchaseInvoiceDetailResponse toDetail(PurchaseInvoice invoice) {
        var items = purchaseInvoiceItemRepository.findByPurchaseInvoiceIdOrderByIdAsc(invoice.getId()).stream()
                .map(PurchaseInvoiceItemResponse::new)
                .toList();

        return new PurchaseInvoiceDetailResponse(new PurchaseInvoiceResponse(invoice), items);
    }
}
