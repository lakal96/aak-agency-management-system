package lk.aak.agency.batch;

import jakarta.persistence.*;
import lk.aak.agency.model.Product;
import lk.aak.agency.util.BusinessTime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One received purchase line: its cost per piece and the selling price that cost implies (cost + margin, rounded). */
@Entity
@Table(name = "stock_batches", uniqueConstraints = @UniqueConstraint(name = "uk_stock_batch_item", columnNames = "purchase_invoice_item_id"))
public class StockBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "purchase_invoice_id", nullable = false)
    private Long purchaseInvoiceId;

    @Column(name = "purchase_invoice_item_id", nullable = false)
    private Long purchaseInvoiceItemId;

    @Column(name = "document_number", nullable = false)
    private String documentNumber;

    @Column(name = "batch_date", nullable = false)
    private LocalDate batchDate;

    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 2)
    private BigDecimal receivedQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 15, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "margin_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal marginPercent;

    /** cost + margin to the cent */
    @Column(name = "exact_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal exactPrice;

    /** the exact price rounded the way the owner chose (nearest rupee) */
    @Column(name = "suggested_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal suggestedPrice;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = BusinessTime.now();

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public Long getPurchaseInvoiceId() { return purchaseInvoiceId; }
    public void setPurchaseInvoiceId(Long purchaseInvoiceId) { this.purchaseInvoiceId = purchaseInvoiceId; }
    public Long getPurchaseInvoiceItemId() { return purchaseInvoiceItemId; }
    public void setPurchaseInvoiceItemId(Long purchaseInvoiceItemId) { this.purchaseInvoiceItemId = purchaseInvoiceItemId; }
    public String getDocumentNumber() { return documentNumber; }
    public void setDocumentNumber(String documentNumber) { this.documentNumber = documentNumber; }
    public LocalDate getBatchDate() { return batchDate; }
    public void setBatchDate(LocalDate batchDate) { this.batchDate = batchDate; }
    public BigDecimal getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(BigDecimal receivedQuantity) { this.receivedQuantity = receivedQuantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public BigDecimal getMarginPercent() { return marginPercent; }
    public void setMarginPercent(BigDecimal marginPercent) { this.marginPercent = marginPercent; }
    public BigDecimal getExactPrice() { return exactPrice; }
    public void setExactPrice(BigDecimal exactPrice) { this.exactPrice = exactPrice; }
    public BigDecimal getSuggestedPrice() { return suggestedPrice; }
    public void setSuggestedPrice(BigDecimal suggestedPrice) { this.suggestedPrice = suggestedPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
