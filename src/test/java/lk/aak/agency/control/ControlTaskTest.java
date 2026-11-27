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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The to-do list: which tasks fall on which day, confirming them, what stops a confirmation, the restart, and the bell. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ControlTaskTest {

    private static final String LORRY = "MAKOLARTM-A";
    private static final String VAN = "MAKOLARTM-AV4";

    /** The work restarts 45 days ago (so the window holds a Saturday, a Sunday and a month end), whatever the date the test runs on. */
    @DynamicPropertySource
    static void startDate(DynamicPropertyRegistry registry) {
        registry.add("app.control.start-date", () -> BusinessTime.today().minusDays(45).toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ControlService control;
    @Autowired private SystemUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String token(String role) {
        SystemUser user = users.save(new SystemUser("tk_" + UUID.randomUUID().toString().substring(0, 8), passwordEncoder.encode("password1"), "Test User", role, true));
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }

    private static String bearer(String t) { return "Bearer " + t; }

    private static LocalDate start() { return BusinessTime.today().minusDays(45); }

    private boolean plainWeekday(LocalDate d) {
        return control.closedReason(d) == null && d.getDayOfWeek() != DayOfWeek.SATURDAY && !d.equals(lastWorkingDay(d));
    }

    private LocalDate lastWorkingDay(LocalDate d) {
        LocalDate x = d.withDayOfMonth(d.lengthOfMonth());
        while (control.closedReason(x) != null) x = x.minusDays(1);
        return x;
    }

    /** A recent ordinary working day: not a Saturday, not the last working day of its month. */
    private LocalDate ordinaryDay() {
        LocalDate d = BusinessTime.today().minusDays(1);
        while (!plainWeekday(d) || d.isBefore(start())) d = d.minusDays(1);
        return d;
    }

    private ResultActions day(String token, LocalDate date) throws Exception {
        return mockMvc.perform(get("/api/v1/control/tasks/day/" + date).header("Authorization", bearer(token)));
    }

    private ResultActions confirm(String token, LocalDate date, String code, String vehicle, String by, String note) throws Exception {
        String body = "{\"taskCode\":\"" + code + "\",\"vehicle\":" + (vehicle == null ? "null" : "\"" + vehicle + "\"") + ",\"doneBy\":" + (by == null ? "null" : "\"" + by + "\"") + ",\"note\":" + (note == null ? "null" : "\"" + note + "\"") + "}";
        return mockMvc.perform(post("/api/v1/control/tasks/day/" + date + "/confirm").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------------------------------------------------------------- which tasks fall on a day

    @Test
    void anOrdinaryWorkingDayHasTheDailyTasksOfEveryLorryAndNoWeeklyOrMonthlyOnes() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        day(office, d).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[?(@.code=='LOAD')]").value(hasSize(5)))
                .andExpect(jsonPath("$.tasks[?(@.code=='BILLS')]").value(hasSize(5)))
                .andExpect(jsonPath("$.tasks[?(@.code=='MORNING_CHECK')]").value(hasSize(1)))
                .andExpect(jsonPath("$.tasks[?(@.code=='STOCK_COUNT')].vehicle").value(VAN))
                .andExpect(jsonPath("$.tasks[?(@.code=='UNLOAD')]").value(hasSize(4)))
                .andExpect(jsonPath("$.tasks[?(@.code=='UNLOAD_VAN')]").value(hasSize(1)))
                .andExpect(jsonPath("$.tasks[?(@.code=='SAFE')]").value(hasSize(1)))
                .andExpect(jsonPath("$.tasks[?(@.code=='W_COUNT')]").isEmpty())
                .andExpect(jsonPath("$.tasks[?(@.code=='M_COUNT')]").isEmpty())
                // 5 LOAD + 1 MORNING_CHECK + 5 BILLS + 5 CASH + 1 STOCK_COUNT + 4 UNLOAD + 4 day tasks (cancelled, cheques, old credit, safe)
                .andExpect(jsonPath("$.required").value(25))
                .andExpect(jsonPath("$.done").value(0))
                .andExpect(jsonPath("$.overdue").value(true));
    }

    @Test
    void saturdayAddsTheWeeklyTasksAndTheLastWorkingDayOfTheMonthAddsTheMonthlyOnes() throws Exception {
        String office = token("OFFICE");
        LocalDate saturday = BusinessTime.today().minusDays(1);
        while (saturday.getDayOfWeek() != DayOfWeek.SATURDAY || saturday.isBefore(start()) || control.closedReason(saturday) != null) saturday = saturday.minusDays(1);
        day(office, saturday).andExpect(jsonPath("$.tasks[?(@.code=='W_COUNT')]").value(hasSize(1))).andExpect(jsonPath("$.tasks[?(@.code=='W_BALANCES')]").value(hasSize(1)));

        LocalDate last = BusinessTime.today().minusDays(1);
        while (!last.equals(lastWorkingDay(last)) || last.isBefore(start())) last = last.minusDays(1);
        day(office, last).andExpect(jsonPath("$.tasks[?(@.code=='M_MONTHEND')]").value(hasSize(1)));
    }

    @Test
    void daysBeforeTheStartDateAndTomorrowHaveNoTasks() throws Exception {
        String office = token("OFFICE");
        day(office, start().minusDays(1)).andExpect(jsonPath("$.beforeStart").value(true)).andExpect(jsonPath("$.tasks").isEmpty());
        day(office, BusinessTime.today().plusDays(1)).andExpect(jsonPath("$.future").value(true)).andExpect(jsonPath("$.tasks").isEmpty());
    }

    @Test
    void aSundayHasNoTasksUntilSomeoneSaysTheAgencyWorked() throws Exception {
        String office = token("OFFICE");
        LocalDate sunday = BusinessTime.today().minusDays(1);
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY || sunday.isBefore(start())) sunday = sunday.minusDays(1);
        day(office, sunday).andExpect(jsonPath("$.closedReason").value("Sunday")).andExpect(jsonPath("$.worked").value(false)).andExpect(jsonPath("$.tasks").isEmpty());
        mockMvc.perform(post("/api/v1/control/tasks/day/" + sunday + "/open").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{\"doneBy\":\"Nimal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.worked").value(true)).andExpect(jsonPath("$.tasks[?(@.code=='LOAD')]").value(hasSize(5)));
    }

    // ---------------------------------------------------------------- confirming

    @Test
    void aListTaskIsConfirmedWithANameAndCanBeReopened() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        confirm(office, d, "CANCELLED", null, null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("your name")));
        confirm(office, d, "CANCELLED", null, "Nimal", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks[?(@.code=='CANCELLED')].status").value("DONE"))
                .andExpect(jsonPath("$.tasks[?(@.code=='CANCELLED')].doneBy").value("Nimal"))
                .andExpect(jsonPath("$.done").value(1));
        confirm(office, d, "CANCELLED", null, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("already")));
        mockMvc.perform(delete("/api/v1/control/tasks/day/" + d + "/confirm").param("taskCode", "CANCELLED").header("Authorization", bearer(office)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.done").value(0));
    }

    @Test
    void theBillsTaskCannotBeConfirmedUntilThePaperAndTheCblReportAgree() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        // nothing written yet
        confirm(office, d, "BILLS", LORRY, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("No bills written yet")));

        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/bills").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"repName\":\"S\",\"driverName\":\"K\",\"bills\":[{\"billNo\":\"9001\",\"billValue\":100.00,\"cashDriver\":100.00}]}")).andExpect(status().isOk());
        confirm(office, d, "BILLS", LORRY, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("not uploaded")));
        // three checks wait for the same report, but the person is told once
        day(office, d).andExpect(jsonPath("$.tasks[?(@.code=='BILLS' && @.vehicle=='" + LORRY + "')].blockers[*]").value(hasSize(1)));

        // CBL still shows the bill as unpaid credit: the payments check fails
        String ds = d.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        byte[] unpaid = ControlFixtures.rdSales(ds, LORRY, List.of(new ControlFixtures.Bill("9001", "S", "100.00", "Credit", "0.00")));
        mockMvc.perform(multipart("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/uploads").file(new MockMultipartFile("file", "rd.xlsx", "application/octet-stream", unpaid)).param("kind", "RD_SALES").header("Authorization", bearer(office))).andExpect(status().isCreated());
        confirm(office, d, "BILLS", LORRY, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("cash on paper 100.00, in CBL 0.00")));

        // the payment is typed into CBL and the report downloaded again
        byte[] paid = ControlFixtures.rdSales(ds, LORRY, List.of(new ControlFixtures.Bill("9001", "S", "100.00", "Cash", "100.00")));
        mockMvc.perform(multipart("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/uploads").file(new MockMultipartFile("file", "rd2.xlsx", "application/octet-stream", paid)).param("kind", "RD_SALES").header("Authorization", bearer(office))).andExpect(status().isCreated());
        var confirmed = confirm(office, d, "BILLS", LORRY, "Nimal", null).andReturn().getResponse();
        assertThat(confirmed.getStatus()).as(confirmed.getContentAsString()).isEqualTo(200);
        day(office, d).andExpect(jsonPath("$.tasks[?(@.code=='BILLS' && @.vehicle=='" + LORRY + "')].status").value("DONE"));
    }

    @Test
    void aFailingCheckBlocksUntilTheOwnerAcknowledgesIt() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE"), admin = token("ADMIN");
        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/cash").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{\"cashCountedDriver\":90.00}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/bills").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"9002\",\"billValue\":100.00,\"cashDriver\":100.00}]}")).andExpect(status().isOk());
        confirm(office, d, "CASH", LORRY, "Kamala", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("counted 90.00")));

        mockMvc.perform(post("/api/v1/control/dashboard/days/" + d + "/lorries/" + LORRY + "/acknowledge").header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkCode\":\"CASH_COUNT\",\"note\":\"10 paid for fuel, voucher 4\"}")).andExpect(status().isOk());
        confirm(office, d, "CASH", LORRY, "Kamala", null).andExpect(status().isOk());
    }

    @Test
    void theVansMorningCheckNeedsBothAnswers() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        confirm(office, d, "MORNING_CHECK", VAN, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("tablet show")));
        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + VAN + "/tablet").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tabletStockMorning\":\"NO\",\"morningStockOk\":\"yes\"}")).andExpect(status().isOk());
        // a "No" is an honest answer: it is recorded, and the dashboard shows it
        confirm(office, d, "MORNING_CHECK", VAN, "Nimal", null).andExpect(status().isOk());
    }

    @Test
    void theSafeCannotBeClosedBeforeTheOtherTasksOfTheDay() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        mockMvc.perform(put("/api/v1/control/days/" + d + "/book").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"safeOpening\":0,\"banked\":0,\"paidOut\":0,\"safeCounted\":0,\"dayEndDone\":true}")).andExpect(status().isOk());
        confirm(office, d, "SAFE", null, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Finish these first")));
    }

    @Test
    void aWeeklyConfirmationNeedsANoteAboutWhatWasCompared() throws Exception {
        LocalDate saturday = BusinessTime.today().minusDays(1);
        while (saturday.getDayOfWeek() != DayOfWeek.SATURDAY || saturday.isBefore(start()) || control.closedReason(saturday) != null) saturday = saturday.minusDays(1);
        String office = token("OFFICE");
        confirm(office, saturday, "W_BALANCES", null, "Nimal", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("note")));
        confirm(office, saturday, "W_BALANCES", null, "Nimal", "Cheques 41,472 agree; credit 88,239 agrees").andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- not running, the calendar, the restart

    @Test
    void aLorryThatDidNotRunHasNoTasksUnlessItAlreadyHasBills() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        mockMvc.perform(post("/api/v1/control/tasks/day/" + d + "/no-run").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{\"vehicle\":\"MAKOLARTM-AV2\",\"doneBy\":\"Nimal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tasks[?(@.vehicle=='MAKOLARTM-AV2')]").isEmpty()).andExpect(jsonPath("$.required").value(25 - 4));
        mockMvc.perform(delete("/api/v1/control/tasks/day/" + d + "/no-run").param("vehicle", "MAKOLARTM-AV2").header("Authorization", bearer(office)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.required").value(25));

        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/bills").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"9003\",\"billValue\":5.00,\"cashDriver\":5.00}]}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/control/tasks/day/" + d + "/no-run").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("{\"vehicle\":\"" + LORRY + "\",\"doneBy\":\"Nimal\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("did run")));
    }

    @Test
    void theOwnerCanSwitchALorryOffForGood() throws Exception {
        LocalDate d = ordinaryDay();
        String admin = token("ADMIN"), office = token("OFFICE");
        mockMvc.perform(put("/api/v1/control/dashboard/lorries/SMMTMAKOLA-A/active").param("active", "false").header("Authorization", bearer(admin))).andExpect(status().isOk()).andExpect(jsonPath("$['SMMTMAKOLA-A']").value(false));
        day(office, d).andExpect(jsonPath("$.tasks[?(@.vehicle=='SMMTMAKOLA-A')]").isEmpty());
        mockMvc.perform(put("/api/v1/control/dashboard/lorries/SMMTMAKOLA-A/active").param("active", "false").header("Authorization", bearer(office))).andExpect(status().isForbidden());
    }

    @Test
    void theCalendarMarksOverdueDaysAndDaysBeforeTheRestart() throws Exception {
        String office = token("OFFICE");
        LocalDate d = ordinaryDay();
        String month = d.toString().substring(0, 7);
        String json = mockMvc.perform(get("/api/v1/control/tasks/calendar").param("month", month).header("Authorization", bearer(office))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json).contains("\"date\":\"" + d + "\",\"state\":\"OVERDUE\"");
        assertThat(json).contains("\"state\":\"FUTURE\"".substring(0, 7)); // the month always has days after the ones tested, unless it ended
        mockMvc.perform(get("/api/v1/control/tasks/calendar").param("month", "nonsense").header("Authorization", bearer(office))).andExpect(status().isBadRequest());
    }

    @Test
    void theCatchUpListHasEveryOpenDayOldestFirstAndShrinksAsTasksAreConfirmed() throws Exception {
        String office = token("OFFICE");
        String body = mockMvc.perform(get("/api/v1/control/tasks/backlog").header("Authorization", bearer(office))).andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value(start().toString())).andExpect(jsonPath("$.list[0].date").value(start().toString()))
                .andReturn().getResponse().getContentAsString();
        int before = Integer.parseInt(body.replaceAll(".*\"tasks\":(\\d+).*", "$1"));
        confirm(office, start(), "CANCELLED", null, "Nimal", null).andExpect(status().isOk());
        String after = mockMvc.perform(get("/api/v1/control/tasks/backlog").header("Authorization", bearer(office))).andReturn().getResponse().getContentAsString();
        assertThat(Integer.parseInt(after.replaceAll(".*\"tasks\":(\\d+).*", "$1"))).isEqualTo(before - 1);
    }

    // ---------------------------------------------------------------- the lists typed into Connect Plus

    @Test
    void theListsKeepTheirLinesAndRefuseBadOnes() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE");
        String url = "/api/v1/control/tasks/entries/" + d + "/LOAD?vehicle=" + LORRY;
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"a\":\"CHOCO MILK 20G\",\"qty\":120},{\"a\":\"NUTS 40G\",\"qty\":30},{}]")).andExpect(status().isOk()).andExpect(jsonPath("$.lines", hasSize(2))).andExpect(jsonPath("$.title").value("Load list"));
        mockMvc.perform(get(url).header("Authorization", bearer(office))).andExpect(jsonPath("$.lines[1].qty").value(30));
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("[{\"qty\":5}]")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url).header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("[{\"a\":\"X\",\"qty\":-1}]")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/control/tasks/entries/" + d + "/NOPE").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/control/tasks/entries/" + start().minusDays(1) + "/LOAD").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the dashboard in numbers, and the bell

    @Test
    void theNumbersAddUpWhatWasEnteredAndSayWhereToFocus() throws Exception {
        LocalDate d = ordinaryDay();
        String office = token("OFFICE"), admin = token("ADMIN");
        mockMvc.perform(put("/api/v1/control/days/" + d + "/lorries/" + LORRY + "/bills").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bills\":[{\"billNo\":\"9101\",\"billValue\":1000.00,\"cashDriver\":600.00,\"creditAmount\":400.00},{\"billNo\":\"9102\",\"billValue\":500.00,\"chequeAmount\":500.00,\"chequeNo\":\"77\"}]}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/control/tasks/entries/" + d + "/OLD_CREDIT").header("Authorization", bearer(office)).contentType(MediaType.APPLICATION_JSON).content("[{\"a\":\"SHOP\",\"b\":\"old 1 cash\",\"amount\":250.00}]")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/control/dashboard/numbers").param("from", start().toString()).param("to", BusinessTime.today().toString()).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.bills").value(2))
                .andExpect(jsonPath("$.totals.billValue").value(1500.0))
                .andExpect(jsonPath("$.totals.cash").value(600.0))
                .andExpect(jsonPath("$.totals.cheque").value(500.0))
                .andExpect(jsonPath("$.totals.credit").value(400.0))
                .andExpect(jsonPath("$.totals.oldCreditCollected").value(250.0))
                .andExpect(jsonPath("$.lorries[0].vehicle").value(LORRY))
                .andExpect(jsonPath("$.tasks.overdueTasks").isNumber())
                .andExpect(jsonPath("$.focus[0].severity").value("DANGER"))
                .andExpect(jsonPath("$.focus[0].title").value(containsString("overdue")));
        mockMvc.perform(get("/api/v1/control/dashboard/numbers").header("Authorization", bearer(office))).andExpect(status().isForbidden());
    }

    @Test
    void theBellTellsTheOfficeHowFarBehindTheWorkIs() throws Exception {
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token("OFFICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='control-overdue')].severity").value("DANGER"))
                .andExpect(jsonPath("$[?(@.id=='control-overdue')].link").value(org.hamcrest.Matchers.hasItem(containsString("/control?date=" + start()))));
        mockMvc.perform(get("/api/v1/notifications").header("Authorization", bearer(token("SALES_REP")))).andExpect(jsonPath("$[?(@.id=='control-overdue')]").isEmpty());
    }

    @Test
    void thePortalAndTheTasksAreForOfficeAndOwnerOnly() throws Exception {
        LocalDate d = ordinaryDay();
        day(token("SALES_REP"), d).andExpect(status().isForbidden());
        day(token("DRIVER"), d).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/control/tasks/day/" + d)).andExpect(status().isUnauthorized());
        day(token("ADMIN"), d).andExpect(status().isOk());
    }
}
