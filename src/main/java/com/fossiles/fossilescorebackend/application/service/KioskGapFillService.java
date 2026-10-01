package com.fossiles.fossilescorebackend.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fossiles.fossilescorebackend.application.dto.response.KioskExcelDataDto;
import com.fossiles.fossilescorebackend.application.dto.response.KioskGapFillResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.util.KioskGapFillPlanner;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskDailySalesHistEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskImportBatchEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteAliasEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.KioskSiteEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskDailySalesHistRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskImportBatchRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteAliasRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.KioskSiteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Corrección de "días sin sistema": sube el reporte Excel del mes y cubre, sólo para los kioscos elegidos, los días
 * anteriores a su primera venta en el POS que el Excel sí muestra. Escribe únicamente ventas en
 * {@code kiosk_daily_sales_hist} (lote revertible desde el historial de importaciones); no toca costos, tasas, metas
 * ni el POS. Ver docs/KIOSK-FINANCIALS-CONTRACT.md.
 */
@Service
@RequiredArgsConstructor
public class KioskGapFillService {

    /** Prefijo del nombre de archivo de estos lotes (el historial los distingue de una importación completa). */
    public static final String BATCH_PREFIX = "DIAS SIN SISTEMA - ";

    private final KioskExcelParser parser;
    private final KioskSiteRepository siteRepository;
    private final KioskSiteAliasRepository aliasRepository;
    private final KioskSalesSourceResolver salesSourceResolver;
    private final KioskDailySalesHistRepository histRepository;
    private final KioskImportBatchRepository batchRepository;
    private final KioskFinancialsAccessGuard guard;
    private final ObjectMapper objectMapper;

    /** Resultado interno del análisis (sirve a preview y commit para que ambos calculen exactamente lo mismo). */
    private record Analysis(KioskExcelParser.ParseResult parsed, String sha256,
                            List<KioskGapFillPlanner.SitePlan> plans,
                            List<KioskGapFillResponse.IgnoredColumn> ignored,
                            List<KioskGapFillResponse.SkippedCell> skipped) {
    }

    // ------------------------------------------------------------------ preview

    @Transactional(readOnly = true)
    public KioskGapFillResponse preview(MultipartFile file, Integer year, Integer month) throws BusinessException {
        guard.assertCanImport();
        Analysis analysis = analyze(file, periodOf(year, month));

        List<KioskGapFillResponse.Site> sites = new ArrayList<>();
        int withCandidates = 0;
        int days = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (KioskGapFillPlanner.SitePlan p : analysis.plans()) {
            if (p.isEmpty()) {
                continue;
            }
            if (!p.candidates().isEmpty()) {
                withCandidates++;
                days += p.candidates().size();
                amount = amount.add(p.candidateTotal());
            }
            sites.add(KioskGapFillResponse.Site.builder()
                    .siteId(p.siteId()).name(p.siteName()).excelName(p.excelName()).goLive(p.goLive())
                    .candidates(p.candidates().stream()
                            .map(a -> KioskGapFillResponse.Day.builder().date(a.date()).excelAmount(a.excelAmount()).build())
                            .toList())
                    .candidateTotal(money(p.candidateTotal()))
                    .differences(p.differences().stream()
                            .map(d -> KioskGapFillResponse.Difference.builder().date(d.date())
                                    .excelAmount(d.excelAmount()).systemAmount(d.systemAmount()).build())
                            .toList())
                    .afterGoLiveGaps(p.afterGoLiveGaps().stream()
                            .map(a -> KioskGapFillResponse.Day.builder().date(a.date()).excelAmount(a.excelAmount()).build())
                            .toList())
                    .build());
        }
        KioskExcelParser.ParseResult parsed = analysis.parsed();
        return KioskGapFillResponse.builder()
                .fileName(parsed.getFileName())
                .sha256(analysis.sha256())
                .year(parsed.getYear())
                .month(parsed.getMonth())
                .periodSource(parsed.getPeriodSource())
                .periodEditable(parsed.isPeriodEditable())
                .sites(sites)
                .ignoredColumns(analysis.ignored())
                .skippedCells(analysis.skipped())
                .totals(KioskGapFillResponse.Totals.builder()
                        .sitesWithCandidates(withCandidates).candidateDays(days).candidateAmount(money(amount)).build())
                .build();
    }

    // ------------------------------------------------------------------ commit

    /**
     * Vuelve a leer el archivo y recalcula todo (nunca confía en montos enviados por el cliente). Sólo escribe los
     * kioscos de {@code siteIds}; cada uno debe tener candidatos.
     */
    @Transactional(rollbackFor = Exception.class)
    public KioskGapFillResponse.Applied commit(MultipartFile file, Integer year, Integer month, Set<Long> siteIds)
            throws BusinessException {
        guard.assertCanImport();
        if (siteIds == null || siteIds.isEmpty()) {
            throw new BusinessException("Elija al menos un kiosco para corregir.");
        }
        Analysis analysis = analyze(file, periodOf(year, month));
        Map<Long, KioskGapFillPlanner.SitePlan> byId = new LinkedHashMap<>();
        analysis.plans().forEach(p -> byId.put(p.siteId(), p));

        List<KioskGapFillPlanner.SitePlan> selected = new ArrayList<>();
        for (Long id : siteIds) {
            KioskGapFillPlanner.SitePlan p = byId.get(id);
            if (p == null || p.candidates().isEmpty()) {
                throw new BusinessException("El sitio " + id + " no tiene días sin sistema por corregir en este archivo.");
            }
            selected.add(p);
        }
        selected.sort(Comparator.comparing(KioskGapFillPlanner.SitePlan::siteName, String.CASE_INSENSITIVE_ORDER));

        KioskExcelParser.ParseResult parsed = analysis.parsed();
        int rows = selected.stream().mapToInt(p -> p.candidates().size()).sum();
        BigDecimal total = selected.stream().map(KioskGapFillPlanner.SitePlan::candidateTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<String> summary = new ArrayList<>();
        for (KioskGapFillPlanner.SitePlan p : selected) {
            summary.add(p.siteName() + ": " + p.candidates().size() + " días, Q" + money(p.candidateTotal()).toPlainString());
        }
        KioskImportBatchEntity batch = batchRepository.save(KioskImportBatchEntity.builder()
                .fileName(truncate(BATCH_PREFIX + parsed.getFileName(), 255))
                // sha propio: que este lote no se confunda con la importación completa del mismo archivo
                .fileSha256(KioskExcelImportService.sha256(("gapfill:" + analysis.sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .periodYear(parsed.getYear())
                .periodMonth(parsed.getMonth())
                .salesRows(rows)
                .warningsJson(toJson(summary))
                .createdBy(guard.currentUserId())
                .build());

        List<KioskDailySalesHistEntity> toSave = new ArrayList<>();
        List<KioskGapFillResponse.AppliedSite> appliedSites = new ArrayList<>();
        for (KioskGapFillPlanner.SitePlan p : selected) {
            for (KioskGapFillPlanner.Amount a : p.candidates()) {
                toSave.add(KioskDailySalesHistEntity.builder()
                        .siteId(p.siteId()).saleDate(a.date()).amount(money(a.excelAmount()))
                        .importBatchId(batch.getId()).build());
            }
            appliedSites.add(KioskGapFillResponse.AppliedSite.builder()
                    .siteId(p.siteId()).name(p.siteName()).days(p.candidates().size()).amount(money(p.candidateTotal())).build());
        }
        histRepository.saveAll(toSave);

        return KioskGapFillResponse.Applied.builder()
                .batchId(batch.getId()).year(parsed.getYear()).month(parsed.getMonth())
                .salesRows(rows).amount(money(total)).sites(appliedSites).build();
    }

    // ------------------------------------------------------------------ análisis

    private Analysis analyze(MultipartFile file, YearMonth override) throws BusinessException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Adjunte el Excel del reporte (campo 'file').");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo '" + file.getOriginalFilename() + "'.", e);
        }
        String name = file.getOriginalFilename() == null ? "archivo.xlsx" : file.getOriginalFilename();
        KioskExcelParser.ParseResult parsed = parser.parse(name, content, override);
        YearMonth ym = YearMonth.of(parsed.getYear(), parsed.getMonth());

        List<KioskGapFillResponse.IgnoredColumn> ignored = new ArrayList<>();
        Map<Long, KioskSiteEntity> siteById = new LinkedHashMap<>();
        Map<Long, String> excelNameBySite = new HashMap<>();
        for (KioskExcelParser.ParsedColumn column : parsed.getColumns()) {
            Optional<KioskSiteAliasEntity> alias = aliasRepository.findByAliasNormalized(column.getNormalized());
            Optional<KioskSiteEntity> site = alias.flatMap(a -> siteRepository.findById(a.getSiteId()));
            if (site.isEmpty()) {
                ignored.add(ignored(column, "Sin sitio asociado"));
            } else if (Boolean.TRUE.equals(site.get().getExcludeFromReports())) {
                ignored.add(ignored(column, "Sitio externo, fuera de los reportes"));
            } else if (site.get().getLocationId() == null) {
                ignored.add(ignored(column, "Sitio histórico (sin POS): use el importador normal"));
            } else if (siteById.containsKey(site.get().getId())) {
                ignored.add(ignored(column, "Columna repetida para " + site.get().getName()));
            } else {
                siteById.put(site.get().getId(), site.get());
                excelNameBySite.put(site.get().getId(), column.getExcelName());
            }
        }

        Map<Long, KioskSalesSourceResolver.SiteSales> resolved = salesSourceResolver.resolve(
                siteById.values(), ym.atDay(1), ym.atEndOfMonth());

        List<KioskGapFillPlanner.SitePlan> plans = new ArrayList<>();
        List<KioskSiteEntity> ordered = new ArrayList<>(siteById.values());
        ordered.sort(Comparator.comparing(KioskSiteEntity::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(KioskSiteEntity::getName, String.CASE_INSENSITIVE_ORDER));
        for (KioskSiteEntity site : ordered) {
            KioskSalesSourceResolver.SiteSales sales = resolved.get(site.getId());
            String excelName = excelNameBySite.get(site.getId());
            if (sales == null || sales.goLive() == null) {
                ignored.add(KioskGapFillResponse.IgnoredColumn.builder().excelName(excelName)
                        .reason("Aún no tiene ventas en el POS: use el importador normal").build());
                continue;
            }
            Map<LocalDate, BigDecimal> excel = new HashMap<>();
            for (KioskExcelDataDto.Day day : parsed.getData().getDays()) {
                BigDecimal v = day.getValues() == null ? null : day.getValues().get(excelName);
                if (v != null) {
                    excel.put(day.getDate(), v);
                }
            }
            plans.add(KioskGapFillPlanner.plan(new KioskGapFillPlanner.SiteInput(
                    site.getId(), site.getName(), excelName, sales.goLive(), excel, sales.daily())));
        }

        List<KioskGapFillResponse.SkippedCell> skipped = new ArrayList<>();
        if (parsed.getData().getBlockedCells() != null) {
            for (KioskExcelDataDto.BlockedCell b : parsed.getData().getBlockedCells()) {
                skipped.add(KioskGapFillResponse.SkippedCell.builder()
                        .excelName(b.getExcelName()).date(b.getDate()).cell(b.getCell()).rawValue(b.getRawValue()).build());
            }
        }
        return new Analysis(parsed, KioskExcelImportService.sha256(content), plans, ignored, skipped);
    }

    private static KioskGapFillResponse.IgnoredColumn ignored(KioskExcelParser.ParsedColumn c, String reason) {
        return KioskGapFillResponse.IgnoredColumn.builder().excelName(c.getExcelName()).reason(reason).build();
    }

    private static YearMonth periodOf(Integer year, Integer month) throws BusinessException {
        if (year == null && month == null) {
            return null;
        }
        if (year == null || month == null || year < 2000 || year > 2100 || month < 1 || month > 12) {
            throw new BusinessException("Período inválido: indique año (2000-2100) y mes (1-12).");
        }
        return YearMonth.of(year, month);
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
