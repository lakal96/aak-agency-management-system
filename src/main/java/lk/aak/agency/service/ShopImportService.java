package lk.aak.agency.service;

import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.dto.api.ImportSummaryResponse.RowError;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bulk shop (customer) import from .xlsx. Per-row partial: a bad row is reported and the rest still import.
 *
 * Re-importing the same sheet never creates duplicates: a row matches an existing shop by
 * (name + phone) when the row has a phone, otherwise by (name + area), ignoring case and extra spaces,
 * and then UPDATES it (blank cells leave the shop unchanged). A row that repeats an earlier row of the
 * same sheet is skipped ("duplicate of row N"). The optional last column names the sales rep.
 */
@Service
public class ShopImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShopImportService.class);

    public static final String SALES_REP_HEADER = "Sales Rep (login or employee name)";
    private static final String NAME = "Customer Name*";
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\-\\s]{7,20}$");

    private final ExcelService excelService;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final EmployeeRepository employeeRepository;

    public ShopImportService(
            ExcelService excelService,
            CustomerRepository customerRepository,
            CustomerService customerService,
            EmployeeRepository employeeRepository) {

        this.excelService = excelService;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.employeeRepository = employeeRepository;
    }

    public ImportSummaryResponse importShops(InputStream in) {

        ExcelService.ImportSheet sheet = excelService.readSheet(in);
        if (!sheet.headers().contains(NAME)) {
            throw new IllegalArgumentException(
                    "The first row of the sheet must have the column headings from the template (\"" + NAME
                            + "\" is missing). Download the template and copy your data into it.");
        }

        List<Customer> existingShops = new ArrayList<>(customerRepository.findAll());
        List<Employee> activeReps = employeeRepository.findAll().stream()
                .filter(e -> "SALES_REP".equalsIgnoreCase(e.getDesignation()) && "ACTIVE".equalsIgnoreCase(e.getStatus()))
                .toList();

        List<RowError> errors = new ArrayList<>();
        List<RowError> skipped = new ArrayList<>();
        List<RowError> warnings = new ArrayList<>();
        Map<String, Integer> firstRowOfKey = new HashMap<>();
        int created = 0;
        int updated = 0;

        for (ExcelService.ImportRow importRow : sheet.rows()) {
            int rowNumber = importRow.rowNumber();
            try {
                Map<String, String> row = importRow.values();

                String name = ImportValues.text(row.get(NAME), "Customer Name", 150);
                if (name == null) {
                    throw new IllegalArgumentException("the Customer Name column is empty - it is required.");
                }
                String area = ImportValues.text(row.get("Area"), "Area", ImportValues.MAX_SHORT_TEXT);
                String phone = phone(row.get("Phone"));

                String key = phone != null
                        ? "P|" + nameKey(name) + "|" + phoneKey(phone)
                        : "A|" + nameKey(name) + "|" + nameKey(area);
                Integer firstRow = firstRowOfKey.get(key);
                if (firstRow != null) {
                    skipped.add(new RowError(rowNumber, "duplicate of row " + firstRow + " - skipped."));
                    continue;
                }

                String address = ImportValues.text(row.get("Address"), "Address", ImportValues.MAX_LONG_TEXT);
                String contact = ImportValues.text(row.get("Contact Person"), "Contact Person", ImportValues.MAX_SHORT_TEXT);
                BigDecimal creditLimit = ImportValues.money(row.get("Credit Limit"), "Credit Limit");
                Integer terms = ImportValues.wholeNumber(row.get("Payment Terms Days"), "Payment Terms Days");
                String type = ImportValues.text(row.get("Customer Type"), "Customer Type", ImportValues.MAX_SHORT_TEXT);
                String status = ImportValues.status(row.get("Status"), "Status");
                String notes = ImportValues.text(row.get("Notes"), "Notes", ImportValues.MAX_LONG_TEXT);

                // The rep column is optional: a bad value is a warning, never a reason to skip the shop.
                List<RowError> rowWarnings = new ArrayList<>();
                Long repId = null;
                String repText = ImportValues.blankToNull(row.get(SALES_REP_HEADER));
                if (repText != null) {
                    List<Employee> matches = activeReps.stream()
                            .filter(e -> repText.equalsIgnoreCase(trim(e.getSystemUsername()))
                                    || repText.equalsIgnoreCase(trim(e.getFullName())))
                            .toList();
                    if (matches.size() == 1) {
                        repId = matches.get(0).getId();
                    } else if (matches.isEmpty()) {
                        rowWarnings.add(new RowError(rowNumber, "no active sales rep matches \"" + repText
                                + "\" - the shop was imported without a rep."));
                    } else {
                        rowWarnings.add(new RowError(rowNumber, "more than one active sales rep matches \"" + repText
                                + "\" - the shop was imported without a rep."));
                    }
                }

                List<Customer> matches = findMatches(existingShops, name, phone, area);

                if (matches.isEmpty()) {
                    Customer customer = new Customer();
                    customer.setCustomerName(name);
                    customer.setArea(area);
                    customer.setPhone(phone);
                    customer.setAddress(address);
                    customer.setContactPerson(contact);
                    customer.setCreditLimit(creditLimit == null ? BigDecimal.ZERO : creditLimit);
                    customer.setPaymentTermsDays(terms == null ? 21 : terms);
                    customer.setCustomerType(type);
                    customer.setStatus(status == null ? "ACTIVE" : status);
                    customer.setNotes(notes);
                    customer.setAssignedEmployeeId(repId);
                    existingShops.add(customerService.saveCustomer(customer));
                    created++;
                } else {
                    Customer customer = matches.get(0);
                    if (matches.size() > 1) {
                        rowWarnings.add(new RowError(rowNumber, "more than one existing shop has this name - "
                                + "the oldest one (" + customer.getCustomerCode() + ") was updated."));
                    }

                    BigDecimal oldCreditLimit = customer.getCreditLimit();
                    Integer oldTerms = customer.getPaymentTermsDays();

                    // Blank cell = leave the existing value alone.
                    if (area != null) customer.setArea(area);
                    if (phone != null) customer.setPhone(phone);
                    if (address != null) customer.setAddress(address);
                    if (contact != null) customer.setContactPerson(contact);
                    if (creditLimit != null) customer.setCreditLimit(creditLimit);
                    if (terms != null) customer.setPaymentTermsDays(terms);
                    if (type != null) customer.setCustomerType(type);
                    if (status != null) customer.setStatus(status);
                    if (notes != null) customer.setNotes(notes);
                    if (repId != null) customer.setAssignedEmployeeId(repId);

                    Customer saved = customerService.saveCustomer(customer);
                    customerService.recordCreditTermsChange(saved, oldCreditLimit, oldTerms, "bulk import");
                    updated++;
                }

                firstRowOfKey.put(key, rowNumber);
                warnings.addAll(rowWarnings);
            } catch (Exception e) {
                errors.add(new RowError(rowNumber, ImportValues.friendlyMessage(e, "shop", LOGGER, rowNumber)));
            }
        }

        return new ImportSummaryResponse(sheet.rows().size(), created, updated, errors, skipped, warnings);
    }

    /** Existing shops this row refers to, oldest first. */
    private List<Customer> findMatches(List<Customer> shops, String name, String phone, String area) {
        String wantedName = nameKey(name);
        return shops.stream()
                .filter(shop -> nameKey(shop.getCustomerName()).equals(wantedName))
                .filter(shop -> phone != null
                        ? phoneKey(phone).equals(phoneKey(shop.getPhone()))
                        : nameKey(area).equals(nameKey(shop.getArea())))
                .sorted((a, b) -> Long.compare(a.getId() == null ? 0 : a.getId(), b.getId() == null ? 0 : b.getId()))
                .toList();
    }

    private String phone(String value) {
        String trimmed = ImportValues.text(value, "Phone", 20);
        if (trimmed == null) {
            return null;
        }
        // Excel drops the leading 0 of a phone typed as a number (0771234567 -> 771234567).
        if (trimmed.matches("[1-9][0-9]{8}")) {
            trimmed = "0" + trimmed;
        }
        if (!PHONE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "the Phone column has \"" + trimmed + "\", which is not a valid phone number.");
        }
        return trimmed;
    }

    private static String nameKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String phoneKey(String value) {
        return value == null ? "" : value.replaceAll("[^0-9+]", "");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
