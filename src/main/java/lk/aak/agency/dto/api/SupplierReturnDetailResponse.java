package lk.aak.agency.dto.api;

import java.util.List;

public class SupplierReturnDetailResponse {

    private final SupplierReturnResponse supplierReturn;
    private final List<SupplierReturnItemResponse> items;

    public SupplierReturnDetailResponse(SupplierReturnResponse supplierReturn, List<SupplierReturnItemResponse> items) {
        this.supplierReturn = supplierReturn;
        this.items = items;
    }

    public SupplierReturnResponse getSupplierReturn() {
        return supplierReturn;
    }

    public List<SupplierReturnItemResponse> getItems() {
        return items;
    }
}
