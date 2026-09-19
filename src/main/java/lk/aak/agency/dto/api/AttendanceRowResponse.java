package lk.aak.agency.dto.api;

import lk.aak.agency.service.EmployeeAttendanceService.AttendanceRow;

public class AttendanceRowResponse {

    private final Long employeeId;
    private final String employeeCode;
    private final String employeeName;
    private final String designation;
    private final String status;

    public AttendanceRowResponse(AttendanceRow row) {
        this.employeeId = row.employee().getId();
        this.employeeCode = row.employee().getEmployeeCode();
        this.employeeName = row.employee().getFullName();
        this.designation = row.employee().getDesignation();
        this.status = row.getStatus();
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public String getDesignation() {
        return designation;
    }

    public String getStatus() {
        return status;
    }
}
