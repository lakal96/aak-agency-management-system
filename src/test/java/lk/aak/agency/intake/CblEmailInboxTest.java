package lk.aak.agency.intake;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.model.AuditLog;
import lk.aak.agency.model.Product;
import lk.aak.agency.repository.AuditLogRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CBL e-mail box: every e-mail the Gmail script posts leaves a record inside the system, so a person can see what was imported
 * and, above all, what NEEDS ATTENTION (a refused PDF is kept and shown once however often it is retried, an e-mail the script could
 * not use is listed, and a script that has stopped checking shows up).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.intake.token=test-token-test-token-test-token-1234")
class CblEmailInboxTest {

    private static final String TOKEN = "test-token-test-token-test-token-1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private CblEmailIntakeRepository repository;
    @Autowired private IntegrationHeartbeatRepository heartbeats;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    // ------------------------------------------------------------ made-up invoices laid out like the real PDF

    private static byte[] invoicePdf(String number, String[][] rows, String total) throws Exception {
        Document doc = new Document(PageSize.A4, 30, 30, 30, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 9);
        doc.add(new Paragraph("INVOICE", f));
        doc.add(new Paragraph("Date of Invoice: 11/06/2026     Tax Invoice Number: " + number, f));
        doc.add(new Paragraph("P.O Number: C99999999RN-TAX", f));
        doc.add(new Paragraph("Territory: Makola - RTM", f));
        doc.add(new Paragraph("Date of Supply: 11/06/2026     Place of Supply: Ranala", f));
        PdfPTable table = new PdfPTable(new float[]{14, 38, 8, 8, 10, 6, 11, 14});
        table.setWidthPercentage(100);
        for (String h : new String[]{"Product Code/Reference", "Description of Goods or Services", "Net Weigh", "No of Boxes", "Sold Quantity", "Unit", "Unit Price Rs.", "Amount Including VAT Rs."}) {
            table.addCell(new PdfPCell(new Paragraph(h, f)));
        }
        for (String[] row : rows) {
            for (String cell : row) table.addCell(new PdfPCell(new Paragraph(cell, f)));
        }
        doc.add(table);
        doc.add(new Paragraph("Total Order Value " + total, f));
        doc.add(new Paragraph("Less Discount 0.00", f));
        doc.add(new Paragraph("Total Value of Supply : " + total, f));
        doc.add(new Paragraph("Total Amount including VAT : " + total, f));
        doc.add(new Paragraph("Mode of Payments : Direct Bank Transfer", f));
        doc.close();
        return out.toByteArray();
    }

    /** a master-carton line with no pack size anywhere: refused until the product list knows its pack */
    private static byte[] refusedPdf(String number) throws Exception {
        return invoicePdf(number, new String[][]{{"CK990052", "TEST PARTY PACK", "150G", "6.00", "6.00", "MC", "100.00", "600.00"}}, "600.00");
    }

    private static byte[] goodPdf(String number) throws Exception {
        return invoicePdf(number, new String[][]{{"CHS90001", "TEST CHOCOLA MILK 20GX24DZ - SACMI", "20G", "10.00", "240.00", "DZ", "10.00", "2,400.00"}}, "2,400.00");
    }

    private byte[] sendPdf(byte[] pdf, String name, String subject) throws Exception {
        return mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake")
                        .file(new MockMultipartFile("file", name, "application/pdf", pdf))
                        .param("subject", subject).header("X-Intake-Token", TOKEN))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private List<CblEmailIntake> attention() {
        return repository.findByStatusAndResolvedFalseOrderByIdDesc(CblEmailIntake.NEEDS_ATTENTION);
    }

    // ------------------------------------------------------------ refused PDFs

    @Test
    void aRefusedPdfIsKeptAndShownOnceHoweverOftenTheScriptRetriesIt() throws Exception {
        byte[] pdf = refusedPdf("26NOV_BOX_0000000001");
        sendPdf(pdf, "inv.pdf", "E-Invoice - No: 26NOV_BOX_0000000001");
        sendPdf(pdf, "inv.pdf", "E-Invoice - No: 26NOV_BOX_0000000001");
        sendPdf(pdf, "inv.pdf", "E-Invoice - No: 26NOV_BOX_0000000001");

        assertThat(attention()).hasSize(1);
        CblEmailIntake row = attention().get(0);
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getKind()).isEqualTo("INVOICE");
        assertThat(row.getEmailSubject()).isEqualTo("E-Invoice - No: 26NOV_BOX_0000000001");
        assertThat(row.getProblems()).contains("master cartons").contains("enter this invoice by hand");
        assertThat(row.getStoredFileName()).as("the refused PDF is kept in the system").isNotNull();

        // the owner and the office can read the list, see the reason and open the PDF itself
        mockMvc.perform(get("/api/v1/cbl-email-intake").with(user("office1").roles("OFFICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("NEEDS_ATTENTION"))
                .andExpect(jsonPath("$[0].hasFile").value(true))
                .andExpect(jsonPath("$[0].problems[0]").value(containsString("master cartons")));
        MvcResult file = mockMvc.perform(get("/api/v1/cbl-email-intake/" + row.getId() + "/file").with(user("owner").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn();
        assertThat(new String(file.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");

        // and it reaches the bell: one activity line, not one per retry
        assertThat(auditActions()).filteredOn("CBL_EMAIL_NEEDS_ATTENTION"::equals).hasSize(1);
    }

    @Test
    void whenTheSamePdfCanBeReadLaterTheEntryClosesItselfAndTheImportIsRecorded() throws Exception {
        byte[] pdf = refusedPdf("26NOV_BOX_0000000002");
        sendPdf(pdf, "later.pdf", "E-Invoice - No: 26NOV_BOX_0000000002");
        assertThat(attention()).hasSize(1);
        CblEmailIntake refused = attention().get(0);

        // the product list now knows the pack, so the very same PDF can be read
        Product known = new Product();
        known.setCblProductCode("CK990052");
        known.setProductName("TEST PARTY PACK 150GX12EA");
        known.setUnit("PCS");
        productRepository.save(known);
        sendPdf(pdf, "later.pdf", "E-Invoice - No: 26NOV_BOX_0000000002");

        assertThat(attention()).isEmpty();
        CblEmailIntake closed = repository.findById(refused.getId()).orElseThrow();
        assertThat(closed.isResolved()).isTrue();
        assertThat(closed.getResolvedBy()).isEqualTo("system");
        assertThat(closed.getResolutionNote()).contains("Imported later");
        CblEmailIntake imported = repository.findFirstByStatusOrderByIdDesc(CblEmailIntake.IMPORTED).orElseThrow();
        assertThat(imported.getPurchaseInvoiceId()).isNotNull();
        assertThat(imported.getSummary()).contains("1 lines");
        assertThat(auditActions()).contains("CBL_EMAIL_IMPORTED");
    }

    // ------------------------------------------------------------ good, repeated and empty e-mails

    @Test
    void aGoodInvoiceIsRecordedAsImportedARepeatAddsNothingAndAnEmptyOneIsQuiet() throws Exception {
        byte[] good = goodPdf("26NOV_BOX_0000000003");
        sendPdf(good, "good.pdf", "E-Invoice - No: 26NOV_BOX_0000000003");
        sendPdf(good, "good.pdf", "E-Invoice - No: 26NOV_BOX_0000000003");   // the script's recheck: already imported

        assertThat(repository.findAll()).extracting(CblEmailIntake::getStatus).containsExactly(CblEmailIntake.IMPORTED);
        mockMvc.perform(get("/api/v1/cbl-email-intake/summary").with(user("office1").roles("OFFICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsAttention").value(0))
                .andExpect(jsonPath("$.lastImportedAt").isNotEmpty());

        byte[] empty = invoicePdf("26NOV_BOX_0000000004", new String[0][], "0.00");
        sendPdf(empty, "empty.pdf", "E-Invoice - No: 26NOV_BOX_0000000004");
        sendPdf(empty, "empty.pdf", "E-Invoice - No: 26NOV_BOX_0000000004");
        assertThat(repository.findAll()).extracting(CblEmailIntake::getStatus).containsExactlyInAnyOrder(CblEmailIntake.IMPORTED, CblEmailIntake.EMPTY);
        assertThat(attention()).as("an empty invoice is not something to worry about").isEmpty();
    }

    // ------------------------------------------------------------ e-mails the script could not use

    @Test
    void anEmailTheScriptCouldNotUseIsListedOnceAndAPersonCanMarkItHandled() throws Exception {
        String report = "{\"messageKey\":\"18c2f0\",\"subject\":\"Statement of account\",\"reason\":\"No PDF is attached to this e-mail from CBL.\"}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/purchase-invoices/email-intake/unread").header("X-Intake-Token", TOKEN)
                    .contentType(APPLICATION_JSON).content(report)).andExpect(status().isNoContent());
        }
        assertThat(attention()).hasSize(1);
        CblEmailIntake row = attention().get(0);
        assertThat(row.getKind()).isEqualTo("OTHER");
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getEmailSubject()).isEqualTo("Statement of account");

        mockMvc.perform(post("/api/v1/cbl-email-intake/" + row.getId() + "/resolve").with(user("office1").roles("OFFICE"))
                        .contentType(APPLICATION_JSON).content("{\"note\":\"Not an invoice, nothing to do.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true))
                .andExpect(jsonPath("$.resolvedBy").value("office1"));
        assertThat(attention()).isEmpty();
        // a second person marking it handled does not overwrite who did it first
        mockMvc.perform(post("/api/v1/cbl-email-intake/" + row.getId() + "/resolve").with(user("owner").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolvedBy").value("office1"));
        assertThat(auditActions()).contains("CBL_EMAIL_HANDLED");
    }

    @Test
    void aRefusedPdfThatAPersonMarkedHandledDoesNotComeBackWhenTheScriptRetriesIt() throws Exception {
        byte[] pdf = refusedPdf("26NOV_BOX_0000000005");
        sendPdf(pdf, "hand.pdf", "E-Invoice - No: 26NOV_BOX_0000000005");
        Long id = attention().get(0).getId();
        mockMvc.perform(post("/api/v1/cbl-email-intake/" + id + "/resolve").with(user("office1").roles("OFFICE"))
                .contentType(APPLICATION_JSON).content("{\"note\":\"Entered by hand\"}")).andExpect(status().isOk());

        sendPdf(pdf, "hand.pdf", "E-Invoice - No: 26NOV_BOX_0000000005");   // retried by the script within its recheck window

        assertThat(attention()).isEmpty();
        assertThat(repository.findById(id).orElseThrow().getAttempts()).isEqualTo(2);
    }

    // ------------------------------------------------------------ the script reporting in

    @Test
    void aScriptThatStoppedCheckingShowsUpAndOneThatReportsInIsFine() throws Exception {
        mockMvc.perform(get("/api/v1/cbl-email-intake/summary").with(user("office1").roles("OFFICE")))
                .andExpect(jsonPath("$.checkerStatus").value("NEVER"));

        mockMvc.perform(post("/api/v1/purchase-invoices/email-intake/heartbeat").header("X-Intake-Token", TOKEN)
                .contentType(APPLICATION_JSON).content("{\"detail\":\"checked 3 e-mails\"}")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/cbl-email-intake/summary").with(user("office1").roles("OFFICE")))
                .andExpect(jsonPath("$.checkerStatus").value("OK")).andExpect(jsonPath("$.lastCheckedAt").isNotEmpty());

        IntegrationHeartbeat beat = heartbeats.findById(CblEmailIntakeLog.CHECKER).orElseThrow();
        beat.setLastSeenAt(BusinessTime.now().minus(Duration.ofHours(4)));
        heartbeats.save(beat);
        mockMvc.perform(get("/api/v1/cbl-email-intake/summary").with(user("office1").roles("OFFICE")))
                .andExpect(jsonPath("$.checkerStatus").value("STALE"));
    }

    // ------------------------------------------------------------ who may do what

    @Test
    void onlyOfficeAndOwnerSeeTheBoxAndTheScriptNeedsItsToken() throws Exception {
        for (String path : List.of("/api/v1/cbl-email-intake", "/api/v1/cbl-email-intake/summary")) {
            mockMvc.perform(get(path).with(user("rep1").roles("SALES_REP"))).andExpect(status().isForbidden());
            mockMvc.perform(get(path).with(user("driver1").roles("DRIVER"))).andExpect(status().isForbidden());
            mockMvc.perform(get(path).with(user("office1").roles("OFFICE"))).andExpect(status().isOk());
            mockMvc.perform(get(path).with(user("owner").roles("ADMIN"))).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/cbl-email-intake/1/resolve").with(user("rep1").roles("SALES_REP"))).andExpect(status().isForbidden());

        // the script's two reports are open to the internet only as far as the token goes
        for (String path : List.of("/api/v1/purchase-invoices/email-intake/unread", "/api/v1/purchase-invoices/email-intake/heartbeat")) {
            mockMvc.perform(post(path).contentType(APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).header("X-Intake-Token", "not-the-token-not-the-token-1234").contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(repository.count()).isZero();
    }

    private List<String> auditActions() {
        return auditLogRepository.findAll().stream().map(AuditLog::getAction).toList();
    }
}
