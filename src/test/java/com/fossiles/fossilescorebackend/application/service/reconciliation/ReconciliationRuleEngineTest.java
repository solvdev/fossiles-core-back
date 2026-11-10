package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.service.reconciliation.CaseFacts.ReceiptFact;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationRuleEngineTest {

    private final ReconciliationRuleEngine engine = new ReconciliationRuleEngine();

    private static BigDecimal q(String v) {
        return new BigDecimal(v);
    }

    /** Venta contra entrega de Forza: productos Q450 + envío Q30 = Q480, con guía, sin fondos todavía. */
    private static CaseFactsBuilder base() {
        return new CaseFactsBuilder();
    }

    private static ReceiptFact carrierReceipt(String amount) {
        return new ReceiptFact(1L, Recon.SourceType.CARRIER, "Forza Delivery", "58123456789", "LIQ-FZ-312", null,
                q(amount), q("30.00"), LocalDate.of(2026, 10, 12));
    }

    private RuleResult rule(List<RuleResult> results, String code) {
        return results.stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    private Status statusOf(CaseFacts f, boolean manual, Optional<Recon.DifferenceStatus> adj, boolean covers) {
        List<RuleResult> results = engine.evaluate(f);
        return ReconciliationStatusResolver.resolve(results, false, manual, f.expectedAmount(), f.totalApplied(),
                !f.receipts().isEmpty(), adj, covers);
    }

    private Status statusOf(CaseFacts f) {
        return statusOf(f, false, Optional.empty(), false);
    }

    @Test
    void cobradoIgualARecibido_conTodoCorrecto_concilaAutomaticamente() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("480.00"))).build();

        List<RuleResult> results = engine.evaluate(f);

        assertThat(rule(results, "R06").outcome()).isEqualTo(Recon.Outcome.PASS);
        assertThat(results).noneMatch(RuleResult::failed);
        assertThat(statusOf(f)).isEqualTo(Status.CONCILIADO_AUTO);
    }

    @Test
    void cobradoQ100_recibidoQ95_diferenciaDeQ5_noConcilia() {
        CaseFacts f = base().expected("100.00").net("70.00").shipping("30.00").receipts(List.of(carrierReceipt("95.00"))).build();

        RuleResult r06 = rule(engine.evaluate(f), "R06");

        assertThat(r06.outcome()).isEqualTo(Recon.Outcome.FAIL);
        assertThat(r06.severity()).isEqualTo(Recon.Severity.CRITICAL);
        assertThat(r06.message()).contains("-Q5.00").contains("falta dinero");
        assertThat(statusOf(f)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void unCentavoDeDiferencia_sigueSiendoDiferencia_sinTolerancia() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("480.01"))).build();

        assertThat(rule(engine.evaluate(f), "R06").outcome()).isEqualTo(Recon.Outcome.FAIL);
        assertThat(statusOf(f)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void sinFondoRecibido_esInformacionIncompleta() {
        CaseFacts f = base().build();

        assertThat(rule(engine.evaluate(f), "R06").found()).isEqualTo("Sin fondo recibido");
        assertThat(statusOf(f)).isEqualTo(Status.INFORMACION_INCOMPLETA);
    }

    @Test
    void guiaFaltante_esCriticaEnVentasNuevas_yAdvertenciaEnHistoricas() {
        CaseFacts nueva = base().guide(null).origin(Recon.Origin.NORMAL).receipts(List.of(carrierReceipt("480.00"))).build();
        CaseFacts historica = base().guide(null).origin(Recon.Origin.HISTORICO).receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(nueva), "R03").severity()).isEqualTo(Recon.Severity.CRITICAL);
        assertThat(rule(engine.evaluate(historica), "R03").severity()).isEqualTo(Recon.Severity.WARNING);
        assertThat(statusOf(nueva)).isEqualTo(Status.INFORMACION_INCOMPLETA);
        assertThat(statusOf(historica)).isEqualTo(Status.PENDIENTE_REVISION);
    }

    @Test
    void motoristaNoNecesitaGuia() {
        CaseFacts f = base().carrier("MOTORISTA").guide(null).receipts(List.of(
                new ReceiptFact(1L, Recon.SourceType.CASH, null, null, "EFE-1012", null, q("480.00"), null, LocalDate.of(2026, 10, 12)))).build();

        assertThat(rule(engine.evaluate(f), "R03").outcome()).isEqualTo(Recon.Outcome.NA);
        assertThat(statusOf(f)).isEqualTo(Status.CONCILIADO_AUTO);
    }

    @Test
    void guiasDistintas_esCritico() {
        CaseFacts f = base().guide("111").receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(f), "R05").criticalFailure()).isTrue();
    }

    @Test
    void depositoPagadoConFondoDeTransportista_esCritico() {
        CaseFacts f = base().payment("DEPOSITO_LISTO").receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(f), "R07").criticalFailure()).isTrue();
    }

    @Test
    void referenciaDeDeposito_faltanteEnVenta_advierte_ydistintaEsCritica() {
        ReceiptFact bank = new ReceiptFact(1L, Recon.SourceType.BANK, null, null, "DEP-123456", null, q("480.00"), null, LocalDate.of(2026, 10, 12));
        CaseFacts sinRef = base().payment("DEPOSITO_LISTO").auth(null).receipts(List.of(bank)).build();
        CaseFacts distinta = base().payment("DEPOSITO_LISTO").auth("DEP-999999").receipts(List.of(bank)).build();
        CaseFacts igual = base().payment("DEPOSITO_LISTO").auth("dep 123456").receipts(List.of(bank)).build();

        assertThat(rule(engine.evaluate(sinRef), "R08").severity()).isEqualTo(Recon.Severity.WARNING);
        assertThat(rule(engine.evaluate(sinRef), "R08").failed()).isTrue();
        assertThat(rule(engine.evaluate(distinta), "R08").criticalFailure()).isTrue();
        assertThat(rule(engine.evaluate(igual), "R08").outcome()).isEqualTo(Recon.Outcome.PASS);
    }

    @Test
    void fondoAnteriorALaVenta_esAdvertencia() {
        ReceiptFact early = new ReceiptFact(1L, Recon.SourceType.CARRIER, "Forza", "58123456789", "LIQ", null, q("480.00"), q("30.00"), LocalDate.of(2026, 10, 1));
        CaseFacts f = base().receipts(List.of(early)).build();

        RuleResult r09 = rule(engine.evaluate(f), "R09");
        assertThat(r09.failed()).isTrue();
        assertThat(r09.severity()).isEqualTo(Recon.Severity.WARNING);
        assertThat(statusOf(f)).isEqualTo(Status.PENDIENTE_REVISION);
    }

    @Test
    void mismaGuiaEnOtraVenta_esPosibleDuplicado() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("480.00"))).sameGuide(List.of("ENVL-01300")).build();

        assertThat(rule(engine.evaluate(f), "R10").criticalFailure()).isTrue();
        assertThat(statusOf(f)).isEqualTo(Status.POSIBLE_DUPLICADO);
    }

    @Test
    void envioDeLaFuenteDistinto_advierteYExplicaLaDiferencia() {
        ReceiptFact r = new ReceiptFact(1L, Recon.SourceType.CARRIER, "Forza", "58123456789", "LIQ", null, q("475.00"), q("25.00"), LocalDate.of(2026, 10, 12));
        CaseFacts f = base().receipts(List.of(r)).build();

        List<RuleResult> results = engine.evaluate(f);
        assertThat(rule(results, "R12").failed()).isTrue();
        assertThat(rule(results, "R06").failed()).isTrue();
    }

    @Test
    void comision_esDosPorCientoDelNetoSinEnvio() {
        CaseFacts ok = base().registeredCommission("9.00").receipts(List.of(carrierReceipt("480.00"))).build();
        CaseFacts mal = base().registeredCommission("10.00").receipts(List.of(carrierReceipt("480.00"))).build();
        CaseFacts sinCatalogo = base().sellerInCatalog(false).receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(ok), "R13").outcome()).isEqualTo(Recon.Outcome.PASS);
        assertThat(rule(engine.evaluate(ok), "R13").expected()).isEqualTo("Q9.00");
        assertThat(rule(engine.evaluate(mal), "R13").failed()).isTrue();
        assertThat(rule(engine.evaluate(sinCatalogo), "R13").message()).contains("catálogo");
    }

    @Test
    void comision_enDevolucion_esCero() {
        CaseFacts f = base().saleStatus("DEVOLUCION").registeredCommission("0.00").build();

        assertThat(rule(engine.evaluate(f), "R13").expected()).isEqualTo("Q0.00");
        assertThat(rule(engine.evaluate(f), "R13").outcome()).isEqualTo(Recon.Outcome.PASS);
    }

    @Test
    void ventaDevueltaConFondos_esCritico() {
        CaseFacts f = base().saleStatus("DEVOLUCION").receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(f), "R15").criticalFailure()).isTrue();
    }

    @Test
    void cambioConTotalQ0_sinFondos_noTieneNadaQueCobrar_yConFondosAvisaDelEnvio() {
        CaseFacts sinFondos = base().expected("0.00").net("0.00").shipping("0.00").build();
        CaseFacts conFondos = base().expected("0.00").net("0.00").shipping("0.00").receipts(List.of(carrierReceipt("30.00"))).build();

        assertThat(statusOf(sinFondos)).isEqualTo(Status.CONCILIADO_AUTO);
        RuleResult r17 = rule(engine.evaluate(conFondos), "R17");
        assertThat(r17.criticalFailure()).isTrue();
        assertThat(r17.message()).contains("cobro de envío no registrado");
        assertThat(statusOf(conFondos)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void sobrepago_esCriticoYNoSePuedeAjustarComoFaltante() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("500.00"))).build();

        assertThat(rule(engine.evaluate(f), "R16").criticalFailure()).isTrue();
        assertThat(statusOf(f)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void ventaModificadaDespuesDeCrearElCaso_advierte() {
        CaseFacts f = base().currentSaleTotal("500.00").receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(f), "R11").failed()).isTrue();
    }

    @Test
    void facturaConOtroTotal_advierte_ysinFacturaNoAplica() {
        CaseFacts mala = base().invoiceTotal("450.00").receipts(List.of(carrierReceipt("480.00"))).build();
        CaseFacts sin = base().receipts(List.of(carrierReceipt("480.00"))).build();

        assertThat(rule(engine.evaluate(mala), "R14").failed()).isTrue();
        assertThat(rule(engine.evaluate(sin), "R14").outcome()).isEqualTo(Recon.Outcome.NA);
    }

    @Test
    void ajusteAutorizado_soloResuelveElMonto_ySoloSiCubreExactamenteElFaltante() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("475.00"))).build();

        assertThat(statusOf(f, false, Optional.of(Recon.DifferenceStatus.PROPOSED), true)).isEqualTo(Status.REQUIERE_AUTORIZACION);
        assertThat(statusOf(f, false, Optional.of(Recon.DifferenceStatus.AUTHORIZED), true)).isEqualTo(Status.CONCILIADO_CON_AJUSTE);
        // el ajuste ya no cubre el faltante (cambió el fondo): la diferencia reaparece
        assertThat(statusOf(f, false, Optional.of(Recon.DifferenceStatus.AUTHORIZED), false)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void ajusteAutorizado_noOcultaOtraFallaCritica() {
        CaseFacts f = base().guide("111").receipts(List.of(carrierReceipt("475.00"))).build();

        assertThat(statusOf(f, false, Optional.of(Recon.DifferenceStatus.AUTHORIZED), true)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void conciliarAManual_soloAplicaConAdvertencias() {
        CaseFacts conAdvertencia = base().registeredCommission("10.00").receipts(List.of(carrierReceipt("480.00"))).build();
        CaseFacts conDiferencia = base().receipts(List.of(carrierReceipt("470.00"))).build();

        assertThat(statusOf(conAdvertencia, false, Optional.empty(), false)).isEqualTo(Status.PENDIENTE_REVISION);
        assertThat(statusOf(conAdvertencia, true, Optional.empty(), false)).isEqualTo(Status.CONCILIADO_MANUAL);
        assertThat(statusOf(conDiferencia, true, Optional.empty(), false)).isEqualTo(Status.CON_DIFERENCIAS);
    }

    @Test
    void problemaPrincipal_prioriza_autorizacion_duplicado_y_monto() {
        CaseFacts f = base().receipts(List.of(carrierReceipt("470.00"))).build();
        List<RuleResult> results = engine.evaluate(f);

        assertThat(ReconciliationStatusResolver.primaryProblem(Status.CON_DIFERENCIAS, results, true, f.expectedAmount(), 3))
                .contains(Recon.DifferenceType.MONTO_INCORRECTO);
        assertThat(ReconciliationStatusResolver.primaryProblem(Status.REQUIERE_AUTORIZACION, results, true, f.expectedAmount(), 3))
                .contains(Recon.DifferenceType.PENDIENTE_AUTORIZACION);
        assertThat(ReconciliationStatusResolver.primaryProblem(Status.CONCILIADO_AUTO, results, true, f.expectedAmount(), 3)).isEmpty();
    }

    @Test
    void ventaSinFondo_soloEsProblema_despuesDeCincoDias() {
        CaseFacts f = base().build();
        List<RuleResult> results = engine.evaluate(f);

        assertThat(ReconciliationStatusResolver.primaryProblem(Status.INFORMACION_INCOMPLETA, results, false, f.expectedAmount(), 3)).isEmpty();
        assertThat(ReconciliationStatusResolver.primaryProblem(Status.INFORMACION_INCOMPLETA, results, false, f.expectedAmount(), 9))
                .contains(Recon.DifferenceType.VENTA_SIN_ENVIO);
    }
}
