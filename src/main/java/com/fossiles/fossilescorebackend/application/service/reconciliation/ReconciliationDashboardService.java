package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.Bucket;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CaseFilter;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.Dashboard;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCommissionLineEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCommissionLineRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.money;

/** Indicadores del dashboard calculados con los casos reales del filtro. Nada es decorativo. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReconciliationDashboardService {

    private static final Set<Recon.Status> WITH_DIFFERENCES = EnumSet.of(Recon.Status.CON_DIFERENCIAS, Recon.Status.REQUIERE_AUTORIZACION,
            Recon.Status.POSIBLE_DUPLICADO);

    private final ReconCaseRepository caseRepository;
    private final ReconCommissionLineRepository commissionRepository;
    private final ReconReceiptRepository receiptRepository;

    public Dashboard dashboard(CaseFilter filter) {
        List<ReconCaseEntity> all = caseRepository.findAll(ReconciliationSpecs.of(filter, Set.of()));
        List<ReconCaseEntity> live = all.stream().filter(c -> c.getStatus() != Recon.Status.EXCLUIDO).toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Recon.Status s : Recon.Status.values()) byStatus.put(s.name(), 0L);
        all.forEach(c -> byStatus.merge(c.getStatus().name(), 1L, Long::sum));

        long pending = count(live, c -> c.getStatus() == Recon.Status.PENDIENTE_REVISION);
        long reconciled = count(live, c -> c.getStatus().isReconciled());
        long withDifferences = count(live, c -> WITH_DIFFERENCES.contains(c.getStatus()));
        long incomplete = count(live, c -> c.getStatus() == Recon.Status.INFORMACION_INCOMPLETA);

        BigDecimal expected = sum(live, ReconCaseEntity::getExpectedAmount);
        BigDecimal received = sum(live, ReconCaseEntity::getReceivedAmount);
        BigDecimal pendingDifferences = money(live.stream()
                .filter(c -> !c.getStatus().isReconciled() && c.getReceivedAmount().signum() > 0 && c.getDifference().signum() != 0)
                .map(c -> c.getDifference().abs()).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal awaitingFunds = money(live.stream()
                .filter(c -> !c.getStatus().isReconciled() && c.getReceivedAmount().signum() == 0 && c.getExpectedAmount().signum() > 0)
                .map(ReconCaseEntity::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        Map<String, Long> byProblem = new LinkedHashMap<>();
        for (ReconCaseEntity c : live) {
            if (c.getProblemType() != null) byProblem.merge(c.getProblemType().name(), 1L, Long::sum);
        }
        return new Dashboard(byStatus, pending, reconciled, withDifferences, incomplete, all.size() - live.size(), expected, received,
                pendingDifferences, awaitingFunds, commissionsToValidate(live), buckets(live, ReconCaseEntity::getPaymentMethod),
                buckets(live, ReconCaseEntity::getCarrier), byProblem, receiptRepository.findUnapplied(PageRequest.of(0, 1000)).size());
    }

    /** Casos conciliados cuya comisión aún no está congelada: falta calcularla o cerrar el mes. */
    private long commissionsToValidate(List<ReconCaseEntity> live) {
        List<Long> ids = live.stream().filter(c -> c.getStatus().isReconciled()).map(ReconCaseEntity::getId).toList();
        if (ids.isEmpty()) return 0;
        Set<Long> frozen = new HashSet<>();
        for (int i = 0; i < ids.size(); i += 500) {
            for (ReconCommissionLineEntity l : commissionRepository.findByCaseIdIn(ids.subList(i, Math.min(ids.size(), i + 500)))) {
                if (l.getStatus() == Recon.CommissionStatus.CONGELADA) frozen.add(l.getCaseId());
            }
        }
        return ids.stream().filter(id -> !frozen.contains(id)).count();
    }

    private List<Bucket> buckets(List<ReconCaseEntity> live, Function<ReconCaseEntity, String> key) {
        Map<String, List<ReconCaseEntity>> groups = live.stream().collect(Collectors.groupingBy(c -> key.apply(c) == null ? "(sin dato)" : key.apply(c)));
        return groups.entrySet().stream().map(e -> {
            List<ReconCaseEntity> cs = e.getValue();
            long rec = count(cs, c -> c.getStatus().isReconciled());
            long diff = count(cs, c -> WITH_DIFFERENCES.contains(c.getStatus()));
            long inc = count(cs, c -> c.getStatus() == Recon.Status.INFORMACION_INCOMPLETA);
            return new Bucket(e.getKey(), cs.size(), rec, cs.size() - rec - diff - inc, diff, inc, sum(cs, ReconCaseEntity::getExpectedAmount),
                    sum(cs, ReconCaseEntity::getReceivedAmount));
        }).sorted(Comparator.comparingLong(Bucket::cases).reversed()).toList();
    }

    private static long count(List<ReconCaseEntity> cases, java.util.function.Predicate<ReconCaseEntity> p) {
        return cases.stream().filter(p).count();
    }

    private static BigDecimal sum(List<ReconCaseEntity> cases, Function<ReconCaseEntity, BigDecimal> f) {
        return money(cases.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
