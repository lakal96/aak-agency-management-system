package lk.aak.agency.service;

import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional   // roll back, so the shared in-memory database stays clean for other tests
class ShopImportServiceTest {

    private static final String[] HEADERS = {
            "Customer Name*", "Area", "Phone", "Address", "Contact Person",
            "Credit Limit", "Payment Terms Days", "Customer Type", "Status", "Notes",
            "Sales Rep (login or employee name)"
    };

    @Autowired
    private ShopImportService importService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private List<Customer> shopsNamed(String name) {
        return customerRepository.findAll().stream()
                .filter(c -> c.getCustomerName().equalsIgnoreCase(name.trim()))
                .toList();
    }

    private Employee employee(String code, String name, String username, String designation, String status) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFullName(name);
        e.setDesignation(designation);
        e.setStatus(status);
        e.setSystemUsername(username);
        return employeeRepository.save(e);
    }

    @Test
    void reImportingTheSameSheetUpdatesInsteadOfDuplicating() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "SI Reimport Mart").text(1, 1, "Kandy").text(1, 2, "0771111111")
                    .number(1, 5, 50000, "0").number(1, 6, 21, "0");

            ImportSummaryResponse first = importService.importShops(sheet.stream());
            ImportSummaryResponse second = importService.importShops(sheet.stream());

            assertThat(first.getCreatedCount()).isEqualTo(1);
            assertThat(second.getCreatedCount()).isZero();
            assertThat(second.getUpdatedCount()).isEqualTo(1);
            assertThat(shopsNamed("SI Reimport Mart")).hasSize(1);
        }
    }

    @Test
    void matchIgnoresCaseAndSpaces_andBlankCellsLeaveTheShopUnchanged_andCreditChangeIsAudited() throws Exception {
        Customer existing = new Customer();
        existing.setCustomerCode("SI-CODE-2");
        existing.setCustomerName("SI Match Store");
        existing.setPhone("0772222222");
        existing.setArea("Galle");
        existing.setAddress("Old address");
        existing.setCreditLimit(new BigDecimal("1000.00"));
        existing.setPaymentTermsDays(14);
        existing.setStatus("INACTIVE");
        customerRepository.save(existing);

        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "  si MATCH  store ").text(1, 2, "0772222222").number(1, 5, 2500, "0");

            ImportSummaryResponse result = importService.importShops(sheet.stream());

            assertThat(result.getUpdatedCount()).isEqualTo(1);
            assertThat(result.getErrors()).isEmpty();
        }

        assertThat(shopsNamed("SI Match Store")).hasSize(1);
        Customer after = shopsNamed("SI Match Store").get(0);
        assertThat(after.getCreditLimit()).isEqualByComparingTo("2500");
        assertThat(after.getPaymentTermsDays()).isEqualTo(14);
        assertThat(after.getAddress()).isEqualTo("Old address");
        assertThat(after.getStatus()).isEqualTo("INACTIVE");
        assertThat(after.getArea()).isEqualTo("Galle");

        assertThat(auditLogRepository.search("SI Match Store", PageRequest.of(0, 10)).getContent())
                .extracting(AuditLog::getDetails)
                .anySatisfy(d -> assertThat(d).contains("Credit limit 1000.00 -> 2500.00").doesNotContain("Payment terms"));
    }

    @Test
    void withoutAPhoneTheShopMatchesByNameAndArea() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "SI Area Shop").text(1, 1, "Matale")
                    .text(2, 0, "SI Area Shop").text(2, 1, "Jaffna");

            ImportSummaryResponse first = importService.importShops(sheet.stream());
            ImportSummaryResponse again = importService.importShops(sheet.stream());

            assertThat(first.getCreatedCount()).isEqualTo(2);       // same name, different area = different shops
            assertThat(again.getUpdatedCount()).isEqualTo(2);
            assertThat(shopsNamed("SI Area Shop")).hasSize(2);
        }
    }

    @Test
    void duplicateRowsInsideOneSheetAreSkippedAndReported() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "SI Dup Shop").text(1, 2, "0773333333")
                    .blankRow(2)
                    .text(3, 0, "si dup shop ").text(3, 2, "0773333333");

            ImportSummaryResponse result = importService.importShops(sheet.stream());

            assertThat(result.getCreatedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getSkipped().get(0).getRow()).isEqualTo(4);
            assertThat(result.getSkipped().get(0).getMessage()).contains("duplicate of row 2");
            assertThat(result.getFailedCount()).isZero();
            assertThat(shopsNamed("SI Dup Shop")).hasSize(1);
        }
    }

    @Test
    void salesRepColumnAssignsOnlyWhenExactlyOneActiveRepMatches() throws Exception {
        Employee rep = employee("SI-E1", "Nimal Perera", "nimal.si", "SALES_REP", "ACTIVE");
        employee("SI-E2", "Kamal Same", "kamal.one", "SALES_REP", "ACTIVE");
        employee("SI-E3", "Kamal Same", "kamal.two", "SALES_REP", "ACTIVE");
        employee("SI-E4", "Old Rep", "old.si", "SALES_REP", "INACTIVE");
        employee("SI-E5", "Driver Dan", "dan.si", "DRIVER", "ACTIVE");

        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "SI Rep Shop A").text(1, 10, "NIMAL.SI")
                    .text(2, 0, "SI Rep Shop B").text(2, 10, "Nimal Perera")
                    .text(3, 0, "SI Rep Shop C").text(3, 10, "Kamal Same")
                    .text(4, 0, "SI Rep Shop D").text(4, 10, "Old Rep")
                    .text(5, 0, "SI Rep Shop E").text(5, 10, "Driver Dan");

            ImportSummaryResponse result = importService.importShops(sheet.stream());

            assertThat(result.getCreatedCount()).isEqualTo(5);
            assertThat(result.getFailedCount()).isZero();
            assertThat(shopsNamed("SI Rep Shop A").get(0).getAssignedEmployeeId()).isEqualTo(rep.getId());
            assertThat(shopsNamed("SI Rep Shop B").get(0).getAssignedEmployeeId()).isEqualTo(rep.getId());
            assertThat(shopsNamed("SI Rep Shop C").get(0).getAssignedEmployeeId()).isNull();
            assertThat(shopsNamed("SI Rep Shop D").get(0).getAssignedEmployeeId()).isNull();
            assertThat(shopsNamed("SI Rep Shop E").get(0).getAssignedEmployeeId()).isNull();
            assertThat(result.getWarnings()).extracting(w -> w.getRow()).containsExactly(4, 5, 6);
        }
    }

    @Test
    void excelDroppedLeadingZeroOfAPhoneNumberIsRestored() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "SI Phone Shop").number(1, 2, 771234567d, "0");

            importService.importShops(sheet.stream());
        }

        assertThat(shopsNamed("SI Phone Shop").get(0).getPhone()).isEqualTo("0771234567");
    }

    @Test
    void oldSheetWithoutTheRepColumnStillWorks_andBadValuesGiveFriendlyRowErrors() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(
                "Customer Name*", "Area", "Phone", "Address", "Contact Person",
                "Credit Limit", "Payment Terms Days", "Customer Type", "Status", "Notes")) {
            sheet.text(1, 0, "SI Old Format Shop")
                    .text(2, 0, "SI Bad Terms Shop").text(2, 6, "soon");

            ImportSummaryResponse result = importService.importShops(sheet.stream());

            assertThat(result.getCreatedCount()).isEqualTo(1);
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getRow()).isEqualTo(3);
            assertThat(result.getErrors().get(0).getMessage()).contains("Payment Terms Days");
        }
    }
}
