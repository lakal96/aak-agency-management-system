package lk.aak.agency.service;

import lk.aak.agency.model.Product;
import lk.aak.agency.repository.ProductRepository;
import lk.aak.agency.repository.PurchaseInvoiceItemRepository;
import lk.aak.agency.repository.SalesInvoiceItemRepository;
import lk.aak.agency.repository.ShopReturnItemRepository;
import lk.aak.agency.repository.StockAdjustmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final AuditLogService auditLogService;
    private final SalesInvoiceItemRepository salesInvoiceItemRepository;
    private final PurchaseInvoiceItemRepository purchaseInvoiceItemRepository;
    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final ShopReturnItemRepository shopReturnItemRepository;

    public ProductService(
            ProductRepository productRepository,
            AuditLogService auditLogService,
            SalesInvoiceItemRepository salesInvoiceItemRepository,
            PurchaseInvoiceItemRepository purchaseInvoiceItemRepository,
            StockAdjustmentRepository stockAdjustmentRepository,
            ShopReturnItemRepository shopReturnItemRepository) {

        this.productRepository = productRepository;
        this.auditLogService = auditLogService;
        this.salesInvoiceItemRepository = salesInvoiceItemRepository;
        this.purchaseInvoiceItemRepository = purchaseInvoiceItemRepository;
        this.stockAdjustmentRepository = stockAdjustmentRepository;
        this.shopReturnItemRepository = shopReturnItemRepository;
    }

    public List<Product> getAllProducts() {

        return productRepository.findAll(
                Sort.by(
                        Sort.Direction.ASC,
                        "productName"
                )
        );
    }

    public Page<Product> search(String keyword, int page, int size) {
        return productRepository.search(
                keyword,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.max(size, 1),
                        Sort.by(Sort.Direction.ASC, "productName")
                )
        );
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    public Optional<Product> getProductByCblCode(
            String cblProductCode) {

        return productRepository.findByCblProductCode(
                cblProductCode
        );
    }

    public List<Product> searchProducts(
            String productName) {

        if (productName == null ||
                productName.isBlank()) {

            return getAllProducts();
        }

        return productRepository
                .findByProductNameContainingIgnoreCase(
                        productName.trim()
                );
    }

    public Product saveProduct(Product product) {

        String cblCode = product
                .getCblProductCode()
                .trim()
                .toUpperCase();

        product.setCblProductCode(cblCode);

        Optional<Product> existingProduct =
                productRepository
                        .findByCblProductCode(cblCode);

        if (existingProduct.isPresent() &&
                !existingProduct
                        .get()
                        .getId()
                        .equals(product.getId())) {

            throw new IllegalArgumentException(
                    "This CBL product code already exists."
            );
        }

        return productRepository.save(product);
    }

    public void deleteProduct(Long id) {

        Product product = productRepository.findById(id).orElse(null);

        if (salesInvoiceItemRepository.existsByProductId(id)
                || purchaseInvoiceItemRepository.existsByProductId(id)
                || stockAdjustmentRepository.existsByProductId(id)
                || shopReturnItemRepository.existsByProductId(id)) {
            throw new IllegalArgumentException(
                    "This product has sales, purchase, adjustment or return history and cannot be deleted. "
                            + "Set its status to INACTIVE instead."
            );
        }

        productRepository.deleteById(id);

        auditLogService.record(
                "PRODUCT_DELETED", "Product", id,
                product == null ? null : "Deleted product \"" + product.getProductName() + "\""
        );
    }
}