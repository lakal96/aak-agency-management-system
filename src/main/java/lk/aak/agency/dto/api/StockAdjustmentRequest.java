package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class StockAdjustmentRequest {

    @NotNull
    private Long productId;

    @NotBlank
    private String adjustmentType;

    private String direction;

    @NotNull
    @DecimalMin(value = "0.01", message = "Adjustment quantity must be greater than zero.")
    @DecimalMax(value = "999999.99", message = "Adjustment quantity is too large - please check the number.")
    private BigDecimal quantity;

    private String referenceNumber;
    @NotBlank(message = "Please write a short note saying why the stock is changing.")
    @Size(max = 1000, message = "The note is too long (the most allowed is 1000 characters).")
    private String notes;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getAdjustmentType() {
        return adjustmentType;
    }

    public void setAdjustmentType(String adjustmentType) {
        this.adjustmentType = adjustmentType;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
