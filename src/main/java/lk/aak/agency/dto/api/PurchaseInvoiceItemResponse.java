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

    public PurchaseInvoiceItemResponse(PurchaseInvoiceItem item) {
        this.id = item.getId();
        this.productId = item.getProduct() != null ? item.getProduct().getId() : null;
        this.productName = item.getProduct() != null ? item.getProduct().getProductName() : null;
        this.quantity = item.getQuantity();
        this.unit = item.getUnit();
        this.unitPrice = item.getUnitPrice();
        this.amount = item.getAmount();
        this.expiryDate = item.getExpiryDate();
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

    public LocalDate getExpiryDate() {
        return expiryDate;
    }
}
