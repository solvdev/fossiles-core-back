package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationMatcher.Hit;
import com.fossiles.fossilescorebackend.application.service.reconciliation.ReconciliationMatcher.Kind;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationMatcherTest {

    private static Hit hit(long id) {
        return new Hit(id, false, false);
    }

    @Test
    void guiaExactaYUnica_seAplicaSola() {
        var out = ReconciliationMatcher.decide(List.of(hit(7)), List.of(), List.of());

        assertThat(out.kind()).isEqualTo(Kind.EXACT);
        assertThat(out.caseId()).isEqualTo(7L);
        assertThat(out.method()).isEqualTo(Recon.MatchMethod.GUIDE);
    }

    @Test
    void referenciaYEnvlSinGuia_usanElMetodoMasFuerte() {
        assertThat(ReconciliationMatcher.decide(List.of(), List.of(hit(3)), List.of(hit(3))).method()).isEqualTo(Recon.MatchMethod.REFERENCE);
        assertThat(ReconciliationMatcher.decide(List.of(), List.of(), List.of(hit(3))).method()).isEqualTo(Recon.MatchMethod.ENVL);
    }

    @Test
    void identificadoresQueCoincidenEnElMismoCaso_siguenSiendoExactos() {
        var out = ReconciliationMatcher.decide(List.of(hit(5)), List.of(hit(5)), List.of(hit(5)));

        assertThat(out.kind()).isEqualTo(Kind.EXACT);
        assertThat(out.method()).isEqualTo(Recon.MatchMethod.GUIDE);
    }

    @Test
    void masDeUnCaso_esAmbiguo_yNoSeAplica() {
        var out = ReconciliationMatcher.decide(List.of(hit(1), hit(2)), List.of(), List.of());

        assertThat(out.kind()).isEqualTo(Kind.AMBIGUOUS);
        assertThat(out.caseId()).isNull();
        assertThat(out.candidateCaseIds()).containsExactly(1L, 2L);
    }

    @Test
    void identificadoresQueApuntanACasosDistintos_sonAmbiguos() {
        var out = ReconciliationMatcher.decide(List.of(hit(1)), List.of(hit(2)), List.of());

        assertThat(out.kind()).isEqualTo(Kind.AMBIGUOUS);
    }

    @Test
    void sinCoincidencia_noHayCaso() {
        assertThat(ReconciliationMatcher.decide(List.of(), List.of(), List.of()).kind()).isEqualTo(Kind.NONE);
    }

    @Test
    void casoExcluidoOEnLoteCerrado_noRecibeFondos() {
        assertThat(ReconciliationMatcher.decide(List.of(new Hit(1, true, false)), List.of(), List.of()).kind()).isEqualTo(Kind.BLOCKED);
        var closed = ReconciliationMatcher.decide(List.of(new Hit(1, false, true)), List.of(), List.of());
        assertThat(closed.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(closed.reason()).contains("lote cerrado");
    }
}
