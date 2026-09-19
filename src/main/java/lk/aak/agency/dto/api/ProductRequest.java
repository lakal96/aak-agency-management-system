package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class ProductRequest {

    @NotBlank(message = "CBL product code is required.")
    @Size(max = 100, message = "CBL product code must be 100 characters or fewer.")
    private String cblProductCode;

    @NotBlank(message = "Product name is required.")
    @Size(max = 200, message = "Product name must be 200 characters or fewer.")
    private String productName;

    private String brand;
    private String category;
    private String netWeight;

    @NotBlank(message = "Unit is required.")
    private String unit;

    @DecimalMin(value = "0", message = "MRP cannot be negative.")
    private BigDecimal mrp;

    @DecimalMin(value = "0", message = "Standard selling price cannot be negative.")
    private BigDecimal standardSellingPrice;

    @DecimalMin(value = "0", message = "Reorder level cannot be negative.")
    private BigDecimal reorderLevel;

    private String status;
    private String notes;

    public String getCblProductCode() {
        return cblProductCode;
    }

    public void setCblProductCode(String cblProductCode) {
        this.cblProductCode = cblProductCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getNetWeight() {
        return netWeight;
    }

    public void setNetWeight(String netWeight) {
        this.netWeight = netWeight;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public void setMrp(BigDecimal mrp) {
        this.mrp = mrp;
    }

    public BigDecimal getStandardSellingPrice() {
        return standardSellingPrice;
    }

    public void setStandardSellingPrice(BigDecimal standardSellingPrice) {
        this.standardSellingPrice = standardSellingPrice;
    }

    public BigDecimal getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(BigDecimal reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
