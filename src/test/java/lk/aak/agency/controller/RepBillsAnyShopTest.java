package lk.aak.agency.controller;

import lk.aak.agency.controller.api.CustomerApiController;
import lk.aak.agency.controller.api.CustomerApiController.BillableShop;
import lk.aak.agency.controller.api.SalesInvoiceApiController;
import lk.aak.agency.dto.api.CustomerResponse;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.SalesInvoiceCreateRequest;
import lk.aak.agency.dto.api.SalesInvoiceDetailResponse;
import lk.aak.agency.dto.api.SalesInvoiceItemRequest;
import lk.aak.agency.dto.api.SalesInvoiceResponse;
import lk.aak.agency.model.Customer;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.Product;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reps swap shops, so a sales rep may bill ANY shop, not only the ones assigned to them. The bill still shows up
 * for the rep who wrote it (and for the shop's own rep), and a rep still gets no phone, credit limit or notes of
 * another rep's shop.
 */
@SpringBootTest
@Transactional
class RepBillsAnyShopTest {

    @Autowired private SalesInvoiceApiController invoiceApi;
    @Autowired private CustomerApiController customerApi;
    @Autowired private CustomerRepository customers;
    @Autowired private EmployeeRepository employees;
    @Autowired private ProductRepository products;

    private Long repAId;
    private Long repBId;
    private Customer shopOfA;
    private Customer shopOfB;
    private Customer inactiveShop;
    private Product biscuit;

    @BeforeEach
    void setUp() {
        repAId = rep("rep.a");
        repBId = rep("rep.b");
        rep("rep.c");
        shopOfA = shop("RA-1", "Shop of A", "Kandy", repAId, "ACTIVE", "0771111111");
        shopOfB = shop("RB-1", "Shop of B", "Matale", repBId, "ACTIVE", "0772222222");
        inactiveShop = shop("RB-2", "Closed shop", "Matale", repBId, "INACTIVE", "0773333333");
        biscuit = new Product();
        biscuit.setCblProductCode("RB-P1");
        biscuit.setProductName("Biscuit");
        biscuit.setUnit("PCS");
        biscuit = products.save(biscuit);
    }

    private Long rep(String username) {
        Employee employee = new Employee();
        employee.setEmployeeCode("T-" + username);
        employee.setFullName(username);
        employee.setDesignation("SALES_REP");
        employee.setStatus("ACTIVE");
        employee.setSystemUsername(username);
        return employees.save(employee).getId();
    }

    private Customer shop(String code, String name, String area, Long repId, String status, String phone) {
        Customer customer = new Customer();
        customer.setCustomerCode(code);
        customer.setCustomerName(name);
        customer.setArea(area);
        customer.setAssignedEmployeeId(repId);
        customer.setStatus(status);
        customer.setPhone(phone);
        customer.setCreditLimit(new BigDecimal("500000"));
        customer.setNotes("private note");
        customer.setQrCode("QR-" + code);
        return customers.save(customer);
    }

    private static Authentication login(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private SalesInvoiceCreateRequest bill(Customer customer, String number) {
        SalesInvoiceItemRequest item = new SalesInvoiceItemRequest();
        item.setProductId(biscuit.getId());
        item.setQuantity(new BigDecimal("10"));
        item.setUnitPrice(new BigDecimal("100"));
        SalesInvoiceCreateRequest request = new SalesInvoiceCreateRequest();
        request.setCustomerId(customer.getId());
        request.setInvoiceNumber(number);
        request.setInvoiceDate(BusinessTime.today());
        request.setSaleType("CREDIT");
        request.setItems(List.of(item));
        return request;
    }

    private List<String> invoiceNumbersSeenBy(Authentication who) {
        return invoiceApi.list(0, 50, "", who).getContent().stream().map(SalesInvoiceResponse::getInvoiceNumber).toList();
    }

    @Test
    void aRepCanWriteABillForAnotherRepsShopAndKeepsSeeingIt() {
        Authentication repA = login("rep.a", "SALES_REP");

        SalesInvoiceDetailResponse created = invoiceApi.create(bill(shopOfB, "RB-INV-1"), repA).getBody();

        assertThat(created.getInvoice().getCustomerId()).isEqualTo(shopOfB.getId());
        assertThat(invoiceNumbersSeenBy(repA)).contains("RB-INV-1");
        // the rep can open it again and change the draft
        assertThat(invoiceApi.getById(created.getInvoice().getId(), repA).getInvoice().getInvoiceNumber()).isEqualTo("RB-INV-1");
        assertThat(invoiceApi.update(created.getInvoice().getId(), bill(shopOfA, "RB-INV-1"), repA).getInvoice().getCustomerId())
                .isEqualTo(shopOfA.getId());
    }

    @Test
    void theShopsOwnRepAndTheOfficeSeeItToo_butAnUnrelatedRepDoesNot() {
        invoiceApi.create(bill(shopOfB, "RB-INV-2"), login("rep.a", "SALES_REP"));

        assertThat(invoiceNumbersSeenBy(login("rep.b", "SALES_REP"))).contains("RB-INV-2");
        assertThat(invoiceNumbersSeenBy(login("office", "OFFICE"))).contains("RB-INV-2");
        Authentication repC = login("rep.c", "SALES_REP");
        assertThat(invoiceNumbersSeenBy(repC)).doesNotContain("RB-INV-2");
        Long id = invoiceApi.list(0, 50, "", login("office", "OFFICE")).getContent().stream()
                .filter(i -> "RB-INV-2".equals(i.getInvoiceNumber())).findFirst().orElseThrow().getId();
        assertThatThrownBy(() -> invoiceApi.getById(id, repC)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> invoiceApi.update(id, bill(shopOfB, "RB-INV-2"), repC)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aBillWrittenByTheOfficeStaysWithTheShopsRepOnly() {
        invoiceApi.create(bill(shopOfB, "RB-INV-3"), login("office", "OFFICE"));

        assertThat(invoiceNumbersSeenBy(login("rep.b", "SALES_REP"))).contains("RB-INV-3");
        assertThat(invoiceNumbersSeenBy(login("rep.a", "SALES_REP"))).doesNotContain("RB-INV-3");
    }

    @Test
    void aLoginNotLinkedToAnEmployeeStillGetsNothing() {
        assertThatThrownBy(() -> invoiceApi.create(bill(shopOfA, "RB-INV-4"), login("nobody", "SALES_REP")))
                .hasMessageContaining("Selected shop was not found");
        PageResponse<BillableShop> shops = customerApi.billable(0, 20, "", login("nobody", "SALES_REP"));
        assertThat(shops.getContent()).isEmpty();
    }

    @Test
    void theShopPickerListsEveryActiveShopToARepButLeavesOutOtherRepsPhones() {
        List<BillableShop> shops = customerApi.billable(0, 50, "Shop of", login("rep.a", "SALES_REP")).getContent();

        assertThat(shops).extracting(BillableShop::customerName).containsExactly("Shop of A", "Shop of B");
        assertThat(shops.get(0).phone()).isEqualTo("0771111111");   // own shop
        assertThat(shops.get(1).phone()).isNull();                  // another rep's shop
        // inactive shops cannot be billed
        assertThat(customerApi.billable(0, 50, "Closed", login("rep.a", "SALES_REP")).getContent()).isEmpty();
    }

    @Test
    void aRepCannotFindAnotherRepsShopByItsPhone_butTheOfficeCan() {
        assertThat(customerApi.billable(0, 50, "0772222222", login("rep.a", "SALES_REP")).getContent()).isEmpty();
        assertThat(customerApi.billable(0, 50, "0771111111", login("rep.a", "SALES_REP")).getContent()).isEmpty();
        List<BillableShop> office = customerApi.billable(0, 50, "0772222222", login("office", "OFFICE")).getContent();
        assertThat(office).extracting(BillableShop::customerName).containsExactly("Shop of B");
        assertThat(office.get(0).phone()).isEqualTo("0772222222");
    }

    @Test
    void scanningAnotherRepsShopGivesOnlyWhatABillNeeds_andTheShopDetailStaysHidden() {
        Authentication repA = login("rep.a", "SALES_REP");

        CustomerResponse scanned = customerApi.scan("QR-RB-1", repA);
        assertThat(scanned.getId()).isEqualTo(shopOfB.getId());
        assertThat(scanned.getCustomerName()).isEqualTo("Shop of B");
        assertThat(scanned.getPhone()).isNull();
        assertThat(scanned.getCreditLimit()).isNull();
        assertThat(scanned.getNotes()).isNull();

        assertThat(customerApi.scan("QR-RA-1", repA).getPhone()).isEqualTo("0771111111");   // own shop: everything
        assertThatThrownBy(() -> customerApi.getById(shopOfB.getId(), repA)).isInstanceOf(NoSuchElementException.class);
        assertThat(customerApi.list(0, 50, "", repA).getContent()).extracting(CustomerResponse::getCustomerName)
                .containsExactly("Shop of A");
    }
}
