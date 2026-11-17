package com.fossiles.fossilescorebackend.application.util;

import com.fossiles.fossilescorebackend.application.util.InventoryRotationClassifier.Profile;
import com.fossiles.fossilescorebackend.application.util.InventoryRotationClassifier.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InventoryRotationClassifierTest {

    private static final Profile P = InventoryRotationClassifier.KIOSK; // 5 / 45 días

    private static BigDecimal n(int v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void ritmoDiarioYCobertura() {
        BigDecimal rate = InventoryRotationClassifier.dailyRate(n(45), 30);
        assertEquals(0, new BigDecimal("1.500").compareTo(rate));
        assertEquals(0, new BigDecimal("8.0").compareTo(InventoryRotationClassifier.coverageDays(n(12), rate)));
    }

    @Test
    void sinRitmoNoHayCobertura() {
        assertNull(InventoryRotationClassifier.coverageDays(n(10), BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, InventoryRotationClassifier.dailyRate(BigDecimal.ZERO, 30));
    }

    @Test
    void seAgotaCuandoAlcanzaMenosQueElUmbralCorto() {
        // 60 vendidas en 30 días = 2 por día; 4 de stock = 2 días.
        assertEquals(Status.SE_AGOTA, InventoryRotationClassifier.classify(n(4), n(60), 30, P));
    }

    @Test
    void normalEntreLosDosUmbrales() {
        // 45 en 30 días = 1.5 por día; 12 de stock = 8 días.
        assertEquals(Status.NORMAL, InventoryRotationClassifier.classify(n(12), n(45), 30, P));
    }

    @Test
    void lentoCuandoAlcanzaMasQueElUmbralLargo() {
        // 3 en 30 días = 0.1 por día; 90 de stock = 900 días.
        assertEquals(Status.LENTO, InventoryRotationClassifier.classify(n(90), n(3), 30, P));
    }

    @Test
    void sinMovimientoConStock() {
        assertEquals(Status.SIN_MOVIMIENTO, InventoryRotationClassifier.classify(n(20), BigDecimal.ZERO, 30, P));
    }

    @Test
    void sinStockPeroConVentaEsLaMasUrgente() {
        assertEquals(Status.SIN_STOCK, InventoryRotationClassifier.classify(BigDecimal.ZERO, n(10), 30, P));
        assertEquals(Status.SIN_STOCK, InventoryRotationClassifier.classify(n(-2), n(10), 30, P));
    }

    @Test
    void sinStockNiMovimientoNoEsFila() {
        assertNull(InventoryRotationClassifier.classify(BigDecimal.ZERO, BigDecimal.ZERO, 30, P));
    }

    @Test
    void losUmbralesSonExclusivosEnElBorde() {
        // 5 días justos con umbral corto 5: ya no es "se agota".
        assertEquals(Status.NORMAL, InventoryRotationClassifier.classify(n(5), n(30), 30, P));
        // 45 días justos con umbral lento 45: todavía normal.
        assertEquals(Status.NORMAL, InventoryRotationClassifier.classify(n(45), n(30), 30, P));
    }

    @Test
    void laUrgenciaOrdenaLoPrimeroLoCritico() {
        assertEquals(true, Status.SIN_STOCK.severity() < Status.SE_AGOTA.severity());
        assertEquals(true, Status.SE_AGOTA.severity() < Status.NORMAL.severity());
        assertEquals(true, Status.LENTO.severity() < Status.SIN_MOVIMIENTO.severity());
    }
}
