package com.fossiles.fossilescorebackend.application.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una fila del listado de Órdenes de Producción.
 *
 * <p>Deliberadamente ligero: no trae {@code items}. Lo que la pantalla calculaba
 * recorriendo los ítems en el navegador —cantidad total, recibido y etapa del proceso—
 * viene ya resuelto desde el servidor.
 *
 * <p>No sustituye a {@code ProductionOrderResponse}, que lo comparten el detalle, el
 * formulario de edición y la impresión, y que sí necesita los ítems completos.
 *
 * <p><b>Sobre {@code totalQty}:</b> en los ítems de cincho la cantidad no está en la
 * columna {@code quantity} —que viene nula en la práctica totalidad de ellos— sino dentro
 * del JSON de {@code sizes_data}. La consulta suma las tallas cuando existen y cae a
 * {@code quantity} cuando no, que es la misma regla que aplicaba el navegador.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductionOrderListItemResponse {

    private Long id;
    private String code;
    private String orderType;
    /** Familia comercial ya resuelta: OPL | OPK | OPV | OPI | OPCK | OPD | OPC. */
    private String family;
    private String customerName;
    private String sellerName;
    private String distributionNumber;
    private LocalDate startDate;
    private LocalDate deliveryDate;
    private LocalDateTime createdAt;
    private String status;
    private String observations;

    /** Suma de lo planificado (tallas si las hay, si no {@code quantity}). */
    private long totalQty;
    /** Suma de lo recibido en bodega, recortado a lo planificado por ítem. */
    private long receivedQty;
    /** Lo que falta por recibir; nunca negativo. */
    private long pendingQty;
    /** Avance en porcentaje entero, 0 cuando no hay nada planificado. */
    private int progressPct;
    private int itemCount;

    /** Etapa del proceso resuelta en servidor. */
    private ProcessStage processStage;

    /**
     * Misma forma que el objeto que la pantalla construía en el navegador, para que el
     * badge no cambie de contrato.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessStage {
        private String key;
        private String label;
        private String color;
    }
}
