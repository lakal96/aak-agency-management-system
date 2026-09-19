package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.CustomerRequest;
import lk.aak.agency.dto.api.CustomerResponse;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.service.CustomerService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final CustomerService customerService;

    public CustomerApiController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<Customer> customers = (q == null || q.isBlank())
                ? customerService.getCustomers(page, size)
                : customerService.search(q, page, size);

        return PageResponse.from(customers, CustomerResponse::new);
    }

    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable Long id) {
        return customerService.getCustomerById(id)
                .map(CustomerResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {

        Customer customer = new Customer();
        applyRequest(customer, request);

        Customer saved = customerService.saveCustomer(customer);

        return ResponseEntity.status(HttpStatus.CREATED).body(new CustomerResponse(saved));
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));

        applyRequest(customer, request);

        return new CustomerResponse(customerService.saveCustomer(customer));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {

        if (customerService.getCustomerById(id).isEmpty()) {
            throw new NoSuchElementException("Customer not found.");
        }

        customerService.deleteCustomer(id);

        return ResponseEntity.noContent().build();
    }

    private void applyRequest(Customer customer, CustomerRequest request) {
        customer.setCustomerName(request.getCustomerName());
        customer.setCustomerType(request.getCustomerType());
        customer.setAddress(request.getAddress());
        customer.setArea(request.getArea());
        customer.setContactPerson(request.getContactPerson());
        customer.setPhone(request.getPhone());
        customer.setCreditLimit(request.getCreditLimit());
        customer.setPaymentTermsDays(request.getPaymentTermsDays());
        customer.setAssignedEmployee(request.getAssignedEmployee());
        customer.setStatus(request.getStatus());
        customer.setNotes(request.getNotes());
    }
}
