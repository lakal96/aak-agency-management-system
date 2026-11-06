package lk.aak.agency.batch;

import lk.aak.agency.model.Product;
import lk.aak.agency.model.PurchaseInvoice;
import lk.aak.agency.model.PurchaseInvoiceItem;
import lk.aak.agency.service.InventoryService;
import lk.aak.agency.util.BusinessTime;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 1 of batch pricing: every completed purchase line is remembered as a batch with its cost per piece (what
 * the invoice line cost before the invoice-level discount) and the selling price that cost implies (+10%, to the
 * nearest rupee). Nothing here changes what a bill charges.
 */
@Service
public class StockBatchService {

    public static final BigDecimal MARGIN_PERCENT = new BigDecimal("10.00");

    private final StockBatchRepository repository;
    private final InventoryService inventoryService;

    public StockBatchService(StockBatchRepository repository, InventoryService inventoryService) {
        this.repository = repository;
        this.inventoryService = inventoryService;
    }

    /** Called when a purchase invoice is completed. Safe to call twice: a purchase line becomes one batch only. */
    public void recordBatches(PurchaseInvoice invoice, List<PurchaseInvoiceItem> items) {

        for (PurchaseInvoiceItem item : items) {
            if (item.getId() == null || item.getQuantity() == null || item.getQuantity().signum() <= 0
                    || repository.existsByPurchaseInvoiceItemId(item.getId())) {
                continue;
            }
            BigDecimal amount = item.getAmount() == null ? BigDecimal.ZERO : item.getAmount();
            BigDecimal unitCost = amount.divide(item.getQuantity(), 4, RoundingMode.HALF_UP);
            BigDecimal exact = unitCost.multiply(BigDecimal.ONE.add(MARGIN_PERCENT.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);

            StockBatch batch = new StockBatch();
            batch.setProduct(item.getProduct());
            batch.setPurchaseInvoiceId(invoice.getId());
            batch.setPurchaseInvoiceItemId(item.getId());
            batch.setDocumentNumber(invoice.getDocumentNumber());
            batch.setBatchDate(invoice.getDeliveryDate() != null ? invoice.getDeliveryDate()
                    : invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : BusinessTime.today());
            batch.setReceivedQuantity(item.getQuantity());
            batch.setUnitCost(unitCost);
            batch.setMarginPercent(MARGIN_PERCENT);
            batch.setExactPrice(exact);
            batch.setSuggestedPrice(exact.setScale(0, RoundingMode.HALF_UP).setScale(2));
            repository.save(batch);
        }
    }

    public record Row(Long batchId, Long productId, String productCode, String productName, String unit, Long purchaseInvoiceId,
                      String documentNumber, LocalDate batchDate, BigDecimal receivedQuantity, BigDecimal remainingQuantity,
                      BigDecimal unitCost, BigDecimal exactPrice, BigDecimal suggestedPrice, BigDecimal currentSellingPrice) {
    }

    public record Unbatched(Long productId, String productCode, String productName, String unit, BigDecimal quantity) {
    }

    public record View(List<Row> rows, List<Unbatched> unbatched) {
    }

    /**
     * The batches with how much of each is still in stock. Stock is sold oldest first, so what is on hand is the
     * NEWEST batches: the current stock is counted back from the latest batch. Stock that no purchase explains
     * (opening stock, corrections) has no cost and is listed separately.
     */
    public View view(boolean onlyInStock, String search) {

        Map<Long, BigDecimal> stock = inventoryService.getStockByProduct();
        Map<Long, List<StockBatch>> byProduct = new LinkedHashMap<>();
        for (StockBatch batch : repository.findAllByOrderByProductIdAscBatchDateAscIdAsc()) {
            byProduct.computeIfAbsent(batch.getProduct().getId(), id -> new ArrayList<>()).add(batch);
        }

        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        List<Unbatched> unbatched = new ArrayList<>();

        for (Product product : inventoryService.getAllProducts()) {
            if (!needle.isEmpty() && !(product.getProductName().toLowerCase(Locale.ROOT).contains(needle)
                    || product.getCblProductCode().toLowerCase(Locale.ROOT).contains(needle))) {
                continue;
            }
            BigDecimal onHand = stock.getOrDefault(product.getId(), BigDecimal.ZERO);
            List<StockBatch> batches = byProduct.getOrDefault(product.getId(), List.of());

            BigDecimal left = onHand.max(BigDecimal.ZERO);
            List<Row> productRows = new ArrayList<>();
            for (int i = batches.size() - 1; i >= 0; i--) {   // newest first
                StockBatch b = batches.get(i);
                BigDecimal remaining = left.min(b.getReceivedQuantity());
                left = left.subtract(remaining);
                productRows.add(0, new Row(b.getId(), product.getId(), product.getCblProductCode(), product.getProductName(),
                        product.getUnit(), b.getPurchaseInvoiceId(), b.getDocumentNumber(), b.getBatchDate(),
                        b.getReceivedQuantity(), remaining, b.getUnitCost(), b.getExactPrice(), b.getSuggestedPrice(),
                        product.getStandardSellingPrice()));
            }
            for (Row row : productRows) {
                if (!onlyInStock || row.remainingQuantity().signum() > 0) rows.add(row);
            }
            if (left.signum() > 0) {
                unbatched.add(new Unbatched(product.getId(), product.getCblProductCode(), product.getProductName(), product.getUnit(), left));
            }
        }
        return new View(rows, unbatched);
    }
}
