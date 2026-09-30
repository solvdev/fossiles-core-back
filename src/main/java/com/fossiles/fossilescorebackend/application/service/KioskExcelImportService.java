package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.request.KioskExcelCommitRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelBatchSummaryResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelCommitResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelIssueDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelPreviewResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Importador de los Excel mensuales de ventas/costos por kiosco (preview -> commit -> revert).
 * Todo el acceso a datos es SQL directo sobre las tablas de {@code scripts/migration-kiosk-financials.sql}.
 * Ver docs/KIOSK-FINANCIALS-CONTRACT.md, sección "Importación Excel".
 */
@Service
public class KioskExcelImportService {

    static final String SOURCE_EXCEL = "EXCEL";
    static final String STATUS_APPLIED = "APPLIED";
    static final String STATUS_REVERTED = "REVERTED";

    private final KioskExcelParser parser;
    private final NamedParameterJdbcTemplate jdbc;
    private final KioskFinancialsAccessGuard accessGuard;
    private final ObjectMapper objectMapper;

    public KioskExcelImportService(KioskExcelParser parser, NamedParameterJdbcTemplate jdbc,
                                   KioskFinancialsAccessGuard accessGuard, ObjectMapper objectMapper) {
        this.parser = parser;
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
    }

    // ================================================================== PREVIEW

    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public KioskExcelPreviewResponse preview(List<MultipartFile> files) throws BusinessException {
        return preview(files, null);
    }

    /**
     * @param periodsJson opcional: {@code {"<nombre de archivo>":{"year":2026,"month":4}}}. Corrige el mes/año de los
     *                    archivos del formato 2026, cuyas fechas internas pueden venir mal (los del formato anterior
     *                    lo ignoran).
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public KioskExcelPreviewResponse preview(List<MultipartFile> files, String periodsJson) throws BusinessException {
        accessGuard.assertCanImport();
        if (files == null || files.isEmpty()) {
            throw new BusinessException("Debe adjuntar al menos un archivo Excel (campo 'files').");
        }
        if (files.size() > 24) {
            throw new BusinessException("Máximo 24 archivos por importación.");
        }
        List<NamedBytes> inputs = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            try {
                inputs.add(new NamedBytes(f.getOriginalFilename() == null ? "archivo.xlsx" : f.getOriginalFilename(), f.getBytes()));
            } catch (IOException e) {
                throw new BusinessException("No se pudo leer el archivo '" + f.getOriginalFilename() + "'.", e);
            }
        }
        return previewBytes(inputs, parsePeriods(periodsJson));
    }

    private Map<String, YearMonth> parsePeriods(String periodsJson) throws BusinessException {
        Map<String, YearMonth> periods = new HashMap<>();
        if (periodsJson == null || periodsJson.isBlank()) {
            return periods;
        }
        try {
            Map<String, Map<String, Integer>> raw = objectMapper.readValue(periodsJson,
                    new TypeReference<Map<String, Map<String, Integer>>>() {
                    });
            for (Map.Entry<String, Map<String, Integer>> e : raw.entrySet()) {
                Integer year = e.getValue() == null ? null : e.getValue().get("year");
                Integer month = e.getValue() == null ? null : e.getValue().get("month");
                if (year == null || year < 2000 || year > 2100 || month == null || month < 1 || month > 12) {
                    throw new BusinessException("Período inválido para '" + e.getKey() + "': indique año (2000-2100) y mes (1-12).");
                }
                periods.put(e.getKey(), YearMonth.of(year, month));
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException("El parámetro 'periods' no es un JSON válido.", e);
        }
        return periods;
    }

    /** Variante sin multipart (usada por tests). */
    public KioskExcelPreviewResponse previewBytes(List<NamedBytes> inputs) {
        return previewBytes(inputs, Map.of());
    }

    public KioskExcelPreviewResponse previewBytes(List<NamedBytes> inputs, Map<String, YearMonth> periods) {
        List<KioskExcelPreviewResponse.FilePreview> previews = new ArrayList<>();
        Set<String> seenSha = new HashSet<>();
        Set<String> seenPeriod = new HashSet<>();
        for (NamedBytes in : inputs) {
            String sha = sha256(in.content());
            KioskExcelPreviewResponse.FilePreview preview;
            try {
                KioskExcelParser.ParseResult parsed = parser.parse(in.name(), in.content(), periods.get(in.name()));
                preview = buildPreview(parsed, sha);
                if (!seenSha.add(sha) || !seenPeriod.add(parsed.getYear() + "-" + parsed.getMonth())) {
                    addIssue(preview.getIssues(), KioskExcelIssueDto.WARNING, KioskExcelIssueDto.DUPLICATE_FILE,
                            "Este archivo (o su mes " + parsed.getYear() + "-" + String.format("%02d", parsed.getMonth())
                                    + ") ya viene repetido en esta misma carga.", null);
                }
            } catch (BusinessException e) {
                List<KioskExcelIssueDto> issues = new ArrayList<>();
                addIssue(issues, KioskExcelIssueDto.BLOCKING, KioskExcelIssueDto.FILE_ERROR, e.getMessage(), null);
                preview = KioskExcelPreviewResponse.FilePreview.builder()
                        .fileName(in.name()).sha256(sha).columns(List.of()).issues(issues).build();
            }
            previews.add(preview);
        }
        return KioskExcelPreviewResponse.builder().files(previews).build();
    }

    private KioskExcelPreviewResponse.FilePreview buildPreview(KioskExcelParser.ParseResult parsed, String sha) {
        List<KioskExcelIssueDto> issues = new ArrayList<>(parsed.getIssues());

        // --- mapeo de columnas por alias
        Set<String> normalizedNames = new HashSet<>();
        for (KioskExcelParser.ParsedColumn c : parsed.getColumns()) {
            normalizedNames.add(c.getNormalized());
        }
        Map<String, SiteRef> aliasMap = loadAliases(normalizedNames);
        Map<Long, Integer> siteUse = new HashMap<>();
        for (KioskExcelParser.ParsedColumn c : parsed.getColumns()) {
            SiteRef ref = aliasMap.get(c.getNormalized());
            if (ref != null) {
                siteUse.merge(ref.id(), 1, Integer::sum);
            }
        }
        List<KioskExcelPreviewResponse.ColumnMapping> columns = new ArrayList<>();
        Map<String, Long> matchedSiteByColumn = new LinkedHashMap<>();
        for (KioskExcelParser.ParsedColumn c : parsed.getColumns()) {
            SiteRef ref = aliasMap.get(c.getNormalized());
            String status;
            if (ref == null) {
                status = "UNMATCHED";
                addIssue(issues, KioskExcelIssueDto.BLOCKING, KioskExcelIssueDto.UNMATCHED_COLUMN,
                        "La columna '" + c.getExcelName() + "' no tiene sitio asociado: mapéela a un sitio existente o cree un sitio histórico.",
                        c.getExcelName());
            } else if (siteUse.get(ref.id()) > 1) {
                status = "AMBIGUOUS";
                addIssue(issues, KioskExcelIssueDto.BLOCKING, KioskExcelIssueDto.UNMATCHED_COLUMN,
                        "La columna '" + c.getExcelName() + "' es ambigua: varias columnas apuntan al sitio '" + ref.name() + "'.",
                        c.getExcelName());
            } else {
                status = "MATCHED";
                matchedSiteByColumn.put(c.getExcelName(), ref.id());
            }
            columns.add(KioskExcelPreviewResponse.ColumnMapping.builder()
                    .excelName(c.getExcelName())
                    .normalized(c.getNormalized())
                    .matchedSiteId(ref == null ? null : ref.id())
                    .matchedSiteName(ref == null ? null : ref.name())
                    .matchStatus(status)
                    .build());
        }

        // --- ya importado (mismo sha o mismo año-mes con lote APPLIED)
        KioskExcelPreviewResponse.AlreadyImported already = findAlreadyImported(sha, parsed.getYear(), parsed.getMonth());
        if (already != null) {
            addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.DUPLICATE_FILE,
                    (already.isSameFile() ? "Este mismo archivo ya fue importado" : "Ya existe una importación de este mes")
                            + " (lote " + already.getBatchId() + "). Al confirmar se reemplazarán los datos del mes.", null);
        }

        // --- solapamiento con POS (sólo columnas ya mapeadas)
        for (String warning : posOverlapWarnings(matchedSiteByColumn, parsed.getData())) {
            addIssue(issues, KioskExcelIssueDto.WARNING, KioskExcelIssueDto.OVERLAPS_POS, warning, null);
        }

        BigDecimal sheetTotal = BigDecimal.ZERO;
        for (BigDecimal v : parsed.getSheetTotals().values()) {
            sheetTotal = sheetTotal.add(v);
        }
        BigDecimal salesTotal = BigDecimal.ZERO;
        for (BigDecimal v : parsed.getRecomputedTotals().values()) {
            salesTotal = salesTotal.add(v);
        }
        return KioskExcelPreviewResponse.FilePreview.builder()
                .fileName(parsed.getFileName())
                .sha256(sha)
                .year(parsed.getYear())
                .month(parsed.getMonth())
                .sheetName(parsed.getSheetName())
                .alreadyImported(already)
                .columns(columns)
                .issues(issues)
                .data(parsed.getData())
                .format(parsed.getFormat())
                .periodSource(parsed.getPeriodSource())
                .periodEditable(parsed.isPeriodEditable())
                .stats(KioskExcelPreviewResponse.Stats.builder()
                        .columns(parsed.getColumns().size())
                        .days(parsed.getDays())
                        .salesCells(parsed.getSalesCells())
                        .salesTotal(salesTotal.setScale(2, RoundingMode.HALF_UP))
                        .sheetTotal(sheetTotal.setScale(2, RoundingMode.HALF_UP))
                        .build())
                .build();
    }

    // ================================================================== COMMIT

    @Transactional(rollbackFor = Exception.class)
    public KioskExcelCommitResponse commit(KioskExcelCommitRequest request) throws BusinessException {
        accessGuard.assertCanImport();

        Set<String> costCodes = loadCostCodes();
        KioskExcelCommitValidator validator = new KioskExcelCommitValidator(
                normalized -> loadAliases(Set.of(normalized)).entrySet().stream()
                        .findFirst().map(e -> e.getValue().id()).orElse(null),
                this::siteExists,
                costCodes);
        KioskExcelCommitValidator.Result validation = validator.validate(request);
        validation.throwIfInvalid();

        Long userId = accessGuard.currentUserId();
        Map<String, Long> createdInThisCommit = new HashMap<>();
        List<KioskExcelCommitResponse.BatchResult> results = new ArrayList<>();
        for (KioskExcelCommitValidator.ResolvedFile file : validation.files) {
            results.add(applyFile(file, request.isReplaceExisting(), userId, createdInThisCommit));
        }
        return KioskExcelCommitResponse.builder().batches(results).build();
    }

    private KioskExcelCommitResponse.BatchResult applyFile(KioskExcelCommitValidator.ResolvedFile file,
                                                          boolean replaceExisting, Long userId,
                                                          Map<String, Long> createdInThisCommit) throws BusinessException {
        // --- 1) sitios destino (crear históricos, alias de mapeos manuales)
        Map<String, Long> siteByColumn = new LinkedHashMap<>();
        for (Map.Entry<String, KioskExcelCommitValidator.ColumnTarget> e : file.targets.entrySet()) {
            String column = e.getKey();
            KioskExcelCommitValidator.ColumnTarget target = e.getValue();
            String columnAlias = KioskExcelParser.normalizeAlias(column);
            Long siteId;
            if (target.create != null) {
                siteId = findOrCreateSite(target.create, createdInThisCommit);
                upsertAlias(columnAlias, siteId, column);
                upsertAlias(KioskExcelParser.normalizeAlias(target.create.getName()), siteId, target.create.getName());
            } else {
                siteId = target.siteId;
                if (target.manual) {
                    upsertAlias(columnAlias, siteId, column);
                }
            }
            siteByColumn.put(column, siteId);
        }
        List<Long> siteIds = new ArrayList<>(siteByColumn.values());
        LocalDate from = LocalDate.of(file.year, file.month, 1);
        LocalDate to = from.plusMonths(1);

        // --- 2) datos previos del mes
        int existing = countExisting(siteIds, file.year, file.month, from, to);
        if (existing > 0 && !replaceExisting) {
            throw new BusinessException("Ya existen " + existing + " registros de " + file.year + "-"
                    + String.format("%02d", file.month) + " para los sitios de '" + file.fileName
                    + "'. Confirme el reemplazo (replaceExisting=true) para sobrescribirlos.");
        }

        // --- 3) lote
        long batchId = insertBatch(file, userId);

        // --- 4) reemplazo: borra el mes de esos sitios y escribe lo nuevo
        MapSqlParameterSource del = new MapSqlParameterSource()
                .addValue("ids", siteIds).addValue("year", file.year).addValue("month", file.month)
                .addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
        int replaced = 0;
        if (!siteIds.isEmpty()) {
            replaced += jdbc.update("DELETE FROM kiosk_daily_sales_hist WHERE site_id IN (:ids) AND sale_date >= :from AND sale_date < :to", del);
            replaced += jdbc.update("DELETE FROM kiosk_period_config WHERE site_id IN (:ids) AND year = :year AND month = :month", del);
            replaced += jdbc.update("DELETE FROM kiosk_fixed_cost WHERE site_id IN (:ids) AND year = :year AND month = :month", del);
        }

        List<SqlParameterSource> salesRows = new ArrayList<>();
        List<SqlParameterSource> configRows = new ArrayList<>();
        List<SqlParameterSource> costRows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Long> matchedForOverlap = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : siteByColumn.entrySet()) {
            String column = e.getKey();
            Long siteId = e.getValue();
            matchedForOverlap.put(column, siteId);

            TreeMap<LocalDate, BigDecimal> sales = file.sales.getOrDefault(column, new TreeMap<>());
            for (Map.Entry<LocalDate, BigDecimal> s : sales.entrySet()) {
                salesRows.add(new MapSqlParameterSource()
                        .addValue("site", siteId)
                        .addValue("date", Date.valueOf(s.getKey()))
                        .addValue("amount", s.getValue().setScale(2, RoundingMode.HALF_UP))
                        .addValue("batch", batchId));
            }

            BigDecimal goal = file.goals.get(column);
            if (goal != null && goal.signum() <= 0) {
                if (!sales.isEmpty()) {
                    warnings.add("MISSING_GOAL: '" + column + "' viene con meta 0; se guardó sin meta.");
                }
                goal = null;
            }
            KioskExcelDataDto.Rates rates = file.rates.get(column);
            boolean anyRate = rates != null && (rates.getProductCostPct() != null || rates.getSalesCommissionPct() != null
                    || rates.getCardCommissionPct() != null || rates.getTaxPct() != null);
            if (goal != null || anyRate) {
                configRows.add(new MapSqlParameterSource()
                        .addValue("site", siteId).addValue("year", file.year).addValue("month", file.month)
                        .addValue("goal", goal == null ? null : goal.setScale(2, RoundingMode.HALF_UP))
                        .addValue("pc", rate(rates == null ? null : rates.getProductCostPct()))
                        .addValue("sc", rate(rates == null ? null : rates.getSalesCommissionPct()))
                        .addValue("tc", rate(rates == null ? null : rates.getCardCommissionPct()))
                        .addValue("tx", rate(rates == null ? null : rates.getTaxPct()))
                        .addValue("user", userId)
                        .addValue("batch", batchId));
            }

            Map<String, BigDecimal> costs = file.costs.get(column);
            if (costs != null) {
                for (Map.Entry<String, BigDecimal> c : costs.entrySet()) {
                    if (c.getValue() == null) {
                        continue;
                    }
                    costRows.add(new MapSqlParameterSource()
                            .addValue("site", siteId).addValue("year", file.year).addValue("month", file.month)
                            .addValue("cat", c.getKey())
                            .addValue("amount", c.getValue().setScale(2, RoundingMode.HALF_UP))
                            .addValue("user", userId)
                            .addValue("batch", batchId));
                }
            }
        }

        if (!salesRows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO kiosk_daily_sales_hist (site_id, sale_date, amount, import_batch_id) "
                    + "VALUES (:site, :date, :amount, :batch)", salesRows.toArray(new SqlParameterSource[0]));
        }
        if (!configRows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO kiosk_period_config (site_id, year, month, sales_goal, product_cost_pct, "
                    + "sales_commission_pct, card_commission_pct, tax_pct, source, updated_by, import_batch_id) "
                    + "VALUES (:site, :year, :month, :goal, :pc, :sc, :tc, :tx, '" + SOURCE_EXCEL + "', :user, :batch)",
                    configRows.toArray(new SqlParameterSource[0]));
        }
        if (!costRows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO kiosk_fixed_cost (site_id, year, month, category_code, amount, updated_by, import_batch_id) "
                    + "VALUES (:site, :year, :month, :cat, :amount, :user, :batch)", costRows.toArray(new SqlParameterSource[0]));
        }

        // --- 5) avisos: hist que cae sobre el POS (se escribe igual)
        warnings.addAll(posOverlapWarningsFromSales(matchedForOverlap, file.sales));

        // --- 6) contadores y avisos del lote
        jdbc.update("UPDATE kiosk_import_batch SET sales_rows = :s, config_rows = :c, cost_rows = :k, warnings_json = :w WHERE id = :id",
                new MapSqlParameterSource().addValue("s", salesRows.size()).addValue("c", configRows.size())
                        .addValue("k", costRows.size()).addValue("w", toJson(warnings)).addValue("id", batchId));

        return KioskExcelCommitResponse.BatchResult.builder()
                .batchId(batchId)
                .fileName(file.fileName)
                .year(file.year)
                .month(file.month)
                .salesRows(salesRows.size())
                .configRows(configRows.size())
                .costRows(costRows.size())
                .replacedRows(replaced)
                .warnings(warnings)
                .build();
    }

    // ================================================================== REVERT / LIST

    @Transactional(rollbackFor = Exception.class)
    public KioskExcelBatchSummaryResponse revert(Long batchId) throws BusinessException, ResourceNotFoundException {
        accessGuard.assertCanImport();
        List<String> status = jdbc.query("SELECT status FROM kiosk_import_batch WHERE id = :id",
                new MapSqlParameterSource("id", batchId), (rs, i) -> rs.getString(1));
        if (status.isEmpty()) {
            throw new ResourceNotFoundException("Lote de importación no encontrado: " + batchId);
        }
        if (STATUS_REVERTED.equalsIgnoreCase(status.get(0))) {
            throw new BusinessException("El lote " + batchId + " ya fue revertido.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource("id", batchId);
        jdbc.update("DELETE FROM kiosk_daily_sales_hist WHERE import_batch_id = :id", p);
        jdbc.update("DELETE FROM kiosk_fixed_cost WHERE import_batch_id = :id", p);
        // Si alguien editó la config a mano (source MANUAL/COPIED) se respeta esa edición.
        jdbc.update("DELETE FROM kiosk_period_config WHERE import_batch_id = :id AND source = '" + SOURCE_EXCEL + "'", p);
        jdbc.update("UPDATE kiosk_import_batch SET status = '" + STATUS_REVERTED + "', reverted_by = :by, "
                        + "reverted_at = CURRENT_TIMESTAMP WHERE id = :id",
                new MapSqlParameterSource().addValue("id", batchId).addValue("by", accessGuard.currentUserId()));
        return findBatch(batchId);
    }

    @Transactional(readOnly = true)
    public List<KioskExcelBatchSummaryResponse> list() throws BusinessException {
        accessGuard.assertCanImport();
        return jdbc.query("SELECT id, file_name, file_sha256, year, month, status, sales_rows, config_rows, cost_rows, "
                        + "warnings_json, created_by, created_at, reverted_by, reverted_at "
                        + "FROM kiosk_import_batch ORDER BY id DESC",
                new MapSqlParameterSource(), (rs, i) -> mapBatch(rs));
    }

    private KioskExcelBatchSummaryResponse findBatch(Long id) {
        List<KioskExcelBatchSummaryResponse> rows = jdbc.query(
                "SELECT id, file_name, file_sha256, year, month, status, sales_rows, config_rows, cost_rows, "
                        + "warnings_json, created_by, created_at, reverted_by, reverted_at "
                        + "FROM kiosk_import_batch WHERE id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> mapBatch(rs));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private KioskExcelBatchSummaryResponse mapBatch(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp reverted = rs.getTimestamp("reverted_at");
        long createdBy = rs.getLong("created_by");
        boolean createdByNull = rs.wasNull();
        long revertedBy = rs.getLong("reverted_by");
        boolean revertedByNull = rs.wasNull();
        return KioskExcelBatchSummaryResponse.builder()
                .id(rs.getLong("id"))
                .fileName(rs.getString("file_name"))
                .sha256(rs.getString("file_sha256"))
                .year(rs.getInt("year"))
                .month(rs.getInt("month"))
                .status(rs.getString("status"))
                .salesRows(rs.getInt("sales_rows"))
                .configRows(rs.getInt("config_rows"))
                .costRows(rs.getInt("cost_rows"))
                .warnings(fromJson(rs.getString("warnings_json")))
                .createdBy(createdByNull ? null : createdBy)
                .createdAt(created == null ? null : created.toLocalDateTime())
                .revertedBy(revertedByNull ? null : revertedBy)
                .revertedAt(reverted == null ? null : reverted.toLocalDateTime())
                .build();
    }

    // ================================================================== SQL helpers

    record SiteRef(Long id, String name) {
    }

    public record NamedBytes(String name, byte[] content) {
    }

    private Map<String, SiteRef> loadAliases(Collection<String> normalizedNames) {
        Map<String, SiteRef> map = new HashMap<>();
        if (normalizedNames.isEmpty()) {
            return map;
        }
        jdbc.query("SELECT a.alias_normalized, s.id, s.name FROM kiosk_site_alias a JOIN kiosk_site s ON s.id = a.site_id "
                        + "WHERE a.alias_normalized IN (:names)",
                new MapSqlParameterSource("names", normalizedNames),
                rs -> {
                    map.put(rs.getString(1), new SiteRef(rs.getLong(2), rs.getString(3)));
                });
        return map;
    }

    private boolean siteExists(Long id) {
        if (id == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM kiosk_site WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        return n != null && n > 0;
    }

    private Set<String> loadCostCodes() {
        return new HashSet<>(jdbc.query("SELECT code FROM kiosk_cost_category",
                new MapSqlParameterSource(), (rs, i) -> rs.getString(1)));
    }

    private KioskExcelPreviewResponse.AlreadyImported findAlreadyImported(String sha, int year, int month) {
        List<KioskExcelPreviewResponse.AlreadyImported> bySha = jdbc.query(
                "SELECT id, created_at FROM kiosk_import_batch WHERE status = '" + STATUS_APPLIED
                        + "' AND file_sha256 = :sha ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource("sha", sha),
                (rs, i) -> alreadyImported(rs, true));
        if (!bySha.isEmpty()) {
            return bySha.get(0);
        }
        List<KioskExcelPreviewResponse.AlreadyImported> byPeriod = jdbc.query(
                "SELECT id, created_at FROM kiosk_import_batch WHERE status = '" + STATUS_APPLIED
                        + "' AND year = :y AND month = :m ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource().addValue("y", year).addValue("m", month),
                (rs, i) -> alreadyImported(rs, false));
        return byPeriod.isEmpty() ? null : byPeriod.get(0);
    }

    private static KioskExcelPreviewResponse.AlreadyImported alreadyImported(java.sql.ResultSet rs, boolean same)
            throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp(2);
        LocalDateTime at = created == null ? null : created.toLocalDateTime();
        return KioskExcelPreviewResponse.AlreadyImported.builder().batchId(rs.getLong(1)).createdAt(at).sameFile(same).build();
    }

    private Long findOrCreateSite(KioskExcelCommitRequest.CreateSite create, Map<String, Long> createdInThisCommit)
            throws BusinessException {
        String key = create.getName().trim().toUpperCase();
        Long cached = createdInThisCommit.get(key);
        if (cached != null) {
            return cached;
        }
        List<Long> existing = jdbc.query("SELECT id FROM kiosk_site WHERE UPPER(name) = :name",
                new MapSqlParameterSource("name", key), (rs, i) -> rs.getLong(1));
        if (!existing.isEmpty()) {
            createdInThisCommit.put(key, existing.get(0));
            return existing.get(0);
        }
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 1000) FROM kiosk_site WHERE location_id IS NULL",
                new MapSqlParameterSource(), Integer.class);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO kiosk_site (name, location_id, status, closed_on, sort_order) "
                        + "VALUES (:name, NULL, :status, :closedOn, :sort)",
                new MapSqlParameterSource()
                        .addValue("name", create.getName().trim())
                        .addValue("status", create.getStatus() == null ? "CLOSED" : create.getStatus())
                        .addValue("closedOn", create.getClosedOn() == null ? null : Date.valueOf(create.getClosedOn()))
                        .addValue("sort", (maxSort == null ? 1000 : maxSort) + 1),
                keys, new String[]{"id"});
        Number id = keys.getKey();
        if (id == null) {
            throw new BusinessException("No se pudo crear el sitio '" + create.getName() + "'.");
        }
        createdInThisCommit.put(key, id.longValue());
        return id.longValue();
    }

    private void upsertAlias(String normalized, Long siteId, String original) throws BusinessException {
        if (normalized == null || normalized.isEmpty()) {
            throw new BusinessException("El nombre '" + original + "' no genera un alias válido.");
        }
        List<Long> existing = jdbc.query("SELECT site_id FROM kiosk_site_alias WHERE alias_normalized = :a",
                new MapSqlParameterSource("a", normalized), (rs, i) -> rs.getLong(1));
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO kiosk_site_alias (alias_normalized, site_id) VALUES (:a, :s)",
                    new MapSqlParameterSource().addValue("a", normalized).addValue("s", siteId));
        } else if (!existing.get(0).equals(siteId)) {
            throw new BusinessException("El alias '" + normalized + "' ya está asignado a otro sitio (" + existing.get(0)
                    + "). Corríjalo en la configuración de sitios antes de importar.");
        }
    }

    private int countExisting(List<Long> siteIds, int year, int month, LocalDate from, LocalDate to) {
        if (siteIds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("ids", siteIds).addValue("year", year)
                .addValue("month", month).addValue("from", Date.valueOf(from)).addValue("to", Date.valueOf(to));
        int n = 0;
        n += count("SELECT COUNT(*) FROM kiosk_daily_sales_hist WHERE site_id IN (:ids) AND sale_date >= :from AND sale_date < :to", p);
        n += count("SELECT COUNT(*) FROM kiosk_period_config WHERE site_id IN (:ids) AND year = :year AND month = :month", p);
        n += count("SELECT COUNT(*) FROM kiosk_fixed_cost WHERE site_id IN (:ids) AND year = :year AND month = :month", p);
        return n;
    }

    private int count(String sql, SqlParameterSource p) {
        Integer n = jdbc.queryForObject(sql, p, Integer.class);
        return n == null ? 0 : n;
    }

    private long insertBatch(KioskExcelCommitValidator.ResolvedFile file, Long userId) throws BusinessException {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO kiosk_import_batch (file_name, file_sha256, year, month, status, created_by) "
                        + "VALUES (:name, :sha, :year, :month, '" + STATUS_APPLIED + "', :user)",
                new MapSqlParameterSource().addValue("name", truncate(file.fileName, 255)).addValue("sha", file.sha256)
                        .addValue("year", file.year).addValue("month", file.month).addValue("user", userId),
                keys, new String[]{"id"});
        Number id = keys.getKey();
        if (id == null) {
            throw new BusinessException("No se pudo registrar el lote de importación.");
        }
        return id.longValue();
    }

    // ------------------------------------------------------------------ POS overlap

    /** goLive efectivo por sitio: override si existe; si no, primera venta real (no test, no VOID/CANCELLED) del location. */
    private Map<Long, LocalDate> loadGoLive(Collection<Long> siteIds) {
        Map<Long, LocalDate> result = new HashMap<>();
        if (siteIds.isEmpty()) {
            return result;
        }
        jdbc.query("SELECT s.id, s.pos_go_live_override, "
                        + "(SELECT MIN(k.sale_date) FROM kiosk_sale k WHERE k.kiosk_location_id = s.location_id "
                        + "AND k.test_sale = FALSE AND UPPER(COALESCE(k.status, '')) NOT IN ('VOID', 'CANCELLED')) AS detected "
                        + "FROM kiosk_site s WHERE s.id IN (:ids)",
                new MapSqlParameterSource("ids", siteIds),
                rs -> {
                    Date override = rs.getDate(2);
                    Date detected = rs.getDate(3);
                    Date effective = override != null ? override : detected;
                    if (effective != null) {
                        result.put(rs.getLong(1), effective.toLocalDate());
                    }
                });
        return result;
    }

    private List<String> posOverlapWarnings(Map<String, Long> siteByColumn, KioskExcelDataDto data) {
        Map<String, TreeMap<LocalDate, BigDecimal>> sales = new LinkedHashMap<>();
        for (KioskExcelDataDto.Day day : data.getDays()) {
            if (day.getValues() == null) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> e : day.getValues().entrySet()) {
                if (e.getValue() != null && siteByColumn.containsKey(e.getKey())) {
                    sales.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).put(day.getDate(), e.getValue());
                }
            }
        }
        return posOverlapWarningsFromSales(siteByColumn, sales);
    }

    private List<String> posOverlapWarningsFromSales(Map<String, Long> siteByColumn,
                                                     Map<String, TreeMap<LocalDate, BigDecimal>> sales) {
        List<String> warnings = new ArrayList<>();
        Map<Long, LocalDate> goLive = loadGoLive(siteByColumn.values());
        for (Map.Entry<String, Long> e : siteByColumn.entrySet()) {
            LocalDate live = goLive.get(e.getValue());
            TreeMap<LocalDate, BigDecimal> rows = sales.get(e.getKey());
            if (live == null || rows == null) {
                continue;
            }
            long overlapping = rows.tailMap(live, true).size();
            if (overlapping > 0) {
                warnings.add("OVERLAPS_POS: '" + e.getKey() + "' tiene " + overlapping
                        + " día(s) de ventas históricas desde el inicio del POS (" + live
                        + "); se guardan pero los reportes usarán el POS.");
            }
        }
        return warnings;
    }

    // ------------------------------------------------------------------ misc

    private static BigDecimal rate(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }

    private static void addIssue(List<KioskExcelIssueDto> issues, String severity, String code, String message, String excelName) {
        issues.add(KioskExcelIssueDto.builder()
                .id("i" + (issues.size() + 1))
                .severity(severity)
                .code(code)
                .message(message)
                .excelName(excelName)
                .build());
    }

    private String toJson(List<String> warnings) {
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
