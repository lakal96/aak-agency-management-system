package lk.aak.agency.dto.api;

import lk.aak.agency.model.PurchaseInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public class PurchaseInvoiceResponse {

    private final Long id;
    private final String documentNumber;
    private final String taxInvoiceNumber;
    private final String poNumber;
    private final LocalDate invoiceDate;
    private final LocalDate deliveryDate;
    private final String supplierName;
    private final String territory;
    private final String placeOfSupply;
    private final BigDecimal subtotal;
    private final BigDecimal discountAmount;
    private final BigDecimal vatAmount;
    private final BigDecimal totalAmount;
    private final String paymentMethod;
    private final String status;
    private final String notes;

    public PurchaseInvoiceResponse(PurchaseInvoice invoice) {
        this.id = invoice.getId();
        this.documentNumber = invoice.getDocumentNumber();
        this.taxInvoiceNumber = invoice.getTaxInvoiceNumber();
        this.poNumber = invoice.getPoNumber();
        this.invoiceDate = invoice.getInvoiceDate();
        this.deliveryDate = invoice.getDeliveryDate();
        this.supplierName = invoice.getSupplierName();
        this.territory = invoice.getTerritory();
        this.placeOfSupply = invoice.getPlaceOfSupply();
        this.subtotal = invoice.getSubtotal();
        this.discountAmount = invoice.getDiscountAmount();
        this.vatAmount = invoice.getVatAmount();
        this.totalAmount = invoice.getTotalAmount();
        this.paymentMethod = invoice.getPaymentMethod();
        this.status = invoice.getStatus();
        this.notes = invoice.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getTaxInvoiceNumber() {
        return taxInvoiceNumber;
    }

    public String getPoNumber() {
        return poNumber;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public String getSupplierName() {
        return supplierName;
    }

    public String getTerritory() {
        return territory;
    }

    public String getPlaceOfSupply() {
        return placeOfSupply;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getVatAmount() {
        return vatAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
