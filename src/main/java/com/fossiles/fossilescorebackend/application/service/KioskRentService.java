package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.Change;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RentRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RowError;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.Summary;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.OtherRentConflictException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OtherRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OtherRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Contabilidad > Rentas. Por kiosco: a quién se le paga, el día de pago y la vigencia. El monto del kiosco no se
 * guarda aquí: se lee de Finanzas (costo fijo ALQUILER, último mes cargado).
 *
 * <p>También las otras rentas que paga la empresa (oficina, bodega u otra), sin kiosco. Esas sí guardan el monto.
 * No hay historial de pagos: el próximo pago se calcula igual que en un kiosco.
 *
 * <p>Contabilidad lo llena a mano o con una plantilla de Excel. La subida primero se revisa y luego se aplica; una
 * celda vacía no borra lo que ya hay, y con un error en cualquier fila no se aplica nada.
 */
@Service
@RequiredArgsConstructor
public class KioskRentService {

    static final String RENT_CATEGORY = "ALQUILER";
    static final String KIOSK_CATEGORY = "KIOSKO";
    static final String TYPE_KIOSK = "KIOSK";
    static final String TYPE_OTHER = "OTHER";
    static final String SHEET_RENTS = "Rentas";
    static final String SHEET_HELP = "Instrucciones";

    private static final String EXCEL_KIOSK = "KIOSCO";
    private static final String EXCEL_OTHER = "OTRA";
    private static final String DUPLICATE_INDEX = "uq_other_rent_active_category_name";

    private static final String H_TIPO = "Tipo";
    private static final String H_ID = "ID";
    private static final String H_CODE = "Código";
    private static final String H_NAME = "Kiosco";
    private static final String H_NOMBRE = "Nombre";
    private static final String H_CATEGORIA = "Categoría";
    private static final String H_DESCRIPCION = "Descripción";
    private static final String H_MONTO = "Monto";
    private static final String H_RENT = "Renta mensual (Q) - de Finanzas";
    private static final String H_LANDLORD = "Arrendador";
    private static final String H_DAY = "Día de pago";
    private static final String H_FROM = "Contrato desde";
    private static final String H_TO = "Contrato hasta";
    private static final String H_NOTE = "Notas";

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_LANDLORD = 200;
    private static final int MAX_NOTE = 500;
    private static final int MAX_NAME = 200;
    private static final int MAX_DESCRIPTION = 500;
    private static final Set<String> CATEGORIES = Set.of(
            OtherRentEntity.CATEGORY_OFICINA, OtherRentEntity.CATEGORY_BODEGA, OtherRentEntity.CATEGORY_OTRO);
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final KioskRentRepository rentRepository;
    private final LocationRepository locationRepository;
    private final KioskFixedCostRepository fixedCostRepository;
    private final KioskRentAccessGuard guard;
    private final OtherRentRepository otherRentRepository;

    /** Lo que se guarda de un kiosco (todo opcional; el monto no va aquí). */
    public record Values(String landlordName, Integer paymentDay, LocalDate contractStart, LocalDate contractEnd,
                         String note) {
        boolean isEmpty() {
            return landlordName == null && paymentDay == null && contractStart == null && contractEnd == null
                    && note == null;
        }
    }

    /** Lo que se guarda de una renta que no es kiosco. El nombre y la categoría son obligatorios. */
    public record OtherValues(String name, String description, String category, String landlordName,
                              BigDecimal monthlyAmount, Integer paymentDay, LocalDate contractStart,
                              LocalDate contractEnd, String note) {
    }

    private record Rent(BigDecimal amount, String period) {}

    // ------------------------------------------------------------------------------------------------
    // Lista, guardar, quitar
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ListResponse list(String rentType) throws BusinessException {
        guard.assertCanView();
        return buildList(GuatemalaDateTime.today(), normalizeRentType(rentType));
    }

    @Transactional
    public RentRow createOther(OtherValues input) throws BusinessException {
        Long userId = guard.assertCanEdit();
        OtherValues values = normalizeOther(input);
        assertUniqueActiveName(values.category(), values.name(), null);
        OtherRentEntity entity = OtherRentEntity.builder().createdBy(userId).active(true).build();
        applyOther(entity, values, userId, OtherRentEntity.SOURCE_MANUAL);
        return otherRow(saveOther(entity), GuatemalaDateTime.today());
    }

    @Transactional
    public RentRow updateOther(Long rentId, OtherValues input) throws BusinessException {
        Long userId = guard.assertCanEdit();
        OtherRentEntity entity = activeOther(rentId);
        OtherValues values = normalizeOther(input);
        assertUniqueActiveName(values.category(), values.name(), entity.getId());
        applyOther(entity, values, userId, OtherRentEntity.SOURCE_MANUAL);
        return otherRow(saveOther(entity), GuatemalaDateTime.today());
    }

    @Transactional
    public void deleteOther(Long rentId) throws BusinessException {
        Long userId = guard.assertCanEdit();
        OtherRentEntity entity = activeOther(rentId);
        entity.setActive(false);
        entity.setUpdatedBy(userId);
        otherRentRepository.save(entity);
    }

    @Transactional
    public ListResponse save(Long locationId, Values input) throws BusinessException {
        Long userId = guard.assertCanEdit();
        findKiosk(locationId);
        Values values = normalize(input);
        if (values.isEmpty()) {
            throw new BusinessException("Indique al menos un dato. Para borrar los datos del kiosco use «Quitar».");
        }
        validate(values);
        KioskRentEntity entity = rentRepository.findById(locationId)
                .orElseGet(() -> KioskRentEntity.builder().locationId(locationId).createdBy(userId).build());
        apply(entity, values, userId, KioskRentEntity.SOURCE_MANUAL);
        rentRepository.save(entity);
        return buildList(GuatemalaDateTime.today(), null);
    }

    @Transactional
    public ListResponse clear(Long locationId) throws BusinessException {
        guard.assertCanEdit();
        findKiosk(locationId);
        rentRepository.findById(locationId).ifPresent(rentRepository::delete);
        return buildList(GuatemalaDateTime.today(), null);
    }

    ListResponse buildList(LocalDate today, String rentType) {
        Map<Long, KioskRentEntity> rents = new HashMap<>();
        rentRepository.findAll().forEach(r -> rents.put(r.getLocationId(), r));
        Map<Long, Rent> finance = financeRents();

        List<RentRow> rows = new ArrayList<>();
        Map<String, Integer> byStatus = statusCounts();
        Set<String> landlords = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Totals totals = new Totals();

        for (LocationEntity kiosk : rentKiosks()) {
            KioskRentEntity r = rents.get(kiosk.getId());
            Rent rent = finance.get(kiosk.getId());
            String landlord = r != null ? r.getLandlordName() : null;
            Integer day = r != null ? r.getPaymentDay() : null;
            LocalDate start = r != null ? r.getContractStart() : null;
            LocalDate end = r != null ? r.getContractEnd() : null;
            Schedule schedule = schedule(landlord, day, start, end, today);
            BigDecimal amount = rent != null ? rent.amount() : null;
            totals.add(true, schedule.status(), byStatus, landlord, landlords, schedule.daysToNext(), amount);
            rows.add(new RentRow(kiosk.getId(), kiosk.getCode(), kiosk.getName(), landlord, amount,
                    rent != null ? rent.period() : null, day, start, end, r != null ? r.getNote() : null,
                    schedule.status(), schedule.next(), schedule.daysToNext(), schedule.daysToEnd(), schedule.progress(),
                    r != null ? (r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt()) : null,
                    TYPE_KIOSK, null, null, null));
        }
        for (OtherRentEntity other : otherRentRepository.findByActiveTrueOrderByNameAscIdAsc()) {
            rows.add(otherRow(other, today, byStatus, landlords, totals));
        }
        Summary summary = totals.summary(byStatus);
        List<RentRow> visible = rentType == null ? rows
                : rows.stream().filter(row -> rentType.equals(row.rentType())).toList();
        return new ListResponse(today, KioskRentCalculator.EXPIRING_DAYS, canEdit(), summary, visible,
                new ArrayList<>(landlords));
    }

    /** Renta vigente por kiosco: el costo ALQUILER más reciente que Finanzas tiene cargado. */
    private Map<Long, Rent> financeRents() {
        Map<Long, Rent> result = new HashMap<>();
        for (Object[] row : fixedCostRepository.findByCategoryWithLocation(RENT_CATEGORY)) {
            Long locationId = ((Number) row[0]).longValue();
            result.computeIfAbsent(locationId, id -> new Rent((BigDecimal) row[1],
                    String.format("%04d-%02d", ((Number) row[2]).intValue(), ((Number) row[3]).intValue())));
        }
        return result;
    }

    /** Kioscos que van en Rentas: los reales. Los que están en piloto POS (pos_test_mode) no se muestran. */
    private List<LocationEntity> rentKiosks() {
        return locationRepository.findByCategoriaIgnoreCaseOrderByNameAsc(KIOSK_CATEGORY).stream()
                .filter(KioskRentService::isRentKiosk)
                .toList();
    }

    private static boolean isRentKiosk(LocationEntity location) {
        return KIOSK_CATEGORY.equalsIgnoreCase(location.getCategoria()) && !Boolean.TRUE.equals(location.getPosTestMode());
    }

    private LocationEntity findKiosk(Long locationId) throws BusinessException {
        LocationEntity location = locationId == null ? null : locationRepository.findById(locationId).orElse(null);
        if (location == null || !isRentKiosk(location)) {
            throw new BusinessException("El kiosco indicado no existe.");
        }
        return location;
    }

    private static Values normalize(Values in) {
        if (in == null) {
            return new Values(null, null, null, null, null);
        }
        return new Values(blankToNull(in.landlordName()), in.paymentDay(), in.contractStart(), in.contractEnd(),
                blankToNull(in.note()));
    }

    private static void validate(Values v) throws BusinessException {
        if (v.landlordName() != null && v.landlordName().length() > MAX_LANDLORD) {
            throw new BusinessException("El nombre del arrendador no puede pasar de " + MAX_LANDLORD + " caracteres.");
        }
        if (v.note() != null && v.note().length() > MAX_NOTE) {
            throw new BusinessException("Las notas no pueden pasar de " + MAX_NOTE + " caracteres.");
        }
        if (v.paymentDay() != null && (v.paymentDay() < 1 || v.paymentDay() > 31)) {
            throw new BusinessException("El día de pago debe estar entre 1 y 31.");
        }
        if (v.contractStart() != null && v.contractEnd() != null && v.contractEnd().isBefore(v.contractStart())) {
            throw new BusinessException("«Contrato hasta» no puede ser anterior a «Contrato desde».");
        }
    }

    private static void apply(KioskRentEntity entity, Values v, Long userId, String source) {
        entity.setLandlordName(v.landlordName());
        entity.setPaymentDay(v.paymentDay());
        entity.setContractStart(v.contractStart());
        entity.setContractEnd(v.contractEnd());
        entity.setNote(v.note());
        entity.setSource(source);
        entity.setUpdatedBy(userId);
    }

    private static Values valuesOf(KioskRentEntity e) {
        return e == null ? new Values(null, null, null, null, null)
                : new Values(e.getLandlordName(), e.getPaymentDay(), e.getContractStart(), e.getContractEnd(), e.getNote());
    }

    // ------------------------------------------------------------------------------------------------
    // Plantilla de Excel (descarga)
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public byte[] buildTemplate() throws BusinessException {
        guard.assertCanView();
        Map<Long, KioskRentEntity> rents = new HashMap<>();
        rentRepository.findAll().forEach(r -> rents.put(r.getLocationId(), r));
        Map<Long, Rent> finance = financeRents();
        List<LocationEntity> kiosks = rentKiosks();
        List<OtherRentEntity> others = otherRentRepository.findByActiveTrueOrderByNameAscIdAsc();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat format = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headFixed = style(wb, bold, IndexedColors.GREY_25_PERCENT, null);
            CellStyle headInput = style(wb, bold, IndexedColors.LIGHT_YELLOW, null);
            CellStyle fixed = style(wb, null, IndexedColors.GREY_25_PERCENT, null);
            CellStyle fixedMoney = style(wb, null, IndexedColors.GREY_25_PERCENT, format.getFormat("#,##0.00"));
            CellStyle input = style(wb, null, IndexedColors.LIGHT_YELLOW, null);
            CellStyle inputMoney = style(wb, null, IndexedColors.LIGHT_YELLOW, format.getFormat("#,##0.00"));
            CellStyle inputDate = style(wb, null, IndexedColors.LIGHT_YELLOW, format.getFormat("dd/mm/yyyy"));

            Sheet sheet = wb.createSheet(SHEET_RENTS);
            String[] headers = {H_TIPO, H_ID, H_CODE, H_NAME, H_NOMBRE, H_CATEGORIA, H_DESCRIPCION, H_MONTO, H_RENT,
                    H_LANDLORD, H_DAY, H_FROM, H_TO, H_NOTE};
            Set<String> fixedHeaders = Set.of(normalize(H_ID), normalize(H_CODE), normalize(H_NAME), normalize(H_RENT));
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(fixedHeaders.contains(normalize(headers[i])) ? headFixed : headInput);
            }
            int rowIndex = 1;
            for (LocationEntity kiosk : kiosks) {
                KioskRentEntity r = rents.get(kiosk.getId());
                Rent rent = finance.get(kiosk.getId());
                Row row = sheet.createRow(rowIndex++);
                inputCell(row, 0, input).setCellValue(EXCEL_KIOSK);
                fixedCell(row, 1, fixed).setCellValue(kiosk.getId());
                fixedCell(row, 2, fixed).setCellValue(safe(kiosk.getCode()));
                fixedCell(row, 3, fixed).setCellValue(safe(kiosk.getName()));
                inputCell(row, 4, input);
                inputCell(row, 5, input);
                inputCell(row, 6, input);
                inputCell(row, 7, inputMoney);
                Cell rentCell = fixedCell(row, 8, fixedMoney);
                if (rent != null) {
                    rentCell.setCellValue(rent.amount().doubleValue());
                }
                inputCell(row, 9, input);
                inputCell(row, 10, input);
                inputCell(row, 11, inputDate);
                inputCell(row, 12, inputDate);
                inputCell(row, 13, input);
                if (r != null) {
                    if (r.getLandlordName() != null) {
                        row.getCell(9).setCellValue(r.getLandlordName());
                    }
                    if (r.getPaymentDay() != null) {
                        row.getCell(10).setCellValue(r.getPaymentDay());
                    }
                    if (r.getContractStart() != null) {
                        row.getCell(11).setCellValue(r.getContractStart());
                    }
                    if (r.getContractEnd() != null) {
                        row.getCell(12).setCellValue(r.getContractEnd());
                    }
                    if (r.getNote() != null) {
                        row.getCell(13).setCellValue(r.getNote());
                    }
                }
            }
            for (OtherRentEntity other : others) {
                Row row = sheet.createRow(rowIndex++);
                inputCell(row, 0, input).setCellValue(EXCEL_OTHER);
                fixedCell(row, 1, fixed).setCellValue(other.getId());
                fixedCell(row, 2, fixed);
                fixedCell(row, 3, fixed);
                inputCell(row, 4, input).setCellValue(other.getName());
                inputCell(row, 5, input).setCellValue(other.getCategory());
                inputCell(row, 6, input);
                Cell amount = inputCell(row, 7, inputMoney);
                if (other.getDescription() != null) {
                    row.getCell(6).setCellValue(other.getDescription());
                }
                if (other.getMonthlyAmount() != null) {
                    amount.setCellValue(other.getMonthlyAmount().doubleValue());
                }
                fixedCell(row, 8, fixedMoney);
                inputCell(row, 9, input);
                inputCell(row, 10, input);
                inputCell(row, 11, inputDate);
                inputCell(row, 12, inputDate);
                inputCell(row, 13, input);
                if (other.getLandlordName() != null) {
                    row.getCell(9).setCellValue(other.getLandlordName());
                }
                if (other.getPaymentDay() != null) {
                    row.getCell(10).setCellValue(other.getPaymentDay());
                }
                if (other.getContractStart() != null) {
                    row.getCell(11).setCellValue(other.getContractStart());
                }
                if (other.getContractEnd() != null) {
                    row.getCell(12).setCellValue(other.getContractEnd());
                }
                if (other.getNote() != null) {
                    row.getCell(13).setCellValue(other.getNote());
                }
            }
            sheet.createFreezePane(4, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIndex - 1, 0), 0, headers.length - 1));
            int[] widths = {12, 8, 14, 28, 28, 14, 36, 14, 24, 32, 12, 16, 16, 32};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            int lastValidationRow = Math.max(rowIndex + 40, 40);
            DataValidationHelper helper = sheet.getDataValidationHelper();
            addListValidation(sheet, helper, 0, lastValidationRow, new String[]{EXCEL_KIOSK, EXCEL_OTHER},
                    "Tipo", "Escriba KIOSCO u OTRA.");
            addListValidation(sheet, helper, 5, lastValidationRow,
                    new String[]{OtherRentEntity.CATEGORY_OFICINA, OtherRentEntity.CATEGORY_BODEGA, OtherRentEntity.CATEGORY_OTRO},
                    "Categoría", "Escriba OFICINA, BODEGA u OTRO.");
            DataValidationConstraint dayRule = helper.createIntegerConstraint(
                    DataValidationConstraint.OperatorType.BETWEEN, "1", "31");
            DataValidation dayValidation = helper.createValidation(dayRule,
                    new CellRangeAddressList(1, lastValidationRow, 10, 10));
            dayValidation.setShowErrorBox(true);
            dayValidation.createErrorBox("Día de pago", "Escriba un día del mes entre 1 y 31.");
            sheet.addValidationData(dayValidation);

            Sheet help = wb.createSheet(SHEET_HELP);
            String[] lines = {
                    "PLANTILLA DE RENTAS",
                    "",
                    "La columna Tipo dice si la fila es un kiosco (KIOSCO) u otra renta (OTRA: oficina, bodega u otra).",
                    "Una plantilla anterior, sin la columna Tipo, se lee como rentas de kiosco.",
                    "",
                    "KIOSCO: no cambie ID, Código, Kiosco ni la renta gris (sale de Finanzas y aquí no se sube).",
                    "Llene Arrendador, Día de pago (1 a 31), Contrato desde, Contrato hasta y Notas.",
                    "",
                    "OTRA: ID vacío crea una renta; con ID actualiza la que ya existe.",
                    "Llene Nombre, Categoría (OFICINA, BODEGA u OTRO), Descripción, Monto y los mismos datos del contrato.",
                    "No puede haber dos rentas activas con la misma categoría y el mismo nombre.",
                    "",
                    "Las fechas se escriben como 31/12/2026. Si el mes tiene menos días que el día de pago, se toma el último día.",
                    "Una celda vacía NO borra lo que ya está guardado.",
                    "Al subir el archivo primero se muestra una revisión; si hay algún error no se guarda nada.",
            };
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 130 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("No se pudo generar la plantilla de rentas.", e);
        }
    }

    private static CellStyle style(Workbook wb, Font font, IndexedColors fill, Short dataFormat) {
        CellStyle s = wb.createCellStyle();
        if (font != null) {
            s.setFont(font);
            s.setBorderBottom(BorderStyle.THIN);
        }
        s.setFillForegroundColor(fill.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        if (dataFormat != null) {
            s.setDataFormat(dataFormat);
        }
        return s;
    }

    private static Cell fixedCell(Row row, int col, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellStyle(style);
        return c;
    }

    private static Cell inputCell(Row row, int col, CellStyle style) {
        return fixedCell(row, col, style);
    }

    private static void addListValidation(Sheet sheet, DataValidationHelper helper, int column, int lastRow,
                                          String[] values, String title, String message) {
        DataValidation validation = helper.createValidation(helper.createExplicitListConstraint(values),
                new CellRangeAddressList(1, lastRow, column, column));
        validation.setShowErrorBox(true);
        validation.createErrorBox(title, message);
        sheet.addValidationData(validation);
    }

    // ------------------------------------------------------------------------------------------------
    // Plantilla de Excel (subida)
    // ------------------------------------------------------------------------------------------------

    private record Pending(Long locationId, Values values) {}

    private record OtherPending(int rowNumber, Long rentId, OtherValues values, Change change) {}

    @Transactional
    public ImportResult importTemplate(byte[] content, boolean apply) throws BusinessException {
        Long userId = guard.assertCanEdit();
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo está vacío.");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido (5 MB).");
        }
        Map<Long, LocationEntity> kiosks = new HashMap<>();
        rentKiosks().forEach(k -> kiosks.put(k.getId(), k));
        Map<Long, KioskRentEntity> existing = new HashMap<>();
        rentRepository.findAll().forEach(r -> existing.put(r.getLocationId(), r));
        Map<Long, OtherRentEntity> others = new HashMap<>();
        otherRentRepository.findByActiveTrueOrderByNameAscIdAsc().forEach(o -> others.put(o.getId(), o));

        List<RowError> errors = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        List<OtherPending> otherPending = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Set<Long> seenOthers = new HashSet<>();
        int[] counters = new int[3]; // leídas, vacías, sin cambio
        boolean foundSheet = false;

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                Map<String, Integer> cols = headerColumns(sheet.getRow(sheet.getFirstRowNum()));
                if (!cols.containsKey(normalize(H_ID)) || !cols.containsKey(normalize(H_LANDLORD))) {
                    continue; // otra hoja (p. ej. Instrucciones)
                }
                foundSheet = true;
                boolean tipoColumn = cols.containsKey(normalize(H_TIPO));
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    readRow(sheet.getRow(r), r + 1, cols, tipoColumn, kiosks, existing, others, seen, seenOthers,
                            errors, changes, pending, otherPending, counters);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
        if (!foundSheet) {
            throw new BusinessException("El archivo no tiene las columnas de la plantilla. Use la plantilla descargada del sistema.");
        }
        rejectDuplicateOthers(others, otherPending, changes, errors);

        boolean applied = apply && errors.isEmpty();
        if (applied) {
            for (Pending p : pending) {
                KioskRentEntity entity = existing.get(p.locationId());
                if (entity == null) {
                    entity = KioskRentEntity.builder().locationId(p.locationId()).createdBy(userId).build();
                }
                apply(entity, p.values(), userId, KioskRentEntity.SOURCE_EXCEL);
                rentRepository.save(entity);
            }
            for (OtherPending p : otherPending) {
                OtherRentEntity entity = p.rentId() == null
                        ? OtherRentEntity.builder().createdBy(userId).active(true).build()
                        : others.get(p.rentId());
                applyOther(entity, p.values(), userId, OtherRentEntity.SOURCE_EXCEL);
                saveOther(entity);
            }
        }
        errors.sort(Comparator.comparingInt(RowError::row));
        return new ImportResult(applied, counters[0], counters[1], counters[2], changes, errors);
    }

    private void readRow(Row row, int rowNumber, Map<String, Integer> cols, boolean tipoColumn,
                         Map<Long, LocationEntity> kiosks, Map<Long, KioskRentEntity> existing,
                         Map<Long, OtherRentEntity> others, Set<Long> seen, Set<Long> seenOthers, List<RowError> errors,
                         List<Change> changes, List<Pending> pending, List<OtherPending> otherPending, int[] counters) {
        if (row == null) {
            return;
        }
        String tipo = tipoColumn ? excelTipo(cell(row, cols, H_TIPO)) : EXCEL_KIOSK;
        if (tipo == null) {
            counters[0]++;
            errors.add(new RowError(rowNumber, null, text(cell(row, cols, H_CODE)), text(cell(row, cols, H_NOMBRE)),
                    "El tipo debe ser KIOSCO u OTRA.", null, null));
            return;
        }
        if (EXCEL_OTHER.equals(tipo)) {
            readOtherRow(row, rowNumber, cols, others, seenOthers, errors, changes, otherPending, counters);
            return;
        }
        Cell idCell = cell(row, cols, H_ID);
        Cell codeCell = cell(row, cols, H_CODE);
        Cell landlordCell = cell(row, cols, H_LANDLORD);
        Cell dayCell = cell(row, cols, H_DAY);
        Cell fromCell = cell(row, cols, H_FROM);
        Cell toCell = cell(row, cols, H_TO);
        Cell noteCell = cell(row, cols, H_NOTE);
        boolean noInput = isBlank(landlordCell) && isBlank(dayCell) && isBlank(fromCell) && isBlank(toCell)
                && isBlank(noteCell);
        if (isBlank(idCell) && noInput) {
            return; // renglón vacío
        }
        counters[0]++;

        Long id = null;
        try {
            id = parseId(idCell);
        } catch (RuntimeException e) {
            errors.add(kioskError(rowNumber, null, text(codeCell), null, "El ID no es válido. No lo modifique."));
            return;
        }
        LocationEntity kiosk = kiosks.get(id);
        if (kiosk == null) {
            errors.add(kioskError(rowNumber, id, text(codeCell), null, "El ID " + id + " no es un kiosco del sistema."));
            return;
        }
        String name = kiosk.getName();
        if (!isBlank(codeCell) && !text(codeCell).equalsIgnoreCase(safe(kiosk.getCode()))) {
            errors.add(kioskError(rowNumber, id, kiosk.getCode(), name,
                    "El código «" + text(codeCell) + "» no coincide con el del kiosco (" + safe(kiosk.getCode()) + ")."));
            return;
        }
        if (!seen.add(id)) {
            errors.add(kioskError(rowNumber, id, kiosk.getCode(), name, "El kiosco aparece más de una vez en el archivo."));
            return;
        }
        if (noInput) {
            counters[1]++;
            return;
        }

        List<String> problems = new ArrayList<>();
        String landlord = isBlank(landlordCell) ? null : text(landlordCell);
        String note = isBlank(noteCell) ? null : text(noteCell);
        Integer day = null;
        LocalDate from = null;
        LocalDate to = null;
        try {
            day = isBlank(dayCell) ? null : parseDay(dayCell);
        } catch (RuntimeException e) {
            problems.add("«Día de pago» debe ser un número del 1 al 31");
        }
        try {
            from = isBlank(fromCell) ? null : parseDate(fromCell);
        } catch (RuntimeException e) {
            problems.add("«Contrato desde» no es una fecha válida (use 31/12/2026)");
        }
        try {
            to = isBlank(toCell) ? null : parseDate(toCell);
        } catch (RuntimeException e) {
            problems.add("«Contrato hasta» no es una fecha válida (use 31/12/2026)");
        }

        // Lo que viene en el archivo se encima a lo guardado; lo vacío se queda como estaba.
        Values before = valuesOf(existing.get(id));
        Values merged = new Values(
                landlord != null ? landlord : before.landlordName(),
                day != null ? day : before.paymentDay(),
                from != null ? from : before.contractStart(),
                to != null ? to : before.contractEnd(),
                note != null ? note : before.note());
        if (problems.isEmpty()) {
            try {
                validate(merged);
            } catch (BusinessException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            errors.add(kioskError(rowNumber, id, kiosk.getCode(), name, String.join("; ", problems) + "."));
            return;
        }
        if (merged.equals(before)) {
            counters[2]++;
            return;
        }
        pending.add(new Pending(id, merged));
        changes.add(new Change(id, kiosk.getCode(), name, merged.landlordName(), merged.paymentDay(),
                merged.contractStart(), merged.contractEnd(), before.contractEnd(),
                existing.get(id) == null ? "NEW" : "UPDATE", TYPE_KIOSK, null));
    }

    private static RowError kioskError(int row, Long locationId, String code, String name, String message) {
        return new RowError(row, locationId, code, name, message, TYPE_KIOSK, null);
    }

    private void readOtherRow(Row row, int rowNumber, Map<String, Integer> cols, Map<Long, OtherRentEntity> others,
                              Set<Long> seenOthers, List<RowError> errors, List<Change> changes,
                              List<OtherPending> pending, int[] counters) {
        Cell idCell = cell(row, cols, H_ID);
        Cell nameCell = cell(row, cols, H_NOMBRE);
        Cell categoryCell = cell(row, cols, H_CATEGORIA);
        Cell descriptionCell = cell(row, cols, H_DESCRIPCION);
        Cell amountCell = cell(row, cols, H_MONTO);
        Cell landlordCell = cell(row, cols, H_LANDLORD);
        Cell dayCell = cell(row, cols, H_DAY);
        Cell fromCell = cell(row, cols, H_FROM);
        Cell toCell = cell(row, cols, H_TO);
        Cell noteCell = cell(row, cols, H_NOTE);
        boolean noInput = isBlank(nameCell) && isBlank(categoryCell) && isBlank(descriptionCell) && isBlank(amountCell)
                && isBlank(landlordCell) && isBlank(dayCell) && isBlank(fromCell) && isBlank(toCell) && isBlank(noteCell);
        if (isBlank(idCell) && noInput) {
            return;
        }
        counters[0]++;

        Long id = null;
        if (!isBlank(idCell)) {
            try {
                id = parseId(idCell);
            } catch (RuntimeException e) {
                errors.add(new RowError(rowNumber, null, null, text(nameCell), "El ID no es válido.", TYPE_OTHER, null));
                return;
            }
        }
        OtherRentEntity current = id == null ? null : others.get(id);
        if (id != null && current == null) {
            errors.add(new RowError(rowNumber, null, null, text(nameCell),
                    "El ID " + id + " no es una renta activa.", TYPE_OTHER, id));
            return;
        }
        String shownName = current != null ? current.getName() : text(nameCell);
        if (id != null && !seenOthers.add(id)) {
            errors.add(new RowError(rowNumber, null, null, shownName, "La renta aparece más de una vez en el archivo.",
                    TYPE_OTHER, id));
            return;
        }
        if (noInput) {
            counters[1]++;
            return;
        }

        List<String> problems = new ArrayList<>();
        String name = isBlank(nameCell) ? null : text(nameCell);
        String description = isBlank(descriptionCell) ? null : text(descriptionCell);
        String category = null;
        if (!isBlank(categoryCell)) {
            category = canonicalCategory(text(categoryCell));
            if (category == null) {
                problems.add("La categoría debe ser OFICINA, BODEGA u OTRO");
            }
        }
        BigDecimal amount = null;
        if (!isBlank(amountCell)) {
            try {
                amount = parseAmount(amountCell);
            } catch (RuntimeException e) {
                problems.add("«Monto» no es un número válido");
            }
        }
        String landlord = isBlank(landlordCell) ? null : text(landlordCell);
        String note = isBlank(noteCell) ? null : text(noteCell);
        Integer day = null;
        LocalDate from = null;
        LocalDate to = null;
        try {
            day = isBlank(dayCell) ? null : parseDay(dayCell);
        } catch (RuntimeException e) {
            problems.add("«Día de pago» debe ser un número del 1 al 31");
        }
        try {
            from = isBlank(fromCell) ? null : parseDate(fromCell);
        } catch (RuntimeException e) {
            problems.add("«Contrato desde» no es una fecha válida (use 31/12/2026)");
        }
        try {
            to = isBlank(toCell) ? null : parseDate(toCell);
        } catch (RuntimeException e) {
            problems.add("«Contrato hasta» no es una fecha válida (use 31/12/2026)");
        }

        OtherValues before = otherValuesOf(current);
        OtherValues merged = new OtherValues(
                name != null ? name : before.name(),
                description != null ? description : before.description(),
                category != null ? category : before.category(),
                landlord != null ? landlord : before.landlordName(),
                amount != null ? amount : before.monthlyAmount(),
                day != null ? day : before.paymentDay(),
                from != null ? from : before.contractStart(),
                to != null ? to : before.contractEnd(),
                note != null ? note : before.note());
        if (problems.isEmpty()) {
            if (merged.name() == null) {
                problems.add("El nombre es obligatorio");
            } else if (merged.name().length() > MAX_NAME) {
                problems.add("El nombre no puede pasar de " + MAX_NAME + " caracteres");
            }
            if (merged.description() != null && merged.description().length() > MAX_DESCRIPTION) {
                problems.add("La descripción no puede pasar de " + MAX_DESCRIPTION + " caracteres");
            }
            if (merged.category() == null) {
                problems.add("La categoría es obligatoria");
            }
            try {
                merged = new OtherValues(merged.name(), merged.description(), merged.category(), merged.landlordName(),
                        canonicalAmount(merged.monthlyAmount()), merged.paymentDay(), merged.contractStart(),
                        merged.contractEnd(), merged.note());
                validate(new Values(merged.landlordName(), merged.paymentDay(), merged.contractStart(),
                        merged.contractEnd(), merged.note()));
            } catch (BusinessException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            errors.add(new RowError(rowNumber, null, null, shownName, String.join("; ", problems) + ".", TYPE_OTHER, id));
            return;
        }
        if (merged.equals(before)) {
            counters[2]++;
            return;
        }
        Change change = new Change(null, null, merged.name(), merged.landlordName(), merged.paymentDay(),
                merged.contractStart(), merged.contractEnd(), before.contractEnd(), id == null ? "NEW" : "UPDATE",
                TYPE_OTHER, id);
        pending.add(new OtherPending(rowNumber, id, merged, change));
        changes.add(change);
    }

    /** Después de leer el archivo, dos rentas activas no pueden compartir categoría y nombre. */
    private void rejectDuplicateOthers(Map<Long, OtherRentEntity> existing, List<OtherPending> pending,
                                       List<Change> changes, List<RowError> errors) {
        Set<Long> updated = new HashSet<>();
        for (OtherPending row : pending) {
            if (row.rentId() != null) {
                updated.add(row.rentId());
            }
        }
        Map<String, Long> untouched = new HashMap<>();
        for (OtherRentEntity entity : existing.values()) {
            if (!updated.contains(entity.getId())) {
                untouched.put(otherKey(entity.getCategory(), entity.getName()), entity.getId());
            }
        }
        Map<String, List<OtherPending>> byKey = new LinkedHashMap<>();
        for (OtherPending row : pending) {
            byKey.computeIfAbsent(otherKey(row.values().category(), row.values().name()), key -> new ArrayList<>())
                    .add(row);
        }
        List<OtherPending> rejected = new ArrayList<>();
        for (Map.Entry<String, List<OtherPending>> entry : byKey.entrySet()) {
            int keep = untouched.containsKey(entry.getKey()) ? 0 : 1;
            List<OtherPending> group = entry.getValue();
            for (int i = keep; i < group.size(); i++) {
                rejected.add(group.get(i));
            }
        }
        for (OtherPending row : rejected) {
            pending.remove(row);
            changes.remove(row.change());
            errors.add(new RowError(row.rowNumber(), null, null, row.values().name(),
                    duplicateMessage(row.values().category(), row.values().name()), TYPE_OTHER, row.rentId()));
        }
    }

    /** Sin columna Tipo, o con la celda vacía, la fila es un kiosco (plantilla anterior). */
    private static String excelTipo(Cell cell) {
        if (isBlank(cell)) {
            return EXCEL_KIOSK;
        }
        String value = normalize(text(cell));
        if (value.equals(normalize(EXCEL_KIOSK))) {
            return EXCEL_KIOSK;
        }
        if (value.equals(normalize(EXCEL_OTHER))) {
            return EXCEL_OTHER;
        }
        return null;
    }

    private static String normalizeRentType(String rentType) throws BusinessException {
        if (rentType == null || rentType.isBlank()) {
            return null;
        }
        String value = rentType.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_KIOSK.equals(value) && !TYPE_OTHER.equals(value)) {
            throw new BusinessException("rentType debe ser KIOSK u OTHER.");
        }
        return value;
    }

    private OtherValues normalizeOther(OtherValues in) throws BusinessException {
        if (in == null || blankToNull(in.name()) == null) {
            throw new BusinessException("El nombre es obligatorio.");
        }
        String name = blankToNull(in.name());
        if (name.length() > MAX_NAME) {
            throw new BusinessException("El nombre no puede pasar de " + MAX_NAME + " caracteres.");
        }
        String description = blankToNull(in.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new BusinessException("La descripción no puede pasar de " + MAX_DESCRIPTION + " caracteres.");
        }
        String category = canonicalCategory(in.category());
        if (category == null) {
            throw new BusinessException(blankToNull(in.category()) == null
                    ? "La categoría es obligatoria."
                    : "La categoría debe ser OFICINA, BODEGA u OTRO.");
        }
        Values shared = normalize(new Values(in.landlordName(), in.paymentDay(), in.contractStart(), in.contractEnd(),
                in.note()));
        validate(shared);
        return new OtherValues(name, description, category, shared.landlordName(), canonicalAmount(in.monthlyAmount()),
                shared.paymentDay(), shared.contractStart(), shared.contractEnd(), shared.note());
    }

    private static String canonicalCategory(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static BigDecimal canonicalAmount(BigDecimal amount) throws BusinessException {
        if (amount == null) {
            return null;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        if (stripped.signum() < 0) {
            throw new BusinessException("El monto no puede ser negativo.");
        }
        if (stripped.scale() > 2) {
            throw new BusinessException("El monto solo admite 2 decimales.");
        }
        BigDecimal scaled = stripped.setScale(2);
        if (scaled.precision() - scaled.scale() > 10) {
            throw new BusinessException("El monto es demasiado grande.");
        }
        return scaled;
    }

    private void assertUniqueActiveName(String category, String name, Long excludeId) {
        for (OtherRentEntity other : otherRentRepository.findActiveByCategoryAndName(category, name)) {
            if (excludeId == null || !excludeId.equals(other.getId())) {
                throw new OtherRentConflictException(duplicateMessage(category, name));
            }
        }
    }

    private OtherRentEntity activeOther(Long rentId) throws BusinessException {
        OtherRentEntity entity = rentId == null ? null : otherRentRepository.findByIdAndActiveTrue(rentId).orElse(null);
        if (entity == null) {
            throw new BusinessException("No se encontró la renta.");
        }
        return entity;
    }

    private OtherRentEntity saveOther(OtherRentEntity entity) {
        try {
            return otherRentRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            if (isDuplicateOtherRent(ex)) {
                throw new OtherRentConflictException(duplicateMessage(entity.getCategory(), entity.getName()));
            }
            throw ex;
        }
    }

    private static boolean isDuplicateOtherRent(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(DUPLICATE_INDEX)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void applyOther(OtherRentEntity entity, OtherValues values, Long userId, String source) {
        entity.setName(values.name());
        entity.setDescription(values.description());
        entity.setCategory(values.category());
        entity.setLandlordName(values.landlordName());
        entity.setMonthlyAmount(values.monthlyAmount());
        entity.setPaymentDay(values.paymentDay());
        entity.setContractStart(values.contractStart());
        entity.setContractEnd(values.contractEnd());
        entity.setNote(values.note());
        entity.setSource(source);
        entity.setUpdatedBy(userId);
    }

    private static OtherValues otherValuesOf(OtherRentEntity entity) {
        if (entity == null) {
            return new OtherValues(null, null, null, null, null, null, null, null, null);
        }
        BigDecimal amount = entity.getMonthlyAmount();
        try {
            amount = canonicalAmount(amount);
        } catch (BusinessException ignored) {
            // Se conserva el valor leído; la validación de la fila lo reporta si no entra en el monto.
        }
        return new OtherValues(entity.getName(), entity.getDescription(), entity.getCategory(), entity.getLandlordName(),
                amount, entity.getPaymentDay(), entity.getContractStart(), entity.getContractEnd(), entity.getNote());
    }

    private RentRow otherRow(OtherRentEntity entity, LocalDate today) {
        return otherRow(entity, today, null, null, null);
    }

    private RentRow otherRow(OtherRentEntity entity, LocalDate today, Map<String, Integer> byStatus,
                             Set<String> landlords, Totals totals) {
        Schedule schedule = schedule(entity.getLandlordName(), entity.getPaymentDay(), entity.getContractStart(),
                entity.getContractEnd(), today);
        if (totals != null) {
            totals.add(false, schedule.status(), byStatus, entity.getLandlordName(), landlords, schedule.daysToNext(),
                    entity.getMonthlyAmount());
        }
        return new RentRow(null, null, entity.getName(), entity.getLandlordName(), entity.getMonthlyAmount(), null,
                entity.getPaymentDay(), entity.getContractStart(), entity.getContractEnd(), entity.getNote(),
                schedule.status(), schedule.next(), schedule.daysToNext(), schedule.daysToEnd(), schedule.progress(),
                entity.getUpdatedAt() != null ? entity.getUpdatedAt() : entity.getCreatedAt(),
                TYPE_OTHER, entity.getId(), entity.getCategory(), entity.getDescription());
    }

    private static Schedule schedule(String landlord, Integer day, LocalDate start, LocalDate end, LocalDate today) {
        String status = KioskRentCalculator.status(landlord, day, start, end, today);
        LocalDate next = KioskRentCalculator.nextPaymentDate(day, start, end, today);
        Integer daysToNext = next == null ? null : (int) ChronoUnit.DAYS.between(today, next);
        Integer daysToEnd = end == null ? null : (int) ChronoUnit.DAYS.between(today, end);
        return new Schedule(status, next, daysToNext, daysToEnd, KioskRentCalculator.progressPct(start, end, today));
    }

    private static Map<String, Integer> statusCounts() {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String status : List.of(KioskRentCalculator.VIGENTE, KioskRentCalculator.POR_VENCER,
                KioskRentCalculator.VENCIDO, KioskRentCalculator.INCOMPLETO, KioskRentCalculator.SIN_CARGAR)) {
            byStatus.put(status, 0);
        }
        return byStatus;
    }

    private boolean canEdit() {
        try {
            return guard.canEdit();
        } catch (BusinessException e) {
            return false;
        }
    }

    private static String duplicateMessage(String category, String name) {
        return "Ya existe una renta activa de categoría " + category + " con el nombre «" + name + "».";
    }

    private static String otherKey(String category, String name) {
        return category + "\n" + name.trim().toLowerCase(Locale.ROOT);
    }

    private record Schedule(String status, LocalDate next, Integer daysToNext, Integer daysToEnd, Integer progress) {
    }

    private static final class Totals {
        private int kiosks;
        private int kiosksWithRent;
        private int others;
        private BigDecimal monthly = BigDecimal.ZERO;
        private BigDecimal otherMonthly = BigDecimal.ZERO;
        private int paymentsSoon;
        private BigDecimal paymentsSoonAmount = BigDecimal.ZERO;

        void add(boolean kiosk, String status, Map<String, Integer> byStatus, String landlord, Set<String> landlords,
                 Integer daysToNext, BigDecimal amount) {
            byStatus.merge(status, 1, Integer::sum);
            if (landlord != null && !landlord.isBlank()) {
                landlords.add(landlord);
            }
            if (kiosk) {
                kiosks++;
                if (amount != null) {
                    kiosksWithRent++;
                }
            } else {
                others++;
                if (amount != null) {
                    otherMonthly = otherMonthly.add(amount);
                }
            }
            if (amount != null) {
                monthly = monthly.add(amount);
            }
            if (daysToNext != null && daysToNext <= 7) {
                paymentsSoon++;
                if (amount != null) {
                    paymentsSoonAmount = paymentsSoonAmount.add(amount);
                }
            }
        }

        Summary summary(Map<String, Integer> byStatus) {
            return new Summary(kiosks, kiosksWithRent, monthly, monthly.multiply(BigDecimal.valueOf(12)), paymentsSoon,
                    paymentsSoonAmount, byStatus, others, otherMonthly);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Lectura de celdas
    // ------------------------------------------------------------------------------------------------

    private static Map<String, Integer> headerColumns(Row header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        if (header != null) {
            for (Cell cell : header) {
                String name = normalize(text(cell));
                if (!name.isEmpty()) {
                    columns.putIfAbsent(name, cell.getColumnIndex());
                }
            }
        }
        return columns;
    }

    private static Cell cell(Row row, Map<String, Integer> cols, String header) {
        Integer index = cols.get(normalize(header));
        return index == null ? null : row.getCell(index);
    }

    private static CellType effectiveType(Cell cell) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    }

    private static boolean isBlank(Cell cell) {
        if (cell == null) {
            return true;
        }
        CellType type = effectiveType(cell);
        return type == CellType.BLANK || (type == CellType.STRING && text(cell).isEmpty());
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

    /** Día del mes 1-31; si Excel entregó una fecha completa se toma su día. */
    private static Integer parseDay(Cell cell) {
        int day;
        if (effectiveType(cell) == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            day = cell.getLocalDateTimeCellValue().getDayOfMonth();
        } else if (effectiveType(cell) == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value != Math.rint(value)) {
                throw new NumberFormatException("day");
            }
            day = (int) value;
        } else {
            day = Integer.parseInt(text(cell));
        }
        if (day < 1 || day > 31) {
            throw new NumberFormatException("day");
        }
        return day;
    }

    private static BigDecimal parseAmount(Cell cell) {
        if (effectiveType(cell) == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                throw new NumberFormatException("amount");
            }
            return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        }
        String raw = text(cell).replace("Q", "").replace("q", "").replace(" ", "");
        if (raw.isEmpty()) {
            throw new NumberFormatException("amount");
        }
        int lastComma = raw.lastIndexOf(',');
        int lastDot = raw.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            raw = lastComma > lastDot ? raw.replace(".", "").replace(',', '.') : raw.replace(",", "");
        } else if (lastComma >= 0) {
            raw = raw.replace(',', '.');
        }
        return new BigDecimal(raw);
    }

    private static LocalDate parseDate(Cell cell) {
        if (effectiveType(cell) == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (value < 1) {
                throw new NumberFormatException("date");
            }
            return DateUtil.getLocalDateTime(value).toLocalDate();
        }
        String raw = text(cell);
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // se prueba el siguiente formato
            }
        }
        throw new NumberFormatException("date");
    }

    private static String normalize(String value) {
        String noAccents = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
