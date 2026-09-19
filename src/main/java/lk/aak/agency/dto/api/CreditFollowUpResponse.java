package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.util.List;

public class CreditFollowUpResponse {

    private final List<CreditFollowUpRowResponse> rows;
    private final BigDecimal totalOutstanding;
    private final BigDecimal totalOverdue;

    public CreditFollowUpResponse(
            List<CreditFollowUpRowResponse> rows, BigDecimal totalOutstanding, BigDecimal totalOverdue) {

        this.rows = rows;
        this.totalOutstanding = totalOutstanding;
        this.totalOverdue = totalOverdue;
    }

    public List<CreditFollowUpRowResponse> getRows() {
        return rows;
    }

    public BigDecimal getTotalOutstanding() {
        return totalOutstanding;
    }

    public BigDecimal getTotalOverdue() {
        return totalOverdue;
    }
}
