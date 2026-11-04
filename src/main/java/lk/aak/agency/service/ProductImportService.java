package lk.aak.agency.service;

import lk.aak.agency.dto.api.ImportSummaryResponse;
import lk.aak.agency.dto.api.ImportSummaryResponse.RowError;
import lk.aak.agency.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bulk product import from .xlsx. Per-row partial: a bad row is reported and the rest still import.
 *
 * Matches an existing product by CBL product code. On UPDATE a blank cell means "leave unchanged"
 * (it never zeroes a price or re-activates an inactive product); on CREATE blanks get defaults.
 * A changed MRP / selling price is audit-logged (old -> new).
 */
@Service
public class ProductImportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductImportService.class);

    static final String CODE = "CBL Product Code*";
    static final String NAME = "Product Name*";
    static final String UNIT = "Unit*";

    private final ExcelService excelService;
    private final ProductService productService;

    public ProductImportService(ExcelService excelService, ProductService productService) {
        this.excelService = excelService;
        this.productService = productService;
    }

    public ImportSummaryResponse importProducts(InputStream in) {

        ExcelService.ImportSheet sheet = excelService.readSheet(in);
        requireColumns(sheet.headers());

        List<RowError> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;

        for (ExcelService.ImportRow importRow : sheet.rows()) {
            try {
                if (importOne(importRow.values())) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                errors.add(new RowError(
                        importRow.rowNumber(),
                        ImportValues.friendlyMessage(e, "product", LOGGER, importRow.rowNumber())));
            }
        }

        return new ImportSummaryResponse(sheet.rows().size(), created, updated, errors, List.of(), List.of());
    }

    /** Returns true when a new product was created, false when an existing one was updated. */
    private boolean importOne(Map<String, String> row) {

        String code = ImportValues.text(row.get(CODE), "CBL Product Code", 100);
        if (code == null) {
            throw new IllegalArgumentException("the CBL Product Code column is empty - it is required.");
        }
        code = code.toUpperCase();

        String name = ImportValues.text(row.get(NAME), "Product Name", 200);
        String brand = ImportValues.text(row.get("Brand"), "Brand", ImportValues.MAX_SHORT_TEXT);
        String category = ImportValues.text(row.get("Category"), "Category", ImportValues.MAX_SHORT_TEXT);
        String netWeight = ImportValues.text(row.get("Net Weight"), "Net Weight", ImportValues.MAX_SHORT_TEXT);
        String unit = ImportValues.text(row.get("Unit*"), "Unit", ImportValues.MAX_SHORT_TEXT);
        BigDecimal mrp = ImportValues.money(row.get("MRP"), "MRP");
        BigDecimal sellingPrice = ImportValues.money(row.get("Standard Selling Price"), "Standard Selling Price");
        BigDecimal reorderLevel = ImportValues.quantity(row.get("Reorder Level"), "Reorder Level");
        String status = ImportValues.status(row.get("Status"), "Status");
        String notes = ImportValues.text(row.get("Notes"), "Notes", ImportValues.MAX_LONG_TEXT);

        Product existing = productService.getProductByCblCode(code).orElse(null);

        if (existing == null) {
            if (name == null) {
                throw new IllegalArgumentException("the Product Name column is empty - it is required for a new product.");
            }
            if (unit == null) {
                throw new IllegalArgumentException("the Unit column is empty - it is required for a new product.");
            }

            Product product = new Product();
            product.setCblProductCode(code);
            product.setProductName(name);
            product.setBrand(brand);
            product.setCategory(category);
            product.setNetWeight(netWeight);
            product.setUnit(unit);
            product.setMrp(mrp == null ? BigDecimal.ZERO : mrp);
            product.setStandardSellingPrice(sellingPrice == null ? BigDecimal.ZERO : sellingPrice);
            product.setReorderLevel(reorderLevel == null ? BigDecimal.ZERO : reorderLevel);
            product.setStatus(status == null ? "ACTIVE" : status);
            product.setNotes(notes);
            productService.saveProduct(product);
            return true;
        }

        BigDecimal oldMrp = existing.getMrp();
        BigDecimal oldSellingPrice = existing.getStandardSellingPrice();

        // Blank cell = leave the existing value alone.
        if (name != null) existing.setProductName(name);
        if (brand != null) existing.setBrand(brand);
        if (category != null) existing.setCategory(category);
        if (netWeight != null) existing.setNetWeight(netWeight);
        if (unit != null) existing.setUnit(unit);
        if (mrp != null) existing.setMrp(mrp);
        if (sellingPrice != null) existing.setStandardSellingPrice(sellingPrice);
        if (reorderLevel != null) existing.setReorderLevel(reorderLevel);
        if (status != null) existing.setStatus(status);
        if (notes != null) existing.setNotes(notes);

        Product saved = productService.saveProduct(existing);
        productService.recordPriceChange(saved, oldMrp, oldSellingPrice, "bulk import");
        return false;
    }

    private void requireColumns(List<String> headers) {
        List<String> missing = new ArrayList<>();
        for (String required : List.of(CODE, NAME, UNIT)) {
            if (!headers.contains(required)) {
                missing.add("\"" + required + "\"");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "The first row of the sheet must have the column headings from the template. Missing: "
                            + String.join(", ", missing) + ". Download the template and copy your data into it.");
        }
    }
}
