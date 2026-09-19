package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.SupplierReturnDetailResponse;
import lk.aak.agency.dto.api.SupplierReturnItemResponse;
import lk.aak.agency.dto.api.SupplierReturnResponse;
import lk.aak.agency.service.SupplierReturnService;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** Read-only + approve/reject for now - creating a return needs its own item-builder UI (deferred). */
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
}
