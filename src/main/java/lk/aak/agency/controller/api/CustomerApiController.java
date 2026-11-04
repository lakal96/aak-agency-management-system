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
import lk.aak.agency.dto.api.ShopScanPreviewResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Payment;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.repository.PaymentRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.service.CustomerService;
import lk.aak.agency.service.ExcelService;
import lk.aak.agency.service.PaymentService;
import lk.aak.agency.service.QrCodeService;
import lk.aak.agency.service.ShopImportService;
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

import org.springframework.security.core.Authentication;
import lk.aak.agency.service.SalesRepScopeService;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ZoneId SRI_LANKA_TIME_ZONE = ZoneId.of("Asia/Colombo");

    private static final List<String> IMPORT_HEADERS = List.of(
            "Customer Name*", "Area", "Phone", "Address", "Contact Person",
            "Credit Limit", "Payment Terms Days", "Customer Type", "Status", "Notes",
            ShopImportService.SALES_REP_HEADER
    );

    private final CustomerService customerService;
    private final QrCodeService qrCodeService;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final ExcelService excelService;
    private final SalesRepScopeService salesRepScopeService;
    private final ShopImportService shopImportService;

    public CustomerApiController(
            CustomerService customerService,
            QrCodeService qrCodeService,
            SalesInvoiceRepository salesInvoiceRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            ExcelService excelService,
            SalesRepScopeService salesRepScopeService,
            ShopImportService shopImportService) {

        this.customerService = customerService;
        this.qrCodeService = qrCodeService;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.excelService = excelService;
        this.salesRepScopeService = salesRepScopeService;
        this.shopImportService = shopImportService;
    }

    /** Hides a customer outside a SALES_REP's assigned scope by reporting it as not found. */
    private void assertVisible(Customer customer, Authentication authentication) {
        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);
        if (scopedEmployeeId != null && !scopedEmployeeId.equals(customer.getAssignedEmployeeId())) {
            throw new NoSuchElementException("Customer not found.");
        }
    }

    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q,
            Authentication authentication) {

        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);

        Page<Customer> customers = (q == null || q.isBlank())
                ? customerService.getCustomers(page, size, scopedEmployeeId)
                : customerService.search(q, page, size, scopedEmployeeId);

        return PageResponse.from(customers, CustomerResponse::new);
    }

    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable Long id, Authentication authentication) {
        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));
        assertVisible(customer, authentication);
        return new CustomerResponse(customer);
    }

    /** The shop's printable QR code (PNG) - scanning it opens this shop's credit history. */
    @GetMapping(value = "/{id}/qr-code", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrCode(@PathVariable Long id, Authentication authentication) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));
        assertVisible(customer, authentication);

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
    public CustomerResponse scan(@PathVariable String qrCode, Authentication authentication) {
        Customer customer = customerService.getCustomerByQrCode(qrCode)
                .orElseThrow(() -> new NoSuchElementException("No shop matches this QR code."));
        // A rep scanning another rep's shop gets the same answer as an unknown code.
        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);
        if (scopedEmployeeId != null && !scopedEmployeeId.equals(customer.getAssignedEmployeeId())) {
            throw new NoSuchElementException("No shop matches this QR code.");
        }
        return new CustomerResponse(customer);
    }

    /** Public (unauthenticated) preview for a scanned shop QR - shop name only, nothing else. */
    @GetMapping("/scan/{qrCode}/preview")
    public ShopScanPreviewResponse scanPreview(@PathVariable String qrCode) {
        return customerService.getCustomerByQrCode(qrCode)
                .map(customer -> new ShopScanPreviewResponse(customer.getCustomerName()))
                .orElseThrow(() -> new NoSuchElementException("No shop matches this QR code."));
    }

    @GetMapping("/{id}/credit-history")
    public CustomerCreditHistoryResponse creditHistory(@PathVariable Long id, Authentication authentication) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));
        assertVisible(customer, authentication);

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
    public CreditFollowUpResponse creditFollowUp(Authentication authentication) {

        // A sales rep sees only the shops assigned to them (an unlinked rep sees none).
        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);

        LocalDate today = LocalDate.now(SRI_LANKA_TIME_ZONE);
        List<CreditFollowUpRowResponse> rows = new ArrayList<>();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalOverdue = BigDecimal.ZERO;

        for (Customer customer : customerService.getAllCustomers()) {

            if (scopedEmployeeId != null && !scopedEmployeeId.equals(customer.getAssignedEmployeeId())) {
                continue;
            }

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
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request, Authentication authentication) {

        Customer customer = new Customer();
        applyRequest(customer, request);

        // A SALES_REP can only ever assign new shops to themselves, never to another employee.
        Long scopedEmployeeId = salesRepScopeService.resolveScopedEmployeeId(authentication);
        if (scopedEmployeeId != null) {
            customer.setAssignedEmployeeId(scopedEmployeeId);
        }

        Customer saved = customerService.saveCustomer(customer);

        return ResponseEntity.status(HttpStatus.CREATED).body(new CustomerResponse(saved));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {

        Customer customer = customerService.getCustomerById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found."));

        BigDecimal oldCreditLimit = customer.getCreditLimit();
        Integer oldPaymentTermsDays = customer.getPaymentTermsDays();

        applyRequest(customer, request);

        Customer saved = customerService.saveCustomer(customer);
        customerService.recordCreditTermsChange(saved, oldCreditLimit, oldPaymentTermsDays, "edit");

        return new CustomerResponse(saved);
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
        customer.setAssignedEmployeeId(request.getAssignedEmployeeId());
        customer.setStatus(request.getStatus());
        customer.setNotes(request.getNotes());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] file = excelService.buildTemplate(
                "Customers",
                IMPORT_HEADERS,
                List.of("Green Valley Store", "Colombo", "0771234567", "12 Main St", "Mr. Perera", "50000", "21", "RETAIL", "ACTIVE", "", "")
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import")
    public ImportSummaryResponse importCustomers(@RequestParam("file") MultipartFile file) throws IOException {
        return shopImportService.importShops(file.getInputStream());
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
