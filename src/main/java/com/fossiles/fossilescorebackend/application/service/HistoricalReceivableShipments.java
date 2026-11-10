package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentDetailEntity;
import com.fossiles.fossilescorebackend.infrastructure.persistence.entity.ProductShipmentEntity;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Locale;

/**
 * Reconoce los «ENVIO HISTORICO» que el respaldo de cartera (backfill-historical-shipments-for-receivables.sql)
 * le creó a cada orden solo para que el cargo de cuentas por cobrar tuviera un envío.
 *
 * Nacen CONFIRMED y, desde backfill-envios-historicos-detalle.sql, traen como líneas TODA la orden. Mientras nadie
 * los pase a SENT no representan mercadería que salió, así que no deben restar del pendiente ni contarse como
 * despachado. La nota «ENVIO HISTORICO» se pierde al editar observaciones del envío; por eso también se reconocen
 * por la marca fija de created_at que ese relleno puso en sus líneas.
 */
public final class HistoricalReceivableShipments {

    static final String NOTES_PREFIX = "ENVIO HISTORICO";
    /** created_at fijo de las líneas insertadas por backfill-envios-historicos-detalle.sql. */
    static final LocalDateTime DETAIL_BACKFILL_MARK = LocalDateTime.of(2026, 10, 29, 0, 0, 0);

    private HistoricalReceivableShipments() {
    }

    /** Histórico de cartera, por la nota o porque todas sus líneas vienen del relleno. */
    public static boolean isHistorical(ProductShipmentEntity shipment, Collection<ProductShipmentDetailEntity> details) {
        if (shipment == null) {
            return false;
        }
        String notes = shipment.getNotes();
        if (notes != null && notes.trim().toUpperCase(Locale.ROOT).startsWith(NOTES_PREFIX)) {
            return true;
        }
        return details != null
                && !details.isEmpty()
                && details.stream().allMatch(d -> DETAIL_BACKFILL_MARK.equals(d.getCreatedAt()));
    }

    /** Histórico que nadie ha marcado como enviado: no salió nada de bodega por él. */
    public static boolean isUnsentHistorical(
            ProductShipmentEntity shipment, Collection<ProductShipmentDetailEntity> details) {
        if (shipment == null || shipment.getSentAt() != null) {
            return false;
        }
        String status = shipment.getStatus() == null ? "" : shipment.getStatus().trim().toUpperCase(Locale.ROOT);
        return "CONFIRMED".equals(status) && isHistorical(shipment, details);
    }
}
