package lk.aak.agency.dto.api;

import lk.aak.agency.model.PurchaseInvoiceItem;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PurchaseInvoiceItemResponse {

    private final Long id;
    private final Long productId;
    private final String productName;
    private final BigDecimal quantity;
    private final String unit;
    private final BigDecimal unitPrice;
    private final BigDecimal amount;
    private final LocalDate expiryDate;
    private final BigDecimal invoicedQuantity;
    private final String invoicedUnit;
    private final BigDecimal invoicedUnitPrice;
    private final boolean receivedConfirmed;
    private final String receivedBy;
    private final java.time.LocalDateTime receivedAt;
    private final BigDecimal expectedQuantity;

    public PurchaseInvoiceItemResponse(PurchaseInvoiceItem item) {
        this.id = item.getId();
        this.productId = item.getProduct() != null ? item.getProduct().getId() : null;
        this.productName = item.getProduct() != null ? item.getProduct().getProductName() : null;
        this.quantity = item.getQuantity();
        this.unit = item.getUnit();
        this.unitPrice = item.getUnitPrice();
        this.amount = item.getAmount();
        this.expiryDate = item.getExpiryDate();
        this.invoicedQuantity = item.getInvoicedQuantity();
        this.invoicedUnit = item.getInvoicedUnit();
        this.invoicedUnitPrice = item.getInvoicedUnitPrice();
        this.receivedConfirmed = item.isReceivedConfirmed();
        this.receivedBy = item.getReceivedBy();
        this.receivedAt = item.getReceivedAt();
        this.expectedQuantity = item.getExpectedQuantity();
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isReceivedConfirmed() {
        return receivedConfirmed;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public java.time.LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    /** The pieces the invoice said, when the quantity received was different (null when nothing differed). */
    public BigDecimal getExpectedQuantity() {
        return expectedQuantity;
    }

    public BigDecimal getInvoicedQuantity() {
        return invoicedQuantity;
    }

    public String getInvoicedUnit() {
        return invoicedUnit;
    }

    public BigDecimal getInvoicedUnitPrice() {
        return invoicedUnitPrice;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }
}
