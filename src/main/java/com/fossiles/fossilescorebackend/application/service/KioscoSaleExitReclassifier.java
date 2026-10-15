package com.fossiles.fossilescorebackend.application.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Devuelve a VENTA la salida de una venta POS que se registró sin factura cuando esa venta termina facturada
 * (corrección manual de datos FEL o reintento de certificación sobre el borrador).
 *
 * <p>Solo depende de {@link EntityManager}: lo usa {@code TaxInvoiceService}, que no puede inyectar
 * {@code KioscoInventoryService} sin riesgo de dependencia circular.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KioscoSaleExitReclassifier {

    private static final String ADMIN_MOVEMENT_MUTATION_KEY = "app.kiosco_movement_admin_mutation";

    private final EntityManager entityManager;

    /**
     * Los movimientos {@code SALIDA} con motivo "Venta sin factura ..." de la venta pasan a {@code VENTA}.
     * No cambia cantidades ni stock. Devuelve cuántos movimientos reclasificó.
     *
     * <p>Corre en su propia transacción: si falla no debe arrastrar la certificación FEL que la disparó. Sin
     * movimientos que reclasificar (lo normal) solo hace una consulta y no toca la protección append-only.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int restoreAsSale(Long kioskLocationId, Long saleId) {
        if (kioskLocationId == null || saleId == null) {
            return 0;
        }
        String reasonPrefix = KioscoInventoryService.REASON_SALIDA_VENTA_SIN_FACTURA + "%";
        String scope = "WHERE movement_type = 'SALIDA' "
                + "AND reference_id = :saleId "
                + "AND reason LIKE :reasonPrefix "
                + "AND kiosco_stock_id IN (SELECT s.id FROM kiosco_stock s WHERE s.location_id = :locationId)";
        Number pending = (Number) entityManager.createNativeQuery("SELECT COUNT(*) FROM kiosco_movement " + scope)
                .setParameter("saleId", saleId)
                .setParameter("reasonPrefix", reasonPrefix)
                .setParameter("locationId", kioskLocationId)
                .getSingleResult();
        if (pending == null || pending.longValue() == 0) {
            return 0;
        }
        setAdminMutation(true);
        try {
            int updated = entityManager.createNativeQuery(
                            "UPDATE kiosco_movement SET movement_type = 'VENTA', reason = NULL " + scope)
                    .setParameter("saleId", saleId)
                    .setParameter("reasonPrefix", reasonPrefix)
                    .setParameter("locationId", kioskLocationId)
                    .executeUpdate();
            if (updated > 0) {
                log.info("KIOSCO_SALIDA_A_VENTA saleId={} locationId={} movimientos={} (la venta se facturó)",
                        saleId, kioskLocationId, updated);
            }
            return updated;
        } finally {
            setAdminMutation(false);
        }
    }

    private void setAdminMutation(boolean enabled) {
        entityManager.createNativeQuery("SELECT set_config(:key, :value, false)")
                .setParameter("key", ADMIN_MOVEMENT_MUTATION_KEY)
                .setParameter("value", enabled ? "true" : "false")
                .getSingleResult();
    }
}
