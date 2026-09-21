package lk.aak.agency.controller.api;

import jakarta.validation.Valid;
import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.dto.api.PageResponse;
import lk.aak.agency.dto.api.ProductRequest;
import lk.aak.agency.dto.api.ProductResponse;
import lk.aak.agency.model.Product;
import lk.aak.agency.service.ExcelService;
import lk.aak.agency.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/v1/products")
public class ProductApiController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final List<String> IMPORT_HEADERS = List.of(
            "CBL Product Code*", "Product Name*", "Brand", "Category", "Net Weight",
            "Unit*", "MRP", "Standard Selling Price", "Reorder Level", "Status", "Notes"
    );

    private final ProductService productService;
    private final ExcelService excelService;

    public ProductApiController(ProductService productService, ExcelService excelService) {
        this.productService = productService;
        this.excelService = excelService;
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

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> importTemplate() {
        byte[] file = excelService.buildTemplate(
                "Products",
                IMPORT_HEADERS,
                List.of("CBL-001", "Sample Biscuit Pack", "CBL", "Snacks", "200g", "PCS", "250", "220", "10", "ACTIVE", "")
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/import")
    public ImportSummaryResponse importProducts(@RequestParam("file") MultipartFile file) throws IOException {
        List<Map<String, String>> rows = excelService.readRows(file.getInputStream());
        List<ImportSummaryResponse.RowError> errors = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < rows.size(); i++) {
            int rowNumber = i + 2;
            Map<String, String> row = rows.get(i);

            try {
                ProductRequest request = new ProductRequest();
                request.setCblProductCode(row.getOrDefault("CBL Product Code*", ""));
                request.setProductName(row.getOrDefault("Product Name*", ""));
                request.setBrand(blankToNull(row.get("Brand")));
                request.setCategory(blankToNull(row.get("Category")));
                request.setNetWeight(blankToNull(row.get("Net Weight")));
                request.setUnit(row.getOrDefault("Unit*", ""));
                request.setMrp(parseDecimal(row.get("MRP")));
                request.setStandardSellingPrice(parseDecimal(row.get("Standard Selling Price")));
                request.setReorderLevel(parseDecimal(row.get("Reorder Level")));
                request.setStatus(blankOr(row.get("Status"), "ACTIVE"));
                request.setNotes(blankToNull(row.get("Notes")));

                if (request.getCblProductCode().isBlank()) {
                    throw new IllegalArgumentException("CBL Product Code is required.");
                }
                if (request.getProductName().isBlank()) {
                    throw new IllegalArgumentException("Product Name is required.");
                }
                if (request.getUnit().isBlank()) {
                    throw new IllegalArgumentException("Unit is required.");
                }

                // Upsert by CBL product code - re-importing the same sheet updates existing
                // products instead of failing on the code's unique constraint.
                Product product = productService.getProductByCblCode(request.getCblProductCode().trim().toUpperCase())
                        .orElseGet(Product::new);
                applyRequest(product, request);
                productService.saveProduct(product);
                successCount++;
            } catch (Exception e) {
                errors.add(new ImportSummaryResponse.RowError(rowNumber, e.getMessage()));
            }
        }

        return new ImportSummaryResponse(rows.size(), successCount, errors);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a valid number.");
        }
    }
}
