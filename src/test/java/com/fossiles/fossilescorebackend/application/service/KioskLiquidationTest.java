package com.fossiles.fossilescorebackend.application.service;

import com.fossiles.fossilescorebackend.application.service.KioskLiquidationPlanner.Pending;
import com.fossiles.fossilescorebackend.application.service.KioskLiquidationPlanner.Slot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KioskLiquidationTest {

    private static final List<LocalDate> WEEK = List.of(
            LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 9),
            LocalDate.of(2026, 12, 10), LocalDate.of(2026, 12, 11));

    @Test
    void unKioscoPorDiaDelMasViejoAlMasReciente() {
        List<Pending> pending = List.of(
                new Pending(1, LocalDate.of(2026, 10, 20), 1), new Pending(2, LocalDate.of(2026, 10, 22), 1),
                new Pending(3, LocalDate.of(2026, 10, 25), 1), new Pending(4, LocalDate.of(2026, 10, 28), 1),
                new Pending(5, LocalDate.of(2026, 11, 2), 1), new Pending(6, LocalDate.of(2026, 11, 4), 1));
        List<Slot> slots = KioskLiquidationPlanner.assign(WEEK, Set.of(), pending, 14);
        assertEquals(5, slots.size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), slots.stream().map(Slot::locationId).toList());
        assertEquals(5, new HashSet<>(slots.stream().map(Slot::date).toList()).size(), "un kiosco por día");
    }

    @Test
    void noLiquidaUnConteoRecienteNiPisaDiasOcupados() {
        List<Pending> pending = List.of(
                new Pending(1, LocalDate.of(2026, 10, 20), 1),
                new Pending(2, LocalDate.of(2026, 12, 1), 1)); // muy reciente: espera 14 días
        List<Slot> slots = KioskLiquidationPlanner.assign(WEEK, Set.of(WEEK.get(0)), pending, 14);
        assertEquals(1, slots.size());
        assertEquals(WEEK.get(1), slots.get(0).date(), "el lunes ya estaba ocupado");
    }

    @Test
    void entrecuerosYCelajesOcupanDosDiasSeguidos() {
        List<Pending> pending = List.of(new Pending(9, LocalDate.of(2026, 10, 20), 2), new Pending(1, LocalDate.of(2026, 10, 25), 1));
        List<Slot> slots = KioskLiquidationPlanner.assign(WEEK, Set.of(), pending, 14);
        assertEquals(2, slots.get(0).days());
        assertEquals(WEEK.get(0), slots.get(0).date());
        assertEquals(WEEK.get(2), slots.get(1).date(), "el siguiente kiosco va después de los dos días");
    }

    @Test
    void entiendeLosTextosDeFacturasVerdes() {
        assertEquals(0, KioskCountImportService.parseAreasToFix("NO TIENE AREAS PARA CORREGIR"));
        assertEquals(0, KioskCountImportService.parseAreasToFix("NINGUNA AREA PARA CORREGIR"));
        assertEquals(0, KioskCountImportService.parseAreasToFix("SIN AREAS A CORREGIR"));
        assertEquals(1, KioskCountImportService.parseAreasToFix("UNA AREA PARA CORREGIR"));
        assertEquals(3, KioskCountImportService.parseAreasToFix("TREAS AREAS PARA CORREGIR"));
        assertEquals(5, KioskCountImportService.parseAreasToFix("5 AREAS PARA CORREGIR"));
        assertEquals(4, KioskCountImportService.parseAreasToFix("4 AREAS PARA MODIFICAR"));
        assertEquals(7, KioskCountImportService.parseAreasToFix("SIETE AREAS PARA CORREGIR"));
        assertNull(KioskCountImportService.parseAreasToFix("PENDIENTE"));
        assertNull(KioskCountImportService.parseAreasToFix(null));
    }

    @Test
    void normalizaNombresDeKioscosDelExcel() {
        assertEquals("SANTAL", KioskCountImportService.normalize("SANTAL�"));
        assertEquals("SANTALU", KioskCountImportService.normalize("Santalú"));
        assertEquals("PERI ROOSEVELT", KioskCountImportService.normalize("PERI-ROOSEVELT"));
        assertEquals("SAN LUCAS", KioskCountImportService.normalize("SAN LUCAS "));
        assertEquals("MIRAFLORES 2", KioskCountImportService.normalize("Miraflores 2"));
        assertTrue(KioskCountImportService.normalize(null).isEmpty());
    }
}
