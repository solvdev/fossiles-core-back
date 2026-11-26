package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.SupplierEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Código de proveedor (PV001, PV002…): el siguiente número al crear uno, validación al cambiarlo y la carga de los
 * códigos del sistema anterior desde Excel (primero se revisa, después se aplica).
 */
@Service
@RequiredArgsConstructor
public class SupplierCodeService {

    static final String PREFIX = "PV";
    private static final Pattern CODE = Pattern.compile("^(?:PV)?\\s*-?\\s*(\\d{1,6})$");
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 3000;
    /** Parecido mínimo (palabras en común) para proponer un proveedor cuando ni el NIT ni el nombre coinciden. */
    private static final double MIN_SIMILARITY = 0.5;

    private static final String H_CODE = "Código";
    private static final String H_NAME = "Nombre";
    private static final String H_NIT = "NIT (opcional)";
    private static final String H_PHONE = "Teléfono (opcional)";

    public static final String ST_OK = "OK";
    public static final String ST_SAME = "SIN_CAMBIO";
    public static final String ST_REVIEW = "REVISAR";
    public static final String ST_NEW = "NUEVO";
    public static final String ST_ERROR = "ERROR";

    private final SupplierRepository supplierRepository;
    private final SupplierPaymentAccessGuard guard;

    public record Candidate(Long id, String code, String name, String nit) {}

    /** Una fila del Excel ya revisada. {@code supplierId} es la propuesta (null si es nuevo o error). */
    public record ImportRow(int row, String code, String name, String nit, String phone, String status, Long supplierId,
                            String supplierName, String currentCode, String message, List<Candidate> candidates) {}

    public record ImportPreview(int readRows, List<ImportRow> rows, Map<String, Integer> counts) {}

    /** Lo que se aplica de cada fila: a qué proveedor ({@code supplierId}), o crearlo ({@code create}), u omitirla. */
    public record ApplyItem(int row, String code, Long supplierId, boolean create, String name, String nit, String phone) {}

    public record ApplyRequest(List<ApplyItem> items) {}

    public record Recoded(Long id, String name, String oldCode, String newCode) {}

    public record ApplyResult(int updated, int created, int unchanged, List<Recoded> recoded) {}

    // ------------------------------------------------------------------------------------------------
    // Código
    // ------------------------------------------------------------------------------------------------

    /** «pv13», «13», «PV-013» → «PV013». Nulo si no es un código válido. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        Matcher m = CODE.matcher(raw.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(1));
        return n <= 0 ? null : format(n);
    }

    static String format(int n) {
        return PREFIX + String.format("%03d", n);
    }

    /** Número de un código PV (para ordenar y comparar rangos); -1 si no es PV. */
    public static int numberOf(String code) {
        String n = normalize(code);
        return n == null ? -1 : Integer.parseInt(n.substring(PREFIX.length()));
    }

    @Transactional(readOnly = true)
    public String nextCode() {
        return format(maxNumber(supplierRepository.findAll()) + 1);
    }

    private static int maxNumber(List<SupplierEntity> suppliers) {
        int max = 0;
        for (SupplierEntity s : suppliers) {
            max = Math.max(max, numberOf(s.getCode()));
        }
        return max;
    }

    /** Código para un proveedor nuevo: el que se escribió (validado) o el siguiente disponible. */
    @Transactional(readOnly = true)
    public String codeForNew(String requested) throws BusinessException {
        if (requested == null || requested.isBlank()) {
            return nextCode();
        }
        return validFree(requested, null);
    }

    /** Valida el código que se quiere poner a un proveedor ya existente. */
    @Transactional(readOnly = true)
    public String validFree(String requested, Long supplierId) throws BusinessException {
        String code = normalize(requested);
        if (code == null) {
            throw new BusinessException("El código debe ser PV seguido de números, por ejemplo PV013.");
        }
        SupplierEntity holder = supplierRepository.findByCode(code).orElse(null);
        if (holder != null && !holder.getId().equals(supplierId)) {
            throw new BusinessException("El código " + code + " ya es de " + holder.getName() + ".");
        }
        return code;
    }

    // ------------------------------------------------------------------------------------------------
    // Carga desde Excel
    // ------------------------------------------------------------------------------------------------

    public byte[] buildTemplate() throws BusinessException {
        guard.assertCanPay();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            Sheet sheet = wb.createSheet("Codigos");
            String[] headers = {H_CODE, H_NAME, H_NIT, H_PHONE};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(head);
            }
            int[] widths = {12, 50, 18, 20};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            sheet.createFreezePane(0, 1);
            Sheet help = wb.createSheet("Instrucciones");
            String[] lines = {
                    "CÓDIGOS DE PROVEEDOR DEL SISTEMA ANTERIOR",
                    "",
                    "1. Un renglón por proveedor, desde el renglón 2 de la hoja «Codigos».",
                    "2. «Código» como sale en el reporte de pagos: PV013 (también se acepta 13).",
                    "3. «Nombre» y «NIT» sirven para encontrar al proveedor en Fossiles: primero por NIT, luego por nombre.",
                    "4. Si no se encuentra, se propone crearlo con ese nombre, NIT y teléfono.",
                    "5. Al subir el archivo primero se muestra una revisión. No se cambia nada hasta presionar «Aplicar códigos».",
                    "6. Si un proveedor que no viene en el archivo ya tenía uno de esos códigos, se le da el siguiente número libre.",
            };
            for (int i = 0; i < lines.length; i++) {
                help.createRow(i).createCell(0).setCellValue(lines[i]);
            }
            help.setColumnWidth(0, 120 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("No se pudo generar la plantilla de códigos.", e);
        }
    }

    private record ParsedRow(int row, String code, String rawCode, String name, String nit, String phone) {}

    @Transactional(readOnly = true)
    public ImportPreview preview(byte[] content) throws BusinessException {
        guard.assertCanPay();
        if (content == null || content.length == 0) {
            throw new BusinessException("El archivo está vacío.");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido (5 MB).");
        }
        List<ParsedRow> parsed = parse(content);
        List<SupplierEntity> suppliers = supplierRepository.findAll();
        return new ImportPreviewBuilder(suppliers).build(parsed);
    }

    private List<ParsedRow> parse(byte[] content) throws BusinessException {
        List<ParsedRow> rows = new ArrayList<>();
        boolean foundSheet = false;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                Map<String, Integer> cols = ExcelCells.headerColumns(sheet.getRow(sheet.getFirstRowNum()));
                if (!cols.containsKey(ExcelCells.normalize(H_CODE)) || !cols.containsKey(ExcelCells.normalize(H_NAME))) {
                    continue;
                }
                foundSheet = true;
                if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) {
                    throw new BusinessException("El archivo trae demasiados renglones (máximo " + MAX_ROWS + ").");
                }
                for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String rawCode = ExcelCells.text(ExcelCells.cell(row, cols, H_CODE));
                    String name = ExcelCells.text(ExcelCells.cell(row, cols, H_NAME));
                    if (rawCode.isEmpty() && name.isEmpty()) continue;
                    rows.add(new ParsedRow(r + 1, normalize(rawCode), rawCode, name,
                            ExcelCells.text(ExcelCells.cell(row, cols, H_NIT)),
                            ExcelCells.text(ExcelCells.cell(row, cols, H_PHONE))));
                }
                break;
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException("No se pudo leer el archivo. Suba la plantilla en formato .xlsx.");
        }
        if (!foundSheet) {
            throw new BusinessException("El archivo no tiene las columnas «Código» y «Nombre». Use la plantilla descargada del sistema.");
        }
        return rows;
    }

    /** Busca a cada fila su proveedor y marca lo que hay que revisar. Separado para poder probarlo sin base de datos. */
    static final class ImportPreviewBuilder {
        private final List<SupplierEntity> suppliers;
        private final Map<String, SupplierEntity> byNit = new HashMap<>();
        private final Map<String, SupplierEntity> byName = new HashMap<>();

        ImportPreviewBuilder(List<SupplierEntity> suppliers) {
            this.suppliers = suppliers;
            for (SupplierEntity s : suppliers) {
                String nit = nitKey(s.getNit());
                if (!nit.isEmpty()) byNit.putIfAbsent(nit, s);
                if (s.getName() != null) byName.putIfAbsent(nameKey(s.getName()), s);
                if (s.getPayeeName() != null) byName.putIfAbsent(nameKey(s.getPayeeName()), s);
            }
        }

        ImportPreview build(List<ParsedRow> parsed) {
            Map<String, Integer> codeCount = new HashMap<>();
            for (ParsedRow p : parsed) {
                if (p.code() != null) codeCount.merge(p.code(), 1, Integer::sum);
            }
            Set<Long> taken = new HashSet<>();
            List<ImportRow> rows = new ArrayList<>();
            for (ParsedRow p : parsed) {
                rows.add(review(p, codeCount, taken));
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String st : List.of(ST_OK, ST_SAME, ST_REVIEW, ST_NEW, ST_ERROR)) counts.put(st, 0);
            rows.forEach(r -> counts.merge(r.status(), 1, Integer::sum));
            return new ImportPreview(parsed.size(), rows, counts);
        }

        private ImportRow review(ParsedRow p, Map<String, Integer> codeCount, Set<Long> taken) {
            if (p.code() == null) {
                return error(p, p.rawCode().isEmpty() ? "Falta el código." : "«" + p.rawCode() + "» no es un código válido (use PV013).");
            }
            if (codeCount.getOrDefault(p.code(), 0) > 1) {
                return error(p, "El código " + p.code() + " viene más de una vez en el archivo.");
            }
            if (p.name().isEmpty()) {
                return error(p, "Falta el nombre.");
            }
            SupplierEntity exact = byNit.get(nitKey(p.nit()));
            String why = "Mismo NIT.";
            if (exact == null) {
                exact = byName.get(nameKey(p.name()));
                why = "Mismo nombre.";
            }
            if (exact != null) {
                if (!taken.add(exact.getId())) {
                    return error(p, exact.getName() + " ya quedó con otro código en una fila anterior.");
                }
                boolean same = p.code().equals(exact.getCode());
                return new ImportRow(p.row(), p.code(), p.name(), p.nit(), p.phone(), same ? ST_SAME : ST_OK,
                        exact.getId(), exact.getName(), exact.getCode(), same ? "Ya tiene ese código." : why,
                        List.of(candidate(exact)));
            }
            List<Candidate> candidates = similar(p.name(), taken);
            if (!candidates.isEmpty()) {
                Candidate best = candidates.get(0);
                taken.add(best.id());
                return new ImportRow(p.row(), p.code(), p.name(), p.nit(), p.phone(), ST_REVIEW, best.id(), best.name(),
                        best.code(), "Nombre parecido. Confirme que es el mismo proveedor.", candidates);
            }
            return new ImportRow(p.row(), p.code(), p.name(), p.nit(), p.phone(), ST_NEW, null, null, null,
                    "No está en Fossiles. Se crea con este código.", List.of());
        }

        private static ImportRow error(ParsedRow p, String message) {
            return new ImportRow(p.row(), p.code() != null ? p.code() : p.rawCode(), p.name(), p.nit(), p.phone(),
                    ST_ERROR, null, null, null, message, List.of());
        }

        /** Hasta 3 proveedores con más palabras en común; el nombre del Excel se corta en «/» («CRISMA / CHEQUE A/N…»). */
        private List<Candidate> similar(String name, Set<Long> taken) {
            Set<String> words = words(name.split("/")[0]);
            Set<String> all = words(name);
            record Scored(SupplierEntity s, double score) {}
            List<Scored> scored = new ArrayList<>();
            for (SupplierEntity s : suppliers) {
                if (taken.contains(s.getId()) || s.getName() == null) continue;
                Set<String> other = words(s.getName());
                double score = Math.max(jaccard(words, other), jaccard(all, other));
                if (score >= MIN_SIMILARITY) scored.add(new Scored(s, score));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparing(sc -> sc.s().getName(), String.CASE_INSENSITIVE_ORDER));
            return scored.stream().limit(3).map(sc -> candidate(sc.s())).toList();
        }

        private static Candidate candidate(SupplierEntity s) {
            return new Candidate(s.getId(), s.getCode(), s.getName(), s.getNit());
        }
    }

    private static final Set<String> STOP_WORDS = Set.of("s", "a", "sa", "de", "del", "la", "el", "y", "sociedad",
            "anonima", "ltda", "cia", "los", "las");

    static Set<String> words(String value) {
        Set<String> out = new HashSet<>();
        for (String w : SupplierPaymentService.normalizeName(value).replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (!w.isBlank() && !STOP_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    static String nitKey(String nit) {
        return nit == null ? "" : nit.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    static String nameKey(String name) {
        return SupplierPaymentService.normalizeName(name).replaceAll("[.,]", "").replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------------------------------------
    // Aplicar
    // ------------------------------------------------------------------------------------------------

    @Transactional
    public ApplyResult apply(ApplyRequest request) throws BusinessException {
        Long userId = guard.assertCanPay();
        List<ApplyItem> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.isEmpty()) {
            throw new BusinessException("No hay filas para aplicar.");
        }
        List<SupplierEntity> all = supplierRepository.findAll();
        Map<Long, SupplierEntity> byId = new HashMap<>();
        all.forEach(s -> byId.put(s.getId(), s));

        Map<Long, String> assign = new LinkedHashMap<>();
        List<ApplyItem> creates = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Set<String> nits = new HashSet<>();
        all.forEach(s -> { if (!nitKey(s.getNit()).isEmpty()) nits.add(nitKey(s.getNit())); });
        int unchanged = 0;
        for (ApplyItem item : items) {
            String code = normalize(item.code());
            if (code == null) {
                throw new BusinessException("Fila " + item.row() + ": el código no es válido.");
            }
            if (!codes.add(code)) {
                throw new BusinessException("Fila " + item.row() + ": el código " + code + " se repite.");
            }
            if (item.create()) {
                if (item.name() == null || item.name().isBlank()) {
                    throw new BusinessException("Fila " + item.row() + ": falta el nombre del proveedor nuevo.");
                }
                String nit = nitKey(item.nit());
                if (!nit.isEmpty() && !nits.add(nit)) {
                    throw new BusinessException("Fila " + item.row() + ": ya hay un proveedor con el NIT " + item.nit() + ".");
                }
                creates.add(new ApplyItem(item.row(), code, null, true, item.name(), item.nit(), item.phone()));
                continue;
            }
            SupplierEntity s = item.supplierId() == null ? null : byId.get(item.supplierId());
            if (s == null) {
                throw new BusinessException("Fila " + item.row() + ": el proveedor no existe.");
            }
            if (assign.containsKey(s.getId())) {
                throw new BusinessException("Fila " + item.row() + ": " + s.getName() + " ya tiene código en otra fila.");
            }
            if (code.equals(s.getCode())) {
                unchanged++;
            }
            assign.put(s.getId(), code);
        }

        // Quien tenga hoy uno de esos códigos y no venga en el archivo recibe después el siguiente libre.
        List<SupplierEntity> displaced = new ArrayList<>();
        for (SupplierEntity s : all) {
            if (s.getCode() != null && codes.contains(s.getCode()) && !assign.containsKey(s.getId())) {
                displaced.add(s);
            }
        }
        Map<Long, String> oldCodes = new HashMap<>();
        List<SupplierEntity> touched = new ArrayList<>();
        for (Long id : assign.keySet()) touched.add(byId.get(id));
        touched.addAll(displaced);
        for (SupplierEntity s : touched) {
            oldCodes.put(s.getId(), s.getCode());
            s.setCode(null);
        }
        // Primero se liberan los códigos (el índice es único); luego se ponen los nuevos.
        supplierRepository.saveAllAndFlush(touched);
        int updated = 0;
        for (Map.Entry<Long, String> e : assign.entrySet()) {
            SupplierEntity s = byId.get(e.getKey());
            s.setCode(e.getValue());
            s.setUpdatedBy(userId);
            if (!e.getValue().equals(oldCodes.get(s.getId()))) updated++;
        }
        supplierRepository.saveAllAndFlush(assign.keySet().stream().map(byId::get).toList());
        for (ApplyItem c : creates) {
            supplierRepository.save(SupplierEntity.builder()
                    .code(c.code())
                    .name(c.name().trim().toUpperCase(Locale.ROOT))
                    .nit(nitKey(c.nit()).isEmpty() ? null : c.nit().trim())
                    .phone(c.phone() == null || c.phone().isBlank() ? null : c.phone().trim())
                    .status("active")
                    .createdBy(userId)
                    .build());
        }
        supplierRepository.flush();
        int next = maxNumber(supplierRepository.findAll());
        List<Recoded> recoded = new ArrayList<>();
        displaced.sort(Comparator.comparing(SupplierEntity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        for (SupplierEntity s : displaced) {
            String code = format(++next);
            s.setCode(code);
            s.setUpdatedBy(userId);
            recoded.add(new Recoded(s.getId(), s.getName(), oldCodes.get(s.getId()), code));
        }
        supplierRepository.saveAllAndFlush(displaced);
        return new ApplyResult(updated, creates.size(), unchanged, recoded);
    }
}
