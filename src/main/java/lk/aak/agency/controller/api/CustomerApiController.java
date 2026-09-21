package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.CreditFollowUpResponse;
import lk.aak.agency.dto.api.CreditFollowUpRowResponse;
import lk.aak.agency.dto.api.CreditInvoiceRow;
import lk.aak.agency.dto.api.CustomerCreditHistoryResponse;
import lk.aak.agency.dto.api.CustomerRequest;
import lk.aak.agency.dto.api.CustomerResponse;
import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.PaymentResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.CustomerService;
import lk.aak.agency.service.ExcelService;
import lk.aak.agency.service.PaymentService;
import lk.aak.agency.service.QrCodeService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ZoneId SRI_LANKA_TIME_ZONE = ZoneId.of("Asia/Colombo");

    private static final List<String> IMPORT_HEADERS = List.of(
            "Customer Name*", "Area", "Phone", "Address", "Contact Person",
            "Credit Limit", "Payment Terms Days", "Customer Type", "Status", "Notes"
    );

    private final CustomerService customerService;
    private final QrCodeService qrCodeService;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final ExcelService excelService;

    public CustomerApiController(
            CustomerService customerService,
            QrCodeService qrCodeService,
            SalesInvoiceRepository salesInvoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            ExcelService excelService) {

        this.customerService = customerService;
        this.qrCodeService = qrCodeService;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.excelService = excelService;
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

    /** The shop's printable QR code (PNG) - scanning it opens this shop's credit history. */
    @GetMapping(value = "/{id}/qr-code", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrCode(@PathVariable Long id) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));

        String scanUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/customers/scan/{qrCode}")
                .buildAndExpand(customer.getQrCode())
                .toUriString();

        byte[] pngImage = qrCodeService.generatePng(scanUrl);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(pngImage);
    }

    @GetMapping("/scan/{qrCode}")
    public CustomerResponse scan(@PathVariable String qrCode) {
        return customerService.getCustomerByQrCode(qrCode)
                .map(CustomerResponse::new)
                .orElseThrow(() -> new NoSuchElementException("No shop matches this QR code."));
    }

    @GetMapping("/{id}/credit-history")
    public CustomerCreditHistoryResponse creditHistory(@PathVariable Long id) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));

        List<SalesInvoice> creditInvoices = salesInvoiceRepository
                .findByCustomerIdOrderByInvoiceDateDesc(id).stream()
                .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                .filter(invoice -> "CREDIT".equalsIgnoreCase(invoice.getSaleType()))
                .toList();

        List<Payment> payments = paymentRepository.findBySalesInvoiceCustomerIdOrderByPaymentDateDesc(id);

        LocalDate today = LocalDate.now(SRI_LANKA_TIME_ZONE);
        List<CreditInvoiceRow> rows = new ArrayList<>();
        BigDecimal totalCreditSales = BigDecimal.ZERO;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        long overdueInvoiceCount = 0;

        for (SalesInvoice invoice : creditInvoices) {
            BigDecimal netAmount = zeroIfNull(invoice.getNetAmount());
            BigDecimal paidAmount = paymentService.getPaidAmount(invoice.getId());
            BigDecimal balance = netAmount.subtract(paidAmount);

            if (balance.compareTo(BigDecimal.ZERO) < 0) {
                balance = BigDecimal.ZERO;
            }

            boolean overdue = invoice.getDueDate() != null
                    && invoice.getDueDate().isBefore(today)
                    && balance.compareTo(BigDecimal.ZERO) > 0;

            rows.add(new CreditInvoiceRow(invoice, paidAmount, balance, overdue));

            totalCreditSales = totalCreditSales.add(netAmount);
            totalPaid = totalPaid.add(paidAmount);
            totalOutstanding = totalOutstanding.add(balance);

            if (overdue) {
                overdueInvoiceCount++;
            }
        }

        return new CustomerCreditHistoryResponse(
                new CustomerResponse(customer),
                rows,
                payments.stream().map(PaymentResponse::new).toList(),
                totalCreditSales,
                totalPaid,
                totalOutstanding,
                overdueInvoiceCount);
    }

    /**
     * Agency-wide "Outstanding and overdue amounts by shop and bill" report - the
     * proposal's own wording for the Credit Follow-up report the owner needs.
     */
    @GetMapping("/credit-followup")
    public CreditFollowUpResponse creditFollowUp() {

        LocalDate today = LocalDate.now(SRI_LANKA_TIME_ZONE);
        List<CreditFollowUpRowResponse> rows = new ArrayList<>();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalOverdue = BigDecimal.ZERO;

        for (Customer customer : customerService.getAllCustomers()) {

            List<SalesInvoice> creditInvoices = salesInvoiceRepository
                    .findByCustomerIdOrderByInvoiceDateDesc(customer.getId()).stream()
                    .filter(invoice -> "COMPLETED".equalsIgnoreCase(invoice.getStatus()))
                    .filter(invoice -> "CREDIT".equalsIgnoreCase(invoice.getSaleType()))
                    .toList();

            BigDecimal customerOutstanding = BigDecimal.ZERO;
            BigDecimal customerOverdue = BigDecimal.ZERO;
            LocalDate oldestOverdueDueDate = null;

            for (SalesInvoice invoice : creditInvoices) {
                BigDecimal netAmount = zeroIfNull(invoice.getNetAmount());
                BigDecimal paidAmount = paymentService.getPaidAmount(invoice.getId());
                BigDecimal balance = netAmount.subtract(paidAmount);

                if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                customerOutstanding = customerOutstanding.add(balance);

                if (invoice.getDueDate() != null && invoice.getDueDate().isBefore(today)) {
                    customerOverdue = customerOverdue.add(balance);

                    if (oldestOverdueDueDate == null || invoice.getDueDate().isBefore(oldestOverdueDueDate)) {
                        oldestOverdueDueDate = invoice.getDueDate();
                    }
                }
            }

            if (customerOutstanding.compareTo(BigDecimal.ZERO) > 0) {
                long daysOverdue = oldestOverdueDueDate == null
                        ? 0
                        : java.time.temporal.ChronoUnit.DAYS.between(oldestOverdueDueDate, today);

                rows.add(new CreditFollowUpRowResponse(
                        customer.getId(),
                        customer.getCustomerCode(),
                        customer.getCustomerName(),
                        customer.getArea(),
                        customer.getPhone(),
                        customerOutstanding,
                        customerOverdue,
                        oldestOverdueDueDate,
                        daysOverdue));

                totalOutstanding = totalOutstanding.add(customerOutstanding);
                totalOverdue = totalOverdue.add(customerOverdue);
            }
        }

        rows.sort((a, b) -> Long.compare(b.getDaysOverdue(), a.getDaysOverdue()));

        return new CreditFollowUpResponse(rows, totalOutstanding, totalOverdue);
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

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] file = excelService.buildTemplate(
                "Customers",
                IMPORT_HEADERS,
                List.of("Green Valley Store", "Colombo", "0771234567", "12 Main St", "Mr. Perera", "50000", "21", "RETAIL", "ACTIVE", "")
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import")
    public ImportSummaryResponse importCustomers(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> rows = excelService.readRows(file.getInputStream());
        List<ImportSummaryResponse.RowError> errors = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 2; // +1 for header row, +1 for 1-based row numbers
            Map<String, String> row = rows.get(i);

            try {
                CustomerRequest request = new CustomerRequest();
                request.setCustomerName(row.getOrDefault("Customer Name*", ""));
                request.setArea(blankToNull(row.get("Area")));
                request.setPhone(blankToNull(row.get("Phone")));
                request.setAddress(blankToNull(row.get("Address")));
                request.setContactPerson(blankToNull(row.get("Contact Person")));
                request.setCreditLimit(parseDecimal(row.get("Credit Limit")));
                request.setPaymentTermsDays(parseInt(row.get("Payment Terms Days")));
                request.setCustomerType(blankToNull(row.get("Customer Type")));
                request.setStatus(blankOr(row.get("Status"), "ACTIVE"));
                request.setNotes(blankToNull(row.get("Notes")));

                if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
                    throw new IllegalArgumentException("Customer Name is required.");
                }

                Customer customer = new Customer();
                applyRequest(customer, request);
                customerService.saveCustomer(customer);
                successCount++;
            } catch (Exception e) {
                errors.add(new ImportSummaryResponse.RowError(rowNumber, e.getMessage()));
            }
        }

        return new ImportSummaryResponse(rows.size(), successCount, errors);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a valid number.");
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a valid whole number.");
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
