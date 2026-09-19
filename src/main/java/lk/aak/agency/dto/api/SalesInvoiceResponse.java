package lk.aak.agency.dto.api;

import lk.aak.agency.model.SalesInvoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public class SalesInvoiceResponse {

    private final Long id;
    private final String invoiceNumber;
    private final Long customerId;
    private final String customerName;
    private final LocalDate invoiceDate;
    private final String routeCode;
    private final BigDecimal grossAmount;
    private final BigDecimal discountAmount;
    private final BigDecimal returnAmount;
    private final BigDecimal netAmount;
    private final String saleType;
    private final LocalDate dueDate;
    private final String paymentStatus;
    private final String status;
    private final String notes;

    public SalesInvoiceResponse(SalesInvoice invoice) {
        this.id = invoice.getId();
        this.invoiceNumber = invoice.getInvoiceNumber();
        this.customerId = invoice.getCustomer() != null ? invoice.getCustomer().getId() : null;
        this.customerName = invoice.getCustomer() != null ? invoice.getCustomer().getCustomerName() : null;
        this.invoiceDate = invoice.getInvoiceDate();
        this.routeCode = invoice.getRouteCode();
        this.grossAmount = invoice.getGrossAmount();
        this.discountAmount = invoice.getDiscountAmount();
        this.returnAmount = invoice.getReturnAmount();
        this.netAmount = invoice.getNetAmount();
        this.saleType = invoice.getSaleType();
        this.dueDate = invoice.getDueDate();
        this.paymentStatus = invoice.getPaymentStatus();
        this.status = invoice.getStatus();
        this.notes = invoice.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public String getRouteCode() {
        return routeCode;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getReturnAmount() {
        return returnAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public String getSaleType() {
        return saleType;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
