package lk.aak.agency.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic .xlsx template/import helper used by the bulk-import feature (customers, products,
 * etc.) - keeps the Apache POI plumbing in one place instead of duplicating it per entity.
 */
@Service
public class ExcelService {

    /** Builds a downloadable template: header row (required columns marked with *) + one example row. */
    public byte[] buildTemplate(String sheetName, List<String> headers, List<String> exampleRow) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 5000);
            }

            if (exampleRow != null && !exampleRow.isEmpty()) {
                Row row = sheet.createRow(1);
                for (int i = 0; i < exampleRow.size(); i++) {
                    row.createCell(i).setCellValue(exampleRow.get(i));
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the import template.", e);
        }
    }

    /** One data row of an uploaded sheet with its real spreadsheet row number (header = row 1). */
    public record ImportRow(int rowNumber, Map<String, String> values) {
    }

    /** The header names (trimmed) and every non-blank data row of the first sheet. */
    public record ImportSheet(List<String> headers, List<ImportRow> rows) {
    }

    /**
     * Reads every data row (skipping the header row) into an ordered header-name -> cell-text
     * map. Numeric/date cells are converted to plain text so callers can parse them as needed.
     * Use {@link #readSheet(InputStream)} when the real spreadsheet row numbers matter.
     */
    public List<Map<String, String>> readRows(InputStream in) {
        return readSheet(in).rows().stream().map(ImportRow::values).toList();
    }

    /**
     * Reads the first sheet. Blank rows are skipped but every returned row keeps its real
     * spreadsheet row number, so error messages point at the row the clerk sees in Excel.
     *
     * Cell values: numeric cells are read as numbers (the exact decimal value, never the display
     * format - 250.75 in a "0" formatted cell stays 250.75); formula cells are evaluated and give
     * their computed value; date cells become yyyy-MM-dd; everything else is its text.
     */
    public ImportSheet readSheet(InputStream in) {
        List<ImportRow> rows = new ArrayList<>();
        List<String> headers = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            Row headerRow = sheet.getRow(0);
            if (headerRow == null || sheet.getLastRowNum() < 1) {
                return new ImportSheet(headers, rows);
            }

            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                headers.add(cell == null ? "" : cellValue(cell, evaluator));
            }

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }

                Map<String, String> values = new LinkedHashMap<>();
                boolean blank = true;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    String text = cell == null ? "" : cellValue(cell, evaluator);
                    if (!text.isEmpty()) {
                        blank = false;
                    }
                    values.put(headers.get(c), text);
                }

                if (!blank) {
                    rows.add(new ImportRow(r + 1, values));
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Could not read the uploaded file - make sure it's a valid .xlsx export.", e);
        }

        return new ImportSheet(headers, rows);
    }

    /** The text value of one cell (see {@link #readSheet}); never null, already trimmed. */
    private String cellValue(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();

        if (type == CellType.FORMULA) {
            try {
                type = evaluator.evaluateFormulaCell(cell);
            } catch (RuntimeException e) {
                // A function POI cannot evaluate - fall back to the result Excel last saved.
                type = cell.getCachedFormulaResultType();
            }
            return formulaResult(cell, type);
        }

        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
    }

    private String formulaResult(Cell cell, CellType resultType) {
        return switch (resultType) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> numericText(cell, cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> "";
        };
    }

    private String numericText(Cell cell, double value) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return DateUtil.getLocalDateTime(value).toLocalDate().toString();
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        // The exact decimal text of the stored number (250.75 -> "250.75", 10.0 -> "10"),
        // independent of how the cell is formatted on screen.
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
