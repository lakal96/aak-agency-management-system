package lk.aak.agency.intake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CBL e-mail section (every PDF CBL sent is kept, listed, searchable and downloadable) and the bell (every item that needs a person,
 * each with the page where it is dealt with).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.intake.token=test-token-test-token-test-token-1234")
class CblEmailsPageAndNotificationsTest {

    private static final String TOKEN = "test-token-test-token-test-token-1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private CblEmailIntakeRepository repository;

    private void send(byte[] pdf, String name, String subject, String sender, String sentAt) throws Exception {
        var request = multipart("/api/v1/purchase-invoices/email-intake")
                .file(new MockMultipartFile("file", name, "application/pdf", pdf))
                .param("subject", subject).header("X-Intake-Token", TOKEN);
        if (sender != null) request.param("sender", sender);
        if (sentAt != null) request.param("sentAt", sentAt);
        mockMvc.perform(request).andReturn();
    }

    // ------------------------------------------------------------------------------------------ the e-mail section

    @Test
    void everyPdfIsKeptAndCanBeOpenedOrDownloadedWhateverCameOfIt() throws Exception {
        send(CblEmailInboxTest.goodPdf("26NOV_PAGE_0000000001"), "good.pdf", "E-Invoice - No: 26NOV_PAGE_0000000001",
                "noreply_sap@cbllk.com", "2026-11-06T09:15:00+05:30");
        send(CblEmailInboxTest.refusedPdf("26NOV_PAGE_0000000002"), "refused.pdf", "E-Invoice - No: 26NOV_PAGE_0000000002", null, null);

        List<CblEmailIntake> rows = repository.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, 10));
        CblEmailIntake imported = rows.stream().filter(r -> r.getStatus().equals(CblEmailIntake.IMPORTED)).findFirst().orElseThrow();
        assertThat(imported.getStoredFileName()).as("an imported PDF is kept too").isNotNull();
        assertThat(imported.getFileSize()).isPositive();
        assertThat(imported.getEmailSender()).isEqualTo("noreply_sap@cbllk.com");
        assertThat(imported.getEmailSentAt()).hasToString("2026-11-06T09:15");

        // opened in the browser, or saved as a file
        MvcResult inline = mockMvc.perform(get("/api/v1/cbl-email-intake/" + imported.getId() + "/file").with(user("o").roles("OFFICE")))
                .andExpect(status().isOk()).andReturn();
        assertThat(inline.getResponse().getHeader("Content-Disposition")).startsWith("inline");
        assertThat(new String(inline.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
        MvcResult saved = mockMvc.perform(get("/api/v1/cbl-email-intake/" + imported.getId() + "/file?download=true").with(user("o").roles("OFFICE")))
                .andExpect(status().isOk()).andReturn();
        assertThat(saved.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains("good.pdf");
    }

    @Test
    void theListPagesFiltersAndSearchesAndTheChipsHaveCounts() throws Exception {
        send(CblEmailInboxTest.goodPdf("26NOV_PAGE_0000000011"), "good.pdf", "E-Invoice - No: 26NOV_PAGE_0000000011", null, null);
        send(CblEmailInboxTest.refusedPdf("26NOV_PAGE_0000000012"), "refused.pdf", "E-Invoice - No: 26NOV_PAGE_0000000012", null, null);

        mockMvc.perform(get("/api/v1/cbl-email-intake/counts").with(user("o").roles("OFFICE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.all").value(2)).andExpect(jsonPath("$.attention").value(1))
                .andExpect(jsonPath("$.imported").value(1)).andExpect(jsonPath("$.handled").value(0));

        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=attention").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fileName").value("refused.pdf"));
        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=imported").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fileName").value("good.pdf"));
        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=all&q=0000000012").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fileName").value("refused.pdf"));
        // document numbers contain underscores: they must match as typed
        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=all&q=26NOV_PAGE_0000000011").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].fileName").value("good.pdf"));
        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=all&q=no-such-thing").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/cbl-email-intake/page?filter=all&size=1&page=1").with(user("o").roles("OFFICE")))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.totalPages").value(2));

        Long id = repository.findAllByOrderByIdDesc(org.springframework.data.domain.PageRequest.of(0, 5)).get(0).getId();
        mockMvc.perform(get("/api/v1/cbl-email-intake/" + id).with(user("o").roles("OFFICE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.hasFile").value(true));
    }

    @Test
    void aSalesRepCannotReachTheCblEmails() throws Exception {
        mockMvc.perform(get("/api/v1/cbl-email-intake/page").with(user("rep").roles("SALES_REP"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/cbl-email-intake/counts").with(user("rep").roles("SALES_REP"))).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------ the bell

    @Test
    void theBellListsWhatNeedsAPersonEachWithItsPageAndItemsGoAwayWhenDone() throws Exception {
        send(CblEmailInboxTest.goodPdf("26NOV_BELL_0000000001"), "good.pdf", "E-Invoice - No: 26NOV_BELL_0000000001", null, null);
        send(CblEmailInboxTest.refusedPdf("26NOV_BELL_0000000002"), "refused.pdf", "E-Invoice - No: 26NOV_BELL_0000000002", null, null);
        CblEmailIntake refused = repository.findByStatusAndResolvedFalseOrderByIdDesc(CblEmailIntake.NEEDS_ATTENTION).get(0);
        CblEmailIntake imported = repository.findFirstByStatusOrderByIdDesc(CblEmailIntake.IMPORTED).orElseThrow();

        var result = mockMvc.perform(get("/api/v1/notifications").with(user("office1").roles("OFFICE")))
                .andExpect(status().isOk())
                // the unreadable e-mail is the most urgent thing: first, red, and it opens that very e-mail
                .andExpect(jsonPath("$[0].severity").value("DANGER"))
                .andExpect(jsonPath("$[0].link").value("/cbl-emails/" + refused.getId()))
                // the imported invoice is a draft waiting for the goods check, and opens that draft
                .andExpect(jsonPath("$[?(@.category=='Stock' && @.title=~/CBL invoice arrived.*/)].link",
                        hasItem("/purchase-invoices/" + imported.getPurchaseInvoiceId())))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"id\":\"cbl-email-" + refused.getId() + "\"");

        // every item has somewhere to go
        mockMvc.perform(get("/api/v1/notifications").with(user("office1").roles("OFFICE")))
                .andExpect(jsonPath("$[?(@.link == null || @.link == '')]", hasSize(0)));

        // once a person deals with it, it leaves the bell
        mockMvc.perform(post("/api/v1/cbl-email-intake/" + refused.getId() + "/resolve").with(user("office1").roles("OFFICE"))
                        .contentType(APPLICATION_JSON).content("{\"note\":\"typed in by hand\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/notifications").with(user("office1").roles("OFFICE")))
                .andExpect(jsonPath("$[?(@.id=='cbl-email-" + refused.getId() + "')]", hasSize(0)));
    }

    @Test
    void aSalesRepSeesNoOfficeItemsAndADriverIsRefused() throws Exception {
        send(CblEmailInboxTest.refusedPdf("26NOV_BELL_0000000003"), "refused.pdf", "E-Invoice - No: 26NOV_BELL_0000000003", null, null);
        mockMvc.perform(get("/api/v1/notifications").with(user("rep").roles("SALES_REP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.category=='CBL e-mail')]", hasSize(0)))
                .andExpect(jsonPath("$[?(@.category=='Stock')]", hasSize(0)))
                .andExpect(jsonPath("$[?(@.category=='Money' && @.link=='/cheques')]", hasSize(0)));
        // a driver's login has no access to the system's data at all
        mockMvc.perform(get("/api/v1/notifications").with(user("driver").roles("DRIVER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }
}
