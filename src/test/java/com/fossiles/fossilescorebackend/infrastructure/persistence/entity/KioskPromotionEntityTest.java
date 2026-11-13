package com.fossiles.fossilescorebackend.infrastructure.persistence.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KioskPromotionEntityTest {

    @Test
    void sinKioscosAplicaATodos() {
        KioskPromotionEntity promo = new KioskPromotionEntity();
        assertTrue(promo.effectiveKioskIds().isEmpty());
        assertTrue(promo.appliesToKiosk(5L));
        assertTrue(promo.appliesToKiosk(null));
    }

    @Test
    void kioscoUnicoViejoSeRespetaSiNoHayLista() {
        KioskPromotionEntity promo = new KioskPromotionEntity();
        promo.setKioskLocationId(7L);
        assertEquals(Set.of(7L), promo.effectiveKioskIds());
        assertTrue(promo.appliesToKiosk(7L));
        assertFalse(promo.appliesToKiosk(8L));
        assertFalse(promo.appliesToKiosk(null));
    }

    @Test
    void listaDeKioscosGuardaYRespeta() {
        KioskPromotionEntity promo = new KioskPromotionEntity();
        promo.assignKiosks(List.of(3L, 7L, 12L));
        assertEquals("3,7,12", promo.getKioskLocationIds());
        assertNull(promo.getKioskLocationId());
        assertTrue(promo.appliesToKiosk(7L));
        assertFalse(promo.appliesToKiosk(4L));
    }

    @Test
    void unSoloKioscoTambienLlenaLaColumnaVieja() {
        KioskPromotionEntity promo = new KioskPromotionEntity();
        promo.assignKiosks(List.of(9L));
        assertEquals("9", promo.getKioskLocationIds());
        assertEquals(9L, promo.getKioskLocationId());
    }

    @Test
    void vaciarLaListaVuelveATodos() {
        KioskPromotionEntity promo = new KioskPromotionEntity();
        promo.assignKiosks(List.of(3L, 7L));
        promo.assignKiosks(List.of());
        assertNull(promo.getKioskLocationIds());
        assertNull(promo.getKioskLocationId());
        assertTrue(promo.appliesToKiosk(99L));
    }

    @Test
    void idsInvalidosSeIgnoran() {
        assertEquals(Set.of(1L, 2L), KioskPromotionEntity.parseKioskIds(" 1, x,2 ,,"));
    }
}
