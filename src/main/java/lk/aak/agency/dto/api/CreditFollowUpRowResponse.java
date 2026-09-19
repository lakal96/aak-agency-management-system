package lk.aak.agency.dto.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CreditFollowUpRowResponse {

    private final Long customerId;
    private final String customerCode;
    private final String customerName;
    private final String area;
    private final String phone;
    private final BigDecimal totalOutstanding;
    private final BigDecimal overdueAmount;
    private final LocalDate oldestOverdueDueDate;
    private final long daysOverdue;

    public CreditFollowUpRowResponse(
            Long customerId,
            String customerCode,
            String customerName,
            String area,
            String phone,
            BigDecimal totalOutstanding,
            BigDecimal overdueAmount,
            LocalDate oldestOverdueDueDate,
            long daysOverdue) {

        this.customerId = customerId;
        this.customerCode = customerCode;
        this.customerName = customerName;
        this.area = area;
        this.phone = phone;
        this.totalOutstanding = totalOutstanding;
        this.overdueAmount = overdueAmount;
        this.oldestOverdueDueDate = oldestOverdueDueDate;
        this.daysOverdue = daysOverdue;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getArea() {
        return area;
    }

    public String getPhone() {
        return phone;
    }

    public BigDecimal getTotalOutstanding() {
        return totalOutstanding;
    }

    public BigDecimal getOverdueAmount() {
        return overdueAmount;
    }

    public LocalDate getOldestOverdueDueDate() {
        return oldestOverdueDueDate;
    }

    public long getDaysOverdue() {
        return daysOverdue;
    }
}
