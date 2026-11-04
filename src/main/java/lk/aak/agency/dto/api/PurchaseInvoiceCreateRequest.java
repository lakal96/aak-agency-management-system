package lk.aak.agency.dto.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lk.aak.agency.util.Decimals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class PurchaseInvoiceCreateRequest {

    @NotBlank(message = "Document number is required.")
    private String documentNumber;

    private String taxInvoiceNumber;
    private String poNumber;

    @NotNull(message = "Invoice date is required.")
    private LocalDate invoiceDate;

    private LocalDate deliveryDate;

    @NotBlank(message = "Supplier name is required.")
    private String supplierName;

    private String territory;

    @NotBlank(message = "Place of supply is required.")
    private String placeOfSupply;

    @DecimalMin(value = "0", message = "Discount cannot be negative.")
    @DecimalMax(value = "999999999.99", message = "Discount is too large - please check the number.")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @DecimalMin(value = "0", message = "VAT cannot be negative.")
    @DecimalMax(value = "999999999.99", message = "VAT is too large - please check the number.")
    private BigDecimal vatAmount = BigDecimal.ZERO;
    private String paymentMethod;
    private String notes;

    @NotEmpty(message = "Add at least one product to the purchase invoice.")
    @Valid
    private List<PurchaseInvoiceItemRequest> items;

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public String getTaxInvoiceNumber() {
        return taxInvoiceNumber;
    }

    public void setTaxInvoiceNumber(String taxInvoiceNumber) {
        this.taxInvoiceNumber = taxInvoiceNumber;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public void setPoNumber(String poNumber) {
        this.poNumber = poNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public void setDeliveryDate(LocalDate deliveryDate) {
        this.deliveryDate = deliveryDate;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public void setSupplierName(String supplierName) {
        this.supplierName = supplierName;
    }

    public String getTerritory() {
        return territory;
    }

    public void setTerritory(String territory) {
        this.territory = territory;
    }

    public String getPlaceOfSupply() {
        return placeOfSupply;
    }

    public void setPlaceOfSupply(String placeOfSupply) {
        this.placeOfSupply = placeOfSupply;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = Decimals.round2(discountAmount);
    }

    public BigDecimal getVatAmount() {
        return vatAmount;
    }

    public void setVatAmount(BigDecimal vatAmount) {
        this.vatAmount = Decimals.round2(vatAmount);
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(String paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<PurchaseInvoiceItemRequest> getItems() {
        return items;
    }

    public void setItems(List<PurchaseInvoiceItemRequest> items) {
        this.items = items;
    }
}
