package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.ShopReturn;
import lk.aak.agency.model.ShopReturnItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnItemRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.util.BusinessTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shop returns (goods a shop sends back).
 *
 * Rules:
 * - A return belongs to ONE completed bill of the SAME shop.
 * - Each returned product must be on that bill, and the quantity cannot exceed what was sold minus what is
 *   already returned (approved) or waiting for approval (pending) on that bill.
 * - The default unit price is the price billed for that product on the bill (the bill line's unit price,
 *   BEFORE any bill-level discount; if the product is on several lines, the quantity-weighted average). A
 *   different price may be typed but never above the highest price billed for that product.
 * - Approving a return (owner/office) restocks SALEABLE items only, marks DAMAGED / EXPIRED items FOR_CLAIM
 *   (to be claimed from CBL; they never enter sellable stock), and CREDITS the shop: value = quantity x unit
 *   price of ALL returned items, taken off what the shop owes on that bill as a CREDIT_NOTE payment. The credit
 *   is capped at the bill's remaining balance; the rest (already paid for) is recorded as "refund due" and
 *   reported - no refund is created automatically.
 * - Approval runs under a row lock and is idempotent: a second approval is refused and credits nothing.
 */
@Service
public class ShopReturnService {

    public static final String CLAIM_FOR_CLAIM = "FOR_CLAIM";

    private final ShopReturnRepository shopReturnRepository;
    private final ShopReturnItemRepository shopReturnItemRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogService auditLogService;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final PaymentService paymentService;
    private final RowLockService rowLockService;

    public ShopReturnService(
            ShopReturnRepository shopReturnRepository,
            ShopReturnItemRepository shopReturnItemRepository,
            CustomerRepository customerRepository,
            ProductRepository productRepository,
            StockMovementRepository stockMovementRepository,
            AuditLogService auditLogService,
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            PaymentService paymentService,
            RowLockService rowLockService) {

        this.shopReturnRepository = shopReturnRepository;
        this.shopReturnItemRepository = shopReturnItemRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.auditLogService = auditLogService;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.paymentService = paymentService;
        this.rowLockService = rowLockService;
    }

    public List<ShopReturn> getAllReturns() {
        return shopReturnRepository.findAllByOrderByReturnDateDesc();
    }

    public Optional<ShopReturn> getReturnById(Long id) {
        return shopReturnRepository.findById(id);
    }

    public List<ShopReturnItem> getItemsForReturn(Long shopReturnId) {
        return shopReturnItemRepository.findByShopReturnIdOrderByIdAsc(shopReturnId);
    }

    @Transactional
    public ShopReturn saveReturn(ShopReturn shopReturn) {

        if (shopReturn.getId() != null) {
            throw new IllegalArgumentException("A shop return cannot be changed after it has been created.");
        }

        if (shopReturn.getCustomerId() == null) {
            throw new IllegalArgumentException("Selected shop was not found.");
        }

        Customer customer = customerRepository.findById(shopReturn.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Selected shop was not found."));

        if (shopReturn.getSalesInvoiceId() == null) {
            throw new IllegalArgumentException(
                    "Choose the bill this return is for. A return must be linked to a completed bill of the same shop."
            );
        }

        SalesInvoice invoice = salesInvoiceRepository.findById(shopReturn.getSalesInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("The selected bill was not found."));

        if (invoice.getCustomer() == null || !customer.getId().equals(invoice.getCustomer().getId())) {
            throw new IllegalArgumentException(
                    "Bill " + invoice.getInvoiceNumber() + " belongs to a different shop. "
                            + "Choose a bill of " + customer.getCustomerName() + "."
            );
        }

        if (!"COMPLETED".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalArgumentException(
                    "Bill " + invoice.getInvoiceNumber() + " is not completed yet. "
                            + "A return can only be recorded against a completed bill."
            );
        }

        // Whatever the caller sent, a new return always starts as PENDING with nothing credited.
        shopReturn.setStatus("PENDING");
        shopReturn.setCreditAppliedAmount(null);
        shopReturn.setRefundDueAmount(null);
        shopReturn.setCustomerName(customer.getCustomerName());

        return shopReturnRepository.save(shopReturn);
    }

    /*
     * Locks the bill first so two people adding items for the same bill at once cannot both pass the
     * "not more than was sold" check.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShopReturnItem addItem(
            Long shopReturnId, Long productId, BigDecimal quantity, BigDecimal unitPrice, String category) {

        ShopReturn shopReturn = shopReturnRepository.findById(shopReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Shop return was not found."));

        if (shopReturn.getSalesInvoiceId() == null) {
            throw new IllegalArgumentException(
                    "This return is not linked to a bill, so items cannot be added. "
                            + "Reject it and record the return again against the bill."
            );
        }

        rowLockService.lockSalesInvoice(shopReturn.getSalesInvoiceId());
        rowLockService.forget(shopReturn);

        shopReturn = shopReturnRepository.findById(shopReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Shop return was not found."));

        if (!"PENDING".equalsIgnoreCase(shopReturn.getStatus())) {
            throw new IllegalArgumentException("Items can only be added to a pending return.");
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Selected product was not found."));

        if (quantity == null) {
            throw new IllegalArgumentException("Return quantity must be greater than zero.");
        }

        quantity = quantity.setScale(2, RoundingMode.HALF_UP);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Return quantity must be greater than zero.");
        }

        String normalizedCategory = category == null ? "SALEABLE" : category.trim().toUpperCase();

        if (!Set.of("SALEABLE", "DAMAGED", "EXPIRED").contains(normalizedCategory)) {
            throw new IllegalArgumentException("Selected return category is invalid.");
        }

        SalesInvoice invoice = salesInvoiceRepository.findById(shopReturn.getSalesInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("The bill of this return was not found."));

        BilledProduct billed = billedProduct(invoice, product);

        BigDecimal alreadyReturned = zeroIfNull(
                shopReturnItemRepository.sumReturnedQuantity(
                        invoice.getId(), product.getId(), Set.of("APPROVED", "PENDING")));

        BigDecimal stillReturnable = billed.quantity().subtract(alreadyReturned);

        if (quantity.compareTo(stillReturnable) > 0) {
            throw new IllegalArgumentException(
                    "Only " + plain(stillReturnable.max(BigDecimal.ZERO)) + " " + product.getUnit()
                            + " of " + product.getDisplayName() + " can still be returned on bill "
                            + invoice.getInvoiceNumber() + " (sold " + plain(billed.quantity())
                            + ", already returned or waiting for approval " + plain(alreadyReturned) + ")."
            );
        }

        BigDecimal price;

        if (unitPrice == null) {
            price = billed.averagePrice();

        } else {
            price = unitPrice.setScale(2, RoundingMode.HALF_UP);

            if (price.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Unit price cannot be negative.");
            }

            if (price.compareTo(billed.highestPrice()) > 0) {
                throw new IllegalArgumentException(
                        "Unit price cannot be higher than the price billed on the bill (Rs. "
                                + plain(billed.highestPrice()) + ")."
                );
            }
        }

        ShopReturnItem item = new ShopReturnItem();
        item.setShopReturnId(shopReturn.getId());
        item.setProduct(product);
        item.setQuantity(quantity);
        item.setUnit(product.getUnit());
        item.setUnitPrice(price);
        item.setCategory(normalizedCategory);

        return shopReturnItemRepository.save(item);
    }

    /**
     * Owner/office approval - only now does stock move and the shop get its credit. Saleable items go back
     * into sellable warehouse stock; damaged/expired items are recorded FOR_CLAIM (from CBL), never restocked.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShopReturn approveReturn(Long shopReturnId) {

        // Serialise approvals of the same return (and re-read it as it is now): the second caller waits,
        // then finds it already approved and credits nothing.
        rowLockService.lockShopReturn(shopReturnId);

        ShopReturn shopReturn = shopReturnRepository.findById(shopReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Shop return was not found."));

        if ("APPROVED".equalsIgnoreCase(shopReturn.getStatus())) {
            throw new IllegalArgumentException(
                    "This return has already been approved. The shop has already been credited once."
            );
        }

        if (!"PENDING".equalsIgnoreCase(shopReturn.getStatus())) {
            throw new IllegalArgumentException("Only a pending return can be approved.");
        }

        if (shopReturn.getSalesInvoiceId() == null) {
            throw new IllegalArgumentException(
                    "This return is not linked to a bill, so the shop cannot be credited. "
                            + "Reject it and record the return again against the bill."
            );
        }

        List<ShopReturnItem> items = getItemsForReturn(shopReturnId);

        if (items.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item before approving this return.");
        }

        // Lock order: the return (above), then the bill it credits.
        rowLockService.lockSalesInvoice(shopReturn.getSalesInvoiceId());

        SalesInvoice invoice = salesInvoiceRepository.findById(shopReturn.getSalesInvoiceId())
                .orElseThrow(() -> new IllegalArgumentException("The bill of this return was not found."));

        // Re-check the quantities against what is approved so far (pending ones were counted when added).
        for (ShopReturnItem item : items) {

            BigDecimal soldQuantity = billedProduct(invoice, item.getProduct()).quantity();
            BigDecimal approvedElsewhere = zeroIfNull(
                    shopReturnItemRepository.sumReturnedQuantity(
                            invoice.getId(), item.getProduct().getId(), Set.of("APPROVED")));
            BigDecimal thisReturn = items.stream()
                    .filter(other -> other.getProduct().getId().equals(item.getProduct().getId()))
                    .map(ShopReturnItem::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (approvedElsewhere.add(thisReturn).compareTo(soldQuantity) > 0) {
                throw new IllegalArgumentException(
                        "More " + item.getProduct().getDisplayName() + " would be returned than was sold on bill "
                                + invoice.getInvoiceNumber() + " (sold " + plain(soldQuantity)
                                + ", already returned " + plain(approvedElsewhere) + "). Reject this return."
                );
            }
        }

        BigDecimal creditValue = BigDecimal.ZERO;

        for (ShopReturnItem item : items) {

            creditValue = creditValue.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());

            if (!"SALEABLE".equalsIgnoreCase(item.getCategory())) {

                // Damaged / expired: not sellable stock; to be claimed from CBL.
                item.setCblClaimStatus(CLAIM_FOR_CLAIM);
                shopReturnItemRepository.save(item);
                continue;
            }

            StockMovement stockMovement = new StockMovement();
            stockMovement.setProduct(item.getProduct());
            stockMovement.setMovementType("SHOP_RETURN");
            stockMovement.setQuantityChange(item.getQuantity());
            stockMovement.setStockUnit(item.getUnit());
            stockMovement.setReferenceType("SHOP_RETURN_ITEM");
            stockMovement.setReferenceItemId(item.getId());
            stockMovement.setReferenceNumber("SR-" + shopReturn.getId());
            stockMovement.setNotes(
                    "Shop return from " + shopReturn.getCustomerName() + " - restocked to warehouse."
            );

            stockMovementRepository.save(stockMovement);
        }

        PaymentService.CreditNoteResult credit = paymentService.applyCreditNote(
                invoice.getId(),
                creditValue,
                "CN-SR-" + shopReturn.getId(),
                BusinessTime.today(),
                "Credit for shop return #" + shopReturn.getId()
        );

        shopReturn.setCreditAppliedAmount(credit.creditApplied());
        shopReturn.setRefundDueAmount(credit.refundDue());
        shopReturn.setStatus("APPROVED");
        ShopReturn saved = shopReturnRepository.save(shopReturn);

        auditLogService.record(
                "SHOP_RETURN_APPROVED", "ShopReturn", shopReturnId,
                "Approved return from " + shopReturn.getCustomerName() + " against bill " + invoice.getInvoiceNumber()
                        + ": credited Rs. " + plain(credit.creditApplied())
                        + (credit.refundDue().signum() > 0
                        ? ", refund due to the shop Rs. " + plain(credit.refundDue()) : "")
        );

        return saved;
    }

    @Transactional
    public void rejectReturn(Long shopReturnId) {

        ShopReturn shopReturn = shopReturnRepository.findById(shopReturnId)
                .orElseThrow(() -> new IllegalArgumentException("Shop return was not found."));

        if (!"PENDING".equalsIgnoreCase(shopReturn.getStatus())) {
            throw new IllegalArgumentException("Only a pending return can be rejected.");
        }

        shopReturn.setStatus("REJECTED");
        shopReturnRepository.save(shopReturn);

        auditLogService.record(
                "SHOP_RETURN_REJECTED", "ShopReturn", shopReturnId,
                "Rejected return from " + shopReturn.getCustomerName()
        );
    }

    /** Damaged / expired goods from approved returns still to be claimed from CBL, per product and category. */
    public List<CblClaimRow> getUnclaimedCblItems() {

        List<CblClaimRow> rows = new ArrayList<>();

        for (Object[] row : shopReturnItemRepository.unclaimedCblItems()) {
            rows.add(new CblClaimRow(
                    ((Number) row[0]).longValue(),
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    (BigDecimal) row[5]));
        }

        return rows;
    }

    public record CblClaimRow(
            Long productId,
            String productName,
            String cblProductCode,
            String unit,
            String category,
            BigDecimal quantity) {
    }

    /** What the bill says about one product: total quantity sold, the average and the highest price billed. */
    private record BilledProduct(BigDecimal quantity, BigDecimal averagePrice, BigDecimal highestPrice) {
    }

    private BilledProduct billedProduct(SalesInvoice invoice, Product product) {

        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal highest = BigDecimal.ZERO;

        for (SalesInvoiceItem line : salesInvoiceItemRepository.findBySalesInvoiceIdOrderByIdAsc(invoice.getId())) {

            if (line.getProduct() == null || !line.getProduct().getId().equals(product.getId())) {
                continue;
            }

            BigDecimal lineQuantity = zeroIfNull(line.getQuantity());
            BigDecimal linePrice = zeroIfNull(line.getUnitPrice());

            quantity = quantity.add(lineQuantity);
            value = value.add(lineQuantity.multiply(linePrice));
            highest = highest.max(linePrice);
        }

        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException(
                    product.getDisplayName() + " is not on bill " + invoice.getInvoiceNumber()
                            + ", so it cannot be returned against it."
            );
        }

        return new BilledProduct(quantity, value.divide(quantity, 2, RoundingMode.HALF_UP), highest);
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
