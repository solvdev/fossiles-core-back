package com.fossiles.fossilescorebackend.application.service.reconciliation;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.recon.Recon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Todo lo que el motor de reglas necesita saber de un caso. Se arma en el servicio a partir de la base;
 * así el motor no depende de ella y se prueba con datos en memoria.
 */
public record CaseFacts(
        Recon.Origin origin,
        String saleStatus,
        String shipmentNumber,
        String guideNumber,
        String carrier,
        String paymentMethod,
        String paymentAuthorization,
        LocalDate saleDate,
        LocalDateTime dispatchedAt,
        BigDecimal expectedAmount,
        BigDecimal netAmount,
        BigDecimal shippingCost,
        /** Suma de las líneas de la venta; null si no se conoce. */
        BigDecimal itemsSubtotal,
        /** total_amount actual de la venta (puede haber cambiado después de crear el caso); null si no se conoce. */
        BigDecimal currentSaleTotal,
        /** Total de la factura FEL asociada; null si no hay. */
        BigDecimal invoiceTotal,
        boolean sellerInCatalog,
        BigDecimal commissionRate,
        /** Comisión ya registrada en recon_commission_line; null si todavía no existe. */
        BigDecimal registeredCommission,
        List<ReceiptFact> receipts,
        /** ENVL de otros casos con la misma guía. */
        List<String> otherCasesWithSameGuide,
        /** ENVL de otros casos cuya venta usa la misma referencia de pago. */
        List<String> otherCasesWithSameReference) {

    public record ReceiptFact(
            Long receiptId,
            Recon.SourceType sourceType,
            String carrier,
            String guideNumber,
            String reference,
            String authorizationNumber,
            BigDecimal amountApplied,
            BigDecimal shippingAmount,
            LocalDate receivedDate) {
    }

    public BigDecimal totalApplied() {
        BigDecimal sum = BigDecimal.ZERO;
        for (ReceiptFact r : receipts) {
            if (r.amountApplied() != null) sum = sum.add(r.amountApplied());
        }
        return ReconText.money(sum);
    }
}
