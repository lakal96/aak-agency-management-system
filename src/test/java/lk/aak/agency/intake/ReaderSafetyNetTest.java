package lk.aak.agency.intake;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.repository.PurchaseInvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The safety net around the reader: if CBL changes how its documents look, an office worker must not be misled. Refusals are explained in plain words,
 * an invoice that reads but lacks the usual headings is imported MARKED, and a run of such documents raises an alarm (which clears by itself).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.intake.token=test-token-test-token-test-token-1234")
class ReaderSafetyNetTest {

    private static final String TOKEN = "test-token-test-token-test-token-1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private CblEmailIntakeRepository repository;
    @Autowired private PurchaseInvoiceRepository invoices;
    @Autowired private CblEmailIntakeLog log;

    // ---- plain words -------------------------------------------------------------------------------------------------

    @Test
    void everyKindOfRefusalIsExplainedInPlainWordsWithWhatToDo() {
        var pack = CblProblemExplainer.explain("INVOICE", List.of("CK990052: this line is counted in master cartons (MC), but its pack size (like X12EA) is neither in the product name"));
        assertThat(pack.category()).isEqualTo("DATA");
        assertThat(pack.headline()).contains("pack size is not known");
        assertThat(pack.whatToDo()).contains("Products").contains("by hand").contains("Nothing was imported");

        var unreadable = CblProblemExplainer.explain("CREDIT_NOTES", List.of("Page 1, credit note 1: a line could not be read: \"3,480.00\"", "Page 1, credit note 1: no amount was found."));
        assertThat(unreadable.category()).isEqualTo("FORMAT");
        assertThat(unreadable.headline()).isEqualTo("The system could not read this credit note file the usual way.");
        assertThat(unreadable.whatToDo()).startsWith("Nothing was imported.").contains("enter it by hand").contains("tell the developer");

        assertThat(CblProblemExplainer.explain("INVOICE", List.of("The product rows add up to 10.00 but the invoice says the Total Order Value is 12.00.")).category()).isEqualTo("FORMAT");
        assertThat(CblProblemExplainer.explain("INVOICE", List.of("CK1: 10 boxes of X do not match the invoiced quantity 5 DZ.")).category()).isEqualTo("DATA");
        assertThat(CblProblemExplainer.explain("OTHER", List.of("A PDF came from CBL with a subject this script does not pick up (it looks for \"E-Invoice\"...)")).category()).isEqualTo("NEW_DOCUMENT");
        assertThat(CblProblemExplainer.explain("OTHER", List.of("No PDF is attached to this e-mail from CBL, so there is nothing for the system to read.")).headline()).contains("no PDF");
        assertThat(CblProblemExplainer.explain("INVOICE", List.of("That is not a PDF file (or it is empty or too large).")).category()).isEqualTo("SYSTEM");
        // a layout complaint outweighs a content complaint when both are there
        assertThat(CblProblemExplainer.explain("INVOICE", List.of("CK990052: this line is counted in master cartons (MC), ...", "No product rows were found.")).category()).isEqualTo("FORMAT");
        // every explanation tells the worker what became of the data (nothing was imported / nothing to import / nothing was changed) and what to do
        for (var problems : List.of(
                List.of("CK990052: this line is counted in master cartons (MC), but its pack size ..."),
                List.of("CK1: 10 boxes of X do not match the invoiced quantity 5 DZ."),
                List.of("Page 1: a line could not be read: \"3,480.00\""),
                List.of("The product rows add up to 10.00 but the invoice says ..."),
                List.of("A PDF came from CBL with a subject this script does not pick up (...)"),
                List.of("No PDF is attached to this e-mail from CBL, so there is nothing for the system to read."),
                List.of("That is not a PDF file (or it is empty or too large)."),
                List.of("The system refused to store this invoice (it may have been imported at the same moment)."),
                List.of("something nobody has seen before"))) {
            var e = CblProblemExplainer.explain("INVOICE", problems);
            assertThat((e.headline() + " " + e.whatToDo()).toLowerCase()).as(problems.get(0)).containsAnyOf("nothing was imported", "nothing to import", "nothing was changed");
            assertThat(e.whatToDo()).as(problems.get(0)).isNotBlank();
        }
        // whatever the words, a refusal always says nothing was imported or what to do
        assertThat(CblProblemExplainer.explain("INVOICE", List.of("something nobody has seen before")).whatToDo()).contains("enter it by hand");
    }

    @Test
    void theApiRowCarriesTheHeadlineAndWhatToDoAndKeepsTheTechnicalLines() throws Exception {
        send(unusualInvoice("26NOV_SAFE_" + tag(), false, "no rows please"), "plain.pdf");
        mockMvc.perform(get("/api/v1/cbl-email-intake?status=attention").with(user("o").roles("OFFICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("FORMAT"))
                .andExpect(jsonPath("$[0].headline").value("The system could not read this invoice the usual way."))
                .andExpect(jsonPath("$[0].whatToDo").value(org.hamcrest.Matchers.containsString("Nothing was imported")))
                .andExpect(jsonPath("$[0].problems", org.hamcrest.Matchers.not(hasSize(0))));
    }

    // ---- unusual layout is marked, never trusted ---------------------------------------------------------------------

    @Test
    void anInvoiceThatReadsButLacksAUsualHeadingIsImportedMarkedForAPersonToCheck() throws Exception {
        String number = "26NOV_SAFE_" + tag();
        var result = send(unusualInvoice(number, true, null), "odd.pdf");
        assertThat(result).isEqualTo(201);

        PurchaseInvoice draft = invoices.findByDocumentNumber(number).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getNotes()).contains("LAYOUT CHECK").contains("Unit Price").contains("EVERY line");

        CblEmailIntake row = repository.findFirstByStatusOrderByIdDesc(CblEmailIntake.IMPORTED).orElseThrow();
        assertThat(row.getLayoutWarning()).contains("Unit Price");
        mockMvc.perform(get("/api/v1/cbl-email-intake/" + row.getId()).with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.layoutWarnings[0]").value(org.hamcrest.Matchers.containsString("Unit Price")));
        // and the bell says so, most urgently, linking to that very draft
        mockMvc.perform(get("/api/v1/notifications").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$[?(@.id=='purchase-draft-" + draft.getId() + "')].severity", hasItem("DANGER")))
                .andExpect(jsonPath("$[?(@.id=='purchase-draft-" + draft.getId() + "')].title", hasItem(org.hamcrest.Matchers.containsString("unusual layout"))));
    }

    @Test
    void anInvoiceWithTheUsualHeadingsHasNoWarning() throws Exception {
        String number = "26NOV_SAFE_" + tag();
        assertThat(send(unusualInvoice(number, false, null), "usual.pdf")).isEqualTo(201);
        assertThat(invoices.findByDocumentNumber(number).orElseThrow().getNotes()).doesNotContain("LAYOUT CHECK");
        assertThat(repository.findFirstByStatusOrderByIdDesc(CblEmailIntake.IMPORTED).orElseThrow().getLayoutWarning()).isNull();
    }

    // ---- the alarm ----------------------------------------------------------------------------------------------------

    @Test
    void aRunOfDocumentsThatLookDifferentRaisesTheAlarmAndItClearsWhenNormalOnesArrive() throws Exception {
        assertThat(log.formatChanged()).isFalse();
        send(unusualInvoice("26NOV_SAFE_" + tag(), true, null), "a.pdf");          // imported, unusual layout
        send(unusualInvoice("26NOV_SAFE_" + tag(), false, "no rows please"), "b.pdf");   // could not be read
        assertThat(log.formatChanged()).as("two is a coincidence").isFalse();
        send(unusualInvoice("26NOV_SAFE_" + tag(), false, "no rows please"), "c.pdf");   // could not be read
        assertThat(log.formatChanged()).as("three of the latest is CBL").isTrue();
        assertThat(log.summary().formatChanged()).isTrue();
        mockMvc.perform(get("/api/v1/cbl-email-intake/summary").with(user("o").roles("OFFICE"))).andExpect(jsonPath("$.formatChanged").value(true));
        mockMvc.perform(get("/api/v1/notifications").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$[?(@.id=='cbl-format-alarm')].link", hasItem("/cbl-emails?filter=attention")))
                .andExpect(jsonPath("$[0].severity").value("DANGER"));

        // normal documents come in: the window moves on and the alarm goes down by itself
        for (int i = 0; i < 4; i++) send(unusualInvoice("26NOV_SAFE_" + tag(), false, null), "ok" + i + ".pdf");
        assertThat(log.formatChanged()).isFalse();
        mockMvc.perform(get("/api/v1/notifications").with(user("o").roles("OFFICE"))).andExpect(jsonPath("$[?(@.id=='cbl-format-alarm')]", hasSize(0)));
    }

    @Test
    void aProblemWithTheContentsIsNotMistakenForAFormatChange() throws Exception {
        for (int i = 0; i < 4; i++) {
            // a master-carton line with no pack size anywhere: a content problem each time, whatever the layout
            send(cartonInvoice("26NOV_SAFE_" + tag()), "carton" + i + ".pdf");
        }
        assertThat(repository.countByStatusAndResolvedFalse(CblEmailIntake.NEEDS_ATTENTION)).isGreaterThanOrEqualTo(4);
        assertThat(log.formatChanged()).isFalse();
    }

    @Test
    void creditNotesWhoseTableHeadingsChangedAreRefusedNotTrusted() {
        var parser = new lk.aak.agency.credit.CreditNoteParser();
        String page = "CBL Foods International (Pvt) Ltd\nTe1:(94) 11 7878600, Doc.No.:26NOV_X_1\nDate   :15.11.2026\nCredit Note\nM/s: 10282\n"
                + "Remarks       Value Rs.\n   1,000.00 \nThis is a system generated document & no signature is required\n";
        var parsed = parser.parsePages(List.of(page));
        assertThat(parsed.notes()).isEmpty();
        assertThat(parsed.problems()).anyMatch(p -> p.contains("layout has changed"));
        assertThat(CblProblemExplainer.explain("CREDIT_NOTES", parsed.problems()).category()).isEqualTo("FORMAT");
    }

    @Test
    void theHeadingCheckToleratesWrappedColumnsButNotRenamedHeadings() {
        String usual = "INVOICE Tax Invoice Number: X Product Description of Goods or Services Net No of Sold Unit Unit Price Amount Including VAT Code/Reference Weigh Boxes Quantity Rs. Rs. "
                + "CK1 ROW Total Order Value 1.00 Total Value of Supply : 1.00 Total Amount including VAT : 1.00";
        assertThat(CblLayoutCheck.missingInvoiceHeadings(usual)).isEmpty();
        // a narrow column wraps "Amount Including VAT" over three lines and other columns' words land in between
        String wrapped = "INVOICE Tax Invoice Number: X Product Description of Goods or Services Net No of Sold Unit Unit Price Amount "
                + "Code/Reference Weigh Boxes Quantity Rs. Including VAT Rs. CK1 ROW Total Order Value 1.00 Total Value of Supply : 1.00 Total Amount including VAT : 1.00";
        assertThat(CblLayoutCheck.missingInvoiceHeadings(wrapped)).isEmpty();
        // renamed headings are caught
        assertThat(CblLayoutCheck.missingInvoiceHeadings(usual.replace("Unit Price", "Unit Rate"))).containsExactly("Unit Price");
        assertThat(CblLayoutCheck.missingInvoiceHeadings(usual.replace("Description of Goods or Services", "Item"))).containsExactly("Description of Goods or Services");
        assertThat(CblLayoutCheck.missingInvoiceHeadings(usual.replace("Total Value of Supply", "Supply total"))).containsExactly("Total Value of Supply");
        assertThat(CblLayoutCheck.missingInvoiceHeadings("")).hasSize(7);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /** A made-up invoice written as plain lines. `dropUnitPriceHeading` leaves out a heading CBL always prints; `breakIt` replaces the product rows with nonsense. */
    private static byte[] unusualInvoice(String number, boolean dropUnitPriceHeading, String breakIt) throws Exception {
        String headings = dropUnitPriceHeading
                ? "Product Description of Goods or Services Net No of Sold Unit Rate Amount Including VAT"
                : "Product Description of Goods or Services Net No of Sold Unit Unit Price Amount Including VAT";
        String row = breakIt == null ? "CHS90001 TEST CHOCOLA MILK 20GX24DZ - SACMI 20G 10.00 240.00 DZ 10.00 2,400.00" : breakIt;
        String text = "INVOICE\nDate of Invoice: 11/06/2026 Tax Invoice Number: " + number + "\nP.O Number: C99999999RN-TAX\nTerritory: Makola - RTM\n"
                + "Date of Supply: 11/06/2026 Place of Supply: Ranala\n" + headings + "\nCode/Reference Weigh Boxes Quantity Rs. Rs.\n" + row + "\n"
                + "Total Order Value 2,400.00\nLess Discount 0.00\nTotal Value of Supply : 2,400.00\nTotal Amount including VAT : 2,400.00\nMode of Payments : Direct Bank Transfer\n";
        return linesPdf(text);
    }

    private static byte[] cartonInvoice(String number) throws Exception {
        String text = "INVOICE\nDate of Invoice: 11/06/2026 Tax Invoice Number: " + number + "\nP.O Number: C99999999RN-TAX\nTerritory: Makola - RTM\n"
                + "Date of Supply: 11/06/2026 Place of Supply: Ranala\nProduct Description of Goods or Services Net No of Sold Unit Unit Price Amount Including VAT\n"
                + "Code/Reference Weigh Boxes Quantity Rs. Rs.\nCK990052 TEST PARTY PACK 150G 6.00 6.00 MC 100.00 600.00\n"
                + "Total Order Value 600.00\nLess Discount 0.00\nTotal Value of Supply : 600.00\nTotal Amount including VAT : 600.00\nMode of Payments : Direct Bank Transfer\n";
        return linesPdf(text);
    }

    private static byte[] linesPdf(String text) throws Exception {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font f = FontFactory.getFont(FontFactory.HELVETICA, 9);
        for (String line : text.split("\n")) doc.add(new Paragraph(line, f));
        doc.close();
        return out.toByteArray();
    }

    private int send(byte[] pdf, String name) throws Exception {
        return mockMvc.perform(multipart("/api/v1/purchase-invoices/email-intake")
                        .file(new MockMultipartFile("file", name, "application/pdf", pdf)).param("subject", "E-Invoice - " + name)
                        .header("X-Intake-Token", TOKEN))
                .andReturn().getResponse().getStatus();
    }
}
