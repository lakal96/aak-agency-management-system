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

    /** The id of the line being edited (when this line already exists on the draft). */
    private Long id;

    /**
     * The line amount exactly as CBL printed it (optional). CBL's printed unit price is rounded, so quantity x price can
     * be a few cents off the printed amount; when this is given and differs, it is the amount that counts.
     */
    @DecimalMin(value = "0", message = "Amount cannot be negative.")
    @DecimalMax(value = "999999999.99", message = "Amount is too large - please check the number.")
    private BigDecimal amount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = Decimals.round2(amount);
    }

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
