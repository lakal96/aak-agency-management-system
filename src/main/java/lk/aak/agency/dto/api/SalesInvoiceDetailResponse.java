package lk.aak.agency.dto.api;

import java.util.List;

public class SalesInvoiceDetailResponse {

    private final SalesInvoiceResponse invoice;
    private final List<SalesInvoiceItemResponse> items;
    private final java.math.BigDecimal paidAmount;
    private final java.math.BigDecimal balance;

    public SalesInvoiceDetailResponse(
            SalesInvoiceResponse invoice,
            List<SalesInvoiceItemResponse> items,
            java.math.BigDecimal paidAmount,
            java.math.BigDecimal balance) {
        this.invoice = invoice;
        this.items = items;
        this.paidAmount = paidAmount;
        this.balance = balance;
    }

    public SalesInvoiceResponse getInvoice() {
        return invoice;
    }

    public List<SalesInvoiceItemResponse> getItems() {
        return items;
    }

    public java.math.BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public java.math.BigDecimal getBalance() {
        return balance;
    }
}
