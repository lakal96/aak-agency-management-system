package lk.aak.agency.service;

import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.model.Product;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductImportServiceTest {

    private static final String[] HEADERS = {
            "CBL Product Code*", "Product Name*", "Brand", "Category", "Net Weight",
            "Unit*", "MRP", "Standard Selling Price", "Reorder Level", "Status", "Notes"
    };

    @Autowired
    private ProductImportService importService;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private Product product(String code) {
        return productRepository.findByCblProductCode(code).orElseThrow();
    }

    @Test
    void numericCellsKeepTheirExactValueWhateverTheDisplayFormat() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "PI-NUM-1").text(1, 1, "Num one").text(1, 5, "PCS")
                    .number(1, 6, 250.75, "0")              // displays as 251
                    .number(1, 7, 1200.5, "#,##0.00")       // displays as 1,200.50
                    .number(1, 8, 12, "0");

            ImportSummaryResponse result = importService.importProducts(sheet.stream());

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getCreatedCount()).isEqualTo(1);
            Product saved = product("PI-NUM-1");
            assertThat(saved.getMrp()).isEqualByComparingTo("250.75");
            assertThat(saved.getStandardSellingPrice()).isEqualByComparingTo("1200.50");
            assertThat(saved.getReorderLevel()).isEqualByComparingTo("12");
        }
    }

    @Test
    void textAmountsWithCommasSpacesAndRsAreParsedAndFormulasAreEvaluated() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "PI-TXT-1").text(1, 1, "Text one").text(1, 5, "PCS")
                    .text(1, 6, " Rs. 1,200 ").text(1, 7, "1,200.50")
                    .formula(1, 8, "5+5");

            ImportSummaryResponse result = importService.importProducts(sheet.stream());

            assertThat(result.getErrors()).isEmpty();
            Product saved = product("PI-TXT-1");
            assertThat(saved.getMrp()).isEqualByComparingTo("1200");
            assertThat(saved.getStandardSellingPrice()).isEqualByComparingTo("1200.50");
            assertThat(saved.getReorderLevel()).isEqualByComparingTo("10");
        }
    }

    @Test
    void invalidNumberGivesARowErrorNamingTheColumnAndTheRealSpreadsheetRow() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "PI-OK-1").text(1, 1, "Fine").text(1, 5, "PCS")
                    .blankRow(2).blankRow(3)
                    .text(4, 0, "PI-BAD-1").text(4, 1, "Bad price").text(4, 5, "PCS").text(4, 6, "abc");

            ImportSummaryResponse result = importService.importProducts(sheet.stream());

            assertThat(result.getTotalRows()).isEqualTo(2);
            assertThat(result.getCreatedCount()).isEqualTo(1);
            assertThat(result.getFailedCount()).isEqualTo(1);
            assertThat(result.getErrors().get(0).getRow()).isEqualTo(5);   // not 3 (index + 2)
            assertThat(result.getErrors().get(0).getMessage()).contains("MRP").contains("abc");
            assertThat(productRepository.findByCblProductCode("PI-BAD-1")).isEmpty();
        }
    }

    @Test
    void blankCellsOnUpdateLeaveTheProductUnchanged_andPriceChangeIsAudited() throws Exception {
        Product existing = new Product();
        existing.setCblProductCode("PI-UPD-1");
        existing.setProductName("Original name");
        existing.setUnit("PCS");
        existing.setMrp(new BigDecimal("100.00"));
        existing.setStandardSellingPrice(new BigDecimal("90.00"));
        existing.setReorderLevel(new BigDecimal("7"));
        existing.setStatus("INACTIVE");
        productRepository.save(existing);

        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "pi-upd-1").text(1, 7, "95.50");   // only the selling price is given

            ImportSummaryResponse result = importService.importProducts(sheet.stream());

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getUpdatedCount()).isEqualTo(1);
            assertThat(result.getCreatedCount()).isZero();
        }

        Product after = product("PI-UPD-1");
        assertThat(after.getProductName()).isEqualTo("Original name");
        assertThat(after.getMrp()).isEqualByComparingTo("100");
        assertThat(after.getStandardSellingPrice()).isEqualByComparingTo("95.50");
        assertThat(after.getReorderLevel()).isEqualByComparingTo("7");
        assertThat(after.getStatus()).isEqualTo("INACTIVE");

        assertThat(auditLogRepository.search("PI-UPD-1", PageRequest.of(0, 10)).getContent())
                .extracting(AuditLog::getAction, AuditLog::getDetails)
                .anySatisfy(t -> {
                    assertThat(t.toList().get(0)).isEqualTo("PRODUCT_PRICE_CHANGED");
                    assertThat((String) t.toList().get(1)).contains("Selling price 90.00 -> 95.50").doesNotContain("MRP");
                });
    }

    @Test
    void newProductWithBlankPricesGetsDefaults() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "PI-NEW-1").text(1, 1, "Brand new").text(1, 5, "PCS");

            importService.importProducts(sheet.stream());
        }

        Product saved = product("PI-NEW-1");
        assertThat(saved.getMrp()).isEqualByComparingTo("0");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void tooLongNotesGiveAFriendlyMessageWithoutSqlText() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet(HEADERS)) {
            sheet.text(1, 0, "PI-LONG-1").text(1, 1, "Long notes").text(1, 5, "PCS")
                    .text(1, 10, "x".repeat(ImportValues.MAX_LONG_TEXT + 1));

            ImportSummaryResponse result = importService.importProducts(sheet.stream());

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).getMessage())
                    .isEqualTo("the Notes column is too long (max " + ImportValues.MAX_LONG_TEXT + " characters).");
        }
    }

    @Test
    void sheetWithoutTheTemplateHeadingsIsRefusedWithAClearMessage() throws Exception {
        try (XlsxTestSheet sheet = new XlsxTestSheet("Code", "Name")) {
            sheet.text(1, 0, "X").text(1, 1, "Y");

            assertThatThrownBy(() -> importService.importProducts(sheet.stream()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Download the template");
        }
    }
}
