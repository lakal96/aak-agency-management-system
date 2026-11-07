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
    private final BigDecimal reservedByDrafts;
    private final int pendingBills;

    public InventoryItemResponse(
            Long productId,
            String productCode,
            String productName,
            String unit,
            BigDecimal currentStock,
            BigDecimal reorderLevel) {
        this(productId, productCode, productName, unit, currentStock, reorderLevel, BigDecimal.ZERO, 0);
    }

    public InventoryItemResponse(
            Long productId,
            String productCode,
            String productName,
            String unit,
            BigDecimal currentStock,
            BigDecimal reorderLevel,
            BigDecimal reservedByDrafts,
            int pendingBills) {

        this.reservedByDrafts = reservedByDrafts;
        this.pendingBills = pendingBills;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unit = unit;
        this.currentStock = currentStock;
        this.reorderLevel = reorderLevel;
        this.lowStock = reorderLevel != null && currentStock.compareTo(reorderLevel) <= 0;
    }

    /** Pieces that other pending (draft) bills already ask for: not taken from stock yet, but spoken for. */
    public BigDecimal getReservedByDrafts() {
        return reservedByDrafts;
    }

    public int getPendingBills() {
        return pendingBills;
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
