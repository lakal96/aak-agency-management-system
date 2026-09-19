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
