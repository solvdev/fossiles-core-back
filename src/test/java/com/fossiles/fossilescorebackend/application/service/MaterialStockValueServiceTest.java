package com.fossiles.fossilescorebackend.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterialStockValueServiceTest {

    private static MaterialStockValueService.Line line(String name, String supplier, String qty, String cost) {
        return new MaterialStockValueService.Line(name, supplier,
                qty == null ? null : new BigDecimal(qty), cost == null ? null : new BigDecimal(cost));
    }

    @Test
    void groupsBandolaAndOthersSkippingLeather() {
        var report = MaterialStockValueService.group(List.of(
                line("Hebilla dorada", "LA BANDOLA DE ORO, S.A.", "10", "2.50"),
                line("Remache", "la bándola de oro", "100", "0.10"),
                line("Hilo", "Distribuidora X", "4", "12"),
                line("Pegamento", null, "1", "30"),
                line("Cuero vaqueta", "La Bandola de Oro", "50", "20"),
                line("CUERO sintético", "Otro", "5", "8")));
        assertEquals(2, report.getGroups().size());
        assertEquals("La Bandola de Oro", report.getGroups().get(0).getName());
        assertEquals(new BigDecimal("35.00"), report.getGroups().get(0).getValue());
        assertEquals(2, report.getGroups().get(0).getMaterials());
        assertEquals("Otros", report.getGroups().get(1).getName());
        assertEquals(new BigDecimal("78.00"), report.getGroups().get(1).getValue());
        assertEquals(new BigDecimal("113.00"), report.getTotalValue());
    }

    @Test
    void zeroOrNegativeStockAndEmptyGroupsAreLeftOut() {
        var report = MaterialStockValueService.group(List.of(
                line("Hebilla", "Bandola de Oro", "-3", "2"),
                line("Hilo", "X", "0", "5"),
                line("Broche", "X", "2", null),
                line("Ojal", "X", "3", "1")));
        assertEquals(1, report.getGroups().size());
        assertEquals("Otros", report.getGroups().get(0).getName());
        assertEquals(new BigDecimal("3.00"), report.getTotalValue());
    }
}
