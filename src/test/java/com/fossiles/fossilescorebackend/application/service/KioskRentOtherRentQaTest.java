package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RentRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RowError;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.OtherRentConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OtherRentRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cases not covered by {@link KioskRentOtherRentTest}: uniqueness edges, inactive ids, kiosk
 * regression, Excel Tipo/template, and derived payment-day 31.
 */
@ExtendWith(MockitoExtension.class)
class KioskRentOtherRentQaTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final String[] NEW_HEADERS = {
            "Tipo", "ID", "Código", "Kiosco", "Nombre", "Categoría", "Descripción", "Monto",
            "Renta mensual (Q) - de Finanzas", "Arrendador", "Día de pago", "Contrato desde", "Contrato hasta", "Notas"
    };

    @Mock private KioskRentRepository rentRepository;
    @Mock private LocationRepository locationRepository;
    @Mock private KioskFixedCostRepository fixedCostRepository;
    @Mock private KioskRentAccessGuard guard;
    @Mock private OtherRentRepository otherRentRepository;

    private KioskRentService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new KioskRentService(rentRepository, locationRepository, fixedCostRepository, guard, otherRentRepository);
        lenient().when(guard.canEdit()).thenReturn(true);
        lenient().when(guard.assertCanEdit()).thenReturn(9L);
        lenient().when(rentRepository.findAll()).thenReturn(List.of());
        lenient().when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of());
        lenient().when(fixedCostRepository.findByCategoryWithLocation(KioskRentService.RENT_CATEGORY)).thenReturn(List.of());
        lenient().when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of());
        lenient().when(otherRentRepository.findActiveByCategoryAndName(anyString(), anyString())).thenReturn(List.of());
        lenient().when(otherRentRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            OtherRentEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(41L);
            }
            return entity;
        });
    }

    @Test
    void duplicateCheckIgnoresCaseAndSpacesAndOnlyLooksAtActiveRents() throws Exception {
        when(otherRentRepository.findActiveByCategoryAndName("OFICINA", "oficina")).thenReturn(List.of(
                other(7, "Oficina", "OFICINA", "10.00", null, null, null)));

        OtherRentConflictException spaced = assertThrows(OtherRentConflictException.class,
                () -> service.createOther(values("  oficina  ", null, "oficina", BigDecimal.ZERO, 15,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null)));
        assertEquals("Ya existe una renta activa de categoría OFICINA con el nombre «oficina».", spaced.getMessage());

        RentRow otherCategory = service.createOther(values("Oficina", null, "BODEGA", BigDecimal.ZERO, null, null, null, null));
        assertEquals("BODEGA", otherCategory.category());
        assertEquals("Oficina", otherCategory.name());

        when(otherRentRepository.findActiveByCategoryAndName("OFICINA", "Oficina")).thenReturn(List.of());
        RentRow afterSoftDelete = service.createOther(values("Oficina", null, "OFICINA", BigDecimal.ZERO, null, null, null, null));
        assertEquals(41L, afterSoftDelete.rentId());
        assertEquals("OFICINA", afterSoftDelete.category());
    }

    @Test
    void puttingANameAlreadyUsedByAnotherActiveRentIsConflict() throws Exception {
        OtherRentEntity current = other(5, "Vieja", "OFICINA", "10.00", "Ana", 20, null);
        when(otherRentRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(current));
        when(otherRentRepository.findActiveByCategoryAndName("OFICINA", "Oficina")).thenReturn(List.of(
                other(7, "Oficina", "OFICINA", "20.00", null, null, null)));

        OtherRentConflictException conflict = assertThrows(OtherRentConflictException.class,
                () -> service.updateOther(5L, values("Oficina", null, "OFICINA", new BigDecimal("10"), 20,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null)));
        assertEquals("Ya existe una renta activa de categoría OFICINA con el nombre «Oficina».", conflict.getMessage());
        verify(otherRentRepository, never()).saveAndFlush(any());
    }

    @Test
    void putAndDeleteRejectUnknownAndInactiveIds() {
        when(otherRentRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());
        when(otherRentRepository.findByIdAndActiveTrue(8L)).thenReturn(Optional.empty());

        assertEquals("No se encontró la renta.",
                assertThrows(BusinessException.class, () -> service.updateOther(99L, values("Oficina", null, "OFICINA",
                        null, null, null, null, null))).getMessage());
        assertEquals("No se encontró la renta.",
                assertThrows(BusinessException.class, () -> service.deleteOther(99L)).getMessage());
        assertEquals("No se encontró la renta.",
                assertThrows(BusinessException.class, () -> service.updateOther(8L, values("Oficina", null, "OFICINA",
                        null, null, null, null, null))).getMessage());
        assertEquals("No se encontró la renta.",
                assertThrows(BusinessException.class, () -> service.deleteOther(8L)).getMessage());
        verify(otherRentRepository, never()).save(any());
        verify(otherRentRepository, never()).saveAndFlush(any());
    }

    @Test
    void inactiveOtherRentIsHiddenFromListAndSummary() {
        LocationEntity alfa = kiosk(1, "A", "Alfa", false);
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(alfa));
        List<Object[]> finance = new ArrayList<>();
        finance.add(new Object[]{1L, new BigDecimal("1000.00"), 2026, 10});
        when(fixedCostRepository.findByCategoryWithLocation(KioskRentService.RENT_CATEGORY)).thenReturn(finance);
        OtherRentEntity active = other(10, "Oficina centro", "OFICINA", "500.00", "Beto", 18, null);
        OtherRentEntity inactive = other(11, "Bodega vieja", "BODEGA", "900.00", "Ana", 5, null);
        inactive.setActive(false);
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(active));

        ListResponse list = service.buildList(TODAY, null);

        assertEquals(1, list.summary().otherRentsTotal());
        assertEquals(new BigDecimal("500.00"), list.summary().otherMonthlyRent());
        assertEquals(new BigDecimal("1500.00"), list.summary().totalMonthlyRent());
        assertEquals(new BigDecimal("18000.00"), list.summary().totalAnnualRent());
        assertEquals(1, list.summary().kiosksTotal());
        assertTrue(list.rows().stream().noneMatch(row -> Long.valueOf(11L).equals(row.rentId())));
        assertTrue(list.rows().stream().noneMatch(row -> "Bodega vieja".equals(row.name())));
        assertEquals(1, list.rows().stream().filter(row -> KioskRentService.TYPE_OTHER.equals(row.rentType())).count());
    }

    @Test
    void otherRowClampsPaymentDay31InShortMonths() {
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(12, "Oficina febrero", "OFICINA", "100.00", "Ana", 31, null)));

        ListResponse list = service.buildList(LocalDate.of(2027, 2, 1), null);

        RentRow row = list.rows().get(0);
        assertEquals(KioskRentService.TYPE_OTHER, row.rentType());
        assertNull(row.locationId());
        assertNull(row.code());
        assertEquals(31, row.paymentDay());
        assertEquals(LocalDate.of(2027, 2, 28), row.nextPaymentDate());
        assertEquals(27, row.daysToNextPayment());
        assertEquals("VIGENTE", row.status());
        assertEquals(new BigDecimal("100.00"), row.monthlyRent());
    }

    @Test
    void listRentTypeFilterIsCaseInsensitiveAndLeavesSummaryAlone() throws Exception {
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(kiosk(1, "A", "Alfa", false), kiosk(3, "P", "Piloto", true)));
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(10, "Oficina centro", "OFICINA", "500.00", "Beto", 18, null)));

        ListResponse all = service.list(null);
        ListResponse onlyOther = service.list("other");
        ListResponse onlyKiosk = service.list("kiosk");

        assertEquals(all.summary(), onlyOther.summary());
        assertEquals(all.summary(), onlyKiosk.summary());
        assertEquals(1, onlyOther.rows().size());
        assertEquals(KioskRentService.TYPE_OTHER, onlyOther.rows().get(0).rentType());
        assertEquals(1, onlyKiosk.rows().size());
        assertEquals(KioskRentService.TYPE_KIOSK, onlyKiosk.rows().get(0).rentType());
        assertTrue(all.rows().stream().noneMatch(row -> "Piloto".equals(row.name())));
    }

    @Test
    void kioskSaveAndClearStayOnTheKioskAndRejectPilotPos() throws Exception {
        LocationEntity alfa = kiosk(1, "A", "Alfa", false);
        LocationEntity piloto = kiosk(3, "P", "Piloto", true);
        when(locationRepository.findById(1L)).thenReturn(Optional.of(alfa));
        when(locationRepository.findById(3L)).thenReturn(Optional.of(piloto));
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(alfa, piloto));
        when(rentRepository.findById(1L)).thenReturn(Optional.empty());
        when(rentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(10, "Oficina centro", "OFICINA", "500.00", "Beto", 18, null)));

        ListResponse saved = service.save(1L, new KioskRentService.Values("Ana", 10,
                LocalDate.of(2025, 1, 1), LocalDate.of(2027, 12, 31), "n"));

        verify(rentRepository).save(any(KioskRentEntity.class));
        verify(otherRentRepository, never()).saveAndFlush(any());
        assertTrue(saved.rows().stream().anyMatch(row -> KioskRentService.TYPE_OTHER.equals(row.rentType())));
        assertTrue(saved.rows().stream().anyMatch(row -> KioskRentService.TYPE_KIOSK.equals(row.rentType())));
        assertTrue(saved.rows().stream().noneMatch(row -> "Piloto".equals(row.name())));

        assertEquals("El kiosco indicado no existe.",
                assertThrows(BusinessException.class, () -> service.save(3L,
                        new KioskRentService.Values("X", 1, null, null, null))).getMessage());
        assertEquals("El kiosco indicado no existe.",
                assertThrows(BusinessException.class, () -> service.clear(3L)).getMessage());

        KioskRentEntity existing = kioskRent(1, "Ana", 10);
        when(rentRepository.findById(1L)).thenReturn(Optional.of(existing));
        service.clear(1L);
        verify(rentRepository).delete(existing);
        verify(otherRentRepository, never()).save(any());
    }

    @Test
    void excelBlankTipoIsKioskAndInvalidOtherRowCarriesTypeAndId() throws Exception {
        LocationEntity miraflores = kiosk(10, "M1", "Miraflores", false);
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(miraflores));
        when(rentRepository.findAll()).thenReturn(List.of(kioskRent(10, "Viejo", 5)));
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(5, "Bodega norte", "BODEGA", "100.00", "Ana", 3, "Fria")));
        byte[] file = xlsx(NEW_HEADERS, new Object[][]{
                {"", 10, "M1", "Miraflores", null, null, null, null, null, "Nuevo", null, null, null, null},
                {"OTRA", 5, null, null, "Bodega norte", "tienda", null, null, null, null, null, null, null, null}
        });

        ImportResult result = service.importTemplate(file, true);

        assertFalse(result.applied());
        assertEquals(1, result.changes().size());
        assertEquals(KioskRentService.TYPE_KIOSK, result.changes().get(0).rentType());
        assertNull(result.changes().get(0).rentId());
        assertEquals(10L, result.changes().get(0).locationId());
        assertEquals("Nuevo", result.changes().get(0).landlordName());
        assertEquals(1, result.errors().size());
        RowError error = result.errors().get(0);
        assertEquals(KioskRentService.TYPE_OTHER, error.rentType());
        assertEquals(5L, error.rentId());
        assertTrue(error.message().contains("categoría"));
        verify(rentRepository, never()).save(any());
        verify(otherRentRepository, never()).saveAndFlush(any());
    }

    @Test
    void templateIncludesTheNewColumnsAndSkipsPilotPos() throws Exception {
        LocationEntity alfa = kiosk(1, "A", "Alfa", false);
        LocationEntity piloto = kiosk(3, "P", "Piloto", true);
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(alfa, piloto));
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(10, "Oficina centro", "OFICINA", "500.00", "Beto", 18, "Piso 2")));

        byte[] bytes = service.buildTemplate();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("Rentas");
            Row head = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int i = 0; i < head.getLastCellNum(); i++) {
                headers.add(head.getCell(i).getStringCellValue());
            }
            assertEquals(List.of("Tipo", "ID", "Código", "Kiosco", "Nombre", "Categoría", "Descripción", "Monto",
                    "Renta mensual (Q) - de Finanzas", "Arrendador", "Día de pago", "Contrato desde", "Contrato hasta",
                    "Notas"), headers);
            assertEquals("KIOSCO", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Alfa", sheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("OTRA", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Oficina centro", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("OFICINA", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals("Piso 2", sheet.getRow(2).getCell(6).getStringCellValue());
            assertEquals(500.0, sheet.getRow(2).getCell(7).getNumericCellValue());
            assertEquals(2, sheet.getLastRowNum());
            assertTrue(workbook.getSheet("Instrucciones") != null);
        }
    }

    private static KioskRentService.OtherValues values(String name, String description, String category, BigDecimal amount,
                                                       Integer day, LocalDate start, LocalDate end, String note) {
        return new KioskRentService.OtherValues(name, description, category, "Ana", amount, day, start, end, note);
    }

    private static LocationEntity kiosk(long id, String code, String name, boolean pilot) {
        return LocationEntity.builder().id(id).code(code).name(name).categoria("KIOSKO").posTestMode(pilot).build();
    }

    private static KioskRentEntity kioskRent(long locationId, String landlord, int day) {
        return KioskRentEntity.builder()
                .locationId(locationId)
                .landlordName(landlord)
                .paymentDay(day)
                .contractStart(LocalDate.of(2025, 1, 1))
                .contractEnd(LocalDate.of(2027, 12, 31))
                .note("n")
                .source(KioskRentEntity.SOURCE_MANUAL)
                .build();
    }

    private static OtherRentEntity other(long id, String name, String category, String amount, String landlord, Integer day,
                                         String description) {
        return OtherRentEntity.builder()
                .id(id)
                .name(name)
                .category(category)
                .description(description)
                .monthlyAmount(amount == null ? null : new BigDecimal(amount))
                .landlordName(landlord)
                .paymentDay(day)
                .contractStart(day == null ? null : LocalDate.of(2025, 1, 1))
                .contractEnd(day == null ? null : LocalDate.of(2027, 12, 31))
                .note("vieja")
                .active(true)
                .source(OtherRentEntity.SOURCE_MANUAL)
                .build();
    }

    private static byte[] xlsx(String[] headers, Object[][] data) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Rentas");
            Row head = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                head.createCell(column).setCellValue(headers[column]);
            }
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int column = 0; column < data[r].length; column++) {
                    Object value = data[r][column];
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(column);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(value));
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
