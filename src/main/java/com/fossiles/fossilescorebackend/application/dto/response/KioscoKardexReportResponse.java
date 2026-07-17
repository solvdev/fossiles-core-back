package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Kardex de inventario kiosco por periodo: inventario inicial, movimientos clasificados
 * en las categorias operativas del kiosko y inventario final, por producto/color.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KioscoKardexReportResponse {
    private Long locationId;
    private String locationCode;
    private String locationName;
    private LocalDate from;
    private LocalDate to;
    private List<KioscoKardexRow> rows;
    private KioscoKardexRow totals;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KioscoKardexRow {
        private Long productId;
        private String productCode;
        private String productName;
        private Long colorId;
        private String colorName;
        /** DAMA, CABALLERO o UNISEX */
        private String audienceCategory;
        /** CASUAL o REVERSIBLE */
        private String cinchoType;
        private int inventarioInicial;
        /** Ingresos por correccion de inventario, boletas de cambio y ajustes positivos. */
        private int comprasAjustes;
        /** Correccion de un ajuste mal hecho en compras/ajustes. */
        private int anulacionCompras;
        /** Envios de distribucion bodega->kiosco y traslados entrantes entre sucursales. */
        private int entradas;
        private int ventas;
        /** Regresa a inventario el valor de una venta anulada. */
        private int anulacionVenta;
        /** Devoluciones a oficina, traslados salientes entre sucursales y merma. */
        private int salida;
        /** Subconjunto de salida: devoluciones a bodega / reintegros del periodo. */
        private int salidaDevolucion;
        private int inventarioFinal;
    }
}
