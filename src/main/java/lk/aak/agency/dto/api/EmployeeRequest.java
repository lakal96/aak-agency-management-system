package lk.aak.agency.dto.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public class EmployeeRequest {

    @NotBlank(message = "Full name is required.")
    @Size(max = 150, message = "Full name must be 150 characters or fewer.")
    private String fullName;

    @NotBlank(message = "Designation is required.")
    private String designation;

    @DecimalMin(value = "0", message = "Base salary cannot be negative.")
    private BigDecimal baseSalary;

    @Pattern(regexp = "^$|^[0-9+()\\-\\s]{7,20}$", message = "Enter a valid phone number.")
    private String phone;

    private String address;
    private String nicNumber;
    private LocalDate joinDate;
    private String status;
    private String systemUsername;
    private String biometricDeviceUserId;
    private String notes;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getDesignation() {
        return designation;
    }

    public void setDesignation(String designation) {
        this.designation = designation;
    }

    public BigDecimal getBaseSalary() {
        return baseSalary;
    }

    public void setBaseSalary(BigDecimal baseSalary) {
        this.baseSalary = baseSalary;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getNicNumber() {
        return nicNumber;
    }

    public void setNicNumber(String nicNumber) {
        this.nicNumber = nicNumber;
    }

    public LocalDate getJoinDate() {
        return joinDate;
    }

    public void setJoinDate(LocalDate joinDate) {
        this.joinDate = joinDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSystemUsername() {
        return systemUsername;
    }

    public void setSystemUsername(String systemUsername) {
        this.systemUsername = systemUsername;
    }

    public String getBiometricDeviceUserId() {
        return biometricDeviceUserId;
    }

    public void setBiometricDeviceUserId(String biometricDeviceUserId) {
        this.biometricDeviceUserId = biometricDeviceUserId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
