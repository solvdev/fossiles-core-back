package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.service.reconciliation.CaseFacts.ReceiptFact;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon.Outcome;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon.Severity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.fossiles.fossilescorebackend.application.service.reconciliation.ReconText.*;

/**
 * Reglas de validación R01 a R17 del plan de conciliación. Funciones puras: reciben los hechos del caso y
 * devuelven un resultado por regla. La regla principal es R06: lo cobrado debe ser igual a lo recibido, al centavo.
 */
@Component
public class ReconciliationRuleEngine {

    private static final Set<String> CONCILIABLE_SALE_STATUS = Set.of("ENVIADO", "ENTREGADO");
    private static final Set<String> CLOSED_SALE_STATUS = Set.of("DEVOLUCION", "ANULADA", "CANCELADO");

    public List<RuleResult> evaluate(CaseFacts f) {
        List<RuleResult> out = new ArrayList<>();
        BigDecimal applied = f.totalApplied();
        boolean hasReceipts = !f.receipts().isEmpty();

        out.add(r01SaleExists(f));
        out.add(r02Shipment(f));
        out.add(r03Guide(f));
        out.add(r04Carrier(f));
        out.add(r05GuideAndReference(f));
        out.add(r06AmountEqual(f, applied, hasReceipts));
        out.add(r07SourceMatchesPayment(f));
        out.add(r08Reference(f));
        out.add(r09Dates(f));
        out.add(r10Duplicates(f));
        out.add(r11Integrity(f));
        out.add(r12Shipping(f));
        out.add(r13Commission(f));
        out.add(r14Invoice(f));
        out.add(r15ReturnWithFunds(f, applied));
        out.add(r16NotOverpaid(f, applied, hasReceipts));
        out.add(r17ExchangeShipping(f, applied));
        return out;
    }

    private RuleResult r01SaleExists(CaseFacts f) {
        boolean ok = f.saleStatus() != null && CONCILIABLE_SALE_STATUS.contains(f.saleStatus());
        return new RuleResult("R01", "La venta existe y es conciliable", Severity.CRITICAL, ok ? Outcome.PASS : Outcome.FAIL,
                "ENVIADO o ENTREGADO", String.valueOf(f.saleStatus()),
                ok ? "" : "La venta está en estado " + f.saleStatus() + " y no se concilia en el lote normal.",
                ok ? "" : "Revisar la venta o excluir el caso con motivo");
    }

    private RuleResult r02Shipment(CaseFacts f) {
        boolean ok = !blank(f.shipmentNumber());
        return new RuleResult("R02", "ENVL presente", Severity.CRITICAL, ok ? Outcome.PASS : Outcome.FAIL,
                "Un ENVL por venta", ok ? f.shipmentNumber() : "Sin ENVL",
                ok ? "" : "La venta no tiene número de envío (ENVL).", ok ? "" : "Asignar el ENVL desde Ventas Online");
    }

    private RuleResult r03Guide(CaseFacts f) {
        if (!isCarrierWithGuide(f.carrier())) {
            return new RuleResult("R03", "Guía presente", Severity.CRITICAL, Outcome.NA, "No aplica", "—",
                    "Este transportista no genera guía.", "");
        }
        boolean ok = !blank(f.guideNumber());
        // En ventas históricas la guía se está recuperando, por eso solo advierte; en las nuevas es crítica.
        Severity sev = f.origin() == Recon.Origin.HISTORICO ? Severity.WARNING : Severity.CRITICAL;
        String sourceGuide = f.receipts().stream().map(ReceiptFact::guideNumber).filter(g -> !blank(g)).findFirst().orElse(null);
        String why = ok ? "" : (sourceGuide != null
                ? "La fuente trae la guía " + sourceGuide + " pero la venta no la tiene."
                : "La venta no tiene guía capturada.");
        return new RuleResult("R03", "Guía presente", sev, ok ? Outcome.PASS : Outcome.FAIL,
                "Guía del transportista", ok ? f.guideNumber() : "Sin guía", why,
                ok ? "" : "Capturar la guía en Ventas Online (portal del transportista, por ENVL)");
    }

    private RuleResult r04Carrier(CaseFacts f) {
        for (ReceiptFact r : f.receipts()) {
            if (!blank(r.carrier()) && !blank(f.carrier()) && !carrierKey(r.carrier()).equals(carrierKey(f.carrier()))) {
                return new RuleResult("R04", "El transportista coincide con la fuente", Severity.CRITICAL, Outcome.FAIL,
                        f.carrier(), r.carrier(), "El fondo viene de otro transportista que el de la venta.",
                        "Verificar a qué venta pertenece el fondo");
            }
        }
        return new RuleResult("R04", "El transportista coincide con la fuente", Severity.CRITICAL,
                f.receipts().isEmpty() ? Outcome.NA : Outcome.PASS, String.valueOf(f.carrier()), "—", "", "");
    }

    private RuleResult r05GuideAndReference(CaseFacts f) {
        for (ReceiptFact r : f.receipts()) {
            if (!blank(r.guideNumber()) && !blank(f.guideNumber()) && !norm(r.guideNumber()).equals(norm(f.guideNumber()))) {
                return new RuleResult("R05", "La guía de la fuente coincide con la de la venta", Severity.CRITICAL, Outcome.FAIL,
                        f.guideNumber(), r.guideNumber(), "La guía de la venta y la del fondo son distintas.",
                        "Revisar cuál guía es la correcta");
            }
        }
        return new RuleResult("R05", "La guía de la fuente coincide con la de la venta", Severity.CRITICAL,
                f.receipts().isEmpty() ? Outcome.NA : Outcome.PASS, String.valueOf(f.guideNumber()), "—", "", "");
    }

    /** Regla principal: cobrado = recibido, exacto, sin tolerancia ni distinción por método de pago. */
    private RuleResult r06AmountEqual(CaseFacts f, BigDecimal applied, boolean hasReceipts) {
        BigDecimal expected = money(f.expectedAmount());
        if (!hasReceipts) {
            if (expected.signum() == 0) {
                return new RuleResult("R06", "Cobrado = recibido (exacto)", Severity.CRITICAL, Outcome.PASS,
                        q(expected), "Sin cobro", "", "");
            }
            return new RuleResult("R06", "Cobrado = recibido (exacto)", Severity.CRITICAL, Outcome.FAIL,
                    q(expected), "Sin fondo recibido", "No hay dinero aplicado a esta venta.",
                    "Importar o capturar el fondo, o esperar la liquidación");
        }
        BigDecimal diff = applied.subtract(expected);
        boolean ok = diff.signum() == 0;
        return new RuleResult("R06", "Cobrado = recibido (exacto)", Severity.CRITICAL, ok ? Outcome.PASS : Outcome.FAIL,
                q(expected), q(applied),
                ok ? "" : "Diferencia de " + (diff.signum() < 0 ? "-" : "+") + q(diff.abs())
                        + (diff.signum() < 0 ? " (falta dinero)." : " (sobra dinero).") + " No hay tolerancia.",
                ok ? "" : "Corregir el dato de origen, anular la aplicación equivocada o solicitar un ajuste autorizado");
    }

    private RuleResult r07SourceMatchesPayment(CaseFacts f) {
        if (f.receipts().isEmpty()) {
            return new RuleResult("R07", "La fuente corresponde a la forma de pago", Severity.CRITICAL, Outcome.NA,
                    String.valueOf(f.paymentMethod()), "—", "", "");
        }
        for (ReceiptFact r : f.receipts()) {
            if (!sourceAllowed(f.paymentMethod(), r.sourceType())) {
                return new RuleResult("R07", "La fuente corresponde a la forma de pago", Severity.CRITICAL, Outcome.FAIL,
                        expectedSources(f.paymentMethod()), String.valueOf(r.sourceType()),
                        "Un pago " + f.paymentMethod() + " no debería venir de " + r.sourceType() + ".",
                        "Verificar la forma de pago de la venta o el origen del fondo");
            }
        }
        if (isPendingPayment(f.paymentMethod())) {
            return new RuleResult("R07", "La fuente corresponde a la forma de pago", Severity.WARNING, Outcome.FAIL,
                    "Pago confirmado", f.paymentMethod(), "La venta sigue marcada como pago pendiente aunque ya hay fondo.",
                    "Actualizar la forma de pago en Ventas Online");
        }
        return new RuleResult("R07", "La fuente corresponde a la forma de pago", Severity.CRITICAL, Outcome.PASS,
                expectedSources(f.paymentMethod()), "Correcta", "", "");
    }

    static boolean sourceAllowed(String paymentMethod, Recon.SourceType source) {
        if (source == Recon.SourceType.MANUAL) return true;
        if (isCod(paymentMethod)) return source == Recon.SourceType.CARRIER || source == Recon.SourceType.CASH || source == Recon.SourceType.BANK;
        if (isBankPayment(paymentMethod)) return source == Recon.SourceType.BANK;
        if (isCardPayment(paymentMethod)) return source == Recon.SourceType.CARD;
        return true;
    }

    private static String expectedSources(String paymentMethod) {
        if (isCod(paymentMethod)) return "Transportista, efectivo o banco";
        if (isBankPayment(paymentMethod)) return "Banco";
        if (isCardPayment(paymentMethod)) return "Reporte de tarjeta";
        return "Cualquiera";
    }

    private RuleResult r08Reference(CaseFacts f) {
        String name = "Referencia de pago presente y correcta";
        if (isCod(f.paymentMethod()) || f.receipts().isEmpty()) {
            return new RuleResult("R08", name, Severity.CRITICAL, Outcome.NA, "—", "—",
                    isCod(f.paymentMethod()) ? "No aplica a contra entrega." : "", "");
        }
        List<String> sourceRefs = new ArrayList<>();
        for (ReceiptFact r : f.receipts()) {
            for (String v : new String[]{r.reference(), r.authorizationNumber()}) {
                if (!blank(v)) sourceRefs.add(v.trim());
            }
        }
        if (sourceRefs.isEmpty()) {
            return new RuleResult("R08", name, Severity.WARNING, Outcome.FAIL, "Referencia del fondo", "Sin referencia",
                    "El fondo no trae referencia, no se puede verificar contra la venta.", "Completar la referencia del fondo");
        }
        if (blank(f.paymentAuthorization())) {
            return new RuleResult("R08", name, Severity.WARNING, Outcome.FAIL, sourceRefs.get(0), "Venta sin referencia",
                    "La fuente trae " + sourceRefs.get(0) + " pero la venta no registró la referencia.",
                    "Copiar la referencia a la venta");
        }
        boolean match = sourceRefs.stream().anyMatch(s -> norm(s).equals(norm(f.paymentAuthorization())));
        return new RuleResult("R08", name, Severity.CRITICAL, match ? Outcome.PASS : Outcome.FAIL,
                f.paymentAuthorization(), String.join(", ", sourceRefs),
                match ? "" : "La referencia de la venta no existe en la fuente.",
                match ? "" : "Verificar el comprobante con el cliente");
    }

    private RuleResult r09Dates(CaseFacts f) {
        String name = "Fechas coherentes";
        LocalDate dispatched = f.dispatchedAt() == null ? null : f.dispatchedAt().toLocalDate();
        if (dispatched != null && f.saleDate() != null && dispatched.isBefore(f.saleDate())) {
            return new RuleResult("R09", name, Severity.WARNING, Outcome.FAIL, "Despacho ≥ " + f.saleDate(), dispatched.toString(),
                    "El despacho tiene fecha anterior a la venta.", "Revisar las fechas de la venta");
        }
        for (ReceiptFact r : f.receipts()) {
            LocalDate d = r.receivedDate();
            if (d == null) continue;
            if (f.saleDate() != null && d.isBefore(f.saleDate())) {
                return new RuleResult("R09", name, Severity.WARNING, Outcome.FAIL, "Fondo ≥ " + f.saleDate(), d.toString(),
                        "El fondo tiene fecha anterior a la venta.", "Revisar la fecha del archivo o de la venta");
            }
            if (isCod(f.paymentMethod()) && dispatched != null && d.isBefore(dispatched)) {
                return new RuleResult("R09", name, Severity.WARNING, Outcome.FAIL, "Fondo ≥ despacho " + dispatched, d.toString(),
                        "El fondo contra entrega es anterior al despacho.", "Revisar la fecha del archivo o del despacho");
            }
        }
        return new RuleResult("R09", name, Severity.WARNING, f.receipts().isEmpty() ? Outcome.NA : Outcome.PASS,
                "Fechas en orden", "—", "", "");
    }

    private RuleResult r10Duplicates(CaseFacts f) {
        String name = "Sin duplicados";
        if (!f.otherCasesWithSameGuide().isEmpty()) {
            return new RuleResult("R10", name, Severity.CRITICAL, Outcome.FAIL, "Guía usada una vez",
                    "Guía " + f.guideNumber() + " también en " + String.join(", ", f.otherCasesWithSameGuide()),
                    "La misma guía aparece en más de una venta.", "Revisar cuál venta corresponde y anular la aplicación equivocada");
        }
        if (!f.otherCasesWithSameReference().isEmpty()) {
            return new RuleResult("R10", name, Severity.CRITICAL, Outcome.FAIL, "Referencia usada una vez",
                    "Referencia " + f.paymentAuthorization() + " también en " + String.join(", ", f.otherCasesWithSameReference()),
                    "La misma referencia de pago aparece en más de una venta.", "Verificar si el pago se aplicó dos veces");
        }
        return new RuleResult("R10", name, Severity.CRITICAL, Outcome.PASS, "Sin repetidos", "Sin repetidos", "", "");
    }

    private RuleResult r11Integrity(CaseFacts f) {
        String name = "Integridad: total = neto + envío; neto = líneas";
        if (f.itemsSubtotal() != null && !sameMoney(f.netAmount(), f.itemsSubtotal())) {
            return new RuleResult("R11", name, Severity.WARNING, Outcome.FAIL, q(f.itemsSubtotal()) + " (suma de líneas)",
                    q(f.netAmount()) + " (neto)", "El neto de la venta no coincide con la suma de sus líneas.",
                    "Corregir la venta en Ventas Online");
        }
        if (!sameMoney(money(f.netAmount()).add(money(f.shippingCost())), f.expectedAmount())) {
            return new RuleResult("R11", name, Severity.WARNING, Outcome.FAIL, q(money(f.netAmount()).add(money(f.shippingCost()))),
                    q(f.expectedAmount()), "El total cobrado no es neto + envío.", "Corregir la venta en Ventas Online");
        }
        if (f.currentSaleTotal() != null && !sameMoney(f.currentSaleTotal(), f.expectedAmount())) {
            return new RuleResult("R11", name, Severity.WARNING, Outcome.FAIL, q(f.expectedAmount()) + " (caso)",
                    q(f.currentSaleTotal()) + " (venta hoy)", "La venta cambió después de crear el caso.", "Revalidar el caso");
        }
        return new RuleResult("R11", name, Severity.WARNING, Outcome.PASS, q(f.expectedAmount()), q(f.expectedAmount()), "", "");
    }

    private RuleResult r12Shipping(CaseFacts f) {
        String name = "Envío de la fuente = envío de la venta";
        for (ReceiptFact r : f.receipts()) {
            if (r.shippingAmount() != null && !sameMoney(r.shippingAmount(), f.shippingCost())) {
                return new RuleResult("R12", name, Severity.WARNING, Outcome.FAIL, q(f.shippingCost()), q(r.shippingAmount()),
                        "La liquidación reporta un envío distinto. Explica la diferencia de R06.",
                        "Revisar el envío cobrado por el transportista");
            }
        }
        BigDecimal ship = money(f.shippingCost());
        boolean standard = ship.signum() == 0 || ship.compareTo(new BigDecimal("15.00")) == 0 || ship.compareTo(new BigDecimal("30.00")) == 0;
        if (!standard) {
            return new RuleResult("R12", name, Severity.WARNING, Outcome.FAIL, "Q15.00 o Q30.00", q(ship),
                    "El envío de la venta no es Q30 (contra entrega) ni Q15; fue editado.", "Confirmar que el envío editado es correcto");
        }
        return new RuleResult("R12", name, Severity.WARNING, Outcome.PASS, q(ship), q(ship), "", "");
    }

    private RuleResult r13Commission(CaseFacts f) {
        String name = "Comisión del vendedor calculada y registrada";
        BigDecimal rate = f.commissionRate() == null ? BigDecimal.ZERO : f.commissionRate();
        BigDecimal expected = CLOSED_SALE_STATUS.contains(String.valueOf(f.saleStatus()))
                ? BigDecimal.ZERO.setScale(2) : money(f.netAmount()).multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (!f.sellerInCatalog()) {
            return new RuleResult("R13", name, Severity.WARNING, Outcome.FAIL, "Vendedor del catálogo", "Fuera del catálogo",
                    "El vendedor no está en el catálogo; su comisión no se puede asignar ni cerrar el mes.",
                    "Agregar el vendedor al catálogo");
        }
        if (f.registeredCommission() == null) {
            return new RuleResult("R13", name, Severity.WARNING, Outcome.NA, q(expected), "Aún sin registrar", "", "");
        }
        boolean ok = sameMoney(expected, f.registeredCommission());
        return new RuleResult("R13", name, Severity.WARNING, ok ? Outcome.PASS : Outcome.FAIL, q(expected), q(f.registeredCommission()),
                ok ? "" : "La comisión registrada difiere de la calculada: la venta cambió después de calcularla. No cambia lo que se espera recibir.",
                ok ? "" : "Recalcular la comisión del caso con motivo");
    }

    private RuleResult r14Invoice(CaseFacts f) {
        if (f.invoiceTotal() == null) {
            return new RuleResult("R14", "Factura FEL = cobrado", Severity.WARNING, Outcome.NA, "—", "Sin factura", "", "");
        }
        boolean ok = sameMoney(f.invoiceTotal(), f.expectedAmount());
        return new RuleResult("R14", "Factura FEL = cobrado", Severity.WARNING, ok ? Outcome.PASS : Outcome.FAIL,
                q(f.expectedAmount()), q(f.invoiceTotal()), ok ? "" : "El total de la factura no coincide con lo cobrado.",
                ok ? "" : "Revisar la factura FEL de la venta");
    }

    private RuleResult r15ReturnWithFunds(CaseFacts f, BigDecimal applied) {
        boolean closedSale = CLOSED_SALE_STATUS.contains(String.valueOf(f.saleStatus()));
        boolean fail = closedSale && applied.signum() > 0;
        return new RuleResult("R15", "Venta devuelta o anulada sin fondos", Severity.CRITICAL,
                closedSale ? (fail ? Outcome.FAIL : Outcome.PASS) : Outcome.NA, "Sin fondos", q(applied),
                fail ? "La venta está en " + f.saleStatus() + " y tiene fondos aplicados: puede faltar un reembolso." : "",
                fail ? "Revisar si el fondo debe reembolsarse o aplicarse a otra venta" : "");
    }

    private RuleResult r16NotOverpaid(CaseFacts f, BigDecimal applied, boolean hasReceipts) {
        if (!hasReceipts) {
            return new RuleResult("R16", "Fondos aplicados ≤ cobrado", Severity.CRITICAL, Outcome.NA, "≤ " + q(f.expectedAmount()), "—", "", "");
        }
        boolean over = applied.compareTo(money(f.expectedAmount())) > 0;
        return new RuleResult("R16", "Fondos aplicados ≤ cobrado", Severity.CRITICAL, over ? Outcome.FAIL : Outcome.PASS,
                "≤ " + q(f.expectedAmount()), q(applied), over ? "Se aplicó más dinero del cobrado." : "",
                over ? "Revisar si el fondo se aplicó a la venta correcta" : "");
    }

    private RuleResult r17ExchangeShipping(CaseFacts f, BigDecimal applied) {
        boolean exchange = money(f.expectedAmount()).signum() == 0;
        boolean fail = exchange && applied.signum() > 0;
        return new RuleResult("R17", "Cambio con cobro de envío registrado", Severity.CRITICAL,
                exchange ? (fail ? Outcome.FAIL : Outcome.PASS) : Outcome.NA, "Envío del cambio registrado", q(applied),
                fail ? "Cambio con cobro de envío no registrado: el transportista cobró y el cambio tiene total Q0.00." : "",
                fail ? "Editar el envío del cambio en Ventas Online (Q30 contra entrega, Q15 en otro caso)" : "");
    }
}
