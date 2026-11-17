package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.Change;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RentRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RowError;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.OtherRentConflictException;
import com.fossiles.fossilescorebackend.infrastructure.config.GlobalExceptionHandler;
import com.fossiles.fossilescorebackend.infrastructure.controller.KioskRentController;
import com.fossiles.fossilescorebackend.infrastructure.controller.KioskRentController.OtherSaveRequest;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OtherRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KioskRentOtherRentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final String[] OLD_HEADERS = {
            "ID", "Código", "Kiosco", "Renta mensual (Q) - de Finanzas",
            "Arrendador", "Día de pago", "Contrato desde", "Contrato hasta", "Notas"
    };
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
        lenient().when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY)).thenReturn(List.of());
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
    void summaryIncludesOtherRentsAndTheFilterDoesNotChangeIt() throws Exception {
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY)).thenReturn(List.of(
                kiosk(1, "A", "Alfa", false),
                kiosk(3, "P", "Piloto", true),
                kiosk(2, "B", "Beta", false)));
        List<Object[]> finance = new ArrayList<>();
        finance.add(new Object[]{1L, new BigDecimal("1000.00"), 2026, 10});
        finance.add(new Object[]{3L, new BigDecimal("9999.00"), 2026, 10});
        when(fixedCostRepository.findByCategoryWithLocation(KioskRentService.RENT_CATEGORY)).thenReturn(finance);
        when(rentRepository.findAll()).thenReturn(List.of(kioskRent(1, "Ana", 20)));
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(11, "Bodega sur", "BODEGA", "250.50", "Ana", 5, null),
                other(13, "Local sin monto", "OTRO", null, null, 16, null),
                other(10, "Oficina centro", "OFICINA", "500.00", "Beto", 18, "Piso 2")));

        ListResponse all = service.buildList(TODAY, null);
        ListResponse onlyOther = service.buildList(TODAY, KioskRentService.TYPE_OTHER);
        ListResponse onlyKiosk = service.buildList(TODAY, KioskRentService.TYPE_KIOSK);

        assertEquals(new BigDecimal("1750.50"), all.summary().totalMonthlyRent());
        assertEquals(new BigDecimal("21006.00"), all.summary().totalAnnualRent());
        assertEquals(new BigDecimal("750.50"), all.summary().otherMonthlyRent());
        assertEquals(3, all.summary().otherRentsTotal());
        assertEquals(2, all.summary().kiosksTotal());
        assertEquals(1, all.summary().kiosksWithRent());
        assertEquals(3, all.summary().paymentsNext7Days());
        assertEquals(new BigDecimal("1500.00"), all.summary().paymentsNext7DaysAmount());
        assertEquals(3, all.summary().countByStatus().get("VIGENTE"));
        assertEquals(1, all.summary().countByStatus().get("INCOMPLETO"));
        assertEquals(1, all.summary().countByStatus().get("SIN_CARGAR"));
        assertEquals(0, all.summary().countByStatus().get("POR_VENCER"));
        assertEquals(List.of("Ana", "Beto"), all.landlords());
        assertEquals(all.summary(), onlyOther.summary());
        assertEquals(all.summary(), onlyKiosk.summary());
        assertEquals(List.of("Ana", "Beto"), onlyKiosk.landlords());

        assertEquals(5, all.rows().size());
        assertEquals(2, onlyKiosk.rows().size());
        assertEquals(3, onlyOther.rows().size());
        assertTrue(all.rows().stream().noneMatch(row -> "Piloto".equals(row.name())));

        RentRow alfa = all.rows().get(0);
        assertEquals(KioskRentService.TYPE_KIOSK, alfa.rentType());
        assertNull(alfa.rentId());
        assertNull(alfa.category());
        assertNull(alfa.description());
        assertEquals(1L, alfa.locationId());
        assertEquals("A", alfa.code());
        assertEquals(new BigDecimal("1000.00"), alfa.monthlyRent());
        assertEquals("2026-10", alfa.rentPeriod());

        RentRow oficina = all.rows().get(4);
        assertEquals(KioskRentService.TYPE_OTHER, oficina.rentType());
        assertEquals(10L, oficina.rentId());
        assertNull(oficina.locationId());
        assertNull(oficina.code());
        assertNull(oficina.rentPeriod());
        assertEquals("Oficina centro", oficina.name());
        assertEquals("OFICINA", oficina.category());
        assertEquals("Piso 2", oficina.description());
        assertEquals(new BigDecimal("500.00"), oficina.monthlyRent());
        assertEquals("VIGENTE", oficina.status());
        assertEquals(LocalDate.of(2026, 11, 18), oficina.nextPaymentDate());
        assertEquals(2, oficina.daysToNextPayment());
        assertNotNull(oficina.daysToContractEnd());
        assertNotNull(oficina.contractProgressPct());

        BusinessException invalid = assertThrows(BusinessException.class, () -> service.list("BODEGA"));
        assertEquals("rentType debe ser KIOSK u OTHER.", invalid.getMessage());
    }

    @Test
    void createAndUpdateValidateMoneyAndRejectDuplicates() throws Exception {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        assertInvalid(values("  ", null, "OFICINA", null, null, null, null, null), "El nombre es obligatorio.");
        assertInvalid(values("Oficina", null, "tienda", null, null, null, null, null),
                "La categoría debe ser OFICINA, BODEGA u OTRO.");
        assertInvalid(values("Oficina", null, null, null, null, null, null, null), "La categoría es obligatoria.");
        assertInvalid(values("Oficina", null, "OFICINA", new BigDecimal("-1"), null, null, null, null),
                "El monto no puede ser negativo.");
        assertInvalid(values("Oficina", null, "OFICINA", new BigDecimal("1.234"), null, null, null, null),
                "El monto solo admite 2 decimales.");
        assertInvalid(values("Oficina", null, "OFICINA", null, 0, null, null, null),
                "El día de pago debe estar entre 1 y 31.");
        assertInvalid(values("Oficina", null, "OFICINA", null, 32, null, null, null),
                "El día de pago debe estar entre 1 y 31.");
        assertInvalid(values("Oficina", null, "OFICINA", null, 5, end, start, null),
                "«Contrato hasta» no puede ser anterior a «Contrato desde».");
        verify(otherRentRepository, never()).saveAndFlush(any());

        when(otherRentRepository.findActiveByCategoryAndName("OFICINA", "oficina")).thenReturn(List.of(
                other(7, "Oficina", "OFICINA", "10.00", null, null, null)));
        OtherRentConflictException duplicate = assertThrows(OtherRentConflictException.class,
                () -> service.createOther(values("oficina", "Piso", "oficina", BigDecimal.ZERO, 15, start, end, "nota")));
        assertEquals("Ya existe una renta activa de categoría OFICINA con el nombre «oficina».", duplicate.getMessage());

        OtherRentEntity current = other(5, "Oficina", "OFICINA", "10.00", "Ana", 20, null);
        current.setContractStart(start);
        current.setContractEnd(end);
        when(otherRentRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(current));
        when(otherRentRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());
        assertEquals("No se encontró la renta.",
                assertThrows(BusinessException.class, () -> service.updateOther(99L, values("Oficina", null, "OFICINA", null, null, null, null, null))).getMessage());

        RentRow updated = service.updateOther(5L, values(" Oficina ", "Nueva", "oficina", new BigDecimal("20"), 20, start, end, "  "));
        assertEquals(5L, updated.rentId());
        assertEquals(KioskRentService.TYPE_OTHER, updated.rentType());
        assertEquals(new BigDecimal("20.00"), updated.monthlyRent());
        assertEquals("Oficina", updated.name());
        assertEquals("OFICINA", updated.category());
        assertEquals("Nueva", updated.description());
        assertNull(updated.note());
        assertNull(updated.locationId());
        assertEquals(KioskRentCalculator.status("Ana", 20, start, end, GuatemalaDateTime.today()), updated.status());
        assertEquals(KioskRentCalculator.nextPaymentDate(20, start, end, GuatemalaDateTime.today()), updated.nextPaymentDate());
        assertNotNull(updated.daysToNextPayment());
        assertNotNull(updated.daysToContractEnd());
        assertNotNull(updated.contractProgressPct());

        RentRow created = service.createOther(values("Bodega", null, "bodega", new BigDecimal("0"), null, null, null, null));
        assertEquals(41L, created.rentId());
        assertEquals("BODEGA", created.category());
        assertEquals(new BigDecimal("0.00"), created.monthlyRent());
        assertEquals("INCOMPLETO", created.status());
        assertNull(created.nextPaymentDate());
    }

    @Test
    void deleteDeactivatesTheRent() throws Exception {
        OtherRentEntity current = other(5, "Oficina", "OFICINA", "10.00", null, null, null);
        when(otherRentRepository.findByIdAndActiveTrue(5L)).thenReturn(Optional.of(current));

        service.deleteOther(5L);

        assertFalse(current.isActive());
        assertEquals(9L, current.getUpdatedBy());
        verify(otherRentRepository).save(current);
    }

    @Test
    void excelImportsOtherRentCreateAndUpdate() throws Exception {
        when(otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()).thenReturn(List.of(
                other(5, "Bodega norte", "BODEGA", "100.00", "Ana", 3, "Fria")));
        byte[] file = xlsx(NEW_HEADERS, new Object[][]{
                {"OTRA", null, null, null, "Oficina zona 4", "oficina", "Recepción", 3500, null, "Inmobiliaria", 10, "1/1/2026", "31/12/2027", "nueva"},
                {"OTRA", 5, null, null, null, null, null, 200, null, null, null, null, null, null}
        });

        ImportResult result = service.importTemplate(file, true);

        assertTrue(result.applied());
        assertTrue(result.errors().isEmpty());
        assertEquals(2, result.changes().size());
        Change created = result.changes().get(0);
        assertEquals("NEW", created.kind());
        assertEquals(KioskRentService.TYPE_OTHER, created.rentType());
        assertNull(created.rentId());
        assertNull(created.locationId());
        assertEquals("Oficina zona 4", created.name());
        assertEquals("Inmobiliaria", created.landlordName());
        assertEquals(10, created.paymentDay());
        Change updated = result.changes().get(1);
        assertEquals("UPDATE", updated.kind());
        assertEquals(KioskRentService.TYPE_OTHER, updated.rentType());
        assertEquals(5L, updated.rentId());
        assertEquals("Bodega norte", updated.name());
        assertEquals("Ana", updated.landlordName());
        assertEquals(3, updated.paymentDay());

        ArgumentCaptor<OtherRentEntity> saved = ArgumentCaptor.forClass(OtherRentEntity.class);
        verify(otherRentRepository, times(2)).saveAndFlush(saved.capture());
        OtherRentEntity newRent = saved.getAllValues().get(0);
        assertEquals("Oficina zona 4", newRent.getName());
        assertEquals("OFICINA", newRent.getCategory());
        assertEquals("Recepción", newRent.getDescription());
        assertEquals(new BigDecimal("3500.00"), newRent.getMonthlyAmount());
        assertEquals("Inmobiliaria", newRent.getLandlordName());
        assertEquals(10, newRent.getPaymentDay());
        assertEquals(LocalDate.of(2026, 1, 1), newRent.getContractStart());
        assertEquals(LocalDate.of(2027, 12, 31), newRent.getContractEnd());
        assertEquals("nueva", newRent.getNote());
        assertEquals(OtherRentEntity.SOURCE_EXCEL, newRent.getSource());
        assertTrue(newRent.isActive());
        assertEquals(9L, newRent.getCreatedBy());

        OtherRentEntity existing = saved.getAllValues().get(1);
        assertEquals(5L, existing.getId());
        assertEquals("Bodega norte", existing.getName());
        assertEquals("BODEGA", existing.getCategory());
        assertEquals("Fria", existing.getDescription());
        assertEquals(new BigDecimal("200.00"), existing.getMonthlyAmount());
        assertEquals("Ana", existing.getLandlordName());
        assertEquals(3, existing.getPaymentDay());
        assertEquals("vieja", existing.getNote());
        assertEquals(OtherRentEntity.SOURCE_EXCEL, existing.getSource());
    }

    @Test
    void excelOldTemplateStillUpdatesKiosks() throws Exception {
        LocationEntity miraflores = kiosk(10, "M1", "Miraflores", false);
        when(locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KioskRentService.KIOSK_CATEGORY))
                .thenReturn(List.of(miraflores));
        KioskRentEntity rent = kioskRent(10, "Viejo", 5);
        when(rentRepository.findAll()).thenReturn(List.of(rent));
        byte[] file = xlsx(OLD_HEADERS, new Object[][]{
                {10, "M1", "Miraflores", null, "Nuevo", null, null, null, null},
                {999, "X", null, null, "Alguien", null, null, null, null}
        });

        ImportResult result = service.importTemplate(file, true);

        assertFalse(result.applied());
        assertEquals(1, result.changes().size());
        Change change = result.changes().get(0);
        assertEquals("UPDATE", change.kind());
        assertEquals(KioskRentService.TYPE_KIOSK, change.rentType());
        assertNull(change.rentId());
        assertEquals(10L, change.locationId());
        assertEquals("M1", change.code());
        assertEquals("Nuevo", change.landlordName());
        assertEquals(5, change.paymentDay());
        assertEquals(1, result.errors().size());
        RowError error = result.errors().get(0);
        assertEquals(KioskRentService.TYPE_KIOSK, error.rentType());
        assertNull(error.rentId());
        assertEquals(999L, error.locationId());
        assertTrue(error.message().contains("no es un kiosco"));
        verify(rentRepository, never()).save(any());
        verify(otherRentRepository, never()).saveAndFlush(any());
    }

    @Test
    void excelDuplicateOtherNameIsARowErrorAndAppliesNothing() throws Exception {
        byte[] file = xlsx(NEW_HEADERS, new Object[][]{
                {"OTRA", null, null, null, "Oficina", "OFICINA", null, 100, null, null, null, null, null, null},
                {"otra", null, null, null, " oficina ", "oficina", null, 200, null, null, null, null, null, null}
        });

        ImportResult result = service.importTemplate(file, true);

        assertFalse(result.applied());
        assertEquals(1, result.changes().size());
        assertEquals(1, result.errors().size());
        assertEquals(3, result.errors().get(0).row());
        assertEquals(KioskRentService.TYPE_OTHER, result.errors().get(0).rentType());
        assertTrue(result.errors().get(0).message().contains("Ya existe una renta activa"));
        verify(otherRentRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateAndValidationUseTheExistingErrorBody() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        String message = "Ya existe una renta activa de categoría OFICINA con el nombre «Oficina».";

        ResponseEntity<Map<String, Object>> conflict = handler.handleOtherRentConflict(new OtherRentConflictException(message));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals(409, conflict.getBody().get("status"));
        assertEquals(message, conflict.getBody().get("message"));
        assertNotNull(conflict.getBody().get("timestamp"));

        ResponseEntity<Map<String, Object>> invalid = handler.handleBusinessException(new BusinessException("El nombre es obligatorio."));
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertEquals(400, invalid.getBody().get("status"));
        assertEquals("El nombre es obligatorio.", invalid.getBody().get("message"));
    }

    @Test
    void writeEndpointsReturnCreatedOkAndNoContent() throws Exception {
        KioskRentService rents = mock(KioskRentService.class);
        KioskRentController controller = new KioskRentController(rents);
        RentRow row = new RentRow(null, null, "Oficina", null, new BigDecimal("1.00"), null, null, null, null, null,
                "VIGENTE", null, null, null, null, null, KioskRentService.TYPE_OTHER, 4L, "OFICINA", null);
        OtherSaveRequest request = new OtherSaveRequest("Oficina", null, "OFICINA", null, null, null, null, null, null);
        when(rents.createOther(any())).thenReturn(row);
        when(rents.updateOther(eq(4L), any())).thenReturn(row);

        ResponseEntity<RentRow> created = controller.createOther(request);
        ResponseEntity<RentRow> updated = controller.updateOther(4L, request);
        ResponseEntity<Void> deleted = controller.deleteOther(4L);

        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertSame(row, created.getBody());
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertSame(row, updated.getBody());
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());
        assertNull(deleted.getBody());
        verify(rents).deleteOther(4L);
    }

    @Test
    void h2CreatesOtherRentWithoutThePartialIndex() {
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.url", "jdbc:h2:mem:other_rent;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                .applySetting("hibernate.connection.driver_class", "org.h2.Driver")
                .applySetting("hibernate.connection.username", "sa")
                .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                .applySetting("hibernate.hbm2ddl.auto", "create")
                .build();
        try {
            Metadata metadata = new MetadataSources(registry).addAnnotatedClass(OtherRentEntity.class).buildMetadata();
            try (SessionFactory factory = metadata.buildSessionFactory(); Session session = factory.openSession()) {
                OtherRentEntity entity = OtherRentEntity.builder()
                        .name("Oficina")
                        .category("OFICINA")
                        .monthlyAmount(new BigDecimal("10.00"))
                        .active(true)
                        .source(OtherRentEntity.SOURCE_MANUAL)
                        .build();
                session.beginTransaction();
                session.persist(entity);
                session.getTransaction().commit();
                session.clear();
                OtherRentEntity loaded = session.find(OtherRentEntity.class, entity.getId());
                assertEquals("Oficina", loaded.getName());
                assertEquals("OFICINA", loaded.getCategory());
                assertTrue(loaded.isActive());
                assertEquals(0, new BigDecimal("10.00").compareTo(loaded.getMonthlyAmount()));
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void assertInvalid(KioskRentService.OtherValues values, String message) {
        BusinessException error = assertThrows(BusinessException.class, () -> service.createOther(values));
        assertEquals(message, error.getMessage());
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
