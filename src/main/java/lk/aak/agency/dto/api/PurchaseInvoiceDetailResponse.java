package lk.aak.agency.dto.api;

import java.util.List;

public class PurchaseInvoiceDetailResponse {

    private final PurchaseInvoiceResponse invoice;
    private final List<PurchaseInvoiceItemResponse> items;

    public PurchaseInvoiceDetailResponse(PurchaseInvoiceResponse invoice, List<PurchaseInvoiceItemResponse> items) {
        this.invoice = invoice;
        this.items = items;
    }

    public PurchaseInvoiceResponse getInvoice() {
        return invoice;
    }

    public List<PurchaseInvoiceItemResponse> getItems() {
        return items;
    }
}
