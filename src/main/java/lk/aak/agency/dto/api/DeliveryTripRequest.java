package lk.aak.agency.dto.api;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class DeliveryTripRequest {

    @NotNull(message = "Trip date is required.")
    private LocalDate tripDate;

    private Long routeId;
    private String areaCovered;

    @NotNull(message = "Vehicle is required.")
    private Long vehicleId;

    private Long driverEmployeeId;
    private Long helperEmployeeId;
    private String status;
    private String notes;

    public LocalDate getTripDate() {
        return tripDate;
    }

    public void setTripDate(LocalDate tripDate) {
        this.tripDate = tripDate;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public String getAreaCovered() {
        return areaCovered;
    }

    public void setAreaCovered(String areaCovered) {
        this.areaCovered = areaCovered;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(Long vehicleId) {
        this.vehicleId = vehicleId;
    }

    public Long getDriverEmployeeId() {
        return driverEmployeeId;
    }

    public void setDriverEmployeeId(Long driverEmployeeId) {
        this.driverEmployeeId = driverEmployeeId;
    }

    public Long getHelperEmployeeId() {
        return helperEmployeeId;
    }

    public void setHelperEmployeeId(Long helperEmployeeId) {
        this.helperEmployeeId = helperEmployeeId;
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
