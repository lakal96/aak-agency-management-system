package lk.aak.agency.dto.api;

import lk.aak.agency.model.DeliveryTrip;

import java.time.LocalDate;

public class DeliveryTripResponse {

    private final Long id;
    private final LocalDate tripDate;
    private final Long routeId;
    private final String routeName;
    private final String areaCovered;
    private final Long vehicleId;
    private final String vehicleNumber;
    private final Long driverEmployeeId;
    private final String driverName;
    private final Long helperEmployeeId;
    private final String helperName;
    private final String status;
    private final String notes;

    public DeliveryTripResponse(DeliveryTrip trip) {
        this.id = trip.getId();
        this.tripDate = trip.getTripDate();
        this.routeId = trip.getRouteId();
        this.routeName = trip.getRouteName();
        this.areaCovered = trip.getAreaCovered();
        this.vehicleId = trip.getVehicleId();
        this.vehicleNumber = trip.getVehicleNumber();
        this.driverEmployeeId = trip.getDriverEmployeeId();
        this.driverName = trip.getDriverName();
        this.helperEmployeeId = trip.getHelperEmployeeId();
        this.helperName = trip.getHelperName();
        this.status = trip.getStatus();
        this.notes = trip.getNotes();
    }

    public Long getId() {
        return id;
    }

    public LocalDate getTripDate() {
        return tripDate;
    }

    public Long getRouteId() {
        return routeId;
    }

    public String getRouteName() {
        return routeName;
    }

    public String getAreaCovered() {
        return areaCovered;
    }

    public Long getVehicleId() {
        return vehicleId;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public Long getDriverEmployeeId() {
        return driverEmployeeId;
    }

    public String getDriverName() {
        return driverName;
    }

    public Long getHelperEmployeeId() {
        return helperEmployeeId;
    }

    public String getHelperName() {
        return helperName;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }
}
