package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductCategoryEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductCategoryRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.ProductRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.SecurityUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductPriceTemplateServiceTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductCategoryRepository productCategoryRepository;
    @Mock
    private SecurityUtil securityUtil;

    @InjectMocks
    private ProductPriceTemplateService service;

    private ProductEntity billetera;
    private ProductEntity cincho;
    private ProductEntity sinPrecio;

    @BeforeEach
    void setUp() {
        billetera = ProductEntity.builder().id(1L).code("B-1").name("BILLETERA LUCIANO").categoryId(2L)
                .salePrice(new BigDecimal("346.00")).build();
        cincho = ProductEntity.builder().id(2L).code("foss-est").name("CINCHO ESTEBAN").categoryId(15L)
                .salePrice(new BigDecimal("357.00")).build();
        sinPrecio = ProductEntity.builder().id(3L).code("AM-5").name("BILLETERA B-13 MINIMALISTA").categoryId(2L)
                .build();
        when(productRepository.findAllById(any())).thenAnswer(inv -> {
            List<ProductEntity> all = List.of(billetera, cincho, sinPrecio);
            Iterable<Long> ids = inv.getArgument(0);
            List<ProductEntity> found = new java.util.ArrayList<>();
            for (Long id : ids) {
                all.stream().filter(p -> p.getId().equals(id)).findFirst().ifPresent(found::add);
            }
            return found;
        });
        when(productCategoryRepository.findAll()).thenReturn(List.of(
                ProductCategoryEntity.builder().id(2L).name("BILLETERAS").build(),
                ProductCategoryEntity.builder().id(15L).name("CINCHOS").build()));
        when(securityUtil.getCurrentUserId()).thenReturn(99L);
    }

    @Test
    void buildTemplate_unaSolaHojaConCategoriasSeparadasPorTitulo() throws Exception {
        when(productRepository.findAll()).thenReturn(List.of(cincho, billetera, sinPrecio));

        byte[] bytes = service.buildTemplate(null);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("Precios");
            assertThat(workbook.getSheetName(1)).isEqualTo("Instrucciones");

            Sheet sheet = workbook.getSheet("Precios");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Código");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Producto");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Precio actual");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Precio nuevo");
            // Categorías en orden (BILLETERAS antes que CINCHOS), cada una con su título y sus productos por código.
            assertThat(sheet.getLastRowNum()).isEqualTo(5);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("BILLETERAS (2)");
            assertThat(sheet.getRow(1).getCell(0).getCellType().name()).isEqualTo("BLANK");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("AM-5");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("B-1");
            assertThat(sheet.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("BILLETERA LUCIANO");
            assertThat(sheet.getRow(3).getCell(3).getNumericCellValue()).isEqualTo(346.0);
            assertThat(sheet.getRow(3).getCell(4).getCellType().name()).isEqualTo("BLANK");
            // Sin precio actual la celda queda vacía.
            assertThat(sheet.getRow(2).getCell(3).getCellType().name()).isEqualTo("BLANK");
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("CINCHOS (1)");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("foss-est");
        }
    }

    @Test
    void buildTemplate_noIncluyeEmpaqueNiCinchosDeMarcasDeEntrecueros() throws Exception {
        ProductEntity empaque = ProductEntity.builder().id(10L).code("SUM-CAJA-B-FOSS").name("CAJA BILLETERA")
                .categoryId(1L).salePrice(BigDecimal.ZERO).build();
        ProductEntity entrecueros = ProductEntity.builder().id(11L).code("L-19").name("CINCHO CASUAL LEVIS")
                .categoryId(15L).salePrice(new BigDecimal("110.00")).entrecuerosEnabled(true).cinchoType("CASUAL").build();
        // Billetera con precio Entrecueros que también se vende en kioscos normales: sí va.
        ProductEntity billeteraEntrecueros = ProductEntity.builder().id(12L).code("B-14").name("BILLETERA SANTIAGO")
                .categoryId(2L).salePrice(new BigDecimal("346.00")).entrecuerosEnabled(true).build();
        when(productRepository.findAll()).thenReturn(
                List.of(cincho, billetera, empaque, entrecueros, billeteraEntrecueros));
        when(productCategoryRepository.findAll()).thenReturn(List.of(
                ProductCategoryEntity.builder().id(1L).name("ACCESORIOS").build(),
                ProductCategoryEntity.builder().id(2L).name("BILLETERAS").build(),
                ProductCategoryEntity.builder().id(15L).name("CINCHOS").build()));

        byte[] bytes = service.buildTemplate(null);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            List<String> codes = new java.util.ArrayList<>();
            List<String> titles = new java.util.ArrayList<>();
            Sheet sheet = workbook.getSheet("Precios");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                String text = row.getCell(1).getStringCellValue();
                (row.getCell(0).getCellType().name().equals("BLANK") ? titles : codes).add(text);
            }
            // ACCESORIOS solo tenía empaque: no genera título. CINCHOS solo trae el que no es de Entrecueros.
            assertThat(titles).containsExactly("BILLETERAS (2)", "CINCHOS (1)");
            assertThat(codes).containsExactly("B-1", "B-14", "foss-est");
        }
    }

    @Test
    void buildTemplate_sinProductosDeKioscosFalla() {
        ProductEntity empaque = ProductEntity.builder().id(10L).code("SUM-CAJA-B-FOSS").name("CAJA BILLETERA")
                .categoryId(1L).build();
        when(productRepository.findAll()).thenReturn(List.of(empaque));

        assertThatThrownBy(() -> service.buildTemplate(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void buildTemplate_soloLasCategoriasElegidas() throws Exception {
        when(productRepository.findByCategoryId(2L)).thenReturn(List.of(billetera, sinPrecio));
        when(productRepository.findByCategoryId(15L)).thenReturn(List.of(cincho));

        byte[] bytes = service.buildTemplate(List.of(2L, 2L), "kiosco");

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Precios");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("BILLETERAS (2)");
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("B-1");
        }
    }

    @Test
    void buildTemplate_entrecuerosEsOtraPlantillaYUnaFilaPorProducto() throws Exception {
        ProductEntity empaque = ProductEntity.builder().id(10L).code("SUM-CAJA").name("CAJA")
                .categoryId(1L).entrecuerosEnabled(true).entrecuerosPriceUnit(new BigDecimal("5.00")).build();
        ProductEntity cinchoEc = ProductEntity.builder().id(11L).code("L-19").name("CINCHO CASUAL LEVIS")
                .categoryId(15L).salePrice(new BigDecimal("110.00")).entrecuerosEnabled(true).cinchoType("CASUAL")
                .entrecuerosPriceUnit(new BigDecimal("100.00")).entrecuerosPriceQty3(new BigDecimal("90.00"))
                .entrecuerosPriceQty6(new BigDecimal("80.00")).entrecuerosPriceQty12(new BigDecimal("75.00")).build();
        ProductEntity billeteraEc = ProductEntity.builder().id(12L).code("B-14").name("BILLETERA SANTIAGO")
                .categoryId(2L).salePrice(new BigDecimal("346.00")).entrecuerosEnabled(true)
                .entrecuerosPriceUnit(new BigDecimal("100.00")).build();
        when(productRepository.findByCategoryId(2L)).thenReturn(List.of(billeteraEc, billetera));
        when(productRepository.findByCategoryId(15L)).thenReturn(List.of(cinchoEc, cincho, empaque));
        when(productCategoryRepository.findAll()).thenReturn(List.of(
                ProductCategoryEntity.builder().id(1L).name("ACCESORIOS").build(),
                ProductCategoryEntity.builder().id(2L).name("BILLETERAS").build(),
                ProductCategoryEntity.builder().id(15L).name("CINCHOS").build()));

        byte[] bytes = service.buildTemplate(List.of(2L, 15L), "entrecueros");

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Precios");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Precio 1 actual");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Precio 1 nuevo");
            assertThat(header.getCell(10).getStringCellValue()).isEqualTo("Precio 12 nuevo");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("BILLETERAS (1)");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("B-14");
            assertThat(sheet.getRow(2).getCell(4).getCellType().name()).isEqualTo("BLANK");
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("CINCHOS (1)");
            Row cinchoRow = sheet.getRow(4);
            assertThat(cinchoRow.getCell(1).getStringCellValue()).isEqualTo("L-19");
            assertThat(cinchoRow.getCell(3).getNumericCellValue()).isEqualTo(100.0);
            assertThat(cinchoRow.getCell(5).getNumericCellValue()).isEqualTo(90.0);
            assertThat(cinchoRow.getCell(7).getNumericCellValue()).isEqualTo(80.0);
            assertThat(cinchoRow.getCell(9).getNumericCellValue()).isEqualTo(75.0);
            assertThat(workbook.getSheet("Instrucciones").getRow(2).getCell(0).getStringCellValue())
                    .contains("variantes");
        }
    }

    @Test
    void import_entrecuerosActualizaSoloLasEscalasEscritas() throws Exception {
        cincho.setCode("L-19");
        cincho.setName("CINCHO CASUAL LEVIS");
        cincho.setSalePrice(new BigDecimal("110.00"));
        cincho.setEntrecuerosEnabled(true);
        cincho.setCinchoType("CASUAL");
        cincho.setEntrecuerosPriceUnit(new BigDecimal("100.00"));
        cincho.setEntrecuerosPriceQty3(new BigDecimal("90.00"));
        cincho.setEntrecuerosPriceQty6(new BigDecimal("80.00"));
        cincho.setEntrecuerosPriceQty12(new BigDecimal("75.00"));
        when(productRepository.findByCategoryId(15L)).thenReturn(List.of(cincho));
        when(productCategoryRepository.findAll()).thenReturn(List.of(
                ProductCategoryEntity.builder().id(15L).name("CINCHOS").build()));
        byte[] template = service.buildTemplate(List.of(15L), "entrecueros");
        byte[] filled;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Row row = workbook.getSheet("Precios").getRow(2);
            row.getCell(4).setCellValue(120.0);
            row.getCell(8).setCellValue(70.0);
            workbook.write(out);
            filled = out.toByteArray();
        }

        ProductPriceTemplateService.ImportResult result = service.importTemplate(filled, true);

        assertThat(result.errors()).isEmpty();
        assertThat(result.applied()).isTrue();
        assertThat(result.changes()).extracting(ProductPriceTemplateService.Change::price).containsExactly("1", "6");
        assertThat(cincho.getEntrecuerosPriceUnit()).isEqualByComparingTo("120.00");
        assertThat(cincho.getEntrecuerosPriceQty3()).isEqualByComparingTo("90.00");
        assertThat(cincho.getEntrecuerosPriceQty6()).isEqualByComparingTo("70.00");
        assertThat(cincho.getEntrecuerosPriceQty12()).isEqualByComparingTo("75.00");
        assertThat(cincho.getSalePrice()).isEqualByComparingTo("110.00");
    }

    @Test
    void import_entrecuerosRechazaElProductoQueNoEstaHabilitado() throws Exception {
        byte[] file = entrecuerosFile(2L, "foss-est", "CINCHO ESTEBAN", 80.0);

        ProductPriceTemplateService.ImportResult result = service.importTemplate(file, true);

        assertThat(result.applied()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("no está habilitado");
        verify(productRepository, never()).saveAll(any());
        assertThat(cincho.getSalePrice()).isEqualByComparingTo("357.00");
    }

    @Test
    void buildTemplate_filtraPorCategoria() throws Exception {
        when(productRepository.findByCategoryId(15L)).thenReturn(List.of(cincho));

        byte[] bytes = service.buildTemplate(15L);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Precios");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("CINCHOS (1)");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("foss-est");
        }
    }

    @Test
    void import_laPlantillaDescargadaSeSubeSinErroresIgnorandoLosTitulosDeCategoria() throws Exception {
        when(productRepository.findAll()).thenReturn(List.of(cincho, billetera, sinPrecio));
        byte[] template = service.buildTemplate(null);

        // Sin llenar nada: sin errores ni cambios (los renglones de título no cuentan).
        ProductPriceTemplateService.ImportResult empty = service.importTemplate(template, true);
        assertThat(empty.errors()).isEmpty();
        assertThat(empty.changes()).isEmpty();
        assertThat(empty.readRows()).isEqualTo(3);
        assertThat(empty.blankRows()).isEqualTo(3);

        // Llenando un precio nuevo en el renglón de B-1.
        byte[] filled;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.getSheet("Precios").getRow(3).getCell(4).setCellValue(399.0);
            workbook.write(out);
            filled = out.toByteArray();
        }
        ProductPriceTemplateService.ImportResult result = service.importTemplate(filled, false);
        assertThat(result.errors()).isEmpty();
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).code()).isEqualTo("B-1");
        assertThat(result.changes().get(0).newPrice()).isEqualByComparingTo("399.00");
    }

    @Test
    void import_leeTodasLasHojasYUbicaLosErroresPorHoja() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fillSheet(workbook.createSheet("BILLETERAS"), row(1, "B-1", "BILLETERA LUCIANO", "346.00", 399.0));
            fillSheet(workbook.createSheet("CINCHOS"),
                    row(2, "foss-est", "CINCHO ESTEBAN", "357.00", 400.0),
                    row(1, "B-1", "BILLETERA LUCIANO", "346.00", 410.0));
            workbook.createSheet("Instrucciones").createRow(0).createCell(0).setCellValue("texto");
            workbook.write(out);

            ProductPriceTemplateService.ImportResult result = service.importTemplate(out.toByteArray(), true);

            assertThat(result.applied()).isFalse();
            assertThat(result.changes()).hasSize(2);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.errors().get(0).sheet()).isEqualTo("CINCHOS");
            assertThat(result.errors().get(0).row()).isEqualTo(3);
            assertThat(result.errors().get(0).message()).contains("más de una vez");
        }
    }

    @Test
    void import_revisarNoGuardaYSeparaCambiosSinCambioYVacios() throws Exception {
        byte[] file = workbook(
                row(1, "B-1", "BILLETERA LUCIANO", "346.00", 399.0),
                row(2, "foss-est", "CINCHO ESTEBAN", "357.00", 357.0),
                row(3, "AM-5", "BILLETERA B-13 MINIMALISTA", null, null));

        ProductPriceTemplateService.ImportResult result = service.importTemplate(file, false);

        assertThat(result.applied()).isFalse();
        assertThat(result.errors()).isEmpty();
        assertThat(result.readRows()).isEqualTo(3);
        assertThat(result.unchangedRows()).isEqualTo(1);
        assertThat(result.blankRows()).isEqualTo(1);
        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).code()).isEqualTo("B-1");
        assertThat(result.changes().get(0).currentPrice()).isEqualByComparingTo("346.00");
        assertThat(result.changes().get(0).newPrice()).isEqualByComparingTo("399.00");
        verify(productRepository, never()).saveAll(any());
        assertThat(billetera.getSalePrice()).isEqualByComparingTo("346.00");
    }

    @Test
    void import_aplicarGuardaPrecioNuevoYUsuario() throws Exception {
        byte[] file = workbook(
                row(1, "B-1", "BILLETERA LUCIANO", "346.00", 399.5),
                row(3, "AM-5", "BILLETERA B-13 MINIMALISTA", null, 208.0));

        ProductPriceTemplateService.ImportResult result = service.importTemplate(file, true);

        assertThat(result.applied()).isTrue();
        assertThat(result.changes()).hasSize(2);
        ArgumentCaptor<List<ProductEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(productRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(billetera.getSalePrice()).isEqualByComparingTo("399.50");
        assertThat(sinPrecio.getSalePrice()).isEqualByComparingTo("208.00");
        assertThat(billetera.getUpdatedBy()).isEqualTo(99L);
    }

    @Test
    void import_conErroresNoAplicaNada() throws Exception {
        byte[] file = workbook(
                row(1, "B-1", "BILLETERA LUCIANO", "346.00", 399.0),
                row(2, "OTRO", "CINCHO ESTEBAN", "357.00", 400.0),
                row(77, "X-1", "NO EXISTE", "10.00", 10.0),
                row(3, "AM-5", "BILLETERA B-13 MINIMALISTA", null, "abc"),
                row(1, "B-1", "BILLETERA LUCIANO", "346.00", 410.0));

        ProductPriceTemplateService.ImportResult result = service.importTemplate(file, true);

        assertThat(result.applied()).isFalse();
        assertThat(result.errors()).extracting(ProductPriceTemplateService.RowError::row)
                .containsExactly(3, 4, 5, 6);
        assertThat(result.errors().get(0).message()).contains("no coincide");
        assertThat(result.errors().get(1).message()).contains("No existe");
        assertThat(result.errors().get(2).message()).contains("inválido");
        assertThat(result.errors().get(3).message()).contains("más de una vez");
        verify(productRepository, never()).saveAll(any());
        assertThat(billetera.getSalePrice()).isEqualByComparingTo("346.00");
    }

    @Test
    void import_precioCeroONegativoEsError() throws Exception {
        byte[] file = workbook(
                row(1, "B-1", "BILLETERA LUCIANO", "346.00", 0.0),
                row(2, "foss-est", "CINCHO ESTEBAN", "357.00", -5.0));

        ProductPriceTemplateService.ImportResult result = service.importTemplate(file, true);

        assertThat(result.applied()).isFalse();
        assertThat(result.errors()).hasSize(2);
    }

    @Test
    void import_archivoQueNoEsExcelFalla() {
        assertThatThrownBy(() -> service.importTemplate("no soy excel".getBytes(), false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void import_sinColumnasDeLaPlantillaFalla() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("otra cosa");
            workbook.write(out);
            assertThatThrownBy(() -> service.importTemplate(out.toByteArray(), false))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Precio nuevo");
        }
    }

    @Test
    void parsePriceText_aceptaFormatosComunes() {
        assertThat(ProductPriceTemplateService.parsePriceText("346")).isEqualByComparingTo("346.00");
        assertThat(ProductPriceTemplateService.parsePriceText("Q346.50")).isEqualByComparingTo("346.50");
        assertThat(ProductPriceTemplateService.parsePriceText("1,183.00")).isEqualByComparingTo("1183.00");
        assertThat(ProductPriceTemplateService.parsePriceText("346,50")).isEqualByComparingTo("346.50");
        assertThat(ProductPriceTemplateService.parsePriceText("1,183")).isEqualByComparingTo("1183.00");
        assertThatThrownBy(() -> ProductPriceTemplateService.parsePriceText("abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    private static byte[] entrecuerosFile(long id, String code, String name, Double price1) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Precios");
            Row header = sheet.createRow(0);
            String[] titles = {
                    "ID", "Código", "Producto",
                    "Precio 1 actual", "Precio 1 nuevo",
                    "Precio 3 actual", "Precio 3 nuevo",
                    "Precio 6 actual", "Precio 6 nuevo",
                    "Precio 12 actual", "Precio 12 nuevo"
            };
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(id);
            row.createCell(1).setCellValue(code);
            row.createCell(2).setCellValue(name);
            row.createCell(4).setCellValue(price1);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static Object[] row(long id, String code, String name, String current, Object newPrice) {
        return new Object[] {id, code, name, current, newPrice};
    }

    private static byte[] workbook(Object[]... rows) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fillSheet(workbook.createSheet("Precios"), rows);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void fillSheet(Sheet sheet, Object[]... rows) {
        Row header = sheet.createRow(0);
        String[] titles = {"ID", "Código", "Producto", "Precio actual", "Precio nuevo"};
        for (int i = 0; i < titles.length; i++) {
            header.createCell(i).setCellValue(titles[i]);
        }
        int index = 1;
        for (Object[] values : rows) {
            Row row = sheet.createRow(index++);
            row.createCell(0).setCellValue(((Long) values[0]).doubleValue());
            row.createCell(1).setCellValue((String) values[1]);
            row.createCell(2).setCellValue((String) values[2]);
            if (values[3] != null) {
                row.createCell(3).setCellValue(Double.parseDouble((String) values[3]));
            }
            if (values[4] instanceof Double) {
                row.createCell(4).setCellValue((Double) values[4]);
            } else if (values[4] != null) {
                row.createCell(4).setCellValue(String.valueOf(values[4]));
            }
        }
    }
}
