package lk.aak.agency.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_invoice_items")
public class PurchaseInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "purchase_invoice_id",
            nullable = false
    )
    private PurchaseInvoice purchaseInvoice;

    @ManyToOne(
            fetch = FetchType.EAGER,
            optional = false
    )
    @JoinColumn(
            name = "product_id",
            nullable = false
    )
    private Product product;

    @Column(
            name = "purchased_quantity",
            precision = 12,
            scale = 2,
            nullable = false
    )
    private BigDecimal quantity;

    @Column(
            name = "purchase_unit",
            nullable = false
    )
    private String unit;

    @Column(
            name = "unit_price",
            precision = 15,
            scale = 2,
            nullable = false
    )
    private BigDecimal unitPrice;

    @Column(
            name = "amount_including_vat",
            precision = 15,
            scale = 2,
            nullable = false
    )
    private BigDecimal amount;

    // Optional - the expiry date of this specific received batch, if known.
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    // What CBL printed on its invoice (set for lines that came from an e-mailed invoice): quantity in the
    // invoiced unit (dozens / cartons), that unit, its price and the exact line amount. `quantity` above is the
    // number of pieces that go into stock.
    @Column(name = "invoiced_quantity", precision = 15, scale = 2)
    private BigDecimal invoicedQuantity;

    @Column(name = "invoiced_unit", length = 20)
    private String invoicedUnit;

    @Column(name = "invoiced_unit_price", precision = 15, scale = 4)
    private BigDecimal invoicedUnitPrice;

    @Column(name = "invoiced_amount", precision = 15, scale = 2)
    private BigDecimal invoicedAmount;

    // Goods check: ticked once the goods of this line were counted. If the received quantity differed from the
    // invoice, the quantity and amount above follow what was received and the invoice's own figures are kept here.
    @Column(name = "received_confirmed", nullable = false)
    private boolean receivedConfirmed;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Column(name = "received_at")
    private java.time.LocalDateTime receivedAt;

    @Column(name = "expected_quantity", precision = 15, scale = 2)
    private BigDecimal expectedQuantity;

    @Column(name = "expected_amount", precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    public PurchaseInvoiceItem() {
    }

    @PrePersist
    @PreUpdate
    public void calculateValues() {

        if (quantity == null) {
            quantity = BigDecimal.ZERO;
        }

        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }

        if ((unit == null || unit.isBlank())
                && product != null) {

            unit = product.getUnit();
        }

        if (unit == null || unit.isBlank()) {
            unit = "PKT";
        } else {
            unit = unit.trim().toUpperCase();
        }

        // Two decimals, so draft and completed totals always agree with what is stored.
        // A line taken from CBL's own invoice keeps the amount CBL printed (its unit price is rounded, so
        // quantity x price can be a few cents or rupees away from it, and what we owe CBL must match exactly).
        amount = invoicedAmount != null
                ? invoicedAmount.setScale(2, java.math.RoundingMode.HALF_UP)
                : quantity.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public void calculateAmount() {
        calculateValues();
    }

    public boolean isReceivedConfirmed() { return receivedConfirmed; }
    public void setReceivedConfirmed(boolean receivedConfirmed) { this.receivedConfirmed = receivedConfirmed; }
    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }
    public java.time.LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(java.time.LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
    public BigDecimal getExpectedQuantity() { return expectedQuantity; }
    public void setExpectedQuantity(BigDecimal expectedQuantity) { this.expectedQuantity = expectedQuantity; }
    public BigDecimal getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(BigDecimal expectedAmount) { this.expectedAmount = expectedAmount; }
    public BigDecimal getInvoicedQuantity() { return invoicedQuantity; }
    public void setInvoicedQuantity(BigDecimal invoicedQuantity) { this.invoicedQuantity = invoicedQuantity; }
    public String getInvoicedUnit() { return invoicedUnit; }
    public void setInvoicedUnit(String invoicedUnit) { this.invoicedUnit = invoicedUnit; }
    public BigDecimal getInvoicedUnitPrice() { return invoicedUnitPrice; }
    public void setInvoicedUnitPrice(BigDecimal invoicedUnitPrice) { this.invoicedUnitPrice = invoicedUnitPrice; }
    public BigDecimal getInvoicedAmount() { return invoicedAmount; }
    public void setInvoicedAmount(BigDecimal invoicedAmount) { this.invoicedAmount = invoicedAmount; }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PurchaseInvoice getPurchaseInvoice() {
        return purchaseInvoice;
    }

    public void setPurchaseInvoice(
            PurchaseInvoice purchaseInvoice) {

        this.purchaseInvoice = purchaseInvoice;
    }

    public Product getProduct() {
        return product;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public void setProduct(Product product) {
        this.product = product;

        if (product != null) {
            this.unit = product.getUnit();
        }
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    /*
     * Temporary compatibility methods.
     * These keep the existing controller, service and HTML
     * files working until they are updated.
     */

    @Deprecated
    @Transient
    public BigDecimal getNumberOfBoxes() {
        return BigDecimal.ZERO;
    }

    @Deprecated
    public void setNumberOfBoxes(
            BigDecimal numberOfBoxes) {
        // Number of boxes is no longer required.
    }

    @Deprecated
    @Transient
    public BigDecimal getPurchasedQuantity() {
        return quantity;
    }

    @Deprecated
    public void setPurchasedQuantity(
            BigDecimal purchasedQuantity) {

        this.quantity = purchasedQuantity;
    }

    @Deprecated
    @Transient
    public String getPurchaseUnit() {
        return unit;
    }

    @Deprecated
    public void setPurchaseUnit(
            String purchaseUnit) {

        this.unit = purchaseUnit;
    }

    @Deprecated
    @Transient
    public BigDecimal getAmountIncludingVat() {
        return amount;
    }

    @Deprecated
    public void setAmountIncludingVat(
            BigDecimal amountIncludingVat) {

        this.amount = amountIncludingVat;
    }
}