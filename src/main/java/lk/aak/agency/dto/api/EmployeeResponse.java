package lk.aak.agency.dto.api;

import lk.aak.agency.model.Employee;

import java.time.LocalDate;

public class EmployeeResponse {

    private final Long id;
    private final String employeeCode;
    private final String fullName;
    private final String designation;
    private final String phone;
    private final String address;
    private final String nicNumber;
    private final LocalDate joinDate;
    private final String status;
    private final String systemUsername;
    private final String biometricDeviceUserId;
    private final String notes;

    public EmployeeResponse(Employee employee) {
        this.id = employee.getId();
        this.employeeCode = employee.getEmployeeCode();
        this.fullName = employee.getFullName();
        this.designation = employee.getDesignation();
        this.phone = employee.getPhone();
        this.address = employee.getAddress();
        this.nicNumber = employee.getNicNumber();
        this.joinDate = employee.getJoinDate();
        this.status = employee.getStatus();
        this.systemUsername = employee.getSystemUsername();
        this.biometricDeviceUserId = employee.getBiometricDeviceUserId();
        this.notes = employee.getNotes();
    }

    public Long getId() {
        return id;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getFullName() {
        return fullName;
    }

    public String getDesignation() {
        return designation;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddress() {
        return address;
    }

    public String getNicNumber() {
        return nicNumber;
    }

    public LocalDate getJoinDate() {
        return joinDate;
    }

    public String getStatus() {
        return status;
    }

    public String getSystemUsername() {
        return systemUsername;
    }

    public String getBiometricDeviceUserId() {
        return biometricDeviceUserId;
    }

    public String getNotes() {
        return notes;
    }
}
