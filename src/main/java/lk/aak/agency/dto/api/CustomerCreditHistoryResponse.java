package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.util.List;

public class CustomerCreditHistoryResponse {

    private final CustomerResponse customer;
    private final List<CreditInvoiceRow> creditInvoices;
    private final List<PaymentResponse> payments;
    private final BigDecimal totalCreditSales;
    private final BigDecimal totalPaid;
    private final BigDecimal totalOutstanding;
    private final long overdueInvoiceCount;

    public CustomerCreditHistoryResponse(
            CustomerResponse customer,
            List<CreditInvoiceRow> creditInvoices,
            List<PaymentResponse> payments,
            BigDecimal totalCreditSales,
            BigDecimal totalPaid,
            BigDecimal totalOutstanding,
            long overdueInvoiceCount) {

        this.customer = customer;
        this.creditInvoices = creditInvoices;
        this.payments = payments;
        this.totalCreditSales = totalCreditSales;
        this.totalPaid = totalPaid;
        this.totalOutstanding = totalOutstanding;
        this.overdueInvoiceCount = overdueInvoiceCount;
    }

    public CustomerResponse getCustomer() {
        return customer;
    }

    public List<CreditInvoiceRow> getCreditInvoices() {
        return creditInvoices;
    }

    public List<PaymentResponse> getPayments() {
        return payments;
    }

    public BigDecimal getTotalCreditSales() {
        return totalCreditSales;
    }

    public BigDecimal getTotalPaid() {
        return totalPaid;
    }

    public BigDecimal getTotalOutstanding() {
        return totalOutstanding;
    }

    public long getOverdueInvoiceCount() {
        return overdueInvoiceCount;
    }
}
