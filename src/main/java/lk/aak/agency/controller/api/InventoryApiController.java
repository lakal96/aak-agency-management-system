package lk.aak.agency.controller.api;

import lk.aak.agency.dto.api.InventoryItemResponse;
import lk.aak.agency.model.Product;
import lk.aak.agency.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryApiController {

    private final InventoryService inventoryService;

    public InventoryApiController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public List<InventoryItemResponse> currentStock() {
        return stockList();
    }

    /**
     * The same stock figures, open to sales reps as well (see ApiSecurityConfig): they only get what the bill
     * screen shows beside a product - code, name, unit, how many are in stock and the reorder level.
     */
    @GetMapping("/availability")
    public List<InventoryItemResponse> availability() {
        return stockList();
    }

    private List<InventoryItemResponse> stockList() {

        Map<Long, BigDecimal> stockByProduct = inventoryService.getStockByProduct();

        return inventoryService.getAllProducts().stream()
                .map(product -> toResponse(product, stockByProduct))
                .toList();
    }

    private InventoryItemResponse toResponse(Product product, Map<Long, BigDecimal> stockByProduct) {
        BigDecimal stock = stockByProduct.getOrDefault(product.getId(), BigDecimal.ZERO);
        return new InventoryItemResponse(
                product.getId(),
                product.getCblProductCode(),
                product.getProductName(),
                product.getUnit(),
                stock,
                product.getReorderLevel()
        );
    }
}
