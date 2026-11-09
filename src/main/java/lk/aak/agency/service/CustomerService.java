package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.ShopReturnRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final AuditLogService auditLogService;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final ShopReturnRepository shopReturnRepository;

    public CustomerService(
            CustomerRepository customerRepository,
            AuditLogService auditLogService,
            SalesInvoiceRepository salesInvoiceRepository,
            ShopReturnRepository shopReturnRepository) {
        this.customerRepository = customerRepository;
        this.auditLogService = auditLogService;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.shopReturnRepository = shopReturnRepository;
    }

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll(
                Sort.by(Sort.Direction.ASC, "customerName").and(Sort.by(Sort.Direction.ASC, "id"))
        );
    }

    public Page<Customer> getCustomers(int page, int size, Long scopedEmployeeId) {
        return customerRepository.search(
                "",
                scopedEmployeeId,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.ASC, "customerName").and(Sort.by(Sort.Direction.ASC, "id"))
                )
        );
    }

    public Page<Customer> search(String keyword, int page, int size, Long scopedEmployeeId) {
        return customerRepository.search(
                keyword,
                scopedEmployeeId,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.ASC, "customerName").and(Sort.by(Sort.Direction.ASC, "id"))
                )
        );
    }

    /** Active shops a bill can be written for, whoever they are assigned to (see CustomerRepository.searchBillable). */
    public Page<Customer> searchBillable(String keyword, boolean withPhone, int page, int size) {
        return customerRepository.searchBillable(
                keyword == null ? "" : keyword.trim(),
                withPhone,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.ASC, "customerName").and(Sort.by(Sort.Direction.ASC, "id"))
                )
        );
    }

    public Optional<Customer> getCustomerById(Long id) {
        return customerRepository.findById(id);
    }

    public Optional<Customer> getCustomerByQrCode(String qrCode) {
        return customerRepository.findByQrCode(qrCode);
    }

    public Customer saveCustomer(Customer customer) {

        if (customer.getId() == null &&
                (customer.getCustomerCode() == null ||
                        customer.getCustomerCode().isBlank())) {

            customer.setCustomerCode(generateCustomerCode());
        }

        if (customer.getId() == null &&
                (customer.getQrCode() == null || customer.getQrCode().isBlank())) {

            customer.setQrCode(UUID.randomUUID().toString());
        }

        return customerRepository.save(customer);
    }

    /**
     * Audit-logs a change of credit limit or payment terms (old -> new); does nothing when neither
     * changed. "source" says how it was changed, e.g. "edit" or "bulk import".
     */
    public void recordCreditTermsChange(
            Customer customer, BigDecimal oldCreditLimit, Integer oldPaymentTermsDays, String source) {

        List<String> changes = new ArrayList<>();

        BigDecimal before = oldCreditLimit == null ? BigDecimal.ZERO : oldCreditLimit;
        BigDecimal after = customer.getCreditLimit() == null ? BigDecimal.ZERO : customer.getCreditLimit();
        if (before.compareTo(after) != 0) {
            changes.add("Credit limit " + before.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " -> " + after.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }

        if (!Objects.equals(oldPaymentTermsDays, customer.getPaymentTermsDays())) {
            changes.add("Payment terms " + oldPaymentTermsDays + " -> " + customer.getPaymentTermsDays() + " days");
        }

        if (!changes.isEmpty()) {
            auditLogService.record(
                    "CUSTOMER_TERMS_CHANGED", "Customer", customer.getId(),
                    "\"" + customer.getCustomerName() + "\": " + String.join("; ", changes) + " (" + source + ")"
            );
        }
    }

    public void deleteCustomer(Long id) {

        Customer customer = customerRepository.findById(id).orElse(null);

        if (salesInvoiceRepository.existsByCustomerId(id) || shopReturnRepository.existsByCustomerId(id)) {
            throw new IllegalArgumentException(
                    "This customer has sales invoices or returns and cannot be deleted. "
                            + "Set its status to INACTIVE instead."
            );
        }

        customerRepository.deleteById(id);

        auditLogService.record(
                "CUSTOMER_DELETED", "Customer", id,
                customer == null ? null : "Deleted customer \"" + customer.getCustomerName() + "\""
        );
    }

    private String generateCustomerCode() {

        long nextNumber = customerRepository
                .findTopByOrderByIdDesc()
                .map(customer -> customer.getId() + 1)
                .orElse(1L);

        return String.format("AAK-%06d", nextNumber);
    }
}
