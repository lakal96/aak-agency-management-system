package lk.aak.agency.dto.api;

import lk.aak.agency.model.Product;

import java.math.BigDecimal;

public class ProductResponse {

    private final Long id;
    private final String cblProductCode;
    private final String productName;
    private final String brand;
    private final String category;
    private final String netWeight;
    private final String unit;
    private final BigDecimal mrp;
    private final BigDecimal standardSellingPrice;
    private final BigDecimal reorderLevel;
    private final String status;
    private final String notes;

    public ProductResponse(Product product) {
        this.id = product.getId();
        this.cblProductCode = product.getCblProductCode();
        this.productName = product.getProductName();
        this.brand = product.getBrand();
        this.category = product.getCategory();
        this.netWeight = product.getNetWeight();
        this.unit = product.getUnit();
        this.mrp = product.getMrp();
        this.standardSellingPrice = product.getStandardSellingPrice();
        this.reorderLevel = product.getReorderLevel();
        this.status = product.getStatus();
        this.notes = product.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getCblProductCode() {
        return cblProductCode;
    }

    public String getProductName() {
        return productName;
    }

    public String getBrand() {
        return brand;
    }

    public String getCategory() {
        return category;
    }

    public String getNetWeight() {
        return netWeight;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public BigDecimal getStandardSellingPrice() {
        return standardSellingPrice;
    }

    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
