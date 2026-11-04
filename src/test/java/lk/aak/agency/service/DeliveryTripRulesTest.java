package lk.aak.agency.service;

import lk.aak.agency.model.Customer;
import lk.aak.agency.model.DeliveryTrip;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.Product;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.StockMovement;
import lk.aak.agency.model.Vehicle;
import lk.aak.agency.repository.CustomerRepository;
import lk.aak.agency.repository.DeliveryTripLoadItemRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.StockMovementRepository;
import lk.aak.agency.repository.VehicleRepository;
import lk.aak.agency.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trip rules against the real services and an H2 database (the walkthrough flow plus every refusal). */
@SpringBootTest
@Transactional   // roll back, so the shared in-memory database stays clean for other tests
class DeliveryTripRulesTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private DeliveryTripService service;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private SalesInvoiceRepository invoiceRepository;
    @Autowired private SalesInvoiceItemRepository itemRepository;
    @Autowired private StockMovementRepository stockMovementRepository;
    @Autowired private DeliveryTripLoadItemRepository loadItemRepository;

    private Vehicle vehicle;
    private Employee driver;
    private Employee helper;
    private Product product;
    private Customer customer;

    @BeforeEach
    void data() {
        int n = SEQ.incrementAndGet();
        vehicle = new Vehicle();
        vehicle.setVehicleNumber("TR-" + n);
        vehicle.setStatus("ACTIVE");
        vehicle = vehicleRepository.save(vehicle);

        driver = employee("TRD-" + n, "Driver " + n, "DRIVER", "ACTIVE");
        helper = employee("TRH-" + n, "Helper " + n, "HELPER", "ACTIVE");

        product = new Product();
        product.setCblProductCode("TRP-" + n);
        product.setProductName("Trip product " + n);
        product.setUnit("PCS");
        product = productRepository.save(product);

        customer = new Customer();
        customer.setCustomerCode("TRC-" + n);
        customer.setCustomerName("Trip customer " + n);
        customer = customerRepository.save(customer);
    }

    private Employee employee(String code, String name, String designation, String status) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFullName(name);
        e.setDesignation(designation);
        e.setStatus(status);
        return employeeRepository.save(e);
    }

    private SalesInvoice completedBill(String quantity) {
        SalesInvoice invoice = new SalesInvoice();
        invoice.setInvoiceNumber("TRI-" + SEQ.incrementAndGet() + "-" + System.nanoTime());
        invoice.setCustomer(customer);
        invoice.setInvoiceDate(BusinessTime.today());
        invoice.setStatus("COMPLETED");
        invoice = invoiceRepository.save(invoice);

        SalesInvoiceItem item = new SalesInvoiceItem();
        item.setSalesInvoice(invoice);
        item.setProduct(product);
        item.setQuantity(new BigDecimal(quantity));
        item.setUnit("PCS");
        item.setUnitPrice(BigDecimal.TEN);
        itemRepository.save(item);
        return invoice;
    }

    private DeliveryTrip request(String status) {
        DeliveryTrip trip = new DeliveryTrip();
        trip.setTripDate(BusinessTime.today());
        trip.setVehicleId(vehicle.getId());
        trip.setDriverEmployeeId(driver.getId());
        trip.setHelperEmployeeId(helper.getId());
        trip.setStatus(status);
        return trip;
    }

    private DeliveryTrip plannedTrip() {
        return service.saveTrip(request("PLANNED"));
    }

    private DeliveryTrip changeStatus(DeliveryTrip trip, String status) {
        DeliveryTrip changes = request(status);
        changes.setId(trip.getId());
        return service.saveTrip(changes);
    }

    private SalesInvoice reload(SalesInvoice invoice) {
        return invoiceRepository.findById(invoice.getId()).orElseThrow();
    }

    // ---- the existing walkthrough flow must keep working

    @Test
    void walkthroughFlow_plan_assign_load_deliver_close() {
        SalesInvoice bill = completedBill("5");

        DeliveryTrip trip = plannedTrip();
        assertThat(trip.getStatus()).isEqualTo("PLANNED");
        assertThat(trip.getVehicleNumber()).isEqualTo(vehicle.getVehicleNumber());

        service.assignInvoiceToTrip(trip.getId(), bill.getId());
        assertThat(reload(bill).getDeliveryStatus()).isEqualTo("PENDING");

        service.confirmLoading(trip.getId(), null);
        assertThat(service.getTripById(trip.getId()).orElseThrow().getStatus()).isEqualTo("LOADED");

        service.updateDeliveryStatus(trip.getId(), bill.getId(), "COMPLETED");
        assertThat(reload(bill).getDeliveryStatus()).isEqualTo("COMPLETED");

        // the UI closes a trip by sending the whole trip back with status COMPLETED
        DeliveryTrip closed = changeStatus(trip, "COMPLETED");
        assertThat(closed.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void closingStillWorksAfterTheVehicleWasRetired() {
        SalesInvoice bill = completedBill("2");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());
        service.confirmLoading(trip.getId(), null);

        vehicle.setStatus("INACTIVE");
        vehicleRepository.save(vehicle);

        assertThat(changeStatus(trip, "COMPLETED").getStatus()).isEqualTo("COMPLETED");
    }

    // ---- state machine

    @Test
    void unknownTripStatusIsRefusedWithAClearMessage() {
        DeliveryTrip trip = plannedTrip();

        assertThatThrownBy(() -> changeStatus(trip, "BANANA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trip status").hasMessageContaining("BANANA");
        assertThatThrownBy(() -> service.saveTrip(request("BANANA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown trip status");
    }

    @Test
    void aNewTripCannotStartLoadedOrClosed() {
        assertThatThrownBy(() -> service.saveTrip(request("COMPLETED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("starts as PLANNED");
        assertThatThrownBy(() -> service.saveTrip(request("LOADED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("starts as PLANNED");
    }

    @Test
    void onlyThePermittedStatusMovesAreAllowed() {
        SalesInvoice bill = completedBill("3");
        DeliveryTrip planned = plannedTrip();

        assertThatThrownBy(() -> changeStatus(planned, "COMPLETED"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not been loaded");
        assertThatThrownBy(() -> changeStatus(planned, "LOADED"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Confirm loading");

        service.assignInvoiceToTrip(planned.getId(), bill.getId());
        service.confirmLoading(planned.getId(), null);

        assertThatThrownBy(() -> changeStatus(planned, "PLANNED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changeStatus(planned, "CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be cancelled");

        changeStatus(planned, "COMPLETED");
        assertThatThrownBy(() -> changeStatus(planned, "PLANNED"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already COMPLETED");
    }

    @Test
    void cancellingAPlannedTripReleasesItsBills() {
        SalesInvoice bill = completedBill("3");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());

        assertThat(changeStatus(trip, "CANCELLED").getStatus()).isEqualTo("CANCELLED");

        assertThat(reload(bill).getDeliveryTripId()).isNull();
        assertThat(reload(bill).getDeliveryStatus()).isNull();
        assertThatThrownBy(() -> changeStatus(trip, "PLANNED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.assignInvoiceToTrip(trip.getId(), bill.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CANCELLED");
    }

    // ---- bills on trips

    @Test
    void billsCannotBeAddedOrRemovedOnceTheTripIsLoadedOrClosed() {
        SalesInvoice onTrip = completedBill("3");
        SalesInvoice late = completedBill("1");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), onTrip.getId());
        service.confirmLoading(trip.getId(), null);

        assertThatThrownBy(() -> service.assignInvoiceToTrip(trip.getId(), late.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancel it and plan a new one");
        assertThatThrownBy(() -> service.removeInvoiceFromTrip(trip.getId(), onTrip.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cancel it and plan a new one");

        changeStatus(trip, "COMPLETED");
        assertThatThrownBy(() -> service.assignInvoiceToTrip(trip.getId(), late.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reload(late).getDeliveryTripId()).isNull();
    }

    @Test
    void aDeliveredBillCannotBeTakenOffATrip() {
        SalesInvoice bill = completedBill("3");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());
        SalesInvoice stored = reload(bill);
        stored.setDeliveryStatus("COMPLETED");
        invoiceRepository.save(stored);

        assertThatThrownBy(() -> service.removeInvoiceFromTrip(trip.getId(), bill.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already been delivered");
    }

    @Test
    void aBillCannotBeOnTwoTrips() {
        SalesInvoice bill = completedBill("3");
        DeliveryTrip first = plannedTrip();
        DeliveryTrip second = plannedTrip();
        service.assignInvoiceToTrip(first.getId(), bill.getId());

        assertThatThrownBy(() -> service.assignInvoiceToTrip(second.getId(), bill.getId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already assigned");
        assertThat(reload(bill).getDeliveryTripId()).isEqualTo(first.getId());
    }

    // ---- delivery status

    @Test
    void deliveryStatusMustBeARealOne_andTheTripMustBeLoaded() {
        SalesInvoice bill = completedBill("3");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());

        assertThatThrownBy(() -> service.updateDeliveryStatus(trip.getId(), bill.getId(), "COMPLETED"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Confirm the loading");

        service.confirmLoading(trip.getId(), null);

        for (String bad : new String[]{"BANANA", "", "  ", null, "DELIVERED"}) {
            assertThatThrownBy(() -> service.updateDeliveryStatus(trip.getId(), bill.getId(), bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a delivery status");
        }
        for (String good : new String[]{"PENDING", "partial", "UNSUCCESSFUL", "COMPLETED"}) {
            service.updateDeliveryStatus(trip.getId(), bill.getId(), good);
        }
        assertThat(reload(bill).getDeliveryStatus()).isEqualTo("COMPLETED");
    }

    // ---- loading

    @Test
    void loadingCannotBeConfirmedTwice() {
        SalesInvoice bill = completedBill("5");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());

        service.confirmLoading(trip.getId(), null);

        assertThatThrownBy(() -> service.confirmLoading(trip.getId(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already confirmed");
        assertThat(loadItemRepository.findByDeliveryTripIdOrderByIdAsc(trip.getId())).hasSize(1);
    }

    @Test
    void loadedQuantityIsChecked() {
        SalesInvoice bill = completedBill("5");
        DeliveryTrip trip = plannedTrip();
        service.assignInvoiceToTrip(trip.getId(), bill.getId());
        Long productId = product.getId();

        assertThatThrownBy(() -> service.confirmLoading(trip.getId(), Map.of(productId, new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> service.confirmLoading(trip.getId(), Map.of(productId, new BigDecimal("1000000"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not realistic");
        // 5 on the bill + 0 in the warehouse = at most 5
        assertThatThrownBy(() -> service.confirmLoading(trip.getId(), Map.of(productId, new BigDecimal("6"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than the bills need");
        assertThatThrownBy(() -> service.confirmLoading(trip.getId(), Map.of(987654321L, BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on this trip");
        assertThat(service.getTripById(trip.getId()).orElseThrow().getStatus()).isEqualTo("PLANNED");

        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setMovementType("PURCHASE");
        movement.setQuantityChange(new BigDecimal("3"));
        movement.setStockUnit("PCS");
        movement.setReferenceType("TEST");
        movement.setReferenceItemId(SEQ.incrementAndGet() + 1000L);
        stockMovementRepository.save(movement);

        service.confirmLoading(trip.getId(), Map.of(productId, new BigDecimal("8")));   // 5 + 3 in stock
        assertThat(loadItemRepository.findByDeliveryTripIdOrderByIdAsc(trip.getId()).get(0).getLoadedQuantity())
                .isEqualByComparingTo("8");
    }

    // ---- who and what a trip may use

    @Test
    void inactiveVehicleAndPeopleAreRefused() {
        vehicle.setStatus("INACTIVE");
        vehicleRepository.save(vehicle);
        assertThatThrownBy(() -> service.saveTrip(request("PLANNED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("is not active");

        vehicle.setStatus("ACTIVE");
        vehicleRepository.save(vehicle);

        driver.setStatus("INACTIVE");
        employeeRepository.save(driver);
        assertThatThrownBy(() -> service.saveTrip(request("PLANNED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an active employee");

        driver.setStatus("ACTIVE");
        employeeRepository.save(driver);
        helper.setStatus("INACTIVE");
        employeeRepository.save(helper);
        assertThatThrownBy(() -> service.saveTrip(request("PLANNED")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an active employee");
    }

    @Test
    void aDriverMustHaveTheDriverDesignation_butAnyActiveEmployeeCanHelp() {
        Employee clerk = employee("TRO-" + SEQ.incrementAndGet(), "Office Olga", "OFFICE_STAFF", "ACTIVE");

        DeliveryTrip asDriver = request("PLANNED");
        asDriver.setDriverEmployeeId(clerk.getId());
        assertThatThrownBy(() -> service.saveTrip(asDriver))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a driver");

        DeliveryTrip asHelper = request("PLANNED");
        asHelper.setHelperEmployeeId(clerk.getId());
        assertThat(service.saveTrip(asHelper).getHelperName()).isEqualTo("Office Olga");
    }

    @Test
    void nonexistentIdsGiveClearMessagesNotServerErrors() {
        DeliveryTrip badVehicle = request("PLANNED");
        badVehicle.setVehicleId(987654321L);
        assertThatThrownBy(() -> service.saveTrip(badVehicle))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Selected vehicle was not found.");

        DeliveryTrip badDriver = request("PLANNED");
        badDriver.setDriverEmployeeId(987654321L);
        assertThatThrownBy(() -> service.saveTrip(badDriver))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The selected driver was not found.");

        DeliveryTrip badRoute = request("PLANNED");
        badRoute.setRouteId(987654321L);
        assertThatThrownBy(() -> service.saveTrip(badRoute))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Selected route was not found.");

        assertThatThrownBy(() -> service.assignInvoiceToTrip(987654321L, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Delivery trip was not found.");
    }
}
