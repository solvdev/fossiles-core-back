package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.BulkResult;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.GenerateResult;
import com.fossiles.fossilescorebackend.application.dto.reconciliation.ReconciliationDtos.InvalidCaseRef;
import com.fossiles.fossilescorebackend.application.exception.BusinessException;
import com.fossiles.fossilescorebackend.application.exception.ResourceNotFoundException;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.OnlineSaleEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.*;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.OnlineSaleRepository;
import com.fossiles.fossilescorebackend.infrastructure.persistence.repository.recon.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/** Ciclo de vida de los casos: generarlos desde las ventas, revalidar y conciliar, excluir y reabrir. */
@Service
@RequiredArgsConstructor
public class ReconciliationCaseService {

    private static final Set<String> TRACKED_SALE_STATUS = Set.of("ENVIADO", "ENTREGADO", "DEVOLUCION", "ANULADA", "CANCELADO");
    private static final Set<String> EXCLUDED_SALE_STATUS = Set.of("DEVOLUCION", "ANULADA", "CANCELADO");
    private static final int CHUNK = 500;

    private final ReconCaseRepository caseRepository;
    private final ReconSellerRepository sellerRepository;
    private final ReconReceiptApplicationRepository applicationRepository;
    private final ReconDifferenceRepository differenceRepository;
    private final OnlineSaleRepository saleRepository;
    private final ReconciliationRecomputeService recompute;
    private final ReconciliationLedgerService ledger;
    private final ReconEventService events;
    private final ReconActor actor;

    /** Crea un caso por venta despachada del período. Idempotente: las ventas que ya tienen caso se omiten. */
    @Transactional
    public GenerateResult generate(LocalDate from, LocalDate to) throws BusinessException {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException("Indique un período válido (desde y hasta).");
        }
        if (ChronoUnit.DAYS.between(from, to) > 400) {
            throw new BusinessException("El período no puede pasar de 400 días.");
        }
        List<OnlineSaleEntity> sales = saleRepository.findBySaleDateBetweenOrderBySaleDateDesc(from, to).stream()
                .filter(s -> s.getStatus() != null && TRACKED_SALE_STATUS.contains(s.getStatus())).toList();

        Set<Long> existing = new HashSet<>();
        List<Long> ids = sales.stream().map(OnlineSaleEntity::getId).toList();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            caseRepository.findByOnlineSaleIdIn(ids.subList(i, Math.min(ids.size(), i + CHUNK)))
                    .forEach(c -> existing.add(c.getOnlineSaleId()));
        }
        Map<String, ReconSellerEntity> sellers = sellerRepository.findAll().stream()
                .collect(Collectors.toMap(s -> s.getName().trim(), s -> s, (a, b) -> a));

        List<ReconCaseEntity> created = new ArrayList<>();
        int excluded = 0;
        for (OnlineSaleEntity sale : sales) {
            if (existing.contains(sale.getId())) continue;
            boolean exclude = EXCLUDED_SALE_STATUS.contains(sale.getStatus());
            ReconSellerEntity seller = sale.getSalesperson() == null ? null : sellers.get(sale.getSalesperson().trim());
            ReconCaseEntity c = ReconCaseEntity.builder()
                    .onlineSaleId(sale.getId())
                    .origin(sale.getDispatchedAt() != null ? Recon.Origin.NORMAL : Recon.Origin.HISTORICO)
                    .saleDate(sale.getSaleDate())
                    .saleNumber(sale.getSaleNumber())
                    .shipmentNumber(sale.getShipmentNumber())
                    .guideNumber(sale.getGuideNumber())
                    .carrier(sale.getShippingCarrier())
                    .paymentMethod(sale.getPaymentMethod())
                    .salesperson(sale.getSalesperson())
                    .sellerId(seller == null ? null : seller.getId())
                    .expectedAmount(money(sale.getTotalAmount()))
                    .dispatchedAt(sale.getDispatchedAt())
                    .sourceUpdatedAt(sale.getUpdatedAt())
                    .excludedReason(exclude ? "Venta en estado " + sale.getStatus() : null)
                    .status(exclude ? Recon.Status.EXCLUIDO : Recon.Status.PENDIENTE_REVISION)
                    .createdBy(actor.id())
                    .build();
            created.add(c);
            if (exclude) excluded++;
        }
        caseRepository.saveAll(created);
        caseRepository.flush();
        for (ReconCaseEntity c : created) {
            if (c.getExcludedReason() == null) ledger.postInitialCharge(c);
            events.record(c.getId(), null, "CASO_CREADO", null, c.getStatus(),
                    Map.of("origen", c.getOrigin().name(), "cobrado", money(c.getExpectedAmount()).toPlainString()));
        }
        recompute.recomputeAll(created, false);
        return new GenerateResult(created.size(), existing.size(), excluded, sales.size());
    }

    @Transactional
    public ReconCaseEntity revalidate(Long id) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        recompute.recompute(c);
        return c;
    }

    /** Revalida todos los casos abiertos de un período (por ejemplo tras importar guías). */
    @Transactional
    public int revalidatePeriod(LocalDate from, LocalDate to) {
        List<ReconCaseEntity> cases = caseRepository.findBySaleDateBetween(from, to).stream().filter(c -> !c.isClosed()).toList();
        recompute.recomputeAll(cases);
        return cases.size();
    }

    /** Comisión fija de Forza y Guatex. */
    public static final java.math.BigDecimal FIXED_COMMISSION_RATE = new java.math.BigDecimal("0.035");
    /** Porcentajes que se pueden elegir para las demás ventas. */
    public static final List<java.math.BigDecimal> COMMISSION_CHOICES = List.of(new java.math.BigDecimal("0.03"), new java.math.BigDecimal("0.035"), new java.math.BigDecimal("0.04"));

    /**
     * Guarda el envío real y el porcentaje de comisión de una venta. Es solo un registro: no cambia lo esperado, lo recibido ni la diferencia.
     * Forza y Guatex llevan 3.5% fijo (no se elige); en las demás se elige 3, 3.5 o 4%. Una venta en un lote cerrado ya no se toca.
     */
    @Transactional
    public ReconCaseEntity updateShippingCommission(Long id, java.math.BigDecimal realShipping, java.math.BigDecimal rate) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + id));
        if (c.isClosed() || c.getBatchId() != null) throw new BusinessException("La venta está en un lote cerrado y no se puede modificar.");
        if (c.getExcludedReason() != null) throw new BusinessException("La venta está excluida de la conciliación.");
        if (realShipping != null) {
            if (realShipping.signum() < 0) throw new BusinessException("El envío real no puede ser negativo.");
            if (realShipping.stripTrailingZeros().scale() > 2) throw new BusinessException("El envío real no puede tener más de 2 decimales.");
        }
        java.math.BigDecimal stored = null;
        if (rate != null) {
            if (isCarrierWithGuide(c.getCarrier())) {
                if (rate.compareTo(FIXED_COMMISSION_RATE) != 0) throw new BusinessException("Forza y Guatex llevan 3.5% fijo: no se cambia.");
            } else if (COMMISSION_CHOICES.stream().noneMatch(r -> r.compareTo(rate) == 0)) {
                throw new BusinessException("Elija 3%, 3.5% o 4% de comisión.");
            } else {
                stored = rate.setScale(4, java.math.RoundingMode.HALF_UP);
            }
        }
        c.setRealShipping(realShipping == null ? null : money(realShipping));
        c.setCarrierCommissionRate(stored);
        c.setUpdatedBy(actor.id());
        c.setUpdatedAt(java.time.LocalDateTime.now());
        caseRepository.save(c);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("envioReal", c.getRealShipping() == null ? null : c.getRealShipping().toPlainString());
        payload.put("comision", rate == null ? null : rate.multiply(new java.math.BigDecimal(100)).stripTrailingZeros().toPlainString() + "%");
        events.record(c.getId(), null, "ENVIO_COMISION", c.getStatus(), c.getStatus(), payload);
        return c;
    }

    /** Concilia a mano un caso que solo tiene advertencias. Un caso con diferencias o fallas críticas no se puede forzar. */
    @Transactional
    public ReconCaseEntity reconcile(Long id, String observation) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        recompute.recompute(c);
        if (c.getStatus() != Recon.Status.PENDIENTE_REVISION) {
            throw new BusinessException("Solo se puede conciliar a mano un caso «Pendiente de revisión» (solo advertencias). Estado actual: "
                    + c.getStatus() + ".");
        }
        Recon.Status from = c.getStatus();
        c.setManualReconciled(true);
        c.setUpdatedBy(actor.id());
        recompute.recompute(c);
        events.record(c.getId(), null, "CONCILIADO_MANUAL", from, c.getStatus(), observationPayload(observation));
        return c;
    }

    /** Concilia en bloque solo lo seguro: casos «Pendiente de revisión» sin diferencia de monto. El resto se informa con su motivo. */
    @Transactional
    public BulkResult bulkReconcile(List<Long> ids, String observation) {
        int done = 0;
        List<InvalidCaseRef> skipped = new ArrayList<>();
        for (Long id : ids == null ? List.<Long>of() : ids) {
            ReconCaseEntity c = caseRepository.findById(id).orElse(null);
            if (c == null) {
                skipped.add(new InvalidCaseRef(id, null, "No existe"));
            } else if (c.isClosed()) {
                skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), "Está en un lote cerrado"));
            } else if (c.getStatus() != Recon.Status.PENDIENTE_REVISION) {
                skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), "Estado " + c.getStatus() + ": no es seguro conciliarlo en bloque"));
            } else if (c.getDifference().signum() != 0) {
                skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), "Tiene diferencia de monto"));
            } else {
                try {
                    reconcile(id, observation);
                    done++;
                } catch (BusinessException | ResourceNotFoundException e) {
                    skipped.add(new InvalidCaseRef(id, c.getShipmentNumber(), e.getMessage()));
                }
            }
        }
        return new BulkResult(done, skipped);
    }

    /** Reabre un caso conciliado para revisarlo de nuevo. Un caso en lote cerrado solo se reabre anulando el lote. */
    @Transactional
    public ReconCaseEntity reopen(Long id, String reason) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        requireReason(reason, 5, "Escriba el motivo para reabrir el caso.");
        if (!c.getStatus().isReconciled()) {
            throw new BusinessException("Solo se reabre un caso conciliado. Estado actual: " + c.getStatus() + ".");
        }
        Recon.Status from = c.getStatus();
        c.setManualReconciled(false);
        if (from == Recon.Status.CONCILIADO_CON_AJUSTE) {
            differenceRepository.findFirstByCaseIdAndStatusOrderByIdDesc(c.getId(), Recon.DifferenceStatus.AUTHORIZED).ifPresent(d -> {
                d.setStatus(Recon.DifferenceStatus.REJECTED);
                differenceRepository.save(d);
            });
        }
        recompute.recompute(c);
        events.record(c.getId(), null, "REABIERTO", from, c.getStatus(), Map.of("motivo", reason.trim()));
        return c;
    }

    @Transactional
    public ReconCaseEntity exclude(Long id, String reason) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        requireReason(reason, 10, "Escriba el motivo de la exclusión (mínimo 10 caracteres).");
        if (!applicationRepository.findByCaseIdAndStatus(c.getId(), Recon.RowStatus.ACTIVE).isEmpty()) {
            throw new BusinessException("El caso tiene fondos aplicados. Anule primero esas aplicaciones.");
        }
        Recon.Status from = c.getStatus();
        c.setExcludedReason(reason.trim());
        ledger.reverseCharges(c, "Caso excluido: " + reason.trim());
        recompute.recompute(c);
        events.record(c.getId(), null, "EXCLUIDO", from, c.getStatus(), Map.of("motivo", reason.trim()));
        return c;
    }

    @Transactional
    public ReconCaseEntity include(Long id) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        if (c.getExcludedReason() == null) {
            throw new BusinessException("El caso no está excluido.");
        }
        Recon.Status from = c.getStatus();
        c.setExcludedReason(null);
        recompute.recompute(c);
        if (ledger.balance(c.getId()).signum() == 0) ledger.postCharge(c);
        events.record(c.getId(), null, "REINCORPORADO", from, c.getStatus(), null);
        return c;
    }

    @Transactional
    public void observe(Long id, String text) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + id));
        requireReason(text, 3, "Escriba la observación.");
        events.record(c.getId(), null, "OBSERVACION", c.getStatus(), c.getStatus(), Map.of("texto", text.trim()));
    }

    /**
     * Toma el monto cobrado actual de la venta cuando cambió después de crear el caso. Es explícito porque mueve
     * el libro: se revierte el cargo anterior y se registra uno nuevo; nada se edita en silencio.
     */
    @Transactional
    public ReconCaseEntity refreshAmountFromSale(Long id, String reason) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = openCase(id);
        requireReason(reason, 10, "Escriba el motivo (mínimo 10 caracteres).");
        OnlineSaleEntity sale = saleRepository.findById(c.getOnlineSaleId())
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada: " + c.getOnlineSaleId()));
        if (sameMoney(sale.getTotalAmount(), c.getExpectedAmount())) {
            throw new BusinessException("El monto de la venta no cambió: sigue siendo " + q(c.getExpectedAmount()) + ".");
        }
        boolean activeAdjustment = differenceRepository.findFirstByCaseIdAndStatusOrderByIdDesc(c.getId(), Recon.DifferenceStatus.PROPOSED).isPresent()
                || differenceRepository.findFirstByCaseIdAndStatusOrderByIdDesc(c.getId(), Recon.DifferenceStatus.AUTHORIZED).isPresent();
        if (activeAdjustment) {
            throw new BusinessException("El caso tiene un ajuste propuesto o autorizado. Recházelo o reabra el caso antes de cambiar el monto.");
        }
        var before = c.getExpectedAmount();
        ledger.reverseCharges(c, reason.trim());
        c.setExpectedAmount(money(sale.getTotalAmount()));
        caseRepository.saveAndFlush(c);
        ledger.postCharge(c);
        recompute.recompute(c);
        events.record(c.getId(), null, "MONTO_ACTUALIZADO", c.getStatus(), c.getStatus(),
                Map.of("antes", money(before).toPlainString(), "ahora", c.getExpectedAmount().toPlainString(), "motivo", reason.trim()));
        return c;
    }

    // ───────── apoyo ─────────

    private ReconCaseEntity openCase(Long id) throws ResourceNotFoundException, BusinessException {
        ReconCaseEntity c = caseRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Caso no encontrado: " + id));
        if (c.isClosed()) {
            throw new BusinessException("El caso está en un lote cerrado y no se puede modificar. Solo se corrige anulando el lote completo.");
        }
        return c;
    }

    private static void requireReason(String reason, int min, String message) throws BusinessException {
        if (reason == null || reason.trim().length() < min) throw new BusinessException(message);
    }

    private static Map<String, Object> observationPayload(String observation) {
        return blank(observation) ? Map.of() : Map.of("observacion", observation.trim());
    }
}
