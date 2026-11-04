package lk.aak.agency.security;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SystemUser;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.RefreshTokenRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.SystemUserRepository;
import lk.aak.agency.security.jwt.JwtService;
import lk.aak.agency.security.jwt.RefreshTokenService;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks of who may call what in the REST API, and that a login stops working the
 * moment the user record says so. Runs against H2 with real Spring Security; every test rolls back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiAccessAndSessionsTest {

    private static final String PASSWORD = "password1";

    @Autowired private MockMvc mockMvc;
    @Autowired private SystemUserRepository userRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private SalesInvoiceRepository salesInvoiceRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    // ---------------------------------------------------------------- helpers

    private String uniq(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SystemUser newUser(String role) {
        SystemUser user = new SystemUser(uniq("u"), passwordEncoder.encode(PASSWORD), "Test User", role, true);
        return userRepository.save(user);
    }

    private String token(SystemUser user) {
        return jwtService.generateAccessToken(user.getUsername(), user.getRole());
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SystemUser user) {
        return request.header("Authorization", "Bearer " + token(user));
    }

    private Employee newEmployee(String name, String systemUsername, String status) {
        Employee employee = new Employee();
        employee.setEmployeeCode(uniq("E"));
        employee.setFullName(name);
        employee.setDesignation("SALES_REP");
        employee.setStatus(status);
        employee.setSystemUsername(systemUsername);
        return employeeRepository.save(employee);
    }

    private Customer newShop(String name, Long assignedEmployeeId) {
        Customer customer = new Customer();
        customer.setCustomerCode(uniq("C"));
        customer.setCustomerName(name);
        customer.setQrCode(uniq("qr"));
        customer.setStatus("ACTIVE");
        customer.setAssignedEmployeeId(assignedEmployeeId);
        return customerRepository.save(customer);
    }

    private void owes(Customer customer, String amount) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber(uniq("INV"));
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(BusinessTime.today());
        invoice.setStatus("COMPLETED");
        invoice.setSaleType("CREDIT");
        invoice.setPaymentStatus("UNPAID");
        invoice.setNetAmount(new BigDecimal(amount));
        salesInvoiceRepository.save(invoice);
    }

    private static String json(String... pairs) {
        StringBuilder body = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) body.append(',');
            body.append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return body.append('}').toString();
    }

    // ------------------------------------------------- #2 lock down by role

    @Test
    void driverIsForbiddenFromDashboardAndEverythingUnderApiV1() throws Exception {
        SystemUser driver = newUser("DRIVER");

        for (String path : List.of("/api/v1/dashboard/summary", "/api/v1/dashboard/overview", "/api/v1/customers",
                "/api/v1/products", "/api/v1/something-new", "/api/v1/employees", "/api/v1/users")) {
            mockMvc.perform(as(get(path), driver)).andExpect(status().isForbidden());
        }
    }

    @Test
    void unknownOrWronglyWrittenRolesGetNothing() throws Exception {
        for (String role : List.of("sales_rep", "ROLE_ADMIN", "FOO", "admin")) {
            SystemUser odd = newUser(role);
            mockMvc.perform(as(get("/api/v1/dashboard/summary"), odd)).andExpect(status().isForbidden());
            mockMvc.perform(as(get("/api/v1/customers"), odd)).andExpect(status().isForbidden());
        }
    }

    @Test
    void everyLoggedInRoleCanStillSeeItselfAndChangeItsOwnPassword() throws Exception {
        SystemUser driver = newUser("DRIVER");

        mockMvc.perform(as(get("/api/auth/me"), driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(driver.getUsername()));

        mockMvc.perform(as(post("/api/auth/change-password"), driver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", "brandnew99", "confirmPassword", "brandnew99")))
                .andExpect(status().isNoContent());
    }

    @Test
    void ownerOfficeAndRepsStillReachTheDashboard() throws Exception {
        for (String role : List.of("ADMIN", "OFFICE", "SALES_REP")) {
            mockMvc.perform(as(get("/api/v1/dashboard/summary"), newUser(role))).andExpect(status().isOk());
        }
    }

    // ---------------------------------------------------- #1 rep scoping

    @Test
    void aRepOnlySeesTheirOwnShopsInTheCreditFollowUp() throws Exception {
        SystemUser rep = newUser("SALES_REP");
        Employee mine = newEmployee("Kamal", rep.getUsername(), "ACTIVE");
        Employee other = newEmployee("Nimal", uniq("someoneelse"), "ACTIVE");
        Customer myShop = newShop("My Shop", mine.getId());
        Customer theirShop = newShop("Their Shop", other.getId());
        owes(myShop, "1000");
        owes(theirShop, "5000");

        mockMvc.perform(as(get("/api/v1/customers/credit-followup"), rep))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].customerName").value("My Shop"))
                .andExpect(jsonPath("$.totalOutstanding").value(1000));

        SystemUser office = newUser("OFFICE");
        mockMvc.perform(as(get("/api/v1/customers/credit-followup"), office))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void aRepWhoseLoginIsNotLinkedToAnEmployeeSeesNoShopsInTheCreditFollowUp() throws Exception {
        SystemUser rep = newUser("SALES_REP");
        Employee other = newEmployee("Nimal", uniq("someoneelse"), "ACTIVE");
        owes(newShop("Their Shop", other.getId()), "5000");

        mockMvc.perform(as(get("/api/v1/customers/credit-followup"), rep))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(0)))
                .andExpect(jsonPath("$.totalOutstanding").value(0));
    }

    @Test
    void aRepCannotOpenAnotherRepsShopByQrCode() throws Exception {
        SystemUser rep = newUser("SALES_REP");
        Employee mine = newEmployee("Kamal", rep.getUsername(), "ACTIVE");
        Employee other = newEmployee("Nimal", uniq("someoneelse"), "ACTIVE");
        Customer myShop = newShop("My Shop", mine.getId());
        Customer theirShop = newShop("Their Shop", other.getId());

        mockMvc.perform(as(get("/api/v1/customers/scan/" + myShop.getQrCode()), rep)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/customers/scan/" + theirShop.getQrCode()), rep)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/v1/customers/scan/" + theirShop.getQrCode()), newUser("SALES_REP")))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/v1/customers/scan/" + theirShop.getQrCode()), newUser("OFFICE")))
                .andExpect(status().isOk());
        // the public "shop name only" preview is unchanged
        mockMvc.perform(get("/api/v1/customers/scan/" + theirShop.getQrCode() + "/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Their Shop"));
    }

    // ------------------------------------------------ #13 shared logins

    @Test
    void aRepWithTwoEmployeeRecordsOnOneLoginSeesNothingInsteadOfFailing() throws Exception {
        SystemUser rep = newUser("SALES_REP");
        Employee first = newEmployee("Kamal", rep.getUsername(), "ACTIVE");
        newEmployee("Kamal Again", rep.getUsername().toUpperCase(), "ACTIVE");
        newShop("Kamals Shop", first.getId());

        mockMvc.perform(as(get("/api/v1/customers"), rep))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(as(get("/api/v1/dashboard/summary"), rep)).andExpect(status().isOk());
    }

    @Test
    void aRepWhoseLoginIsNotLinkedToAnEmployeeCannotAddShops() throws Exception {
        SystemUser rep = newUser("SALES_REP");
        long shopsBefore = customerRepository.count();

        mockMvc.perform(as(post("/api/v1/customers"), rep).contentType(MediaType.APPLICATION_JSON)
                        .content(json("customerName", "Orphan Shop", "customerType", "RETAIL", "status", "ACTIVE",
                                "creditLimit", "1000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not linked to an employee")));

        org.assertj.core.api.Assertions.assertThat(customerRepository.count()).isEqualTo(shopsBefore);
    }

    @Test
    void twoEmployeesCannotShareOneLogin_evenWithDifferentCase() throws Exception {
        SystemUser admin = newUser("ADMIN");
        String login = uniq("rep");

        mockMvc.perform(as(post("/api/v1/employees"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Kamal Perera", "designation", "SALES_REP", "status", "ACTIVE",
                                "systemUsername", login)))
                .andExpect(status().isCreated());

        mockMvc.perform(as(post("/api/v1/employees"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Nimal Silva", "designation", "SALES_REP", "status", "ACTIVE",
                                "systemUsername", login.toUpperCase())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("That login is already linked to Kamal Perera")));
    }

    @Test
    void editingAnEmployeeCannotStealAnotherEmployeesLoginButKeepingYourOwnIsFine() throws Exception {
        SystemUser admin = newUser("ADMIN");
        String login = uniq("rep");
        Employee kamal = newEmployee("Kamal Perera", login, "ACTIVE");
        Employee nimal = newEmployee("Nimal Silva", null, "ACTIVE");

        mockMvc.perform(as(put("/api/v1/employees/" + kamal.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Kamal P", "designation", "SALES_REP", "status", "ACTIVE",
                                "systemUsername", login)))
                .andExpect(status().isOk());

        // (last in the test: the refused edit leaves the shared test transaction holding a half-edit)
        mockMvc.perform(as(put("/api/v1/employees/" + nimal.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Nimal Silva", "designation", "SALES_REP", "status", "ACTIVE",
                                "systemUsername", login)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("That login is already linked to Kamal P")));
    }

    // ------------------------------------------- #10 returns: admin only

    @Test
    void onlyTheOwnerMayApproveOrRejectReturns() throws Exception {
        SystemUser office = newUser("OFFICE");
        SystemUser admin = newUser("ADMIN");

        for (String path : List.of("/api/v1/shop-returns/999999/approve", "/api/v1/shop-returns/999999/reject",
                "/api/v1/supplier-returns/999999/approve", "/api/v1/supplier-returns/999999/reject")) {
            mockMvc.perform(as(post(path), office)).andExpect(status().isForbidden());
            // the owner is let through (the return does not exist, so the service answers, not security)
            int adminStatus = mockMvc.perform(as(post(path), admin)).andReturn().getResponse().getStatus();
            assertThat(adminStatus).isNotIn(401, 403);
        }

        // office keeps the rest of the returns screens
        mockMvc.perform(as(get("/api/v1/shop-returns"), office)).andExpect(status().isOk());
        mockMvc.perform(as(get("/api/v1/supplier-returns"), office)).andExpect(status().isOk());
    }

    // --------------------------------------------- #14 sessions and logins

    @Test
    void aDisabledUsersStillValidTokenStopsWorkingAtOnce() throws Exception {
        SystemUser office = newUser("OFFICE");
        String token = token(office);

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        office.setEnabled(false);
        userRepository.save(office);

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aFailedLoginLockDoesNotEvictASessionAlreadyInProgress_butBlocksLoginAndRefresh() throws Exception {
        SystemUser office = newUser("OFFICE");
        String token = token(office);
        String refresh = refreshTokenService.issue(office.getUsername());

        office.setLockedUntil(BusinessTime.now().plusMinutes(10));
        userRepository.save(office);

        // the owner's work in progress is not interrupted by a stranger's wrong guesses
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // but no new session can be started while locked
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", office.getUsername(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDemotedUsersOldTokenGetsTheCurrentRoleNotTheOneInTheToken() throws Exception {
        SystemUser formerAdmin = newUser("ADMIN");
        String token = token(formerAdmin);
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        formerAdmin.setRole("OFFICE");
        userRepository.save(formerAdmin);

        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void aDeletedUsersTokenStopsWorking() throws Exception {
        SystemUser gone = newUser("OFFICE");
        String token = token(gone);
        userRepository.delete(gone);
        userRepository.flush();

        mockMvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingYourOwnPasswordSignsOutEveryOtherSession() throws Exception {
        SystemUser office = newUser("OFFICE");
        String oldSession = refreshTokenService.issue(office.getUsername());

        mockMvc.perform(as(post("/api/auth/change-password"), office)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", "brandnew99", "confirmPassword", "brandnew99")))
                .andExpect(status().isNoContent());

        assertThat(refreshTokenService.consume(oldSession)).isEmpty();
    }

    @Test
    void anAdminPasswordResetSignsTheUserOutAndClearsTheLock() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser office = newUser("OFFICE");
        office.setFailedAttemptCount(5);
        office.setLockedUntil(BusinessTime.now().plusMinutes(10));
        userRepository.save(office);
        String session = refreshTokenService.issue(office.getUsername());

        mockMvc.perform(as(put("/api/v1/users/" + office.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", office.getUsername(), "fullName", "Test User", "role", "OFFICE",
                                "password", "freshpass99")))
                .andExpect(status().isOk());

        SystemUser reloaded = userRepository.findById(office.getId()).orElseThrow();
        assertThat(reloaded.getLockedUntil()).isNull();
        assertThat(reloaded.getFailedAttemptCount()).isZero();
        assertThat(passwordEncoder.matches("freshpass99", reloaded.getPassword())).isTrue();
        assertThat(refreshTokenService.consume(session)).isEmpty();
    }

    @Test
    void disablingOrDemotingAUserRevokesTheirRefreshTokens() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser office = newUser("OFFICE");
        String session = refreshTokenService.issue(office.getUsername());

        mockMvc.perform(as(put("/api/v1/users/" + office.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + office.getUsername() + "\",\"fullName\":\"Test User\","
                                + "\"role\":\"OFFICE\",\"enabled\":false}"))
                .andExpect(status().isOk());

        assertThat(refreshTokenService.consume(session)).isEmpty();
    }

    @Test
    void aLockedAccountAndAnUnknownUsernameGetTheSameAnswerAtLogin() throws Exception {
        SystemUser office = newUser("OFFICE");
        office.setLockedUntil(BusinessTime.now().plusMinutes(10));
        userRepository.save(office);

        String locked = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", office.getUsername(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknown = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", uniq("nobody"), "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(locked).isEqualTo(unknown);
        assertThat(locked).contains("Invalid username or password");
    }

    @Test
    void anAdminCanUnlockAUser_andNobodyElseCan() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser office = newUser("OFFICE");
        SystemUser victim = newUser("SALES_REP");
        victim.setFailedAttemptCount(5);
        victim.setLockedUntil(BusinessTime.now().plusMinutes(10));
        userRepository.save(victim);

        mockMvc.perform(as(post("/api/v1/users/" + victim.getId() + "/unlock"), office))
                .andExpect(status().isForbidden());

        mockMvc.perform(as(post("/api/v1/users/" + victim.getId() + "/unlock"), admin))
                .andExpect(status().isNoContent());

        SystemUser reloaded = userRepository.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getLockedUntil()).isNull();
        assertThat(reloaded.getFailedAttemptCount()).isZero();

        mockMvc.perform(as(post("/api/v1/users/999999/unlock"), admin)).andExpect(status().isNotFound());
    }

    @Test
    void deactivatingAnEmployeeTurnsOffTheirLoginAndSessions_reactivatingDoesNotTurnItBackOn() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser rep = newUser("SALES_REP");
        Employee employee = newEmployee("Kamal Perera", rep.getUsername(), "ACTIVE");
        String session = refreshTokenService.issue(rep.getUsername());

        mockMvc.perform(as(put("/api/v1/employees/" + employee.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Kamal Perera", "designation", "SALES_REP", "status", "INACTIVE",
                                "systemUsername", rep.getUsername())))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(rep.getId()).orElseThrow().isEnabled()).isFalse();
        assertThat(refreshTokenService.consume(session)).isEmpty();
        mockMvc.perform(as(get("/api/v1/customers"), rep)).andExpect(status().isUnauthorized());

        mockMvc.perform(as(put("/api/v1/employees/" + employee.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "Kamal Perera", "designation", "SALES_REP", "status", "ACTIVE",
                                "systemUsername", rep.getUsername())))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(rep.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void deactivatingAnEmployeeNeverDisablesTheLastActiveOwnerLogin() throws Exception {
        SystemUser onlyAdmin = newUser("ADMIN");
        // make sure no other enabled admin exists in this test's data
        userRepository.findAll().stream()
                .filter(u -> "ADMIN".equals(u.getRole()) && !u.getId().equals(onlyAdmin.getId()))
                .forEach(u -> { u.setEnabled(false); userRepository.save(u); });
        Employee owner = newEmployee("The Owner", onlyAdmin.getUsername(), "ACTIVE");

        mockMvc.perform(as(put("/api/v1/employees/" + owner.getId()), onlyAdmin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("fullName", "The Owner", "designation", "MANAGER", "status", "INACTIVE",
                                "systemUsername", onlyAdmin.getUsername())))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(onlyAdmin.getId()).orElseThrow().isEnabled()).isTrue();
    }

    // ------------------------------------------ user creation validation

    private String userBody(String username, String role, String password) {
        return json("username", username, "fullName", "Some One", "role", role, "password", password);
    }

    @Test
    void creatingAUserWithAnInvalidRoleIsRejectedWithAClearMessage() throws Exception {
        SystemUser admin = newUser("ADMIN");

        for (String role : List.of("FOO", "ROLE_ADMIN", "admin", "Office", "sales_rep")) {
            mockMvc.perform(as(post("/api/v1/users"), admin).contentType(MediaType.APPLICATION_JSON)
                            .content(userBody(uniq("n"), role, "longenough1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("ADMIN, OFFICE, SALES_REP, DRIVER")));
        }
    }

    @Test
    void creatingAUserWithAShortPasswordIsRejectedWithAClearMessage() throws Exception {
        SystemUser admin = newUser("ADMIN");

        mockMvc.perform(as(post("/api/v1/users"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(userBody(uniq("n"), "OFFICE", "short7!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least 8 characters")));
    }

    @Test
    void creatingAValidUserStillWorks_andAllFourRolesAreAccepted() throws Exception {
        SystemUser admin = newUser("ADMIN");

        for (String role : List.of("ADMIN", "OFFICE", "SALES_REP", "DRIVER")) {
            mockMvc.perform(as(post("/api/v1/users"), admin).contentType(MediaType.APPLICATION_JSON)
                            .content(userBody(uniq("n"), role, "longenough1")))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void updatingAUserRejectsABadRoleOrAShortResetPassword_andLeavesTheUserUntouched() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser office = newUser("OFFICE");
        String oldHash = office.getPassword();

        mockMvc.perform(as(put("/api/v1/users/" + office.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", office.getUsername(), "fullName", "Test User", "role", "ROLE_ADMIN")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(as(put("/api/v1/users/" + office.getId()), admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", office.getUsername(), "fullName", "Test User", "role", "OFFICE",
                                "password", "tiny")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least 8 characters")));

        SystemUser reloaded = userRepository.findById(office.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo("OFFICE");
        assertThat(reloaded.getPassword()).isEqualTo(oldHash);
    }

    @Test
    void changingYourPasswordToAShortOneIsRejected() throws Exception {
        SystemUser office = newUser("OFFICE");

        mockMvc.perform(as(post("/api/auth/change-password"), office).contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", "short7!", "confirmPassword", "short7!")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theUserListTellsTheOwnerWhichAccountsAreLocked() throws Exception {
        SystemUser admin = newUser("ADMIN");
        SystemUser office = newUser("OFFICE");
        office.setLockedUntil(BusinessTime.now().plusMinutes(10));
        userRepository.save(office);

        mockMvc.perform(as(get("/api/v1/users/" + office.getId()), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));
        mockMvc.perform(as(get("/api/v1/users/" + admin.getId()), admin))
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void springSecurityTestUsersStillWorkLikeBefore() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(user("office1").roles("OFFICE"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").with(user("driver1").roles("DRIVER"))).andExpect(status().isForbidden());
    }
}
