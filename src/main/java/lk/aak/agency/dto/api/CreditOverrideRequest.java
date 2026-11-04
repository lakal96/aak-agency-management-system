package lk.aak.agency.dto.api;

import jakarta.validation.constraints.NotBlank;

public class CreditOverrideRequest {

    // Optional note only: the approver recorded on the bill is the logged-in admin.
    private String approvedBy;

    @NotBlank(message = "A reason is required to override the credit limit.")
    private String reason;

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
