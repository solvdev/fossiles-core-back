package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.ProductCinchoType;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Plantilla Excel para actualizar el precio de venta normal ({@code product.sale_price}) en lote:
 * se descarga con el precio actual, se llena la columna "Precio nuevo" y se vuelve a subir.
 *
 * <p>La plantilla es una sola hoja con las categorías separadas por renglones de título y solo productos de kioscos (sin empaque ni Entrecueros).
 *
 * <p>La subida primero se revisa ({@code apply=false}, devuelve qué cambiaría y qué filas tienen error) y luego se
 * aplica ({@code apply=true}). Si hay filas con error no se aplica nada: el archivo se corrige y se vuelve a subir.
 * Solo toca {@code sale_price}; el precio con descuento, el de vendedor y los de Entrecueros no se modifican.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPriceTemplateService {

    static final String SHEET_PRICES = "Precios";
    static final String SHEET_HELP = "Instrucciones";
    static final String HEADER_ID = "ID";
    static final String HEADER_CODE = "Código";
    static final String HEADER_NAME = "Producto";
    static final String HEADER_CURRENT = "Precio actual";
    static final String HEADER_NEW = "Precio nuevo";

    private static final int COL_ID = 0;
    private static final int COL_CODE = 1;
    private static final int COL_NAME = 2;
    private static final int COL_CURRENT = 3;
    private static final int COL_NEW = 4;

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    /** product.sale_price es numeric(12,2). */
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final SecurityUtil securityUtil;

    public record Change(Long id, String code, String name, BigDecimal currentPrice, BigDecimal newPrice) {}

    public record RowError(String sheet, int row, Long id, String code, String message) {}

    private record PendingPrice(ProductEntity product, BigDecimal newPrice) {}

    public record ImportResult(
            boolean applied,
            int readRows,
            int blankRows,
            int unchangedRows,
            List<Change> changes,
            List<RowError> errors
    ) {}

    // ------------------------------------------------------------------------------------------------
    // Descarga
    // ------------------------------------------------------------------------------------------------

    /**
     * Productos que van en la plantilla: solo los de kioscos. Se excluyen los de empaque (código SUM-…) y los
     * cinchos de marcas de Entrecueros (esos se cobran con sus propias escalas). Las billeteras y demás productos
     * que tienen precio Entrecueros pero también se venden en los kioscos normales sí van.
     */
    static boolean isTemplateProduct(ProductEntity product) {
        boolean entrecuerosBrandCincho = Boolean.TRUE.equals(product.getEntrecuerosEnabled())
                && ProductCinchoType.normalizeCinchoType(product.getCinchoType()) != null;
        return !ProductCinchoType.isPackagingProductCode(product.getCode()) && !entrecuerosBrandCincho;
    }

    @Transactional(readOnly = true)
    public byte[] buildTemplate(Long categoryId) throws BusinessException {
        List<ProductEntity> products = (categoryId != null
                ? productRepository.findByCategoryId(categoryId)
                : productRepository.findAll()).stream()
                .filter(ProductPriceTemplateService::isTemplateProduct)
                .collect(Collectors.toList());
        if (products.isEmpty()) {
            throw new BusinessException("No hay productos de kioscos para la plantilla.");
        }
        Map<Long, String> categoryNames = productCategoryRepository.findAll().stream()
                .collect(Collectors.toMap(ProductCategoryEntity::getId,
                        c -> c.getName() != null ? c.getName() : "", (a, b) -> a));
        // Una sola hoja con las categorías separadas por un renglón de título (como el conteo físico), en orden
        // alfabético; sin categoría al final.
        Map<Long, List<ProductEntity>> byCategory = products.stream()
                .collect(Collectors.groupingBy(p -> p.getCategoryId() != null ? p.getCategoryId() : -1L));
        List<Long> categoryOrder = new ArrayList<>(byCategory.keySet());
        categoryOrder.sort(Comparator.comparing(
                (Long id) -> id < 0 ? "￿" : categoryNames.getOrDefault(id, ""),
                String.CASE_INSENSITIVE_ORDER));

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat format = workbook.createDataFormat();

            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle headerNew = workbook.createCellStyle();
            headerNew.cloneStyleFrom(header);
            headerNew.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());

            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(format.getFormat("#,##0.00"));
            CellStyle moneyNew = workbook.createCellStyle();
            moneyNew.cloneStyleFrom(money);
            moneyNew.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            moneyNew.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            moneyNew.setBorderBottom(BorderStyle.HAIR);
            moneyNew.setBorderLeft(BorderStyle.HAIR);
            moneyNew.setBorderRight(BorderStyle.HAIR);
            moneyNew.setBorderTop(BorderStyle.HAIR);

            CellStyle categoryTitle = workbook.createCellStyle();
            categoryTitle.setFont(bold);
            categoryTitle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            categoryTitle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Map<String, List<ProductEntity>> sections = new LinkedHashMap<>();
            for (Long id : categoryOrder) {
                List<ProductEntity> inCategory = new ArrayList<>(byCategory.get(id));
                inCategory.sort(Comparator.comparing(
                        (ProductEntity p) -> p.getCode() != null ? p.getCode() : "", String.CASE_INSENSITIVE_ORDER));
                sections.put(id < 0 ? "Sin categoría" : categoryNames.getOrDefault(id, "Categoría " + id), inCategory);
            }
            writePriceSheet(workbook.createSheet(SHEET_PRICES), sections, header, headerNew, categoryTitle, money,
                    moneyNew);

            Sheet help = workbook.createSheet(SHEET_HELP);
            String[] lines = {
                    "Plantilla para actualizar el precio de venta normal de los productos de kioscos.",
                    "",
                    "1. Los productos están en una sola hoja, separados por categoría (renglones azules). Escriba el precio "
                            + "nuevo en la columna \"Precio nuevo\" (amarilla) de cada producto a cambiar.",
                    "2. Las filas con \"Precio nuevo\" vacío no se modifican.",
                    "3. No cambie las columnas ID ni Código: con ellas el sistema encuentra el producto.",
                    "4. Puede borrar filas o filtrar, pero no cambie el orden de las columnas ni el nombre de los encabezados. "
                            + "Los renglones azules de categoría se ignoran: no escriba en ellos.",
                    "5. El precio debe ser mayor a 0. Use punto como separador de decimales (ej. 346.50).",
                    "6. Guarde el archivo como .xlsx y súbalo en \"Precios de Productos\". Antes de aplicar se muestra una "
                            + "vista previa de lo que cambiará.",
                    "",
                    "No incluye productos de empaque ni cinchos de marcas de Entrecueros. Solo se actualiza el precio de venta normal: "
                            + "el precio con descuento, el de vendedor y los de Entrecueros no cambian."
            };
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 110 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("No se pudo generar la plantilla de precios.");
        }
    }

    private static void writePriceSheet(
            Sheet sheet, Map<String, List<ProductEntity>> sections,
            CellStyle header, CellStyle headerNew, CellStyle categoryTitle, CellStyle money, CellStyle moneyNew
    ) {
        Row head = sheet.createRow(0);
        String[] headers = {HEADER_ID, HEADER_CODE, HEADER_NAME, HEADER_CURRENT, HEADER_NEW};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(i == COL_NEW ? headerNew : header);
        }
        int rowIndex = 1;
        for (Map.Entry<String, List<ProductEntity>> section : sections.entrySet()) {
            // Renglón de título: sin ID ni precio nuevo, así que la subida lo ignora. El texto va en la columna
            // Código (no en ID) para no confundirse con un ID inválido.
            Row title = sheet.createRow(rowIndex++);
            for (int col = COL_ID; col <= COL_NEW; col++) {
                title.createCell(col).setCellStyle(categoryTitle);
            }
            title.getCell(COL_CODE).setCellValue(section.getKey().toUpperCase(Locale.ROOT)
                    + " (" + section.getValue().size() + ")");
            for (ProductEntity product : section.getValue()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(COL_ID).setCellValue(product.getId());
                row.createCell(COL_CODE).setCellValue(product.getCode() != null ? product.getCode() : "");
                row.createCell(COL_NAME).setCellValue(product.getName() != null ? product.getName() : "");
                Cell current = row.createCell(COL_CURRENT);
                current.setCellStyle(money);
                if (product.getSalePrice() != null) {
                    current.setCellValue(product.getSalePrice().doubleValue());
                }
                row.createCell(COL_NEW).setCellStyle(moneyNew);
            }
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, COL_NEW));
        sheet.setColumnWidth(COL_ID, 8 * 256);
        sheet.setColumnWidth(COL_CODE, 16 * 256);
        sheet.setColumnWidth(COL_NAME, 46 * 256);
        sheet.setColumnWidth(COL_CURRENT, 15 * 256);
        sheet.setColumnWidth(COL_NEW, 15 * 256);
    }

    // ------------------------------------------------------------------------------------------------
    // Subida
    // ------------------------------------------------------------------------------------------------

    @Transactional
    public ImportResult importTemplate(byte[] content, boolean apply) throws BusinessException {
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo está vacío.");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido (5 MB).");
        }
        Workbook workbook;
        try {
            workbook = WorkbookFactory.create(new ByteArrayInputStream(content));
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
        try (workbook) {
            return readWorkbook(workbook, apply);
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
    }

    private record Parsed(String sheet, int row, Long id, String code, BigDecimal newPrice) {}

    private ImportResult readWorkbook(Workbook workbook, boolean apply) throws BusinessException {
        List<RowError> errors = new ArrayList<>();
        List<Parsed> parsed = new ArrayList<>();
        List<String> sheetOrder = new ArrayList<>();
        int readRows = 0;
        int blankRows = 0;
        int totalRows = 0;
        // Se leen todas las hojas que traigan las columnas de la plantilla (una por categoría); las demás
        // (p. ej. "Instrucciones") se ignoran.
        for (Sheet sheet : workbook) {
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                continue;
            }
            Map<String, Integer> columns = headerColumns(header);
            Integer idCol = columns.get(normalize(HEADER_ID));
            Integer newCol = columns.get(normalize(HEADER_NEW));
            Integer codeCol = columns.get(normalize(HEADER_CODE));
            if (idCol == null || newCol == null) {
                continue;
            }
            sheetOrder.add(sheet.getSheetName());
            int lastRow = sheet.getLastRowNum();
            totalRows += lastRow - sheet.getFirstRowNum();
            if (totalRows > MAX_ROWS) {
                throw new BusinessException("El archivo tiene más de " + MAX_ROWS + " filas.");
            }
            for (int r = sheet.getFirstRowNum() + 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Cell idCell = row.getCell(idCol);
                Cell newCell = row.getCell(newCol);
                String codeText = codeCol != null ? text(row.getCell(codeCol)) : "";
                boolean idBlank = isBlank(idCell);
                boolean newBlank = isBlank(newCell);
                if (idBlank && newBlank) {
                    continue;
                }
                readRows++;
                String sheetName = sheet.getSheetName();
                int excelRow = r + 1;
                Long id;
                if (idBlank) {
                    errors.add(new RowError(sheetName, excelRow, null, codeText, "Fila sin ID de producto."));
                    continue;
                }
                try {
                    id = parseId(idCell);
                } catch (NumberFormatException e) {
                    errors.add(new RowError(sheetName, excelRow, null, codeText, "ID inválido: " + text(idCell)));
                    continue;
                }
                if (newBlank) {
                    blankRows++;
                    continue;
                }
                BigDecimal price;
                try {
                    price = parsePrice(newCell);
                } catch (NumberFormatException e) {
                    errors.add(new RowError(sheetName, excelRow, id, codeText,
                            "Precio nuevo inválido: " + text(newCell)));
                    continue;
                }
                if (price.compareTo(BigDecimal.ZERO) <= 0) {
                    errors.add(new RowError(sheetName, excelRow, id, codeText, "El precio nuevo debe ser mayor a 0."));
                    continue;
                }
                if (price.compareTo(MAX_PRICE) > 0) {
                    errors.add(new RowError(sheetName, excelRow, id, codeText, "El precio nuevo es demasiado grande."));
                    continue;
                }
                parsed.add(new Parsed(sheetName, excelRow, id, codeText, price));
            }
        }
        if (sheetOrder.isEmpty()) {
            throw new BusinessException("El archivo no tiene las columnas \"" + HEADER_ID + "\" y \""
                    + HEADER_NEW + "\". Use la plantilla descargada del sistema.");
        }

        Map<Long, ProductEntity> productsById = productRepository
                .findAllById(parsed.stream().map(Parsed::id).distinct().collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(ProductEntity::getId, Function.identity()));

        List<Change> changes = new ArrayList<>();
        List<PendingPrice> toSave = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int unchanged = 0;
        for (Parsed item : parsed) {
            ProductEntity product = productsById.get(item.id());
            if (product == null) {
                errors.add(new RowError(item.sheet(), item.row(), item.id(), item.code(),
                        "No existe un producto con ese ID."));
                continue;
            }
            if (!seen.add(item.id())) {
                errors.add(new RowError(item.sheet(), item.row(), item.id(), item.code(),
                        "El producto " + product.getCode() + " aparece más de una vez en el archivo."));
                continue;
            }
            if (!item.code().isEmpty() && !item.code().equalsIgnoreCase(safe(product.getCode()))) {
                errors.add(new RowError(item.sheet(), item.row(), item.id(), item.code(),
                        "El código no coincide con el ID (el ID " + item.id() + " es " + product.getCode()
                                + "). No edite las columnas ID ni Código."));
                continue;
            }
            BigDecimal current = product.getSalePrice();
            if (current != null && current.compareTo(item.newPrice()) == 0) {
                unchanged++;
                continue;
            }
            changes.add(new Change(product.getId(), product.getCode(), product.getName(), current, item.newPrice()));
            toSave.add(new PendingPrice(product, item.newPrice()));
        }
        errors.sort(Comparator
                .comparingInt((RowError e) -> sheetOrder.indexOf(e.sheet()))
                .thenComparingInt(RowError::row));

        boolean applied = apply && errors.isEmpty() && !toSave.isEmpty();
        if (applied) {
            Long userId = securityUtil.getCurrentUserId();
            // Las entidades son gestionadas: solo se modifican al aplicar, si no, la revisión también guardaría.
            toSave.forEach(pending -> {
                pending.product().setSalePrice(pending.newPrice());
                pending.product().setUpdatedBy(userId);
            });
            productRepository.saveAll(toSave.stream().map(PendingPrice::product).collect(Collectors.toList()));
            log.info("PRODUCT_PRICE_TEMPLATE_APPLIED userId={} products={} changes={}", userId, toSave.size(),
                    changes.stream()
                            .map(c -> c.code() + ":" + c.currentPrice() + "->" + c.newPrice())
                            .collect(Collectors.joining(", ")));
        }
        return new ImportResult(applied, readRows, blankRows, unchanged, changes, errors);
    }

    // ------------------------------------------------------------------------------------------------
    // Lectura de celdas
    // ------------------------------------------------------------------------------------------------

    private static Map<String, Integer> headerColumns(Row header) throws BusinessException {
        if (header == null) {
            throw new BusinessException("El archivo no tiene encabezados. Use la plantilla descargada del sistema.");
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : header) {
            String name = normalize(text(cell));
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private static boolean isBlank(Cell cell) {
        if (cell == null) {
            return true;
        }
        CellType type = effectiveType(cell);
        return type == CellType.BLANK || (type == CellType.STRING && text(cell).isEmpty());
    }

    private static CellType effectiveType(Cell cell) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (effectiveType(cell)) {
            case STRING:
                return cell.getStringCellValue() != null ? cell.getStringCellValue().trim() : "";
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return "";
                }
                return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private static Long parseId(Cell cell) {
        if (effectiveType(cell) == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value != Math.rint(value) || value <= 0) {
                throw new NumberFormatException("id");
            }
            return (long) value;
        }
        return Long.parseLong(text(cell).replace(" ", ""));
    }

    private static BigDecimal parsePrice(Cell cell) {
        CellType type = effectiveType(cell);
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (type != CellType.STRING) {
            throw new NumberFormatException("price");
        }
        return parsePriceText(text(cell));
    }

    /** Acepta "346", "346.50", "Q346.50", "1,183.00" y "346,50". */
    static BigDecimal parsePriceText(String raw) {
        String value = raw.replace("Q", "").replace("q", "").replace(' ', ' ').replace(" ", "");
        if (value.contains(",") && value.contains(".")) {
            value = value.replace(",", "");
        } else if (value.contains(",")) {
            value = value.matches("\\d+,\\d{1,2}") ? value.replace(',', '.') : value.replace(",", "");
        }
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String normalize(String value) {
        String noAccents = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}
