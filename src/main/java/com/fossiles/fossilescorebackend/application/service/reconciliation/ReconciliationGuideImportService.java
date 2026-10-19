package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.GuideImportResult;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.RowReport;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.blank;
import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.norm;

/**
 * Carga las guías históricas recuperadas del portal del transportista (que muestra el ENVL y los datos del cliente).
 * Solo llena guías que faltan: nunca pisa una guía existente ni toca montos. Con {@code dryRun} solo muestra lo que haría.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationGuideImportService {

    private static final int REPORT_LIMIT = 500;

    private final OnlineSaleRepository saleRepository;
    private final ReconCaseRepository caseRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconActor actor;

    @Transactional
    public GuideImportResult importGuides(byte[] bytes, String fileName, boolean dryRun) throws BusinessException {
        ReconciliationFileParser.Sheet sheet = ReconciliationFileParser.read(bytes, fileName);
        List<Map<String, String>> rows = sheet.rows();
        List<String> envls = rows.stream().map(ReconciliationFileParser::envlOf).filter(Objects::nonNull).distinct().toList();
        if (envls.isEmpty()) {
            throw new BusinessException("No encontré una columna de número de envío (ENVL). Columnas del archivo: "
                    + String.join(", ", sheet.headers().stream().filter(h -> !h.isEmpty()).toList()) + ".");
        }
        Map<String, OnlineSaleEntity> byEnvl = new HashMap<>();
        for (int i = 0; i < envls.size(); i += 500) {
            for (OnlineSaleEntity s : saleRepository.findByShipmentNumberIn(envls.subList(i, Math.min(envls.size(), i + 500)))) {
                byEnvl.put(s.getShipmentNumber().trim().toUpperCase(Locale.ROOT), s);
            }
        }

        List<RowReport> report = new ArrayList<>();
        Map<String, String> guideByEnvl = new HashMap<>();
        Set<Long> changedSales = new LinkedHashSet<>();
        int toSet = 0;
        int set = 0;
        int same = 0;
        int conflicts = 0;
        int notFound = 0;
        for (Map<String, String> row : rows) {
            int n = Integer.parseInt(row.getOrDefault("#fila", "0"));
            String envl = ReconciliationFileParser.envlOf(row);
            String guide = ReconciliationFileParser.guideOf(row);
            if (envl == null || blank(guide)) {
                add(report, n, "FILA_INCOMPLETA", "Falta el ENVL o la guía.");
                continue;
            }
            String key = envl.trim().toUpperCase(Locale.ROOT);
            String previous = guideByEnvl.putIfAbsent(key, guide.trim());
            if (previous != null && !norm(previous).equals(norm(guide))) {
                conflicts++;
                add(report, n, "CONFLICTO", "El archivo trae dos guías distintas para " + envl + ": " + previous + " y " + guide.trim() + ".");
                continue;
            }
            OnlineSaleEntity sale = byEnvl.get(key);
            if (sale == null) {
                notFound++;
                add(report, n, "NO_ENCONTRADA", "No hay una venta con el ENVL " + envl + ".");
                continue;
            }
            if (!blank(sale.getGuideNumber())) {
                if (norm(sale.getGuideNumber()).equals(norm(guide))) {
                    same++;
                } else {
                    conflicts++;
                    add(report, n, "CONFLICTO", envl + " ya tiene la guía " + sale.getGuideNumber() + "; no se cambia por " + guide.trim() + ".");
                }
                continue;
            }
            String usedBy = saleRepository.findByGuideNumberIgnoreCase(guide.trim()).stream()
                    .filter(o -> !o.getId().equals(sale.getId())).map(OnlineSaleEntity::getShipmentNumber).findFirst().orElse(null);
            if (usedBy != null) {
                conflicts++;
                add(report, n, "CONFLICTO", "La guía " + guide.trim() + " ya está en " + usedBy + ". Revise cuál corresponde.");
                continue;
            }
            toSet++;
            if (!dryRun) {
                sale.setGuideNumber(guide.trim());
                sale.setSkipAmountCalculation(true);   // solo se llena la guía: nunca se recalculan montos
                sale.setUpdatedBy(actor.id());
                saleRepository.save(sale);
                changedSales.add(sale.getId());
                set++;
                events.record(null, null, "GUIA_IMPORTADA", null, null, Map.of("envl", envl, "guia", guide.trim(), "archivo", fileName == null ? "" : fileName));
            }
        }
        if (!dryRun && !changedSales.isEmpty()) {
            List<ReconCaseEntity> cases = caseRepository.findByOnlineSaleIdIn(changedSales).stream().filter(c -> !c.isClosed()).collect(Collectors.toList());
            recompute.recomputeAll(cases);
        }
        return new GuideImportResult(dryRun, rows.size(), toSet, set, same, conflicts, notFound, report);
    }

    private static void add(List<RowReport> report, int row, String status, String message) {
        if (report.size() < REPORT_LIMIT) report.add(new RowReport(row, status, message));
    }
}
