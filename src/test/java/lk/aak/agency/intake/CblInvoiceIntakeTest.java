package lk.aak.agency.intake;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import lk.aak.agency.service.InventoryService;
import lk.aak.agency.service.PurchaseInvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A made-up CBL invoice, laid out like the real PDF (header block, product table with a wrapped description, totals),
 * goes in as a DRAFT with exact amounts, stock only moves when it is completed, and anything odd is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.intake.token=test-token-test-token-test-token-1234")
class CblInvoiceIntakeTest {

    private static final String TOKEN = "test-token-test-token-test-token-1234";

    @Autowired private CblInvoiceIntakeService intake;
    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private PurchaseInvoiceRepository invoiceRepository;
    @Autowired private PurchaseInvoiceItemRepository itemRepository;
    @Autowired private PurchaseInvoiceService purchaseInvoiceService;
    @Autowired private InventoryService inventoryService;

    // ------------------------------------------------------------ a made-up invoice PDF

    private static final String[][] ROWS = {
            {"CHS90001", "TEST CHOCOLA MILK 20GX24DZ - SACMI", "20G", "10.00", "240.00", "DZ", "503.82", "120,916.80"},
            {"CHS90002", "TEST CHO. FINGERS 18GX32DZ", "18G", "1.00", "32.00", "DZ", "403.06", "12,897.79"},
            {"CK990003", "TEST LAYER CAKE CHOCOLATE 300GX12EA", "300G", "10.00", "10.00", "MC", "5,709.96", "57,099.60"},
            {"CHS90004", "TEST RITZ. MOMENTS ASSORTMENT PACK\n240GX12EA", "240G", "12.00", "12.00", "DZ", "6,549.66", "78,595.92"},
    };

    private static byte[] invoicePdf(String number, String[][] rows, String totalOrder, String discount, String supply) throws Exception {
        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 9);
        doc.add(new Paragraph("INVOICE", f));
        doc.add(new Paragraph("Date of Invoice: 11/06/2026     Tax Invoice Number: " + number, f));
        doc.add(new Paragraph("P.O Number: C99999999RN-TAX", f));
        doc.add(new Paragraph("Delivery Note: 8001000001 8001000002", f));
        doc.add(new Paragraph("Territory: Makola - RTM", f));
        doc.add(new Paragraph("Date of Supply: 11/06/2026     Place of Supply: Ranala", f));
        doc.add(new Paragraph("Additional Information if any:", f));
        PdfPTable table = new PdfPTable(new float[]{14, 38, 8, 8, 10, 6, 11, 14});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Product Code/Reference", "Description of Goods or Services", "Net Weigh", "No of Boxes", "Sold Quantity", "Unit", "Unit Price Rs.", "Amount Including VAT Rs."}) {
            table.addCell(new PdfPCell(new Paragraph(h, f)));
        }
        for (String[] row : rows) {
            for (String cell : row) {
                PdfPCell c = new PdfPCell(new Paragraph(cell, f));
                c.setVerticalAlignment(com.lowagie.text.Element.ALIGN_TOP);
                table.addCell(c);
            }
        }
        doc.add(table);
        doc.add(new Paragraph("Total Order Value " + totalOrder, f));
        doc.add(new Paragraph("Less Discount " + discount, f));
        doc.add(new Paragraph("Total Value of Supply : " + supply, f));
        doc.add(new Paragraph("Total Amount including VAT : " + supply, f));
        doc.add(new Paragraph("Mode of Payments : Direct Bank Transfer", f));
        doc.close();
        return out.toByteArray();
    }

    private static byte[] goodPdf(String number) throws Exception {
        return invoicePdf(number, ROWS, "269,510.11", "1,000.00", "268,510.11");
    }

    private Product product(String code, String name) {
        Product p = new Product();
        p.setCblProductCode(code);
        p.setProductName(name);
        p.setUnit("PCS");
        p.setMrp(new BigDecimal("100"));
        p.setStandardSellingPrice(new BigDecimal("90"));
        p.setReorderLevel(BigDecimal.ZERO);
        p.setStatus("ACTIVE");
        return productRepository.save(p);
    }

    private BigDecimal stockOf(Product product) {
        return inventoryService.getStockByProduct().getOrDefault(product.getId(), BigDecimal.ZERO);
    }

    // ------------------------------------------------------------ tests

    @Test
    void anEmailedInvoiceBecomesADraftWithCblsExactAmountsAndStockMovesOnlyWhenCompleted() throws Exception {
        Product milk = product("CHS90001", "TEST CHOCOLA MILK 20GX24DZ - SACMI");
        Product tempFingers = product("TEMP-990", "TEST CHO. FINGERS 18GX32DZ");   // still on a temporary code
        product("CHS90004", "TEST RITZ. MOMENTS ASSORTMENT PACK 240GX12EA");
        // CK990003 does not exist yet: it is created from the invoice

        CblInvoiceIntakeService.Result result = intake.importInvoice(goodPdf("26NOV_TEST_0000000001"), "invoice.pdf");

        assertThat(result.problems()).isEmpty();
        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.CREATED);
        assertThat(result.lines()).isEqualTo(4);
        assertThat(result.total()).isEqualByComparingTo("268510.11");

        PurchaseInvoice invoice = invoiceRepository.findById(result.invoiceId()).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo("DRAFT");
        assertThat(invoice.getSource()).isEqualTo("EMAIL");
        assertThat(invoice.getTaxInvoiceNumber()).isEqualTo("26NOV_TEST_0000000001");
        assertThat(invoice.getPoNumber()).isEqualTo("C99999999RN-TAX");
        assertThat(invoice.getInvoiceDate()).hasToString("2026-11-06");
        assertThat(invoice.getTerritory()).isEqualTo("Makola - RTM");
        assertThat(invoice.getPaymentMethod()).isEqualTo("BANK_TRANSFER");
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("269510.11");
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("1000.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("268510.11");
        assertThat(invoice.hasInvoiceFile()).as("the PDF is attached for the person who verifies it").isTrue();
        assertThat(invoice.getNotes()).contains("MADE AUTOMATICALLY").contains("TEMP-990 -> CHS90002").contains("CK990003");

        List<PurchaseInvoiceItem> items = itemRepository.findByPurchaseInvoiceIdOrderByIdAsc(invoice.getId());
        assertThat(items).extracting(i -> i.getQuantity().intValue()).containsExactly(2880, 384, 120, 144);
        assertThat(items).extracting(PurchaseInvoiceItem::getAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("120916.80"), new BigDecimal("12897.79"), new BigDecimal("57099.60"), new BigDecimal("78595.92"));
        assertThat(items.get(0).getInvoicedUnit()).isEqualTo("DZ");
        assertThat(items.get(0).getInvoicedQuantity()).isEqualByComparingTo("240");
        assertThat(items.get(0).getInvoicedUnitPrice()).isEqualByComparingTo("503.82");

        // the temporary code was replaced; the new product is inactive with price 0 until someone prices it
        assertThat(productRepository.findByCblProductCode("CHS90002")).isPresent();
        assertThat(productRepository.findByCblProductCode("TEMP-990")).isEmpty();
        Product created = productRepository.findByCblProductCode("CK990003").orElseThrow();
        assertThat(created.getStatus()).isEqualTo("INACTIVE");
        assertThat(created.getStandardSellingPrice()).isEqualByComparingTo("0");

        // a draft changes nothing ...
        assertThat(stockOf(milk)).isEqualByComparingTo("0");

        // ... completing it (the person has compared the PDF) puts PIECES into stock and owes CBL the printed total
        purchaseInvoiceService.completeInvoice(invoice.getId(), true);
        assertThat(stockOf(milk)).isEqualByComparingTo("2880");
        assertThat(stockOf(tempFingers)).isEqualByComparingTo("384");
        assertThat(stockOf(created)).isEqualByComparingTo("120");
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getTotalAmount()).isEqualByComparingTo("268510.11");
    }

    @Test
    void theSameInvoiceTwiceIsRecognisedAndNothingIsAddedAgain() throws Exception {
        byte[] pdf = goodPdf("26NOV_TEST_0000000002");
        CblInvoiceIntakeService.Result first = intake.importInvoice(pdf, "a.pdf");
        long invoicesBefore = invoiceRepository.count();

        CblInvoiceIntakeService.Result second = intake.importInvoice(pdf, "a (1).pdf");

        assertThat(first.status()).isEqualTo(CblInvoiceIntakeService.Status.CREATED);
        assertThat(second.status()).isEqualTo(CblInvoiceIntakeService.Status.DUPLICATE);
        assertThat(second.invoiceId()).isEqualTo(first.invoiceId());
        assertThat(invoiceRepository.count()).isEqualTo(invoicesBefore);
    }

    @Test
    void anInvoiceWhoseRowsDoNotAddUpToItsTotalIsRefusedAndNothingIsCreated() throws Exception {
        long invoices = invoiceRepository.count();
        long products = productRepository.count();

        CblInvoiceIntakeService.Result result = intake.importInvoice(
                invoicePdf("26NOV_TEST_0000000003", ROWS, "270,000.00", "1,000.00", "269,000.00"), "bad.pdf");

        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.REJECTED);
        assertThat(String.join(" ", result.problems())).contains("add up to 269510.11");
        assertThat(invoiceRepository.count()).isEqualTo(invoices);
        assertThat(productRepository.count()).isEqualTo(products);
    }

    @Test
    void quantitiesThatDisagreeWithThePackSizeInTheNameAreRefused() throws Exception {
        String[][] rows = {{"CHS90001", "TEST CHOCOLA MILK 20GX24DZ - SACMI", "20G", "10.00", "100.00", "DZ", "503.82", "50,382.00"}};

        CblInvoiceIntakeService.Result result = intake.importInvoice(
                invoicePdf("26NOV_TEST_0000000004", rows, "50,382.00", "0.00", "50,382.00"), "odd.pdf");

        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.REJECTED);
        assertThat(String.join(" ", result.problems())).contains("do not match the invoiced quantity");
    }

    @Test
    void somethingThatIsNotAnInvoiceIsRefused() throws Exception {
        assertThat(intake.importInvoice("hello".getBytes(), "x.pdf").status()).isEqualTo(CblInvoiceIntakeService.Status.REJECTED);
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("A menu for the staff party, lots of words so the file is not tiny: " + "pizza ".repeat(40)));
        doc.close();
        CblInvoiceIntakeService.Result result = intake.importInvoice(out.toByteArray(), "menu.pdf");
        assertThat(result.status()).isEqualTo(CblInvoiceIntakeService.Status.REJECTED);
        assertThat(String.join(" ", result.problems())).contains("does not look like a CBL product invoice");
    }

    @Test
    void theAddressNeedsTheSecretToken() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", goodPdf("26NOV_TEST_0000000005"));

        mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake").file(file)).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake").file(file).header("X-Intake-Token", "wrong-token-wrong-token-wrong-12"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake").file(file).header("X-Intake-Token", TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.lines").value(4));
        // sending the same file again is harmless
        mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake").file(file).header("X-Intake-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }
}
