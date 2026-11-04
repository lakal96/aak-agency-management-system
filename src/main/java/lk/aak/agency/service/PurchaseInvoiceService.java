package lk.aak.agency.service;

import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class PurchaseInvoiceService {

    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogService auditLogService;
    private final RowLockService rowLockService;

    public PurchaseInvoiceService(
            PurchaseInvoiceRepository purchaseInvoiceRepository,
            PurchaseInvoiceItemRepository purchaseInvoiceItemRepository,
            StockMovementRepository stockMovementRepository,
            AuditLogService auditLogService,
            RowLockService rowLockService) {

        this.rowLockService = rowLockService;
        this.purchaseInvoiceRepository = purchaseInvoiceRepository;
        this.purchaseInvoiceItemRepository = purchaseInvoiceItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.auditLogService = auditLogService;
    }

    public List<PurchaseInvoice> getAllInvoices() {
        return purchaseInvoiceRepository
                .findAllByOrderByInvoiceDateDesc();
    }

    public Page<PurchaseInvoice> getInvoicePage(
            String search, int page, int size) {

        return purchaseInvoiceRepository.search(
                search == null ? "" : search.trim(),
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.DESC, "invoiceDate")
                )
        );
    }

    public PurchaseInvoice getInvoiceById(Long id) {
        return purchaseInvoiceRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Purchase invoice not found: " + id
                        )
                );
    }

    public List<PurchaseInvoiceItem> getItemsByInvoiceId(
            Long invoiceId) {

        return purchaseInvoiceItemRepository
                .findByPurchaseInvoiceIdOrderByIdAsc(invoiceId);
    }

    @Transactional
    public PurchaseInvoice saveInvoice(
            PurchaseInvoice purchaseInvoice) {

        validateInvoiceCanBeSaved(purchaseInvoice);
        setInvoiceDefaults(purchaseInvoice);
        resetVerification(purchaseInvoice);

        BigDecimal subtotal =
                zeroIfNull(purchaseInvoice.getSubtotal());

        BigDecimal discountAmount =
                zeroIfNull(purchaseInvoice.getDiscountAmount());

        BigDecimal vatAmount =
                zeroIfNull(purchaseInvoice.getVatAmount());

        BigDecimal totalAmount =
                subtotal
                        .subtract(discountAmount)
                        .add(vatAmount);

        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            totalAmount = BigDecimal.ZERO;
        }

        purchaseInvoice.setTotalBoxes(BigDecimal.ZERO);
        purchaseInvoice.setSubtotal(subtotal);
        purchaseInvoice.setDiscountAmount(discountAmount);
        purchaseInvoice.setVatAmount(vatAmount);
        purchaseInvoice.setTotalAmount(totalAmount);

        return purchaseInvoiceRepository.save(purchaseInvoice);
    }

    /*
     * READ_COMMITTED + a row lock on the invoice: an edit racing a "complete" can no longer
     * re-save a stale DRAFT status over a completed invoice (stock would move while the
     * invoice showed different quantities).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PurchaseInvoice saveInvoiceWithItems(
            PurchaseInvoice purchaseInvoice,
            List<PurchaseInvoiceItem> items) {

        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add at least one product to the purchase invoice."
            );
        }

        if (purchaseInvoice != null && purchaseInvoice.getId() != null) {

            Long invoiceId = purchaseInvoice.getId();

            // Lock FIRST, then read the invoice as it is now: the caller's copy may be stale.
            rowLockService.forget(purchaseInvoice);
            rowLockService.lockPurchaseInvoice(invoiceId);

            PurchaseInvoice existingInvoice = getInvoiceById(invoiceId);

            if ("COMPLETED".equalsIgnoreCase(existingInvoice.getStatus())) {
                throw new IllegalArgumentException(
                        "A completed purchase invoice cannot be edited."
                );
            }

            validateInvoiceCanBeSaved(purchaseInvoice);

            if (existingInvoice != purchaseInvoice) {
                copyEditableFields(purchaseInvoice, existingInvoice);
            }

            purchaseInvoice = existingInvoice;

        } else {
            validateInvoiceCanBeSaved(purchaseInvoice);
        }

        setInvoiceDefaults(purchaseInvoice);
        resetVerification(purchaseInvoice);

        BigDecimal subtotal = BigDecimal.ZERO;

        for (PurchaseInvoiceItem item : items) {
            validateItem(item);

            item.setUnit(item.getProduct().getUnit());
            item.calculateAmount();

            subtotal = subtotal.add(
                    zeroIfNull(item.getAmount())
            );
        }

        BigDecimal discountAmount =
                zeroIfNull(purchaseInvoice.getDiscountAmount());

        BigDecimal vatAmount =
                zeroIfNull(purchaseInvoice.getVatAmount());

        validateDiscount(subtotal, discountAmount);

        BigDecimal totalAmount =
                subtotal
                        .subtract(discountAmount)
                        .add(vatAmount);

        purchaseInvoice.setTotalBoxes(BigDecimal.ZERO);
        purchaseInvoice.setSubtotal(subtotal);
        purchaseInvoice.setDiscountAmount(discountAmount);
        purchaseInvoice.setVatAmount(vatAmount);
        purchaseInvoice.setTotalAmount(totalAmount);

        PurchaseInvoice savedInvoice =
                purchaseInvoiceRepository.save(purchaseInvoice);

        purchaseInvoiceRepository.flush();

        if (savedInvoice.getId() != null) {
            purchaseInvoiceItemRepository
                    .deleteByPurchaseInvoiceId(
                            savedInvoice.getId()
                    );

            purchaseInvoiceItemRepository.flush();
        }

        for (PurchaseInvoiceItem item : items) {
            item.setId(null);
            item.setPurchaseInvoice(savedInvoice);
            item.setUnit(item.getProduct().getUnit());
            item.calculateAmount();

            purchaseInvoiceItemRepository.save(item);
        }

        purchaseInvoiceItemRepository.flush();

        return savedInvoice;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PurchaseInvoiceItem saveItem(
            PurchaseInvoiceItem item) {

        PurchaseInvoice invoice =
                item.getPurchaseInvoice();

        if (invoice == null || invoice.getId() == null) {
            throw new IllegalArgumentException(
                    "Save the purchase invoice before adding products."
            );
        }

        rowLockService.lockPurchaseInvoice(invoice.getId());

        if ("COMPLETED".equalsIgnoreCase(
                getInvoiceById(invoice.getId()).getStatus())) {

            throw new IllegalArgumentException(
                    "Products cannot be added to a completed invoice."
            );
        }

        validateItem(item);

        item.calculateAmount();

        PurchaseInvoiceItem savedItem =
                purchaseInvoiceItemRepository.save(item);

        purchaseInvoiceItemRepository.flush();

        resetInvoiceVerification(invoice.getId());
        recalculateInvoiceTotals(invoice.getId());

        return savedItem;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteItem(Long itemId) {

        PurchaseInvoiceItem item =
                purchaseInvoiceItemRepository
                        .findById(itemId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Purchase invoice item was not found."
                                )
                        );

        Long invoiceId = item.getPurchaseInvoice().getId();

        rowLockService.lockPurchaseInvoice(invoiceId);

        PurchaseInvoice invoice = getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "Products cannot be removed from a completed invoice."
            );
        }

        purchaseInvoiceItemRepository.deleteById(itemId);
        purchaseInvoiceItemRepository.flush();

        resetInvoiceVerification(invoiceId);
        recalculateInvoiceTotals(invoiceId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeInvoice(
            Long invoiceId,
            boolean verificationConfirmed) {

        // Serialise with edits, file uploads and a second "complete": the later caller waits here
        // and then sees the final status instead of racing the first one.
        rowLockService.lockPurchaseInvoice(invoiceId);

        PurchaseInvoice invoice =
                getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "This purchase invoice is already completed."
            );
        }

        if (!invoice.hasInvoiceFile()) {
            throw new IllegalArgumentException(
                    "Upload the original CBL invoice image or PDF "
                            + "before completing this invoice."
            );
        }

        if (!verificationConfirmed) {
            throw new IllegalArgumentException(
                    "Compare the original CBL invoice with the entered "
                            + "details and confirm the verification checkbox."
            );
        }

        List<PurchaseInvoiceItem> items =
                getItemsByInvoiceId(invoiceId);

        if (items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add at least one product before completing the invoice."
            );
        }

        recalculateInvoiceTotals(invoiceId);

        BigDecimal subtotal =
                zeroIfNull(invoice.getSubtotal());

        BigDecimal discountAmount =
                zeroIfNull(invoice.getDiscountAmount());

        validateDiscount(subtotal, discountAmount);

        for (PurchaseInvoiceItem item : items) {
            validateItem(item);

            boolean movementExists =
                    stockMovementRepository
                            .existsByReferenceTypeAndReferenceItemId(
                                    "PURCHASE_INVOICE_ITEM",
                                    item.getId()
                            );

            if (!movementExists) {
                StockMovement movement =
                        new StockMovement();

                movement.setProduct(item.getProduct());
                movement.setMovementType("PURCHASE");
                movement.setQuantityChange(item.getQuantity());
                movement.setStockUnit(item.getUnit());

                movement.setReferenceType(
                        "PURCHASE_INVOICE_ITEM"
                );

                movement.setReferenceNumber(
                        invoice.getDocumentNumber()
                );

                movement.setReferenceItemId(item.getId());

                movement.setNotes(
                        "Stock received from CBL purchase invoice "
                                + invoice.getDocumentNumber()
                );

                stockMovementRepository.save(movement);
            }
        }

        invoice.setInvoiceVerified(true);
        invoice.setInvoiceVerifiedAt(BusinessTime.now());
        invoice.setStatus("COMPLETED");

        purchaseInvoiceRepository.save(invoice);
    }

    /*
     * Temporary compatibility method.
     * The controller will be updated in the next step
     * to send the verification checkbox value.
     */
    @Transactional
    public void completeInvoice(Long invoiceId) {
        completeInvoice(invoiceId, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteInvoice(Long invoiceId) {

        rowLockService.lockPurchaseInvoice(invoiceId);

        PurchaseInvoice invoice =
                getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "A completed invoice cannot be deleted."
            );
        }

        purchaseInvoiceItemRepository
                .deleteByPurchaseInvoiceId(invoiceId);

        purchaseInvoiceItemRepository.flush();

        purchaseInvoiceRepository.deleteById(invoiceId);

        auditLogService.record(
                "PURCHASE_INVOICE_DELETED", "PurchaseInvoice", invoiceId,
                "Deleted purchase invoice \"" + invoice.getDocumentNumber() + "\""
        );
    }

    /** What replacing the original CBL invoice file produced: the saved invoice and the old stored file to delete. */
    public record FileReplacement(PurchaseInvoice invoice, String previousStoredFileName) {
    }

    /**
     * Attaches (or replaces) the scanned original CBL invoice. Under the invoice row lock; once the
     * invoice is completed (verified against that very document) the file can no longer be replaced.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FileReplacement attachInvoiceFile(
            Long invoiceId, PurchaseInvoiceFileService.StoredInvoiceFile storedFile) {

        rowLockService.lockPurchaseInvoice(invoiceId);

        PurchaseInvoice invoice = getInvoiceById(invoiceId);

        requireFileCanBeReplaced(invoice);

        String previousStoredFileName = invoice.getInvoiceFileStoredName();

        invoice.setInvoiceFileOriginalName(storedFile.originalFileName());
        invoice.setInvoiceFileStoredName(storedFile.storedFileName());
        invoice.setInvoiceFileContentType(storedFile.contentType());
        invoice.setInvoiceFileSize(storedFile.fileSize());
        invoice.setInvoiceFileUploadedAt(BusinessTime.now());

        // A different document means any earlier verification no longer applies.
        resetVerification(invoice);

        return new FileReplacement(purchaseInvoiceRepository.save(invoice), previousStoredFileName);
    }

    /** Cheap early check so a refused upload is not written to disk at all. */
    public void requireFileCanBeReplaced(PurchaseInvoice invoice) {

        if ("COMPLETED".equalsIgnoreCase(invoice.getStatus()) || invoice.isInvoiceVerified()) {
            throw new IllegalArgumentException(
                    "This purchase invoice is already completed and verified against the original "
                            + "CBL document, so the file cannot be replaced."
            );
        }
    }

    private void copyEditableFields(PurchaseInvoice from, PurchaseInvoice to) {

        to.setDocumentNumber(from.getDocumentNumber());
        to.setTaxInvoiceNumber(from.getTaxInvoiceNumber());
        to.setPoNumber(from.getPoNumber());
        to.setInvoiceDate(from.getInvoiceDate());
        to.setDeliveryDate(from.getDeliveryDate());
        to.setSupplierName(from.getSupplierName());
        to.setTerritory(from.getTerritory());
        to.setPlaceOfSupply(from.getPlaceOfSupply());
        to.setDiscountAmount(from.getDiscountAmount());
        to.setVatAmount(from.getVatAmount());
        to.setPaymentMethod(from.getPaymentMethod());
        to.setNotes(from.getNotes());

        // The old Thymeleaf form carries a newly uploaded file on the invoice it submits. Take the
        // file only if it is newer than what is stored (a stale copy must not undo a later upload).
        if (from.getInvoiceFileUploadedAt() != null
                && (to.getInvoiceFileUploadedAt() == null
                || from.getInvoiceFileUploadedAt().isAfter(to.getInvoiceFileUploadedAt()))) {

            to.setInvoiceFileOriginalName(from.getInvoiceFileOriginalName());
            to.setInvoiceFileStoredName(from.getInvoiceFileStoredName());
            to.setInvoiceFileContentType(from.getInvoiceFileContentType());
            to.setInvoiceFileSize(from.getInvoiceFileSize());
            to.setInvoiceFileUploadedAt(from.getInvoiceFileUploadedAt());
        }
    }

    private void validateInvoiceCanBeSaved(
            PurchaseInvoice purchaseInvoice) {

        if (purchaseInvoice == null) {
            throw new IllegalArgumentException(
                    "Purchase invoice information is required."
            );
        }

        if (purchaseInvoice.getDocumentNumber() == null
                || purchaseInvoice.getDocumentNumber().isBlank()) {

            throw new IllegalArgumentException(
                    "Document number is required."
            );
        }

        if (purchaseInvoice.getInvoiceDate() == null) {
            throw new IllegalArgumentException(
                    "Invoice date is required."
            );
        }

        if (purchaseInvoice.getId() != null) {
            PurchaseInvoice existingInvoice =
                    getInvoiceById(purchaseInvoice.getId());

            if ("COMPLETED".equalsIgnoreCase(
                    existingInvoice.getStatus())) {

                throw new IllegalArgumentException(
                        "A completed purchase invoice cannot be edited."
                );
            }
        }

        Optional<PurchaseInvoice> duplicateInvoice =
                purchaseInvoiceRepository
                        .findByDocumentNumber(
                                purchaseInvoice
                                        .getDocumentNumber()
                                        .trim()
                        );

        if (duplicateInvoice.isPresent()
                && !duplicateInvoice.get().getId()
                .equals(purchaseInvoice.getId())) {

            throw new IllegalArgumentException(
                    "Document number already exists."
            );
        }
    }

    private void setInvoiceDefaults(
            PurchaseInvoice purchaseInvoice) {

        purchaseInvoice.setSupplierName(
                "CBL Foods International (Pvt) Ltd"
        );

        purchaseInvoice.setPlaceOfSupply("Ranala");

        if (purchaseInvoice.getStatus() == null
                || purchaseInvoice.getStatus().isBlank()) {

            purchaseInvoice.setStatus("DRAFT");
        }

        purchaseInvoice.setTotalBoxes(BigDecimal.ZERO);

        purchaseInvoice.setSubtotal(
                zeroIfNull(purchaseInvoice.getSubtotal())
        );

        purchaseInvoice.setDiscountAmount(
                zeroIfNull(
                        purchaseInvoice.getDiscountAmount()
                )
        );

        purchaseInvoice.setVatAmount(
                zeroIfNull(purchaseInvoice.getVatAmount())
        );

        purchaseInvoice.setTotalAmount(
                zeroIfNull(purchaseInvoice.getTotalAmount())
        );
    }

    private void validateItem(
            PurchaseInvoiceItem item) {

        if (item == null) {
            throw new IllegalArgumentException(
                    "Purchase invoice item is required."
            );
        }

        if (item.getProduct() == null) {
            throw new IllegalArgumentException(
                    "Select a product for every invoice row."
            );
        }

        if (item.getQuantity() == null
                || item.getQuantity()
                .compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "Purchase quantity must be greater than zero."
            );
        }

        if (item.getUnitPrice() == null
                || item.getUnitPrice()
                .compareTo(BigDecimal.ZERO) < 0) {

            throw new IllegalArgumentException(
                    "Purchase rate cannot be negative."
            );
        }

        item.setUnit(item.getProduct().getUnit());
    }

    private void validateDiscount(
            BigDecimal subtotal,
            BigDecimal discountAmount) {

        if (discountAmount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException(
                    "Discount amount cannot be greater than the invoice value."
            );
        }
    }

    private void recalculateInvoiceTotals(
            Long invoiceId) {

        PurchaseInvoice invoice =
                getInvoiceById(invoiceId);

        List<PurchaseInvoiceItem> items =
                getItemsByInvoiceId(invoiceId);

        BigDecimal subtotal = BigDecimal.ZERO;

        for (PurchaseInvoiceItem item : items) {
            item.calculateAmount();

            subtotal = subtotal.add(
                    zeroIfNull(item.getAmount())
            );
        }

        BigDecimal discountAmount =
                zeroIfNull(invoice.getDiscountAmount());

        BigDecimal vatAmount =
                zeroIfNull(invoice.getVatAmount());

        BigDecimal totalAmount =
                subtotal
                        .subtract(discountAmount)
                        .add(vatAmount);

        if (totalAmount.compareTo(BigDecimal.ZERO) < 0) {
            totalAmount = BigDecimal.ZERO;
        }

        invoice.setTotalBoxes(BigDecimal.ZERO);
        invoice.setSubtotal(subtotal);
        invoice.setDiscountAmount(discountAmount);
        invoice.setVatAmount(vatAmount);
        invoice.setTotalAmount(totalAmount);

        purchaseInvoiceRepository.save(invoice);
    }

    private void resetInvoiceVerification(
            Long invoiceId) {

        PurchaseInvoice invoice =
                getInvoiceById(invoiceId);

        resetVerification(invoice);

        purchaseInvoiceRepository.save(invoice);
    }

    private void resetVerification(
            PurchaseInvoice invoice) {

        invoice.setInvoiceVerified(false);
        invoice.setInvoiceVerifiedAt(null);
    }

    private BigDecimal zeroIfNull(
            BigDecimal value) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }
}