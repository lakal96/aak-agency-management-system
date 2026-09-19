package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.VehicleRequest;
import lk.aak.agency.dto.api.VehicleResponse;
import lk.aak.agency.model.Vehicle;
import lk.aak.agency.service.VehicleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleApiController {

    private final VehicleService vehicleService;

    public VehicleApiController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    public List<VehicleResponse> list() {
        return vehicleService.getAllVehicles().stream().map(VehicleResponse::new).toList();
    }

    @GetMapping("/{id}")
    public VehicleResponse getById(@PathVariable Long id) {
        return vehicleService.getVehicleById(id)
                .map(VehicleResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Vehicle not found."));
    }

    @PostMapping
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody VehicleRequest request) {
        Vehicle vehicle = new Vehicle();
        applyRequest(vehicle, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new VehicleResponse(vehicleService.saveVehicle(vehicle)));
    }

    @PutMapping("/{id}")
    public VehicleResponse update(@PathVariable Long id, @Valid @RequestBody VehicleRequest request) {
        Vehicle vehicle = vehicleService.getVehicleById(id)
                .orElseThrow(() -> new NoSuchElementException("Vehicle not found."));
        applyRequest(vehicle, request);
        return new VehicleResponse(vehicleService.saveVehicle(vehicle));
    }

    private void applyRequest(Vehicle vehicle, VehicleRequest request) {
        vehicle.setVehicleNumber(request.getVehicleNumber());
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setCapacityNotes(request.getCapacityNotes());
        vehicle.setAssignedDriverId(request.getAssignedDriverId());
        vehicle.setStatus(request.getStatus());
        vehicle.setNotes(request.getNotes());
    }
}
