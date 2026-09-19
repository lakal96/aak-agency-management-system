package lk.aak.agency.dto.api;

import java.math.BigDecimal;

public class InventoryItemResponse {

    private final Long productId;
    private final String productCode;
    private final String productName;
    private final String unit;
    private final BigDecimal currentStock;
    private final BigDecimal reorderLevel;
    private final boolean lowStock;

    public InventoryItemResponse(
            Long productId,
            String productCode,
            String productName,
            String unit,
            BigDecimal currentStock,
            BigDecimal reorderLevel) {

        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unit = unit;
        this.currentStock = currentStock;
        this.reorderLevel = reorderLevel;
        this.lowStock = reorderLevel != null && currentStock.compareTo(reorderLevel) <= 0;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getProductName() {
        return productName;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getCurrentStock() {
        return currentStock;
    }

    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public boolean isLowStock() {
        return lowStock;
    }
}
