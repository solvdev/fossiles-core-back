package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.application.service.reconciliation.CaseFacts.ReceiptFact;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Apoyo de pruebas. Por defecto: venta contra entrega de Forza con guía, productos Q450 + envío Q30 = Q480,
 * vendedor en el catálogo, sin fondos todavía.
 */
class CaseFactsBuilder {
    private Recon.Origin origin = Recon.Origin.NORMAL;
    private String saleStatus = "ENVIADO";
    private String shipment = "ENVL-01601";
    private String guide = "58123456789";
    private String carrier = "FORZA_DELIVERY";
    private String payment = "CONTRA_ENTREGA";
    private String auth;
    private BigDecimal expected = new BigDecimal("480.00");
    private BigDecimal net = new BigDecimal("450.00");
    private BigDecimal shipping = new BigDecimal("30.00");
    private BigDecimal currentSaleTotal;
    private BigDecimal invoiceTotal;
    private boolean sellerInCatalog = true;
    private BigDecimal registeredCommission;
    private List<ReceiptFact> receipts = List.of();
    private List<String> sameGuide = List.of();
    private List<String> sameReference = List.of();

    CaseFactsBuilder origin(Recon.Origin v) { origin = v; return this; }
    CaseFactsBuilder saleStatus(String v) { saleStatus = v; return this; }
    CaseFactsBuilder guide(String v) { guide = v; return this; }
    CaseFactsBuilder carrier(String v) { carrier = v; return this; }
    CaseFactsBuilder payment(String v) { payment = v; return this; }
    CaseFactsBuilder auth(String v) { auth = v; return this; }
    CaseFactsBuilder expected(String v) { expected = new BigDecimal(v); return this; }
    CaseFactsBuilder net(String v) { net = new BigDecimal(v); return this; }
    CaseFactsBuilder shipping(String v) { shipping = new BigDecimal(v); return this; }
    CaseFactsBuilder currentSaleTotal(String v) { currentSaleTotal = new BigDecimal(v); return this; }
    CaseFactsBuilder invoiceTotal(String v) { invoiceTotal = new BigDecimal(v); return this; }
    CaseFactsBuilder sellerInCatalog(boolean v) { sellerInCatalog = v; return this; }
    CaseFactsBuilder registeredCommission(String v) { registeredCommission = new BigDecimal(v); return this; }
    CaseFactsBuilder receipts(List<ReceiptFact> v) { receipts = v; return this; }
    CaseFactsBuilder sameGuide(List<String> v) { sameGuide = v; return this; }
    CaseFactsBuilder sameReference(List<String> v) { sameReference = v; return this; }

    CaseFacts build() {
        return new CaseFacts(origin, saleStatus, shipment, guide, carrier, payment, auth,
                LocalDate.of(2026, 10, 5), LocalDateTime.of(2026, 10, 6, 10, 0),
                expected, net, shipping, net, currentSaleTotal, invoiceTotal, sellerInCatalog,
                new BigDecimal("0.02"), registeredCommission, receipts, sameGuide, sameReference);
    }
}
