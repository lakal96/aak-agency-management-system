package lk.aak.agency.dto.api;

import lk.aak.agency.model.StockAdjustment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class StockAdjustmentResponse {

    private final Long id;
    private final Long productId;
    private final String productName;
    private final String unit;
    private final String adjustmentType;
    private final String direction;
    private final BigDecimal quantity;
    private final LocalDateTime adjustmentDate;
    private final String referenceNumber;
    private final String notes;
    private final String createdBy;

    public StockAdjustmentResponse(StockAdjustment adjustment) {
        this.id = adjustment.getId();
        this.productId = adjustment.getProduct() != null ? adjustment.getProduct().getId() : null;
        this.productName = adjustment.getProduct() != null ? adjustment.getProduct().getProductName() : null;
        this.unit = adjustment.getProduct() != null ? adjustment.getProduct().getUnit() : null;
        this.adjustmentType = adjustment.getAdjustmentType();
        this.direction = adjustment.getDirection();
        this.quantity = adjustment.getQuantity();
        this.adjustmentDate = adjustment.getAdjustmentDate();
        this.referenceNumber = adjustment.getReferenceNumber();
        this.notes = adjustment.getNotes();
        this.createdBy = adjustment.getCreatedBy();
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

    public String getUnit() {
        return unit;
    }

    public String getAdjustmentType() {
        return adjustmentType;
    }

    public String getDirection() {
        return direction;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public LocalDateTime getAdjustmentDate() {
        return adjustmentDate;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public String getNotes() {
        return notes;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
