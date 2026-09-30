package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigBulkRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsConfigCopyRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteCreateRequest;
import com.fossiles.fossilescorebackend.application.dto.request.KioskFinancialsSiteUpdateRequest;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsBulkResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsConfigResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsCopyResponse;
import com.fossiles.fossilescorebackend.application.dto.response.KioskFinancialsSiteResponse;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.application.util.KioskPnlCalculator;
import com.fossiles.fossilescorebackend.application.util.KioskSiteAliasNormalizer;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KioskFinancialsConfigService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");
    private static final Set<String> VALID_STATUS = Set.of(KioskSiteEntity.STATUS_ACTIVE, KioskSiteEntity.STATUS_CLOSED);
    private static final String INCLUDE_COSTS = "COSTS";
    private static final String INCLUDE_RATES = "RATES";
    private static final String INCLUDE_GOALS = "GOALS";

    private final KioskFinancialsAccessGuard guard;
    private final KioskSiteRepository siteRepository;
    private final KioskSiteAliasRepository aliasRepository;
    private final KioskCostCategoryRepository categoryRepository;
    private final KioskFixedCostRepository fixedCostRepository;
    private final KioskPeriodConfigRepository configRepository;
    private final LocationRepository locationRepository;
    private final KioskSalesSourceResolver salesSourceResolver;

    private record PeriodKey(Long siteId, int year, int month) {
    }

    // ------------------------------------------------------------------ Sitios

    @Transactional(readOnly = true)
    public List<KioskFinancialsSiteResponse> getSites() throws BusinessException {
        guard.assertCanView();
        List<KioskSiteEntity> sites = siteRepository.findAllByOrderBySortOrderAscNameAsc();
        Map<Long, LocalDate> detected = salesSourceResolver.detectedGoLive(sites);
        Map<Long, LocalDate> effective = salesSourceResolver.goLiveEffective(sites, detected);
        Map<Long, List<String>> aliasesBySite = aliasRepository.findAll().stream()
                .collect(Collectors.groupingBy(KioskSiteAliasEntity::getSiteId,
                        Collectors.mapping(KioskSiteAliasEntity::getAliasNormalized, Collectors.toList())));
        Set<Long> locationIds = sites.stream().map(KioskSiteEntity::getLocationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> codeByLocation = new HashMap<>();
        if (!locationIds.isEmpty()) {
            for (LocationEntity l : locationRepository.findAllById(locationIds)) {
                codeByLocation.put(l.getId(), l.getCode());
            }
        }

        List<KioskFinancialsSiteResponse> result = new ArrayList<>();
        for (KioskSiteEntity site : sites) {
            List<String> aliases = new ArrayList<>(aliasesBySite.getOrDefault(site.getId(), List.of()));
            Collections.sort(aliases);
            result.add(toSiteResponse(site, codeByLocation.get(site.getLocationId()),
                    detected.get(site.getId()), effective.get(site.getId()), aliases));
        }
        return result;
    }

    @Transactional
    public KioskFinancialsSiteResponse createSite(KioskFinancialsSiteCreateRequest request)
            throws BusinessException {
        guard.assertCanEdit();
        if (request == null) {
            throw new BusinessException("Solicitud vacía.");
        }
        String name = cleanName(request.getName());
        if (siteRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new BusinessException("Ya existe un sitio con el nombre '" + name + "'.");
        }
        String status = normalizeStatus(request.getStatus(), KioskSiteEntity.STATUS_ACTIVE);
        LocalDate closedOn = KioskSiteEntity.STATUS_CLOSED.equals(status) ? request.getClosedOn() : null;
        Integer maxOrder = siteRepository.findMaxSortOrder();

        KioskSiteEntity saved = siteRepository.save(KioskSiteEntity.builder()
                .name(name)
                .locationId(null)
                .status(status)
                .closedOn(closedOn)
                .sortOrder((maxOrder == null ? 0 : maxOrder) + 1)
                .build());

        List<String> aliases = new ArrayList<>();
        String alias = KioskSiteAliasNormalizer.normalize(name);
        if (!alias.isEmpty() && aliasRepository.findByAliasNormalized(alias).isEmpty()) {
            aliasRepository.save(KioskSiteAliasEntity.builder().aliasNormalized(alias).siteId(saved.getId()).build());
            aliases.add(alias);
        }
        return toSiteResponse(saved, null, null, saved.getPosGoLiveOverride(), aliases);
    }

    @Transactional
    public KioskFinancialsSiteResponse updateSite(Long id, KioskFinancialsSiteUpdateRequest request)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanEdit();
        KioskSiteEntity site = siteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sitio no encontrado: " + id));
        if (request == null) {
            throw new BusinessException("Solicitud vacía.");
        }

        if (request.getName() != null) {
            String name = cleanName(request.getName());
            Optional<KioskSiteEntity> other = siteRepository.findByNameIgnoreCase(name);
            if (other.isPresent() && !other.get().getId().equals(site.getId())) {
                throw new BusinessException("Ya existe un sitio con el nombre '" + name + "'.");
            }
            site.setName(name);
        }
        if (request.getStatus() != null) {
            String status = normalizeStatus(request.getStatus(), site.getStatus());
            site.setStatus(status);
            if (KioskSiteEntity.STATUS_ACTIVE.equals(status)) {
                site.setClosedOn(null);
            }
        }
        if (request.getClosedOn() != null && KioskSiteEntity.STATUS_CLOSED.equals(site.getStatus())) {
            site.setClosedOn(request.getClosedOn());
        }
        if (request.getPosGoLiveOverride() != null) {
            site.setPosGoLiveOverride(request.getPosGoLiveOverride());
        } else if (Boolean.TRUE.equals(request.getClearGoLiveOverride())) {
            site.setPosGoLiveOverride(null);
        }

        KioskSiteEntity saved = siteRepository.save(site);

        List<String> aliases;
        if (request.getAliases() != null) {
            aliases = replaceAliases(saved, request.getAliases());
        } else {
            aliases = aliasRepository.findBySiteId(saved.getId()).stream()
                    .map(KioskSiteAliasEntity::getAliasNormalized).sorted().collect(Collectors.toList());
        }

        LocalDate detected = salesSourceResolver.detectedGoLive(List.of(saved)).get(saved.getId());
        LocalDate effective = saved.getPosGoLiveOverride() != null ? saved.getPosGoLiveOverride() : detected;
        String locationCode = saved.getLocationId() == null ? null
                : locationRepository.findById(saved.getLocationId()).map(LocationEntity::getCode).orElse(null);
        return toSiteResponse(saved, locationCode, detected, effective, aliases);
    }

    private List<String> replaceAliases(KioskSiteEntity site, List<String> requested) throws BusinessException {
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String raw : requested) {
            String alias = KioskSiteAliasNormalizer.normalize(raw);
            if (!alias.isEmpty()) {
                wanted.add(alias);
            }
        }
        for (String alias : wanted) {
            Optional<KioskSiteAliasEntity> existing = aliasRepository.findByAliasNormalized(alias);
            if (existing.isPresent() && !existing.get().getSiteId().equals(site.getId())) {
                String owner = siteRepository.findById(existing.get().getSiteId())
                        .map(KioskSiteEntity::getName).orElse("otro sitio");
                throw new BusinessException("El alias '" + alias + "' ya pertenece al sitio '" + owner + "'.");
            }
        }
        List<KioskSiteAliasEntity> current = aliasRepository.findBySiteId(site.getId());
        List<KioskSiteAliasEntity> toDelete = current.stream()
                .filter(a -> !wanted.contains(a.getAliasNormalized())).collect(Collectors.toList());
        Set<String> currentNames = current.stream().map(KioskSiteAliasEntity::getAliasNormalized)
                .collect(Collectors.toSet());
        if (!toDelete.isEmpty()) {
            aliasRepository.deleteAll(toDelete);
        }
        for (String alias : wanted) {
            if (!currentNames.contains(alias)) {
                aliasRepository.save(KioskSiteAliasEntity.builder().aliasNormalized(alias).siteId(site.getId()).build());
            }
        }
        return new ArrayList<>(wanted).stream().sorted().collect(Collectors.toList());
    }

    private KioskFinancialsSiteResponse toSiteResponse(KioskSiteEntity site, String locationCode,
                                                       LocalDate detected, LocalDate effective, List<String> aliases) {
        return KioskFinancialsSiteResponse.builder()
                .id(site.getId())
                .name(site.getName())
                .locationId(site.getLocationId())
                .locationCode(locationCode)
                .status(site.getStatus())
                .closedOn(site.getClosedOn())
                .posGoLiveOverride(site.getPosGoLiveOverride())
                .posGoLiveDetected(detected)
                .goLiveEffective(effective)
                .aliases(aliases)
                .build();
    }

    private static String cleanName(String raw) throws BusinessException {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BusinessException("El nombre del sitio es obligatorio.");
        }
        if (name.length() > 120) {
            throw new BusinessException("El nombre del sitio no puede exceder 120 caracteres.");
        }
        return name;
    }

    private static String normalizeStatus(String raw, String fallback) throws BusinessException {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!VALID_STATUS.contains(status)) {
            throw new BusinessException("Estado inválido '" + raw + "'. Use ACTIVE o CLOSED.");
        }
        return status;
    }

    // ------------------------------------------------------------------ Configuración

    @Transactional(readOnly = true)
    public KioskFinancialsConfigResponse getConfig(Integer year, Long siteId, Integer month)
            throws BusinessException, ResourceNotFoundException {
        guard.assertCanView();
        int y = requireYear(year);
        if (month != null) {
            requireMonth(month);
        }
        List<KioskSiteEntity> sites;
        if (siteId != null) {
            sites = List.of(siteRepository.findById(siteId)
                    .orElseThrow(() -> new ResourceNotFoundException("Sitio no encontrado: " + siteId)));
        } else {
            sites = siteRepository.findAllByOrderBySortOrderAscNameAsc();
        }

        List<KioskCostCategoryEntity> categories = categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc();
        List<String> categoryCodes = categories.stream().map(KioskCostCategoryEntity::getCode).toList();

        Map<PeriodKey, KioskPeriodConfigEntity> configs = new HashMap<>();
        for (KioskPeriodConfigEntity c : configRepository.findByPeriodYear(y)) {
            configs.put(new PeriodKey(c.getSiteId(), c.getPeriodYear(), c.getPeriodMonth()), c);
        }
        Map<PeriodKey, Map<String, BigDecimal>> costs = new HashMap<>();
        for (KioskFixedCostEntity fc : fixedCostRepository.findByPeriodYear(y)) {
            costs.computeIfAbsent(new PeriodKey(fc.getSiteId(), fc.getPeriodYear(), fc.getPeriodMonth()),
                    k -> new HashMap<>()).put(fc.getCategoryCode(), fc.getAmount());
        }

        List<KioskFinancialsConfigResponse.Site> siteDtos = new ArrayList<>();
        for (KioskSiteEntity site : sites) {
            List<KioskFinancialsConfigResponse.Month> months = new ArrayList<>();
            for (int m = 1; m <= 12; m++) {
                if (month != null && month != m) {
                    continue;
                }
                PeriodKey key = new PeriodKey(site.getId(), y, m);
                KioskPeriodConfigEntity cfg = configs.get(key);
                Map<String, BigDecimal> siteCosts = costs.getOrDefault(key, Map.of());
                Map<String, BigDecimal> orderedCosts = new LinkedHashMap<>();
                for (String code : categoryCodes) {
                    if (siteCosts.containsKey(code)) {
                        orderedCosts.put(code, siteCosts.get(code));
                    }
                }
                // Categorias inactivas con datos tambien se devuelven al final
                siteCosts.forEach(orderedCosts::putIfAbsent);

                KioskPnlCalculator.Rates rates = cfg == null ? null : new KioskPnlCalculator.Rates(
                        cfg.getProductCostPct(), cfg.getSalesCommissionPct(), cfg.getCardCommissionPct(), cfg.getTaxPct());
                boolean complete = cfg != null
                        && KioskPnlCalculator.isMonthComplete(cfg.getSalesGoal(), rates, siteCosts, categoryCodes);
                months.add(KioskFinancialsConfigResponse.Month.builder()
                        .month(m)
                        .goal(cfg == null ? null : cfg.getSalesGoal())
                        .productCostPct(cfg == null ? null : cfg.getProductCostPct())
                        .salesCommissionPct(cfg == null ? null : cfg.getSalesCommissionPct())
                        .cardCommissionPct(cfg == null ? null : cfg.getCardCommissionPct())
                        .taxPct(cfg == null ? null : cfg.getTaxPct())
                        .source(cfg == null ? null : cfg.getSource())
                        .costs(orderedCosts)
                        .complete(complete)
                        .build());
            }
            siteDtos.add(KioskFinancialsConfigResponse.Site.builder()
                    .siteId(site.getId()).name(site.getName()).status(site.getStatus()).months(months).build());
        }

        return KioskFinancialsConfigResponse.builder()
                .year(y)
                .categories(categories.stream().map(c -> KioskFinancialsConfigResponse.Category.builder()
                        .code(c.getCode()).name(c.getName()).sortOrder(c.getSortOrder()).build()).toList())
                .sites(siteDtos)
                .build();
    }

    /**
     * Cambios en lote: solo se tocan las claves presentes; costo null borra la fila; meta/tasa con null explicito
     * limpia el valor. Todo en una transaccion (cualquier error de validacion revierte todo).
     */
    @Transactional
    public KioskFinancialsBulkResponse bulkUpdate(KioskFinancialsConfigBulkRequest request) throws BusinessException {
        guard.assertCanEdit();
        if (request == null) {
            throw new BusinessException("Solicitud vacía.");
        }
        int year = requireYear(request.getYear());
        List<KioskFinancialsConfigBulkRequest.Change> changes =
                request.getChanges() == null ? List.of() : request.getChanges();
        if (changes.isEmpty()) {
            return KioskFinancialsBulkResponse.builder().updatedMonths(0).updatedCells(0).build();
        }

        Set<String> validCategories = categoryRepository.findByActiveTrueOrderBySortOrderAscCodeAsc().stream()
                .map(KioskCostCategoryEntity::getCode).collect(Collectors.toSet());
        Set<Long> siteIds = new HashSet<>();
        for (KioskFinancialsConfigBulkRequest.Change c : changes) {
            if (c == null || c.getSiteId() == null) {
                throw new BusinessException("Cada cambio requiere siteId.");
            }
            siteIds.add(c.getSiteId());
        }
        Set<Long> existingSites = siteRepository.findAllById(siteIds).stream()
                .map(KioskSiteEntity::getId).collect(Collectors.toSet());
        for (Long id : siteIds) {
            if (!existingSites.contains(id)) {
                throw new BusinessException("Sitio no encontrado: " + id);
            }
        }

        Map<PeriodKey, KioskPeriodConfigEntity> configs = new HashMap<>();
        for (KioskPeriodConfigEntity c : configRepository.findByPeriodYear(year)) {
            configs.put(new PeriodKey(c.getSiteId(), c.getPeriodYear(), c.getPeriodMonth()), c);
        }
        Map<PeriodKey, Map<String, KioskFixedCostEntity>> costs = new HashMap<>();
        for (KioskFixedCostEntity fc : fixedCostRepository.findByPeriodYear(year)) {
            costs.computeIfAbsent(new PeriodKey(fc.getSiteId(), fc.getPeriodYear(), fc.getPeriodMonth()),
                    k -> new HashMap<>()).put(fc.getCategoryCode(), fc);
        }

        Long userId = guard.currentUserId();
        Set<PeriodKey> touchedMonths = new HashSet<>();
        Map<PeriodKey, KioskPeriodConfigEntity> configsToSave = new LinkedHashMap<>();
        Map<String, KioskFixedCostEntity> costsToSave = new LinkedHashMap<>();
        Set<String> createdKeys = new HashSet<>();
        List<KioskFixedCostEntity> costsToDelete = new ArrayList<>();
        int cells = 0;

        for (KioskFinancialsConfigBulkRequest.Change change : changes) {
            int month = requireMonth(change.getMonth());
            PeriodKey key = new PeriodKey(change.getSiteId(), year, month);

            // Escalares (meta y tasas): clave presente = campo no nulo (Optional, posiblemente vacio)
            boolean scalarPresent = change.getGoal() != null || change.getProductCostPct() != null
                    || change.getSalesCommissionPct() != null || change.getCardCommissionPct() != null
                    || change.getTaxPct() != null;
            if (scalarPresent) {
                KioskPeriodConfigEntity cfg = configs.computeIfAbsent(key, k -> KioskPeriodConfigEntity.builder()
                        .siteId(k.siteId()).periodYear(k.year()).periodMonth(k.month()).build());
                if (change.getGoal() != null) {
                    cfg.setSalesGoal(amountOrNull(change.getGoal().orElse(null), "meta"));
                    cells++;
                }
                if (change.getProductCostPct() != null) {
                    cfg.setProductCostPct(rateOrNull(change.getProductCostPct().orElse(null), "costo del producto"));
                    cells++;
                }
                if (change.getSalesCommissionPct() != null) {
                    cfg.setSalesCommissionPct(rateOrNull(change.getSalesCommissionPct().orElse(null), "comisión de venta"));
                    cells++;
                }
                if (change.getCardCommissionPct() != null) {
                    cfg.setCardCommissionPct(rateOrNull(change.getCardCommissionPct().orElse(null), "comisión de tarjeta"));
                    cells++;
                }
                if (change.getTaxPct() != null) {
                    cfg.setTaxPct(rateOrNull(change.getTaxPct().orElse(null), "IVA"));
                    cells++;
                }
                cfg.setSource(KioskPeriodConfigEntity.SOURCE_MANUAL);
                cfg.setUpdatedBy(userId);
                configsToSave.put(key, cfg);
                touchedMonths.add(key);
            }

            if (change.getCosts() != null) {
                for (Map.Entry<String, BigDecimal> e : change.getCosts().entrySet()) {
                    String code = e.getKey();
                    if (code == null || !validCategories.contains(code)) {
                        throw new BusinessException("Categoría de costo desconocida: '" + code + "'.");
                    }
                    Map<String, KioskFixedCostEntity> monthCosts = costs.computeIfAbsent(key, k -> new HashMap<>());
                    String saveKey = key + "|" + code;
                    if (e.getValue() == null) {
                        KioskFixedCostEntity existing = monthCosts.remove(code);
                        costsToSave.remove(saveKey);
                        // Solo se borra lo que ya existe en BD (no filas creadas antes en esta misma solicitud)
                        if (existing != null && !createdKeys.remove(saveKey)) {
                            costsToDelete.add(existing);
                        }
                    } else {
                        BigDecimal amount = amountOrNull(e.getValue(), "costo " + code);
                        if (!monthCosts.containsKey(code)) {
                            createdKeys.add(saveKey);
                        }
                        KioskFixedCostEntity row = monthCosts.computeIfAbsent(code, c -> KioskFixedCostEntity.builder()
                                .siteId(key.siteId()).periodYear(key.year()).periodMonth(key.month())
                                .categoryCode(c).build());
                        row.setAmount(amount);
                        row.setUpdatedBy(userId);
                        costsToSave.put(saveKey, row);
                    }
                    cells++;
                    touchedMonths.add(key);
                }
            }
        }

        if (!configsToSave.isEmpty()) {
            configRepository.saveAll(configsToSave.values());
        }
        if (!costsToDelete.isEmpty()) {
            fixedCostRepository.deleteAll(costsToDelete);
        }
        if (!costsToSave.isEmpty()) {
            fixedCostRepository.saveAll(costsToSave.values());
        }
        return KioskFinancialsBulkResponse.builder().updatedMonths(touchedMonths.size()).updatedCells(cells).build();
    }

    /** Copia costos/tasas/metas de un mes origen a meses destino. overwrite=false no pisa celdas llenas. */
    @Transactional
    public KioskFinancialsCopyResponse copy(KioskFinancialsConfigCopyRequest request) throws BusinessException {
        guard.assertCanEdit();
        if (request == null) {
            throw new BusinessException("Solicitud vacía.");
        }
        int fromYear = requireYear(request.getFromYear());
        int fromMonth = requireMonth(request.getFromMonth());
        int toYear = requireYear(request.getToYear());
        if (request.getToMonths() == null || request.getToMonths().isEmpty()) {
            throw new BusinessException("Indique al menos un mes destino.");
        }
        LinkedHashSet<Integer> toMonths = new LinkedHashSet<>();
        for (Integer m : request.getToMonths()) {
            toMonths.add(requireMonth(m));
        }
        Set<String> include = new HashSet<>();
        if (request.getInclude() == null || request.getInclude().isEmpty()) {
            include.addAll(List.of(INCLUDE_COSTS, INCLUDE_RATES, INCLUDE_GOALS));
        } else {
            for (String raw : request.getInclude()) {
                String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
                if (!Set.of(INCLUDE_COSTS, INCLUDE_RATES, INCLUDE_GOALS).contains(v)) {
                    throw new BusinessException("Valor de 'include' inválido: '" + raw + "'. Use COSTS, RATES o GOALS.");
                }
                include.add(v);
            }
        }
        boolean overwrite = Boolean.TRUE.equals(request.getOverwrite());

        List<KioskSiteEntity> sites;
        if (request.getSiteIds() == null || request.getSiteIds().isEmpty()) {
            sites = siteRepository.findAllByOrderBySortOrderAscNameAsc();
        } else {
            sites = siteRepository.findAllById(request.getSiteIds());
            Set<Long> found = sites.stream().map(KioskSiteEntity::getId).collect(Collectors.toSet());
            for (Long id : request.getSiteIds()) {
                if (!found.contains(id)) {
                    throw new BusinessException("Sitio no encontrado: " + id);
                }
            }
        }

        Set<Integer> years = new HashSet<>(List.of(fromYear, toYear));
        Map<PeriodKey, KioskPeriodConfigEntity> configs = new HashMap<>();
        Map<PeriodKey, Map<String, KioskFixedCostEntity>> costs = new HashMap<>();
        for (int y : years) {
            for (KioskPeriodConfigEntity c : configRepository.findByPeriodYear(y)) {
                configs.put(new PeriodKey(c.getSiteId(), c.getPeriodYear(), c.getPeriodMonth()), c);
            }
            for (KioskFixedCostEntity fc : fixedCostRepository.findByPeriodYear(y)) {
                costs.computeIfAbsent(new PeriodKey(fc.getSiteId(), fc.getPeriodYear(), fc.getPeriodMonth()),
                        k -> new HashMap<>()).put(fc.getCategoryCode(), fc);
            }
        }

        Long userId = guard.currentUserId();
        int copiedMonths = 0;
        int copiedCells = 0;
        int skippedCells = 0;
        Map<PeriodKey, KioskPeriodConfigEntity> configsToSave = new LinkedHashMap<>();
        List<KioskFixedCostEntity> costsToSave = new ArrayList<>();

        for (KioskSiteEntity site : sites) {
            PeriodKey sourceKey = new PeriodKey(site.getId(), fromYear, fromMonth);
            KioskPeriodConfigEntity srcCfg = configs.get(sourceKey);
            Map<String, KioskFixedCostEntity> srcCosts = costs.getOrDefault(sourceKey, Map.of());

            for (int toMonth : toMonths) {
                if (toYear == fromYear && toMonth == fromMonth) {
                    continue; // copiar sobre si mismo no tiene sentido
                }
                PeriodKey targetKey = new PeriodKey(site.getId(), toYear, toMonth);
                int copiedHere = 0;

                KioskPeriodConfigEntity target = configs.get(targetKey);
                boolean configTouched = false;
                if (srcCfg != null && (include.contains(INCLUDE_GOALS) || include.contains(INCLUDE_RATES))) {
                    if (target == null) {
                        target = KioskPeriodConfigEntity.builder()
                                .siteId(site.getId()).periodYear(toYear).periodMonth(toMonth).build();
                    }
                    if (include.contains(INCLUDE_GOALS)) {
                        int[] r = copyCell(srcCfg.getSalesGoal(), target.getSalesGoal(), overwrite, target::setSalesGoal);
                        copiedHere += r[0];
                        skippedCells += r[1];
                        configTouched |= r[0] > 0;
                    }
                    if (include.contains(INCLUDE_RATES)) {
                        int[] r1 = copyCell(srcCfg.getProductCostPct(), target.getProductCostPct(), overwrite, target::setProductCostPct);
                        int[] r2 = copyCell(srcCfg.getSalesCommissionPct(), target.getSalesCommissionPct(), overwrite, target::setSalesCommissionPct);
                        int[] r3 = copyCell(srcCfg.getCardCommissionPct(), target.getCardCommissionPct(), overwrite, target::setCardCommissionPct);
                        int[] r4 = copyCell(srcCfg.getTaxPct(), target.getTaxPct(), overwrite, target::setTaxPct);
                        for (int[] r : List.of(r1, r2, r3, r4)) {
                            copiedHere += r[0];
                            skippedCells += r[1];
                            configTouched |= r[0] > 0;
                        }
                    }
                    if (configTouched) {
                        target.setSource(KioskPeriodConfigEntity.SOURCE_COPIED);
                        target.setUpdatedBy(userId);
                        configs.put(targetKey, target);
                        configsToSave.put(targetKey, target);
                    }
                }

                if (include.contains(INCLUDE_COSTS)) {
                    Map<String, KioskFixedCostEntity> targetCosts =
                            costs.computeIfAbsent(targetKey, k -> new HashMap<>());
                    for (KioskFixedCostEntity src : srcCosts.values()) {
                        KioskFixedCostEntity existing = targetCosts.get(src.getCategoryCode());
                        if (existing != null && !overwrite) {
                            skippedCells++;
                            continue;
                        }
                        KioskFixedCostEntity row = existing != null ? existing : KioskFixedCostEntity.builder()
                                .siteId(site.getId()).periodYear(toYear).periodMonth(toMonth)
                                .categoryCode(src.getCategoryCode()).build();
                        row.setAmount(src.getAmount());
                        row.setUpdatedBy(userId);
                        targetCosts.put(src.getCategoryCode(), row);
                        costsToSave.add(row);
                        copiedHere++;
                    }
                }

                if (copiedHere > 0) {
                    copiedMonths++;
                    copiedCells += copiedHere;
                }
            }
        }

        if (!configsToSave.isEmpty()) {
            configRepository.saveAll(configsToSave.values());
        }
        if (!costsToSave.isEmpty()) {
            fixedCostRepository.saveAll(costsToSave);
        }
        return KioskFinancialsCopyResponse.builder()
                .copiedMonths(copiedMonths).copiedCells(copiedCells).skippedCells(skippedCells).build();
    }

    /** @return {copiados, omitidos}. Un origen nulo no cuenta como celda. */
    private static int[] copyCell(BigDecimal source, BigDecimal current, boolean overwrite, Consumer<BigDecimal> setter) {
        if (source == null) {
            return new int[]{0, 0};
        }
        if (current != null && !overwrite) {
            return new int[]{0, 1};
        }
        setter.accept(source);
        return new int[]{1, 0};
    }

    // ------------------------------------------------------------------ Validaciones

    private static int requireYear(Integer year) throws BusinessException {
        if (year == null || year < 2000 || year > 2100) {
            throw new BusinessException("Año inválido: " + year + ".");
        }
        return year;
    }

    private static int requireMonth(Integer month) throws BusinessException {
        if (month == null || month < 1 || month > 12) {
            throw new BusinessException("Mes inválido: " + month + ". Debe estar entre 1 y 12.");
        }
        return month;
    }

    private static BigDecimal amountOrNull(BigDecimal value, String label) throws BusinessException {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new BusinessException("El valor de " + label + " no puede ser negativo.");
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException("El valor de " + label + " excede el máximo permitido.");
        }
        return rounded;
    }

    private static BigDecimal rateOrNull(BigDecimal value, String label) throws BusinessException {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("La tasa de " + label + " debe estar entre 0 y 1 (ej. 0.18 = 18 %).");
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
