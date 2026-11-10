package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.DifferenceDto;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconCaseEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.ReconDifferenceEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconCaseRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.ReconDifferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.money;
import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.q;
import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.sameMoney;

/**
 * Ajustes sobre una diferencia. Solo se acepta un faltante (se recibió menos de lo cobrado) y solo con motivo
 * y autorización. Por decisión del negocio quien propone también puede autorizar; el historial guarda ambos
 * nombres. Un sobrante no se ajusta: se corrige el dato o se anula la aplicación. Nunca se oculta una diferencia:
 * el ajuste queda como movimiento aparte y visible.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationDifferenceService {

    private final ReconCaseRepository caseRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconEventService events;
    private final ReconActor actor;

    @Transactional
    public ReconDifferenceEntity propose(Long caseId, String reason) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(caseId);
        if (reason == null || reason.trim().length() < 15) {
            throw new BusinessException("Escriba el motivo del ajuste (mínimo 15 caracteres): qué pasó con la diferencia y por qué se acepta.");
        }
        recompute.recompute(c);
        BigDecimal shortfall = money(c.getExpectedAmount()).subtract(money(c.getReceivedAmount()));
        boolean hasFunds = money(c.getReceivedAmount()).signum() > 0;
        if (!hasFunds || shortfall.signum() <= 0) {
            throw new BusinessException("Solo se puede ajustar un faltante: el caso debe tener fondos aplicados por menos de lo cobrado. "
                    + "Un sobrante se corrige anulando la aplicación o el dato de origen.");
        }
        if (hasActiveAdjustment(caseId)) {
            throw new BusinessException("El caso ya tiene un ajuste propuesto o autorizado.");
        }
        ReconDifferenceEntity d = differenceRepository.save(ReconDifferenceEntity.builder().caseId(caseId)
                .diffType(Recon.DifferenceType.MONTO_INCORRECTO).amount(shortfall).status(Recon.DifferenceStatus.PROPOSED)
                .reason(reason.trim()).proposedBy(actor.id()).proposedAt(LocalDateTime.now()).build());
        Recon.Status from = c.getStatus();
        recompute.recompute(c);
        events.record(caseId, null, "AJUSTE_PROPUESTO", from, c.getStatus(),
                Map.of("monto", shortfall.toPlainString(), "motivo", reason.trim(), "diferencia", d.getId()));
        return d;
    }

    @Transactional
    public ReconDifferenceEntity authorize(Long differenceId) throws ResourceNotFoundException, BusinessException {
        ReconDifferenceEntity d = differenceRepository.findById(differenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Ajuste no encontrado: " + differenceId));
        if (d.getStatus() != Recon.DifferenceStatus.PROPOSED) {
            throw new BusinessException("El ajuste no está pendiente de autorización. Estado: " + d.getStatus() + ".");
        }
        ReconCaseEntity c = openCase(d.getCaseId());
        BigDecimal shortfall = money(c.getExpectedAmount()).subtract(money(c.getReceivedAmount()));
        if (!sameMoney(shortfall, d.getAmount())) {
            throw new BusinessException("La diferencia cambió desde que se propuso el ajuste (" + q(d.getAmount()) + " → " + q(shortfall)
                    + "). Rechácelo y proponga uno nuevo.");
        }
        d.setStatus(Recon.DifferenceStatus.AUTHORIZED);
        d.setAuthorizedBy(actor.id());
        d.setAuthorizedAt(LocalDateTime.now());
        differenceRepository.save(d);
        Recon.Status from = c.getStatus();
        recompute.recompute(c);
        events.record(c.getId(), null, "AJUSTE_AUTORIZADO", from, c.getStatus(),
                Map.of("monto", d.getAmount().toPlainString(), "propuso", String.valueOf(d.getProposedBy()),
                        "autorizo", String.valueOf(d.getAuthorizedBy()), "motivo", d.getReason()));
        return d;
    }

    @Transactional
    public ReconDifferenceEntity reject(Long differenceId, String reason) throws ResourceNotFoundException, BusinessException {
        ReconDifferenceEntity d = differenceRepository.findById(differenceId)
                .orElseThrow(() -> new ResourceNotFoundException("Ajuste no encontrado: " + differenceId));
        if (d.getStatus() != Recon.DifferenceStatus.PROPOSED) {
            throw new BusinessException("Solo se rechaza un ajuste pendiente de autorización.");
        }
        ReconCaseEntity c = openCase(d.getCaseId());
        d.setStatus(Recon.DifferenceStatus.REJECTED);
        differenceRepository.save(d);
        Recon.Status from = c.getStatus();
        recompute.recompute(c);
        events.record(c.getId(), null, "AJUSTE_RECHAZADO", from, c.getStatus(),
                Map.of("monto", d.getAmount().toPlainString(), "motivo", reason == null ? "" : reason.trim()));
        return d;
    }

    @Transactional(readOnly = true)
    public List<DifferenceDto> pendingAuthorization() {
        return differenceRepository.findByStatus(Recon.DifferenceStatus.PROPOSED).stream().map(ReconciliationDifferenceService::toDto).toList();
    }

    public static DifferenceDto toDto(ReconDifferenceEntity d) {
        return toDto(d, java.util.Map.of());
    }

    public static DifferenceDto toDto(ReconDifferenceEntity d, Map<Long, String> names) {
        return new DifferenceDto(d.getId(), d.getDiffType().name(), d.getAmount(), d.getStatus().name(), d.getReason(), d.getProposedBy(),
                names.get(d.getProposedBy()), d.getProposedAt(), d.getAuthorizedBy(), names.get(d.getAuthorizedBy()), d.getAuthorizedAt());
    }

    private boolean hasActiveAdjustment(Long caseId) {
        return differenceRepository.findFirstByCaseIdAndStatusOrderByIdDesc(caseId, Recon.DifferenceStatus.PROPOSED).isPresent()
                || differenceRepository.findFirstByCaseIdAndStatusOrderByIdDesc(caseId, Recon.DifferenceStatus.AUTHORIZED).isPresent();
    }

    private ReconCaseEntity openCase(Long id) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + id));
        if (c.isClosed()) throw new BusinessException("El caso está en un lote cerrado y no se puede modificar.");
        return c;
    }
}
