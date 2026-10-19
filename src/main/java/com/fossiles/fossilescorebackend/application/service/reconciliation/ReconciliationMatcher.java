package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decide si un fondo se puede aplicar solo a un caso. Solo por identificador exacto (guía, referencia o
 * ENVL) y solo si apunta a UN caso. Monto y fecha parecidos nunca bastan: eso va a revisión humana.
 */
public final class ReconciliationMatcher {

    private ReconciliationMatcher() {
    }

    /** Un caso encontrado por un identificador. */
    public record Hit(long caseId, boolean excluded, boolean closed) {
    }

    public enum Kind {
        /** Un único caso por identificador exacto: se puede aplicar solo. */
        EXACT,
        /** Más de un caso, o identificadores que apuntan a casos distintos: revisión manual. */
        AMBIGUOUS,
        /** El identificador existe pero el caso está excluido o en un lote cerrado: no se aplica. */
        BLOCKED,
        /** Ningún identificador coincide. */
        NONE
    }

    public record Outcome(Kind kind, Long caseId, Recon.MatchMethod method, String reason, Set<Long> candidateCaseIds) {
        static Outcome none() {
            return new Outcome(Kind.NONE, null, null, "Ningún identificador coincide con una venta.", Set.of());
        }
    }

    public static Outcome decide(List<Hit> byGuide, List<Hit> byReference, List<Hit> byEnvl) {
        Set<Long> all = new LinkedHashSet<>();
        for (List<Hit> tier : List.of(byGuide, byReference, byEnvl)) {
            for (Hit h : tier) all.add(h.caseId());
        }
        if (all.isEmpty()) return Outcome.none();
        if (all.size() > 1) {
            return new Outcome(Kind.AMBIGUOUS, null, null,
                    "Los identificadores apuntan a " + all.size() + " ventas distintas. Elija cuál corresponde.", all);
        }
        long caseId = all.iterator().next();
        Recon.MatchMethod method = !byGuide.isEmpty() ? Recon.MatchMethod.GUIDE
                : !byReference.isEmpty() ? Recon.MatchMethod.REFERENCE : Recon.MatchMethod.ENVL;
        Hit hit = (!byGuide.isEmpty() ? byGuide : !byReference.isEmpty() ? byReference : byEnvl).get(0);
        if (hit.excluded()) {
            return new Outcome(Kind.BLOCKED, caseId, method, "La venta está excluida de la conciliación.", all);
        }
        if (hit.closed()) {
            return new Outcome(Kind.BLOCKED, caseId, method, "La venta ya está en un lote cerrado.", all);
        }
        return new Outcome(Kind.EXACT, caseId, method, "Coincidencia exacta única.", all);
    }
}
