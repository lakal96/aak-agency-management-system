package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import lk.aak.agency.util.Decimals;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PurchaseInvoiceItemRequest {

    @NotNull(message = "Product is required.")
    private Long productId;

    @NotNull(message = "Quantity is required.")
    @DecimalMin(value = "0.01", message = "Quantity must be greater than zero.")
    @DecimalMax(value = "10000000", message = "Quantity is too large - please check the number.")
    private BigDecimal quantity;

    @NotNull(message = "Unit price is required.")
    @DecimalMin(value = "0", message = "Unit price cannot be negative.")
    @DecimalMax(value = "999999999.99", message = "Unit price is too large - please check the number.")
    private BigDecimal unitPrice;

    private LocalDate expiryDate;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = Decimals.round2(quantity);
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = Decimals.round2(unitPrice);
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }
}
