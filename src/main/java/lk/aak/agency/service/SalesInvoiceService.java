package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.util.BusinessTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SalesInvoiceService {

    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository
            salesInvoiceItemRepository;
    private final StockMovementRepository
            stockMovementRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;
    private final RowLockService rowLockService;

    public SalesInvoiceService(
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            StockMovementRepository stockMovementRepository,
            PaymentRepository paymentRepository,
            AuditLogService auditLogService,
            RowLockService rowLockService) {

        this.salesInvoiceRepository =
                salesInvoiceRepository;

        this.salesInvoiceItemRepository =
                salesInvoiceItemRepository;

        this.stockMovementRepository =
                stockMovementRepository;

        this.paymentRepository =
                paymentRepository;

        this.rowLockService = rowLockService;
        this.auditLogService = auditLogService;
    }

    public List<SalesInvoice> getAllInvoices() {

        return salesInvoiceRepository
                .findAllByOrderByInvoiceDateDesc();
    }

    public Page<SalesInvoice> getInvoicePage(
            String search, int page, int size, Long scopedEmployeeId) {

        return salesInvoiceRepository.search(
                search == null ? "" : search.trim(),
                scopedEmployeeId,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.DESC, "invoiceDate")
                )
        );
    }

    public SalesInvoice getInvoiceById(Long id) {

        return salesInvoiceRepository.findById(id)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Sales invoice not found: " + id
                        )
                );
    }

    public List<SalesInvoiceItem> getItemsByInvoiceId(
            Long invoiceId) {

        return salesInvoiceItemRepository
                .findBySalesInvoiceIdOrderByIdAsc(
                        invoiceId
                );
    }

    @Transactional
    public SalesInvoice saveInvoice(
            SalesInvoice salesInvoice) {

        validateInvoice(salesInvoice);

        BigDecimal grossAmount =
                zeroIfNull(
                        salesInvoice.getGrossAmount()
                );

        BigDecimal discountAmount =
                zeroIfNull(
                        salesInvoice.getDiscountAmount()
                );

        BigDecimal returnAmount =
                zeroIfNull(
                        salesInvoice.getReturnAmount()
                );

        validateAdjustments(
                grossAmount,
                discountAmount,
                returnAmount
        );

        BigDecimal netAmount =
                grossAmount
                        .subtract(discountAmount)
                        .subtract(returnAmount);

        salesInvoice.setGrossAmount(grossAmount);
        salesInvoice.setDiscountAmount(discountAmount);
        salesInvoice.setReturnAmount(returnAmount);
        salesInvoice.setNetAmount(netAmount);

        setInvoiceDefaults(salesInvoice);

        return salesInvoiceRepository.save(
                salesInvoice
        );
    }

    /*
     * READ_COMMITTED + a row lock on the bill: an edit and a "complete" for the same bill can no
     * longer interleave (the edit used to re-save a stale DRAFT status over a completed bill).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SalesInvoice saveInvoiceWithItems(
            SalesInvoice salesInvoice,
            List<SalesInvoiceItem> items) {

        if (salesInvoice == null) {

            throw new IllegalArgumentException(
                    "Sales invoice information is required."
            );
        }

        if (items == null || items.isEmpty()) {

            throw new IllegalArgumentException(
                    "Add at least one product before saving the invoice."
            );
        }

        SalesInvoice existingInvoice = null;

        if (salesInvoice.getId() != null) {

            Long invoiceId = salesInvoice.getId();

            // Lock FIRST, then read the bill as it is now: the copy the caller holds may be stale.
            rowLockService.forget(salesInvoice);
            rowLockService.lockSalesInvoice(invoiceId);

            existingInvoice =
                    getInvoiceById(invoiceId);

            if ("COMPLETED".equalsIgnoreCase(
                    existingInvoice.getStatus())) {

                throw new IllegalArgumentException(
                        "A completed sales invoice cannot be edited."
                );
            }

            // Check what was typed BEFORE touching the stored bill (a duplicate number must give
            // the friendly message, not a database error).
            validateInvoice(salesInvoice);

            if (existingInvoice != salesInvoice) {
                copyEditableFields(salesInvoice, existingInvoice);
            }

            // Status and payment status always stay as the database has them.
            salesInvoice = existingInvoice;

        } else {

            salesInvoice.setStatus("DRAFT");
            salesInvoice.setPaymentStatus("UNPAID");

            validateInvoice(salesInvoice);
        }

        BigDecimal grossAmount =
                BigDecimal.ZERO;

        for (SalesInvoiceItem item : items) {

            validateItem(item);
            requireBillableProduct(item.getProduct());

            item.setUnit(
                    item.getProduct().getUnit()
            );

            item.calculateAmount();

            grossAmount =
                    grossAmount.add(
                            zeroIfNull(
                                    item.getAmount()
                            )
                    );
        }

        BigDecimal discountAmount =
                zeroIfNull(
                        salesInvoice.getDiscountAmount()
                );

        BigDecimal returnAmount =
                zeroIfNull(
                        salesInvoice.getReturnAmount()
                );

        validateAdjustments(
                grossAmount,
                discountAmount,
                returnAmount
        );

        BigDecimal netAmount =
                grossAmount
                        .subtract(discountAmount)
                        .subtract(returnAmount);

        salesInvoice.setGrossAmount(grossAmount);
        salesInvoice.setDiscountAmount(discountAmount);
        salesInvoice.setReturnAmount(returnAmount);
        salesInvoice.setNetAmount(netAmount);

        SalesInvoice savedInvoice =
                salesInvoiceRepository.save(
                        salesInvoice
                );

        salesInvoiceRepository.flush();

        if (existingInvoice != null) {

            salesInvoiceItemRepository
                    .deleteBySalesInvoiceId(
                            savedInvoice.getId()
                    );

            salesInvoiceItemRepository.flush();
        }

        for (SalesInvoiceItem item : items) {

            item.setId(null);
            item.setSalesInvoice(savedInvoice);

            item.setUnit(
                    item.getProduct().getUnit()
            );

            item.calculateAmount();

            salesInvoiceItemRepository.save(item);
        }

        salesInvoiceItemRepository.flush();

        return savedInvoice;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SalesInvoiceItem saveItem(
            SalesInvoiceItem item) {

        if (item == null) {

            throw new IllegalArgumentException(
                    "Sales invoice product information is required."
            );
        }

        SalesInvoice invoice =
                item.getSalesInvoice();

        if (invoice == null
                || invoice.getId() == null) {

            throw new IllegalArgumentException(
                    "Save the sales invoice before adding products."
            );
        }

        rowLockService.lockSalesInvoice(invoice.getId());

        if ("COMPLETED".equalsIgnoreCase(
                getInvoiceById(invoice.getId()).getStatus())) {

            throw new IllegalArgumentException(
                    "Products cannot be added to a completed invoice."
            );
        }

        validateItem(item);
        requireBillableProduct(item.getProduct());

        item.setUnit(
                item.getProduct().getUnit()
        );

        item.calculateAmount();

        SalesInvoiceItem savedItem =
                salesInvoiceItemRepository.save(item);

        salesInvoiceItemRepository.flush();

        recalculateInvoiceTotals(
                invoice.getId()
        );

        return savedItem;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteItem(Long itemId) {

        SalesInvoiceItem item =
                salesInvoiceItemRepository
                        .findById(itemId)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Sales invoice item was not found."
                                )
                        );

        Long invoiceId =
                item.getSalesInvoice().getId();

        rowLockService.lockSalesInvoice(invoiceId);

        SalesInvoice invoice =
                getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "Products cannot be removed from a completed invoice."
            );
        }

        salesInvoiceItemRepository
                .deleteById(itemId);

        salesInvoiceItemRepository.flush();

        recalculateInvoiceTotals(invoiceId);

        validateCustomerCreditLimit(invoice);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeInvoice(Long invoiceId) {
        completeInvoiceInternal(invoiceId, null, null);
    }

    /**
     * Owner-approved credit exception: completes a credit sale even though it would exceed
     * the customer's credit limit. The approver recorded on the bill is always the logged-in
     * admin (taken from the login, never typed); the name typed in the form is kept only as an
     * extra note inside the reason. Every other check (stock availability, item validation)
     * still applies as normal.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void completeInvoiceWithCreditOverride(
            Long invoiceId, String approverNote, String overrideReason) {

        if (overrideReason == null || overrideReason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to override the credit limit.");
        }

        String note = approverNote == null ? "" : approverNote.trim();
        String loggedInAdmin = currentUsername();

        // Without a login (never the case through the API) fall back to the typed name.
        if (loggedInAdmin == null) {
            if (note.isEmpty()) {
                throw new IllegalArgumentException("Approver name is required for a credit override.");
            }
            loggedInAdmin = note;
        }

        String reason = overrideReason.trim();

        if (!note.isEmpty() && !note.equalsIgnoreCase(loggedInAdmin)) {
            reason = reason + " (approver named on the form: " + note + ")";
        }

        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }

        completeInvoiceInternal(invoiceId, loggedInAdmin, reason);

        auditLogService.record(
                "CREDIT_LIMIT_OVERRIDE", "SalesInvoice", invoiceId,
                "Approved by " + loggedInAdmin + ": " + reason
        );
    }

    private String currentUsername() {

        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getName() == null
                || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getName())) {

            return null;
        }

        return authentication.getName();
    }

    private void completeInvoiceInternal(
            Long invoiceId, String overrideApprovedBy, String overrideReason) {

        // Serialise concurrent completions of the same invoice: the second caller waits here,
        // then sees COMPLETED instead of racing the first one.
        rowLockService.lockSalesInvoice(invoiceId);

        SalesInvoice invoice =
                getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "This sales invoice is already completed."
            );
        }

        List<SalesInvoiceItem> items =
                getItemsByInvoiceId(invoiceId);

        if (items.isEmpty()) {

            throw new IllegalArgumentException(
                    "Add at least one product before completing the invoice."
            );
        }

        // Lock every product being sold (and the shop, for its credit limit) before any
        // stock or credit figure is read, so two simultaneous invoices cannot both pass.
        rowLockService.lockProducts(
                items.stream()
                        .map(SalesInvoiceItem::getProduct)
                        .filter(java.util.Objects::nonNull)
                        .map(Product::getId)
                        .toList()
        );

        if (invoice.getCustomer() != null) {
            rowLockService.lockCustomer(invoice.getCustomer().getId());
        }

        requireBillableShop(invoice.getCustomer());
        items.forEach(item -> requireBillableProduct(item.getProduct()));

        recalculateInvoiceTotals(invoiceId);

        /*
         * A credit sale is only ever a real commitment once it is completed
         * (a draft can be built up freely), so the credit limit is enforced
         * here rather than at draft-save time - unless an owner-approved
         * override has been supplied.
         */
        if (overrideApprovedBy == null) {
            validateCustomerCreditLimit(invoice);
        }

        /*
         * Several invoice rows may contain the same product.
         * Therefore quantities are grouped by product before
         * checking the available stock.
         */
        Map<Long, BigDecimal> requiredQuantities =
                new LinkedHashMap<>();

        Map<Long, Product> requiredProducts =
                new LinkedHashMap<>();

        for (SalesInvoiceItem item : items) {

            validateItem(item);

            Product product =
                    item.getProduct();

            BigDecimal quantity =
                    zeroIfNull(
                            item.getQuantity()
                    );

            requiredQuantities.merge(
                    product.getId(),
                    quantity,
                    BigDecimal::add
            );

            requiredProducts.put(
                    product.getId(),
                    product
            );
        }

        /*
         * Validate every required product before
         * creating any negative stock movement.
         */
        for (Map.Entry<Long, BigDecimal> entry
                : requiredQuantities.entrySet()) {

            Long productId =
                    entry.getKey();

            BigDecimal requiredQuantity =
                    entry.getValue();

            BigDecimal currentStock =
                    getAvailableStock(productId);

            if (currentStock.compareTo(
                    requiredQuantity) < 0) {

                Product product =
                        requiredProducts.get(productId);

                throw new IllegalArgumentException(
                        "Not enough stock for "
                                + product.getDisplayName()
                                + ". Available: "
                                + currentStock.stripTrailingZeros()
                                .toPlainString()
                                + " "
                                + getProductUnit(product)
                                + ", required: "
                                + requiredQuantity
                                .stripTrailingZeros()
                                .toPlainString()
                                + " "
                                + getProductUnit(product)
                                + "."
                );
            }
        }

        /*
         * Each item creates one negative stock movement.
         * The reference item check prevents the same
         * invoice row from deducting stock twice.
         */
        for (SalesInvoiceItem item : items) {

            boolean movementExists =
                    stockMovementRepository
                            .existsByReferenceTypeAndReferenceItemId(
                                    "SALES_INVOICE_ITEM",
                                    item.getId()
                            );

            if (!movementExists) {

                StockMovement stockMovement =
                        new StockMovement();

                stockMovement.setProduct(
                        item.getProduct()
                );

                stockMovement.setMovementType(
                        "SALE"
                );

                stockMovement.setQuantityChange(
                        item.getQuantity().negate()
                );

                stockMovement.setStockUnit(
                        item.getUnit()
                );

                stockMovement.setReferenceType(
                        "SALES_INVOICE_ITEM"
                );

                stockMovement.setReferenceNumber(
                        invoice.getInvoiceNumber()
                );

                stockMovement.setReferenceItemId(
                        item.getId()
                );

                stockMovement.setNotes(
                        "Stock issued to customer from sales invoice "
                                + invoice.getInvoiceNumber()
                );

                stockMovementRepository.save(
                        stockMovement
                );
            }
        }

        invoice.setStatus("COMPLETED");

        if (overrideApprovedBy != null) {
            invoice.setCreditOverrideApprovedBy(overrideApprovedBy);
            invoice.setCreditOverrideReason(overrideReason);
            invoice.setCreditOverrideAt(BusinessTime.now());
        }

        /*
         * A CASH bill completes as UNPAID exactly like a credit bill: the office records the cash
         * it receives as a payment (with the collector) so it shows in Collections and the
         * hand-over. It is a receivable until then, but it does not count against the credit limit.
         */
        if (invoice.getPaymentStatus() == null || invoice.getPaymentStatus().isBlank()) {
            invoice.setPaymentStatus("UNPAID");
        }

        salesInvoiceRepository.save(invoice);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteInvoice(Long invoiceId) {

        // Lock first: a delete racing a "complete" must see the final status, not a stale one.
        rowLockService.lockSalesInvoice(invoiceId);

        SalesInvoice invoice =
                getInvoiceById(invoiceId);

        if ("COMPLETED".equalsIgnoreCase(
                invoice.getStatus())) {

            throw new IllegalArgumentException(
                    "A completed sales invoice cannot be deleted."
            );
        }

        salesInvoiceItemRepository
                .deleteBySalesInvoiceId(invoiceId);

        salesInvoiceItemRepository.flush();

        salesInvoiceRepository
                .deleteById(invoiceId);

        auditLogService.record(
                "SALES_INVOICE_DELETED", "SalesInvoice", invoiceId,
                "Deleted sales invoice \"" + invoice.getInvoiceNumber() + "\""
        );
    }

    public BigDecimal getAvailableStock(
            Long productId) {

        if (productId == null) {
            return BigDecimal.ZERO;
        }

        return zeroIfNull(
                stockMovementRepository
                        .calculateCurrentStock(productId)
        );
    }

    private void recalculateInvoiceTotals(
            Long invoiceId) {

        SalesInvoice invoice =
                getInvoiceById(invoiceId);

        List<SalesInvoiceItem> items =
                getItemsByInvoiceId(invoiceId);

        BigDecimal grossAmount =
                BigDecimal.ZERO;

        for (SalesInvoiceItem item : items) {

            item.setUnit(
                    item.getProduct().getUnit()
            );

            item.calculateAmount();

            grossAmount =
                    grossAmount.add(
                            zeroIfNull(
                                    item.getAmount()
                            )
                    );
        }

        BigDecimal discountAmount =
                zeroIfNull(
                        invoice.getDiscountAmount()
                );

        BigDecimal returnAmount =
                zeroIfNull(
                        invoice.getReturnAmount()
                );

        BigDecimal netAmount =
                grossAmount
                        .subtract(discountAmount)
                        .subtract(returnAmount);

        /*
         * A draft can temporarily have no product rows.
         */
        if (netAmount.compareTo(
                BigDecimal.ZERO) < 0) {

            netAmount = BigDecimal.ZERO;
        }

        invoice.setGrossAmount(grossAmount);
        invoice.setDiscountAmount(discountAmount);
        invoice.setReturnAmount(returnAmount);
        invoice.setNetAmount(netAmount);

        salesInvoiceRepository.save(invoice);
    }

    private void validateInvoice(
            SalesInvoice salesInvoice) {

        if (salesInvoice == null) {

            throw new IllegalArgumentException(
                    "Sales invoice information is required."
            );
        }

        validateInvoiceNumber(salesInvoice);

        if (salesInvoice.getInvoiceDate() == null) {

            throw new IllegalArgumentException(
                    "Invoice date is required."
            );
        }

        if (salesInvoice.getCustomer() == null) {

            throw new IllegalArgumentException(
                    "Please select a customer."
            );
        }

        requireBillableShop(salesInvoice.getCustomer());

        salesInvoice.setSaleType(
                normaliseSaleType(salesInvoice.getSaleType())
        );

        applyDueDate(salesInvoice);
    }

    /** Blank means CREDIT (the long-standing default); anything other than CREDIT or CASH is refused. */
    private String normaliseSaleType(String saleType) {

        if (saleType == null || saleType.isBlank()) {
            return "CREDIT";
        }

        String normalised = saleType.trim().toUpperCase();

        if (!"CREDIT".equals(normalised) && !"CASH".equals(normalised)) {

            throw new IllegalArgumentException(
                    "Sale type must be CREDIT or CASH."
            );
        }

        return normalised;
    }

    private void requireBillableShop(Customer customer) {

        if (customer != null
                && "INACTIVE".equalsIgnoreCase(customer.getStatus())) {

            throw new IllegalArgumentException(
                    "The shop \"" + customer.getCustomerName()
                            + "\" is inactive and cannot be billed. "
                            + "Make the shop active first, or choose another shop."
            );
        }
    }

    private void requireBillableProduct(Product product) {

        if (product != null
                && "INACTIVE".equalsIgnoreCase(product.getStatus())) {

            throw new IllegalArgumentException(
                    "The product \"" + product.getDisplayName()
                            + "\" is inactive and cannot be billed. "
                            + "Remove it from the bill or make the product active first."
            );
        }
    }

    /**
     * Due date: refused when before the invoice date. When none was given it defaults to the invoice
     * date plus the shop's payment terms; a CASH bill is due on the invoice date itself (cash is
     * expected on the day).
     */
    private void applyDueDate(SalesInvoice salesInvoice) {

        LocalDate invoiceDate = salesInvoice.getInvoiceDate();

        if (salesInvoice.getDueDate() != null) {

            if (salesInvoice.getDueDate().isBefore(invoiceDate)) {

                throw new IllegalArgumentException(
                        "The due date cannot be before the invoice date."
                );
            }

            return;
        }

        if ("CASH".equals(salesInvoice.getSaleType())) {

            salesInvoice.setDueDate(invoiceDate);
            return;
        }

        Integer terms = salesInvoice.getCustomer().getPaymentTermsDays();

        salesInvoice.setDueDate(
                invoiceDate.plusDays(terms == null ? 0 : Math.max(terms, 0))
        );
    }

    private void copyEditableFields(SalesInvoice from, SalesInvoice to) {

        to.setInvoiceNumber(from.getInvoiceNumber());
        to.setCustomer(from.getCustomer());
        to.setInvoiceDate(from.getInvoiceDate());
        to.setDueDate(from.getDueDate());
        to.setRouteCode(from.getRouteCode());
        to.setSaleType(from.getSaleType());
        to.setDiscountAmount(from.getDiscountAmount());
        to.setReturnAmount(from.getReturnAmount());
        to.setNotes(from.getNotes());
    }

    private void validateInvoiceNumber(
            SalesInvoice salesInvoice) {

        if (salesInvoice.getInvoiceNumber() == null
                || salesInvoice.getInvoiceNumber().isBlank()) {

            throw new IllegalArgumentException(
                    "Invoice number is required."
            );
        }

        salesInvoice.setInvoiceNumber(
                salesInvoice
                        .getInvoiceNumber()
                        .trim()
        );

        Optional<SalesInvoice> existingInvoice =
                salesInvoiceRepository
                        .findByInvoiceNumber(
                                salesInvoice
                                        .getInvoiceNumber()
                        );

        if (existingInvoice.isPresent()
                && !existingInvoice.get().getId()
                .equals(salesInvoice.getId())) {

            throw new IllegalArgumentException(
                    "Invoice number already exists."
            );
        }
    }

    private void validateItem(
            SalesInvoiceItem item) {

        if (item == null) {

            throw new IllegalArgumentException(
                    "Sales invoice product information is required."
            );
        }

        if (item.getProduct() == null) {

            throw new IllegalArgumentException(
                    "Please select a product."
            );
        }

        // Quantities and prices are kept to 2 decimals (half-up), exactly as the database stores them.
        if (item.getQuantity() != null) {
            item.setQuantity(item.getQuantity().setScale(2, RoundingMode.HALF_UP));
        }

        if (item.getUnitPrice() != null) {
            item.setUnitPrice(item.getUnitPrice().setScale(2, RoundingMode.HALF_UP));
        }

        if (item.getQuantity() == null
                || item.getQuantity()
                .compareTo(BigDecimal.ZERO) <= 0) {

            throw new IllegalArgumentException(
                    "Product quantity must be greater than zero."
            );
        }

        if (item.getUnitPrice() == null
                || item.getUnitPrice()
                .compareTo(BigDecimal.ZERO) < 0) {

            throw new IllegalArgumentException(
                    "Selling rate cannot be negative."
            );
        }

        String productUnit =
                getProductUnit(
                        item.getProduct()
                );

        item.setUnit(productUnit);
    }

    private void validateAdjustments(
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal returnAmount) {

        if (discountAmount.compareTo(
                BigDecimal.ZERO) < 0) {

            throw new IllegalArgumentException(
                    "Discount amount cannot be negative."
            );
        }

        if (returnAmount.compareTo(
                BigDecimal.ZERO) < 0) {

            throw new IllegalArgumentException(
                    "Return amount cannot be negative."
            );
        }

        BigDecimal deductions =
                discountAmount.add(returnAmount);

        if (deductions.compareTo(
                grossAmount) > 0) {

            throw new IllegalArgumentException(
                    "Discount and return amounts cannot "
                            + "be greater than the gross amount."
            );
        }
    }

    private void setInvoiceDefaults(
            SalesInvoice salesInvoice) {

        if (salesInvoice.getStatus() == null
                || salesInvoice.getStatus().isBlank()) {

            salesInvoice.setStatus("DRAFT");
        }

        if (salesInvoice.getPaymentStatus() == null
                || salesInvoice.getPaymentStatus().isBlank()) {

            salesInvoice.setPaymentStatus("UNPAID");
        }

        if (salesInvoice.getSaleType() == null
                || salesInvoice.getSaleType().isBlank()) {

            salesInvoice.setSaleType("CREDIT");
        }
    }

    private void validateCustomerCreditLimit(
            SalesInvoice invoice) {

        if (!"CREDIT".equalsIgnoreCase(
                invoice.getSaleType())) {

            return;
        }

        if (invoice.getCustomer() == null
                || invoice.getCustomer().getId() == null) {

            throw new IllegalArgumentException(
                    "Please select a customer."
            );
        }

        BigDecimal creditLimit =
                zeroIfNull(
                        invoice.getCustomer()
                                .getCreditLimit()
                );

        BigDecimal currentOutstanding =
                BigDecimal.ZERO;

        List<SalesInvoice> customerInvoices =
                salesInvoiceRepository
                        .findByCustomerIdOrderByInvoiceDateDesc(
                                invoice.getCustomer().getId()
                        );

        for (SalesInvoice previousInvoice
                : customerInvoices) {

            if (previousInvoice.getId()
                    .equals(invoice.getId())) {

                continue;
            }

            boolean isCompleted =
                    "COMPLETED".equalsIgnoreCase(
                            previousInvoice.getStatus()
                    );

            boolean isCreditSale =
                    "CREDIT".equalsIgnoreCase(
                            previousInvoice.getSaleType()
                    );

            if (isCompleted && isCreditSale) {

                BigDecimal invoiceAmount =
                        zeroIfNull(
                                previousInvoice.getNetAmount()
                        );

                BigDecimal paidAmount =
                        zeroIfNull(
                                paymentRepository
                                        .calculatePaidAmount(
                                                previousInvoice.getId()
                                        )
                        );

                BigDecimal balance =
                        invoiceAmount.subtract(paidAmount);

                if (balance.compareTo(
                        BigDecimal.ZERO) > 0) {

                    currentOutstanding =
                            currentOutstanding.add(balance);
                }
            }
        }

        BigDecimal newInvoiceAmount =
                zeroIfNull(invoice.getNetAmount());

        BigDecimal newTotalOutstanding =
                currentOutstanding.add(
                        newInvoiceAmount
                );

        if (newTotalOutstanding.compareTo(
                creditLimit) > 0) {

            BigDecimal availableCredit =
                    creditLimit.subtract(
                            currentOutstanding
                    );

            if (availableCredit.compareTo(
                    BigDecimal.ZERO) < 0) {

                availableCredit = BigDecimal.ZERO;
            }

            throw new IllegalArgumentException(
                    "Customer credit limit exceeded. "
                            + "Credit limit: Rs. "
                            + creditLimit.toPlainString()
                            + ", current outstanding: Rs. "
                            + currentOutstanding.toPlainString()
                            + ", available credit: Rs. "
                            + availableCredit.toPlainString()
                            + ", this invoice: Rs. "
                            + newInvoiceAmount.toPlainString()
                            + "."
            );
        }
    }


    private String getProductUnit(
            Product product) {

        if (product.getUnit() == null
                || product.getUnit().isBlank()) {

            return "PKT";
        }

        return product.getUnit()
                .trim()
                .toUpperCase();
    }

    private BigDecimal zeroIfNull(
            BigDecimal value) {

        return value == null
                ? BigDecimal.ZERO
                : value;
    }
}
