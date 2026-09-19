package lk.aak.agency.dto.api;

import jakarta.validation.constraints.NotBlank;

public class VehicleRequest {

    @NotBlank(message = "Vehicle number is required.")
    private String vehicleNumber;

    private String vehicleType;
    private String capacityNotes;
    private Long assignedDriverId;
    private String status;
    private String notes;

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(String vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getCapacityNotes() {
        return capacityNotes;
    }

    public void setCapacityNotes(String capacityNotes) {
        this.capacityNotes = capacityNotes;
    }

    public Long getAssignedDriverId() {
        return assignedDriverId;
    }

    public void setAssignedDriverId(Long assignedDriverId) {
        this.assignedDriverId = assignedDriverId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
