package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.Change;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.ListResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RentRow;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.RowError;
import com.fossiles.fossilescorebackend.application.dto.response.KioskRentDtos.Summary;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskRentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.LocationEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskFixedCostRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskRentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.LocationRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Contabilidad > Rentas: por kiosco, a quién se le paga (arrendador), el día de pago del mes y la vigencia del
 * contrato. El monto de la renta NO se guarda aquí: se lee de Finanzas (costo fijo ALQUILER, último mes cargado),
 * donde ya vive y no cambia en todo el año hasta que se renueva el contrato.
 *
 * <p>Contabilidad lo llena a mano o con una plantilla de Excel. La subida primero se revisa y luego se aplica; una
 * celda vacía no borra lo que ya hay, y con un error en cualquier fila no se aplica nada.
 */
@Service
@RequiredArgsConstructor
public class KioskRentService {

    static final String RENT_CATEGORY = "ALQUILER";
    static final String KIOSK_CATEGORY = "KIOSKO";
    static final String SHEET_RENTS = "Rentas";
    static final String SHEET_HELP = "Instrucciones";

    private static final String H_ID = "ID";
    private static final String H_CODE = "Código";
    private static final String H_NAME = "Kiosco";
    private static final String H_RENT = "Renta mensual (Q) - de Finanzas";
    private static final String H_LANDLORD = "Arrendador";
    private static final String H_DAY = "Día de pago";
    private static final String H_FROM = "Contrato desde";
    private static final String H_TO = "Contrato hasta";
    private static final String H_NOTE = "Notas";

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_LANDLORD = 200;
    private static final int MAX_NOTE = 500;
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private final KioskRentRepository rentRepository;
    private final LocationRepository locationRepository;
    private final KioskFixedCostRepository fixedCostRepository;
    private final KioskRentAccessGuard guard;

    /** Lo que se guarda de un kiosco (todo opcional; el monto no va aquí). */
    public record Values(String landlordName, Integer paymentDay, LocalDate contractStart, LocalDate contractEnd,
                         String note) {
        boolean isEmpty() {
            return landlordName == null && paymentDay == null && contractStart == null && contractEnd == null
                    && note == null;
        }
    }

    private record Rent(BigDecimal amount, String period) {}

    // ------------------------------------------------------------------------------------------------
    // Lista, guardar, quitar
    // ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ListResponse list() throws BusinessException {
        guard.assertCanView();
        return buildList(GuatemalaDateTime.today());
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
        return buildList(GuatemalaDateTime.today());
    }

    @Transactional
    public ListResponse clear(Long locationId) throws BusinessException {
        guard.assertCanEdit();
        findKiosk(locationId);
        rentRepository.findById(locationId).ifPresent(rentRepository::delete);
        return buildList(GuatemalaDateTime.today());
    }

    ListResponse buildList(LocalDate today) {
        Map<Long, KioskRentEntity> rents = new HashMap<>();
        rentRepository.findAll().forEach(r -> rents.put(r.getLocationId(), r));
        Map<Long, Rent> finance = financeRents();

        List<RentRow> rows = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of(KioskRentCalculator.VIGENTE, KioskRentCalculator.POR_VENCER,
                KioskRentCalculator.VENCIDO, KioskRentCalculator.INCOMPLETO, KioskRentCalculator.SIN_CARGAR)) {
            byStatus.put(s, 0);
        }
        Set<String> landlords = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        BigDecimal totalMonthly = BigDecimal.ZERO;
        int withRent = 0;
        int paymentsSoon = 0;
        BigDecimal paymentsSoonAmount = BigDecimal.ZERO;

        for (LocationEntity kiosk : rentKiosks()) {
            KioskRentEntity r = rents.get(kiosk.getId());
            Rent rent = finance.get(kiosk.getId());
            String landlord = r != null ? r.getLandlordName() : null;
            Integer day = r != null ? r.getPaymentDay() : null;
            LocalDate start = r != null ? r.getContractStart() : null;
            LocalDate end = r != null ? r.getContractEnd() : null;
            String status = KioskRentCalculator.status(landlord, day, start, end, today);
            LocalDate next = KioskRentCalculator.nextPaymentDate(day, start, end, today);
            Integer daysToNext = next != null ? (int) ChronoUnit.DAYS.between(today, next) : null;
            Integer daysToEnd = end != null ? (int) ChronoUnit.DAYS.between(today, end) : null;

            byStatus.merge(status, 1, Integer::sum);
            if (landlord != null && !landlord.isBlank()) {
                landlords.add(landlord);
            }
            if (rent != null) {
                withRent++;
                totalMonthly = totalMonthly.add(rent.amount());
            }
            if (daysToNext != null && daysToNext <= 7) {
                paymentsSoon++;
                if (rent != null) {
                    paymentsSoonAmount = paymentsSoonAmount.add(rent.amount());
                }
            }
            rows.add(new RentRow(kiosk.getId(), kiosk.getCode(), kiosk.getName(), landlord,
                    rent != null ? rent.amount() : null, rent != null ? rent.period() : null, day, start, end,
                    r != null ? r.getNote() : null, status, next, daysToNext, daysToEnd,
                    KioskRentCalculator.progressPct(start, end, today),
                    r != null ? (r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt()) : null));
        }
        Summary summary = new Summary(rows.size(), withRent, totalMonthly, totalMonthly.multiply(BigDecimal.valueOf(12)),
                paymentsSoon, paymentsSoonAmount, byStatus);
        boolean canEdit;
        try {
            canEdit = guard.canEdit();
        } catch (BusinessException e) {
            canEdit = false;
        }
        return new ListResponse(today, KioskRentCalculator.EXPIRING_DAYS, canEdit, summary, rows, new ArrayList<>(landlords));
    }

    /** Una renta que toca pagar en un mes. */
    public record RentDue(Long locationId, String code, String name, String landlord, BigDecimal amount, LocalDate payDate) {}

    /** Las rentas de un mes y los kioscos que se saltaron, con la razón. */
    public record RentsDue(List<RentDue> due, List<String> skipped) {}

    /**
     * Rentas que se pagan en el mes: kioscos con arrendador, día de pago, contrato que cubre la fecha de pago y renta
     * en Finanzas. Sin permisos: quien lo llama los valida. Los kioscos sin ningún dato cargado se dejan fuera sin avisar.
     */
    @Transactional(readOnly = true)
    public RentsDue rentsDueIn(java.time.YearMonth month) {
        Map<Long, KioskRentEntity> rents = new HashMap<>();
        rentRepository.findAll().forEach(r -> rents.put(r.getLocationId(), r));
        Map<Long, Rent> finance = financeRents();
        List<RentDue> due = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (LocationEntity kiosk : rentKiosks()) {
            KioskRentEntity r = rents.get(kiosk.getId());
            if (r == null || valuesOf(r).isEmpty()) {
                continue;
            }
            String name = kiosk.getName();
            if (r.getLandlordName() == null || r.getPaymentDay() == null) {
                skipped.add(name + ": faltan el arrendador o el día de pago.");
                continue;
            }
            LocalDate payDate = month.atDay(Math.min(r.getPaymentDay(), month.lengthOfMonth()));
            if ((r.getContractStart() != null && payDate.isBefore(r.getContractStart()))
                    || (r.getContractEnd() != null && payDate.isAfter(r.getContractEnd()))) {
                skipped.add(name + ": el contrato no cubre el " + payDate + ".");
                continue;
            }
            Rent rent = finance.get(kiosk.getId());
            if (rent == null) {
                skipped.add(name + ": Finanzas no tiene la renta.");
                continue;
            }
            due.add(new RentDue(kiosk.getId(), kiosk.getCode(), name, r.getLandlordName(), rent.amount(), payDate));
        }
        return new RentsDue(due, skipped);
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

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat format = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headFixed = style(wb, bold, IndexedColors.GREY_25_PERCENT, null);
            CellStyle headInput = style(wb, bold, IndexedColors.LIGHT_YELLOW, null);
            CellStyle fixed = style(wb, null, IndexedColors.GREY_25_PERCENT, null);
            CellStyle fixedMoney = style(wb, null, IndexedColors.GREY_25_PERCENT, format.getFormat("#,##0.00"));
            CellStyle input = style(wb, null, IndexedColors.LIGHT_YELLOW, null);
            CellStyle inputDate = style(wb, null, IndexedColors.LIGHT_YELLOW, format.getFormat("dd/mm/yyyy"));

            Sheet sheet = wb.createSheet(SHEET_RENTS);
            String[] headers = {H_ID, H_CODE, H_NAME, H_RENT, H_LANDLORD, H_DAY, H_FROM, H_TO, H_NOTE};
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(i <= 3 ? headFixed : headInput);
            }
            int rowIndex = 1;
            for (LocationEntity kiosk : kiosks) {
                KioskRentEntity r = rents.get(kiosk.getId());
                Rent rent = finance.get(kiosk.getId());
                Row row = sheet.createRow(rowIndex++);
                fixedCell(row, 0, fixed).setCellValue(kiosk.getId());
                fixedCell(row, 1, fixed).setCellValue(safe(kiosk.getCode()));
                fixedCell(row, 2, fixed).setCellValue(safe(kiosk.getName()));
                Cell rentCell = fixedCell(row, 3, fixedMoney);
                if (rent != null) {
                    rentCell.setCellValue(rent.amount().doubleValue());
                }
                row.createCell(4).setCellStyle(input);
                row.createCell(5).setCellStyle(input);
                row.createCell(6).setCellStyle(inputDate);
                row.createCell(7).setCellStyle(inputDate);
                row.createCell(8).setCellStyle(input);
                if (r != null) {
                    if (r.getLandlordName() != null) {
                        row.getCell(4).setCellValue(r.getLandlordName());
                    }
                    if (r.getPaymentDay() != null) {
                        row.getCell(5).setCellValue(r.getPaymentDay());
                    }
                    if (r.getContractStart() != null) {
                        row.getCell(6).setCellValue(r.getContractStart());
                    }
                    if (r.getContractEnd() != null) {
                        row.getCell(7).setCellValue(r.getContractEnd());
                    }
                    if (r.getNote() != null) {
                        row.getCell(8).setCellValue(r.getNote());
                    }
                }
            }
            sheet.createFreezePane(3, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rowIndex - 1, 0), 0, headers.length - 1));
            int[] widths = {8, 14, 30, 24, 38, 12, 16, 16, 36};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            DataValidationHelper helper = sheet.getDataValidationHelper();
            DataValidationConstraint dayRule = helper.createIntegerConstraint(
                    DataValidationConstraint.OperatorType.BETWEEN, "1", "31");
            DataValidation dayValidation = helper.createValidation(dayRule,
                    new CellRangeAddressList(1, Math.max(rowIndex - 1, 1), 5, 5));
            dayValidation.setShowErrorBox(true);
            dayValidation.createErrorBox("Día de pago", "Escriba un día del mes entre 1 y 31.");
            sheet.addValidationData(dayValidation);

            Sheet help = wb.createSheet(SHEET_HELP);
            String[] lines = {
                    "PLANTILLA DE RENTAS DE KIOSCOS",
                    "",
                    "1. Las columnas grises (ID, Código, Kiosco y Renta) no se tocan. La renta sale de Finanzas > Costos por kiosco y aquí no se sube.",
                    "2. Llene solo las columnas amarillas: Arrendador (a quien se le paga), Día de pago (1 a 31), Contrato desde, Contrato hasta y Notas.",
                    "3. Las fechas se escriben como 31/12/2026.",
                    "4. Si el mes tiene menos días que el día de pago (por ejemplo, 30 en febrero), se toma el último día del mes.",
                    "5. Una celda vacía NO borra lo que ya está guardado. Para quitar los datos de un kiosco use «Quitar» en la pantalla Rentas.",
                    "6. Al subir el archivo primero se muestra una revisión; si hay algún error no se guarda nada hasta corregirlo.",
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

    // ------------------------------------------------------------------------------------------------
    // Plantilla de Excel (subida)
    // ------------------------------------------------------------------------------------------------

    private record Pending(Long locationId, Values values) {}

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

        List<RowError> errors = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int[] counters = new int[3]; // leídas, vacías, sin cambio
        boolean foundSheet = false;

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                Map<String, Integer> cols = headerColumns(sheet.getRow(sheet.getFirstRowNum()));
                if (!cols.containsKey(normalize(H_ID)) || !cols.containsKey(normalize(H_LANDLORD))) {
                    continue; // otra hoja (p. ej. Instrucciones)
                }
                foundSheet = true;
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    readRow(sheet.getRow(r), r + 1, cols, kiosks, existing, seen, errors, changes, pending, counters);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
        if (!foundSheet) {
            throw new BusinessException("El archivo no tiene las columnas de la plantilla. Use la plantilla descargada del sistema.");
        }

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
        }
        return new ImportResult(applied, counters[0], counters[1], counters[2], changes, errors);
    }

    private void readRow(Row row, int rowNumber, Map<String, Integer> cols, Map<Long, LocationEntity> kiosks,
                         Map<Long, KioskRentEntity> existing, Set<Long> seen, List<RowError> errors,
                         List<Change> changes, List<Pending> pending, int[] counters) {
        if (row == null) {
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
            errors.add(new RowError(rowNumber, null, text(codeCell), null, "El ID no es válido. No lo modifique."));
            return;
        }
        LocationEntity kiosk = kiosks.get(id);
        if (kiosk == null) {
            errors.add(new RowError(rowNumber, id, text(codeCell), null, "El ID " + id + " no es un kiosco del sistema."));
            return;
        }
        String name = kiosk.getName();
        if (!isBlank(codeCell) && !text(codeCell).equalsIgnoreCase(safe(kiosk.getCode()))) {
            errors.add(new RowError(rowNumber, id, kiosk.getCode(), name,
                    "El código «" + text(codeCell) + "» no coincide con el del kiosco (" + safe(kiosk.getCode()) + ")."));
            return;
        }
        if (!seen.add(id)) {
            errors.add(new RowError(rowNumber, id, kiosk.getCode(), name, "El kiosco aparece más de una vez en el archivo."));
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
            errors.add(new RowError(rowNumber, id, kiosk.getCode(), name, String.join("; ", problems) + "."));
            return;
        }
        if (merged.equals(before)) {
            counters[2]++;
            return;
        }
        pending.add(new Pending(id, merged));
        changes.add(new Change(id, kiosk.getCode(), name, merged.landlordName(), merged.paymentDay(),
                merged.contractStart(), merged.contractEnd(), before.contractEnd(),
                existing.get(id) == null ? "NEW" : "UPDATE"));
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
