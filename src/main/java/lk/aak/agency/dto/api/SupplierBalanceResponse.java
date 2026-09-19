package lk.aak.agency.dto.api;

import lk.aak.agency.service.SupplierPaymentService.SupplierBalanceSummary;

import java.math.BigDecimal;
import java.util.List;

public class SupplierBalanceResponse {

    private final List<SupplierBalanceRowResponse> rows;
    private final BigDecimal totalOwed;

    public SupplierBalanceResponse(SupplierBalanceSummary summary) {
        this.rows = summary.rows().stream().map(SupplierBalanceRowResponse::new).toList();
        this.totalOwed = summary.totalOwed();
    }

    public List<SupplierBalanceRowResponse> getRows() {
        return rows;
    }

    public BigDecimal getTotalOwed() {
        return totalOwed;
    }
}
