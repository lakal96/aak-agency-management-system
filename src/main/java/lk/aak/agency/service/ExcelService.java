package lk.aak.agency.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /**
     * Reads every data row (skipping the header row) into an ordered header-name -> cell-text
     * map. Numeric/date cells are converted to plain text so callers can parse them as needed.
     */
    public List<Map<String, String>> readRows(InputStream in) {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                return rows;
            }

            Row headerRow = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cellText(cell).trim());
            }

            DataFormatter formatter = new DataFormatter();

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null || isRowBlank(row, formatter)) {
                    continue;
                }

                Map<String, String> values = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    values.put(headers.get(c), cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                rows.add(values);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file - make sure it's a valid .xlsx export.", e);
        }

        return rows;
    }

    private boolean isRowBlank(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cellText(Cell cell) {
        return cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : cell.toString();
    }
}
