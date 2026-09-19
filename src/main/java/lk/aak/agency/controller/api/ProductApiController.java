package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.ProductRequest;
import lk.aak.agency.dto.api.ProductResponse;
import lk.aak.agency.model.Product;
import lk.aak.agency.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/products")
public class ProductApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductService productService;

    public ProductApiController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            @RequestParam(required = false) String q) {

        Page<Product> products = productService.search(q, page, size);
        return PageResponse.from(products, ProductResponse::new);
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return productService.getProductById(id)
                .map(ProductResponse::new)
                .orElseThrow(() -> new NoSuchElementException("Product not found."));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        Product saved = productService.saveProduct(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ProductResponse(saved));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        Product product = productService.getProductById(id)
                .orElseThrow(() -> new NoSuchElementException("Product not found."));
        applyRequest(product, request);
        return new ProductResponse(productService.saveProduct(product));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (productService.getProductById(id).isEmpty()) {
            throw new NoSuchElementException("Product not found.");
        }
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    private void applyRequest(Product product, ProductRequest request) {
        product.setCblProductCode(request.getCblProductCode());
        product.setProductName(request.getProductName());
        product.setBrand(request.getBrand());
        product.setCategory(request.getCategory());
        product.setNetWeight(request.getNetWeight());
        product.setUnit(request.getUnit());
        product.setMrp(request.getMrp());
        product.setStandardSellingPrice(request.getStandardSellingPrice());
        product.setReorderLevel(request.getReorderLevel());
        product.setStatus(request.getStatus());
        product.setNotes(request.getNotes());
    }
}
