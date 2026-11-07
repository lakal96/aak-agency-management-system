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

    /** Drafts older than this many days no longer count as pending (a forgotten draft must not hold stock for ever). */
    static final int PENDING_DRAFT_DAYS = 14;

    private final InventoryService inventoryService;
    private final lk.aak.agency.repository.SalesInvoiceItemRepository salesInvoiceItemRepository;

    public InventoryApiController(InventoryService inventoryService,
                                  lk.aak.agency.repository.SalesInvoiceItemRepository salesInvoiceItemRepository) {
        this.inventoryService = inventoryService;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
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
    public List<InventoryItemResponse> availability(@org.springframework.web.bind.annotation.RequestParam(required = false) Long excludeInvoiceId) {

        // what other pending (draft) bills already ask for, per product, so the bill screen can warn the stock is spoken for
        Map<Long, Object[]> pending = new java.util.HashMap<>();
        for (Object[] row : salesInvoiceItemRepository.pendingDraftQuantities(
                lk.aak.agency.util.BusinessTime.today().minusDays(PENDING_DRAFT_DAYS), excludeInvoiceId)) {
            pending.put(((Number) row[0]).longValue(), row);
        }

        return stockList().stream().map(item -> {
            Object[] row = pending.get(item.getProductId());
            if (row == null) return item;
            return new InventoryItemResponse(item.getProductId(), item.getProductCode(), item.getProductName(), item.getUnit(),
                    item.getCurrentStock(), item.getReorderLevel(), (BigDecimal) row[1], ((Number) row[2]).intValue());
        }).toList();
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
