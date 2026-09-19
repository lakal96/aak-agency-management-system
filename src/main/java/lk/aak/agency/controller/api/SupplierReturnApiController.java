package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.SupplierReturnCreateRequest;
import lk.aak.agency.dto.api.SupplierReturnDetailResponse;
import lk.aak.agency.dto.api.SupplierReturnItemCreateRequest;
import lk.aak.agency.dto.api.SupplierReturnItemResponse;
import lk.aak.agency.dto.api.SupplierReturnResponse;
import lk.aak.agency.model.SupplierReturn;
import lk.aak.agency.service.SupplierReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** Create is header-then-items, matching SupplierReturnService's own two-step save/addItem design. */
@RestController
@RequestMapping("/api/v1/supplier-returns")
public class SupplierReturnApiController {

    private final SupplierReturnService supplierReturnService;

    public SupplierReturnApiController(SupplierReturnService supplierReturnService) {
        this.supplierReturnService = supplierReturnService;
    }

    @GetMapping
    public java.util.List<SupplierReturnResponse> list() {
        return supplierReturnService.getAllReturns().stream().map(SupplierReturnResponse::new).toList();
    }

    @GetMapping("/{id}")
    public SupplierReturnDetailResponse getById(@PathVariable Long id) {
        var supplierReturn = supplierReturnService.getReturnById(id)
                .orElseThrow(() -> new NoSuchElementException("Supplier return not found."));

        var items = supplierReturnService.getItemsForReturn(id).stream()
                .map(SupplierReturnItemResponse::new).toList();

        return new SupplierReturnDetailResponse(new SupplierReturnResponse(supplierReturn), items);
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id) {
        supplierReturnService.approveReturn(id);
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id) {
        supplierReturnService.rejectReturn(id);
    }

    @PostMapping
    public ResponseEntity<SupplierReturnResponse> create(@Valid @RequestBody SupplierReturnCreateRequest request) {
        SupplierReturn supplierReturn = new SupplierReturn();
        supplierReturn.setPurchaseInvoiceId(request.getPurchaseInvoiceId());
        supplierReturn.setReturnDate(request.getReturnDate());
        supplierReturn.setReason(request.getReason());
        supplierReturn.setReferenceNumber(request.getReferenceNumber());
        supplierReturn.setNotes(request.getNotes());

        SupplierReturn saved = supplierReturnService.saveReturn(supplierReturn);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SupplierReturnResponse(saved));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<SupplierReturnItemResponse> addItem(
            @PathVariable Long id, @Valid @RequestBody SupplierReturnItemCreateRequest request) {

        var item = supplierReturnService.addItem(id, request.getProductId(), request.getQuantity(), request.getUnitPrice());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SupplierReturnItemResponse(item));
    }
}
