package lk.aak.agency.service;

import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PRIndirectReference;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfServiceTest {

    private final PdfService pdfService = new PdfService();

    // Sinhala: "Kasun Stores" and an address in Kandy, written without left-hand vowel signs so the
    // extracted text can be compared with what was put in.
    private static final String SINHALA_NAME = "කසුන් සාපුව";
    private static final String SINHALA_AREA = "මාතලන්";

    private Product product() {
        Product product = new Product();
        product.setCblProductCode("CBL-001");
        product.setProductName("Sample Biscuit Pack");
        product.setUnit("PCS");
        return product;
    }

    private SalesInvoice salesInvoice(SalesInvoiceItem[] itemsOut) {
        Customer customer = new Customer();
        customer.setCustomerCode("AAK-000001");
        customer.setCustomerName(SINHALA_NAME);
        customer.setArea(SINHALA_AREA);
        customer.setPhone("0771234567");

        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("AAK-S-0001");
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(LocalDate.of(2026, 11, 4));
        invoice.setDueDate(LocalDate.of(2026, 11, 25));
        invoice.setSaleType("CREDIT");
        invoice.setPaymentStatus("UNPAID");
        invoice.setGrossAmount(new BigDecimal("1234567.5"));
        invoice.setDiscountAmount(new BigDecimal("1000"));
        invoice.setReturnAmount(BigDecimal.ZERO);
        invoice.setNetAmount(new BigDecimal("1233567.5"));

        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setProduct(product());
        item.setQuantity(new BigDecimal("1200"));
        item.setUnit("PCS");
        item.setUnitPrice(new BigDecimal("1028.806"));
        item.setAmount(new BigDecimal("1234567.5"));
        itemsOut[0] = item;
        return invoice;
    }

    private String textOf(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    @Test
    void sinhalaCustomerNameAndAreaSurviveInTheSalesInvoicePdf() throws Exception {
        SalesInvoiceItem[] item = new SalesInvoiceItem[1];
        SalesInvoice invoice = salesInvoice(item);

        byte[] pdf = pdfService.generateSalesInvoicePdf(invoice, List.of(item[0]), new BigDecimal("500"), new BigDecimal("1233067.5"));

        String text = textOf(pdf);
        assertThat(text).contains(SINHALA_NAME);
        assertThat(text).contains(SINHALA_AREA);
        assertThat(text).contains("AAK-S-0001").contains("Sample Biscuit Pack").contains("0771234567");
    }

    @Test
    void aLongBillRepeatsTheColumnNamesAndKeepsItsTotalsTogetherOnOnePage() throws Exception {
        for (int lines : new int[]{18, 19, 20, 21, 22, 23, 24, 25, 26, 38, 39, 40, 41, 60}) {
            SalesInvoiceItem[] first = new SalesInvoiceItem[1];
            SalesInvoice invoice = salesInvoice(first);
            List<SalesInvoiceItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < lines; i++) {
                items.add(first[0]);
            }

            byte[] pdf = pdfService.generateSalesInvoicePdf(invoice, items, new BigDecimal("500"), new BigDecimal("1233067.5"));

            PdfReader reader = new PdfReader(pdf);
            try {
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                int pages = reader.getNumberOfPages();
                for (int page = 2; page <= pages; page++) {
                    String text = extractor.getTextFromPage(page);
                    if (text.contains("Sample Biscuit Pack")) {
                        assertThat(text).as("%d lines: page %d carries rows, so it repeats the column names", lines, page).contains("Unit Price");
                    }
                }
                int withGross = -1;
                int withBalance = -1;
                for (int page = 1; page <= pages; page++) {
                    String text = extractor.getTextFromPage(page);
                    if (text.contains("Gross Amount")) withGross = page;
                    if (text.contains("Balance")) withBalance = page;
                }
                assertThat(withBalance).as("%d lines: the totals box is on one page", lines).isEqualTo(withGross);
            } finally {
                reader.close();
            }
        }
    }

    @Test
    void theSinhalaFontIsEmbeddedInThePdf() throws Exception {
        SalesInvoiceItem[] item = new SalesInvoiceItem[1];
        byte[] pdf = pdfService.generateSalesInvoicePdf(salesInvoice(item), List.of(item[0]), BigDecimal.ZERO, BigDecimal.ZERO);

        PdfReader reader = new PdfReader(pdf);
        try {
            boolean embeddedSinhala = false;
            for (int i = 1; i < reader.getXrefSize(); i++) {
                PdfObject object = reader.getPdfObject(i);
                if (object instanceof PdfDictionary dictionary && PdfName.FONTDESCRIPTOR.equals(dictionary.get(PdfName.TYPE))) {
                    String fontName = String.valueOf(dictionary.get(PdfName.FONTNAME));
                    if (fontName.contains("NotoSansSinhala") && dictionary.get(PdfName.FONTFILE2) != null) {
                        embeddedSinhala = true;
                    }
                }
            }
            assertThat(embeddedSinhala).as("Noto Sans Sinhala is embedded (FontFile2 present)").isTrue();
        } finally {
            reader.close();
        }
    }

    @Test
    void sinhalaGlyphsExistInTheBundledFonts() throws Exception {
        for (String resource : new String[]{"/fonts/NotoSansSinhala-Regular.ttf", "/fonts/NotoSansSinhala-Bold.ttf"}) {
            byte[] bytes = getClass().getResourceAsStream(resource).readAllBytes();
            BaseFont font = BaseFont.createFont("x.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
            for (char c : (SINHALA_NAME + SINHALA_AREA).replace(" ", "").toCharArray()) {
                assertThat(font.charExists(c)).as("glyph for U+%04X in %s", (int) c, resource).isTrue();
            }
        }
    }

    @Test
    void salesInvoiceShowsRupeeAmountsWithThousandsSeparatorsAndDdMmYyyyDates() throws Exception {
        SalesInvoiceItem[] item = new SalesInvoiceItem[1];
        byte[] pdf = pdfService.generateSalesInvoicePdf(salesInvoice(item), List.of(item[0]), new BigDecimal("500"), new BigDecimal("1233067.5"));

        String text = textOf(pdf);
        assertThat(text).contains("Rs. 1,234,567.50").contains("Rs. 1,233,567.50").contains("Rs. 1,233,067.50")
                .contains("Rs. 1,000.00").contains("Rs. 500.00").contains("Rs. 1,028.81");
        assertThat(text).contains("04/11/2026").contains("25/11/2026");
        assertThat(text).doesNotContain("2026-11-04").doesNotContain("Rs. 1234567");
    }

    @Test
    void purchaseInvoiceUsesTheSameFormats() throws Exception {
        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setDocumentNumber("PO-77");
        invoice.setInvoiceDate(LocalDate.of(2026, 1, 9));
        invoice.setDeliveryDate(LocalDate.of(2026, 1, 12));
        invoice.setSupplierName("CBL Foods");
        invoice.setPlaceOfSupply("Kandy");
        invoice.setStatus("DRAFT");
        invoice.setSubtotal(new BigDecimal("2500000"));
        invoice.setDiscountAmount(new BigDecimal("0"));
        invoice.setVatAmount(new BigDecimal("12.3"));
        invoice.setTotalAmount(new BigDecimal("2500012.3"));

        PurchaseInvoiceItem item = new PurchaseInvoiceItem();
        item.setProduct(product());
        item.setQuantity(new BigDecimal("10"));
        item.setUnit("PCS");
        item.setUnitPrice(new BigDecimal("250000"));
        item.setAmount(new BigDecimal("2500000"));
        item.setExpiryDate(LocalDate.of(2027, 3, 1));

        String text = textOf(pdfService.generatePurchaseInvoicePdf(invoice, List.of(item)));

        assertThat(text).contains("Rs. 2,500,000.00").contains("Rs. 12.30").contains("Rs. 2,500,012.30");
        assertThat(text).contains("09/01/2026").contains("12/01/2026").contains("01/03/2027");
    }

    @Test
    void moneyAndDateFormatHelpers() {
        assertThat(PdfService.formatMoney(null)).isEqualTo("Rs. 0.00");
        assertThat(PdfService.formatMoney(new BigDecimal("999.995"))).isEqualTo("Rs. 1,000.00");
        assertThat(PdfService.formatMoney(new BigDecimal("1234.5"))).isEqualTo("Rs. 1,234.50");
        assertThat(PdfService.formatDate(LocalDate.of(2026, 3, 5))).isEqualTo("05/03/2026");
        assertThat(PdfService.formatDate(null)).isEqualTo("-");
    }

    @Test
    void leftHandSinhalaVowelSignsAreMovedInFrontOfTheirConsonant() {
        // ke = KA + E-SIGN  ->  E-SIGN KA
        assertThat(SinhalaShaping.toVisualOrder("කෙ")).isEqualTo("ෙක");
        // ko = KA + O-SIGN  ->  E-SIGN KA AA-SIGN
        assertThat(SinhalaShaping.toVisualOrder("කො")).isEqualTo("ෙකා");
        // plain text and Latin are untouched
        assertThat(SinhalaShaping.toVisualOrder("Kasun Stores 123")).isEqualTo("Kasun Stores 123");
        assertThat(SinhalaShaping.toVisualOrder(SINHALA_NAME)).isEqualTo(SINHALA_NAME);
    }

    @Test
    void latinOnlyInvoicesStillRenderWithoutEmbeddingTheSinhalaFont() throws Exception {
        SalesInvoiceItem[] item = new SalesInvoiceItem[1];
        SalesInvoice invoice = salesInvoice(item);
        invoice.getCustomer().setCustomerName("Green Valley Store");
        invoice.getCustomer().setArea("Colombo");

        byte[] pdf = pdfService.generateSalesInvoicePdf(invoice, List.of(item[0]), BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(textOf(pdf)).contains("Green Valley Store").contains("Colombo");
        assertThat(pdf.length).as("no Sinhala glyph used, so no big font subset").isLessThan(20_000);
    }
}
