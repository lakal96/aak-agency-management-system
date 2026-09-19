package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.ShopReturnCreateRequest;
import lk.aak.agency.dto.api.ShopReturnDetailResponse;
import lk.aak.agency.dto.api.ShopReturnItemCreateRequest;
import lk.aak.agency.dto.api.ShopReturnItemResponse;
import lk.aak.agency.dto.api.ShopReturnResponse;
import lk.aak.agency.model.ShopReturn;
import lk.aak.agency.service.ShopReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** Create is header-then-items, matching ShopReturnService's own two-step save/addItem design. */
@RestController
@RequestMapping("/api/v1/shop-returns")
public class ShopReturnApiController {

    private final ShopReturnService shopReturnService;

    public ShopReturnApiController(ShopReturnService shopReturnService) {
        this.shopReturnService = shopReturnService;
    }

    @GetMapping
    public java.util.List<ShopReturnResponse> list() {
        return shopReturnService.getAllReturns().stream().map(ShopReturnResponse::new).toList();
    }

    @GetMapping("/{id}")
    public ShopReturnDetailResponse getById(@PathVariable Long id) {
        var shopReturn = shopReturnService.getReturnById(id)
                .orElseThrow(() -> new NoSuchElementException("Shop return not found."));

        var items = shopReturnService.getItemsForReturn(id).stream().map(ShopReturnItemResponse::new).toList();

        return new ShopReturnDetailResponse(new ShopReturnResponse(shopReturn), items);
    }

    @PostMapping("/{id}/approve")
    public void approve(@PathVariable Long id) {
        shopReturnService.approveReturn(id);
    }

    @PostMapping("/{id}/reject")
    public void reject(@PathVariable Long id) {
        shopReturnService.rejectReturn(id);
    }

    @PostMapping
    public ResponseEntity<ShopReturnResponse> create(@Valid @RequestBody ShopReturnCreateRequest request) {
        ShopReturn shopReturn = new ShopReturn();
        shopReturn.setCustomerId(request.getCustomerId());
        shopReturn.setSalesInvoiceId(request.getSalesInvoiceId());
        shopReturn.setReturnDate(request.getReturnDate());
        shopReturn.setReason(request.getReason());
        shopReturn.setNotes(request.getNotes());

        ShopReturn saved = shopReturnService.saveReturn(shopReturn);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ShopReturnResponse(saved));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<ShopReturnItemResponse> addItem(
            @PathVariable Long id, @Valid @RequestBody ShopReturnItemCreateRequest request) {

        var item = shopReturnService.addItem(
                id, request.getProductId(), request.getQuantity(), request.getUnitPrice(), request.getCategory()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(new ShopReturnItemResponse(item));
    }
}
