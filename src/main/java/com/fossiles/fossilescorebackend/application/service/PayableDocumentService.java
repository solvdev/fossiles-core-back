package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.DocImportChange;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.DocImportError;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.DocImportResult;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.PayablesResponse;
import com.fossiles.fossilescorebackend.application.dto.response.SupplierPaymentDtos.RentGenerationResult;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.PayableDocumentEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.PayableDocumentRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierPaymentAllocationRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import com.fossiles.fossilescorebackend.infrastructure.util.GuatemalaDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Documentos por pagar: lo que se debe y no viene de una orden de compra. Se capturan a mano, se cargan por Excel (saldos
 * iniciales) o se generan cada mes desde Rentas. Una vez creados se pagan igual que las órdenes de compra.
 */
@Service
@RequiredArgsConstructor
public class PayableDocumentService {

    private static final String H_PAYEE = "Proveedor o arrendador";
    private static final String H_NIT = "NIT (opcional)";
    private static final String H_CONCEPT = "Concepto";
    private static final String H_NUMBER = "No. documento";
    private static final String H_DATE = "Fecha";
    private static final String H_DUE = "Vence";
    private static final String H_AMOUNT = "Monto";

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 2000;

    private final PayableDocumentRepository documentRepository;
    private final SupplierPaymentAllocationRepository allocationRepository;
    private final SupplierRepository supplierRepository;
    private final KioskRentService rentService;
    private final SupplierPaymentService paymentService;
    private final SupplierPaymentAccessGuard guard;

    public record CreateRequest(Long supplierId, String payeeName, String concept, String documentNumber,
                                LocalDate issueDate, LocalDate dueDate, BigDecimal amount) {}

    /** Proveedores por NIT y por nombre, para ligar un documento con su proveedor cuando se puede. */
    private record SupplierIndex(Map<String, SupplierEntity> byNit, Map<String, SupplierEntity> byName) {
        SupplierEntity find(String name, String nit) {
            String nitKey = nitKey(nit);
            if (!nitKey.isEmpty() && byNit.containsKey(nitKey)) {
                return byNit.get(nitKey);
            }
            return byName.get(SupplierPaymentService.normalizeName(name));
        }
    }

    private static String nitKey(String nit) {
        return nit == null ? "" : nit.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private SupplierIndex supplierIndex() {
        Map<String, SupplierEntity> byNit = new HashMap<>();
        Map<String, SupplierEntity> byName = new HashMap<>();
        for (SupplierEntity s : supplierRepository.findAll()) {
            if (s.getNit() != null && !nitKey(s.getNit()).isEmpty()) {
                byNit.putIfAbsent(nitKey(s.getNit()), s);
            }
            if (s.getName() != null) {
                byName.putIfAbsent(SupplierPaymentService.normalizeName(s.getName()), s);
            }
            if (s.getPayeeName() != null) {
                byName.putIfAbsent(SupplierPaymentService.normalizeName(s.getPayeeName()), s);
            }
        }
        return new SupplierIndex(byNit, byName);
    }

    // ------------------------------------------------------------------------------------------------
    // A mano
    // ------------------------------------------------------------------------------------------------

    @Transactional
    public PayablesResponse create(CreateRequest request) throws BusinessException {
        Long userId = guard.assertCanPay();
        SupplierEntity supplier = request.supplierId() == null ? null : supplierRepository.findById(request.supplierId())
                .orElseThrow(() -> new BusinessException("El proveedor no existe."));
        String payee = supplier != null ? supplier.getName() : clean(request.payeeName());
        if (payee == null) {
            throw new BusinessException("Indique a quién se le debe.");
        }
        if (clean(request.concept()) == null) {
            throw new BusinessException("Indique el concepto.");
        }
        if (request.issueDate() == null || request.dueDate() == null) {
            throw new BusinessException("Indique la fecha del documento y cuándo vence.");
        }
        if (request.dueDate().isBefore(request.issueDate())) {
            throw new BusinessException("«Vence» no puede ser anterior a la fecha del documento.");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BusinessException("El monto debe ser mayor que cero.");
        }
        if (payee.length() > 200 || request.concept().length() > 300) {
            throw new BusinessException("El nombre o el concepto son demasiado largos.");
        }
        documentRepository.save(PayableDocumentEntity.builder()
                .supplierId(supplier != null ? supplier.getId() : null)
                .payeeName(payee)
                .concept(request.concept().trim())
                .documentNumber(clean(request.documentNumber()))
                .issueDate(request.issueDate())
                .dueDate(request.dueDate())
                .amount(request.amount().setScale(2, java.math.RoundingMode.HALF_UP))
                .kind(PayableDocumentEntity.KIND_MANUAL)
                .createdBy(userId)
                .build());
        return paymentService.payables();
    }

    @Transactional
    public PayablesResponse voidDocument(Long id, String reason) throws BusinessException {
        Long userId = guard.assertCanPay();
        PayableDocumentEntity document = documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException("El documento no existe."));
        if (!PayableDocumentEntity.STATUS_ACTIVE.equals(document.getStatus())) {
            throw new BusinessException("El documento ya está anulado.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Indique el motivo de la anulación.");
        }
        if (allocationRepository.paidForDocument(id).signum() > 0) {
            throw new BusinessException("El documento ya tiene pagos. Anule primero esos pagos.");
        }
        document.setStatus(PayableDocumentEntity.STATUS_VOID);
        document.setVoidReason(reason.trim());
        document.setVoidedAt(GuatemalaDateTime.now());
        document.setVoidedBy(userId);
        documentRepository.save(document);
        return paymentService.payables();
    }

    // ------------------------------------------------------------------------------------------------
    // Rentas del mes
    // ------------------------------------------------------------------------------------------------

    /**
     * Genera un documento por pagar por cada renta del mes ({@code month} «YYYY-MM»): el arrendador es el beneficiario, el
     * monto sale de Finanzas y vence el día de pago del contrato. Se puede repetir sin duplicar.
     */
    @Transactional
    public RentGenerationResult generateRents(String month) throws BusinessException {
        Long userId = guard.assertCanPay();
        YearMonth period;
        try {
            period = YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException("Indique el mes con el formato AAAA-MM.");
        }
        KioskRentService.RentsDue due = rentService.rentsDueIn(period);
        Set<Long> existing = new HashSet<>();
        documentRepository.findByKindAndPeriodAndStatus(PayableDocumentEntity.KIND_RENT, period.toString(),
                PayableDocumentEntity.STATUS_ACTIVE).forEach(d -> existing.add(d.getLocationId()));
        SupplierIndex suppliers = supplierIndex();
        int created = 0;
        int already = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (KioskRentService.RentDue rent : due.due()) {
            if (existing.contains(rent.locationId())) {
                already++;
                continue;
            }
            SupplierEntity supplier = suppliers.find(rent.landlord(), null);
            documentRepository.save(PayableDocumentEntity.builder()
                    .supplierId(supplier != null ? supplier.getId() : null)
                    .payeeName(supplier != null ? supplier.getName() : rent.landlord())
                    .concept("Renta de " + rent.name() + " · " + period)
                    .documentNumber("RENTA-" + (rent.code() != null ? rent.code() : rent.locationId()) + "-" + period)
                    .issueDate(rent.payDate())
                    .dueDate(rent.payDate())
                    .amount(rent.amount())
                    .kind(PayableDocumentEntity.KIND_RENT)
                    .locationId(rent.locationId())
                    .period(period.toString())
                    .createdBy(userId)
                    .build());
            created++;
            total = total.add(rent.amount());
        }
        return new RentGenerationResult(period.toString(), created, already, total, due.skipped());
    }

    // ------------------------------------------------------------------------------------------------
    // Saldos iniciales por Excel
    // ------------------------------------------------------------------------------------------------

    public byte[] buildTemplate() throws BusinessException {
        guard.assertCanPay();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            DataFormat format = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            Sheet sheet = wb.createSheet("Saldos");
            String[] headers = {H_PAYEE, H_NIT, H_CONCEPT, H_NUMBER, H_DATE, H_DUE, H_AMOUNT};
            Row row = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = row.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(head);
            }
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy"));
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(format.getFormat("#,##0.00"));
            sheet.setDefaultColumnStyle(4, date);
            sheet.setDefaultColumnStyle(5, date);
            sheet.setDefaultColumnStyle(6, money);
            int[] widths = {40, 18, 40, 18, 14, 14, 16};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);

            Sheet help = wb.createSheet("Instrucciones");
            String[] lines = {
                    "SALDOS INICIALES POR PAGAR",
                    "",
                    "1. Un renglón por cada factura o deuda pendiente. Empiece en el renglón 2 de la hoja «Saldos».",
                    "2. «Proveedor o arrendador» es a quien se le debe. Si coincide con un proveedor del sistema (por NIT o por nombre) se liga con él.",
                    "3. «Concepto», «Fecha» y «Monto» son obligatorios. «Vence» es opcional: si falta, se usan los días de crédito del proveedor (30 por defecto).",
                    "4. Fechas como 31/12/2026. Monto sin símbolo, por ejemplo 1250.50.",
                    "5. Al subir el archivo primero se muestra una revisión. Si hay algún error no se guarda nada hasta corregirlo.",
                    "6. Un mismo «No. documento» del mismo beneficiario y monto no se carga dos veces.",
            };
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 130 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("No se pudo generar la plantilla de saldos.", e);
        }
    }

    @Transactional
    public DocImportResult importOpening(byte[] content, boolean apply) throws BusinessException {
        Long userId = guard.assertCanPay();
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo está vacío.");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido (5 MB).");
        }
        SupplierIndex suppliers = supplierIndex();
        Set<String> existingKeys = new HashSet<>();
        for (PayableDocumentEntity d : documentRepository.findByStatus(PayableDocumentEntity.STATUS_ACTIVE)) {
            if (d.getDocumentNumber() != null) {
                existingKeys.add(documentKey(SupplierPaymentService.payeeKeyOf(d.getSupplierId(), d.getPayeeName()),
                        d.getDocumentNumber(), d.getAmount()));
            }
        }

        List<DocImportError> errors = new ArrayList<>();
        List<DocImportChange> changes = new ArrayList<>();
        List<PayableDocumentEntity> pending = new ArrayList<>();
        int read = 0;
        boolean foundSheet = false;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                Map<String, Integer> cols = ExcelCells.headerColumns(sheet.getRow(sheet.getFirstRowNum()));
                if (!cols.containsKey(ExcelCells.normalize(H_PAYEE)) || !cols.containsKey(ExcelCells.normalize(H_AMOUNT))) {
                    continue;
                }
                foundSheet = true;
                if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) {
                    throw new BusinessException("El archivo trae demasiados renglones (máximo " + MAX_ROWS + ").");
                }
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    Cell payeeCell = ExcelCells.cell(row, cols, H_PAYEE);
                    Cell nitCell = ExcelCells.cell(row, cols, H_NIT);
                    Cell conceptCell = ExcelCells.cell(row, cols, H_CONCEPT);
                    Cell numberCell = ExcelCells.cell(row, cols, H_NUMBER);
                    Cell dateCell = ExcelCells.cell(row, cols, H_DATE);
                    Cell dueCell = ExcelCells.cell(row, cols, H_DUE);
                    Cell amountCell = ExcelCells.cell(row, cols, H_AMOUNT);
                    if (ExcelCells.isBlank(payeeCell) && ExcelCells.isBlank(amountCell) && ExcelCells.isBlank(conceptCell)
                            && ExcelCells.isBlank(dateCell) && ExcelCells.isBlank(numberCell)) {
                        continue;
                    }
                    read++;
                    int rowNumber = r + 1;
                    String payee = ExcelCells.text(payeeCell);
                    List<String> problems = new ArrayList<>();
                    if (payee.isEmpty()) {
                        problems.add("falta a quién se le debe");
                    } else if (payee.length() > 200) {
                        problems.add("el nombre es demasiado largo");
                    }
                    BigDecimal amount = null;
                    try {
                        amount = ExcelCells.isBlank(amountCell) ? null : ExcelCells.parseAmount(amountCell);
                    } catch (RuntimeException e) {
                        problems.add("el monto no es un número");
                    }
                    if (amount == null && !problems.contains("el monto no es un número")) {
                        problems.add("falta el monto");
                    } else if (amount != null && amount.signum() <= 0) {
                        problems.add("el monto debe ser mayor que cero");
                    }
                    LocalDate issue = null;
                    try {
                        issue = ExcelCells.isBlank(dateCell) ? null : ExcelCells.parseDate(dateCell);
                    } catch (RuntimeException e) {
                        problems.add("la fecha no es válida (use 31/12/2026)");
                    }
                    if (issue == null && !problems.contains("la fecha no es válida (use 31/12/2026)")) {
                        problems.add("falta la fecha");
                    }
                    LocalDate due = null;
                    try {
                        due = ExcelCells.isBlank(dueCell) ? null : ExcelCells.parseDate(dueCell);
                    } catch (RuntimeException e) {
                        problems.add("«Vence» no es una fecha válida");
                    }
                    String concept = ExcelCells.isBlank(conceptCell) ? "Saldo inicial" : ExcelCells.text(conceptCell);
                    if (concept.length() > 300) {
                        problems.add("el concepto es demasiado largo");
                    }
                    if (!problems.isEmpty()) {
                        errors.add(new DocImportError(rowNumber, payee, "Hay que corregir: " + String.join("; ", problems) + "."));
                        continue;
                    }
                    SupplierEntity supplier = suppliers.find(payee, ExcelCells.text(nitCell));
                    if (due == null) {
                        int days = supplier != null && supplier.getCreditDays() != null ? supplier.getCreditDays()
                                : SupplierPaymentService.DEFAULT_CREDIT_DAYS;
                        due = issue.plusDays(days);
                    }
                    if (due.isBefore(issue)) {
                        errors.add(new DocImportError(rowNumber, payee, "«Vence» no puede ser anterior a «Fecha»."));
                        continue;
                    }
                    String number = ExcelCells.isBlank(numberCell) ? null : ExcelCells.text(numberCell);
                    String payeeName = supplier != null ? supplier.getName() : payee;
                    if (number != null) {
                        String key = documentKey(SupplierPaymentService.payeeKeyOf(supplier != null ? supplier.getId() : null, payeeName),
                                number, amount);
                        if (!existingKeys.add(key)) {
                            errors.add(new DocImportError(rowNumber, payee, "El documento «" + number + "» por ese monto ya está cargado."));
                            continue;
                        }
                    }
                    pending.add(PayableDocumentEntity.builder()
                            .supplierId(supplier != null ? supplier.getId() : null)
                            .payeeName(payeeName)
                            .concept(concept)
                            .documentNumber(number)
                            .issueDate(issue)
                            .dueDate(due)
                            .amount(amount)
                            .kind(PayableDocumentEntity.KIND_OPENING)
                            .createdBy(userId)
                            .build());
                    changes.add(new DocImportChange(payeeName, supplier != null, concept, number, issue, due, amount));
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
        if (!foundSheet) {
            throw new BusinessException("El archivo no tiene las columnas de la plantilla. Use la plantilla descargada del sistema.");
        }
        boolean applied = apply && errors.isEmpty() && !pending.isEmpty();
        if (applied) {
            documentRepository.saveAll(pending);
        }
        BigDecimal total = changes.stream().map(DocImportChange::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DocImportResult(applied, read, total, changes, errors);
    }

    private static String documentKey(String payeeKey, String number, BigDecimal amount) {
        return payeeKey + "|" + number.trim().toLowerCase(Locale.ROOT) + "|" + amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String clean(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
