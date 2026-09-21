package lk.aak.agency.dto.api;

import lk.aak.agency.model.Customer;

import java.math.BigDecimal;

public class CustomerResponse {

    private final Long id;
    private final String customerCode;
    private final String customerName;
    private final String customerType;
    private final String address;
    private final String area;
    private final String contactPerson;
    private final String phone;
    private final BigDecimal creditLimit;
    private final Integer paymentTermsDays;
    private final String assignedEmployee;
    private final Long assignedEmployeeId;
    private final String status;
    private final String notes;
    private final String qrCode;

    public CustomerResponse(Customer customer) {
        this.id = customer.getId();
        this.customerCode = customer.getCustomerCode();
        this.customerName = customer.getCustomerName();
        this.customerType = customer.getCustomerType();
        this.address = customer.getAddress();
        this.area = customer.getArea();
        this.contactPerson = customer.getContactPerson();
        this.phone = customer.getPhone();
        this.creditLimit = customer.getCreditLimit();
        this.paymentTermsDays = customer.getPaymentTermsDays();
        this.assignedEmployee = customer.getAssignedEmployee();
        this.assignedEmployeeId = customer.getAssignedEmployeeId();
        this.status = customer.getStatus();
        this.notes = customer.getNotes();
        this.qrCode = customer.getQrCode();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerCode() {
        return customerCode;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerType() {
        return customerType;
    }

    public String getAddress() {
        return address;
    }

    public String getArea() {
        return area;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public String getPhone() {
        return phone;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public Integer getPaymentTermsDays() {
        return paymentTermsDays;
    }

    public String getAssignedEmployee() {
        return assignedEmployee;
    }

    public Long getAssignedEmployeeId() {
        return assignedEmployeeId;
    }

    public String getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public String getQrCode() {
        return qrCode;
    }
}
