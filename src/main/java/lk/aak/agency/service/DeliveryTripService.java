package lk.aak.agency.service;

import lk.aak.agency.model.DeliveryTrip;
import lk.aak.agency.model.DeliveryTripLoadItem;
import lk.aak.agency.model.Employee;
import lk.aak.agency.model.SalesInvoice;
import lk.aak.agency.model.SalesInvoiceItem;
import lk.aak.agency.model.Vehicle;
import lk.aak.agency.repository.DeliveryTripLoadItemRepository;
import lk.aak.agency.repository.DeliveryTripRepository;
import lk.aak.agency.repository.EmployeeRepository;
import lk.aak.agency.repository.RouteRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceRepository;
import lk.aak.agency.repository.VehicleRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class DeliveryTripService {

    public static final String PLANNED = "PLANNED";
    public static final String LOADED = "LOADED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    /** The delivery statuses a bill on a trip can have (same list the trip screen offers). */
    public static final List<String> DELIVERY_STATUSES = List.of("PENDING", "COMPLETED", "PARTIAL", "UNSUCCESSFUL");

    private static final List<String> TRIP_STATUSES = List.of(PLANNED, LOADED, COMPLETED, CANCELLED);
    private static final BigDecimal MAX_LOADED_QUANTITY = new BigDecimal("1000000");

    private final DeliveryTripRepository deliveryTripRepository;
    private final DeliveryTripLoadItemRepository deliveryTripLoadItemRepository;
    private final SalesInvoiceRepository salesInvoiceRepository;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final RouteRepository routeRepository;
    private final VehicleRepository vehicleRepository;
    private final EmployeeRepository employeeRepository;
    private final InventoryService inventoryService;

    public DeliveryTripService(
            DeliveryTripRepository deliveryTripRepository,
            DeliveryTripLoadItemRepository deliveryTripLoadItemRepository,
            SalesInvoiceRepository salesInvoiceRepository,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            RouteRepository routeRepository,
            VehicleRepository vehicleRepository,
            EmployeeRepository employeeRepository,
            InventoryService inventoryService) {

        this.deliveryTripRepository = deliveryTripRepository;
        this.deliveryTripLoadItemRepository = deliveryTripLoadItemRepository;
        this.salesInvoiceRepository = salesInvoiceRepository;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.routeRepository = routeRepository;
        this.vehicleRepository = vehicleRepository;
        this.employeeRepository = employeeRepository;
        this.inventoryService = inventoryService;
    }

    public List<DeliveryTrip> getAllTrips() {
        return deliveryTripRepository.findAll(Sort.by(Sort.Direction.DESC, "tripDate").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    /** True when the trip exists - does not load it, so a later lock-and-read sees fresh data. */
    public boolean tripExists(Long id) {
        return deliveryTripRepository.existsById(id);
    }

    public Optional<DeliveryTrip> getTripById(Long id) {
        return deliveryTripRepository.findById(id);
    }

    /**
     * Creates a trip (id null) or updates one. A new trip always starts PLANNED. After that the status
     * only moves PLANNED -> LOADED (through confirmLoading), LOADED -> COMPLETED (closing the trip) or
     * PLANNED -> CANCELLED; a blank status on an update means "leave the status as it is". The vehicle
     * must be ACTIVE, the driver an ACTIVE driver and the helper an ACTIVE employee (checked when they
     * are new or changed, so a trip can still be closed after its vehicle was retired).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryTrip saveTrip(DeliveryTrip incoming) {

        boolean isNew = incoming.getId() == null;
        DeliveryTrip trip = isNew ? incoming : lockTrip(incoming.getId());
        String requestedStatus = normalizeTripStatus(incoming.getStatus());

        if (isNew) {
            if (requestedStatus != null && !PLANNED.equals(requestedStatus)) {
                throw new IllegalArgumentException("A new trip always starts as PLANNED. Plan it first, then confirm loading.");
            }
            trip.setStatus(PLANNED);
        } else {
            String currentStatus = trip.getStatus() == null ? PLANNED : trip.getStatus().toUpperCase();
            if (requestedStatus != null && !requestedStatus.equals(currentStatus)) {
                checkTripTransition(currentStatus, requestedStatus);
            }

            boolean vehicleChanged = !Objects.equals(trip.getVehicleId(), incoming.getVehicleId());
            boolean driverChanged = !Objects.equals(trip.getDriverEmployeeId(), incoming.getDriverEmployeeId());
            boolean helperChanged = !Objects.equals(trip.getHelperEmployeeId(), incoming.getHelperEmployeeId());

            trip.setTripDate(incoming.getTripDate());
            trip.setRouteId(incoming.getRouteId());
            trip.setAreaCovered(incoming.getAreaCovered());
            trip.setVehicleId(incoming.getVehicleId());
            trip.setDriverEmployeeId(incoming.getDriverEmployeeId());
            trip.setHelperEmployeeId(incoming.getHelperEmployeeId());
            trip.setNotes(incoming.getNotes());

            applyReferences(trip, vehicleChanged, driverChanged, helperChanged);

            if (requestedStatus != null && !requestedStatus.equals(currentStatus)) {
                trip.setStatus(requestedStatus);
                if (CANCELLED.equals(requestedStatus)) {
                    releaseBills(trip.getId());
                }
            }
            return deliveryTripRepository.save(trip);
        }

        applyReferences(trip, true, true, true);
        return deliveryTripRepository.save(trip);
    }

    /** Looks up and checks the route, vehicle, driver and helper, and copies their names onto the trip. */
    private void applyReferences(DeliveryTrip trip, boolean checkVehicle, boolean checkDriver, boolean checkHelper) {

        if (trip.getRouteId() != null) {
            trip.setRouteName(
                    routeRepository.findById(trip.getRouteId())
                            .map(route -> route.getRouteName())
                            .orElseThrow(() -> new IllegalArgumentException("Selected route was not found."))
            );
        } else {
            trip.setRouteName(null);
        }

        if (trip.getVehicleId() == null) {
            throw new IllegalArgumentException("Choose a vehicle for this trip.");
        }
        Vehicle vehicle = vehicleRepository.findById(trip.getVehicleId())
                .orElseThrow(() -> new IllegalArgumentException("Selected vehicle was not found."));
        if (checkVehicle && !"ACTIVE".equalsIgnoreCase(vehicle.getStatus())) {
            throw new IllegalArgumentException("Vehicle " + vehicle.getVehicleNumber()
                    + " is not active. Choose an active vehicle, or set this one back to ACTIVE first.");
        }
        trip.setVehicleNumber(vehicle.getVehicleNumber());

        if (trip.getDriverEmployeeId() != null) {
            Employee driver = activeEmployee(trip.getDriverEmployeeId(), "driver", checkDriver);
            if (checkDriver && !"DRIVER".equalsIgnoreCase(driver.getDesignation())) {
                throw new IllegalArgumentException(driver.getFullName() + " is not a driver (designation: "
                        + driver.getDesignation() + "). Choose an employee whose designation is DRIVER.");
            }
            trip.setDriverName(driver.getFullName());
        } else {
            trip.setDriverName(null);
        }

        if (trip.getHelperEmployeeId() != null) {
            trip.setHelperName(activeEmployee(trip.getHelperEmployeeId(), "helper", checkHelper).getFullName());
        } else {
            trip.setHelperName(null);
        }
    }

    private Employee activeEmployee(Long id, String role, boolean mustBeActive) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("The selected " + role + " was not found."));
        if (mustBeActive && !"ACTIVE".equalsIgnoreCase(employee.getStatus())) {
            throw new IllegalArgumentException(employee.getFullName() + " is not an active employee, so cannot be the "
                    + role + " of a trip. Choose someone else.");
        }
        return employee;
    }

    private String normalizeTripStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!TRIP_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unknown trip status \"" + status.trim()
                    + "\". It must be PLANNED, LOADED, COMPLETED or CANCELLED.");
        }
        return normalized;
    }

    /** PLANNED -> LOADED (via confirm loading), LOADED -> COMPLETED, PLANNED -> CANCELLED. Nothing else. */
    private void checkTripTransition(String from, String to) {
        if (PLANNED.equals(from) && LOADED.equals(to)) {
            throw new IllegalArgumentException("A trip becomes LOADED when you confirm its loading - use \"Confirm loading\".");
        }
        if (PLANNED.equals(from) && COMPLETED.equals(to)) {
            throw new IllegalArgumentException("This trip has not been loaded yet. Confirm its loading before closing it.");
        }
        boolean allowed = (LOADED.equals(from) && COMPLETED.equals(to))
                || (PLANNED.equals(from) && CANCELLED.equals(to));
        if (!allowed) {
            if (LOADED.equals(from) && CANCELLED.equals(to)) {
                throw new IllegalArgumentException("A loaded trip cannot be cancelled - the goods are already on the vehicle. Close it instead.");
            }
            if (COMPLETED.equals(from) || CANCELLED.equals(from)) {
                throw new IllegalArgumentException("This trip is already " + from + " and cannot be changed to " + to + ".");
            }
            throw new IllegalArgumentException("A " + from + " trip cannot be changed to " + to + ".");
        }
    }

    /** A cancelled trip gives its bills back, so they can be put on another trip. */
    private void releaseBills(Long tripId) {
        for (SalesInvoice invoice : salesInvoiceRepository.findByDeliveryTripIdOrderByIdAsc(tripId)) {
            invoice.setDeliveryTripId(null);
            invoice.setDeliveryStatus(null);
            salesInvoiceRepository.save(invoice);
        }
    }

    private DeliveryTrip lockTrip(Long tripId) {
        return deliveryTripRepository.lockById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery trip was not found."));
    }

    private void requirePlanned(DeliveryTrip trip, String action) {
        if (!PLANNED.equalsIgnoreCase(trip.getStatus())) {
            throw new IllegalArgumentException("Bills cannot be " + action + " once a trip is "
                    + trip.getStatus() + ". To change a loaded trip, cancel it and plan a new one.");
        }
    }

    public List<SalesInvoice> getUnassignedCompletedInvoices() {
        return salesInvoiceRepository.findByStatusAndDeliveryTripIdIsNullOrderByInvoiceDateAsc("COMPLETED");
    }

    public List<SalesInvoice> getInvoicesForTrip(Long tripId) {
        return salesInvoiceRepository.findByDeliveryTripIdOrderByIdAsc(tripId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void assignInvoiceToTrip(Long tripId, Long invoiceId) {

        // Lock the trip, then the bill, and only then check - two people assigning the same bill
        // (or loading the trip meanwhile) wait for each other and see the committed result.
        DeliveryTrip trip = lockTrip(tripId);
        requirePlanned(trip, "added");

        SalesInvoice invoice = lockInvoice(invoiceId);

        if (!"COMPLETED".equalsIgnoreCase(invoice.getStatus())) {
            throw new IllegalArgumentException("Only completed bills can be assigned to a delivery trip.");
        }

        if (invoice.getDeliveryTripId() != null) {
            throw new IllegalArgumentException("This bill is already assigned to a delivery trip.");
        }

        invoice.setDeliveryTripId(trip.getId());
        invoice.setDeliveryStatus("PENDING");
        salesInvoiceRepository.save(invoice);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeInvoiceFromTrip(Long tripId, Long invoiceId) {

        DeliveryTrip trip = lockTrip(tripId);
        requirePlanned(trip, "removed");

        SalesInvoice invoice = lockInvoice(invoiceId);

        if (!tripId.equals(invoice.getDeliveryTripId())) {
            throw new IllegalArgumentException("This bill is not assigned to the selected trip.");
        }

        String delivery = invoice.getDeliveryStatus() == null ? "" : invoice.getDeliveryStatus().toUpperCase();
        if ("COMPLETED".equals(delivery) || "PARTIAL".equals(delivery)) {
            throw new IllegalArgumentException("This bill has already been delivered, so it cannot be taken off the trip.");
        }

        invoice.setDeliveryTripId(null);
        invoice.setDeliveryStatus(null);
        salesInvoiceRepository.save(invoice);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void updateDeliveryStatus(Long tripId, Long invoiceId, String deliveryStatus) {

        String status = deliveryStatus == null ? "" : deliveryStatus.trim().toUpperCase();
        if (!DELIVERY_STATUSES.contains(status)) {
            throw new IllegalArgumentException("\"" + (deliveryStatus == null ? "" : deliveryStatus.trim())
                    + "\" is not a delivery status. Use PENDING, COMPLETED, PARTIAL or UNSUCCESSFUL.");
        }

        DeliveryTrip trip = lockTrip(tripId);
        String tripStatus = trip.getStatus() == null ? "" : trip.getStatus().toUpperCase();
        if (PLANNED.equals(tripStatus)) {
            throw new IllegalArgumentException("Confirm the loading of this trip before recording deliveries.");
        }
        if (CANCELLED.equals(tripStatus)) {
            throw new IllegalArgumentException("This trip was cancelled, so no deliveries can be recorded on it.");
        }

        SalesInvoice invoice = lockInvoice(invoiceId);

        if (!tripId.equals(invoice.getDeliveryTripId())) {
            throw new IllegalArgumentException("This bill is not assigned to the selected trip.");
        }

        invoice.setDeliveryStatus(status);
        salesInvoiceRepository.save(invoice);
    }

    private SalesInvoice lockInvoice(Long invoiceId) {
        return salesInvoiceRepository.lockById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Sales invoice was not found."));
    }

    /**
     * Combined planned product quantities across every bill assigned to this trip -
     * used as the loading summary the warehouse checks off against at morning loading.
     * Read-only for now; actual issued quantities and the warehouse-to-vehicle stock
     * transfer are handled in a follow-up increment.
     */
    public List<LoadingSummaryRow> getLoadingSummary(Long tripId) {

        Map<Long, LoadingSummaryRow> summaryByProduct = new LinkedHashMap<>();

        for (SalesInvoice invoice : getInvoicesForTrip(tripId)) {
            List<SalesInvoiceItem> items = salesInvoiceItemRepository
                    .findBySalesInvoiceIdOrderByIdAsc(invoice.getId());

            for (SalesInvoiceItem item : items) {
                Long productId = item.getProduct().getId();

                LoadingSummaryRow existingRow = summaryByProduct.get(productId);

                if (existingRow == null) {
                    summaryByProduct.put(
                            productId,
                            new LoadingSummaryRow(
                                    productId,
                                    item.getProduct().getProductName(),
                                    item.getSalesUnit(),
                                    item.getQuantity()
                            )
                    );
                } else {
                    existingRow.plannedQuantity = existingRow.plannedQuantity.add(item.getQuantity());
                }
            }
        }

        return List.copyOf(summaryByProduct.values());
    }

    public List<DeliveryTripLoadItem> getLoadItemsForTrip(Long tripId) {
        return deliveryTripLoadItemRepository.findByDeliveryTripIdOrderByIdAsc(tripId);
    }

    /**
     * Confirms what was actually loaded onto the vehicle for this trip, using the planned
     * loading summary as the baseline (adjustable per product for real-world shortages).
     * Warehouse stock was already debited when each bill was completed, so this does not
     * create any further stock movements - it only records the confirmed quantities and
     * moves the trip to LOADED so it shows up in the vehicle stock view.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmLoading(Long tripId, Map<Long, BigDecimal> loadedQuantityByProductId) {

        // Lock first, then check the status: a double click or a second office user confirming at the
        // same moment waits here and then finds the trip already LOADED (no doubled load).
        DeliveryTrip trip = lockTrip(tripId);

        if (!"PLANNED".equalsIgnoreCase(trip.getStatus())) {
            throw new IllegalArgumentException(
                    "Loading was already confirmed for this trip (or the trip is closed or cancelled), so it cannot be confirmed again.");
        }

        List<LoadingSummaryRow> summary = getLoadingSummary(tripId);

        if (summary.isEmpty()) {
            throw new IllegalArgumentException("Assign at least one bill to this trip before confirming loading.");
        }

        if (loadedQuantityByProductId != null) {
            for (Long productId : loadedQuantityByProductId.keySet()) {
                if (summary.stream().noneMatch(row -> row.getProductId().equals(productId))) {
                    throw new IllegalArgumentException(
                            "A loaded quantity was given for a product that is not on this trip's bills.");
                }
            }
        }

        for (LoadingSummaryRow row : summary) {

            BigDecimal loadedQuantity = loadedQuantityByProductId == null
                    ? null
                    : loadedQuantityByProductId.get(row.getProductId());

            if (loadedQuantity == null) {
                loadedQuantity = row.getPlannedQuantity();
            }

            if (loadedQuantity.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Loaded quantity cannot be negative.");
            }

            if (loadedQuantity.compareTo(MAX_LOADED_QUANTITY) >= 0) {
                throw new IllegalArgumentException(
                        "Loaded quantity for " + row.getProductName() + " is not realistic - check the number.");
            }

            // The bills' quantities were already taken out of the warehouse when they were completed, so
            // the most that can be on the vehicle is what the bills need plus whatever is still in stock.
            BigDecimal inWarehouse = inventoryService.getCurrentStock(row.getProductId());
            BigDecimal mostPossible = row.getPlannedQuantity().add(inWarehouse == null ? BigDecimal.ZERO : inWarehouse);
            if (loadedQuantity.compareTo(mostPossible) > 0) {
                throw new IllegalArgumentException(
                        "Loaded quantity for " + row.getProductName() + " (" + loadedQuantity.stripTrailingZeros().toPlainString()
                                + ") is more than the bills need plus the stock in the warehouse ("
                                + mostPossible.stripTrailingZeros().toPlainString() + ").");
            }

            DeliveryTripLoadItem loadItem = new DeliveryTripLoadItem();
            loadItem.setDeliveryTripId(tripId);
            loadItem.setProductId(row.getProductId());
            loadItem.setProductName(row.getProductName());
            loadItem.setUnit(row.getUnit());
            loadItem.setPlannedQuantity(row.getPlannedQuantity());
            loadItem.setLoadedQuantity(loadedQuantity);

            deliveryTripLoadItemRepository.save(loadItem);
        }

        trip.setStatus("LOADED");
        deliveryTripRepository.save(trip);
    }

    /**
     * What's currently loaded and out on the road: sums confirmed loaded quantities per
     * vehicle/product for every trip still in LOADED status (a trip leaves this view once
     * it is closed at end of day).
     */
    public List<VehicleStockRow> getVehicleStockSummary() {

        Map<String, VehicleStockRow> stockByVehicleAndProduct = new LinkedHashMap<>();

        for (DeliveryTrip trip : deliveryTripRepository.findByStatusOrderByTripDateAsc("LOADED")) {

            for (DeliveryTripLoadItem loadItem : getLoadItemsForTrip(trip.getId())) {

                String key = trip.getVehicleNumber() + "|" + loadItem.getProductId();

                VehicleStockRow existingRow = stockByVehicleAndProduct.get(key);

                if (existingRow == null) {
                    stockByVehicleAndProduct.put(
                            key,
                            new VehicleStockRow(
                                    trip.getVehicleNumber(),
                                    loadItem.getProductName(),
                                    loadItem.getUnit(),
                                    loadItem.getLoadedQuantity()
                            )
                    );
                } else {
                    existingRow.loadedQuantity = existingRow.loadedQuantity.add(loadItem.getLoadedQuantity());
                }
            }
        }

        return List.copyOf(stockByVehicleAndProduct.values());
    }

    public static class VehicleStockRow {

        private final String vehicleNumber;
        private final String productName;
        private final String unit;
        private BigDecimal loadedQuantity;

        public VehicleStockRow(String vehicleNumber, String productName, String unit, BigDecimal loadedQuantity) {
            this.vehicleNumber = vehicleNumber;
            this.productName = productName;
            this.unit = unit;
            this.loadedQuantity = loadedQuantity;
        }

        public String getVehicleNumber() {
            return vehicleNumber;
        }

        public String getProductName() {
            return productName;
        }

        public String getUnit() {
            return unit;
        }

        public BigDecimal getLoadedQuantity() {
            return loadedQuantity;
        }
    }

    public static class LoadingSummaryRow {

        private final Long productId;
        private final String productName;
        private final String unit;
        private BigDecimal plannedQuantity;

        public LoadingSummaryRow(Long productId, String productName, String unit, BigDecimal plannedQuantity) {
            this.productId = productId;
            this.productName = productName;
            this.unit = unit;
            this.plannedQuantity = plannedQuantity;
        }

        public Long getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public String getUnit() {
            return unit;
        }

        public BigDecimal getPlannedQuantity() {
            return plannedQuantity;
        }
    }
}
