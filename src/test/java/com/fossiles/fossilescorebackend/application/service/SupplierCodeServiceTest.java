package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupplierCodeServiceTest {

    @Test
    void normalizaLoQueSeEscribe() {
        assertEquals("PV013", SupplierCodeService.normalize("pv13"));
        assertEquals("PV013", SupplierCodeService.normalize("13"));
        assertEquals("PV013", SupplierCodeService.normalize(" PV-013 "));
        assertEquals("PV1000", SupplierCodeService.normalize("PV1000"));
        assertNull(SupplierCodeService.normalize("PV0"));
        assertNull(SupplierCodeService.normalize("AB12"));
        assertNull(SupplierCodeService.normalize(""));
        assertEquals(13, SupplierCodeService.numberOf("PV013"));
        assertEquals(-1, SupplierCodeService.numberOf(null));
    }

    private static SupplierEntity supplier(long id, String code, String name, String nit) {
        return SupplierEntity.builder().id(id).code(code).name(name).nit(nit).build();
    }

    private static Object row(int row, String code, String name, String nit) throws Exception {
        Class<?> type = Class.forName(SupplierCodeService.class.getName() + "$ParsedRow");
        Constructor<?> c = type.getDeclaredConstructors()[0];
        c.setAccessible(true);
        return c.newInstance(row, SupplierCodeService.normalize(code), code, name, nit, "");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void revisaCadaFila() throws Exception {
        var builder = new SupplierCodeService.ImportPreviewBuilder(List.of(
                supplier(1, "PV001", "GUATEX, S.A.", "512334-1"),
                supplier(2, "PV002", "CRISMA", null),
                supplier(3, "PV003", "INDUSTRIAS GALVEZ", null),
                supplier(4, "PV017", "PELETERIA CHAVEZ", "9088124")));
        List parsed = List.of(
                row(2, "PV013", "GUATEX / TEASA", "5123341"),          // mismo NIT
                row(3, "PV078", "CRISMA", ""),                         // mismo nombre
                row(4, "PV017", "INDUSTRIAS GALVEZ S.A.", ""),         // parecido → revisar
                row(5, "PV040", "PELETERIA CHAVEZ", "908812-4"),       // mismo NIT
                row(6, "PV074", "EMPRESA ELECTRICA ALTA MAR", ""),     // nuevo
                row(7, "PV074", "OTRA", ""),                           // código repetido
                row(8, "XX", "MAL", ""));                              // código inválido
        var preview = builder.build(parsed);
        var rows = preview.rows();
        assertEquals(SupplierCodeService.ST_OK, rows.get(0).status());
        assertEquals(1L, rows.get(0).supplierId());
        assertEquals(SupplierCodeService.ST_OK, rows.get(1).status());
        assertEquals(2L, rows.get(1).supplierId());
        assertEquals(SupplierCodeService.ST_REVIEW, rows.get(2).status());
        assertEquals(3L, rows.get(2).supplierId());
        assertEquals(SupplierCodeService.ST_OK, rows.get(3).status());
        assertEquals(SupplierCodeService.ST_ERROR, rows.get(4).status());
        assertEquals(SupplierCodeService.ST_ERROR, rows.get(5).status());
        assertEquals(SupplierCodeService.ST_ERROR, rows.get(6).status());
    }

    @Test
    void vencimientoDesdeLaFactura() {
        SupplierEntity s = SupplierEntity.builder().id(1L).creditDays(32).build();
        LocalDate received = LocalDate.of(2026, 9, 25);
        assertEquals(LocalDate.of(2026, 10, 24), SupplierPaymentService.dueDateOf(
                new SupplierPaymentService.InvoiceInfo("A-539", LocalDate.of(2026, 9, 22)), received, s));
        assertEquals(LocalDate.of(2026, 10, 27), SupplierPaymentService.dueDateOf(null, received, s));
        assertEquals("A-539", SupplierPaymentService.invoiceLabel("A", "539"));
        assertEquals("539", SupplierPaymentService.invoiceLabel(" ", "539"));
    }
}
