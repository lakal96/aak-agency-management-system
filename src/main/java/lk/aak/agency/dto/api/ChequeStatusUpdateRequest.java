package lk.aak.agency.dto.api;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class ChequeStatusUpdateRequest {

    @NotBlank(message = "Select a cheque status.")
    private String chequeStatus;

    private LocalDate actionDate;
    private String returnReason;

    public String getChequeStatus() {
        return chequeStatus;
    }

    public void setChequeStatus(String chequeStatus) {
        this.chequeStatus = chequeStatus;
    }

    public LocalDate getActionDate() {
        return actionDate;
    }

    public void setActionDate(LocalDate actionDate) {
        this.actionDate = actionDate;
    }

    public String getReturnReason() {
        return returnReason;
    }

    public void setReturnReason(String returnReason) {
        this.returnReason = returnReason;
    }
}
