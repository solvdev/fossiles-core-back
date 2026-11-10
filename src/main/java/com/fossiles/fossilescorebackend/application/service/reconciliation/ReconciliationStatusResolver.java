package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon.Status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Decide el estado de un caso a partir de los resultados de las reglas. El estado nunca hace desaparecer una
 * diferencia: solo un ajuste autorizado que cubra exactamente el faltante, o corregir el dato, la resuelve.
 */
public final class ReconciliationStatusResolver {

    private ReconciliationStatusResolver() {
    }

    /**
     * @param adjustment     estado del ajuste más reciente del caso, si existe
     * @param adjustmentCovers true si el ajuste autorizado cubre exactamente el faltante actual
     */
    public static Status resolve(List<RuleResult> results, boolean excluded, boolean manual, BigDecimal expected,
                                 BigDecimal received, boolean hasReceipts, Optional<Recon.DifferenceStatus> adjustment,
                                 boolean adjustmentCovers) {
        if (excluded) return Status.EXCLUIDO;
        if (adjustment.orElse(null) == Recon.DifferenceStatus.PROPOSED) return Status.REQUIERE_AUTORIZACION;

        boolean duplicate = results.stream().anyMatch(r -> r.failed() && r.code().equals("R10"));
        if (duplicate) return Status.POSIBLE_DUPLICADO;

        boolean amountMismatch = hasReceipts && !ReconText.sameMoney(expected, received);
        List<RuleResult> critical = results.stream().filter(RuleResult::criticalFailure).toList();

        if (adjustment.orElse(null) == Recon.DifferenceStatus.AUTHORIZED && adjustmentCovers) {
            // El ajuste solo resuelve la regla del monto; cualquier otra falla crítica sigue abierta.
            boolean otherCritical = critical.stream().anyMatch(r -> !r.code().equals("R06") && !r.code().equals("R12"));
            return otherCritical ? Status.CON_DIFERENCIAS : Status.CONCILIADO_CON_AJUSTE;
        }
        if (amountMismatch) return Status.CON_DIFERENCIAS;

        boolean noMoneyYet = !hasReceipts && ReconText.money(expected).signum() > 0;
        boolean guideMissingCritical = results.stream().anyMatch(r -> r.criticalFailure() && r.code().equals("R03"));
        if (noMoneyYet || guideMissingCritical) {
            boolean otherCritical = critical.stream().anyMatch(r -> !r.code().equals("R06") && !r.code().equals("R03"));
            return otherCritical ? Status.CON_DIFERENCIAS : Status.INFORMACION_INCOMPLETA;
        }
        if (!critical.isEmpty()) return Status.CON_DIFERENCIAS;

        boolean warnings = results.stream().anyMatch(r -> r.failed() && r.severity() == Recon.Severity.WARNING);
        if (warnings) return manual ? Status.CONCILIADO_MANUAL : Status.PENDIENTE_REVISION;
        return manual ? Status.CONCILIADO_MANUAL : Status.CONCILIADO_AUTO;
    }

    /** Tipo de problema principal de un caso abierto, para la pantalla de diferencias. Null si no hay problema. */
    public static Optional<Recon.DifferenceType> primaryProblem(Status status, List<RuleResult> results, boolean hasReceipts,
                                                                BigDecimal expected, long daysSinceSale) {
        if (status == Status.EXCLUIDO || status.isReconciled()) return Optional.empty();
        if (status == Status.REQUIERE_AUTORIZACION) return Optional.of(Recon.DifferenceType.PENDIENTE_AUTORIZACION);
        if (failed(results, "R10")) return Optional.of(Recon.DifferenceType.PAGO_DUPLICADO);
        if (failed(results, "R06") && hasReceipts) return Optional.of(Recon.DifferenceType.MONTO_INCORRECTO);
        if (!hasReceipts && ReconText.money(expected).signum() > 0) {
            return daysSinceSale > 5 ? Optional.of(Recon.DifferenceType.VENTA_SIN_ENVIO) : Optional.empty();
        }
        if (criticalFailed(results, "R08")) return Optional.of(Recon.DifferenceType.REFERENCIA_NO_ENCONTRADA);
        if (failed(results, "R03")) return Optional.of(Recon.DifferenceType.DATOS_INSUFICIENTES);
        if (failed(results, "R09")) return Optional.of(Recon.DifferenceType.FECHA_INCONSISTENTE);
        if (failed(results, "R13")) return Optional.of(Recon.DifferenceType.COMISION_INCORRECTA);
        if (failed(results, "R08") || failed(results, "R11") || failed(results, "R12")) return Optional.of(Recon.DifferenceType.DATOS_INSUFICIENTES);
        return Optional.empty();
    }

    private static boolean failed(List<RuleResult> results, String code) {
        return results.stream().anyMatch(r -> r.failed() && r.code().equals(code));
    }

    private static boolean criticalFailed(List<RuleResult> results, String code) {
        return results.stream().anyMatch(r -> r.criticalFailure() && r.code().equals(code));
    }
}
