package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.CommissionSummary;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.SellerCommission;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.money;

/**
 * Comisión del vendedor: 2% del monto sin envío (el neto de la venta). Se calcula sobre lo ya conciliado y se registra
 * en el sistema; se congela cuando todo el mes está revisado, sin fecha de pago fija. Una devolución no paga comisión y
 * un cambio paga solo por lo que tenga de neto (la diferencia contra la venta anterior). No cambia lo que se espera
 * recibir de ninguna venta.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationCommissionService {

    private final ReconCaseRepository caseRepository;
    private final ReconCommissionLineRepository commissionRepository;
    private final ReconSellerRepository sellerRepository;
    private final OnlineSaleRepository saleRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconciliationProperties props;
    private final ReconActor actor;

    /** Crea o actualiza las líneas del mes (las congeladas no se tocan) y devuelve el resumen. */
    @Transactional
    public CommissionSummary calculate(String month) throws BusinessException {
        Month m = parse(month);
        List<ReconCaseEntity> cases = caseRepository.findBySaleDateBetween(m.first(), m.last());
        Map<Long, OnlineSaleEntity> sales = saleRepository.findAllById(cases.stream().map(ReconCaseEntity::getOnlineSaleId).toList()).stream()
                .collect(Collectors.toMap(OnlineSaleEntity::getId, s -> s));
        Map<Long, ReconCommissionLineEntity> lines = commissionRepository.findByCaseIdIn(cases.stream().map(ReconCaseEntity::getId).toList()).stream()
                .collect(Collectors.toMap(ReconCommissionLineEntity::getCaseId, l -> l));
        List<ReconCommissionLineEntity> toSave = new ArrayList<>();
        for (ReconCaseEntity c : cases) {
            if (c.getSellerId() == null) continue;
            OnlineSaleEntity sale = sales.get(c.getOnlineSaleId());
            boolean returned = isReturned(c, sale);
            boolean eligible = c.getStatus().isReconciled();
            if (!returned && !eligible) continue;
            ReconCommissionLineEntity line = lines.get(c.getId());
            if (line != null && line.getStatus() == Recon.CommissionStatus.CONGELADA) continue;
            BigDecimal base = money(sale == null ? null : sale.getNetAmount());
            BigDecimal amount = returned ? money(BigDecimal.ZERO) : base.multiply(props.getCommissionRate()).setScale(2, RoundingMode.HALF_UP);
            if (line == null) {
                line = ReconCommissionLineEntity.builder().caseId(c.getId()).build();
            }
            line.setSellerId(c.getSellerId());
            line.setMonth(month);
            line.setBaseAmount(returned ? money(BigDecimal.ZERO) : base);
            line.setRate(props.getCommissionRate());
            line.setCommissionAmount(amount);
            line.setStatus(returned ? Recon.CommissionStatus.ANULADA_DEVOLUCION : Recon.CommissionStatus.CALCULADA);
            toSave.add(line);
        }
        commissionRepository.saveAll(toSave);
        recompute.recomputeAll(cases.stream().filter(c -> !c.isClosed()).toList());
        return summary(month);
    }

    @Transactional(readOnly = true)
    public CommissionSummary summary(String month) throws BusinessException {
        Month m = parse(month);
        List<ReconCaseEntity> cases = caseRepository.findBySaleDateBetween(m.first(), m.last());
        Map<Long, ReconCommissionLineEntity> lines = commissionRepository.findByMonth(month).stream()
                .collect(Collectors.toMap(ReconCommissionLineEntity::getCaseId, l -> l, (a, b) -> a));
        Map<Long, String> sellerNames = sellerRepository.findAll().stream().collect(Collectors.toMap(ReconSellerEntity::getId, ReconSellerEntity::getName));
        Map<Long, List<ReconCommissionLineEntity>> bySeller = lines.values().stream().collect(Collectors.groupingBy(ReconCommissionLineEntity::getSellerId));

        int pending = 0;
        int withoutSeller = 0;
        for (ReconCaseEntity c : cases) {
            if (c.getExcludedReason() != null) {
                if (c.getSellerId() == null && c.getExcludedReason().contains("DEVOLUCION")) withoutSeller++;
                continue;
            }
            if (!c.getStatus().isReconciled()) pending++;
            else if (c.getSellerId() == null) withoutSeller++;
        }
        List<SellerCommission> sellers = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var e : bySeller.entrySet()) {
            List<ReconCommissionLineEntity> ls = e.getValue();
            BigDecimal base = ls.stream().map(ReconCommissionLineEntity::getBaseAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal commission = ls.stream().map(ReconCommissionLineEntity::getCommissionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            int annulled = (int) ls.stream().filter(l -> l.getStatus() == Recon.CommissionStatus.ANULADA_DEVOLUCION).count();
            sellers.add(new SellerCommission(e.getKey(), sellerNames.getOrDefault(e.getKey(), "?"), ls.size(), money(base), props.getCommissionRate(),
                    money(commission), annulled, 0));
            total = total.add(commission);
        }
        sellers.sort(Comparator.comparing(SellerCommission::seller));
        boolean closed = !lines.isEmpty() && lines.values().stream().allMatch(l -> l.getStatus() == Recon.CommissionStatus.CONGELADA);
        boolean canClose = !closed && !lines.isEmpty() && pending == 0 && withoutSeller == 0;
        return new CommissionSummary(month, sellers, pending, withoutSeller, canClose, closed, money(total));
    }

    /** Congela las comisiones del mes. Solo si todo el mes está conciliado o excluido y todo vendedor está en el catálogo. */
    @Transactional
    public CommissionSummary closeMonth(String month) throws BusinessException {
        CommissionSummary before = calculate(month);
        if (before.closed()) throw new BusinessException("Las comisiones de " + month + " ya están cerradas.");
        if (before.pendingCases() > 0) {
            throw new BusinessException("Faltan " + before.pendingCases() + " caso(s) por conciliar o excluir en " + month + " antes de cerrar las comisiones.");
        }
        if (before.casesWithoutSeller() > 0) {
            throw new BusinessException(before.casesWithoutSeller() + " caso(s) tienen un vendedor que no está en el catálogo. Agréguelo antes de cerrar el mes.");
        }
        if (before.sellers().isEmpty()) throw new BusinessException("No hay comisiones calculadas en " + month + ".");
        LocalDateTime now = LocalDateTime.now();
        Long userId = actor.id();
        List<ReconCommissionLineEntity> lines = commissionRepository.findByMonth(month);
        for (ReconCommissionLineEntity l : lines) {
            if (l.getStatus() == Recon.CommissionStatus.CONGELADA) continue;
            l.setStatus(Recon.CommissionStatus.CONGELADA);
            l.setLockedAt(now);
            l.setLockedBy(userId);
        }
        commissionRepository.saveAll(lines);
        events.record(null, null, "COMISIONES_CERRADAS", null, null,
                Map.of("mes", month, "lineas", lines.size(), "total", before.totalCommission().toPlainString()));
        return summary(month);
    }

    private static boolean isReturned(ReconCaseEntity c, OnlineSaleEntity sale) {
        return (sale != null && "DEVOLUCION".equals(sale.getStatus()))
                || (c.getExcludedReason() != null && c.getExcludedReason().contains("DEVOLUCION"));
    }

    private record Month(LocalDate first, LocalDate last) {
    }

    private static Month parse(String month) throws BusinessException {
        try {
            YearMonth ym = YearMonth.parse(month);
            return new Month(ym.atDay(1), ym.atEndOfMonth());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException("Indique el mes con el formato AAAA-MM, por ejemplo 2026-10.");
        }
    }
}
