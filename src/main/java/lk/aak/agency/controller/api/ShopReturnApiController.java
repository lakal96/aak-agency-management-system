package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.ShopReturnDetailResponse;
import lk.aak.agency.dto.api.ShopReturnItemResponse;
import lk.aak.agency.dto.api.ShopReturnResponse;
import lk.aak.agency.service.ShopReturnService;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** Read-only + approve/reject for now - creating a return needs its own item-builder UI (deferred). */
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
}
