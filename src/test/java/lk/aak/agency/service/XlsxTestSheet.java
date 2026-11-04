package lk.aak.agency.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Builds small .xlsx files for import tests with exact control over cell types and number formats. */
final class XlsxTestSheet implements AutoCloseable {

    private final XSSFWorkbook workbook = new XSSFWorkbook();
    private final Sheet sheet = workbook.createSheet("Data");

    XlsxTestSheet(String... headers) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
    }

    /** rowIndex is the 0-based POI index, so spreadsheet row = rowIndex + 1. */
    XlsxTestSheet text(int rowIndex, int col, String value) {
        cell(rowIndex, col).setCellValue(value);
        return this;
    }

    XlsxTestSheet number(int rowIndex, int col, double value, String format) {
        Cell cell = cell(rowIndex, col);
        cell.setCellValue(value);
        CellStyle style = workbook.createCellStyle();
        DataFormat dataFormat = workbook.createDataFormat();
        style.setDataFormat(dataFormat.getFormat(format));
        cell.setCellStyle(style);
        return this;
    }

    XlsxTestSheet formula(int rowIndex, int col, String formula) {
        cell(rowIndex, col).setCellFormula(formula);
        return this;
    }

    XlsxTestSheet blankRow(int rowIndex) {
        sheet.createRow(rowIndex);
        return this;
    }

    private Cell cell(int rowIndex, int col) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        Cell cell = row.getCell(col);
        return cell == null ? row.createCell(col) : cell;
    }

    InputStream stream() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }
}
