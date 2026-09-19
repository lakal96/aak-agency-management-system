package lk.aak.agency.dto.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class SalesInvoiceCreateRequest {

    @NotNull(message = "Customer is required.")
    private Long customerId;

    @jakarta.validation.constraints.NotBlank(message = "Invoice number is required.")
    private String invoiceNumber;

    @NotNull(message = "Invoice date is required.")
    private LocalDate invoiceDate;

    private LocalDate dueDate;
    private String routeCode;

    private String saleType = "CREDIT";

    private BigDecimal discountAmount = BigDecimal.ZERO;
    private BigDecimal returnAmount = BigDecimal.ZERO;
    private String notes;

    @NotEmpty(message = "Add at least one product before saving the invoice.")
    @Valid
    private List<SalesInvoiceItemRequest> items;

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public LocalDate getInvoiceDate() {
        return invoiceDate;
    }

    public void setInvoiceDate(LocalDate invoiceDate) {
        this.invoiceDate = invoiceDate;
    }

    public String getRouteCode() {
        return routeCode;
    }

    public void setRouteCode(String routeCode) {
        this.routeCode = routeCode;
    }

    public String getSaleType() {
        return saleType;
    }

    public void setSaleType(String saleType) {
        this.saleType = saleType;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getReturnAmount() {
        return returnAmount;
    }

    public void setReturnAmount(BigDecimal returnAmount) {
        this.returnAmount = returnAmount;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<SalesInvoiceItemRequest> getItems() {
        return items;
    }

    public void setItems(List<SalesInvoiceItemRequest> items) {
        this.items = items;
    }
}
