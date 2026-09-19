package lk.aak.agency.dto.api;

import lk.aak.agency.model.Vehicle;

public class VehicleResponse {

    private final Long id;
    private final String vehicleNumber;
    private final String vehicleType;
    private final String capacityNotes;
    private final Long assignedDriverId;
    private final String assignedDriverName;
    private final String status;
    private final String notes;

    public VehicleResponse(Vehicle vehicle) {
        this.id = vehicle.getId();
        this.vehicleNumber = vehicle.getVehicleNumber();
        this.vehicleType = vehicle.getVehicleType();
        this.capacityNotes = vehicle.getCapacityNotes();
        this.assignedDriverId = vehicle.getAssignedDriverId();
        this.assignedDriverName = vehicle.getAssignedDriverName();
        this.status = vehicle.getStatus();
        this.notes = vehicle.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public String getVehicleType() {
        return vehicleType;
    }

    public String getCapacityNotes() {
        return capacityNotes;
    }

    public Long getAssignedDriverId() {
        return assignedDriverId;
    }

    public String getAssignedDriverName() {
        return assignedDriverName;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
