package lk.aak.agency.control;

import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.JwtService;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Control portal and dashboard through the API: who may do what, and the whole daily flow with made-up CBL reports. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ControlApiTest {

    private static final String LORRY = "MAKOLARTM-A";
    private static final String VAN = "MAKOLARTM-AV4";

    @Autowired private MockMvc mockMvc;
    @Autowired private SystemUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private static LocalDate aWorkingDay() {
        LocalDate d = BusinessTime.today().minusDays(2);
        while (d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    private String token(String role) {
        SystemUser user = users.save(new SystemUser("ct_" + UUID.randomUUID().toString().substring(0, 8), passwordEncoder.encode("password1"), "Test User", role, true));
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String cbl(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")); }

    private static final String PAPER = """
            {"repName":"S. Silva","driverName":"K. Perera","cashCountedDriver":150.00,
             "bills":[{"billNo":"5001","billValue":100.00,"cashDriver":100.00},
                      {"billNo":"5002","billValue":300.00,"cashDriver":50.00,"creditAmount":250.00}],
             "stock":[]}""";

    private MockMultipartFile report(byte[] bytes, String name) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }

    private void upload(String token, LocalDate date, String vehicle, String kind, MockMultipartFile file, int expectedStatus) throws Exception {
        mockMvc.perform(multipart("/api/v1/control/days/" + date + "/lorries/" + vehicle + "/uploads").file(file).param("kind", kind).header("Authorization", bearer(token)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------------------------------------------------------------- who may do what

    @Test
    void theOfficeUsesThePortalButOnlyTheOwnerHasTheDashboard() throws Exception {
        LocalDate date = aWorkingDay();
        mockMvc.perform(get("/api/v1/control/vehicles").header("Authorization", bearer(token("OFFICE")))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/control/vehicles").header("Authorization", bearer(token("ADMIN")))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/control/vehicles").header("Authorization", bearer(token("SALES_REP")))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/control/vehicles").header("Authorization", bearer(token("DRIVER")))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/control/vehicles")).andExpect(status().isUnauthorized());

        String board = "/api/v1/control/dashboard/board?from=" + date + "&to=" + date;
        mockMvc.perform(get(board).header("Authorization", bearer(token("ADMIN")))).andExpect(status().isOk());
        mockMvc.perform(get(board).header("Authorization", bearer(token("OFFICE")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/control/dashboard/days/" + date + "/sign").header("Authorization", bearer(token("OFFICE")))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/control/dashboard/days/" + date + "/lorries/" + LORRY + "/acknowledge").header("Authorization", bearer(token("OFFICE")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"checkCode\":\"STOCK\",\"note\":\"x\"}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- the daily flow

    @Test
    void paperAndCblAgreeThenOneBillIsTypedWrongInCbl() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE");
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(PAPER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPaper").value(true))
                .andExpect(jsonPath("$.bills.length()").value(2))
                .andExpect(jsonPath("$.checks[?(@.code=='BILL_LINES')].status").value("PASS"))
                .andExpect(jsonPath("$.checks[?(@.code=='BILLS_VS_CBL')].status").value("WAITING"));

        // the CBL report, as the Excel button produces it: both bills there, the payment of 5002 not typed in yet
        byte[] rd = ControlFixtures.rdSales(cbl(date), LORRY, List.of(new ControlFixtures.Bill("5001", "SHOP A", "100.00", "Cash", "100.00"), new ControlFixtures.Bill("5002", "SHOP B", "300.00", "Credit", "0.00")));
        upload(office, date, LORRY, "RD_SALES", report(rd, "rd.xlsx"), 201);

        mockMvc.perform(get("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)))
                .andExpect(jsonPath("$.checks[?(@.code=='BILLS_VS_CBL')].status").value("PASS"))
                .andExpect(jsonPath("$.checks[?(@.code=='NET_VS_CBL')].status").value("PASS"))
                .andExpect(jsonPath("$.checks[?(@.code=='PAYMENT_VS_CBL')].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[?(@.code=='PAYMENT_VS_CBL')].detail").value("5002: cash on paper 50.00, in CBL 0.00"))
                .andExpect(jsonPath("$.checks[?(@.code=='CASH_COUNT')].status").value("PASS"))
                .andExpect(jsonPath("$.uploads[0].status").value("READ"))
                .andExpect(jsonPath("$.uploads[0].inUse").value(true));

        // the owner sees it on the board, red, and the problem is listed
        mockMvc.perform(get("/api/v1/control/dashboard/board?from=" + date + "&to=" + date).header("Authorization", bearer(token("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value(date.toString()))
                .andExpect(jsonPath("$[0].light").value("RED"))
                .andExpect(jsonPath("$[0].lorries[0].vehicle").value(LORRY))
                .andExpect(jsonPath("$[0].lorries[0].light").value("RED"))
                .andExpect(jsonPath("$[0].lorries[0].hasRdSales").value(true))
                .andExpect(jsonPath("$[0].lorries[0].problems.length()").value(1));
    }

    @Test
    void aReportForTheWrongDayOrLorryOrAPdfIsKeptButNotUsed() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE");
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(PAPER)).andExpect(status().isOk());
        byte[] otherDay = ControlFixtures.rdSales(cbl(date.minusDays(1)), LORRY, List.of());
        upload(office, date, LORRY, "RD_SALES", report(otherDay, "rd.xlsx"), 201);
        byte[] otherLorry = ControlFixtures.rdSales(cbl(date), "MAKOLARTM-AV2", List.of());
        upload(office, date, LORRY, "RD_SALES", report(otherLorry, "rd2.xlsx"), 201);
        upload(office, date, LORRY, "RD_SALES", report("%PDF-1.4 x".getBytes(StandardCharsets.ISO_8859_1), "rd.pdf"), 201);

        mockMvc.perform(get("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)))
                .andExpect(jsonPath("$.uploads.length()").value(3))
                .andExpect(jsonPath("$.uploads[?(@.status=='PROBLEM')]").value(org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.uploads[0].message").value(org.hamcrest.Matchers.containsString("Excel button")))
                .andExpect(jsonPath("$.uploads[1].message").value(org.hamcrest.Matchers.containsString("MAKOLARTM-AV2")))
                .andExpect(jsonPath("$.uploads[2].message").value(org.hamcrest.Matchers.containsString("is for")))
                .andExpect(jsonPath("$.checks[?(@.code=='BILLS_VS_CBL')].status").value("WAITING"));
    }

    @Test
    void theVanIsCheckedAgainstTheStockBalanceReportAndTheTabletAnswers() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE");
        String paper = """
                {"cashCountedRep":60.00,"cashCountedDriver":40.00,"tabletStockMorning":"NO","tabletStockAfter":"yes","morningStockOk":"YES",
                 "bills":[{"billNo":"7001","billValue":100.00,"cashRep":60.00,"cashDriver":40.00}],
                 "stock":[{"productName":"CHOCO MILK 20G","counted":120,"loadTomorrow":30},{"productName":"NUTS 40G","counted":10}]}""";
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + VAN).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(paper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vanSales").value(true))
                .andExpect(jsonPath("$.tabletStockAfter").value("YES"))
                .andExpect(jsonPath("$.checks[?(@.code=='CASH_COUNT')].status").value("PASS"))
                .andExpect(jsonPath("$.checks[?(@.code=='TABLET')].status").value("FAIL"));
        upload(office, date, VAN, "STOCK_BALANCE", report(ControlFixtures.stockCsv("CHOCO  MILK 20G|123", "NUTS 40G|10"), "export.csv"), 201);

        mockMvc.perform(get("/api/v1/control/days/" + date + "/lorries/" + VAN).header("Authorization", bearer(office)))
                .andExpect(jsonPath("$.checks[?(@.code=='STOCK')].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[?(@.code=='STOCK')].detail").value("CHOCO MILK 20G: counted 120, system 123"))
                .andExpect(jsonPath("$.stock[0].loadTomorrow").value(30));

        // the other lorries have no tablet check
        mockMvc.perform(get("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)))
                .andExpect(jsonPath("$.checks[?(@.code=='TABLET')]").isEmpty());
    }

    // ---------------------------------------------------------------- the owner's acknowledgement and signature

    @Test
    void onlyAFailingCheckCanBeAcknowledgedAndANoteIsRequiredAndItStaysVisible() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE"), admin = token("ADMIN");
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(PAPER)).andExpect(status().isOk());
        String url = "/api/v1/control/dashboard/days/" + date + "/lorries/" + LORRY + "/acknowledge";

        // BILL_LINES: 100 = 100 and 300 = 50 + 250: it passes, so there is nothing to acknowledge
        mockMvc.perform(post(url).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"checkCode\":\"BILL_LINES\",\"note\":\"ok\"}"))
                .andExpect(status().isBadRequest());

        // CASH_COUNT: 150 on the bills, 150 counted -> pass; make it fail by saving a short count
        String shortCash = PAPER.replace("\"cashCountedDriver\":150.00", "\"cashCountedDriver\":140.00");
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(shortCash)).andExpect(status().isOk());
        mockMvc.perform(post(url).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"checkCode\":\"CASH_COUNT\",\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"checkCode\":\"CASH_COUNT\",\"note\":\"Driver paid 10 for fuel, voucher 12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.code=='CASH_COUNT')].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[?(@.code=='CASH_COUNT')].acknowledged.note").value("Driver paid 10 for fuel, voucher 12"))
                .andExpect(jsonPath("$.counts.acknowledged").value(1));
        mockMvc.perform(post(url).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"checkCode\":\"NOPE\",\"note\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void theSafeTheDayEndAndTheOwnersSignatureAreOnTheBook() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE"), admin = token("ADMIN");
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(PAPER)).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/control/days/" + date + "/book").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"safeOpening\":1000.00,\"banked\":1000.00,\"paidOut\":0,\"safeCounted\":150.00,\"dayEndDone\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashCountedAll").value(150.0))
                .andExpect(jsonPath("$.checks[?(@.code=='SAFE')].status").value("PASS"))
                .andExpect(jsonPath("$.checks[?(@.code=='DAY_END')].status").value("PASS"));
        mockMvc.perform(post("/api/v1/control/dashboard/days/" + date + "/sign").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signedBy").isNotEmpty());
    }

    // ---------------------------------------------------------------- the board and what is refused

    @Test
    void theBoardListsEveryDayNewestFirstWithClosedAndMissingDays() throws Exception {
        LocalDate today = BusinessTime.today();
        LocalDate sunday = today.minusDays(1);
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) sunday = sunday.minusDays(1);
        LocalDate from = sunday.minusDays(1), to = sunday.plusDays(1).isAfter(today) ? today : sunday.plusDays(1);
        String json = mockMvc.perform(get("/api/v1/control/dashboard/board?from=" + from + "&to=" + to).header("Authorization", bearer(token("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json).contains("\"closedReason\":\"Sunday\"");
        org.assertj.core.api.Assertions.assertThat(json).contains("\"missing\":true");
        org.assertj.core.api.Assertions.assertThat(json.indexOf(to.toString())).isLessThan(json.indexOf(from.toString()));
    }

    @Test
    void badInputIsRefusedInPlainWords() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE");
        String url = "/api/v1/control/days/" + date + "/lorries/" + LORRY;
        // the same bill twice, money without a bill number, negative money, a future date, an unknown lorry, a bad Yes/No
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"1\",\"billValue\":5},{\"billNo\":\"1\",\"billValue\":6}]}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billValue\":5}]}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"1\",\"billValue\":-5}]}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/control/days/" + BusinessTime.today().plusDays(3) + "/lorries/" + LORRY).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/control/days/" + date + "/lorries/NOT-A-LORRY").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{\"tabletStockMorning\":\"maybe\"}")).andExpect(status().isBadRequest());
        upload(office, date, LORRY, "SOMETHING_ELSE", report(new byte[]{1}, "x.xlsx"), 400);
    }

    @Test
    void savingAgainReplacesTheLinesAndAnUploadCanBeRemoved() throws Exception {
        LocalDate date = aWorkingDay();
        String office = token("OFFICE");
        String url = "/api/v1/control/days/" + date + "/lorries/" + LORRY;
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content(PAPER)).andExpect(jsonPath("$.bills.length()").value(2));
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"9\",\"billValue\":5,\"cashDriver\":5},{}]}")).andExpect(jsonPath("$.bills.length()").value(1)).andExpect(jsonPath("$.bills[0].billNo").value("9"));

        byte[] rd = ControlFixtures.rdSales(cbl(date), LORRY, List.of(new ControlFixtures.Bill("9", "S", "5.00", "Cash", "5.00")));
        upload(office, date, LORRY, "RD_SALES", report(rd, "rd.xlsx"), 201);
        String body = mockMvc.perform(get(url).header("Authorization", bearer(office))).andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"uploads\":\\[\\{\"id\":(\\d+).*", "$1");
        mockMvc.perform(delete(url + "/uploads/" + id).header("Authorization", bearer(office))).andExpect(status().isNoContent());
        mockMvc.perform(get(url).header("Authorization", bearer(office))).andExpect(jsonPath("$.uploads.length()").value(0))
                .andExpect(jsonPath("$.checks[?(@.code=='BILLS_VS_CBL')].status").value("WAITING"));
    }
}
