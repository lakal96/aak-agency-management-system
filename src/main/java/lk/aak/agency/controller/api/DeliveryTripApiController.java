package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.DeliveryTripRequest;
import lk.aak.agency.dto.api.DeliveryTripResponse;
import lk.aak.agency.dto.api.SalesInvoiceResponse;
import lk.aak.agency.model.DeliveryTrip;
import lk.aak.agency.service.DeliveryTripService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/delivery-trips")
public class DeliveryTripApiController {

    private final DeliveryTripService deliveryTripService;

    public DeliveryTripApiController(DeliveryTripService deliveryTripService) {
        this.deliveryTripService = deliveryTripService;
    }

    @GetMapping
    public List<DeliveryTripResponse> list() {
        return deliveryTripService.getAllTrips().stream().map(DeliveryTripResponse::new).toList();
    }

    @GetMapping("/{id}")
    public DeliveryTripResponse getById(@PathVariable Long id) {
        return deliveryTripService.getTripById(id)
                .map(DeliveryTripResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Delivery trip not found."));
    }

    @GetMapping("/{id}/invoices")
    public List<SalesInvoiceResponse> invoices(@PathVariable Long id) {
        return deliveryTripService.getInvoicesForTrip(id).stream().map(SalesInvoiceResponse::new).toList();
    }

    @GetMapping("/unassigned-invoices")
    public List<SalesInvoiceResponse> unassignedInvoices() {
        return deliveryTripService.getUnassignedCompletedInvoices().stream().map(SalesInvoiceResponse::new).toList();
    }

    @GetMapping("/vehicle-stock")
    public List<DeliveryTripService.VehicleStockRow> vehicleStock() {
        return deliveryTripService.getVehicleStockSummary();
    }

    @GetMapping("/{id}/loading-summary")
    public List<DeliveryTripService.LoadingSummaryRow> loadingSummary(@PathVariable Long id) {
        return deliveryTripService.getLoadingSummary(id);
    }

    @PostMapping("/{id}/confirm-loading")
    public void confirmLoading(@PathVariable Long id, @RequestBody(required = false) java.util.Map<Long, java.math.BigDecimal> loadedQuantityByProductId) {
        deliveryTripService.confirmLoading(id, loadedQuantityByProductId);
    }

    @PostMapping
    public ResponseEntity<DeliveryTripResponse> create(@Valid @RequestBody DeliveryTripRequest request) {
        DeliveryTrip trip = new DeliveryTrip();
        applyRequest(trip, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DeliveryTripResponse(deliveryTripService.saveTrip(trip)));
    }

    @PutMapping("/{id}")
    public DeliveryTripResponse update(@PathVariable Long id, @Valid @RequestBody DeliveryTripRequest request) {
        DeliveryTrip trip = deliveryTripService.getTripById(id)
                .orElseThrow(() -> new NoSuchElementException("Delivery trip not found."));
        applyRequest(trip, request);
        return new DeliveryTripResponse(deliveryTripService.saveTrip(trip));
    }

    @PostMapping("/{id}/invoices/{invoiceId}")
    public void assignInvoice(@PathVariable Long id, @PathVariable Long invoiceId) {
        deliveryTripService.assignInvoiceToTrip(id, invoiceId);
    }

    @DeleteMapping("/{id}/invoices/{invoiceId}")
    public void removeInvoice(@PathVariable Long id, @PathVariable Long invoiceId) {
        deliveryTripService.removeInvoiceFromTrip(id, invoiceId);
    }

    @PutMapping("/{id}/invoices/{invoiceId}/delivery-status")
    public void updateDeliveryStatus(
            @PathVariable Long id, @PathVariable Long invoiceId, @RequestParam String status) {

        deliveryTripService.updateDeliveryStatus(id, invoiceId, status);
    }

    private void applyRequest(DeliveryTrip trip, DeliveryTripRequest request) {
        trip.setTripDate(request.getTripDate());
        trip.setRouteId(request.getRouteId());
        trip.setAreaCovered(request.getAreaCovered());
        trip.setVehicleId(request.getVehicleId());
        trip.setDriverEmployeeId(request.getDriverEmployeeId());
        trip.setHelperEmployeeId(request.getHelperEmployeeId());
        trip.setStatus(request.getStatus());
        trip.setNotes(request.getNotes());
    }
}
